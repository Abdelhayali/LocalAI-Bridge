package com.localai.bridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.localai.bridge.data.AttachmentRef
import com.localai.bridge.data.PendingApproval
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var attachMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraUri?.let { if (ok) vm.attach(listOf(Uri.parse(it)), "photo_${System.currentTimeMillis()}.jpg") }
    }
    fun launchCamera() {
        val f = File(ctx.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        cameraUri = uri.toString()
        takePhoto.launch(uri)
    }
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else vm.toast = "Camera permission denied"
    }

    LaunchedEffect(vm.items.size, (vm.items.lastOrNull() as? ChatItem.Assistant)?.text?.length) {
        if (vm.items.isNotEmpty()) listState.scrollToItem(vm.items.lastIndex, Int.MAX_VALUE / 2)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = openDrawer) { Icon(Icons.Default.Menu, "Menu") } },
                title = {
                    Column {
                        Text(vm.currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box {
                            Row(Modifier.clickable { modelMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                                Text(vm.currentModel.ifBlank { "default model" }, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                            }
                            DropdownMenu(modelMenu, { modelMenu = false }) {
                                val models = vm.info?.models.orEmpty()
                                if (models.isEmpty()) DropdownMenuItem(text = { Text("No models (is the LLM running?)") }, onClick = { modelMenu = false })
                                models.forEach { m ->
                                    DropdownMenuItem(text = { Text(m) }, onClick = { vm.selectModel(m); modelMenu = false })
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            vm.connectionError?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.refreshAll() }) { Text("Retry") }
                    }
                }
            }
            if (vm.loadingSession) LinearProgressIndicator(Modifier.fillMaxWidth())

            Box(Modifier.weight(1f)) {
                if (vm.items.isEmpty() && !vm.loadingSession) EmptyChat()
                LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(vm.items, key = { it.key }) { item ->
                        when (item) {
                            is ChatItem.User -> UserBubble(item)
                            is ChatItem.Assistant -> AssistantBubble(item)
                            is ChatItem.Tool -> ToolCard(item)
                            is ChatItem.Notice -> Text(item.text, style = MaterialTheme.typography.bodySmall,
                                color = if (item.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (vm.streaming && vm.items.lastOrNull() !is ChatItem.Assistant) item {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            // ---- pending attachments
            if (vm.pendingAttachments.isNotEmpty() || vm.uploading) {
                LazyRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(vm.pendingAttachments, key = { it.id }) { a ->
                        InputChip(selected = false, onClick = { vm.pendingAttachments.remove(a) },
                            label = { Text(a.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                            leadingIcon = { Icon(kindIcon(a.kind), null, Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) })
                    }
                    if (vm.uploading) item { CircularProgressIndicator(Modifier.size(24.dp).padding(4.dp), strokeWidth = 2.dp) }
                }
            }

            // ---- input bar
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.AttachFile, "Attach") }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Image") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = { attachMenu = false; pickFiles.launch(arrayOf("image/*")) })
                        DropdownMenuItem(text = { Text("PDF") }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                            onClick = { attachMenu = false; pickFiles.launch(arrayOf("application/pdf")) })
                        DropdownMenuItem(text = { Text("Any file") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            onClick = { attachMenu = false; pickFiles.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Camera") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                            onClick = {
                                attachMenu = false
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                                else camPermission.launch(Manifest.permission.CAMERA)
                            })
                    }
                }
                OutlinedTextField(
                    value = vm.draft, onValueChange = { vm.draft = it },
                    placeholder = { Text("Message your local AI…") },
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.size(6.dp))
                if (vm.streaming) {
                    FilledIconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, "Stop") }
                } else {
                    FilledIconButton(onClick = { vm.send() },
                        enabled = (vm.draft.isNotBlank() || vm.pendingAttachments.isNotEmpty()) && !vm.uploading) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }

    vm.approvals.firstOrNull()?.let { ApprovalDialog(it, vm) }
}

@Composable
private fun EmptyChat() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Psychology, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Text("Ask anything. Your PC's model can run code, read your folders, analyse PDFs and images, and remember things.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun kindIcon(kind: String) = when (kind) {
    "image" -> Icons.Default.Image
    "pdf" -> Icons.Default.PictureAsPdf
    "text" -> Icons.Default.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun UserBubble(item: ChatItem.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(12.dp)) {
                item.attachments.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(a.kind), null, Modifier.size(16.dp))
                        Text(" " + a.filename, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (item.text.isNotBlank()) Text(item.text)
            }
        }
    }
}

@Composable
private fun AssistantBubble(item: ChatItem.Assistant) {
    val (thinkInline, visible) = splitThink(item.text)
    val reasoning = (item.reasoning + thinkInline).trim()
    var showReasoning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (reasoning.isNotEmpty()) {
            AssistChip(onClick = { showReasoning = !showReasoning },
                label = { Text(if (showReasoning) "Hide thinking" else "Show thinking") },
                leadingIcon = { Icon(Icons.Default.Psychology, null, Modifier.size(16.dp)) })
            if (showReasoning) Text(reasoning, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (visible.isNotBlank()) MarkdownText(visible.trim())
    }
}

private fun argText(args: JsonObject, k: String) = (args[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun toolSummary(name: String, args: JsonObject): String = when (name) {
    "run_code" -> "Run ${argText(args, "language")}"
    "list_dir" -> "List ${argText(args, "path")}"
    "read_file" -> "Read ${argText(args, "path")}"
    "write_file" -> "Write ${argText(args, "path")}"
    "search_files" -> "Search ${argText(args, "pattern")} in ${argText(args, "root")}"
    "save_memory" -> "Remember: ${argText(args, "content")}"
    "search_memory" -> "Recall: ${argText(args, "query")}"
    else -> name
}

/** run_code returns JSON {exit_code, stdout, stderr}; show it as plain console text. */
private fun prettyOutput(raw: String): String = runCatching {
    val o = kotlinx.serialization.json.Json.parseToJsonElement(raw) as JsonObject
    buildString {
        append(argText(o, "stdout").trimEnd())
        argText(o, "stderr").takeIf { it.isNotBlank() }?.let { append("\n[stderr]\n").append(it.trimEnd()) }
        append("\n[exit ${argText(o, "exit_code")}${if (argText(o, "timed_out") == "true") ", timed out" else ""}]")
    }.trim()
}.getOrDefault(raw)

@Composable
private fun ToolCard(item: ChatItem.Tool) {
    var open by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, Modifier.size(16.dp))
                Text(" " + toolSummary(item.name, item.args), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when (item.status) {
                    "running" -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    "awaiting" -> Text("needs approval", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    else -> Text(if (open) "hide" else "details", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (open) {
                val code = argText(item.args, "code").ifBlank { argText(item.args, "content") }
                if (code.isNotBlank()) CodeBlock(code, argText(item.args, "language"))
                item.output?.let {
                    Text("Output", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    CodeBlock(prettyOutput(it).take(8000), "")
                }
            }
        }
    }
}

@Composable
private fun ApprovalDialog(p: PendingApproval, vm: MainViewModel) {
    val code = argText(p.args, "code").ifBlank { argText(p.args, "content") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (p.name == "run_code") "Run code on your PC?" else "Write file on your PC?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(toolSummary(p.name, p.args), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.respondApproval(p, true) }) { Text("Approve") } },
        dismissButton = { TextButton(onClick = { vm.respondApproval(p, false) }) { Text("Deny") } },
    )
}
