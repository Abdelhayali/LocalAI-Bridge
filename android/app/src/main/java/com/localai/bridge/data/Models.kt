package com.localai.bridge.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionDto(
    val id: String,
    val title: String = "New chat",
    val model: String? = "",
    val updated: Double = 0.0,
    val running: Boolean = false,
)

@Serializable
data class AttachmentRef(
    val id: String,
    val filename: String = "",
    val kind: String = "file",
    val mime: String = "",
    val size: Long = 0,
)

@Serializable
data class ToolFunction(val name: String = "", val arguments: String = "{}")

@Serializable
data class ToolCallDto(val id: String = "", val function: ToolFunction = ToolFunction())

@Serializable
data class MessageDto(
    val id: Long = 0,
    val role: String,
    val content: String? = "",
    val reasoning: String? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class PendingApproval(val id: String, val name: String, val args: JsonObject = JsonObject(emptyMap()))

@Serializable
data class SessionStats(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    val tps: Double? = null,
    @SerialName("prompt_tps") val promptTps: Double? = null,
    @SerialName("ctx_used") val ctxUsed: Long? = null,
    @SerialName("ctx_size") val ctxSize: Long? = null,
    val seconds: Double? = null,
)

@Serializable
data class SessionDetail(
    val id: String,
    val title: String = "",
    val model: String? = "",
    val running: Boolean = false,
    val messages: List<MessageDto> = emptyList(),
    @SerialName("pending_approvals") val pendingApprovals: List<PendingApproval> = emptyList(),
    val stats: SessionStats? = null,
)

@Serializable
data class ServerInfo(
    @SerialName("llm_base_url") val llmBaseUrl: String = "",
    @SerialName("llm_ok") val llmOk: Boolean = false,
    val models: List<String> = emptyList(),
    @SerialName("default_model") val defaultModel: String = "",
    @SerialName("allowed_roots") val allowedRoots: List<String> = emptyList(),
    val workspace: String = "",
    @SerialName("code_exec") val codeExec: Boolean = false,
    @SerialName("require_approval") val requireApproval: Boolean = true,
)

@Serializable
data class FsEntry(
    val name: String,
    val path: String,
    @SerialName("is_dir") val isDir: Boolean,
    val size: Long = 0,
    val modified: Double = 0.0,
)

@Serializable
data class FsListing(val path: String, val parent: String? = null, val entries: List<FsEntry> = emptyList())

@Serializable
data class FsRoots(val roots: List<String> = emptyList(), val workspace: String = "")

@Serializable
data class ExecResult(
    @SerialName("exit_code") val exitCode: Int = 0,
    @SerialName("timed_out") val timedOut: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
)

@Serializable
data class MemoryDto(val id: Long, val content: String, val created: Double = 0.0)

@Serializable
data class PairPayload(val url: String, val token: String)
