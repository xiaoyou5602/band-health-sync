/*  Copyright (C) 2025 Me7c7

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiTLV;

public class HrRriTest {
    public static final byte id = 0x19;

    public static class RealtimeData {
        public static final byte id = 0x03;

        public static class Sample {
            public final int heartRate;
            public final Long deviceTime;
            public final Integer credibility;

            Sample(int heartRate, Long deviceTime, Integer credibility) {
                this.heartRate = heartRate;
                this.deviceTime = deviceTime;
                this.credibility = credibility;
            }
        }

        /** Malformed/empty notifications must not take down the Bluetooth reader. */
        public static List<Sample> parse(HuaweiTLV tlv) {
            List<Sample> samples = new ArrayList<>();
            if (tlv == null || tlv.contains(0x7f)) return samples;
            try {
                List<HuaweiTLV> containers = new ArrayList<>(tlv.getObjects(0x81));
                containers.addAll(tlv.getObjects(0x01));
                for (HuaweiTLV container : containers) {
                    List<HuaweiTLV> entries = new ArrayList<>(container.getObjects(0x82));
                    entries.addAll(container.getObjects(0x02));
                    for (HuaweiTLV entry : entries) {
                        byte[] hr = entry.getBytes(0x03, new byte[0]);
                        if (hr.length != 1 || (hr[0] & 0xff) == 0) continue;
                        byte[] time = entry.getBytes(0x04, new byte[0]);
                        Long seconds = time.length == 4
                                ? ((time[0] & 0xffL) << 24) | ((time[1] & 0xffL) << 16)
                                | ((time[2] & 0xffL) << 8) | (time[3] & 0xffL) : null;
                        byte[] quality = entry.getBytes(0x05, new byte[0]);
                        samples.add(new Sample(hr[0] & 0xff, seconds,
                                quality.length == 1 ? quality[0] & 0xff : null));
                    }
                }
            } catch (RuntimeException ignored) {
                // Truncated nested TLVs are ignored; the session still has a bounded timeout.
            }
            return samples;
        }

        public static class Response extends HuaweiPacket {
            public List<Sample> samples = new ArrayList<>();

            public Response(ParamsProvider paramsProvider) {
                super(paramsProvider);
            }

            @Override
            public void parseTlv() {
                samples = RealtimeData.parse(tlv);
            }
        }
    }

    public static class OpenOrClose {
        public static final byte id = 0x01;

        public static class Request extends HuaweiPacket {
            public Request(ParamsProvider paramsProvider, byte type) {
                super(paramsProvider);

                this.serviceId = HrRriTest.id;
                this.commandId = id;

                this.tlv = new HuaweiTLV().put(0x1, type);

                this.isEncrypted = true;
                this.complete = true;
            }
        }

        public static class Response extends HuaweiPacket {

            public Response(ParamsProvider paramsProvider) {
                super(paramsProvider);
            }

            @Override
            public void parseTlv() throws ParseException {

            }
        }
    }

    public static class RriData {
        public static final byte id = 0x05;

        public static class Response extends HuaweiPacket {
            public static class rriSqiData {
                public short rri;
                public byte sqi;

                @Override
                public String toString() {
                    return "SubContainer{" +
                            "rri=" + rri +
                            ", sqi=" + sqi +
                            '}';
                }
            }

            public List<rriSqiData> containers;

            public Response(ParamsProvider paramsProvider) {
                super(paramsProvider);
            }

            @Override
            public void parseTlv() throws ParseException {
                HuaweiTLV container = this.tlv.getObject(0x82);
                List<HuaweiTLV> subContainers = container.getObjects(0x83);
                this.containers = new ArrayList<>();
                for (HuaweiTLV subContainerTlv : subContainers) {
                    rriSqiData rriSqi = new rriSqiData();
                    rriSqi.rri = subContainerTlv.getShort(0x04);
                    rriSqi.sqi = subContainerTlv.getByte(0x05);
                    this.containers.add(rriSqi);
                }
            }
        }
    }

}
