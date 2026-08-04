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
import java.util.Locale;
import java.util.Set;
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

    private static final Set<Integer> PARAMETERS_BYTE = Set.of(
            0x0084, 0xF00F, 0xF010, 0xF012, 0xF013, 0xF015, 0xF016, 0xF017, 0xF018, 0xF019,
            0xF01A, 0xF01B, 0xF01F, 0xF020, 0xF024, 0xF20A, 0xF215);

    private static final Set<Integer> PARAMETERS_WORD = Set.of(
            0x0031, 0x005B, 0x005C, 0x005D, 0x005E, 0xF000, 0xF001, 0xF203, 0xF204, 0xF205,
            0xF206, 0xF207, 0xF208, 0xF209, 0xF20B, 0xF20C, 0xF20D, 0xF20E, 0xF216, 0xF217,
            0xF218, 0xF219);

    private static final Set<Integer> PARAMETERS_STRING = Set.of(
            0x0010, 0x0011, 0x0012, 0x0013, 0x0083, 0xF005, 0xF006, 0xF007, 0xF008, 0xF01C,
            0xF01D, 0xF01E, 0xF021, 0xF022, 0xF025);

    public TrackonobdProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object encodeCommand(Command command) {

        ByteBuf id = TrackonobdProtocolDecoder.encodeId(getUniqueId(command.getDeviceId()));
        try {
            return switch (command.getType()) {
                case Command.TYPE_CUSTOM -> customFrame(command, id);
                case Command.TYPE_CONFIGURATION -> configuration(command, id);
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
     * A custom command is either a complete pre-built frame given as hex, recognised by the leading
     * and trailing 0x7e identifiers and passed through untouched apart from the escaping applied by
     * the frame encoder, or an ASCII command such as {@code GMT#}.
     *
     * <p>The ASCII form is carried by downlink transparent transmission 0x8900 with transparent
     * type 0xF0. This document does not define an ASCII command channel; the framing follows what
     * {@link Jt808ProtocolEncoder} already sends to Jimi JC series terminals, which share this
     * manufacturer's command set.
     */
    private ByteBuf customFrame(Command command, ByteBuf id) {

        String data = command.getString(Command.KEY_DATA);
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException(
                    "Custom command requires data: either an ASCII command such as GMT# "
                            + "or a whole frame in hex beginning and ending with 7e");
        }
        data = data.trim();

        String compact = data.replaceAll("\\s", "");
        boolean frame = compact.length() >= 4
                && compact.toLowerCase(Locale.ROOT).startsWith("7e")
                && compact.toLowerCase(Locale.ROOT).endsWith("7e")
                && compact.matches("(?i)[0-9a-f]+");
        if (frame) {
            if (compact.length() % 2 != 0) {
                throw new IllegalArgumentException("Hex frame must have an even number of characters");
            }
            return Unpooled.wrappedBuffer(DataConverter.parseHex(compact));
        }

        ByteBuf body = Unpooled.buffer();
        body.writeByte(TrackonobdProtocolDecoder.TRANSPARENT_ONLINE_COMMAND);
        body.writeCharSequence(data, StandardCharsets.US_ASCII);
        return TrackonobdProtocolDecoder.formatMessage(
                TrackonobdProtocolDecoder.MSG_TRANSPARENT_DOWNLINK, id, 0, body);
    }

    /**
     * Sections 9.7 and 9.8: parameter setting 0x8103 and parameter query 0x8106.
     *
     * <p>The data is a comma separated list. A leading question mark makes it a query, otherwise
     * each item assigns a value, for example {@code F00E=30,F00F=1}. Widths come from table 4; an
     * unrecognised parameter defaults to a double word and can be overridden as {@code F00E:2=30}.
     */
    private ByteBuf configuration(Command command, ByteBuf id) {

        String data = command.getString(Command.KEY_DATA);
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("Configuration command requires data, for example F00E=30");
        }
        data = data.trim();

        ByteBuf body = Unpooled.buffer();
        boolean query = data.startsWith("?");
        String[] items = (query ? data.substring(1) : data).split(",");
        body.writeByte(items.length);

        for (String item : items) {
            String entry = item.trim();
            if (query) {
                body.writeShort(parseParameterId(entry));
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator < 0) {
                body.release();
                throw new IllegalArgumentException("Expected parameter=value but found " + entry);
            }
            String name = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1).trim();

            Integer width = null;
            int widthMark = name.indexOf(':');
            if (widthMark >= 0) {
                width = Integer.parseInt(name.substring(widthMark + 1).trim());
                name = name.substring(0, widthMark).trim();
            }

            int parameterId = parseParameterId(name);
            body.writeShort(parameterId);
            writeParameterValue(body, parameterId, width, value);
        }

        return TrackonobdProtocolDecoder.formatMessage(
                query ? TrackonobdProtocolDecoder.MSG_QUERY_PARAMETERS
                        : TrackonobdProtocolDecoder.MSG_SET_PARAMETERS, id, 0, body);
    }

    private int parseParameterId(String name) {
        String value = name.startsWith("0x") || name.startsWith("0X") ? name.substring(2) : name;
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter id must be hexadecimal, for example F00E, but found " + name);
        }
    }

    private void writeParameterValue(ByteBuf body, int parameterId, Integer width, String value) {
        if (width == null && PARAMETERS_STRING.contains(parameterId)) {
            body.writeByte(value.length());
            body.writeCharSequence(value, StandardCharsets.US_ASCII);
            return;
        }
        int length = width != null ? width : parameterWidth(parameterId);
        long number;
        try {
            number = value.startsWith("0x") || value.startsWith("0X")
                    ? Long.parseLong(value.substring(2), 16)
                    : Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter " + String.format("%04X", parameterId)
                    + " expects a number but found " + value);
        }
        body.writeByte(length);
        switch (length) {
            case 1 -> body.writeByte((int) number);
            case 2 -> body.writeShort((int) number);
            case 4 -> body.writeInt((int) number);
            default -> throw new IllegalArgumentException("Unsupported parameter width " + length);
        }
    }

    private int parameterWidth(int parameterId) {
        if (PARAMETERS_BYTE.contains(parameterId)) {
            return 1;
        }
        if (PARAMETERS_WORD.contains(parameterId)) {
            return 2;
        }
        return 4;
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
