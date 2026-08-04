package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;

public class TrackonobdFrameDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new TrackonobdFrameDecoder());

        // the example from section 5.4.2: 30 7e 08 7d 55 travels the wire as 30 7d 02 08 7d 01 55
        verifyFrame(
                binary("7e307e087d557e"),
                decoder.decode(null, null, binary("7e307d02087d01557e")));

        // leading noise before the identifier is discarded
        verifyFrame(
                binary("7e307e087d557e"),
                decoder.decode(null, null, binary("24247e307d02087d01557e")));

        // a heartbeat carries no escapes and passes through unchanged
        verifyFrame(
                binary("7e000200000b3a73ce2ff20001527e"),
                decoder.decode(null, null, binary("7e000200000b3a73ce2ff20001527e")));

        // an incomplete frame is buffered rather than emitted
        verifyNull(decoder.decode(null, null, binary("7e000200000b3a73ce2ff2")));

    }

}
