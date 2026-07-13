package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Command;

public class Jt808ProtocolEncoderTest extends ProtocolTest {

    @Test
    public void testEncode() throws Exception {

        var decoder = inject(new Jt808ProtocolDecoder(null));
        var encoder = inject(new Jt808ProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);

        command.setType(Command.TYPE_ENGINE_STOP);

        verifyFrame(
            binary("7e810500010b3a73ce2ff20000f0247e"),
            encodeCommand(encoder, decoder, command));

        command.setType(Command.TYPE_CUSTOM);

        command.set(Command.KEY_DATA, "7e830000140b3a73ce2ff2000001546573742c20436f6d6d616e642c2031323323a57e");
        verifyFrame(
            binary("7e830000140b3a73ce2ff2000001546573742c20436f6d6d616e642c2031323323a57e"),
            encodeCommand(encoder, decoder, command));

        encoder.setModelOverride("BSJ");

        command.set(Command.KEY_DATA, "Test, Command, 123#");
        verifyFrame(
            binary("7e830000140b3a73ce2ff2000001546573742c20436f6d6d616e642c2031323323a57e"),
            encodeCommand(encoder, decoder, command));

    }

    @Test
    public void testEncodeVideoPlayback() throws Exception {

        var decoder = inject(new Jt808ProtocolDecoder(null));
        var encoder = inject(new Jt808ProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);

        command.setType(Command.TYPE_VIDEO_PAUSE);
        command.set(Command.KEY_INDEX, 1);
        verifyFrame(
            binary("7e910200040b3a73ce2ff2000001020000c57e"),
            encodeCommand(encoder, decoder, command));

        command.setType(Command.TYPE_VIDEO_RESUME);
        command.set(Command.KEY_INDEX, 1);
        verifyFrame(
            binary("7e910200040b3a73ce2ff2000001030000c47e"),
            encodeCommand(encoder, decoder, command));

        command.setType(Command.TYPE_VIDEO_RESOURCES);
        command.set(Command.KEY_INDEX, 1);
        command.set(Command.KEY_START_TIME, "2025-01-23T02:15:12Z");
        command.set(Command.KEY_END_TIME, "2025-01-23T02:18:12Z");
        verifyFrame(
            binary("7e920500180b3a73ce2ff20000012501231015122501231018120000000000000000000000d27e"),
            encodeCommand(encoder, decoder, command));

    }

    @Test
    public void testEncodeJimiCustom() throws Exception {

        var decoder = inject(new Jt808ProtocolDecoder(null));
        var encoder = inject(new Jt808ProtocolEncoder(null));
        encoder.setModelOverride("JC371");

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CUSTOM);
        command.set(Command.KEY_DATA, "TEST");

        verifyFrame(
            binary("7e890000050b3a73ce2ff20000f0544553543b7e"),
            encodeCommand(encoder, decoder, command));

    }

}
