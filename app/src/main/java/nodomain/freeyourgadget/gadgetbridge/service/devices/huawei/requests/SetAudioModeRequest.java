/*  Copyright (C) 2024-2025 Martin.JM, Ilya Nikitenkov

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

package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests;

import android.content.SharedPreferences;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.Earphones;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiSupportProvider;

public class SetAudioModeRequest extends Request {

    public enum AudioMode {
        OFF((byte) 0x00),
        ANC((byte) 0x01),
        TRANSPARENCY((byte) 0x02);

        private final byte code;

        AudioMode(final byte code) {
            this.code = code;
        }

        public byte getCode() {
            return this.code;
        }

        public static AudioMode fromPreferences(final SharedPreferences prefs) {
            return AudioMode.valueOf(prefs.getString(DeviceSettingsPreferenceConst.PREF_HUAWEI_FREEBUDS_AUDIOMODE, OFF.name().toLowerCase()).toUpperCase());
        }
    }

    public SetAudioModeRequest(HuaweiSupportProvider supportProvider) {
        super(supportProvider);
        this.serviceId = Earphones.id;
        this.commandId = Earphones.SetAudioModeRequest.id;
        this.addToResponse = false; // Response with different command ID
    }

    @Override
    protected List<byte[]> createRequest() throws RequestCreationException {
        try {
            AudioMode audioMode = AudioMode.fromPreferences(GBApplication.getDeviceSpecificSharedPrefs(this.getDevice().getAddress()));
            return new Earphones.SetAudioModeRequest(this.paramsProvider, audioMode).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new RequestCreationException(e);
        }
    }
}
