package nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets;

import org.junit.Test;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiTLV;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import static org.junit.Assert.*;

public class HrRriRealtimeTest {
    private final HuaweiPacket.ParamsProvider params = new HuaweiPacket.ParamsProvider() {
        public byte getDeviceSupportType() { return 0; }
        public byte[] getSecretKey() { return new byte[16]; }
        public byte[] getIv() { return new byte[16]; }
        public boolean areTransactionsCrypted() { return false; }
        public int getMtu() { return 0; }
        public int getSliceSize() { return 0xf4; }
    };

    private HuaweiPacket decode(byte command, HuaweiTLV data) throws Exception {
        HuaweiPacket outgoing = new HuaweiPacket(params) {{
            serviceId = HrRriTest.id;
            commandId = command;
            tlv = data;
            complete = true;
        }};
        return new HuaweiPacket(params).parse(outgoing.serialize().get(0));
    }

    @Test public void registryDistinguishesHeartRateAndPressurePackets() throws Exception {
        HuaweiPacket packet = decode((byte) 3, new HuaweiTLV().parse(
                GB.hexStringToByteArray("810E820C03014A04046AE49DC8050103")));
        assertTrue(packet instanceof HrRriTest.RealtimeData.Response);
        packet.parseTlv();
        assertEquals(74, ((HrRriTest.RealtimeData.Response) packet).samples.get(0).heartRate);
        assertTrue(decode((byte) 5, new HuaweiTLV()) instanceof HrRriTest.RriData.Response);
    }
    @Test public void realBand10FixturePreservesTimeAndRawCredibility() {
        HuaweiTLV tlv = new HuaweiTLV().parse(GB.hexStringToByteArray("810E820C03014A04046AE49DC8050103"));
        HrRriTest.RealtimeData.Sample sample = HrRriTest.RealtimeData.parse(tlv).get(0);
        assertEquals(74, sample.heartRate);
        assertEquals(Long.valueOf(0x6ae49dc8L), sample.deviceTime);
        assertEquals(Integer.valueOf(3), sample.credibility);
    }

    @Test public void standardTagsMultipleSamplesAndZeroPlaceholder() {
        HuaweiTLV tlv = new HuaweiTLV().put(1, new HuaweiTLV()
                .put(2, new HuaweiTLV().put(3, (byte) 0))
                .put(2, new HuaweiTLV().put(3, (byte) 70))
                .put(2, new HuaweiTLV().put(3, (byte) 71)));
        assertEquals(2, HrRriTest.RealtimeData.parse(tlv).size());
        assertEquals(70, HrRriTest.RealtimeData.parse(tlv).get(0).heartRate);
        assertNull(HrRriTest.RealtimeData.parse(tlv).get(0).deviceTime);
    }

    @Test public void ignoresStatusMissingFieldsAndMalformedContainers() {
        assertTrue(HrRriTest.RealtimeData.parse(null).isEmpty());
        assertTrue(HrRriTest.RealtimeData.parse(new HuaweiTLV().put(0x7f, 100000)).isEmpty());
        assertTrue(HrRriTest.RealtimeData.parse(new HuaweiTLV()).isEmpty());
        assertTrue(HrRriTest.RealtimeData.parse(new HuaweiTLV().put(0x81,
                new HuaweiTLV().put(0x82, new HuaweiTLV().put(3)))).isEmpty());
        assertTrue(HrRriTest.RealtimeData.parse(new HuaweiTLV().put(0x81, new byte[]{(byte) 0x82, 10})).isEmpty());
    }
}
