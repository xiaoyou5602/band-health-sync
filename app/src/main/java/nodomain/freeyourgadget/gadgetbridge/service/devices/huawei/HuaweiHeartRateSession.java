package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HrRriTest;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.Request;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendHROpenCloseRequest;

/** Android/Bluetooth adapter; results are broadcasts only, never historical samples. */
final class HuaweiHeartRateSession implements HuaweiHeartRateMeasurement.Port {
    private final HuaweiSupportProvider support;
    final String requestId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long startedSeconds = System.currentTimeMillis() / 1000;
    private final HuaweiHeartRateMeasurement measurement = new HuaweiHeartRateMeasurement(this);
    private Request receiver;
    private Runnable owner;

    HuaweiHeartRateSession(HuaweiSupportProvider support, String requestId) {
        this.support = support;
        this.requestId = requestId;
    }

    void start() { measurement.start(); }
    void cancel() { measurement.cancel(); }

    @Override
    public boolean acquire(Runnable cancel) {
        owner = () -> {
            if (Looper.myLooper() == Looper.getMainLooper()) cancel.run();
            else handler.post(cancel);
        };
        return support.beginHeartRateProbe(owner);
    }

    @Override
    public void register() {
        receiver = new Request(support) {
            @Override
            public boolean handleResponse(HuaweiPacket packet) {
                if (packet.serviceId != HrRriTest.id || packet.commandId != HrRriTest.RealtimeData.id) return false;
                receivedPacket = packet;
                return true;
            }

            @Override
            public boolean autoRemoveFromResponseHandler() { return false; }

            @Override
            protected void processResponse() {
                for (HrRriTest.RealtimeData.Sample sample : HrRriTest.RealtimeData.parse(receivedPacket.getTlv())) {
                    // Do not accept a delayed sample from an earlier measurement.
                    if (sample.deviceTime != null && sample.deviceTime < startedSeconds) continue;
                    handler.post(() -> measurement.receive(sample.heartRate));
                    break;
                }
            }
        };
        support.addInProgressRequest(receiver);
    }

    @Override
    public void send(byte type) throws IOException {
        if (support.isDisposed() || !support.getDevice().isInitialized()) {
            throw new IOException("Heart rate measurement connection closed");
        }
        new SendHROpenCloseRequest(support, type).doPerform();
    }

    @Override
    public void schedule(Runnable timeout, long delayMs) { handler.postDelayed(timeout, delayMs); }

    @Override
    public void cleanup(Runnable cancel, Runnable timeout) {
        handler.removeCallbacksAndMessages(null);
        try {
            if (receiver != null) support.removeInProgressRequests(receiver);
        } finally {
            support.finishHeartRateProbe(owner);
        }
    }

    @Override
    public void result(int bpm, String error) {
        publish(support, requestId, bpm, error);
    }

    static void publish(HuaweiSupportProvider support, String requestId, int bpm, String error) {
        Intent intent = new Intent(bpm > 0 ? DeviceService.ACTION_REALTIME_SAMPLES
                : DeviceService.ACTION_HEARTRATE_MEASUREMENT_RESULT);
        intent.putExtra(GBDevice.EXTRA_DEVICE, support.getDevice());
        intent.putExtra(DeviceService.EXTRA_HEARTRATE_REQUEST_ID, requestId);
        if (error != null) intent.putExtra(DeviceService.EXTRA_HEARTRATE_ERROR, error);
        if (bpm > 0) {
            GenericActivitySample sample = new GenericActivitySample();
            sample.setTimestamp((int) (System.currentTimeMillis() / 1000));
            sample.setHeartRate(bpm);
            intent.putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, sample);
        }
        LocalBroadcastManager.getInstance(support.getContext()).sendBroadcast(intent);
    }
}
