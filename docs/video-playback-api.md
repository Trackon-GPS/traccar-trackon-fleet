# JC181 Dashcam Video API — Mobile Integration Guide

This document describes how a mobile app talks to the Traccar server to do **live video**
and **video playback (history)** for JC181 (Jimi IoT) dashcams.

- **Server:** Traccar (this fork), running as the Docker image
  `ghcr.io/trackon-gps/traccar-trackon-fleet`.
- **Camera protocol:** JT808 (GPS + commands) + JT1078 (video), handled entirely by the server.
- **The app never talks to the camera directly.** It only calls the server HTTP API. The server
  tells the camera where to send video, receives the JT1078 stream, and re-serves it to the app
  as **HLS** (`.m3u8` + `.ts`).

Each camera has **2 channels**: `1` (usually front/road) and `2` (cabin). Everything below takes a
`channel` (aka `index`) of `1` or `2`.

---

## 1. Base URL & authentication

```
BASE = https://YOUR_SERVER:8082
```

All API calls are under `BASE/api`. Every request must be authenticated. Two options:

### Option A — Basic auth (simplest for a mobile app)
Send an `Authorization: Basic base64(email:password)` header on **every** request, including the
video stream (`.m3u8` / `.ts`) requests. This is the recommended approach because HLS players can
attach the header to segment requests.

### Option B — Session cookie
```
POST /api/session
Content-Type: application/x-www-form-urlencoded

email=USER@EXAMPLE.COM&password=SECRET
```
The response sets a `JSESSIONID` cookie; send it on all following requests.

> For the HLS player, Basic auth (Option A) is easiest because the player fetches many `.ts`
> segments and each one must be authenticated.

---

## 2. Sending commands

All camera actions (start live, start playback, pause, etc.) are sent through **one endpoint**:

```
POST /api/commands/send
Content-Type: application/json

{
  "deviceId": 42,
  "type": "<command type>",
  "attributes": { ... }
}
```

- `deviceId` — the Traccar device id (get it from `GET /api/devices`).
- `type` — one of the command types in the tables below.
- `attributes` — command parameters (channel, times, etc.).

**Response status:**
| Code | Meaning |
|------|---------|
| `200 OK` | Command sent to the camera immediately (device online). |
| `202 Accepted` | Device offline — command **queued** and sent when it reconnects. |
| `400 / 404` | Bad request / device not found. |

---

## 3. Command reference

### 3.1 Live video

| Action | `type` | `attributes` |
|--------|--------|--------------|
| Start live | `videoStart` | `{ "index": 1 }` |
| Stop live  | `videoStop`  | `{ "index": 1 }` |

`index` = channel (`1` or `2`).

**Example — start live on channel 1:**
```json
POST /api/commands/send
{ "deviceId": 42, "type": "videoStart", "attributes": { "index": 1 } }
```
Then play the HLS stream (see §4).

### 3.2 Video playback (history)

| Action | `type` | `attributes` |
|--------|--------|--------------|
| Start playback | `videoPlayback` | `index`, `startTime`, `endTime`, *(optional)* `playbackMode`, `playbackSpeed` |
| Pause playback | `videoPause` | `{ "index": 1 }` |
| Resume playback | `videoResume` | `{ "index": 1 }` |
| Stop playback | `videoStop` | `{ "index": 1 }` |

**Attributes for `videoPlayback`:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `index` | int | yes | Channel `1` or `2`. |
| `startTime` | string | yes | ISO-8601 instant, e.g. `2026-07-12T02:15:00Z` or `2026-07-12T08:00:00+05:45`. |
| `endTime` | string | no | ISO-8601 instant. Omit or empty = play to the end / indefinitely. |
| `playbackMode` | int | no | `0` = normal (default), `1` = fast-forward, `2` = keyframe rewind, `3` = keyframe playback. |
| `playbackSpeed` | int | no | Only used with `playbackMode` 1/2. `1`=1x, `2`=2x, `3`=4x, `4`=8x, `5`=16x. |

**Example — play channel 1 from 08:00 to 08:05 (Nepal time):**
```json
POST /api/commands/send
{
  "deviceId": 42,
  "type": "videoPlayback",
  "attributes": {
    "index": 1,
    "startTime": "2026-07-12T08:00:00+05:45",
    "endTime":   "2026-07-12T08:05:00+05:45"
  }
}
```
After a `200`, play the **same HLS URL** as live (see §4). Use `videoPause` / `videoResume` /
`videoStop` to control it.

**Example — 4x fast-forward:**
```json
{ "deviceId": 42, "type": "videoPlayback",
  "attributes": { "index": 1, "startTime": "2026-07-12T08:00:00+05:45",
                  "playbackMode": 1, "playbackSpeed": 3 } }
```

> **Seeking:** this camera protocol has **no seek-within-stream** command. To jump to a new time,
> send `videoPlayback` again with a new `startTime`.

### 3.3 Query available recordings

Before playback you can ask the camera **what is actually recorded** so the app can show a
timeline / list of segments instead of guessing a time range.

```json
POST /api/commands/send
{
  "deviceId": 42,
  "type": "videoResources",
  "attributes": {
    "index": 1,                                  // 0 = all channels
    "startTime": "2026-07-12T00:00:00+05:45",
    "endTime":   "2026-07-13T00:00:00+05:45"
  }
}
```

The camera's answer is **asynchronous** — it comes back as a position update, not in the HTTP
response. See §5.

---

## 4. Playing the video (HLS)

Once `videoStart` or `videoPlayback` returns `200`, the server exposes an HLS stream:

```
GET  BASE/api/stream/{deviceId}/{channel}/live.m3u8      -> HLS playlist
GET  BASE/api/stream/{deviceId}/{channel}/{index}.ts     -> video segments (referenced by playlist)
```

Example:
```
https://YOUR_SERVER:8082/api/stream/42/1/live.m3u8
```

- Feed that `.m3u8` URL to any HLS player:
  - **Android:** ExoPlayer / Media3 (`HlsMediaSource`).
  - **iOS:** `AVPlayer` with the m3u8 URL.
  - **React Native / Flutter:** any HLS-capable video widget.
- The player **must send the same auth** (Basic auth header from §1 Option A) on the `.m3u8` **and**
  `.ts` requests. Configure your player's HTTP data source with the `Authorization` header.
- There is a short delay (a few seconds) after sending the command before the first segments
  appear — the camera has to connect and push a keyframe. Retry the playlist for ~5–10s before
  showing an error.
- Stream is **video only** (no audio) for both live and playback.

**Lifecycle:** always send `videoStop` (channel) when the user closes the player, so the camera
stops streaming and frees bandwidth.

---

## 5. Receiving the recordings list (async result)

The `videoResources` reply arrives over the Traccar **WebSocket**, attached to a position update
as a `videoResources` attribute.

1. Open the socket after logging in (same session/auth):
   ```
   GET BASE/api/socket        (WebSocket upgrade)
   ```
2. You receive JSON frames like `{ "positions": [ ... ], "events": [ ... ] }`.
3. Look for a position whose `attributes` contains `videoResources`:

```json
{
  "positions": [
    {
      "deviceId": 42,
      "attributes": {
        "videoResources": "[{\"channel\":1,\"startTime\":\"2026-07-12T02:15:12Z\",\"endTime\":\"2026-07-12T02:18:12Z\",\"mediaType\":2,\"streamType\":1,\"memoryType\":1,\"size\":216}]"
      }
    }
  ]
}
```

The `videoResources` value is a **JSON string** — parse it into an array. Each entry:

| Field | Meaning |
|-------|---------|
| `channel` | Channel number (1/2). |
| `startTime` / `endTime` | Segment time range, ISO-8601 UTC (`Z`). |
| `mediaType` | 0=A/V, 1=audio, 2=video. |
| `streamType` | 1=main stream, 2=sub stream. |
| `memoryType` | 1=primary, 2=backup. |
| `size` | File size in bytes. |

Use these ranges to populate a timeline; when the user taps a segment, call `videoPlayback` with
that segment's `startTime`/`endTime`.

> Large lists may arrive as **several** position updates (the camera splits them); merge them by
> device/channel on the client.

Alternatively (simpler but less real-time), poll the latest position:
```
GET BASE/api/positions?deviceId=42
```
and read `attributes.videoResources` from the newest position.

---

## 6. Typical mobile flows

**Live view**
1. `POST /api/commands/send` → `videoStart` `{ index }`
2. Play `…/api/stream/{deviceId}/{channel}/live.m3u8`
3. On close → `videoStop` `{ index }`

**Playback**
1. *(optional)* `videoResources` → read list from socket → show timeline
2. `POST /api/commands/send` → `videoPlayback` `{ index, startTime, endTime }`
3. Play `…/api/stream/{deviceId}/{channel}/live.m3u8`
4. Controls: `videoPause` / `videoResume`; to seek → send `videoPlayback` again with new `startTime`
5. On close → `videoStop` `{ index }`

---

## 7. Operational notes (for the server admin, not the app)

- **`web.url` must be the server's public address** (env `WEB_URL` in Docker). The server sends this
  host to the camera as the address to stream JT1078 to. If it's wrong/localhost, **no video will
  arrive** even though commands return `200`.
- **Ports:** JT808 = `5015`, JT1078 = `5263`, API/HLS = `8082`. All must be reachable (the compose
  `5000-5500` range already covers 5015/5263).
- **Device timezone:** playback times are converted to the camera's local time using the device's
  `decoder.timezone` attribute (default `GMT+8`). For Nepal set it to `GMT+5:45` on the device so
  requested times match the recordings. If playback returns the wrong footage, this is the first
  thing to check.
- **Don't run live and playback on the same channel at the same time** — they share one stream.
  Different channels are independent.

---

## 8. Quick cURL smoke test

```bash
# Start live on channel 1
curl -u USER:PASS -X POST https://YOUR_SERVER:8082/api/commands/send \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":42,"type":"videoStart","attributes":{"index":1}}'

# Fetch the playlist (should return an #EXTM3U document after a few seconds)
curl -u USER:PASS https://YOUR_SERVER:8082/api/stream/42/1/live.m3u8

# Start playback
curl -u USER:PASS -X POST https://YOUR_SERVER:8082/api/commands/send \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":42,"type":"videoPlayback","attributes":{"index":1,"startTime":"2026-07-12T08:00:00+05:45","endTime":"2026-07-12T08:05:00+05:45"}}'

# Stop
curl -u USER:PASS -X POST https://YOUR_SERVER:8082/api/commands/send \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":42,"type":"videoStop","attributes":{"index":1}}'
```
