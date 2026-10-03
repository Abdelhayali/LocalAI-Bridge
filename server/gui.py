"""Windows control panel: start/stop the server, show the Cloudflare URL + pairing QR."""
import os
import queue
import sys
import tkinter as tk
from tkinter import messagebox, ttk

import qrcode
from PIL import ImageTk

from bridge.config import CONFIG_PATH, config
from bridge.runner import BridgeServer


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.q: queue.Queue = queue.Queue()
        self.srv = BridgeServer(log=lambda m: self.q.put(("log", m)),
                                on_url=lambda u: self.q.put(("url", u)))
        root.title("LocalAI Bridge Server")
        root.geometry("860x640")
        root.minsize(760, 560)
        root.protocol("WM_DELETE_WINDOW", self.on_close)

        top = ttk.Frame(root, padding=10)
        top.pack(fill="x")
        self.status = tk.StringVar(value="Stopped")
        ttk.Label(top, text="Status:", font=("Segoe UI", 11, "bold")).pack(side="left")
        self.status_lbl = ttk.Label(top, textvariable=self.status, foreground="#b00", font=("Segoe UI", 11))
        self.status_lbl.pack(side="left", padx=6)
        self.btn = ttk.Button(top, text="Start server", command=self.toggle)
        self.btn.pack(side="right")

        body = ttk.Frame(root, padding=(10, 0))
        body.pack(fill="both", expand=True)
        left = ttk.Frame(body)
        left.pack(side="left", fill="both", expand=True)
        right = ttk.Frame(body, padding=(10, 0))
        right.pack(side="right", fill="y")

        # --- connection
        conn = ttk.LabelFrame(left, text="Connection", padding=8)
        conn.pack(fill="x")
        self.url = tk.StringVar(value="(start the server)")
        self._row(conn, 0, "Public URL", self.url, copy=True)
        self.token = tk.StringVar(value=config["auth_token"])
        self.show_tok = tk.BooleanVar(value=False)
        self.tok_entry = self._row(conn, 1, "Access token", self.token, copy=True, secret=True)
        ttk.Checkbutton(conn, text="Show", variable=self.show_tok,
                        command=lambda: self.tok_entry.config(show="" if self.show_tok.get() else "•")
                        ).grid(row=1, column=3, padx=2)
        ttk.Button(conn, text="New token", command=self.new_token).grid(row=1, column=4)

        # --- settings
        st = ttk.LabelFrame(left, text="Local LLM", padding=8)
        st.pack(fill="x", pady=8)
        self.llm_url = tk.StringVar(value=config["llm_base_url"])
        self.model = tk.StringVar(value=config["default_model"])
        self._row(st, 0, "LLM base URL", self.llm_url, readonly=False)
        self._row(st, 1, "Default model", self.model, readonly=False)
        ttk.Label(st, text="Ollama :11434/v1  •  LM Studio :1234/v1  •  llama.cpp :8080/v1  (blank model = first available)",
                  foreground="#666").grid(row=2, column=1, columnspan=3, sticky="w")
        self.approve = tk.BooleanVar(value=config["require_tool_approval"])
        self.exec_on = tk.BooleanVar(value=config["enable_code_exec"])
        ttk.Checkbutton(st, text="Allow code execution", variable=self.exec_on).grid(row=3, column=1, sticky="w")
        ttk.Checkbutton(st, text="Phone must approve code/file writes", variable=self.approve
                        ).grid(row=3, column=2, sticky="w")
        bar = ttk.Frame(st)
        bar.grid(row=4, column=1, columnspan=3, sticky="w", pady=(6, 0))
        ttk.Button(bar, text="Save settings", command=self.save).pack(side="left")
        ttk.Button(bar, text="Open config.json (folders, tunnel...)",
                   command=lambda: os.startfile(CONFIG_PATH)).pack(side="left", padx=6)

        # --- log
        lg = ttk.LabelFrame(left, text="Log", padding=4)
        lg.pack(fill="both", expand=True, pady=(0, 10))
        self.log = tk.Text(lg, height=10, font=("Consolas", 9), state="disabled", wrap="word")
        self.log.pack(fill="both", expand=True)

        # --- QR
        ttk.Label(right, text="Scan in the Android app", font=("Segoe UI", 10, "bold")).pack()
        self.qr_lbl = ttk.Label(right, text="QR appears when the\ntunnel is ready", anchor="center",
                                width=34)
        self.qr_lbl.pack(pady=6)
        ttk.Label(right, text="Keep this QR private:\nit grants access to this PC.", foreground="#a00",
                  justify="center").pack()

        root.after(100, self.pump)
        if "--autostart" in sys.argv:
            root.after(300, self.toggle)

    def _row(self, parent, r, label, var, copy=False, secret=False, readonly=True):
        ttk.Label(parent, text=label).grid(row=r, column=0, sticky="w", padx=(0, 6), pady=2)
        e = ttk.Entry(parent, textvariable=var, width=52, show="•" if secret else "")
        if readonly:
            e.state(["readonly"])
        e.grid(row=r, column=1, columnspan=1 if copy else 3, sticky="we", pady=2)
        parent.columnconfigure(1, weight=1)
        if copy:
            ttk.Button(parent, text="Copy", width=6, command=lambda: self.copy(var.get())).grid(row=r, column=2, padx=4)
        return e

    def copy(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.write_log("Copied to clipboard.")

    def write_log(self, msg):
        self.log.config(state="normal")
        self.log.insert("end", msg + "\n")
        self.log.see("end")
        self.log.config(state="disabled")

    def pump(self):
        while not self.q.empty():
            kind, val = self.q.get()
            if kind == "log":
                self.write_log(val)
            elif kind == "url":
                self.url.set(val)
                self.render_qr()
        self.root.after(150, self.pump)

    def render_qr(self):
        img = qrcode.make(self.srv.pairing_payload(), box_size=6, border=2).get_image().resize((260, 260))
        self.qr_img = ImageTk.PhotoImage(img)
        self.qr_lbl.config(image=self.qr_img, text="")

    def toggle(self):
        if self.srv.running:
            self.srv.stop()
            self.status.set("Stopped")
            self.status_lbl.config(foreground="#b00")
            self.btn.config(text="Start server")
            self.url.set("(start the server)")
            self.qr_lbl.config(image="", text="QR appears when the\ntunnel is ready")
        else:
            self.save(quiet=True)
            self.srv.start()
            self.status.set("Running")
            self.status_lbl.config(foreground="#080")
            self.btn.config(text="Stop server")
            if config["tunnel_mode"] == "off":
                self.url.set(f"http://127.0.0.1:{config['port']}")
                self.render_qr()

    def save(self, quiet=False):
        config["llm_base_url"] = self.llm_url.get().strip()
        config["default_model"] = self.model.get().strip()
        config["require_tool_approval"] = self.approve.get()
        config["enable_code_exec"] = self.exec_on.get()
        if not quiet:
            self.write_log("Settings saved (applied immediately).")

    def new_token(self):
        if messagebox.askyesno("New token", "Generate a new access token? Paired phones must re-scan."):
            self.token.set(config.regenerate_token())
            if self.srv.running:
                self.render_qr()

    def on_close(self):
        if self.srv.running:
            self.srv.stop()
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    try:
        ttk.Style().theme_use("vista")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()
