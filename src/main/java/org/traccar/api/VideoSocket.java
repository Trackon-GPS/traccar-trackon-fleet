/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api;

import io.netty.buffer.ByteBuf;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.media.VideoStreamManager;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

/**
 * Streams raw video frames to a client with minimal latency. Each frame is sent as a binary
 * message with a 10-byte header followed by the Annex-B NAL data:
 * <pre>
 *   byte 0      payload type (98 = H.264, 99 = H.265)
 *   byte 1      1 if key frame, otherwise 0
 *   bytes 2-9   frame timestamp in milliseconds (big-endian int64)
 *   bytes 10..  Annex-B encoded NAL units
 * </pre>
 * For two-way intercom the client sends binary messages back on the same socket, each carrying a
 * raw G.711A (PCMA) audio frame, which is forwarded to the camera over its JT1078 connection.
 */
public class VideoSocket implements Session.Listener.AutoDemanding, VideoStreamManager.FrameListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoSocket.class);

    private static final int HEADER_LENGTH = 10;

    private final VideoStreamManager streamManager;
    private final long deviceId;
    private final int channel;

    private volatile Session session;

    public VideoSocket(VideoStreamManager streamManager, long deviceId, int channel) {
        this.streamManager = streamManager;
        this.deviceId = deviceId;
        this.channel = channel;
    }

    @Override
    public void onWebSocketOpen(Session session) {
        this.session = session;
        streamManager.addSubscriber(deviceId, channel, this);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        streamManager.removeSubscriber(deviceId, channel, this);
        session = null;
        callback.succeed();
    }

    @Override
    public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
        if (payload.hasRemaining()) {
            byte[] audio = new byte[payload.remaining()];
            payload.get(audio);
            streamManager.sendAudioToCamera(deviceId, channel, audio);
        }
        callback.succeed();
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        if (!(cause instanceof ClosedChannelException)) {
            LOGGER.warn("Video socket error", cause);
        }
    }

    @Override
    public void onFrame(ByteBuf nalData, long timestamp, boolean keyFrame, int payloadType) {
        Session current = session;
        if (current == null || !current.isOpen()) {
            return;
        }
        int length = nalData.readableBytes();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + length);
        buffer.put((byte) payloadType);
        buffer.put((byte) (keyFrame ? 1 : 0));
        buffer.putLong(timestamp);
        nalData.getBytes(nalData.readerIndex(), buffer.array(), HEADER_LENGTH, length);
        buffer.position(HEADER_LENGTH + length);
        buffer.flip();
        current.sendBinary(buffer, Callback.NOOP);
    }

}
