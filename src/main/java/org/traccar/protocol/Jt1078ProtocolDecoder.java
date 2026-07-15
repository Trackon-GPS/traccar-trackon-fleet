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
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.database.DeviceLookupService;
import org.traccar.helper.BitUtil;
import org.traccar.media.VideoStreamManager;
import org.traccar.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import java.net.SocketAddress;

public class Jt1078ProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(Jt1078ProtocolDecoder.class);

    private DeviceLookupService deviceLookupService;
    private VideoStreamManager streamManager;

    private CompositeByteBuf frameBuffer;
    private int frameDataType;
    private long frameTimestamp;
    private int framePayloadType;

    private long streamDeviceId;
    private int streamChannel;
    private boolean sourceRegistered;

    private VideoStreamManager.AudioSink audioSink;
    private int talkSequence;
    private long talkTimestamp;

    public Jt1078ProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    @Inject
    public void setDeviceLookupService(DeviceLookupService deviceLookupService) {
        this.deviceLookupService = deviceLookupService;
    }

    @Inject
    public void setStreamManager(VideoStreamManager streamManager) {
        this.streamManager = streamManager;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        buf.readUnsignedInt(); // header
        buf.readUnsignedByte(); // V/P/X/CC
        int payloadType = buf.readUnsignedByte() & 0x7F; // M/PT
        buf.readUnsignedShort(); // index

        int idLength = buf.getUnsignedShort(buf.readerIndex()) == 0 ? 10 : 6;
        ByteBuf idSlice = buf.readSlice(idLength);
        byte[] rawId = ByteBufUtil.getBytes(idSlice, idSlice.readerIndex(), idLength, true);
        String uniqueId = Jt808ProtocolDecoder.decodeId(idSlice);
        int videoChannel = buf.readUnsignedByte();
        int rawType = buf.readUnsignedByte();
        int dataType = BitUtil.from(rawType, 4);
        int subpackageType = BitUtil.to(rawType, 4);
        long timestamp = buf.readLong();

        if (dataType <= 2) {
            buf.skipBytes(4); // i-frame interval + frame interval
        }

        int bodyLength = buf.readUnsignedShort();

        if (bodyLength == 0 || dataType > 3) {
            return null; // keep video (0-2) and audio (3); drop pass-through and empty frames
        }

        Device device = deviceLookupService.lookup(new String[]{uniqueId});
        if (device == null) {
            return null;
        }

        streamDeviceId = device.getId();
        streamChannel = videoChannel;

        // A new connection supersedes any earlier one for this device/channel (the camera can leave
        // a previous live/playback connection open). Register this connection as the active source.
        if (!sourceRegistered) {
            streamManager.setActiveSource(streamDeviceId, videoChannel, this);
            final Channel talkChannel = channel;
            final SocketAddress talkAddress = remoteAddress;
            final byte[] talkId = rawId;
            final int talkLogicalChannel = videoChannel;
            audioSink = audio -> sendAudioFrame(talkChannel, talkAddress, talkId, talkLogicalChannel, audio);
            streamManager.registerAudioSink(streamDeviceId, videoChannel, audioSink);
            sourceRegistered = true;
        }

        ByteBuf body = buf.readRetainedSlice(bodyLength);

        if (subpackageType == 0) {
            boolean isKeyFrame = dataType == 0;
            streamManager.handleFrame(streamDeviceId, videoChannel, this, body, timestamp, isKeyFrame, payloadType);
            body.release();
        } else if (subpackageType == 1) {
            if (frameBuffer != null) {
                frameBuffer.release();
            }
            frameBuffer = Unpooled.compositeBuffer();
            frameBuffer.addComponent(true, body);
            frameDataType = dataType;
            frameTimestamp = timestamp;
            framePayloadType = payloadType;
        } else if (subpackageType == 3) {
            if (frameBuffer != null) {
                frameBuffer.addComponent(true, body);
            } else {
                body.release();
            }
        } else if (subpackageType == 2) {
            if (frameBuffer != null) {
                frameBuffer.addComponent(true, body);
                boolean isKeyFrame = frameDataType == 0;
                streamManager.handleFrame(
                        streamDeviceId, videoChannel, this, frameBuffer, frameTimestamp, isKeyFrame, framePayloadType);
                frameBuffer.release();
                frameBuffer = null;
            } else {
                body.release();
            }
        } else {
            body.release();
        }

        return null;
    }

    /**
     * Wraps one intercom audio frame from the app in a JT1078 RTP packet and writes it back to the
     * camera over its own connection (two-way voice, data type 3). The first byte of each message is
     * the JT1078 payload type (codec) the client is sending — e.g. 6 = G.711A, 16 = S16BE PCM,
     * 19 = AAC — so we can match whatever format the camera accepts without a server change.
     */
    private synchronized void sendAudioFrame(
            Channel channel, SocketAddress remoteAddress, byte[] id, int logicalChannel, byte[] audio) {
        if (channel == null || !channel.isActive() || audio.length < 2) {
            return;
        }
        int payloadType = audio[0] & 0x7F;
        int bodyLength = audio.length - 1;
        if (talkSequence == 0) {
            LOGGER.info("intercom diag: writing first RTP audio to camera channel={} active={} PT={} bodyLen={}",
                    logicalChannel, channel.isActive(), payloadType, bodyLength);
        }
        ByteBuf packet = Unpooled.buffer(30 + bodyLength);
        packet.writeInt(0x30316364); // RTP frame header identifier
        packet.writeByte(0x81); // V=2, P=0, X=0, CC=1
        packet.writeByte(0x80 | payloadType); // M=1, PT from the client
        packet.writeShort(talkSequence++ & 0xFFFF);
        packet.writeBytes(id); // SIM (the same identifier the camera streams with)
        packet.writeByte(logicalChannel);
        packet.writeByte(0x30); // data type 3 (audio), subpackage 0 (atomic)
        packet.writeLong(talkTimestamp); // relative timestamp, milliseconds
        talkTimestamp += 40;
        packet.writeShort(bodyLength);
        packet.writeBytes(audio, 1, bodyLength);
        channel.writeAndFlush(new NetworkMessage(packet, remoteAddress));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        if (streamDeviceId > 0) {
            streamManager.clearActiveSource(streamDeviceId, streamChannel, this);
            if (audioSink != null) {
                streamManager.clearAudioSink(streamDeviceId, streamChannel, audioSink);
                audioSink = null;
            }
            streamManager.removeStream(streamDeviceId, streamChannel);
        }
        if (frameBuffer != null) {
            frameBuffer.release();
            frameBuffer = null;
        }
    }

}
