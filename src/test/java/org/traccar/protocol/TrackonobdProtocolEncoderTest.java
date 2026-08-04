package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Command;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
