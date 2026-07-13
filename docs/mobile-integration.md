# JC181 Dashcam — Mobile App Integration Guide

How a mobile app does **live video, audio, and playback (history)** for JC181 (Jimi IoT) dashcams
through the Traccar server (this fork). The app talks only to the server's HTTP + WebSocket API — never
to the camera directly. The server speaks JT808/JT1078 to the camera and re-serves the media.

- **Server:** `https://YOUR_SERVER` (e.g. `https://fleetvts.trackongps.com`) — API + WebSocket under `/api`.
- **Video codec:** H.264 (payload type `98`). **Audio:** AAC-LC, 16 kHz, mono, ADTS-framed (payload type `19`).
- **Channels:** each camera has channel `1` (road) and `2` (cabin).

---

## 1. Authentication

Two things need auth:

- **REST calls** — use HTTP **Basic auth** (`Authorization: Basic base64(email:password)`) on every request. Simplest for mobile.
- **WebSocket** — pass a **token** as a query param (WebSockets can't send custom headers). Mint one:
  ```
  POST /api/session/token          (Basic auth)   ->   returns a token string
  ```
  Reuse the token for all WebSocket connections until it expires, then mint a new one.

Get the device list (show only cameras — they have attribute `camera = true`):
```
GET /api/devices        -> [{ id, name, uniqueId, attributes:{ camera:true, ... }, status }, ...]
```
Use the numeric `id` (the `deviceId`) everywhere below.

---

## 2. Commands — `POST /api/commands/send`

All camera actions go through one endpoint. Body:
```json
{ "deviceId": 6, "type": "<command>", "attributes": { ... } }
```
Response: **`200`** = sent to the camera (online); **`202`** = queued (device offline, sent on reconnect).

| Action | `type` | `attributes` |
|--------|--------|--------------|
| Start live | `videoStart` | `{ "index": 1 }` |
| Stop live/playback | `videoStop` | `{ "index": 1 }` |
| Start playback | `videoPlayback` | `index`, `startTime`, `endTime`, opt. `playbackMode`, `playbackSpeed` |
| Pause playback | `videoPause` | `{ "index": 1 }` |
| Resume playback | `videoResume` | `{ "index": 1 }` |
| List recordings | `videoResources` | `index` (0 = all channels), `startTime`, `endTime` |

- `index` = channel (1 or 2).
- `startTime`/`endTime` = **ISO-8601 instants** with offset or `Z`, e.g. `2026-07-13T14:00:00+05:45`. The server converts to the camera's local time using the device's `decoder.timezone` attribute (see §7).
- `playbackMode`: `0` normal, `1` fast-forward. `playbackSpeed`: `2`=2×, `3`=4×, `4`=8× (used with mode 1).

---

## 3. Live & playback video — the WebSocket

Both live and playback deliver frames over the **same** socket:
```
wss://YOUR_SERVER/api/stream/video?deviceId=6&channel=1&token=<TOKEN>
```
Open it as **binary** (`arraybuffer`). Each message is one media frame:

```
byte 0        payload type   (98 = H.264 video, 19 = AAC audio)
byte 1        1 if key frame, else 0   (video only; ignore for audio)
bytes 2..9    timestamp, milliseconds, big-endian int64 (relative to the stream start — NOT wall clock)
bytes 10..    payload:
                video  -> Annex-B H.264 NAL units (start codes 00 00 00 01)
                audio  -> one AAC frame WITH its 7-byte ADTS header
```

**Flow (live):**
1. Send `videoStart { index: channel }`.
2. Open the WebSocket for that `deviceId`/`channel`.
3. For each binary message: branch on byte 0 → feed video to the video decoder, audio to the audio decoder.
4. On close, send `videoStop { index: channel }`.

**Flow (playback):** identical, but send `videoPlayback { index, startTime, endTime }` instead of `videoStart`, then open the socket for that channel. The camera streams the recorded footage from `startTime`.

**Latency:** the server pushes each frame immediately (no HLS segmenting). Expect ~1 s glass-to-glass with a small jitter buffer. Buffer ~100–150 ms of decoded frames and render paced by the frame timestamps for smoothness.

---

## 4. Decoding on the device

### Video — H.264 Annex-B
- **Android — `MediaCodec`** (`video/avc`): the stream is Annex-B, so you can feed NAL units directly. Configure with the SPS/PPS from the first key frame (or let MediaCodec pick them up in-band), then `queueInputBuffer` each frame and render to a `Surface`. Set `KEY_LOW_LATENCY` / `KEY_PRIORITY` for realtime.
- **iOS — `VideoToolbox`** (`VTDecompressionSession`): VideoToolbox wants length-prefixed (AVCC) samples + a `CMVideoFormatDescription` built from SPS/PPS. Parse the first key frame's SPS/PPS to create the format description, convert each Annex-B NAL to a 4-byte-length-prefixed `CMBlockBuffer`, then decode and render via `AVSampleBufferDisplayLayer` or a Metal/CA view.

### Audio — AAC-LC, 16 kHz, mono, ADTS
- **Android — `MediaCodec`** (`audio/mp4a-latm`): feed each frame including its ADTS header (or strip ADTS and set a CSD-0 from the ADTS params), decode to PCM, play through `AudioTrack`.
- **iOS — `AudioConverter` / `AVAudioEngine`**: parse the ADTS header for sample rate/channels, decode AAC → PCM, play via `AVAudioEngine`/`AudioQueue`.
- Play audio from **one channel at a time** (two channels of audio at once is just noise).

---

## 5. Playback — the recording timeline

To show a Hikvision-style timeline of what's actually recorded:

1. Send `videoResources { index: 0, startTime: <day 00:00>, endTime: <day 24:00> }` (channel 0 = all).
2. The camera's reply is **asynchronous** and arrives as a **position update on the events WebSocket** — it is *not* in the HTTP response and polling `/api/positions` will miss it. Subscribe to:
   ```
   wss://YOUR_SERVER/api/socket?token=<TOKEN>
   ```
   You'll receive JSON frames `{ "positions": [ ... ] }`. Look for a position whose `attributes.videoResources` is set:
   ```json
   {
     "attributes": {
       "videoResources": "[{\"channel\":1,\"startTime\":\"2026-07-13T16:38:02Z\",\"endTime\":\"2026-07-13T16:41:02Z\",\"mediaType\":0,\"streamType\":1,\"memoryType\":1,\"size\":95170330}, ...]"
     }
   }
   ```
   Parse that string into an array. Each entry: `channel`, `startTime`/`endTime` (**ISO-8601 UTC**), `mediaType` (0 A+V, 1 audio, 2 video), `streamType`, `memoryType`, `size` (bytes).
3. Draw the segments as filled spans on a 24-hour bar (one per channel). Gaps = no footage.

**Seeking:** playback is a live stream from the camera, so there's no random access. To "seek", **re-send `videoPlayback` with a new `startTime`** and reopen/refresh the socket. Expect a ~1–2 s reconnect.

**Playback controls:** `videoPause` / `videoResume` hold and continue; `videoStop` ends. Fast-forward is `playbackMode:1` + `playbackSpeed` set on the `videoPlayback` request (not changeable mid-stream — re-request to change speed).

---

## 6. Constraints & gotchas (important)

- **One stream per channel.** The camera streams either live **or** playback on a given channel — not both. **Stop live before starting playback** on the same channel (send `videoStop`, wait ~0.5 s, then `videoPlayback`), or their frames will mix. The server drops stale frames, but stopping cleanly is best.
- **One playback channel at a time.** The camera serves only a single playback stream. Play channel 1 **or** channel 2 in history mode, not both simultaneously. (Live supports both channels at once.)
- **Timezone.** Set the device attribute **`decoder.timezone`** to the camera's zone (e.g. `Asia/Kathmandu`) once, in Traccar. Then send `startTime`/`endTime` as absolute ISO instants and everything lines up. `videoResources` times come back as **UTC ISO** — convert to local for display.
- **Frame timestamp is relative**, not wall-clock — use it only for pacing/A-V sync, not to label footage. The true recording time is in the video's burned-in watermark and in the `videoResources` list.
- **No footage ≠ error.** If a time has no recording, `videoResources` returns fewer/zero entries and `videoPlayback` may jump to the nearest clip or send nothing. Use the recording list to keep the user on real footage.
- **Ports (server side):** JT808 `5015`, JT1078 `5263`, API `8082` must be reachable; `web.url` must be the server's public address (the camera dials it for streaming).

---

## 7. Recommended app flows

**Live**
```
POST videoStart {index}
open  wss …/api/stream/video?deviceId&channel&token
render video (byte0=98) + optional audio (byte0=19)
POST videoStop {index}   on close
```

**Playback**
```
POST videoResources {index:0, day range}       ─┐  (once, to draw the timeline)
open  wss …/api/socket?token  → read attributes.videoResources
                                                 ─┘
user drags to a time on a recorded span →
  POST videoStop {index}  (if live was running)
  POST videoPlayback {index, startTime, endTime}
  open wss …/api/stream/video?deviceId&channel&token
seek = repeat videoPlayback with a new startTime
pause/resume/stop = videoPause / videoResume / videoStop
```

**Alarms / events** (for a timeline or notifications): the camera's alarms (harsh braking, SOS, SD-card errors, tamper, etc.) arrive as normal Traccar events/positions — read them via `/api/reports/events` or the `/api/socket` `events` stream. See the E8 alarm mapping in `jt808-alarm-mapping.md`.

---

## 8. Suggested UI design

Mirror the reference web demo, adapted for a phone (portrait). Two screens: **Live** and **Playback**,
switched by a top segmented control or bottom tabs. A device picker (camera devices only) sits above both.

### Live screen — dual channel
```
┌──────────────────────────────┐
│  [ Vehicle / camera  ▼ ]     │   device picker (camera=true)
├──────────────────────────────┤
│  CH1 · road         🔊  ⤢    │   header: label · mute · fullscreen
│  ┌──────────────────────────┐│
│  │        live video        ││   16:9, tap = fullscreen
│  └──────────────────────────┘│
│  CH2 · cabin        🔇  ⤢    │
│  ┌──────────────────────────┐│
│  │        live video        ││
│  └──────────────────────────┘│
├──────────────────────────────┤
│      ▶ Start        ■ Stop    │
└──────────────────────────────┘
```
- Two 16:9 windows stacked (portrait) or side-by-side (landscape).
- Per-window **mute** (🔊/🔇) — only one channel's audio at a time; tapping one un-mutes it and mutes the other.
- **Tap a window → fullscreen** (landscape), pinch nothing fancy needed.
- Show a small status/latency chip while connecting ("connecting…", then hide).
- Auto-`videoStop` when leaving the screen.

### Playback screen — single channel + timeline
```
┌──────────────────────────────┐
│  [ Camera ▼ ]  [ CH1 ▼ ]     │   device + channel picker
│  [ 2026-07-13  📅 ]  Load     │   date + "Load recordings"
├──────────────────────────────┤
│  ┌──────────────────────────┐│
│  │      playback video      ││   16:9, tap = fullscreen
│  └──────────────────────────┘│
│        20:14:30    🔊         │   current time (from watermark/seek) + mute
├──────────────────────────────┤
│  ⏮   ▶/⏸   ⏭      1x ▼        │   transport + speed
│  ┌──────────────────────────┐│
│  │▓▓▓  ▓▓▓▓▓   ● ▓▓▓▓  ▓▓▓▓ ││   24h TIMELINE:
│  └───────────────▲──────────┘│     ▓ = recorded span, ● = event marker
│  00   06   12   18   24       │     ▲ = draggable playhead
└──────────────────────────────┘
```
- **Timeline** is the centerpiece: a 24-hour bar with **filled spans = recorded footage** (from `videoResources`, per selected channel) and **dots = events** (from Traccar events; tap to jump). Empty = no footage.
- **Drag the playhead** onto a recorded span and release → seek (re-send `videoPlayback`). Show a brief "seeking…".
- **Pinch-zoom the timeline** (e.g. 24h → 1h) for precise seeking on long days — optional but nice.
- **Speed** control maps to `playbackMode`/`playbackSpeed` (changing speed re-issues playback).
- **Single channel only** — the channel picker swaps which channel plays and re-filters the timeline spans.
- Color the current-time label green when the playhead is over a recorded span, gray over a gap (so users don't seek into nothing).

### Cross-cutting
- **One media surface per channel**; tear down and stop cleanly on navigation.
- **Reconnect** the media WebSocket with backoff if it drops; re-issue the last `videoStart`/`videoPlayback`.
- **Errors**: "camera offline" (command returned 202), "no footage for this day", decoder failures → show a retry.
- **Landscape/fullscreen** should hide chrome and letterbox the 16:9 video.

---

## 9. Quick reference

| Purpose | Call |
|---------|------|
| Login token (for WS) | `POST /api/session/token` (Basic auth) |
| Camera devices | `GET /api/devices` → filter `attributes.camera == true` |
| Start/stop live | `POST /api/commands/send` → `videoStart` / `videoStop` |
| Start playback | `POST /api/commands/send` → `videoPlayback` |
| Pause/resume | `videoPause` / `videoResume` |
| List recordings | `videoResources` → reply on `/api/socket` as `attributes.videoResources` |
| Media stream | `wss://…/api/stream/video?deviceId=&channel=&token=` |
| Events/positions stream | `wss://…/api/socket?token=` |
| Frame format | `[u8 codec][u8 keyframe][i64 ms ts][payload]` · 98=H.264, 19=AAC |

A working reference implementation of every flow above is in `docs/live-lowlatency-test.html` (browser/WebCodecs) — use it to see the exact request/response shapes.
