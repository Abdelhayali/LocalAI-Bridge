# LocalAI Bridge - BETA: OpenCode coding agent

This folder is a **separate test copy** of LocalAI Bridge (git branch `opencode-beta`).
Your stable setup in `D:\LocalAI-Bridge` is not affected, and both can run at the same time.

| | Stable | Beta |
|---|---|---|
| Folder | `D:\LocalAI-Bridge` | `D:\LocalAI-Bridge-Beta` |
| Server port | 8765 | 8766 |
| Android app | LocalAI Bridge (blue icon) | LocalAI Bridge **Beta** (purple icon) |
| Token / chats / data | own | own |

## What's new
**☰ → Code agent (beta)**: [OpenCode](https://opencode.ai) works on a project folder of your PC with your
local LLM. It searches and reads code, edits files and runs commands. Every file edit, command and web fetch
asks for your approval on the phone (Allow / Always / Reject), showing the exact diff or command.

## How to run
1. Make sure your LLM server is running (EXL3 / llama.cpp on `http://127.0.0.1:8080`).
2. OpenCode must be installed once: `npm install -g --allow-scripts=opencode-ai opencode-ai`.
3. Double-click **`Start-Server.bat` in this folder** and scan its QR with the **Beta** app
   (`release/LocalAI-Bridge-Beta.apk`).
4. ☰ → **Code agent (beta)** → **New agent session** → pick a project folder → describe a task.

## How it works
- The bridge starts its own OpenCode server (`opencode serve`, localhost only, random password) and
  generates its config from the bridge settings (`server/data/opencode/opencode.json`).
- Progress streams to the phone live. The agent keeps working if the phone sleeps and the app
  reconnects and catches up.
- Agents that are working show in **Running tasks** (Stop button). **Kill all** stops them too.
- Only folders inside `allowed_roots` (server `config.json`) can be used.

## Known limits (beta)
- One model (your default). A 27B local model is good for focused tasks, slower on big refactors.
- The Auto-approve switch (📎 menu) also applies to the agent: then it edits and runs commands without asking.
