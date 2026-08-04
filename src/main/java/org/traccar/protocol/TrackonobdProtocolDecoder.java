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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.BcdUtil;
import org.traccar.helper.BitUtil;
import org.traccar.helper.Checksum;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Decoder for the Jimi IoT intelligent connected vehicle terminal protocol (VG502 and similar).
 *
 * <p>Structurally a JT/T 808-2013 derivative: 0x7e delimited frames, a header of message id,
 * body attributes, a six byte terminal serial number and a sequence number, then an XOR check byte.
 * The payload semantics diverge from plain JT808, which is why this lives apart from
 * {@link Jt808ProtocolDecoder}.
 */
public class TrackonobdProtocolDecoder extends BaseProtocolDecoder {

    public TrackonobdProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    public static final int MSG_TERMINAL_GENERAL_RESPONSE = 0x0001;
    public static final int MSG_HEARTBEAT = 0x0002;
    public static final int MSG_TERMINAL_LOGOUT = 0x0003;
    public static final int MSG_TERMINAL_REGISTER = 0x0100;
    public static final int MSG_TERMINAL_AUTH = 0x0102;
    public static final int MSG_QUERY_PARAMETERS_RESPONSE = 0x0104;
    public static final int MSG_UPGRADE_RESULT = 0x0108;
    public static final int MSG_LOCATION_REPORT = 0x0200;
    public static final int MSG_TEXT_MESSAGE_RESPONSE = 0x0300;
    public static final int MSG_CAN_DATA = 0x0705;
    public static final int MSG_TRANSPARENT = 0x0900;

    public static final int MSG_GENERAL_RESPONSE = 0x8001;
    public static final int MSG_TERMINAL_REGISTER_RESPONSE = 0x8100;
    public static final int MSG_SET_PARAMETERS = 0x8103;
    public static final int MSG_TERMINAL_CONTROL = 0x8105;
    public static final int MSG_QUERY_PARAMETERS = 0x8106;
    public static final int MSG_SEND_TEXT_MESSAGE = 0x8300;
    public static final int MSG_SET_CIRCULAR_AREA = 0x8600;
    public static final int MSG_DELETE_CIRCULAR_AREA = 0x8601;
    public static final int MSG_SET_SQUARE_AREA = 0x8602;
    public static final int MSG_DELETE_SQUARE_AREA = 0x8603;
    public static final int MSG_TRANSPARENT_DOWNLINK = 0x8900;

    public static final int TRANSPARENT_VEHICLE_DATA = 0xF0;
    public static final int TRANSPARENT_VEHICLE_CONTROL = 0xF1;
    public static final int TRANSPARENT_PERIPHERAL_DATA = 0xF3;
    /** Undocumented here, but the ASCII command channel Jimi terminals accept downlink. */
    public static final int TRANSPARENT_ONLINE_COMMAND = 0xF0;

    public static final int VEHICLE_TYPE_COMMERCIAL = 0x01;
    public static final int VEHICLE_TYPE_PASSENGER = 0x02;
    public static final int VEHICLE_TYPE_FORD = 0x04;

    public static final int RESULT_SUCCESS = 0;

    private static final int DELIMITER = 0x7e;
    private static final int ID_LENGTH = 6;

    /**
     * Builds a complete frame. Escaping is applied later by {@link TrackonobdFrameEncoder}.
     */
    public static ByteBuf formatMessage(int type, ByteBuf id, int index, ByteBuf data) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(DELIMITER);
        buf.writeShort(type);
        buf.writeShort(data.readableBytes());
        buf.writeBytes(id, id.readerIndex(), id.readableBytes());
        buf.writeShort(index);
        buf.writeBytes(data);
        data.release();
        buf.writeByte(Checksum.xor(buf.nioBuffer(1, buf.readableBytes() - 1)));
        buf.writeByte(DELIMITER);
        return buf;
    }

    /**
     * Section 5.4.3: the terminal serial number is the first 14 digits of the IMEI expressed as a
     * six byte value, so the IMEI is recovered by appending a recomputed Luhn check digit.
     */
    static String decodeId(ByteBuf id) {
        long value = ((long) id.getUnsignedShort(id.readerIndex()) << 32) + id.getUnsignedInt(id.readerIndex() + 2);
        return value + String.valueOf(Checksum.luhn(value));
    }

    static ByteBuf encodeId(String uniqueId) {
        ByteBuf buf = Unpooled.buffer(ID_LENGTH);
        String digits = uniqueId.length() > 14 ? uniqueId.substring(0, 14) : uniqueId;
        long value = Long.parseLong(digits);
        buf.writeShort((int) (value >> 32));
        buf.writeInt((int) value);
        return buf;
    }

    private void sendGeneralResponse(
            Channel channel, SocketAddress remoteAddress, ByteBuf id, int type, int index) {
        if (channel != null) {
            ByteBuf response = Unpooled.buffer();
            response.writeShort(index);
            response.writeShort(type);
            response.writeByte(RESULT_SUCCESS);
            channel.writeAndFlush(new NetworkMessage(
                    formatMessage(MSG_GENERAL_RESPONSE, id, index, response), remoteAddress));
        }
    }

    private Date readDate(ByteBuf buf, TimeZone timeZone) {
        return new DateBuilder(timeZone)
                .setYear(BcdUtil.readInteger(buf, 2))
                .setMonth(BcdUtil.readInteger(buf, 2))
                .setDay(BcdUtil.readInteger(buf, 2))
                .setHour(BcdUtil.readInteger(buf, 2))
                .setMinute(BcdUtil.readInteger(buf, 2))
                .setSecond(BcdUtil.readInteger(buf, 2))
                .getDate();
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        buf.readUnsignedByte(); // start delimiter
        int type = buf.readUnsignedShort();
        int attribute = buf.readUnsignedShort();
        ByteBuf id = buf.readSlice(ID_LENGTH);
        int index = buf.readUnsignedShort();

        if (BitUtil.check(attribute, 13)) {
            buf.readUnsignedShort(); // total packet count
            buf.readUnsignedShort(); // packet number
        }

        // trailing bytes are the check character and the closing delimiter
        int available = Math.max(buf.readableBytes() - 2, 0);
        ByteBuf body = buf.readSlice(Math.min(BitUtil.to(attribute, 10), available));

        DeviceSession deviceSession = getDeviceSession(
                channel, remoteAddress, decodeId(id), ByteBufUtil.hexDump(id));
        if (deviceSession == null) {
            return null;
        }

        if (!deviceSession.contains(DeviceSession.KEY_TIMEZONE)) {
            deviceSession.set(DeviceSession.KEY_TIMEZONE, getTimeZone(deviceSession.getDeviceId(), "GMT+8"));
        }

        switch (type) {
            case MSG_TERMINAL_REGISTER:
                return decodeRegister(channel, remoteAddress, id, index, deviceSession, body);
            case MSG_HEARTBEAT:
            case MSG_TERMINAL_AUTH:
            case MSG_TERMINAL_LOGOUT:
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return null;
            case MSG_TERMINAL_GENERAL_RESPONSE:
                // section 7.1: this message is itself the reply to a platform message, so
                // acknowledging it would answer an answer
                return decodeGeneralResponse(deviceSession, body);
            case MSG_UPGRADE_RESULT:
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeUpgradeResult(deviceSession, body);
            case MSG_QUERY_PARAMETERS_RESPONSE:
                return decodeParameters(deviceSession, body);
            case MSG_TEXT_MESSAGE_RESPONSE:
                return decodeTextResponse(deviceSession, body);
            case MSG_LOCATION_REPORT:
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeLocation(deviceSession, body);
            case MSG_CAN_DATA:
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeCanData(deviceSession, body);
            case MSG_TRANSPARENT:
                sendGeneralResponse(channel, remoteAddress, id, type, index);
                return decodeTransparent(deviceSession, body);
            default:
                return null;
        }
    }

    /**
     * Fixed width string fields are padded with nulls rather than spaces.
     */
    private String readTrimmed(ByteBuf buf, int length) {
        return buf.readCharSequence(length, StandardCharsets.US_ASCII)
                .toString().replace("\0", "").trim();
    }

    private Position emptyPosition(DeviceSession deviceSession) {
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);
        return position;
    }

    private Position decodeRegister(
            Channel channel, SocketAddress remoteAddress, ByteBuf id, int index,
            DeviceSession deviceSession, ByteBuf buf) {

        if (channel != null) {
            ByteBuf response = Unpooled.buffer();
            response.writeShort(index);
            response.writeByte(RESULT_SUCCESS);
            // the authentication key the terminal is expected to echo back in message 0x0102
            response.writeCharSequence(decodeId(id), StandardCharsets.US_ASCII);
            channel.writeAndFlush(new NetworkMessage(
                    formatMessage(MSG_TERMINAL_REGISTER_RESPONSE, id, index, response), remoteAddress));
        }

        if (buf.readableBytes() < 37) {
            return null;
        }

        Position position = emptyPosition(deviceSession);

        buf.readUnsignedShort(); // manufacturer id, the province id in plain JT/T 808-2013
        buf.readUnsignedShort(); // authentication level, the city id in plain JT/T 808-2013
        String manufacturer = readTrimmed(buf, 5);
        if (!manufacturer.isEmpty()) {
            position.set("manufacturer", manufacturer);
        }

        // Table 8 labels this the SIM ICCID, but devices that follow plain JT/T 808-2013 place the
        // terminal model at the same offset, so the field is classified by its content: an ICCID is
        // 19 or 20 digits, whereas a model is a short alphanumeric string such as "V521H".
        String descriptor = readTrimmed(buf, 20);
        if (descriptor.matches("\\d{18,20}")) {
            position.set(Position.KEY_ICCID, descriptor);
        } else if (!descriptor.isEmpty()) {
            position.set("model", descriptor);
        }

        buf.skipBytes(7); // terminal serial number
        int plateColour = buf.readUnsignedByte();
        String identification = readTrimmed(buf, buf.readableBytes());
        if (!identification.isEmpty()) {
            // section 9.3: with no license plate the field carries the VIN instead
            position.set(plateColour == 0 ? Position.KEY_VIN : "plateNumber", identification);
        }

        return position;
    }

    /**
     * Table 6: the terminal's answer to a platform message, surfaced so that the outcome of a
     * command is visible rather than silently dropped.
     */
    private Position decodeGeneralResponse(DeviceSession deviceSession, ByteBuf buf) {
        if (buf.readableBytes() < 5) {
            return null;
        }
        Position position = emptyPosition(deviceSession);
        buf.readUnsignedShort(); // response sequence number
        int responseType = buf.readUnsignedShort();
        int result = buf.readUnsignedByte();
        String description = switch (result) {
            case 0 -> "success";
            case 1 -> "failure";
            case 2 -> "message error";
            case 3 -> "not supported";
            case 4 -> "key exchange succeeded";
            case 5 -> "key exchange failed";
            default -> "result " + result;
        };
        position.set(Position.KEY_RESULT, String.format("%04X: %s", responseType, description));
        return position;
    }

    private Position decodeUpgradeResult(DeviceSession deviceSession, ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return null;
        }
        Position position = emptyPosition(deviceSession);
        position.set("upgradeType", buf.readUnsignedByte());
        position.set(Position.KEY_RESULT, "upgrade:" + buf.readUnsignedByte());
        return position;
    }

    private Position decodeParameters(DeviceSession deviceSession, ByteBuf buf) {
        if (buf.readableBytes() < 3) {
            return null;
        }
        Position position = emptyPosition(deviceSession);
        buf.readUnsignedShort(); // response sequence number
        int count = buf.readUnsignedByte();
        for (int i = 0; i < count && buf.readableBytes() >= 3; i++) {
            int parameterId = buf.readUnsignedShort();
            int length = Math.min(buf.readUnsignedByte(), buf.readableBytes());
            ByteBuf value = buf.readSlice(length);
            switch (parameterId) {
                case 0xF005 -> position.set(Position.KEY_VERSION_HW,
                        value.readCharSequence(length, StandardCharsets.US_ASCII).toString().trim());
                case 0xF006 -> position.set(Position.KEY_VERSION_FW,
                        value.readCharSequence(length, StandardCharsets.US_ASCII).toString().trim());
                case 0xF007 -> position.set("simInfo",
                        value.readCharSequence(length, StandardCharsets.US_ASCII).toString().trim());
                case 0xF025 -> position.set(Position.KEY_VIN,
                        value.readCharSequence(length, StandardCharsets.US_ASCII).toString().trim());
                default -> position.set(
                        String.format("parameter%04X", parameterId), ByteBufUtil.hexDump(value));
            }
        }
        return position;
    }

    private Position decodeTextResponse(DeviceSession deviceSession, ByteBuf buf) {
        if (buf.readableBytes() < 3) {
            return null;
        }
        Position position = emptyPosition(deviceSession);
        buf.readUnsignedShort(); // response sequence number
        int encoding = buf.readUnsignedByte();
        position.set(Position.KEY_RESULT, buf.readCharSequence(buf.readableBytes(),
                encoding == 1 ? StandardCharsets.UTF_16BE : StandardCharsets.US_ASCII).toString().trim());
        return position;
    }

    /**
     * Table 19. Bits the document leaves undefined are ignored.
     */
    private void decodeAlarm(Position position, long value) {
        if (BitUtil.check(value, 0)) {
            position.addAlarm(Position.ALARM_SOS);
        }
        if (BitUtil.check(value, 1) || BitUtil.check(value, 13)) {
            position.addAlarm(Position.ALARM_OVERSPEED);
        }
        if (BitUtil.check(value, 2) || BitUtil.check(value, 14) || BitUtil.check(value, 18)) {
            position.addAlarm(Position.ALARM_FATIGUE_DRIVING);
        }
        if (BitUtil.check(value, 3)) {
            position.addAlarm(Position.ALARM_GENERAL);
        }
        if (BitUtil.check(value, 4) || BitUtil.check(value, 9) || BitUtil.check(value, 10)
                || BitUtil.check(value, 11) || BitUtil.check(value, 12) || BitUtil.check(value, 24)) {
            position.addAlarm(Position.ALARM_FAULT);
        }
        if (BitUtil.check(value, 5) || BitUtil.check(value, 6)) {
            position.addAlarm(Position.ALARM_GPS_ANTENNA_CUT);
        }
        if (BitUtil.check(value, 7)) {
            position.addAlarm(Position.ALARM_LOW_POWER);
        }
        if (BitUtil.check(value, 8)) {
            position.addAlarm(Position.ALARM_POWER_CUT);
        }
        if (BitUtil.check(value, 19)) {
            position.addAlarm(Position.ALARM_PARKING);
        }
        if (BitUtil.check(value, 20) || BitUtil.check(value, 21) || BitUtil.check(value, 23)) {
            position.addAlarm(Position.ALARM_GEOFENCE);
        }
        if (BitUtil.check(value, 25)) {
            position.addAlarm(Position.ALARM_FUEL_LEAK);
        }
        if (BitUtil.check(value, 26)) {
            position.addAlarm(Position.ALARM_TAMPERING);
        }
        if (BitUtil.check(value, 27)) {
            position.addAlarm(Position.ALARM_POWER_ON);
        }
        if (BitUtil.check(value, 28)) {
            position.addAlarm(Position.ALARM_MOVEMENT);
        }
        if (BitUtil.check(value, 29) || BitUtil.check(value, 30)) {
            position.addAlarm(Position.ALARM_ACCIDENT);
        }
        if (BitUtil.check(value, 31)) {
            position.addAlarm(Position.ALARM_DOOR);
        }
    }

    /**
     * Table 20.
     */
    private void decodeStatus(Position position, long status) {
        position.set(Position.KEY_IGNITION, BitUtil.check(status, 0));
        position.set("loadStatus", (int) BitUtil.between(status, 8, 10));
        position.set(Position.KEY_BLOCKED, BitUtil.check(status, 10));
        position.set("circuitDisconnected", BitUtil.check(status, 11));
        position.set("doorLocked", BitUtil.check(status, 12));
        position.set(Position.KEY_DOOR, BitUtil.between(status, 13, 18) != 0);
        position.set("positioningType", (int) BitUtil.between(status, 29, 31));
        if (BitUtil.check(status, 31)) {
            position.set(Position.KEY_ARCHIVE, true);
        }
    }

    private Position decodeLocation(DeviceSession deviceSession, ByteBuf buf) {

        if (buf.readableBytes() < 28) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        decodeAlarm(position, buf.readUnsignedInt());

        long status = buf.readUnsignedInt();
        decodeStatus(position, status);

        double latitude = buf.readUnsignedInt() / 1000000.0;
        double longitude = buf.readUnsignedInt() / 1000000.0;
        position.setValid(BitUtil.check(status, 1));
        position.setLatitude(BitUtil.check(status, 2) ? -latitude : latitude);
        position.setLongitude(BitUtil.check(status, 3) ? -longitude : longitude);

        position.setAltitude(buf.readShort());
        position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedShort() / 10.0));
        position.setCourse(buf.readUnsignedShort());
        position.setTime(readDate(buf, deviceSession.get(DeviceSession.KEY_TIMEZONE)));

        Network network = new Network();
        decodeExtensions(position, network, buf);
        if (network.getCellTowers() != null || network.getWifiAccessPoints() != null) {
            position.setNetwork(network);
        }

        return position;
    }

    /**
     * Tables 21 to 25: additional location information items, encoded as id, length, value.
     */
    private void decodeExtensions(Position position, Network network, ByteBuf buf) {
        while (buf.readableBytes() >= 2) {
            int subtype = buf.readUnsignedByte();
            int length = buf.readUnsignedByte();
            if (length > buf.readableBytes()) {
                return;
            }
            int endIndex = buf.readerIndex() + length;
            switch (subtype) {
                case 0x2A -> {
                    int io = buf.readUnsignedShort();
                    position.set("deepSleep", BitUtil.check(io, 0));
                    position.set("sleep", BitUtil.check(io, 1));
                }
                case 0x30 -> position.set(Position.KEY_RSSI, buf.readUnsignedByte());
                case 0x31 -> position.set(Position.KEY_SATELLITES, buf.readUnsignedByte());
                case 0xE4 -> decodeDeviceStatus(position, buf, endIndex);
                case 0xE8 -> decodeAdditionalId(position, network, buf, endIndex);
                default -> {
                }
            }
            buf.readerIndex(endIndex);
        }
    }

    /**
     * Table 25: terminal real time status extension information.
     */
    private void decodeDeviceStatus(Position position, ByteBuf buf, int endIndex) {
        if (endIndex - buf.readerIndex() < 7) {
            return;
        }
        position.set(Position.KEY_CHARGE, buf.readUnsignedByte() == 0x01);
        int batteryLevel = buf.readUnsignedByte();
        if (batteryLevel >= 1 && batteryLevel <= 6) {
            position.set(Position.KEY_BATTERY_LEVEL, batteryLevel * 100 / 6);
        }
        position.set(Position.KEY_BATTERY, buf.readUnsignedShort() / 100.0);
        position.set("gsmLevel", buf.readUnsignedByte());
        position.set(Position.KEY_POWER, buf.readUnsignedShort() / 100.0);
    }

    /**
     * Table 24: the 0xE8 item carries either base station data or peripheral status.
     */
    private void decodeAdditionalId(Position position, Network network, ByteBuf buf, int endIndex) {
        if (endIndex - buf.readerIndex() < 2) {
            return;
        }
        int additionalId = buf.readUnsignedShort();
        switch (additionalId) {
            case 0x2000 -> decodeCellTowers(network, buf, endIndex);
            case 0x2005 -> decodePeripherals(position, buf, endIndex);
            default -> {
            }
        }
    }

    /**
     * Table 70. Included only when there is no GNSS fix.
     */
    private void decodeCellTowers(Network network, ByteBuf buf, int endIndex) {
        if (endIndex - buf.readerIndex() < 6) {
            return;
        }
        buf.readUnsignedByte(); // packet length
        int mcc = buf.readUnsignedShort();
        int mnc = buf.readUnsignedShort();
        int count = buf.readUnsignedByte();
        for (int i = 0; i < count && endIndex - buf.readerIndex() >= 7; i++) {
            network.addCellTower(CellTower.from(
                    mcc, mnc, buf.readUnsignedShort(), buf.readUnsignedInt(), buf.readUnsignedByte()));
        }
    }

    /**
     * Table 71.
     */
    private void decodePeripherals(Position position, ByteBuf buf, int endIndex) {
        if (endIndex - buf.readerIndex() < 4) {
            return;
        }
        buf.readUnsignedByte(); // packet length
        if (buf.readUnsignedShort() == 0x2002 && endIndex - buf.readerIndex() >= 8) {
            buf.readUnsignedByte(); // content length
            position.set("bluetooth", buf.readUnsignedByte() == 1);
            position.set("bluetoothMac", ByteBufUtil.hexDump(buf.readSlice(6)));
        }
    }

    /**
     * Section 9.19: raw CAN frames, twelve bytes each.
     */
    private Position decodeCanData(DeviceSession deviceSession, ByteBuf buf) {

        if (buf.readableBytes() < 8) {
            return null;
        }

        Position position = emptyPosition(deviceSession);

        int count = buf.readUnsignedShort();
        buf.skipBytes(5); // reception time of the first frame, hh-mm-ss-msms

        List<String> frames = new ArrayList<>();
        for (int i = 0; i < count && buf.readableBytes() >= 12; i++) {
            long canId = buf.readUnsignedInt();
            frames.add(String.format(
                    "{\"channel\":%d,\"extended\":%b,\"averaged\":%b,\"id\":\"%X\",\"data\":\"%s\"}",
                    BitUtil.check(canId, 31) ? 2 : 1,
                    BitUtil.check(canId, 30),
                    BitUtil.check(canId, 29),
                    BitUtil.to(canId, 29),
                    ByteBufUtil.hexDump(buf.readSlice(8))));
        }

        position.set("canData", "[" + String.join(",", frames) + "]");

        return position;
    }

    private Position decodeTransparent(DeviceSession deviceSession, ByteBuf buf) {

        if (!buf.isReadable()) {
            return null;
        }

        int transparentType = buf.readUnsignedByte();
        return switch (transparentType) {
            case TRANSPARENT_VEHICLE_DATA -> decodeVehicleData(deviceSession, buf);
            case TRANSPARENT_PERIPHERAL_DATA -> decodePeripheralData(deviceSession, buf);
            default -> null;
        };
    }

    /**
     * Section 9.14.1: uplink transparent transmission of terminal data, message type 0xF0.
     */
    private Position decodeVehicleData(DeviceSession deviceSession, ByteBuf buf) {

        if (buf.readableBytes() < 9) {
            return null;
        }

        TimeZone timeZone = deviceSession.get(DeviceSession.KEY_TIMEZONE);

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, readDate(buf, timeZone));

        position.set("dataType", buf.readUnsignedByte());
        int vehicleType = buf.readUnsignedByte();
        int subcategory = buf.readUnsignedByte();

        switch (subcategory) {
            case 0x01 -> decodeDataStreams(position, buf, vehicleType);
            case 0x02 -> decodeTroubleCodes(position, buf);
            case 0x03 -> decodeBehaviour(position, buf);
            case 0x04 -> decodeTrip(position, buf, timeZone);
            case 0x05 -> decodeMcuLog(position, buf);
            case 0x06 -> decodeSegmentedPayload(position, buf, "canLearning");
            case 0x07 -> decodeWordIdList(position, buf, "dataStreamIds");
            case 0x08 -> decodeWordIdList(position, buf, "vehicleControlIds");
            case 0x09 -> decodeByteIdList(position, buf, "behaviorIds");
            case 0x0B -> decodeVin(position, buf);
            case 0x0C -> decodeVehicleCheck(position, buf);
            case 0x0D -> decodeSelfCheck(position, buf);
            case 0x0F -> decodeByteIdList(position, buf, "functionIds");
            case 0x15 -> decodeEmergency(position, buf);
            case 0x16 -> decodeVinDataMask(position, buf);
            case 0x17 -> decodeEld(position, buf, timeZone);
            default -> position.set(
                    String.format("transparent%02X", subcategory), ByteBufUtil.hexDump(buf));
        }

        return position;
    }

    /**
     * Subcategories 0x01 to 0x03 all end with a status word and a coordinate pair.
     */
    private void decodeTrailingLocation(Position position, ByteBuf buf) {
        if (buf.readableBytes() < 12) {
            return;
        }
        long status = buf.readUnsignedInt();
        decodeStatus(position, status);
        double latitude = buf.readUnsignedInt() / 1000000.0;
        double longitude = buf.readUnsignedInt() / 1000000.0;
        if (latitude != 0 || longitude != 0) {
            position.setValid(BitUtil.check(status, 1));
            position.setLatitude(BitUtil.check(status, 2) ? -latitude : latitude);
            position.setLongitude(BitUtil.check(status, 3) ? -longitude : longitude);
        }
    }

    private Long readNumeric(ByteBuf buf, int length) {
        return switch (length) {
            case 1 -> (long) buf.readUnsignedByte();
            case 2 -> (long) buf.readUnsignedShort();
            case 3 -> (long) buf.readUnsignedMedium();
            case 4 -> buf.readUnsignedInt();
            default -> null;
        };
    }

    /**
     * Section 9.14.1.1: OBD data stream reporting.
     */
    private void decodeDataStreams(Position position, ByteBuf buf, int vehicleType) {
        if (!buf.isReadable()) {
            return;
        }
        int count = buf.readUnsignedByte();
        for (int i = 0; i < count && buf.readableBytes() >= 3; i++) {
            int dataId = buf.readUnsignedShort();
            int length = buf.readUnsignedByte();
            if (length > buf.readableBytes()) {
                return;
            }
            int endIndex = buf.readerIndex() + length;
            Long value = readNumeric(buf, length);
            if (value != null) {
                decodeDataStream(position, dataId, value, vehicleType);
            } else {
                position.set(String.format("obd%04X", dataId),
                        ByteBufUtil.hexDump(buf, buf.readerIndex(), length));
            }
            buf.readerIndex(endIndex);
        }
        decodeTrailingLocation(position, buf);
    }

    /**
     * Table 25 defines four data stream id segments and they overlap. The Ford V363 segment reuses
     * ids that the commercial vehicle segment assigns to SCR sensors, so 0x011F is road speed on a
     * Ford and NOx concentration on a truck. Dispatch is therefore driven by the vehicle type byte
     * carried at offset 8 of the 0x0900F0 message, most specific segment first. Ids with no Traccar
     * equivalent are kept under an obdXXXX attribute so nothing is silently discarded.
     */
    private void decodeDataStream(Position position, int dataId, long value, int vehicleType) {
        if (vehicleType == VEHICLE_TYPE_FORD && decodeFordDataStream(position, dataId, value)) {
            return;
        }
        if (decodeSharedDataStream(position, dataId, value, vehicleType)) {
            return;
        }
        if (vehicleType != VEHICLE_TYPE_FORD && decodeCommercialDataStream(position, dataId, value)) {
            return;
        }
        if (decodeJ1939DataStream(position, dataId, value)) {
            return;
        }
        position.set(String.format("obd%04X", dataId), value);
    }

    /**
     * The 0x05xx segment, shared by the commercial vehicle and passenger vehicle tables.
     */
    private boolean decodeSharedDataStream(Position position, int dataId, long value, int vehicleType) {
        switch (dataId) {
            case 0x0500 -> position.set("highBeam", value == 1);
            case 0x0501 -> position.set("lowBeam", value == 1);
            case 0x0502 -> position.set("sideMarkerLight", value == 1);
            case 0x0503 -> position.set("fogLamp", value == 1);
            case 0x0504 -> position.set("leftTurnSignal", value == 1);
            case 0x0505 -> position.set("rightTurnSignal", value == 1);
            case 0x0506 -> position.set("hazardLights", value == 1);
            case 0x0507 -> position.set("doorFrontLeft", value == 1);
            case 0x0508 -> position.set("doorFrontRight", value == 1);
            case 0x0509 -> position.set("doorRearLeft", value == 1);
            case 0x050A -> position.set("doorRearRight", value == 1);
            case 0x050B -> position.set("doorTrunk", value == 1);
            case 0x050C -> position.set("doorLocked", value == 1);
            case 0x050D -> position.set("lockFrontLeft", value == 1);
            case 0x050E -> position.set("lockFrontRight", value == 1);
            case 0x050F -> position.set("lockRearLeft", value == 1);
            case 0x0510 -> position.set("lockRearRight", value == 1);
            case 0x0511 -> position.set("lockTrunk", value == 1);
            case 0x0512 -> position.set("windowFrontLeft", value == 1);
            case 0x0513 -> position.set("windowFrontRight", value == 1);
            case 0x0514 -> position.set("windowRearLeft", value == 1);
            case 0x0515 -> position.set("windowRearRight", value == 1);
            case 0x0516 -> position.set("sunroof", value == 1);
            case 0x0517 -> position.set("faultEcm", value == 1);
            case 0x0518 -> position.set("faultAbs", value == 1);
            case 0x0519 -> position.set("faultSrs", value == 1);
            case 0x051A -> position.set("alarmOil", value == 1);
            case 0x051B -> position.set("alarmTirePressure", value == 1);
            case 0x051C -> position.set("alarmMaintenance", value == 1);
            case 0x051D -> position.set("airbagDeployed", value == 1);
            case 0x051E -> position.set("handbrake", value == 1);
            case 0x051F -> position.set("brake", value == 1);
            case 0x0520 -> {
                // the two tables invert this flag: 0 is normal on a truck but unbuckled on a car
                boolean buckled = vehicleType == VEHICLE_TYPE_COMMERCIAL ? value == 0 : value == 1;
                position.set("seatBelt", buckled);
            }
            case 0x0521 -> position.set("seatBeltPassenger", value == 1);
            case 0x0522 -> position.set("accSignal", value == 1);
            case 0x0523 -> position.set("keyInserted", value == 1);
            case 0x0524 -> position.set("remoteControl", value);
            case 0x0525 -> position.set("wiper", value == 1);
            case 0x0526 -> position.set("airConditioner", value == 1);
            case 0x0527 -> position.set("gear", value);
            case 0x0528 -> position.set(Position.KEY_OBD_ODOMETER, value * 100);
            case 0x0529 -> position.set("enduranceMileage", value / 10.0);
            case 0x052A -> position.set("instantFuelVolume", value / 100.0);
            case 0x052B -> position.set("instantFuelPercent", value);
            case 0x052C -> position.set("totalFuel", value / 100.0);
            case 0x052D -> position.set(Position.KEY_COOLANT_TEMP, value - 40);
            case 0x052E -> position.set("intakeTemp", value - 40);
            case 0x052F -> position.set("interiorTemp", value - 40);
            case 0x0530 -> position.set(Position.KEY_POWER, value / 1000.0);
            case 0x0531 -> position.set("wheelSpeedFrontLeft", value / 10.0);
            case 0x0532 -> position.set("wheelSpeedFrontRight", value / 10.0);
            case 0x0533 -> position.set("wheelSpeedRearLeft", value / 10.0);
            case 0x0534 -> position.set("wheelSpeedRearRight", value / 10.0);
            case 0x0535 -> position.set(Position.KEY_OBD_SPEED, value / 10.0);
            case 0x0536 -> position.set(Position.KEY_RPM, value);
            case 0x0537 -> position.set("fuelConsumptionAverage", value / 100.0);
            case 0x0538 -> position.set("fuelConsumptionInstant", value / 100.0);
            case 0x0539 -> position.set(Position.KEY_FUEL_CONSUMPTION, value / 100.0);
            case 0x053A -> position.set("oilLife", value);
            case 0x053B -> position.set("oilPressure", value / 10.0);
            case 0x053C -> position.set("intakeFlow", value / 10.0);
            case 0x053D -> position.set("intakePressure", value / 10.0);
            case 0x053E -> position.set("injectionPulseWidth", value / 10.0);
            case 0x053F -> position.set(Position.KEY_THROTTLE, value);
            case 0x0540 -> position.set("acceleratorPedal", value == 1);
            case 0x0541 -> position.set("steeringAngle", value);
            case 0x0542 -> position.set("steeringDirection", value);
            case 0x0543 -> position.set("remainingOil", value / 100.0);
            case 0x0544 -> position.set(Position.KEY_FUEL_LEVEL, value);
            case 0x0545 -> position.set("mileageId", value);
            case 0x0546 -> position.set(Position.KEY_ODOMETER, value * 100);
            case 0x0547 -> position.set("relativeThrottle", value);
            case 0x0548 -> position.set("absoluteThrottle", value);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * The commercial vehicle 0x01xx segment: engine, SCR and aftertreatment data.
     */
    private boolean decodeCommercialDataStream(Position position, int dataId, long value) {
        switch (dataId) {
            case 0x0102 -> position.set(Position.KEY_OBD_ODOMETER, value * 100);
            case 0x0103 -> position.set("vehicleFuel", value / 100.0);
            case 0x0105 -> position.set(Position.KEY_FUEL_USED, value / 100.0);
            case 0x010E -> position.set("fuelInjection", value / 10.0);
            case 0x010F -> position.set("oilTemp", value - 273);
            case 0x0111 -> position.set("fuelTemp", value - 40);
            case 0x0113 -> position.set("torque", value);
            case 0x0114 -> position.set(Position.KEY_ENGINE_LOAD, value);
            case 0x0117 -> position.set("clutchLoad", value);
            case 0x0118 -> position.set("torquePercentage", value - 125);
            case 0x011B -> position.set("clutch", value == 1);
            case 0x011E -> position.set("scrStatus", value);
            case 0x011F -> position.set("noxUpstream", value - 200);
            case 0x0120 -> position.set("scrExhaustTemp", value - 273);
            case 0x0121 -> position.set("oxygenSensor1", value);
            case 0x0122 -> position.set("oxygenSensor2", value);
            case 0x0123 -> position.set("catalystTemp", value - 40);
            case 0x0124 -> position.set("ureaLevel", value);
            case 0x0125 -> position.set("noxDownstream", value);
            case 0x0126 -> position.set("checkEngine", value == 1);
            case 0x0127 -> position.set(Position.KEY_HOURS, value * 1000);
            case 0x0128 -> position.set("vehicleWeight", value);
            case 0x0129 -> position.set("scrInletTemp", value - 273);
            case 0x012A -> position.set("frictionTorque", value - 125);
            case 0x012B -> position.set("dpfPressure", value / 10.0);
            case 0x012C -> position.set("manifoldTemp", value - 40);
            case 0x012D -> position.set("torqueMode", value);
            case 0x012E -> position.set("oilLevel", value / 10.0);
            case 0x012F -> position.set("adblueInjection", value);
            case 0x0130 -> position.set("adbluePumpPressure", value);
            case 0x0131 -> position.set("adbluePumpSpeed", value);
            case 0x0132 -> position.set("catalystDownstreamTemp", value - 273);
            case 0x0133 -> position.set("adbluePumpStatus", value);
            case 0x0134 -> position.set("noxInletPowerFailure", value);
            case 0x0135 -> position.set("noxInletHeatingControl", value);
            case 0x0136 -> position.set("noxInletSignalValid", value);
            case 0x0137 -> position.set("noxInletHeatingFailure", value);
            case 0x0138 -> position.set("noxOutletPowerFailure", value);
            case 0x0139 -> position.set("noxOutletHeatingControl", value);
            case 0x013A -> position.set("noxOutletSignalValid", value);
            case 0x013B -> position.set("noxOutletHeatingFailure", value);
            case 0x013C -> position.set("noxInletHeatingEnable", value);
            case 0x013D -> position.set("noxOutletHeatingEnable", value);
            case 0x013E -> position.set("catalystUpstreamTemp", value - 273);
            case 0x013F -> position.set("rpmThreshold", value);
            case 0x0140 -> position.set("torqueThreshold", value - 125);
            case 0x0141 -> position.set("airSolenoidFailure", value);
            case 0x0142 -> position.set("returnPipeBlocked", value == 1);
            case 0x0143 -> position.set("pumpHeatingFailure", value == 1);
            case 0x0144 -> position.set("lowAirFlowFault", value == 1);
            case 0x0145 -> position.set("scrMotorFailure", value == 1);
            case 0x0146 -> position.set("meteringPumpHeating", value);
            case 0x0147 -> position.set("purgeIncomplete", value == 1);
            case 0x0148 -> position.set("scrPumpTemp", value - 40);
            case 0x0149 -> position.set("pressureSwitchAd", value);
            case 0x014A -> position.set("fuelEconomy", value / 100.0);
            case 0x014B -> position.set("ambientTemp", value - 273);
            case 0x014C -> position.set("particulateMatter", value / 1000.0);
            case 0x014D -> position.set("opacity", value / 10.0);
            case 0x014E -> position.set("particulateK", value / 1000.0);
            case 0x014F -> position.set("ureaUsed", value);
            case 0x0150 -> position.set("dpfExhaustTemp", value - 273);
            case 0x0151 -> position.set("sensorVoltage", value / 10.0);
            case 0x0152 -> position.set("sensorTemp", value - 99);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * The SAE J1939 segment, reported alongside the commercial vehicle data stream ids.
     */
    private boolean decodeJ1939DataStream(Position position, int dataId, long value) {
        switch (dataId) {
            case 0xF004 -> position.set(Position.KEY_RPM, value * 0.125);
            case 0xFEF1 -> position.set(Position.KEY_OBD_SPEED, value / 256.0);
            case 0xFECA -> position.set("checkEngine", value == 1);
            case 0xFEE0 -> position.set(Position.KEY_OBD_ODOMETER, (long) (value * 125));
            case 0xE004 -> position.set("torqueMode", value);
            case 0xF003 -> position.set(Position.KEY_ENGINE_LOAD, value);
            case 0xFEF6 -> position.set("boostPressure", value * 2);
            case 0xFEEE -> position.set(Position.KEY_COOLANT_TEMP, value - 40);
            case 0xFEE9 -> position.set(Position.KEY_FUEL_USED, value * 0.5);
            case 0xFEFC -> position.set(Position.KEY_FUEL_LEVEL, value * 0.4);
            case 0xE003 -> position.set("acceleratorPedalPosition", value * 0.4);
            case 0xFEF2 -> position.set(Position.KEY_THROTTLE, value * 0.4);
            case 0xF00A -> position.set("intakeFlow", value * 0.05);
            case 0xFEF5 -> position.set("barometricPressure", value * 0.5);
            case 0xEEF1 -> position.set("ptoGovernorState", value);
            case 0xFEC1 -> position.set(Position.KEY_ODOMETER, (long) (value * 5));
            case 0xFDB8 -> position.set("timeSinceEngineStart", value);
            case 0xEEF6 -> position.set("manifoldTemp", value - 40);
            case 0xE002 -> position.set("torquePercentage", value - 125);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * The Ford V363 passenger car segment. Several ids collide with the commercial vehicle segment
     * and are resolved in favour of this table when the vehicle type is 0x04.
     */
    private boolean decodeFordDataStream(Position position, int dataId, long value) {
        switch (dataId) {
            case 0x0101 -> position.set(Position.KEY_RPM, value * 0.5);
            case 0x0104 -> position.set("acCompressor", value);
            case 0x010D -> position.set("fuelTemp", value * 0.1 - 273.14);
            case 0x0116 -> position.set(Position.KEY_THROTTLE, value * 0.01220703125);
            case 0x011C -> position.set(Position.KEY_COOLANT_TEMP, value * 0.1 - 273.14);
            case 0x011D -> position.set(Position.KEY_POWER, value * 20 / 1000.0);
            case 0x011F -> position.set(Position.KEY_OBD_SPEED, value * 0.01);
            case 0x012F -> position.set(Position.KEY_FUEL_LEVEL, value * 100 / 255.0);
            case 0x015B -> position.set("fuelFilterWaterLevel", value);
            case 0x018D -> position.set("ambientTemp", value * 0.1 - 273.14);
            case 0x01A6 -> position.set(Position.KEY_OBD_ODOMETER, (long) (value * 100));
            case 0x01B4 -> position.set(Position.KEY_HOURS, value * 1000);
            case 0x01F6 -> position.set("sootMass", value * 0.01);
            case 0x022A -> position.set("regenerationRequests", value);
            case 0x022B -> position.set("regenerationsCompleted", value);
            case 0x022E -> position.set("distanceSinceRegeneration", value);
            case 0x025C -> position.set("absoluteThrottle", (value * 4096) >> 12);
            case 0x1612 -> position.set("ureaLevel", value * 0.012207031);
            case 0x1642 -> position.set("catalystEfficiency", value * 0.001);
            case 0x1725 -> position.set("ureaUsed", value * 0.001);
            case 0x4028 -> {
                if (value <= 0x64) {
                    position.set(Position.KEY_BATTERY_LEVEL, value);
                }
            }
            case 0x404C -> position.set(Position.KEY_ODOMETER, value * 100);
            case 0x40D6 -> position.set("keySwitchStatus", value);
            case 0x5013 -> position.set("ambientTemp", value * 0.75 - 48);
            case 0xD134 -> position.set("vehicleMode", value);
            case 0xF411 -> position.set("absoluteThrottle", value * 100 / 255.0);
            case 0xF41F -> position.set("timeSinceEngineStart", value);
            case 0xF42F -> position.set(Position.KEY_FUEL_LEVEL, value * 100 / 255.0);
            case 0xF45C -> position.set("oilTemp", value - 40);
            case 0xF45E -> position.set(Position.KEY_FUEL_CONSUMPTION, value * 0.05);
            case 0xF461 -> position.set("driverDemandTorque", value - 125);
            case 0xF4A6 -> position.set(Position.KEY_OBD_ODOMETER, (long) (value * 100));
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Section 9.14.1.2: trouble code reporting. Appendix 10.3 defines a four byte fault record; if
     * the payload does not line up with that stride the remainder is kept as raw hex instead of
     * being sliced at a guessed offset.
     */
    private void decodeTroubleCodes(Position position, ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return;
        }
        int systems = buf.readUnsignedShort();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < systems; i++) {
            if (buf.readableBytes() < 6) {
                break;
            }
            long systemId = buf.readUnsignedInt();
            int codeCount = buf.readUnsignedShort();
            if (buf.readableBytes() < codeCount * 4) {
                position.set("dtcRaw", ByteBufUtil.hexDump(buf));
                buf.skipBytes(buf.readableBytes());
                break;
            }
            for (int j = 0; j < codeCount; j++) {
                codes.add(String.format("%X:%s", systemId, ByteBufUtil.hexDump(buf.readSlice(4))));
            }
        }
        if (!codes.isEmpty()) {
            position.set(Position.KEY_DTCS, String.join(" ", codes));
        }
        decodeTrailingLocation(position, buf);
    }

    /**
     * Table 28: alarm data and driving behaviour data ids.
     */
    private String decodeBehaviourAlarm(int alarmId) {
        return switch (alarmId) {
            case 0x00 -> Position.ALARM_FAULT;
            case 0x01 -> Position.ALARM_POWER_RESTORED;
            case 0x02 -> Position.ALARM_POWER_CUT;
            case 0x04 -> Position.ALARM_LOW_POWER;
            case 0x05, 0x06, 0x07, 0x08 -> Position.ALARM_TEMPERATURE;
            case 0x13, 0x31 -> Position.ALARM_DOOR;
            case 0x1A -> Position.ALARM_ACCELERATION;
            case 0x1B -> Position.ALARM_BRAKING;
            case 0x1C -> Position.ALARM_CORNERING;
            case 0x1D, 0x1E, 0x1F -> Position.ALARM_LANE_CHANGE;
            case 0x20 -> Position.ALARM_SOS;
            case 0x21 -> Position.ALARM_GEOFENCE_EXIT;
            case 0x22 -> Position.ALARM_GEOFENCE_ENTER;
            case 0x23, 0x24 -> Position.ALARM_FATIGUE_DRIVING;
            case 0x25 -> Position.ALARM_OVERSPEED;
            case 0x26, 0x27, 0x28, 0x2E -> Position.ALARM_ACCIDENT;
            case 0x2F, 0x3E -> Position.ALARM_FUEL_LEAK;
            case 0x30 -> Position.ALARM_TOW;
            default -> null;
        };
    }

    /**
     * Section 9.14.1.3: report of alarm data and driving behaviour data.
     */
    private void decodeBehaviour(Position position, ByteBuf buf) {
        if (!buf.isReadable()) {
            return;
        }
        int count = buf.readUnsignedByte();
        List<String> behaviours = new ArrayList<>();
        for (int i = 0; i < count && buf.readableBytes() >= 2; i++) {
            int alarmId = buf.readUnsignedByte();
            int descriptionLength = buf.readUnsignedByte();
            if (descriptionLength > buf.readableBytes()) {
                return;
            }
            String description = descriptionLength > 0
                    ? buf.readCharSequence(descriptionLength, StandardCharsets.US_ASCII).toString().trim()
                    : null;
            behaviours.add(description != null && !description.isEmpty()
                    ? String.format("%02X(%s)", alarmId, description)
                    : String.format("%02X", alarmId));
            position.addAlarm(decodeBehaviourAlarm(alarmId));
            if (alarmId == 0x38) {
                position.set(Position.KEY_IGNITION, true);
            } else if (alarmId == 0x39) {
                position.set(Position.KEY_IGNITION, false);
            }
        }
        if (!behaviours.isEmpty()) {
            position.set("behaviors", String.join(",", behaviours));
        }
        decodeTrailingLocation(position, buf);
    }

    /**
     * Section 9.14.1.4: tour data reporting.
     */
    private void decodeTrip(Position position, ByteBuf buf, TimeZone timeZone) {
        if (buf.readableBytes() < 11) {
            return;
        }
        int property = buf.readUnsignedByte();
        position.set("tripEvent", property == 0x01 ? "start" : "end");
        position.set("tripNumber", buf.readUnsignedInt());
        position.set("tripStartTime", readDate(buf, timeZone).toInstant().toString());
        if (property != 0x02 || buf.readableBytes() < 33) {
            return;
        }
        position.set("tripEndTime", readDate(buf, timeZone).toInstant().toString());
        double startLatitude = buf.readUnsignedInt() / 1000000.0;
        double startLongitude = buf.readUnsignedInt() / 1000000.0;
        double endLatitude = buf.readUnsignedInt() / 1000000.0;
        double endLongitude = buf.readUnsignedInt() / 1000000.0;
        int signs = buf.readUnsignedByte();
        position.set("tripStartLat", BitUtil.check(signs, 0) ? -startLatitude : startLatitude);
        position.set("tripStartLon", BitUtil.check(signs, 1) ? -startLongitude : startLongitude);
        position.set("tripEndLat", BitUtil.check(signs, 2) ? -endLatitude : endLatitude);
        position.set("tripEndLon", BitUtil.check(signs, 3) ? -endLongitude : endLongitude);
        position.set("idlingCount", buf.readUnsignedShort());
        position.set("idlingTime", buf.readUnsignedShort());
        position.set(Position.KEY_ODOMETER_TRIP, buf.readUnsignedShort() * 100L);
        position.set("tripFuelUsed", buf.readUnsignedShort() / 100.0);
    }

    /**
     * Section 9.14.1.5: OBD MCU log data reporting.
     */
    private void decodeMcuLog(Position position, ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            return;
        }
        position.set("logPacketCount", buf.readUnsignedShort());
        position.set("logPacketIndex", buf.readUnsignedShort());
        position.set("logContent",
                buf.readCharSequence(buf.readableBytes(), StandardCharsets.US_ASCII).toString().trim());
    }

    /**
     * Section 9.14.1.6: data obtained by CAN learning, delivered as numbered 512 byte segments.
     */
    private void decodeSegmentedPayload(Position position, ByteBuf buf, String prefix) {
        if (buf.readableBytes() < 4) {
            return;
        }
        position.set(prefix + "PacketCount", buf.readUnsignedByte());
        position.set(prefix + "PacketIndex", buf.readUnsignedByte());
        int length = Math.min(buf.readUnsignedShort(), buf.readableBytes());
        position.set(prefix + "Data", ByteBufUtil.hexDump(buf.readSlice(length)));
    }

    /**
     * Sections 9.14.1.7 and 9.14.1.8: supported id lists counted by a word.
     */
    private void decodeWordIdList(Position position, ByteBuf buf, String key) {
        if (buf.readableBytes() < 2) {
            return;
        }
        int count = buf.readUnsignedShort();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count && buf.readableBytes() >= 2; i++) {
            ids.add(String.format("%04X", buf.readUnsignedShort()));
        }
        position.set(key, String.join(",", ids));
    }

    /**
     * Sections 9.14.1.8 and 9.14.1.12: supported id lists counted by a byte.
     */
    private void decodeByteIdList(Position position, ByteBuf buf, String key) {
        if (!buf.isReadable()) {
            return;
        }
        int count = buf.readUnsignedByte();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count && buf.isReadable(); i++) {
            ids.add(String.format("%02X", buf.readUnsignedByte()));
        }
        position.set(key, String.join(",", ids));
    }

    /**
     * Section 9.14.1.10: whole vehicle check data upload.
     */
    private void decodeVehicleCheck(Position position, ByteBuf buf) {
        if (buf.readableBytes() < 9) {
            return;
        }
        position.set("checkType", buf.readUnsignedByte());
        position.set("diagnosticNumber", buf.readUnsignedInt());
        position.set("checkPacketCount", buf.readUnsignedByte());
        position.set("checkPacketIndex", buf.readUnsignedByte());
        int length = Math.min(buf.readUnsignedShort(), buf.readableBytes());
        position.set("checkData", ByteBufUtil.hexDump(buf.readSlice(length)));
    }

    /**
     * Section 9.14.1.11: device self-check data, reported as id and value byte pairs.
     */
    private void decodeSelfCheck(Position position, ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return;
        }
        int count = buf.readUnsignedShort();
        for (int i = 0; i < count && buf.readableBytes() >= 2; i++) {
            int checkId = buf.readUnsignedByte();
            position.set(String.format("selfCheck%02X", checkId), buf.readUnsignedByte());
        }
    }

    /**
     * Section 9.14.1.13: emergency scenario data packet. The full payload is 5256 bytes of 20 Hz
     * acceleration and attitude samples split across several 0x0900 messages, so only the segment
     * header and the event identity are surfaced; reassembly is left to the consumer.
     */
    private void decodeEmergency(Position position, ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        buf.readUnsignedShort(); // packet length, from the event id to the end of the content
        position.set("emergencyEventId", buf.readUnsignedInt());
        position.set("emergencyPacketCount", buf.readUnsignedByte());
        int packetIndex = buf.readUnsignedByte();
        position.set("emergencyPacketIndex", packetIndex);
        if (packetIndex == 1 && buf.readableBytes() >= 16) {
            int eventType = (int) buf.readUnsignedInt();
            position.set("emergencyEventType", eventType);
            switch (eventType) {
                case 51 -> position.addAlarm(Position.ALARM_ACCELERATION);
                case 52 -> position.addAlarm(Position.ALARM_BRAKING);
                case 53 -> position.addAlarm(Position.ALARM_CORNERING);
                case 54 -> position.addAlarm(Position.ALARM_LANE_CHANGE);
                case 56, 57 -> position.addAlarm(Position.ALARM_ACCIDENT);
                default -> position.addAlarm(Position.ALARM_GENERAL);
            }
            position.setDeviceTime(new Date(buf.readLong()));
            position.set("peakAcceleration", buf.readUnsignedInt() / 1000.0);
        }
        position.set("emergencyData", ByteBufUtil.hexDump(buf));
    }

    /**
     * Section 9.14.1.9: vehicle VIN upload.
     */
    private void decodeVin(Position position, ByteBuf buf) {
        if (buf.readableBytes() < 2 || buf.readUnsignedByte() != 0x01) {
            return;
        }
        position.set(Position.KEY_VIN,
                buf.readCharSequence(buf.readableBytes(), StandardCharsets.US_ASCII).toString().trim());
    }

    /**
     * Section 9.14.1.14: VIN and DataMask upload.
     */
    private void decodeVinDataMask(Position position, ByteBuf buf) {
        if (!buf.isReadable()) {
            return;
        }
        int count = buf.readUnsignedByte();
        for (int i = 0; i < count && buf.readableBytes() >= 3; i++) {
            int dataId = buf.readUnsignedShort();
            int length = buf.readUnsignedByte();
            if (length > buf.readableBytes()) {
                return;
            }
            ByteBuf value = buf.readSlice(length);
            switch (dataId) {
                case 0x0001 -> position.set(Position.KEY_VIN,
                        value.readCharSequence(length, StandardCharsets.US_ASCII).toString().trim());
                case 0x0002 -> position.set("dataMask", ByteBufUtil.hexDump(value));
                case 0x0003 -> position.set("vehicleTypeId", ByteBufUtil.hexDump(value));
                default -> position.set(String.format("data%04X", dataId), ByteBufUtil.hexDump(value));
            }
        }
    }

    /**
     * Section 9.14.1.15: ELD data upload. Data ids follow table 67, which differs from table 25.
     */
    private void decodeEld(Position position, ByteBuf buf, TimeZone timeZone) {

        if (buf.readableBytes() < 28) {
            return;
        }

        position.setDeviceTime(readDate(buf, timeZone));
        position.set(Position.KEY_EVENT, buf.readUnsignedByte());
        position.set("eventSequence", buf.readUnsignedShort());
        position.set("liveEvent", buf.readUnsignedByte() == 1);

        double latitude = buf.readUnsignedInt() / 1000000.0;
        double longitude = buf.readUnsignedInt() / 1000000.0;
        if (latitude != 0 || longitude != 0) {
            position.setValid(true);
            position.setLatitude(latitude);
            position.setLongitude(longitude);
        }
        position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedShort() / 10.0));
        position.setFixTime(readDate(buf, timeZone));
        position.setCourse(buf.readUnsignedShort());

        if (!buf.isReadable()) {
            return;
        }
        int count = buf.readUnsignedByte();
        for (int i = 0; i < count && buf.readableBytes() >= 3; i++) {
            int dataId = buf.readUnsignedShort();
            int length = buf.readUnsignedByte();
            if (length > buf.readableBytes()) {
                return;
            }
            int endIndex = buf.readerIndex() + length;
            switch (dataId) {
                case 0xF004 -> position.set(Position.KEY_RPM, buf.readUnsignedShort() * 0.125);
                case 0xFEF1 -> position.set(Position.KEY_OBD_SPEED, buf.readUnsignedShort() / 256.0);
                case 0xFEEC -> position.set(Position.KEY_VIN,
                        buf.readCharSequence(length, StandardCharsets.US_ASCII).toString().trim());
                case 0xFEE0 -> position.set(Position.KEY_OBD_ODOMETER, (long) (buf.readUnsignedInt() * 125));
                case 0x0127 -> position.set(Position.KEY_HOURS, buf.readUnsignedInt() * 1000);
                case 0xFEFC -> position.set(Position.KEY_FUEL_LEVEL, buf.readUnsignedByte() * 0.4);
                case 0x0530 -> position.set(Position.KEY_POWER, buf.readUnsignedShort() / 1000.0);
                default -> position.set(String.format("eld%04X", dataId),
                        ByteBufUtil.hexDump(buf, buf.readerIndex(), length));
            }
            buf.readerIndex(endIndex);
        }
    }

    /**
     * Section 9.14.2: uplink transparent transmission of peripheral data, message type 0xF3.
     */
    private Position decodePeripheralData(DeviceSession deviceSession, ByteBuf buf) {

        if (buf.readableBytes() < 4) {
            return null;
        }

        Position position = emptyPosition(deviceSession);

        int type = buf.readUnsignedShort();
        int length = buf.readUnsignedShort();
        if (length > buf.readableBytes()) {
            return null;
        }
        ByteBuf value = buf.readSlice(length);

        switch (type) {
            case 0x0001 -> position.set("peripheralInfo",
                    value.readCharSequence(length, StandardCharsets.US_ASCII).toString().trim());
            case 0x0002 -> position.set("collisionDelta", value.readUnsignedShort() / 256.0 / 100.0);
            case 0x0003 -> {
                List<String> samples = new ArrayList<>();
                while (value.readableBytes() >= 6) {
                    samples.add(String.format("[%d,%d,%d]",
                            readSignedAcceleration(value), readSignedAcceleration(value),
                            readSignedAcceleration(value)));
                }
                position.set(Position.KEY_G_SENSOR, "[" + String.join(",", samples) + "]");
            }
            case 0x0004 -> {
                List<String> points = new ArrayList<>();
                while (value.readableBytes() >= 8) {
                    points.add(String.format("[%.6f,%.6f]",
                            value.readUnsignedInt() / 1000000.0, value.readUnsignedInt() / 1000000.0));
                }
                position.set("collisionTrack", "[" + String.join(",", points) + "]");
            }
            case 0x0005 -> {
                List<String> samples = new ArrayList<>();
                while (value.readableBytes() >= 4) {
                    samples.add(String.format("[%d,%d]", value.readUnsignedShort(), value.readUnsignedShort()));
                }
                position.set("collisionCan", "[" + String.join(",", samples) + "]");
            }
            case 0x0006 -> {
                if (length >= 6) {
                    position.setDeviceTime(readDate(value, TimeZone.getTimeZone("UTC")));
                }
            }
            default -> position.set(String.format("peripheral%04X", type), ByteBufUtil.hexDump(value));
        }

        return position;
    }

    /**
     * Negative values are reported as the inverted value plus one, in units of 1/256 g.
     */
    private int readSignedAcceleration(ByteBuf buf) {
        return buf.readShort();
    }

}
