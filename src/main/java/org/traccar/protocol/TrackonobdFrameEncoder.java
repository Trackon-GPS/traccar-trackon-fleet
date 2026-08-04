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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Applies the escaping described in section 5.4.2 to everything between the two 0x7e identifiers.
 * The leading and trailing identifiers are written through untouched.
 */
public class TrackonobdFrameEncoder extends MessageToByteEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {

        int startIndex = msg.readerIndex();
        while (msg.isReadable()) {
            int index = msg.readerIndex();
            int b = msg.readUnsignedByte();
            if (b == TrackonobdFrameDecoder.ESCAPE) {
                out.writeByte(TrackonobdFrameDecoder.ESCAPE);
                out.writeByte(0x01);
            } else if (b == TrackonobdFrameDecoder.DELIMITER && index != startIndex && msg.isReadable()) {
                out.writeByte(TrackonobdFrameDecoder.ESCAPE);
                out.writeByte(0x02);
            } else {
                out.writeByte(b);
            }
        }
    }

}
