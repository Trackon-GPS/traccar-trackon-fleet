# Intercom (Two-Way Voice) — App Integration

How to add **push-to-talk intercom** to the camera screen, assuming live video, audio, and playback are
already working. Intercom reuses the same auth and the same video WebSocket you already use for live — the
only new work is **capturing the mic, encoding it to the camera's codec, and sending it back up the socket**.

- **Codec (both directions): AAC-LC, 16 kHz, mono.** This is the JC181's own audio format. The camera will
  **not** decode G.711/PCM — sending anything else produces static or silence.
- **Command:** `videoTalk` starts two-way voice; `videoStop` ends it.
- **Transport:** the existing `wss://…/api/stream/video` socket, used **bidirectionally**.

> Server requirement: the deployment must include the intercom build (the one where `videoTalk` /
> `0x9101` data type 2 and the audio-uplink path exist). If `videoTalk` returns 400/unsupported, the
> server image is too old.

---

## 1. The flow

```
POST /api/commands/send
     { "deviceId": id, "type": "videoTalk", "attributes": { "index": channel } }

open wss://YOUR_SERVER/api/stream/video?deviceId=id&channel=channel&token=<token>
     (binaryType = arraybuffer)

── DOWNLINK (camera → app): incoming binary frames, same format as live ──
   byte 0 = 19 (AAC)  →  decode & play the camera's audio (intercom is audio-only, no video)

── UPLINK (app → camera): send binary messages on the SAME socket ──
   each message = one AAC-LC frame, ADTS-framed, 16 kHz mono  →  camera plays it on its speaker

stop:  POST videoStop { index: channel }   +   close the socket
```

Incoming frame layout (only audio matters here):
```
byte 0      payload type (19 = AAC)
byte 1      unused for audio
bytes 2..9  timestamp ms (int64 BE)
bytes 10..  AAC frame with 7-byte ADTS header
```
Uplink message layout (what you send): **just the raw ADTS-framed AAC bytes** — no extra header.

---

## 2. Rules (don't skip)

- **Intercom is its own stream on the channel.** A channel can be live **or** playback **or** intercom — never
  two at once. Before `videoTalk`, send `videoStop` for that channel (and stop your live decoder), wait ~0.5 s,
  then start intercom. On stop, restore live if that's your UX.
- **Echo cancellation on the mic**, and **do not** play your own mic locally, or the camera will echo you back.
- **One channel at a time** for talking. Pick CH1 (road) or CH2 (cabin).
- Audio timestamps are relative — use them only for playback pacing, not wall-clock.

---

## 3. Downlink — play the camera's reply

You already decode AAC for live audio; reuse it. For each incoming frame with `byte 0 == 19`, take
`bytes[10..]` (AAC with ADTS) and feed it to your AAC decoder → speaker. Nothing new here.

---

## 4. Uplink — Android (Kotlin)

Capture 16 kHz mono PCM, encode AAC-LC with `MediaCodec`, prepend an ADTS header, send on the socket.

```kotlin
// --- capture: 16 kHz, mono, 16-bit PCM ---
val sampleRate = 16000
val minBuf = AudioRecord.getMinBufferSize(
    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
val recorder = AudioRecord(
    MediaRecorder.AudioSource.VOICE_COMMUNICATION,          // enables AEC/NS
    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)

// --- encoder: AAC-LC 16 kHz mono ---
val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    setInteger(MediaFormat.KEY_BIT_RATE, 24000)
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
}
val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
codec.start(); recorder.startRecording()

// --- pump PCM in, get AAC out, ADTS-frame, send ---
val info = MediaCodec.BufferInfo()
while (talking) {
    val inIdx = codec.dequeueInputBuffer(10000)
    if (inIdx >= 0) {
        val buf = codec.getInputBuffer(inIdx)!!; buf.clear()
        val n = recorder.read(buf, buf.capacity())
        codec.queueInputBuffer(inIdx, 0, if (n > 0) n else 0, System.nanoTime()/1000, 0)
    }
    var outIdx = codec.dequeueOutputBuffer(info, 0)
    while (outIdx >= 0) {
        val out = codec.getOutputBuffer(outIdx)!!
        val aac = ByteArray(info.size); out.position(info.offset); out.get(aac)
        webSocket.send(ByteString.of(*addAdts(aac)))       // OkHttp WebSocket.send(ByteString)
        codec.releaseOutputBuffer(outIdx, false)
        outIdx = codec.dequeueOutputBuffer(info, 0)
    }
}

// --- 7-byte ADTS header for AAC-LC, 16 kHz (freq index 8), mono ---
fun addAdts(aac: ByteArray): ByteArray {
    val len = aac.size + 7
    val h = ByteArray(7)
    h[0] = 0xFF.toByte()
    h[1] = 0xF1.toByte()                                   // MPEG-4, no CRC
    h[2] = (((2 - 1) shl 6) or (8 shl 2) or (1 shr 2)).toByte()   // profile=AAC LC, freqIdx=8, chan hi
    h[3] = (((1 and 3) shl 6) or (len shr 11)).toByte()          // chan lo, len hi
    h[4] = ((len shr 3) and 0xFF).toByte()
    h[5] = (((len and 7) shl 5) or 0x1F).toByte()
    h[6] = 0xFC.toByte()
    return h + aac
}
```

On stop: `recorder.stop(); recorder.release(); codec.stop(); codec.release()`, send `videoStop`, close socket.

---

## 5. Uplink — iOS (Swift)

Capture with `AVAudioEngine`, convert to AAC-LC 16 kHz mono with `AVAudioConverter`, ADTS-frame, send.

```swift
let engine = AVAudioEngine()
let input = engine.inputNode

// target format: AAC-LC, 16 kHz, mono
var aacDesc = AudioStreamBasicDescription(
    mSampleRate: 16000, mFormatID: kAudioFormatMPEG4AAC, mFormatFlags: 0,
    mBytesPerPacket: 0, mFramesPerPacket: 1024, mBytesPerFrame: 0,
    mChannelsPerFrame: 1, mBitsPerChannel: 0, mReserved: 0)
let aacFormat = AVAudioFormat(streamDescription: &aacDesc)!
let pcm16k = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false)!
let converter = AVAudioConverter(from: pcm16k, to: aacFormat)!

// enable echo cancellation
try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker])

input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
    // resample buffer → 16 kHz mono if needed, then:
    let out = AVAudioCompressedBuffer(format: aacFormat, packetCapacity: 8, maximumPacketSize: 768)
    var err: NSError?
    converter.convert(to: out, error: &err) { _, status in status.pointee = .haveData; return buffer /* 16k PCM */ }
    let aac = Data(bytes: out.data, count: Int(out.byteLength))
    webSocket.send(.data(addADTS(aac)))                    // URLSessionWebSocketTask
}
try engine.start()

func addADTS(_ aac: Data) -> Data {
    let len = aac.count + 7
    var h = Data(count: 7)
    h[0] = 0xFF; h[1] = 0xF1
    h[2] = UInt8(((2 - 1) << 6) | (8 << 2) | (1 >> 2))     // AAC LC, freqIdx 8 (16 kHz), chan
    h[3] = UInt8(((1 & 3) << 6) | (len >> 11))
    h[4] = UInt8((len >> 3) & 0xFF)
    h[5] = UInt8(((len & 7) << 5) | 0x1F)
    h[6] = 0xFC
    return h + aac
}
```
(Use `AVAudioConverter` to resample the tap buffer to 16 kHz mono before AAC conversion if the hardware
input rate differs — the tap gives you the input's native rate.)

On stop: `input.removeTap(onBus: 0); engine.stop()`, send `videoStop`, close socket.

---

## 6. ADTS header cheat-sheet (AAC-LC, 16 kHz, mono)

7 bytes, where `L = 7 + aacPayloadLength`:
```
0: 0xFF
1: 0xF1
2: 0x60                       // profile(AAC-LC)=01, sampling-freq-index(16 kHz)=1000, chan hi=0
3: 0x40 | (L >> 11)           // chan lo=01, frame-length high bits
4: (L >> 3) & 0xFF
5: ((L & 7) << 5) | 0x1F
6: 0xFC
```
Sampling-frequency index for 16 kHz is **8**. Channel config is **1** (mono). If you ever change the rate,
update byte 2's freq-index nibble and the capture/encoder rate together.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Camera plays static / "rain" | Wrong uplink codec (G.711/PCM) or bad ADTS | Send **AAC-LC 16 kHz mono** with a correct ADTS header |
| You hear static in your earpiece | Downlink decoded as the wrong codec | Downlink is **AAC (PT 19)** — decode as AAC |
| Nothing reaches the camera | `videoTalk` not sent, or wrong channel, or stream not stopped first | Send `videoTalk {index}` and `videoStop` the channel's live/playback first |
| `videoTalk` returns 400/unsupported | Server image predates intercom | Deploy the intercom build |
| Echo/feedback loop | Mic routed to local speaker | Enable AEC (`VOICE_COMMUNICATION` / `.voiceChat`), don't monitor mic locally |
| Choppy/robotic audio | Frames too large or paced wrong | ~1024-sample (64 ms) frames; send as encoded, don't batch |

---

## 8. Quick reference

| | |
|---|---|
| Start intercom | `POST /api/commands/send` → `videoTalk { index: channel }` |
| Media socket | `wss://…/api/stream/video?deviceId=&channel=&token=` (binary, bidirectional) |
| Downlink audio | frames with byte 0 = **19** → AAC, ADTS-framed, at `bytes[10..]` |
| Uplink audio | send binary messages = **AAC-LC 16 kHz mono, ADTS-framed** |
| Stop intercom | `POST videoStop { index: channel }` + close socket |
| Codec | AAC-LC, 16 kHz, mono — both directions, no exceptions |

See [live-tracking-camera.md](live-tracking-camera.md) for where intercom fits in the overall camera screen,
and [mobile-integration.md](mobile-integration.md) for the full API reference.
