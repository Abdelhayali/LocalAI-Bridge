package com.localai.bridge.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localai.bridge.data.Api
import com.localai.bridge.data.AttachmentRef
import com.localai.bridge.data.MessageDto
import com.localai.bridge.data.PairPayload
import com.localai.bridge.data.PendingApproval
import com.localai.bridge.data.Prefs
import com.localai.bridge.data.ServerInfo
import com.localai.bridge.data.SessionDto
import com.localai.bridge.data.SessionStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Screen { CHAT, FILES, TERMINAL, MEMORY, SETTINGS }

sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String, val text: String, val attachments: List<AttachmentRef>,
        val messageId: Long? = null,   // server id; needed to edit the message
    ) : ChatItem
    data class Assistant(override val key: String, val text: String, val reasoning: String) : ChatItem
    data class Tool(
        override val key: String, val name: String, val args: JsonObject,
        val output: String?, val status: String,   // running | awaiting | done
        val attachments: List<AttachmentRef> = emptyList(),  // images/files the tool sent to the chat
    ) : ChatItem
    data class Notice(override val key: String, val text: String, val error: Boolean = false) : ChatItem
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    var api: Api? = if (prefs.isPaired) Api(prefs.serverUrl, prefs.token) else null
        private set

    var paired by mutableStateOf(prefs.isPaired)
    var screen by mutableStateOf(Screen.CHAT)
    var info by mutableStateOf<ServerInfo?>(null)
    var connectionError by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)

    val sessions = mutableStateListOf<SessionDto>()
    var currentSessionId by mutableStateOf<String?>(null)
    var currentTitle by mutableStateOf("New chat")
    var currentModel by mutableStateOf("")
    val items = mutableStateListOf<ChatItem>()
    var streaming by mutableStateOf(false)
    var loadingSession by mutableStateOf(false)
    val approvals = mutableStateListOf<PendingApproval>()

    val pendingAttachments = mutableStateListOf<AttachmentRef>()
    var uploading by mutableStateOf(false)
    var draft by mutableStateOf("")
    var editingMessageId by mutableStateOf<Long?>(null)
    var themeMode by mutableStateOf(prefs.themeMode)   // system | light | dark
    var webSearch by mutableStateOf(prefs.webSearch)   // give the model internet search
    var autoApprove by mutableStateOf(prefs.autoApprove)   // run code / write files without asking
    private var turnCompleted = false

    // live status of a running reply (shown above the input while streaming)
    var stats by mutableStateOf<SessionStats?>(null)       // last context / speed numbers of this session
    var phase by mutableStateOf("")                         // sending|prompt|thinking|writing|tool_args|tool|approval
    var phaseTool by mutableStateOf("")
    var liveTokens by mutableStateOf(0)
    var liveTps by mutableStateOf<Double?>(null)
    var streamStartMs by mutableStateOf(0L)
    var lastEventMs by mutableStateOf(0L)
    private var startupDone = false

    private var streamJob: Job? = null
    private var keyCounter = 0
    private fun nextKey() = "k${keyCounter++}"

    init {
        if (paired) refreshAll()
    }

    // ---------------------------------------------------------------- pairing
    suspend fun pair(url: String, token: String): String? {
        val clean = url.trim().trimEnd('/')
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) return "URL must start with https://"
        val test = Api(clean, token.trim())
        return try {
            info = test.info()
            prefs.serverUrl = clean
            prefs.token = token.trim()
            api = test
            paired = true
            refreshAll()
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    fun parsePairQr(text: String): PairPayload? =
        runCatching { Api("http://x", "").json.decodeFromString<PairPayload>(text) }.getOrNull()

    fun unpair() {
        streamJob?.cancel()
        prefs.clear()
        prefs.themeMode = themeMode
        prefs.webSearch = webSearch
        prefs.autoApprove = false
        startupDone = false
        autoApprove = false
        api = null
        paired = false
        sessions.clear(); items.clear(); info = null; currentSessionId = null
    }

    // ---------------------------------------------------------------- sessions
    fun refreshAll() = viewModelScope.launch {
        val a = api ?: return@launch
        try {
            info = a.info()
            connectionError = null
            loadSessions()
            // Only on the first successful connection: reopen the last chat. Later refreshes (e.g. from
            // Settings) must not navigate away from the current screen.
            if (!startupDone) {
                startupDone = true
                val last = prefs.lastSession
                if (currentSessionId == null && last.isNotBlank() && sessions.any { it.id == last }) openSession(last)
                else if (currentSessionId == null) currentModel = info?.defaultModel.orEmpty()
            }
        } catch (e: Exception) {
            connectionError = e.message ?: "Cannot reach server"
        }
    }

    /** Re-fetch the model list from the PC (the LLM server may have loaded a different model). */
    fun refreshModels() = viewModelScope.launch {
        try { info = api?.info() } catch (e: Exception) { toast = e.message }
    }

    suspend fun loadSessions() {
        val list = api?.sessions() ?: return
        sessions.clear(); sessions.addAll(list)
    }

    fun newChat() {
        streamJob?.cancel()
        streaming = false
        currentSessionId = null
        currentTitle = "New chat"
        currentModel = info?.defaultModel.orEmpty()
        stats = null
        items.clear(); approvals.clear()
        screen = Screen.CHAT
    }

    fun openSession(id: String) = viewModelScope.launch {
        val a = api ?: return@launch
        streamJob?.cancel()
        streaming = false
        screen = Screen.CHAT
        loadingSession = true
        try {
            val s = a.session(id)
            currentSessionId = s.id
            currentTitle = s.title
            stats = s.stats
            currentModel = s.model?.takeIf { it.isNotBlank() } ?: info?.defaultModel.orEmpty()
            prefs.lastSession = s.id
            items.clear(); items.addAll(toItems(s.messages))
            approvals.clear(); approvals.addAll(s.pendingApprovals)
            if (s.running) {
                // the reply is still generating on the PC: re-attach to its stream
                trimToLastUser()
                approvals.clear()
                streamJob = viewModelScope.launch { consume(a.resume(s.id)) }
            }
        } catch (e: Exception) {
            toast = e.message
        } finally {
            loadingSession = false
        }
    }

    /** When resuming, the replayed events re-create everything after the last user message. */
    private fun trimToLastUser() {
        val idx = items.indexOfLast { it is ChatItem.User }
        if (idx >= 0) while (items.size > idx + 1) items.removeAt(items.size - 1)
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        try {
            api?.deleteSession(id)
            sessions.removeAll { it.id == id }
            if (id == currentSessionId) newChat()
        } catch (e: Exception) { toast = e.message }
    }

    fun renameSession(id: String, title: String) = viewModelScope.launch {
        try {
            api?.renameSession(id, title)
            if (id == currentSessionId) currentTitle = title
            loadSessions()
        } catch (e: Exception) { toast = e.message }
    }

    fun selectModel(model: String) {
        currentModel = model
        val id = currentSessionId ?: return
        viewModelScope.launch { runCatching { api?.setSessionModel(id, model) } }
    }

    private fun toItems(msgs: List<MessageDto>): List<ChatItem> {
        val out = mutableListOf<ChatItem>()
        val toolIndex = mutableMapOf<String, Int>()
        for (m in msgs) {
            when (m.role) {
                "user" -> out += ChatItem.User(nextKey(), m.content.orEmpty(), m.attachments, m.id)
                "assistant" -> {
                    if (!m.content.isNullOrBlank() || !m.reasoning.isNullOrBlank())
                        out += ChatItem.Assistant(nextKey(), m.content.orEmpty(), m.reasoning.orEmpty())
                    for (tc in m.toolCalls) {
                        val args = runCatching {
                            api!!.json.parseToJsonElement(tc.function.arguments).jsonObject
                        }.getOrDefault(JsonObject(emptyMap()))
                        toolIndex[tc.id] = out.size
                        out += ChatItem.Tool(tc.id, tc.function.name, args, null, "running")
                    }
                }
                "tool" -> {
                    val i = toolIndex[m.toolCallId]
                    if (i != null) out[i] = (out[i] as ChatItem.Tool).copy(
                        output = m.content, status = "done", attachments = m.attachments)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- chat
    fun send() {
        val a = api ?: return
        val text = draft.trim()
        if ((text.isEmpty() && pendingAttachments.isEmpty()) || streaming || uploading) return
        val atts = pendingAttachments.toList()
        val editId = editingMessageId
        draft = ""
        pendingAttachments.clear()
        editingMessageId = null
        if (editId != null) {
            val idx = items.indexOfFirst { it is ChatItem.User && it.messageId == editId }
            if (idx >= 0) while (items.size > idx) items.removeAt(items.size - 1)
        }
        items += ChatItem.User(nextKey(), text, atts)
        streaming = true
        streamStartMs = System.currentTimeMillis()
        phase = "sending"
        streamJob = viewModelScope.launch {
            try {
                val sid = currentSessionId ?: a.createSession(currentModel).id.also {
                    currentSessionId = it
                    prefs.lastSession = it
                }
                if (editId != null) a.truncate(sid, editId)
                consume(a.chat(sid, text, atts.map { it.id }, currentModel.ifBlank { null }, webSearch, autoApprove))
            } catch (e: Exception) {
                items += ChatItem.Notice(nextKey(), e.message ?: e.toString(), error = true)
            } finally {
                streaming = false
            }
        }
    }

    private suspend fun consume(events: kotlinx.coroutines.flow.Flow<JsonObject>) {
        streaming = true
        turnCompleted = false
        val now = System.currentTimeMillis()
        if (streamStartMs == 0L) streamStartMs = now
        lastEventMs = now
        if (phase.isEmpty()) phase = "sending"
        try {
            events.collect { handle(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            items += ChatItem.Notice(nextKey(), "Connection lost: ${e.message}. Reopen the chat to resume.", true)
        } finally {
            streaming = false
            phase = ""; streamStartMs = 0L; liveTokens = 0; liveTps = null
            runCatching { loadSessions() }
        }
        if (turnCompleted) syncFromServer()
    }

    /** Reload the finished conversation so every message has its server id (needed for editing). */
    private suspend fun syncFromServer() {
        val sid = currentSessionId ?: return
        runCatching {
            val s = api!!.session(sid)
            if (!s.running && !streaming) { items.clear(); items.addAll(toItems(s.messages)) }
        }
    }

    private fun str(ev: JsonObject, k: String) = ev[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun handle(ev: JsonObject) {
        lastEventMs = System.currentTimeMillis()
        when (str(ev, "type")) {
            "phase" -> {
                phase = str(ev, "phase"); phaseTool = str(ev, "name")
                if (phase == "prompt") { liveTokens = 0; liveTps = null }
            }
            "progress" -> {
                phase = str(ev, "phase")
                liveTokens = ev["tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: liveTokens
                liveTps = ev["tps"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: liveTps
            }
            "stats" -> stats = runCatching { api!!.json.decodeFromJsonElement<SessionStats>(ev) }.getOrNull() ?: stats
            "delta", "reasoning" -> {
                val isReasoning = str(ev, "type") == "reasoning"
                if (phase == "prompt" || phase == "sending") phase = if (isReasoning) "thinking" else "writing"
                val last = items.lastOrNull()
                val cur = if (last is ChatItem.Assistant) last else ChatItem.Assistant(nextKey(), "", "").also { items += it }
                val upd = if (isReasoning) cur.copy(reasoning = cur.reasoning + str(ev, "text"))
                else cur.copy(text = cur.text + str(ev, "text"))
                items[items.lastIndex] = upd
            }
            "tool_call" -> items += ChatItem.Tool(
                str(ev, "id"), str(ev, "name"), ev["args"]?.jsonObject ?: JsonObject(emptyMap()), null, "running")
            "approval_required" -> {
                val id = str(ev, "id")
                updateTool(id) { it.copy(status = "awaiting") }
                approvals += PendingApproval(id, str(ev, "name"), ev["args"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            "tool_result" -> {
                val id = str(ev, "id")
                approvals.removeAll { it.id == id }
                val atts = runCatching {
                    api!!.json.decodeFromJsonElement<List<AttachmentRef>>(ev["attachments"]!!)
                }.getOrDefault(emptyList())
                updateTool(id) { it.copy(output = str(ev, "output"), status = "done", attachments = atts) }
            }
            "title" -> currentTitle = str(ev, "title")
            "done" -> turnCompleted = true
            "notice" -> items += ChatItem.Notice(nextKey(), str(ev, "text"))
            "error" -> items += ChatItem.Notice(nextKey(), str(ev, "message"), error = true)
        }
    }

    private fun updateTool(id: String, f: (ChatItem.Tool) -> ChatItem.Tool) {
        val i = items.indexOfFirst { it is ChatItem.Tool && it.key == id }
        if (i >= 0) items[i] = f(items[i] as ChatItem.Tool)
    }

    fun respondApproval(p: PendingApproval, ok: Boolean) = viewModelScope.launch {
        approvals.remove(p)
        if (ok) updateTool(p.id) { it.copy(status = "running") }
        try { api?.approve(p.id, ok) } catch (e: Exception) { toast = e.message }
    }

    fun stop() = viewModelScope.launch {
        currentSessionId?.let { runCatching { api?.stop(it) } }
        approvals.clear()
    }

    // ---------------------------------------------------------------- edit / regenerate / theme
    fun startEdit(item: ChatItem.User) {
        if (streaming) return
        if (item.messageId == null) { toast = "Wait a moment - message is still syncing"; return }
        editingMessageId = item.messageId
        draft = item.text
        pendingAttachments.clear(); pendingAttachments.addAll(item.attachments)
    }

    fun cancelEdit() {
        editingMessageId = null
        draft = ""
        pendingAttachments.clear()
    }

    /** Re-run the last user message to get a new answer. */
    fun regenerate() {
        val last = items.lastOrNull { it is ChatItem.User } as? ChatItem.User ?: return
        startEdit(last)
        if (editingMessageId != null) send()
    }

    fun toggleWebSearch() {
        webSearch = !webSearch
        prefs.webSearch = webSearch
        toast = if (webSearch) "Web search ON" else "Web search OFF"
    }

    fun updateAutoApprove(on: Boolean) {
        autoApprove = on
        prefs.autoApprove = on
    }

    fun setTheme(mode: String) {
        themeMode = mode
        prefs.themeMode = mode
    }

    /** Capture the PC screen and attach it to the next message. */
    fun attachScreenshot() = viewModelScope.launch {
        val a = api ?: return@launch
        uploading = true
        try { pendingAttachments += a.screenshot() } catch (e: Exception) { toast = "Screenshot failed: ${e.message}" }
        uploading = false
    }

    // ---------------------------------------------------------------- attachments
    fun attach(uris: List<Uri>, nameOverride: String? = null) = viewModelScope.launch {
        val a = api ?: return@launch
        uploading = true
        try {
            for (u in uris) pendingAttachments += a.upload(getApplication<Application>().contentResolver, u, nameOverride)
        } catch (e: Exception) {
            toast = "Upload failed: ${e.message}"
        } finally {
            uploading = false
        }
    }
}
