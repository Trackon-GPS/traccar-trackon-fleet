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
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.config.Keys;
import org.traccar.helper.DataConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Encoder for the Jimi IoT intelligent connected vehicle terminal protocol.
 *
 * <p>Vehicle actuation goes through the downlink transparent transmission message 0x8900 with
 * transparent type 0xF1 and subcategory 0x01, whose control function byte is listed in table 24.
 * Device level actions use terminal control 0x8105 and parameter setting 0x8103.
 */
public class TrackonobdProtocolEncoder extends BaseProtocolEncoder {

    public static final int CONTROL_LOCK = 0x01;
    public static final int CONTROL_UNLOCK = 0x02;
    public static final int CONTROL_IGNITION_ON = 0x13;
    public static final int CONTROL_IGNITION_OFF = 0x14;
    public static final int CONTROL_ANTI_THEFT_ON = 0x15;
    public static final int CONTROL_ANTI_THEFT_OFF = 0x16;

    private static final int TERMINAL_CONTROL_POWER_OFF = 3;
    private static final int TERMINAL_CONTROL_RESET = 4;
    private static final int TERMINAL_CONTROL_FACTORY_RESET = 5;

    private static final int PARAMETER_REPORTING_INTERVAL = 0x0029;

    public TrackonobdProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object encodeCommand(Command command) {

        ByteBuf id = TrackonobdProtocolDecoder.encodeId(getUniqueId(command.getDeviceId()));
        try {
            return switch (command.getType()) {
                case Command.TYPE_CUSTOM -> Unpooled.wrappedBuffer(
                        DataConverter.parseHex(command.getString(Command.KEY_DATA)));
                case Command.TYPE_ENGINE_STOP -> vehicleControl(command, id, CONTROL_IGNITION_OFF);
                case Command.TYPE_ENGINE_RESUME -> vehicleControl(command, id, CONTROL_IGNITION_ON);
                case Command.TYPE_ALARM_ARM -> vehicleControl(command, id, CONTROL_ANTI_THEFT_ON);
                case Command.TYPE_ALARM_DISARM -> vehicleControl(command, id, CONTROL_ANTI_THEFT_OFF);
                case Command.TYPE_OUTPUT_CONTROL -> vehicleControl(
                        command, id, command.getInteger(Command.KEY_DATA));
                case Command.TYPE_REBOOT_DEVICE -> terminalControl(id, TERMINAL_CONTROL_RESET);
                case Command.TYPE_POWER_OFF -> terminalControl(id, TERMINAL_CONTROL_POWER_OFF);
                case Command.TYPE_FACTORY_RESET -> terminalControl(id, TERMINAL_CONTROL_FACTORY_RESET);
                case Command.TYPE_POSITION_PERIODIC -> positionPeriodic(command, id);
                case Command.TYPE_MESSAGE -> textMessage(command, id);
                default -> null;
            };
        } finally {
            id.release();
        }
    }

    /**
     * Section 9.13.1 and 9.13.2: downlink transparent transmission carrying a vehicle control code.
     */
    private ByteBuf vehicleControl(Command command, ByteBuf id, int function) {
        ByteBuf data = Unpooled.buffer();
        data.writeByte(TrackonobdProtocolDecoder.TRANSPARENT_VEHICLE_CONTROL);
        writeBcdTime(data, command.getDeviceId());
        data.writeByte(0); // reserved
        data.writeByte(TrackonobdProtocolDecoder.VEHICLE_TYPE_COMMERCIAL);
        data.writeByte(0x01); // subcategory: vehicle control
        data.writeByte(function);
        return TrackonobdProtocolDecoder.formatMessage(
                TrackonobdProtocolDecoder.MSG_TRANSPARENT_DOWNLINK, id, 0, data);
    }

    /**
     * Section 9.10: terminal control.
     */
    private ByteBuf terminalControl(ByteBuf id, int commandCharacter) {
        ByteBuf data = Unpooled.buffer();
        data.writeByte(commandCharacter);
        return TrackonobdProtocolDecoder.formatMessage(
                TrackonobdProtocolDecoder.MSG_TERMINAL_CONTROL, id, 0, data);
    }

    /**
     * Section 9.7: set the default regular reporting interval, parameter 0x0029.
     */
    private ByteBuf positionPeriodic(Command command, ByteBuf id) {
        ByteBuf data = Unpooled.buffer();
        data.writeByte(1); // number of parameters
        data.writeShort(PARAMETER_REPORTING_INTERVAL);
        data.writeByte(4); // parameter length
        data.writeInt(command.getInteger(Command.KEY_FREQUENCY));
        return TrackonobdProtocolDecoder.formatMessage(
                TrackonobdProtocolDecoder.MSG_SET_PARAMETERS, id, 0, data);
    }

    /**
     * Section 9.20: text message, terminal display and TTS.
     */
    private ByteBuf textMessage(Command command, ByteBuf id) {
        ByteBuf data = Unpooled.buffer();
        data.writeByte(0b00001100); // terminal display and TTS read
        Charset charset = Charset.isSupported("GBK") ? Charset.forName("GBK") : StandardCharsets.US_ASCII;
        data.writeCharSequence(command.getString(Command.KEY_MESSAGE), charset);
        return TrackonobdProtocolDecoder.formatMessage(
                TrackonobdProtocolDecoder.MSG_SEND_TEXT_MESSAGE, id, 0, data);
    }

    private void writeBcdTime(ByteBuf data, long deviceId) {
        String zoneName = AttributeUtil.lookup(getCacheManager(), Keys.DECODER_TIMEZONE, deviceId);
        ZoneId zone = TimeZone.getTimeZone(zoneName != null ? zoneName : "GMT+8").toZoneId();
        data.writeBytes(DataConverter.parseHex(
                DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(zone).format(Instant.now())));
    }

}
