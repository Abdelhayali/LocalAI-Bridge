# LocalAI Bridge

Chat with the LLM running on your Windows 11 PC from your Android phone, from anywhere, over an
encrypted Cloudflare tunnel. The assistant can **run code on the PC, browse and read your folders,
read PDFs and images, write files, and remember facts across sessions**.

```
Android app ──HTTPS──► Cloudflare tunnel ──► LocalAI Bridge server (127.0.0.1:8765) ──► Ollama / LM Studio / llama.cpp
                                                    │
                                                    ├─ sessions + memory (SQLite)
                                                    ├─ tools: run_code, list_dir, read_file, write_file, search_files, memory
                                                    └─ uploads (PDF text extraction, images for vision models)
```

## 1. Start the server (Windows)

1. Start your LLM backend, e.g. Ollama with a model: `ollama pull qwen2.5:7b`
   (for images use a vision model such as `qwen2.5vl` or `llava`; for tools use a model that supports
   function calling: Qwen 2.5/3, Llama 3.1+, Mistral...).
2. Double-click **`Start-Server.bat`**. The first run creates a Python venv and installs the dependencies.
3. The control panel starts the API server and a Cloudflare quick tunnel, then shows the
   **public URL** and a **pairing QR code**.

`Start-Server-Console.bat` does the same without a GUI (prints the URL and QR in the console).
On the PC itself you can also open <http://127.0.0.1:8765/pair> for a large QR.

## 2. Install the app (Android)

Install `release/LocalAI-Bridge.apk` (allow "install unknown apps"), open it, and tap **Scan QR code**.

| Feature | Where |
|---|---|
| Streaming chat, multiple sessions, rename/delete | Chat + side drawer |
| Model picker (any model loaded in your backend) | Tap the model name under the title |
| Attach images, PDFs, any file, camera photo | 📎 button |
| Approve or deny code execution and file writes | Pop-up dialog |
| Show the model's "thinking" (Qwen/DeepSeek reasoning) | "Show thinking" chip |
| Browse PC folders, open/share files, upload to PC, "Ask AI about this file" | Files on PC |
| Run PowerShell / Python / cmd directly | Terminal |
| View, add or delete long-term memories | Memory |

If the app is closed while a reply is generating, the PC keeps working. Reopen the chat to re-attach.

## 3. Configuration: `server/config.json`

| Key | Meaning |
|---|---|
| `llm_base_url` | OpenAI-compatible endpoint. Ollama `http://localhost:11434/v1`, LM Studio `http://localhost:1234/v1`, llama.cpp `http://localhost:8080/v1` |
| `default_model` | Blank = first model the backend reports |
| `allowed_roots` | **Only** these folders are visible to the app and the AI (default: your user folder + `workspace`). Add e.g. `"D:\\"` if you want it |
| `workspace` | Working directory for code execution |
| `enable_code_exec` / `require_tool_approval` | Turn code execution off, or skip the phone approval step |
| `tunnel_mode` | `quick` (random URL, no account), `named` (fixed hostname), `off` |
| `tunnel_token`, `public_url` | For a named tunnel: create one in the Cloudflare Zero Trust dashboard, paste its token and hostname |

Quick-tunnel URLs change every time the server restarts, so re-scan the QR after a restart, or use a named tunnel.

## Security model

- Every API call needs the 256-bit access token (`Authorization: Bearer`). After 10 failed attempts an IP is locked out for 10 minutes.
- The server listens on `127.0.0.1` only; the outside world reaches it only through the Cloudflare tunnel (HTTPS).
- `/pair` (which shows the token) is served only to requests from the PC itself, never through the tunnel.
- The phone stores the URL and token encrypted with an Android Keystore key, and the app is excluded from backups.
- Code execution and file writes requested by the AI need explicit approval on the phone (configurable).
- **Anyone with the token has the same power as you on the PC.** Keep the QR private, and use **New token** in the control panel if it leaks.

## Development

- Server: `server/bridge/` (FastAPI). `app.py` = routes + chat/tool loop, `tools.py` = tools, `tunnel.py` = cloudflared.
- Android: `android/` (Kotlin, Jetpack Compose). Open it in Android Studio, or build from the command line:
  ```
  cd android
  set JAVA_HOME=D:\Program Files\Android\Android Studio\jbr
  gradlew assembleRelease
  ```
  If Gradle fails with "Unable to establish loopback connection", run
  `set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gtmp` first.
