package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Command;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrackonobdProtocolEncoderTest extends ProtocolTest {

    @Test
    public void testEncodeTerminalControl() throws Exception {

        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);

        command.setType(Command.TYPE_REBOOT_DEVICE);
        verifyCommand(encoder, command, binary("7e810500010b3a73ce2ff2000004d07e"));

        command.setType(Command.TYPE_POWER_OFF);
        verifyCommand(encoder, command, binary("7e810500010b3a73ce2ff2000003d77e"));

        command.setType(Command.TYPE_FACTORY_RESET);
        verifyCommand(encoder, command, binary("7e810500010b3a73ce2ff2000005d17e"));

    }

    @Test
    public void testEncodeParameters() throws Exception {

        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);

        command.setType(Command.TYPE_POSITION_PERIODIC);
        command.set(Command.KEY_FREQUENCY, 60);
        verifyCommand(encoder, command, binary("7e810300080b3a73ce2ff20000010029040000003ccb7e"));

    }

    @Test
    public void testEncodeMessage() throws Exception {

        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);

        command.setType(Command.TYPE_MESSAGE);
        command.set(Command.KEY_MESSAGE, "Test");
        verifyCommand(encoder, command, binary("7e830000050b3a73ce2ff200000c54657374ed7e"));

    }

    @Test
    public void testEncodeConfiguration() throws Exception {

        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CONFIGURATION);

        // F00E is a double word and F00F a single byte, both taken from table 4
        command.set(Command.KEY_DATA, "F00E=30,F00F=1");
        verifyCommand(encoder, command, binary("7e8103000c0b3a73ce2ff2000002f00e040000001ef00f0101c67e"));

        // an explicit width overrides the table, and values may be given in hex
        command.set(Command.KEY_DATA, "F00E:4=0x1E,F00F:1=0x01");
        verifyCommand(encoder, command, binary("7e8103000c0b3a73ce2ff2000002f00e040000001ef00f0101c67e"));

        command.set(Command.KEY_DATA, "?F00E,F00F,F016");
        verifyCommand(encoder, command, binary("7e810600070b3a73ce2ff2000003f00ef00ff016357e"));

    }

    /**
     * An ASCII custom command travels as an online command, and a hex frame passes through whole.
     */
    @Test
    public void testEncodeCustom() throws Exception {

        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CUSTOM);

        command.set(Command.KEY_DATA, "GMT#");
        verifyCommand(encoder, command, binary("7e890000050b3a73ce2ff20000f0474d5423507e"));

        command.set(Command.KEY_DATA, "STATUS#");
        verifyCommand(encoder, command, binary("7e890000080b3a73ce2ff20000f053544154555323177e"));

        // a whole frame in hex is recognised by its identifiers and forwarded untouched
        command.set(Command.KEY_DATA, "7e000200000b3a73ce2ff20001527e");
        verifyCommand(encoder, command, binary("7e000200000b3a73ce2ff20001527e"));

    }

    /**
     * Sections 9.15 to 9.18. Properties are entering and leaving alarms, so the optional time and
     * speed fields are absent and the hemisphere is carried in the properties word.
     */
    @Test
    public void testEncodeGeofence() throws Exception {

        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CONFIGURATION);

        command.set(Command.KEY_DATA, "geofence=circle,add,1,27.7,85.3,500");
        verifyCommand(encoder, command, binary(
                "7e860000140b3a73ce2ff20000010100000001002801a6ab2005159320000001f4907e"));

        command.set(Command.KEY_DATA, "geofence=rect,add,2,27.8,85.2,27.7,85.4");
        verifyCommand(encoder, command, binary(
                "7e860200180b3a73ce2ff20000010100000002002801a831c005140c8001a6ab20051719c0c57e"));

        command.set(Command.KEY_DATA, "geofence=circle,delete,1");
        verifyCommand(encoder, command, binary("7e860100050b3a73ce2ff200000100000001d37e"));

        command.set(Command.KEY_DATA, "geofence=rect,delete,all");
        verifyCommand(encoder, command, binary("7e860300010b3a73ce2ff2000000d57e"));

    }

    /**
     * Table 16 in full, including the codes Traccar has no command type for.
     */
    @Test
    public void testEncodeTerminalControlDirective() throws Exception {

        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CONFIGURATION);

        command.set(Command.KEY_DATA, "control=6"); // disconnect data communication
        verifyCommand(encoder, command, binary("7e810500010b3a73ce2ff2000006d27e"));

        command.set(Command.KEY_DATA, "control=0xB1"); // read OBD MCU log
        verifyCommand(encoder, command, binary("7e810500010b3a73ce2ff20000b1657e"));

        command.setType(Command.TYPE_FIRMWARE_UPDATE);
        verifyCommand(encoder, command, binary("7e810500010b3a73ce2ff20000a2767e"));

    }

    @Test
    public void testRejectsMalformedInput() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));
        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);

        command.setType(Command.TYPE_CONFIGURATION);
        command.set(Command.KEY_DATA, "F00E");
        assertThrows(IllegalArgumentException.class, () -> encodeCommand(encoder, decoder, command));

        command.set(Command.KEY_DATA, "NOTHEX=1");
        assertThrows(IllegalArgumentException.class, () -> encodeCommand(encoder, decoder, command));

        command.set(Command.KEY_DATA, "geofence=triangle,add,1,27.7,85.3,500");
        assertThrows(IllegalArgumentException.class, () -> encodeCommand(encoder, decoder, command));

        command.set(Command.KEY_DATA, "geofence=circle,add,1,27.7");
        assertThrows(IllegalArgumentException.class, () -> encodeCommand(encoder, decoder, command));

    }

    /**
     * Vehicle control embeds the current time, so only the stable parts of the frame are asserted.
     */
    @Test
    public void testEncodeVehicleControl() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));
        var encoder = inject(new TrackonobdProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);

        Object encoded = encodeCommand(encoder, decoder, command);
        assertInstanceOf(ByteBuf.class, encoded);
        String hex = ByteBufUtil.hexDump((ByteBuf) encoded);

        // 0x8900 downlink transparent transmission, 11 byte body, transparent type 0xF1
        assertTrue(hex.startsWith("7e8900000b0b3a73ce2ff20000f1"), hex);
        // reserved, commercial vehicle, subcategory 0x01, control function 0x14 (ignition off)
        assertTrue(hex.contains("00010114"), hex);

    }

}
