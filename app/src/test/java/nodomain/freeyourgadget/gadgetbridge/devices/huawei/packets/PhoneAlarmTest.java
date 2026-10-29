package nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiTLV;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class PhoneAlarmTest {
    private final HuaweiPacket.ParamsProvider paramsProvider = new HuaweiPacket.ParamsProvider() {
        @Override public byte getDeviceSupportType() { return 0; }
        @Override public byte[] getSecretKey() { return new byte[16]; }
        @Override public byte[] getIv() { return new byte[16]; }
        @Override public boolean areTransactionsCrypted() { return false; }
        @Override public int getMtu() { return 0; }
        @Override public int getSliceSize() { return 0xf4; }
    };

    @Test
    public void startUsesVerifiedFieldWidthsAndGenericName() {
        final PhoneAlarm.Start packet = new PhoneAlarm.Start(paramsProvider, 0x0102030405060708L);
        assertEquals(Alarms.id, packet.serviceId);
        assertEquals(0x09, packet.commandId);
        assertArrayEquals(GB.hexStringToByteArray(
                "010400000001020b50686f6e6520616c61726d0308010203040506070804010a050103"),
                packet.getTlv().serialize());
    }

    @Test
    public void dismissMatchesTheSuccessfulProbePacket() throws HuaweiPacket.CryptoException {
        final PhoneAlarm.Dismiss packet = new PhoneAlarm.Dismiss(paramsProvider);
        assertEquals(Alarms.id, packet.serviceId);
        assertEquals(0x0a, packet.commandId);
        assertArrayEquals(GB.hexStringToByteArray("5A000C00080A010400000001020102D94E"),
                packet.serialize().get(0));
    }

    @Test
    public void deviceActionsRequireExactVerifiedPayload() {
        assertEquals(2, PhoneAlarm.readAction(new HuaweiTLV().put(1, 1).put(2, (byte) 2)));
        assertEquals(1, PhoneAlarm.readAction(new HuaweiTLV().put(1, 1).put(2, (byte) 1)));
        assertEquals(0, PhoneAlarm.readAction(new HuaweiTLV().put(0x7f, 100000)));
        assertEquals(0, PhoneAlarm.readAction(new HuaweiTLV().put(1, 2).put(2, (byte) 2)));
        assertEquals(0, PhoneAlarm.readAction(new HuaweiTLV().put(1, 1).put(2, (byte) 3)));
        assertEquals(0, PhoneAlarm.readAction(new HuaweiTLV().put(1, (byte) 1).put(2, (byte) 2)));
        assertEquals(0, PhoneAlarm.readAction(new HuaweiTLV().put(1, 1).put(2, 2)));
        assertEquals(0, PhoneAlarm.readAction(new HuaweiTLV().put(1, 1)));
        assertEquals(0, PhoneAlarm.readAction(null));
    }
}
