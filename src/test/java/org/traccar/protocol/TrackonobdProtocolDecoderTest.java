package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Position;

/**
 * Frames are derived from the protocol specification rather than captured from hardware, so they
 * exercise the documented layouts. Replace or extend them with real captures once a VG502 is
 * reporting.
 */
public class TrackonobdProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testDecodeLocation() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyPosition(decoder, binary(
                "7e0200002b0b3a73ce2ff20001000000000000000301a6ae210515f0b8057801c8005a26080410300030011831010ce4070104019f03049f1b7e"));

        verifyAttribute(decoder, binary(
                "7e0200002b0b3a73ce2ff20001000000000000000301a6ae210515f0b8057801c8005a26080410300030011831010ce4070104019f03049f1b7e"),
                Position.KEY_RSSI, 24);

        verifyAttribute(decoder, binary(
                "7e0200002b0b3a73ce2ff20001000000000000000301a6ae210515f0b8057801c8005a26080410300030011831010ce4070104019f03049f1b7e"),
                Position.KEY_SATELLITES, 12);

        verifyAttribute(decoder, binary(
                "7e0200002b0b3a73ce2ff20001000000000000000301a6ae210515f0b8057801c8005a26080410300030011831010ce4070104019f03049f1b7e"),
                Position.KEY_POWER, 11.83);

        verifyAttribute(decoder, binary(
                "7e0200002b0b3a73ce2ff20001000000000000000301a6ae210515f0b8057801c8005a26080410300030011831010ce4070104019f03049f1b7e"),
                Position.KEY_BATTERY, 4.15);

        verifyAttribute(decoder, binary(
                "7e0200002b0b3a73ce2ff20001000000000000000301a6ae210515f0b8057801c8005a26080410300030011831010ce4070104019f03049f1b7e"),
                Position.KEY_IGNITION, true);

    }

    @Test
    public void testDecodeObdDataStream() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e090000290b3a73ce2ff20001f02608041030050001010405350201c20536020bb8052d0169054401500000000301a6ae210515f0b8db7e"),
                Position.KEY_OBD_SPEED, 45.0);

        verifyAttribute(decoder, binary(
                "7e090000290b3a73ce2ff20001f02608041030050001010405350201c20536020bb8052d0169054401500000000301a6ae210515f0b8db7e"),
                Position.KEY_RPM, 3000L);

        verifyAttribute(decoder, binary(
                "7e090000290b3a73ce2ff20001f02608041030050001010405350201c20536020bb8052d0169054401500000000301a6ae210515f0b8db7e"),
                Position.KEY_COOLANT_TEMP, 65L);

        verifyAttribute(decoder, binary(
                "7e090000290b3a73ce2ff20001f02608041030050001010405350201c20536020bb8052d0169054401500000000301a6ae210515f0b8db7e"),
                Position.KEY_FUEL_LEVEL, 80L);

    }

    /**
     * Table 25 assigns 0x011F to NOx concentration for commercial vehicles and to road speed for
     * the Ford V363, so the same bytes must decode differently depending on the vehicle type byte.
     */
    @Test
    public void testDecodeDataStreamSegmentConflict() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e0900001c0b3a73ce2ff20001f026080410300500040101011f0213880000000301a6ae210515f0b84a7e"),
                Position.KEY_OBD_SPEED, 50.0);

        verifyAttribute(decoder, binary(
                "7e0900001c0b3a73ce2ff20001f026080410300500010101011f0213880000000301a6ae210515f0b84f7e"),
                "noxUpstream", 4800L);

    }

    /**
     * The seat belt flag is inverted between the commercial and passenger vehicle tables.
     */
    @Test
    public void testDecodeSeatBeltPolarity() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e0900001b0b3a73ce2ff20001f026080410300500020101052001010000000301a6ae210515f0b8e97e"),
                "seatBelt", true);

        verifyAttribute(decoder, binary(
                "7e0900001b0b3a73ce2ff20001f026080410300500010101052001010000000301a6ae210515f0b8ea7e"),
                "seatBelt", false);

    }

    @Test
    public void testDecodePassengerSegment() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e090000240b3a73ce2ff20001f0260804103005000201030507010105120101053102013b0000000301a6ae210515f0b8e87e"),
                "doorFrontLeft", true);

        verifyAttribute(decoder, binary(
                "7e090000240b3a73ce2ff20001f0260804103005000201030507010105120101053102013b0000000301a6ae210515f0b8e87e"),
                "windowFrontLeft", true);

        verifyAttribute(decoder, binary(
                "7e090000240b3a73ce2ff20001f0260804103005000201030507010105120101053102013b0000000301a6ae210515f0b8e87e"),
                "wheelSpeedFrontLeft", 31.5);

    }

    @Test
    public void testDecodeJ1939Segment() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e090000200b3a73ce2ff20001f026080410300500010102f004021f40fefc01c80000000301a6ae210515f0b8957e"),
                Position.KEY_RPM, 1000.0);

        verifyAttribute(decoder, binary(
                "7e090000200b3a73ce2ff20001f026080410300500010102f004021f40fefc01c80000000301a6ae210515f0b8957e"),
                Position.KEY_FUEL_LEVEL, 80.0);

    }

    @Test
    public void testDecodeFunctionList() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e0900000e0b3a73ce2ff20001f026080410300500010f03010407a77e"),
                "functionIds", "01,04,07");

    }

    @Test
    public void testDecodeBehaviour() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e090000200b3a73ce2ff20001f0260804103010000103021b00250573706565640000000301a6ae210515f0b8bc7e"),
                "behaviors", "1B,25(speed)");

        verifyAttribute(decoder, binary(
                "7e090000200b3a73ce2ff20001f0260804103010000103021b00250573706565640000000301a6ae210515f0b8bc7e"),
                Position.KEY_ALARM, Position.ALARM_BRAKING + "," + Position.ALARM_OVERSPEED);

    }

    @Test
    public void testDecodeCanData() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e0705001f0b3a73ce2ff20001000210300015000cf00400010203040506070818fee0001122334455667788047e"),
                "canData",
                "[{\"channel\":1,\"extended\":false,\"averaged\":false,\"id\":\"CF00400\","
                        + "\"data\":\"0102030405060708\"},"
                        + "{\"channel\":1,\"extended\":false,\"averaged\":false,\"id\":\"18FEE000\","
                        + "\"data\":\"1122334455667788\"}]");

    }

    /**
     * Table 8 labels offset 9 as the SIM ICCID, but a V521H in the field reports its terminal model
     * there, matching plain JT/T 808-2013. The field is classified by content rather than position.
     */
    @Test
    public void testDecodeRegister() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e010000360b3a73ce2ff200014a4d00014a494d4900563532314800000000000000000000000000000030303030303037004c56534843414d423041585858585858585c7e"),
                "model", "V521H");

        verifyAttribute(decoder, binary(
                "7e010000360b3a73ce2ff200014a4d00014a494d4900563532314800000000000000000000000000000030303030303037004c56534843414d423041585858585858585c7e"),
                Position.KEY_VIN, "LVSHCAMB0AXXXXXXX");

        verifyAttribute(decoder, binary(
                "7e010000360b3a73ce2ff200014a4d00014a494d4900563532314800000000000000000000000000000030303030303037004c56534843414d423041585858585858585c7e"),
                "manufacturer", "JIMI");

        verifyAttribute(decoder, binary(
                "7e0100002f0b3a73ce2ff200014a4d00014a494d49003839383630303030303030303030303030303031303030303030370142413243484131323334387e"),
                Position.KEY_ICCID, "89860000000000000001");

        verifyAttribute(decoder, binary(
                "7e0100002f0b3a73ce2ff200014a4d00014a494d49003839383630303030303030303030303030303031303030303030370142413243484131323334387e"),
                "plateNumber", "BA2CHA1234");

    }

    @Test
    public void testDecodePeripheral() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyAttribute(decoder, binary(
                "7e090000070b3a73ce2ff20001f3000200020a00a77e"),
                "collisionDelta", 0.1);

    }

    @Test
    public void testDecodeHeartbeat() throws Exception {

        var decoder = inject(new TrackonobdProtocolDecoder(null));

        verifyNull(decoder, binary(
                "7e000200000b3a73ce2ff20001527e"));

    }

}
