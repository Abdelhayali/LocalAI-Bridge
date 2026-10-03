"""Starts cloudflared and captures the public tunnel URL."""
import re
import shutil
import subprocess
import threading
import urllib.request
from pathlib import Path

from .config import SERVER_DIR, config

URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")
DOWNLOAD_URL = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"


def find_cloudflared(log=print) -> str:
    candidates = [config["cloudflared_path"], shutil.which("cloudflared"),
                  r"C:\Program Files (x86)\cloudflared\cloudflared.exe",
                  r"C:\Program Files\cloudflared\cloudflared.exe",
                  str(SERVER_DIR / "bin" / "cloudflared.exe")]
    for c in candidates:
        if c and Path(c).is_file():
            return c
    target = SERVER_DIR / "bin" / "cloudflared.exe"
    target.parent.mkdir(exist_ok=True)
    log(f"cloudflared not found - downloading official build from GitHub to {target} ...")
    urllib.request.urlretrieve(DOWNLOAD_URL, target)
    return str(target)


class Tunnel:
    def __init__(self, port: int, on_url=None, log=print):
        self.port, self.on_url, self.log = port, on_url, log
        self.proc: subprocess.Popen | None = None
        self.url: str | None = None

    def start(self):
        mode = config["tunnel_mode"]
        if mode == "off":
            return
        exe = find_cloudflared(self.log)
        if mode == "named":
            if not config["tunnel_token"]:
                self.log("tunnel_mode is 'named' but tunnel_token is empty in config.json")
                return
            cmd = [exe, "tunnel", "--no-autoupdate", "run", "--token", config["tunnel_token"]]
        else:
            cmd = [exe, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{self.port}"]
        self.log("Starting Cloudflare tunnel ...")
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                     encoding="utf-8", errors="replace",
                                     creationflags=subprocess.CREATE_NO_WINDOW)
        if mode == "named" and config["public_url"]:
            self._set_url(config["public_url"].rstrip("/"))
        threading.Thread(target=self._reader, daemon=True).start()

    def _set_url(self, url):
        self.url = url
        (SERVER_DIR / "data" / "tunnel_url.txt").write_text(url, encoding="utf-8")
        if self.on_url:
            self.on_url(url)

    def _reader(self):
        for line in self.proc.stdout:
            line = line.rstrip()
            if not self.url:
                m = URL_RE.search(line)
                if m:
                    self._set_url(m.group(0))
            if "ERR" in line or "error" in line.lower():
                self.log("[cloudflared] " + line)
        self.log("cloudflared exited.")

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
        self.url = None
