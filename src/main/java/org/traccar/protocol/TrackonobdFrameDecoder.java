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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.traccar.BaseFrameDecoder;
import org.traccar.BaseProtocol;

/**
 * Splits the stream on the 0x7e identifier and reverses the escaping described in section 5.4.2:
 * 0x7d 0x01 stands for 0x7d and 0x7d 0x02 stands for 0x7e. Because escaping guarantees that 0x7e
 * never occurs inside a message, the next 0x7e always marks the end of the current frame.
 */
public class TrackonobdFrameDecoder extends BaseFrameDecoder {

    public static final int DELIMITER = 0x7e;
    public static final int ESCAPE = 0x7d;

    public TrackonobdFrameDecoder() {
        super(BaseProtocol.MAX_FRAME_LENGTH_LARGE);
    }

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, ByteBuf buf) throws Exception {

        while (buf.isReadable() && buf.getUnsignedByte(buf.readerIndex()) != DELIMITER) {
            buf.skipBytes(1);
        }

        if (buf.readableBytes() < 2) {
            return null;
        }

        int endIndex = buf.indexOf(buf.readerIndex() + 1, buf.writerIndex(), (byte) DELIMITER);
        if (endIndex < 0) {
            return null;
        }

        ByteBuf result = Unpooled.buffer(endIndex + 1 - buf.readerIndex());
        while (buf.readerIndex() <= endIndex) {
            int b = buf.readUnsignedByte();
            if (b == ESCAPE && buf.readerIndex() <= endIndex) {
                int extension = buf.readUnsignedByte();
                if (extension == 0x01) {
                    result.writeByte(ESCAPE);
                } else if (extension == 0x02) {
                    result.writeByte(DELIMITER);
                } else {
                    result.writeByte(b);
                    result.writeByte(extension);
                }
            } else {
                result.writeByte(b);
            }
        }

        return result;
    }

}
