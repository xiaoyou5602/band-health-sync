package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.Breath;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiSupportProvider;

public class SendSleepBreathRequest extends Request {
    private static final Logger LOG = LoggerFactory.getLogger(SendSleepBreathRequest.class);

    private final boolean userRequested;

    /** Connection-time variant: never pushes an "off" state the user did not ask for. */
    public SendSleepBreathRequest(HuaweiSupportProvider support) {
        this(support, false);
    }

    public SendSleepBreathRequest(HuaweiSupportProvider support, boolean userRequested) {
        super(support);
        this.serviceId = Breath.id;
        this.commandId = Breath.SleepBreath.id;
        this.userRequested = userRequested;
    }

    private boolean sleepBreathEnabled() {
        return GBApplication
                .getDeviceSpecificSharedPrefs(this.getDevice().getAddress())
                .getBoolean(HuaweiConstants.PREF_HUAWEI_SLEEP_BREATH, false);
    }

    @Override
    protected boolean requestSupported() {
        if (!supportProvider.getDeviceState().supportsSleepBreath())
            return false;
        // Same reasoning as SetTruSleepRequest: the preference defaults to off, so the init
        // queue would disable sleep breathing awareness on the watch on every connect.
        if (!userRequested && !sleepBreathEnabled()) {
            LOG.info("Sleep breath is off in Gadgetbridge, leaving the watch setting untouched");
            return false;
        }
        return true;
    }

    @Override
    protected List<byte[]> createRequest() throws Request.RequestCreationException {
        boolean sleepBreathSwitch = sleepBreathEnabled();
        byte type = (byte) (sleepBreathSwitch?2:0);
        try {
            return new Breath.SleepBreath.Request(paramsProvider, type).serialize();
        } catch (HuaweiPacket.CryptoException e) {
            throw new Request.RequestCreationException(e);
        }
    }
}
