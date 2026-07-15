#!/usr/bin/env python3
"""
JC181 camera test UI (video + audio + two-way intercom) for a Traccar/fleetvts server.

A small Tkinter control panel that drives the camera and uses ffplay/ffmpeg for media, so
nothing has to be decoded/encoded in Python and the browser's 16 kHz-AAC limit doesn't apply:

  • Start Live  → videoStart; opens the media WebSocket; H.264 goes to an ffplay video window,
                  AAC goes to ffplay for sound.
  • Talk        → videoTalk (0x9101 data type 2); captures the Mac mic, encodes AAC-LC 16 kHz
                  with ffmpeg, streams it up (PT 19); plays the camera's reply. Push-to-talk.
  • Stop        → videoStop (control 4 for talk / control 0 for live) and tears everything down.

Requires: ffmpeg, ffplay, `pip install requests websocket-client`. Camera must be online on
this server. macOS will prompt for microphone permission the first time you Talk.

Run:  python3 camera_client.py
"""
import base64, queue, subprocess, threading, tkinter as tk
from tkinter import ttk
import requests, websocket

DEFAULT_SERVER = "https://fleetvts.trackongps.com"
AAC_UP = ["-ar", "16000", "-c:a", "aac", "-b:a", "24k", "-f", "adts"]  # mic → AAC-LC 16 kHz mono


class CameraClient(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("JC181 Camera Test — video · audio · intercom")
        self.geometry("560x520")
        self.logq = queue.Queue()
        self.session = requests.Session()
        self.token = None
        self.devices = []
        self.mode = None            # 'live' | 'talk' | None
        self.ws = None
        self.procs = []             # ffplay/ffmpeg subprocesses
        self.stop_flag = threading.Event()
        self._build()
        self.after(100, self._drain_log)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ---------- UI ----------
    def _build(self):
        f = ttk.Frame(self, padding=10); f.pack(fill="x")
        self.v = {}
        for i, (key, label, default, show) in enumerate([
                ("server", "Server", DEFAULT_SERVER, None),
                ("user", "Email", "", None),
                ("password", "Password", "", "•")]):
            ttk.Label(f, text=label).grid(row=i, column=0, sticky="w", pady=2)
            e = ttk.Entry(f, width=44, show=show); e.insert(0, default)
            e.grid(row=i, column=1, columnspan=3, sticky="we", pady=2); self.v[key] = e
        ttk.Button(f, text="Connect", command=self.connect).grid(row=0, column=4, padx=4)

        ttk.Label(f, text="Camera").grid(row=3, column=0, sticky="w", pady=2)
        self.device_cb = ttk.Combobox(f, width=30, state="readonly")
        self.device_cb.grid(row=3, column=1, sticky="we", pady=2)
        ttk.Label(f, text="Ch").grid(row=3, column=2, sticky="e")
        self.channel_cb = ttk.Combobox(f, width=4, state="readonly", values=["1", "2"]); self.channel_cb.set("1")
        self.channel_cb.grid(row=3, column=3, sticky="w")
        f.columnconfigure(1, weight=1)

        b = ttk.Frame(self, padding=(10, 4)); b.pack(fill="x")
        self.btn_live = ttk.Button(b, text="▶ Start Live", command=self.start_live, state="disabled")
        self.btn_stop = ttk.Button(b, text="■ Stop", command=self.stop_all, state="disabled")
        self.btn_talk = ttk.Button(b, text="🎤 Talk (hold)", state="disabled")
        self.btn_live.pack(side="left", padx=3); self.btn_stop.pack(side="left", padx=3)
        self.btn_talk.pack(side="left", padx=3)
        # push-to-talk: press = start, release = stop
        self.btn_talk.bind("<ButtonPress-1>", lambda e: self.talk_start())
        self.btn_talk.bind("<ButtonRelease-1>", lambda e: self.talk_stop())

        self.status = ttk.Label(self, text="Not connected", anchor="w", padding=(10, 2))
        self.status.pack(fill="x")
        self.log = tk.Text(self, height=18, bg="#111", fg="#ddd", font=("Menlo", 10)); self.log.pack(fill="both", expand=True, padx=10, pady=6)

    def _log(self, msg):
        self.logq.put(msg)

    def _drain_log(self):
        while not self.logq.empty():
            self.log.insert("end", self.logq.get() + "\n"); self.log.see("end")
        self.after(100, self._drain_log)

    # ---------- API ----------
    def _headers(self, json=False):
        h = {"Authorization": "Basic " + base64.b64encode(
            f'{self.v["user"].get()}:{self.v["password"].get()}'.encode()).decode()}
        if json:
            h["Content-Type"] = "application/json"
        return h

    def _base(self):
        return self.v["server"].get().rstrip("/")

    def connect(self):
        def work():
            try:
                tok = self.session.post(self._base() + "/api/session/token", headers=self._headers(), data="").text.strip().strip('"')
                if not tok or len(tok) < 8:
                    self._log("Login failed — check email/password/server."); return
                self.token = tok
                devs = self.session.get(self._base() + "/api/devices", headers=self._headers()).json()
                self.devices = [d for d in devs if str((d.get("attributes") or {}).get("camera")).lower() == "true"]
                names = [f'{d["id"]}: {d["name"]}' for d in self.devices]
                self.device_cb["values"] = names
                if names:
                    self.device_cb.set(names[0])
                self._log(f"Connected. {len(self.devices)} camera device(s).")
                self.status.config(text=f"Connected — {len(self.devices)} camera(s)")
                self.btn_live.config(state="normal"); self.btn_talk.config(state="normal"); self.btn_stop.config(state="normal")
            except Exception as e:
                self._log("Connect error: " + str(e))
        threading.Thread(target=work, daemon=True).start()

    def _device_id(self):
        sel = self.device_cb.get()
        return int(sel.split(":")[0]) if sel else None

    def _channel(self):
        return int(self.channel_cb.get())

    def _cmd(self, ctype, attrs):
        dev = self._device_id()
        r = self.session.post(self._base() + "/api/commands/send", headers=self._headers(json=True),
                              json={"deviceId": dev, "type": ctype, "attributes": attrs})
        self._log(f"{ctype} {attrs} -> HTTP {r.status_code}" + ("  (202 = camera offline!)" if r.status_code == 202 else ""))
        return r.status_code

    def _open_ws(self):
        dev, ch = self._device_id(), self._channel()
        url = self._base().replace("http", "ws") + f"/api/stream/video?deviceId={dev}&channel={ch}&token={self.token}"
        self.ws = websocket.create_connection(url, enable_multithread=True)

    def _spawn(self, cmd, **kw):
        p = subprocess.Popen(cmd, **kw); self.procs.append(p); return p

    # ---------- LIVE ----------
    def start_live(self):
        if self._device_id() is None:
            self._log("Pick a camera first."); return
        self.stop_all()
        self.mode = "live"; self.stop_flag.clear()
        ch = self._channel()
        self._cmd("videoStart", {"index": ch})
        try:
            self._open_ws()
        except Exception as e:
            self._log("WebSocket error: " + str(e)); return
        video = self._spawn(["ffplay", "-f", "h264", "-fflags", "nobuffer", "-flags", "low_delay",
                             "-window_title", f"CH{ch} live", "-i", "pipe:0", "-loglevel", "quiet"], stdin=subprocess.PIPE)
        audio = self._spawn(["ffplay", "-f", "aac", "-nodisp", "-autoexit", "-i", "pipe:0", "-loglevel", "quiet"], stdin=subprocess.PIPE)
        threading.Thread(target=self._demux_loop, args=(video, audio), daemon=True).start()
        self.status.config(text=f"LIVE — CH{ch}")
        self._log("Live started. Video window should open shortly…")

    def _demux_loop(self, video, audio):
        seen = set()
        while not self.stop_flag.is_set() and self.ws:
            try:
                msg = self.ws.recv()
            except Exception:
                break
            if not isinstance(msg, (bytes, bytearray)) or len(msg) < 11:
                continue
            pt, payload = msg[0], bytes(msg[10:])
            target = video if pt in (98, 99) else audio
            if pt not in seen:
                seen.add(pt); self._log(f"  << stream payloadType={pt} ({'H.264' if pt == 98 else 'H.265' if pt == 99 else 'AAC' if pt == 19 else '?'})")
            try:
                target.stdin.write(payload); target.stdin.flush()
            except Exception:
                pass

    # ---------- INTERCOM ----------
    def talk_start(self):
        if self.mode == "talk" or self._device_id() is None or not self.token:
            return
        self.stop_all()
        self.mode = "talk"; self.stop_flag.clear()
        ch = self._channel()
        self._cmd("videoTalk", {"index": ch})
        try:
            self._open_ws()
        except Exception as e:
            self._log("WebSocket error: " + str(e)); return
        audio = self._spawn(["ffplay", "-f", "aac", "-nodisp", "-autoexit", "-i", "pipe:0", "-loglevel", "quiet"], stdin=subprocess.PIPE)
        threading.Thread(target=self._demux_loop, args=(None, audio), daemon=True).start()
        mic = self._spawn(["ffmpeg", "-f", "avfoundation", "-i", ":0", "-ac", "1", *AAC_UP, "-loglevel", "error", "pipe:1"],
                          stdout=subprocess.PIPE)
        threading.Thread(target=self._mic_loop, args=(mic,), daemon=True).start()
        self.status.config(text=f"TALK — CH{ch} (speak now)")
        self.btn_talk.config(text="🎤 Talking… (release)")
        self._log("Intercom started — speak into the mic.")

    def _mic_loop(self, mic):
        buf = b""
        while not self.stop_flag.is_set() and self.ws:
            chunk = mic.stdout.read(2048)
            if not chunk:
                break
            buf += chunk
            while len(buf) >= 7:                       # split ADTS stream into frames, tag PT 19
                if buf[0] != 0xFF or (buf[1] & 0xF0) != 0xF0:
                    buf = buf[1:]; continue
                flen = ((buf[3] & 0x03) << 11) | (buf[4] << 3) | (buf[5] >> 5)
                if flen < 7 or len(buf) < flen:
                    break
                try:
                    self.ws.send(bytes([19]) + buf[:flen], websocket.ABNF.OPCODE_BINARY)
                except Exception:
                    return
                buf = buf[flen:]

    def talk_stop(self):
        if self.mode != "talk":
            return
        ch = self._channel()
        self._teardown()
        self._cmd("videoStop", {"index": ch, "twoWay": True})
        self.btn_talk.config(text="🎤 Talk (hold)")
        self.status.config(text="Connected")
        self.mode = None

    # ---------- teardown ----------
    def stop_all(self):
        mode, ch = self.mode, self._channel()
        self._teardown()
        if mode == "live":
            self._cmd("videoStop", {"index": ch})
        elif mode == "talk":
            self._cmd("videoStop", {"index": ch, "twoWay": True})
        self.mode = None
        self.status.config(text="Connected" if self.token else "Not connected")

    def _teardown(self):
        self.stop_flag.set()
        try:
            if self.ws:
                self.ws.close()
        except Exception:
            pass
        self.ws = None
        for p in self.procs:
            try:
                if p.stdin:
                    p.stdin.close()
            except Exception:
                pass
            try:
                p.kill()
            except Exception:
                pass
        self.procs = []

    def _on_close(self):
        self.stop_all()
        self.destroy()


if __name__ == "__main__":
    CameraClient().mainloop()
