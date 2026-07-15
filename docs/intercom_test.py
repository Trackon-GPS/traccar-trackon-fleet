#!/usr/bin/env python3
"""
Intercom (two-way voice) test client for JC181 via a Traccar/fleetvts server.

The browser can't encode 16 kHz AAC, so we use ffmpeg here instead. This script:
  1. logs in, picks a camera, sends `videoTalk` (0x9101 data type 2)
  2. opens the media WebSocket
  3. captures the Mac mic, encodes it (AAC / G.711A / PCM via ffmpeg), and streams it up
     (each frame tagged with its JT1078 payload type in byte 0, as the server expects)
  4. optionally plays the camera's reply audio (downlink) via ffplay
  5. on Ctrl-C, sends `videoStop {twoWay:true}` (0x9102 control 4) and cleans up

Requires: ffmpeg, ffplay, `pip install requests websocket-client`.
The camera must be online AND pointed at this server.

Examples:
  python3 intercom_test.py --user you@example.com --password secret --play
  python3 intercom_test.py -u ... -p ... --codec g711a --channel 1
"""
import argparse, base64, subprocess, sys, threading
import requests, websocket

CODECS = {
    # codec -> (JT1078 payload type, ffmpeg output args)
    "aac":   (19, ["-ar", "16000", "-c:a", "aac", "-b:a", "24k", "-f", "adts"]),
    "g711a": (6,  ["-ar", "8000",  "-c:a", "pcm_alaw", "-f", "alaw"]),
    "pcm":   (16, ["-ar", "16000", "-c:a", "pcm_s16be", "-f", "s16be"]),
}
RAW_FRAME = {"g711a": 320, "pcm": 1280}  # ~40 ms per send for raw codecs


def main():
    ap = argparse.ArgumentParser(description="JC181 intercom test client")
    ap.add_argument("--server", default="https://fleetvts.trackongps.com")
    ap.add_argument("-u", "--user", required=True)
    ap.add_argument("-p", "--password", required=True)
    ap.add_argument("--device", type=int, help="device id (default: first camera=true device)")
    ap.add_argument("--channel", type=int, default=1)
    ap.add_argument("--codec", choices=list(CODECS), default="aac")
    ap.add_argument("--mic", default="0", help="avfoundation audio device index (see: ffmpeg -f avfoundation -list_devices true -i '')")
    ap.add_argument("--play", action="store_true", help="play the camera's reply audio")
    args = ap.parse_args()

    base = args.server.rstrip("/")
    hdr = {"Authorization": "Basic " + base64.b64encode(f"{args.user}:{args.password}".encode()).decode()}
    jhdr = {**hdr, "Content-Type": "application/json"}
    pt, out_args = CODECS[args.codec]

    # --- auth + device ---
    tok = requests.post(base + "/api/session/token", headers=hdr, data="").text.strip().strip('"')
    if not tok or len(tok) < 8:
        sys.exit("login failed (check user/password/server)")
    dev = args.device
    if not dev:
        devs = requests.get(base + "/api/devices", headers=hdr).json()
        cams = [d for d in devs if str((d.get("attributes") or {}).get("camera")).lower() == "true"]
        if not cams:
            sys.exit("no camera devices (attribute camera=true)")
        dev, name = cams[0]["id"], cams[0]["name"]
        print(f"device: {dev} ({name})")

    ch = args.channel

    # --- start intercom ---
    r = requests.post(base + "/api/commands/send", headers=jhdr,
                      json={"deviceId": dev, "type": "videoTalk", "attributes": {"index": ch}})
    print(f"videoTalk -> HTTP {r.status_code}" + ("  (202 = camera offline/queued!)" if r.status_code == 202 else ""))

    wsurl = base.replace("http", "ws") + f"/api/stream/video?deviceId={dev}&channel={ch}&token={tok}"
    ws = websocket.create_connection(wsurl, enable_multithread=True)
    print("websocket open")
    stop = threading.Event()
    OP_BIN = websocket.ABNF.OPCODE_BINARY

    # --- downlink: receive + optionally play the camera's audio ---
    player = None
    if args.play:
        player = subprocess.Popen(["ffplay", "-f", "aac", "-i", "-", "-nodisp", "-autoexit", "-loglevel", "quiet"],
                                  stdin=subprocess.PIPE)

    def recv_loop():
        seen = set()
        while not stop.is_set():
            try:
                msg = ws.recv()
            except Exception:
                break
            if not isinstance(msg, (bytes, bytearray)) or len(msg) < 11:
                continue
            p = msg[0]
            if p in (98, 99):  # video — intercom is audio-only, ignore
                continue
            if p not in seen:
                seen.add(p)
                print(f"  << downlink audio payloadType={p}" + (" (AAC)" if p == 19 else ""))
            if player:
                try:
                    player.stdin.write(bytes(msg[10:])); player.stdin.flush()
                except Exception:
                    pass
    threading.Thread(target=recv_loop, daemon=True).start()

    # --- uplink: capture mic, encode, stream up ---
    cmd = ["ffmpeg", "-f", "avfoundation", "-i", f":{args.mic}", "-ac", "1", *out_args, "-loglevel", "error", "-"]
    ff = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    print(f"talking: codec={args.codec} (PT {pt}) — speak into the mic, Ctrl-C to stop")

    def send(payload):
        ws.send(bytes([pt]) + payload, OP_BIN)

    try:
        if args.codec == "aac":
            buf = b""
            while not stop.is_set():
                chunk = ff.stdout.read(2048)
                if not chunk:
                    break
                buf += chunk
                while len(buf) >= 7:                       # split the ADTS stream into frames
                    if buf[0] != 0xFF or (buf[1] & 0xF0) != 0xF0:
                        buf = buf[1:]; continue
                    flen = ((buf[3] & 0x03) << 11) | (buf[4] << 3) | (buf[5] >> 5)
                    if flen < 7 or len(buf) < flen:
                        break
                    send(buf[:flen]); buf = buf[flen:]
        else:
            fsz = RAW_FRAME[args.codec]
            while not stop.is_set():
                d = ff.stdout.read(fsz)
                if not d:
                    break
                send(d)
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        try: ff.kill()
        except Exception: pass
        requests.post(base + "/api/commands/send", headers=jhdr,
                      json={"deviceId": dev, "type": "videoStop", "attributes": {"index": ch, "twoWay": True}})
        try: ws.close()
        except Exception: pass
        if player:
            try: player.stdin.close(); player.kill()
            except Exception: pass
        print("\nstopped intercom.")


if __name__ == "__main__":
    main()
