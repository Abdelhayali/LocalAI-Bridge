"""FastAPI server: auth, sessions, streaming chat with tool calling, uploads, files, exec, memory."""
import asyncio
import base64
import io
import json
import mimetypes
import re
import secrets
import time
import uuid
from collections import defaultdict, deque
from pathlib import Path

import httpx
from fastapi import Depends, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from pydantic import BaseModel

from . import db, tools
from .config import DATA_DIR, config, llm_api_base

app = FastAPI(title="LocalAI Bridge", docs_url=None, redoc_url=None, openapi_url=None)
state = {"public_url": None}
MAX_UPLOAD = 50 * 1024 * 1024

# ---------------------------------------------------------------- auth
_failures: dict[str, deque] = defaultdict(deque)


def client_ip(req: Request) -> str:
    return req.headers.get("cf-connecting-ip") or (req.client.host if req.client else "?")


def is_local_request(req: Request) -> bool:
    """True only for requests made on this PC itself (not arriving through the tunnel)."""
    tunneled = any(h in req.headers for h in ("cf-connecting-ip", "cf-ray", "x-forwarded-for"))
    return not tunneled and req.client is not None and req.client.host in ("127.0.0.1", "::1")


async def require_auth(req: Request):
    ip = client_ip(req)
    q = _failures[ip]
    now = time.time()
    while q and now - q[0] > 600:
        q.popleft()
    if len(q) >= 10:
        raise HTTPException(429, "Too many failed attempts, try again later")
    auth = req.headers.get("authorization", "")
    token = auth[7:] if auth.lower().startswith("bearer ") else ""
    if not token or not secrets.compare_digest(token.encode(), config["auth_token"].encode()):
        q.append(now)
        raise HTTPException(401, "Invalid token")


Auth = Depends(require_auth)


# ---------------------------------------------------------------- LLM helpers
def llm_headers():
    k = config.data.get("llm_api_key", "")
    return {"Authorization": f"Bearer {k}"} if k else {}


async def list_models() -> list[str]:
    async with httpx.AsyncClient(timeout=10) as c:
        r = await c.get(llm_api_base() + "/models", headers=llm_headers())
        r.raise_for_status()
        return [m["id"] for m in r.json().get("data", [])]


async def pick_model(requested: str | None) -> str:
    if requested:
        return requested
    if config["default_model"]:
        return config["default_model"]
    models = await list_models()
    if not models:
        raise RuntimeError("The LLM server has no models loaded.")
    return models[0]


def image_data_uri(att: dict) -> str:
    raw = Path(att["path"]).read_bytes()
    try:  # downscale big phone photos to keep the context small
        from PIL import Image
        im = Image.open(io.BytesIO(raw))
        if max(im.size) > 1536 or len(raw) > 1_500_000:
            im.thumbnail((1536, 1536))
            buf = io.BytesIO()
            im.convert("RGB").save(buf, "JPEG", quality=85)
            return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()
    except Exception:
        pass
    return f"data:{att['mime']};base64," + base64.b64encode(raw).decode()


def system_prompt() -> str:
    mems = db.list_memories(60)
    parts = [config["system_prompt"],
             f"Current date/time: {time.strftime('%Y-%m-%d %H:%M')}",
             f"Allowed folders: {', '.join(config['allowed_roots'])}",
             f"Code working directory: {config['workspace']}"]
    if mems:
        parts.append("Long-term memories about the user:\n" + "\n".join(f"- {m['content']}" for m in reversed(mems)))
    return "\n\n".join(parts)


def build_llm_messages(session_id: str) -> list[dict]:
    rows = db.get_messages(session_id)[-80:]
    while rows and rows[0]["role"] != "user":
        rows.pop(0)
    last_user_idx = max((i for i, r in enumerate(rows) if r["role"] == "user"), default=-1)
    out = [{"role": "system", "content": system_prompt()}]
    for i, r in enumerate(rows):
        if r["role"] == "user":
            text = r["content"] or ""
            images = []
            for a in r["attachments"]:
                att = db.get_attachment(a["id"])
                if not att:
                    continue
                if att["kind"] == "image":
                    if i == last_user_idx:
                        images.append(att)
                    else:
                        text += f"\n\n[image attached earlier: {att['filename']}]"
                elif att["text"]:
                    text += f"\n\n[Attached file: {att['filename']}]\n{att['text']}"
                else:
                    text += f"\n\n[Attached file: {att['filename']} saved at {att['path']}]"
            if images:
                content = [{"type": "text", "text": text}] + [
                    {"type": "image_url", "image_url": {"url": image_data_uri(a)}} for a in images]
                out.append({"role": "user", "content": content})
            else:
                out.append({"role": "user", "content": text})
        elif r["role"] == "assistant":
            m = {"role": "assistant", "content": r["content"] or ""}
            if r["tool_calls"]:
                m["tool_calls"] = r["tool_calls"]
            out.append(m)
        elif r["role"] == "tool":
            out.append({"role": "tool", "tool_call_id": r["tool_call_id"], "name": r["name"],
                        "content": r["content"]})
    return out


# ---------------------------------------------------------------- chat engine
class Run:
    """A chat turn running in the background; survives phone disconnects."""

    def __init__(self, sid):
        self.sid = sid
        self.queues: list[asyncio.Queue] = []
        self.history: list[dict] = []
        self.stopped = False
        self.task: asyncio.Task | None = None

    def emit(self, ev: dict):
        self.history.append(ev)
        for q in self.queues:
            q.put_nowait(ev)


runs: dict[str, Run] = {}
approvals: dict[str, dict] = {}  # call_id -> {"future", "session_id", "name", "args"}


async def stream_completion(client, payload, run: Run):
    """Streams one completion; returns (content, reasoning, tool_calls)."""
    content, reasoning, calls = "", "", {}
    url = llm_api_base() + "/chat/completions"
    async with client.stream("POST", url, json=payload, headers=llm_headers()) as r:
        if r.status_code >= 400:
            body = (await r.aread()).decode(errors="replace")
            raise httpx.HTTPStatusError(body[:2000], request=r.request, response=r)
        async for line in r.aiter_lines():
            if run.stopped:
                break
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            try:
                chunk = json.loads(data)
            except json.JSONDecodeError:
                continue
            if not chunk.get("choices"):
                continue
            d = chunk["choices"][0].get("delta") or {}
            rz = d.get("reasoning_content") or d.get("reasoning")
            if rz:
                reasoning += rz
                run.emit({"type": "reasoning", "text": rz})
            if d.get("content"):
                content += d["content"]
                run.emit({"type": "delta", "text": d["content"]})
            for tc in d.get("tool_calls") or []:
                slot = calls.setdefault(tc.get("index", 0), {"id": "", "name": "", "args": ""})
                slot["id"] = tc.get("id") or slot["id"]
                fn = tc.get("function") or {}
                slot["name"] += fn.get("name") or ""
                slot["args"] += fn.get("arguments") or ""
    tool_calls = [{"id": c["id"] or f"call_{uuid.uuid4().hex[:12]}", "type": "function",
                   "function": {"name": c["name"], "arguments": c["args"] or "{}"}}
                  for _, c in sorted(calls.items())]
    return content, reasoning, tool_calls


async def chat_turn(run: Run, model: str | None):
    sid = run.sid
    try:
        model = await pick_model(model)
        use_tools = True
        async with httpx.AsyncClient(timeout=httpx.Timeout(600, connect=10)) as client:
            for _round in range(config["max_tool_rounds"]):
                payload = {"model": model, "messages": build_llm_messages(sid), "stream": True}
                if use_tools:
                    payload["tools"] = tools.tool_schemas()
                try:
                    content, reasoning, tool_calls = await stream_completion(client, payload, run)
                except httpx.HTTPStatusError as e:
                    if use_tools and "tool" in str(e).lower():
                        use_tools = False
                        run.emit({"type": "notice", "text": "Model does not support tools; continuing without them."})
                        continue
                    raise
                db.add_message(sid, "assistant", content, reasoning=reasoning or None,
                               tool_calls=tool_calls or None)
                if not tool_calls or run.stopped:
                    break
                for tc in tool_calls:
                    name = tc["function"]["name"]
                    try:
                        args = json.loads(tc["function"]["arguments"] or "{}")
                    except json.JSONDecodeError:
                        args = {}
                    run.emit({"type": "tool_call", "id": tc["id"], "name": name, "args": args})
                    if name in tools.NEEDS_APPROVAL and config["require_tool_approval"]:
                        fut = asyncio.get_running_loop().create_future()
                        approvals[tc["id"]] = {"future": fut, "session_id": sid, "name": name, "args": args}
                        run.emit({"type": "approval_required", "id": tc["id"], "name": name, "args": args})
                        try:
                            ok = await asyncio.wait_for(fut, 600)
                        except asyncio.TimeoutError:
                            ok = False
                        finally:
                            approvals.pop(tc["id"], None)
                        if not ok:
                            result, atts = "The user denied this action.", []
                        else:
                            result, atts = await asyncio.to_thread(tools.execute_tool, name, args)
                    else:
                        result, atts = await asyncio.to_thread(tools.execute_tool, name, args)
                    db.add_message(sid, "tool", result, attachments=atts or None, tool_call_id=tc["id"], name=name)
                    run.emit({"type": "tool_result", "id": tc["id"], "name": name, "output": result,
                              "attachments": atts})
                    if run.stopped:
                        break
            else:
                run.emit({"type": "notice", "text": "Stopped: too many tool rounds."})
        sess = db.get_session(sid)
        if sess and sess["title"] == "New chat":
            first = next((m["content"] for m in db.get_messages(sid) if m["role"] == "user"), "")
            title = re.sub(r"\s+", " ", first).strip()[:48] or "Chat"
            db.update_session(sid, title=title)
            run.emit({"type": "title", "title": title})
        run.emit({"type": "done"})
    except Exception as e:
        run.emit({"type": "error", "message": f"{type(e).__name__}: {e}"})
    finally:
        runs.pop(sid, None)
        for q in run.queues:
            q.put_nowait(None)


def sse_response(run: Run, replay=False):
    q: asyncio.Queue = asyncio.Queue()
    if replay:
        for ev in run.history:
            q.put_nowait(ev)
    run.queues.append(q)

    async def gen():
        try:
            while True:
                try:
                    ev = await asyncio.wait_for(q.get(), 15)
                except asyncio.TimeoutError:
                    yield ": ping\n\n"  # keeps Cloudflare from closing idle streams
                    continue
                if ev is None:
                    break
                yield f"data: {json.dumps(ev, ensure_ascii=False)}\n\n"
        finally:
            if q in run.queues:
                run.queues.remove(q)

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


# ---------------------------------------------------------------- routes: misc
@app.get("/api/health")
async def health():
    return {"ok": True, "name": "LocalAI Bridge"}


@app.get("/api/info", dependencies=[Auth])
async def info():
    try:
        models, llm_ok = await list_models(), True
    except Exception as e:
        models, llm_ok = [], False
    return {"llm_base_url": llm_api_base(), "llm_ok": llm_ok, "models": models,
            "default_model": config["default_model"] or (models[0] if models else ""),
            "allowed_roots": config["allowed_roots"], "workspace": config["workspace"],
            "code_exec": config["enable_code_exec"], "require_approval": config["require_tool_approval"]}


@app.get("/pair", response_class=HTMLResponse)
async def pair_page(req: Request):
    if not is_local_request(req):
        raise HTTPException(404)
    import qrcode
    import qrcode.image.svg
    url = state["public_url"] or f"http://127.0.0.1:{config['port']}"
    payload = json.dumps({"url": url, "token": config["auth_token"]})
    svg = qrcode.make(payload, image_factory=qrcode.image.svg.SvgPathImage, box_size=12).to_string().decode()
    return f"""<!doctype html><meta charset=utf-8><title>Pair phone</title>
<body style="font-family:Segoe UI;text-align:center;padding:30px">
<h2>Scan with the LocalAI Bridge app</h2>{svg}<p><b>{url}</b></p>
<p style="color:#a00">Keep this QR private - it grants full access to this PC.</p></body>"""


# ---------------------------------------------------------------- routes: sessions & chat
class NewSession(BaseModel):
    title: str = "New chat"
    model: str = ""


class PatchSession(BaseModel):
    title: str | None = None
    model: str | None = None


class ChatIn(BaseModel):
    content: str = ""
    attachments: list[str] = []
    model: str | None = None


class ApprovalIn(BaseModel):
    approve: bool


@app.get("/api/sessions", dependencies=[Auth])
async def sessions_list():
    return [dict(s, running=s["id"] in runs) for s in db.list_sessions()]


@app.post("/api/sessions", dependencies=[Auth])
async def sessions_create(body: NewSession):
    return db.create_session(body.title, body.model)


@app.get("/api/sessions/{sid}", dependencies=[Auth])
async def sessions_get(sid: str):
    s = db.get_session(sid)
    if not s:
        raise HTTPException(404)
    pending = [{"id": k, "name": v["name"], "args": v["args"]}
               for k, v in approvals.items() if v["session_id"] == sid]
    return {**s, "running": sid in runs, "messages": db.get_messages(sid), "pending_approvals": pending}


@app.patch("/api/sessions/{sid}", dependencies=[Auth])
async def sessions_patch(sid: str, body: PatchSession):
    fields = {k: v for k, v in body.model_dump().items() if v is not None}
    return db.update_session(sid, **fields)


@app.delete("/api/sessions/{sid}", dependencies=[Auth])
async def sessions_delete(sid: str):
    if sid in runs:
        runs[sid].stopped = True
    db.delete_session(sid)
    return {"ok": True}


@app.post("/api/sessions/{sid}/chat", dependencies=[Auth])
async def chat(sid: str, body: ChatIn):
    s = db.get_session(sid)
    if not s:
        raise HTTPException(404)
    if sid in runs:
        raise HTTPException(409, "A reply is already running in this session")
    atts = []
    for aid in body.attachments:
        a = db.get_attachment(aid)
        if a:
            atts.append({"id": a["id"], "filename": a["filename"], "kind": a["kind"], "mime": a["mime"]})
    db.add_message(sid, "user", body.content, attachments=atts or None)
    model = body.model or s["model"] or None
    if body.model and body.model != s["model"]:
        db.update_session(sid, model=body.model)
    run = Run(sid)
    runs[sid] = run
    resp = sse_response(run)
    run.task = asyncio.create_task(chat_turn(run, model))
    return resp


@app.get("/api/sessions/{sid}/stream", dependencies=[Auth])
async def chat_resume(sid: str):
    """Re-attach to a reply that is still running (e.g. after the app was backgrounded)."""
    if sid not in runs:
        raise HTTPException(404, "Nothing running")
    return sse_response(runs[sid], replay=True)


class TruncateIn(BaseModel):
    message_id: int


@app.post("/api/sessions/{sid}/truncate", dependencies=[Auth])
async def sessions_truncate(sid: str, body: TruncateIn):
    """Remove a message and everything after it - used to edit a message and re-run the chat."""
    if sid in runs:
        raise HTTPException(409, "A reply is still running in this session")
    db.truncate_session(sid, body.message_id)
    return {"ok": True}


@app.post("/api/screenshot", dependencies=[Auth])
async def screenshot():
    """Capture the PC screen; returns an attachment the phone can show or send to the model."""
    return await asyncio.to_thread(tools.take_screenshot)


@app.post("/api/sessions/{sid}/stop", dependencies=[Auth])
async def chat_stop(sid: str):
    if sid in runs:
        runs[sid].stopped = True
        for k, v in list(approvals.items()):
            if v["session_id"] == sid and not v["future"].done():
                v["future"].set_result(False)
    return {"ok": True}


@app.post("/api/approvals/{call_id}", dependencies=[Auth])
async def approve(call_id: str, body: ApprovalIn):
    a = approvals.get(call_id)
    if not a:
        raise HTTPException(404, "No pending approval")
    if not a["future"].done():
        a["future"].set_result(body.approve)
    return {"ok": True}


# ---------------------------------------------------------------- routes: uploads
def _safe_name(name: str) -> str:
    return re.sub(r"[^\w.\- ]", "_", Path(name or "file").name)[:120]


async def _save_upload(f: UploadFile, dest: Path) -> int:
    size = 0
    with dest.open("wb") as out:
        while chunk := await f.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_UPLOAD:
                out.close()
                dest.unlink(missing_ok=True)
                raise HTTPException(413, "File too large (50 MB max)")
            out.write(chunk)
    return size


@app.post("/api/uploads", dependencies=[Auth])
async def upload(file: UploadFile = File(...)):
    name = _safe_name(file.filename)
    dest = DATA_DIR / "uploads" / f"{uuid.uuid4().hex[:8]}_{name}"
    size = await _save_upload(file, dest)
    mime = file.content_type or mimetypes.guess_type(name)[0] or "application/octet-stream"
    ext = dest.suffix.lower()
    text, kind = None, "file"
    if mime == "application/pdf" or ext == ".pdf":
        kind, mime = "pdf", "application/pdf"
        try:
            text = await asyncio.to_thread(tools.extract_pdf_text, dest)
        except Exception as e:
            text = f"[could not extract PDF text: {e}]"
    elif mime.startswith("image/"):
        kind = "image"
    elif mime.startswith("text/") or ext in tools.TEXT_EXT:
        kind = "text"
        text = tools.truncate(dest.read_text(encoding="utf-8", errors="replace"), 60_000)
    a = db.add_attachment(name, mime, kind, str(dest), text, size)
    return {k: a[k] for k in ("id", "filename", "mime", "kind", "size")}


@app.get("/api/uploads/{aid}", dependencies=[Auth])
async def upload_get(aid: str):
    a = db.get_attachment(aid)
    if not a:
        raise HTTPException(404)
    return FileResponse(a["path"], media_type=a["mime"], filename=a["filename"])


# ---------------------------------------------------------------- routes: filesystem
@app.get("/api/fs/roots", dependencies=[Auth])
async def fs_roots():
    return {"roots": config["allowed_roots"], "workspace": config["workspace"]}


@app.get("/api/fs/list", dependencies=[Auth])
async def fs_list(path: str):
    try:
        return await asyncio.to_thread(tools.list_dir, path)
    except tools.ToolError as e:
        raise HTTPException(403, str(e))


@app.get("/api/fs/download", dependencies=[Auth])
async def fs_download(path: str):
    try:
        p = tools.resolve_path(path)
    except tools.ToolError as e:
        raise HTTPException(403, str(e))
    if not p.is_file():
        raise HTTPException(404)
    return FileResponse(p, filename=p.name)


@app.post("/api/fs/upload", dependencies=[Auth])
async def fs_upload(dir: str, file: UploadFile = File(...)):
    try:
        d = tools.resolve_path(dir)
    except tools.ToolError as e:
        raise HTTPException(403, str(e))
    if not d.is_dir():
        raise HTTPException(400, "Not a directory")
    dest = d / _safe_name(file.filename)
    size = await _save_upload(file, dest)
    return {"path": str(dest), "size": size}


# ---------------------------------------------------------------- routes: exec & memory
class ExecIn(BaseModel):
    language: str = "python"
    code: str


@app.post("/api/exec", dependencies=[Auth])
async def exec_code(body: ExecIn):
    try:
        return await asyncio.to_thread(tools.run_code, body.language, body.code)
    except tools.ToolError as e:
        raise HTTPException(400, str(e))


class MemoryIn(BaseModel):
    content: str


@app.get("/api/memory", dependencies=[Auth])
async def memory_list():
    return db.list_memories()


@app.post("/api/memory", dependencies=[Auth])
async def memory_add(body: MemoryIn):
    return {"id": db.add_memory(body.content.strip())}


@app.delete("/api/memory/{mid}", dependencies=[Auth])
async def memory_delete(mid: int):
    db.delete_memory(mid)
    return {"ok": True}
