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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Screen { CHAT, FILES, TERMINAL, MEMORY, SETTINGS }

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val attachments: List<AttachmentRef>) : ChatItem
    data class Assistant(override val key: String, val text: String, val reasoning: String) : ChatItem
    data class Tool(
        override val key: String, val name: String, val args: JsonObject,
        val output: String?, val status: String,   // running | awaiting | done
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
            val last = prefs.lastSession
            if (currentSessionId == null) {
                if (last.isNotBlank() && sessions.any { it.id == last }) openSession(last) else newChat()
            }
        } catch (e: Exception) {
            connectionError = e.message ?: "Cannot reach server"
        }
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
                "user" -> out += ChatItem.User(nextKey(), m.content.orEmpty(), m.attachments)
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
                    if (i != null) out[i] = (out[i] as ChatItem.Tool).copy(output = m.content, status = "done")
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
        draft = ""
        pendingAttachments.clear()
        items += ChatItem.User(nextKey(), text, atts)
        streaming = true
        streamJob = viewModelScope.launch {
            try {
                val sid = currentSessionId ?: a.createSession(currentModel).id.also {
                    currentSessionId = it
                    prefs.lastSession = it
                }
                consume(a.chat(sid, text, atts.map { it.id }, currentModel.ifBlank { null }))
            } catch (e: Exception) {
                items += ChatItem.Notice(nextKey(), e.message ?: e.toString(), error = true)
            } finally {
                streaming = false
            }
        }
    }

    private suspend fun consume(events: kotlinx.coroutines.flow.Flow<JsonObject>) {
        streaming = true
        try {
            events.collect { handle(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            items += ChatItem.Notice(nextKey(), "Connection lost: ${e.message}. Reopen the chat to resume.", true)
        } finally {
            streaming = false
            runCatching { loadSessions() }
        }
    }

    private fun str(ev: JsonObject, k: String) = ev[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun handle(ev: JsonObject) {
        when (str(ev, "type")) {
            "delta", "reasoning" -> {
                val isReasoning = str(ev, "type") == "reasoning"
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
                updateTool(id) { it.copy(output = str(ev, "output"), status = "done") }
            }
            "title" -> currentTitle = str(ev, "title")
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
