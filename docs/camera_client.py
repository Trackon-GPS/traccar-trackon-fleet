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
import base64, queue, subprocess, threading, time, tkinter as tk
from tkinter import ttk
import requests, websocket

DEFAULT_SERVER = "https://fleetvts.trackongps.com"
# codec -> (JT1078 payload type, ffmpeg output args)
CODECS = {
    "aac":   (19, ["-ar", "16000", "-c:a", "aac", "-b:a", "24k", "-f", "adts"]),
    "g711a": (6,  ["-ar", "8000",  "-c:a", "pcm_alaw", "-f", "alaw"]),
    "pcm":   (16, ["-ar", "16000", "-c:a", "pcm_s16be", "-f", "s16be"]),
}
RAW_FRAME = {"g711a": 320, "pcm": 1280}  # ~40 ms per send for raw codecs


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
        # force the window to the front (Tk windows launched from a shell open behind everything on macOS)
        self.lift()
        self.attributes("-topmost", True)
        self.after(1200, lambda: self.attributes("-topmost", False))
        self.focus_force()

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
        ttk.Label(b, text="mode").pack(side="left", padx=(8, 2))
        self.mode_cb = ttk.Combobox(b, width=9, state="readonly", values=["broadcast", "two-way"]); self.mode_cb.set("broadcast")
        self.mode_cb.pack(side="left")
        ttk.Label(b, text="codec").pack(side="left", padx=(8, 2))
        self.codec_cb = ttk.Combobox(b, width=7, state="readonly", values=list(CODECS)); self.codec_cb.set("aac")
        self.codec_cb.pack(side="left")
        self.hear_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(b, text="hear camera (headphones!)", variable=self.hear_var).pack(side="left", padx=8)
        # push-to-talk: press = start, release = stop
        self.btn_talk.bind("<ButtonPress-1>", lambda e: self.talk_start())
        self.btn_talk.bind("<ButtonRelease-1>", lambda e: self.talk_stop())

        t = ttk.Frame(self, padding=(10, 4)); t.pack(fill="x")
        ttk.Label(t, text="🗣 TTS").pack(side="left")
        self.tts_entry = ttk.Entry(t); self.tts_entry.insert(0, "Hello from the office")
        self.tts_entry.pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(t, text="Speak", command=self.speak).pack(side="left")

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
                base = self._base()
                self._log(f"Connecting to {base} …")
                rd = self.session.get(base + "/api/devices", headers=self._headers())
                self._log(f"GET /api/devices -> HTTP {rd.status_code}")
                if rd.status_code == 401:
                    self._log("  401 Unauthorized — wrong email/password."); return
                if rd.status_code != 200:
                    self._log("  unexpected: " + rd.text[:180]); return
                try:
                    devs = rd.json()
                except Exception:
                    self._log("  not JSON (wrong server URL?): " + rd.text[:180]); return
                # token for the media WebSocket
                rt = self.session.post(base + "/api/session/token",
                                       headers={**self._headers(), "Content-Type": "application/x-www-form-urlencoded"}, data="")
                self.token = rt.text.strip().strip('"') if rt.status_code == 200 else None
                if not self.token:
                    self._log(f"  token failed: HTTP {rt.status_code} {rt.text[:120]}"); return
                self.devices = [d for d in devs if str((d.get("attributes") or {}).get("camera")).lower() == "true"]
                pool = self.devices or devs  # fall back to all devices if none flagged camera=true
                names = [f'{d["id"]}: {d["name"]}' for d in pool]
                self.device_cb["values"] = names
                if names:
                    self.device_cb.set(names[0])
                self._log(f"Connected. {len(devs)} device(s), {len(self.devices)} camera(s)"
                          + ("" if self.devices else " — none flagged camera=true, showing all."))
                self.status.config(text=f"Connected — {len(self.devices)} camera(s)")
                self.btn_live.config(state="normal"); self.btn_talk.config(state="normal"); self.btn_stop.config(state="normal")
            except Exception as e:
                self._log("Connect error: " + repr(e))
        threading.Thread(target=work, daemon=True).start()

    def speak(self):                                  # TTS: make the camera read the text aloud
        if not self.token or self._device_id() is None:
            self._log("Connect and pick a camera first."); return
        text = self.tts_entry.get().strip()
        if text:
            self._cmd("message", {"message": text})
            self._log(f'TTS "{text}" sent — the camera should speak it.')

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
        seen = set(); dl = [0]; t0 = time.time()
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
            if pt not in (98, 99):                      # downlink audio proves two-way is established
                dl[0] += 1
                if time.time() - t0 >= 2.0:
                    self._log(f"  << downlink: {dl[0]} audio frames from camera"); t0 = time.time()
            if target is not None:
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
        codec = self.codec_cb.get()
        pt, out_args = CODECS[codec]
        transport = self.mode_cb.get()                 # 'broadcast' (one-way) | 'two-way'
        self.two_way = transport == "two-way"
        cmd = "videoTalk" if self.two_way else "voiceBroadcast"
        self._cmd(cmd, {"index": ch})
        try:
            self._open_ws()
        except Exception as e:
            self._log("WebSocket error: " + str(e)); return
        # broadcast is one-way (no camera audio); two-way can optionally play it (headphones!)
        audio = None
        if self.two_way and self.hear_var.get():
            audio = self._spawn(["ffplay", "-f", "aac", "-nodisp", "-autoexit", "-i", "pipe:0", "-loglevel", "quiet"], stdin=subprocess.PIPE)
        threading.Thread(target=self._demux_loop, args=(None, audio), daemon=True).start()
        mic = self._spawn(["ffmpeg", "-f", "avfoundation", "-i", ":0", "-ac", "1", *out_args, "-loglevel", "error", "pipe:1"],
                          stdout=subprocess.PIPE)
        threading.Thread(target=self._mic_loop, args=(mic, codec, pt), daemon=True).start()
        self.status.config(text=f"{'TALK' if self.two_way else 'BROADCAST'} — CH{ch} (speak now)")
        self.btn_talk.config(text="🎤 Sending… (release)")
        self._log(f"{cmd} started — codec={codec} (PT {pt}), mode={transport}. Speak into the mic.")

    def _mic_loop(self, mic, codec, pt):
        sent = [0]; t0 = [time.time()]

        def push(frame):
            try:
                self.ws.send(bytes([pt]) + frame, websocket.ABNF.OPCODE_BINARY)
            except Exception:
                return False
            sent[0] += 1
            if time.time() - t0[0] >= 1.0:
                self._log(f"  >> uplink: {sent[0]} frames sent ({codec} PT {pt})"); t0[0] = time.time()
            return True

        if codec == "aac":
            buf = b""
            while not self.stop_flag.is_set() and self.ws:
                chunk = mic.stdout.read(2048)
                if not chunk:
                    self._log("  mic produced no data (permission / wrong device?)"); break
                buf += chunk
                while len(buf) >= 7:                   # split the ADTS stream into frames
                    if buf[0] != 0xFF or (buf[1] & 0xF0) != 0xF0:
                        buf = buf[1:]; continue
                    flen = ((buf[3] & 0x03) << 11) | (buf[4] << 3) | (buf[5] >> 5)
                    if flen < 7 or len(buf) < flen:
                        break
                    if not push(buf[:flen]):
                        return
                    buf = buf[flen:]
        else:
            fsz = RAW_FRAME[codec]
            while not self.stop_flag.is_set() and self.ws:
                d = mic.stdout.read(fsz)
                if not d:
                    self._log("  mic produced no data (permission / wrong device?)"); break
                if not push(d):
                    return

    def talk_stop(self):
        if self.mode != "talk":
            return
        ch = self._channel()
        two_way = getattr(self, "two_way", False)
        self._teardown()
        self._cmd("videoStop", {"index": ch, "twoWay": two_way})
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
            self._cmd("videoStop", {"index": ch, "twoWay": getattr(self, "two_way", False)})
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
