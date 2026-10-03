"""Runs uvicorn + the Cloudflare tunnel; used by both the GUI and the headless launcher."""
import json
import threading
import time

import uvicorn

from .app import app, state
from .config import config
from .tunnel import Tunnel


class BridgeServer:
    def __init__(self, log=print, on_url=None):
        self.log = log
        self.on_url_cb = on_url
        self.server: uvicorn.Server | None = None
        self.thread: threading.Thread | None = None
        self.tunnel: Tunnel | None = None

    @property
    def running(self):
        return self.thread is not None and self.thread.is_alive()

    def pairing_payload(self) -> str:
        url = state["public_url"] or f"http://127.0.0.1:{config['port']}"
        return json.dumps({"url": url, "token": config["auth_token"]})

    def _on_url(self, url):
        state["public_url"] = url
        self.log(f"Public URL: {url}")
        if self.on_url_cb:
            self.on_url_cb(url)

    def start(self):
        if self.running:
            return
        cfg = uvicorn.Config(app, host=config["host"], port=config["port"], log_level="warning",
                             proxy_headers=False, timeout_keep_alive=30)
        self.server = uvicorn.Server(cfg)
        self.thread = threading.Thread(target=self.server.run, daemon=True)
        self.thread.start()
        for _ in range(50):
            if self.server.started:
                break
            time.sleep(0.1)
        self.log(f"API server listening on http://{config['host']}:{config['port']}")
        self.log(f"LLM backend: {config['llm_base_url']}")
        self.tunnel = Tunnel(config["port"], on_url=self._on_url, log=self.log)
        try:
            self.tunnel.start()
        except Exception as e:
            self.log(f"Tunnel failed: {e}")

    def stop(self):
        if self.tunnel:
            self.tunnel.stop()
        state["public_url"] = None
        if self.server:
            self.server.should_exit = True
        if self.thread:
            self.thread.join(timeout=5)
        self.thread = None
        self.log("Server stopped.")
