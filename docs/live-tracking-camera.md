# Adding Camera (Live · Intercom · Playback) to the Live-Tracking Page

A step-by-step for wiring JC181 dashcam features into your app's existing live-tracking screen
(the map/list of vehicles). For the exhaustive API + frame-format reference, see
[mobile-integration.md](mobile-integration.md); this doc is the page-level "how to plug it in".

- **Server:** `https://YOUR_SERVER` — REST + WebSocket under `/api`.
- **Video:** H.264 (payload type `98`). **Audio (both ways):** AAC-LC, 16 kHz, mono (payload type `19`).
- **Channels:** `1` = road, `2` = cabin.

---

## 1. Show a camera affordance on tracked vehicles

On your live-tracking page you already list/plot devices. Mark the ones that are dashcams:

```
GET /api/devices          (Basic auth)
→ for each device, attributes.camera === true  →  it's a JC181 dashcam
```

Add a **camera icon** to those vehicles' map markers / list rows. Tapping it opens the camera screen
for that `device.id`. Devices without `camera=true` behave exactly as today.

Keep the device's **online status** in mind (`device.status === "online"`): commands to an offline
camera return `202` (queued) and won't stream. Grey-out or badge the icon when offline.

---

## 2. One-time setup when the camera screen opens

```
POST /api/session/token   (Basic auth)   →  token string   // WebSockets can't send auth headers
```
Cache the token for the session; reuse it for every WebSocket below. All video WebSockets are:
```
wss://YOUR_SERVER/api/stream/video?deviceId=<id>&channel=<1|2>&token=<token>
```
Open as **binary** (`arraybuffer`). Each message is one media frame:
```
byte 0      payload type   (98 = H.264 video, 19 = AAC audio)
byte 1      1 if key frame (video only)
bytes 2..9  timestamp ms, big-endian int64 (relative — for A/V sync only, NOT wall clock)
bytes 10..  payload:  video = Annex-B H.264 NAL units · audio = AAC frame with ADTS header
```

---

## 3. Live video (the default camera view)

**Start:**
```
POST /api/commands/send   { "deviceId": id, "type": "videoStart", "attributes": { "index": 1 } }
open  wss …/api/stream/video?deviceId=id&channel=1&token=…
```
For each binary message, branch on byte 0:
- **98 (video)** → feed the Annex-B NAL units to the platform decoder → render:
  - **Android:** `MediaCodec` (`video/avc`), configure from the first key frame's SPS/PPS, render to a `Surface`. Set `KEY_LOW_LATENCY`.
  - **iOS:** `VideoToolbox` (`VTDecompressionSession`); build the `CMFormatDescription` from SPS/PPS, convert Annex-B → length-prefixed samples, display via `AVSampleBufferDisplayLayer`.
- **19 (audio)** → decode AAC-LC 16 kHz mono (`MediaCodec audio/mp4a-latm` / iOS `AudioConverter`) → play.

**Stop** (on leaving the view): `POST videoStart`'s counterpart → `videoStop { index: 1 }`, then close the socket.

**Two channels:** repeat for `channel: 2` with a second decoder/surface. Live supports both channels at once.
Buffer ~100–150 ms of decoded frames and render paced by the frame timestamps for smoothness (~1 s glass-to-glass).

---

## 4. Intercom — "Talk" button on the live view

The camera has a mic **and** speaker. Add a push-to-talk (or toggle) button.

```
POST videoTalk { index: channel }          // 0x9101 data type 2 = two-way voice
open  wss …/api/stream/video?deviceId=id&channel=channel&token=…   // same socket type
```
- **Receive** the camera's reply audio on the socket (AAC, byte 0 = 19) — decode/play like §3.
- **Send** your mic: capture → encode **AAC-LC, 16 kHz, mono**, ADTS-frame each packet, and send it as a
  **binary WebSocket message** back on that same socket. The server forwards it to the camera's speaker.
  - **Android:** `MediaCodec` (`audio/mp4a-latm`) at 16 kHz mono; prepend a 7-byte ADTS header to each output buffer.
  - **iOS:** `AudioConverter`/`AVAudioEngine` encode AAC; ADTS-frame and send.
- **Stop:** `videoStop { index: channel, twoWay: true }` + close the socket (`twoWay:true` sends the two-way-voice close).

Rules: intercom is **audio-only** and is **its own stream** on that channel — stop live on that channel first
(see §6). Use **echo cancellation** and don't route the mic to the local speaker, or you'll get feedback.

> The uplink codec **must be AAC-LC 16 kHz mono** — the JC181 will not decode G.711/PCM. This is trivial on
> mobile; it's only a limitation for browser-based clients, which can't encode 16 kHz AAC.

---

## 5. Playback (history) — from the same screen

Add a "History" toggle that shows a date + a 24-hour timeline.

**a. Load the recording timeline** (what's actually recorded that day):
```
POST videoResources { index: 0, startTime: <day 00:00 ISO>, endTime: <day 24:00 ISO> }
```
The reply is **asynchronous** — it arrives as a position update on the **events** socket, not in the HTTP
response. Subscribe once:
```
wss://YOUR_SERVER/api/socket?token=<token>
→ JSON frames { "positions": [ { "attributes": { "videoResources": "<JSON string>" } } ] }
```
Parse `attributes.videoResources` → array of `{ channel, startTime, endTime, mediaType, streamType, size }`
(times are **UTC ISO**). Draw filled spans on the timeline per channel; gaps = no footage.

**b. Play from a point:**
```
POST videoStop { index: channel }          // if live/intercom was running on it
POST videoPlayback { index: channel, startTime: <ISO>, endTime: <ISO> }
open  wss …/api/stream/video?deviceId=id&channel=channel&token=…   // same frame format as live
```
- **Seek** = re-send `videoPlayback` with a new `startTime` (there's no in-stream seek; expect ~1–2 s reconnect).
- **Pause/Resume/Stop** = `videoPause` / `videoResume` / `videoStop`.
- **Fast-forward** = add `"playbackMode": 1, "playbackSpeed": 2|3|4` (2×/4×/8×) to `videoPlayback`.
- **Single channel only** in playback — the camera serves one playback stream at a time.

---

## 6. The rules that keep it stable (important)

- **One stream per channel.** A channel is live **or** playback **or** intercom — never two at once. Always
  `videoStop` the current mode on that channel and wait ~0.5 s before starting another. When switching
  screens/modes, tear down cleanly (stop command + close socket).
- **Live = both channels; playback/intercom = one channel.**
- **Timezone:** set the device attribute **`decoder.timezone`** (e.g. `Asia/Kathmandu`) once in Traccar, then
  send `startTime`/`endTime` as absolute ISO instants and everything lines up. Convert the UTC `videoResources`
  times to local for display.
- **Reconnect** the media socket with backoff if it drops; re-issue the last `videoStart`/`videoPlayback`.
- **No footage ≠ error** — if a time has no recording, `videoResources` returns fewer entries and playback
  may jump to the nearest clip. Keep the user on recorded spans using the timeline.

---

## 7. Suggested screen state machine

```
Tracking page
  └─ tap camera icon (device has camera=true, online)
       → Camera screen  [mode = LIVE]
            ├─ CH1 / CH2 live video (+ audio)
            ├─ [🎤 Talk]  → stop live on ch → mode = INTERCOM → talk+listen → stop → back to LIVE
            └─ [History]  → stop live → mode = PLAYBACK
                              → load videoResources → timeline
                              → tap a recorded span → videoPlayback → play/seek/speed
                              → [Live] → stop playback → mode = LIVE
       → leave screen: videoStop all active channels + close all sockets
```

Only one `mode` is active at a time; every transition issues a `videoStop` for the channel(s) it leaves.

---

## 8. Quick reference

| Purpose | Call |
|---|---|
| Camera devices | `GET /api/devices` → `attributes.camera === true` |
| WS token | `POST /api/session/token` |
| Start / stop live | `videoStart` / `videoStop` (`index` = channel) |
| Intercom | `videoTalk` → send AAC mic frames on the video socket; `videoStop` to end |
| Recording list | `videoResources` (index 0) → reply on `/api/socket` as `attributes.videoResources` |
| Play history | `videoPlayback` (`startTime`,`endTime`; seek = re-send) |
| Pause / resume | `videoPause` / `videoResume` |
| Media socket | `wss://…/api/stream/video?deviceId=&channel=&token=` · frame `[u8 codec][u8 keyframe][i64 ts][payload]` · 98=H.264, 19=AAC |
| Events / recording-list socket | `wss://…/api/socket?token=` |

A working reference of every flow (live, dual-channel, audio, intercom, playback timeline) is in
[live-lowlatency-test.html](live-lowlatency-test.html) — open it in desktop Chrome to see the exact
request/response shapes before you build the native screen.
