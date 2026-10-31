package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiTLV;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HrRriTest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.Request;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.SendHROpenCloseRequest;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** A debug-only, bounded protocol probe. It never publishes or stores a health sample. */
public final class HuaweiHeartRateDebugProbe {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiHeartRateDebugProbe.class);
    private static final long WINDOW_MS = 15_000;
    private static final long OBSERVATION_MS = 30_000;
    private static final int MAX_LOGGED_PACKETS = 12;
    private static final int MAX_TLV_BYTES = 128;

    private HuaweiHeartRateDebugProbe() {
    }

    public static void start(HuaweiSupportProvider support) {
        new Session(support).start();
    }

    public static void cancel(HuaweiSupportProvider support) {
        support.cancelHeartRateProbe();
    }

    private static final class Session {
        private final HuaweiSupportProvider support;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Runnable cancelAction = this::cancelSession;
        private final Runnable timeoutAction = () -> stop("15-second window ended");
        private final Runnable observationAction = () -> finish("30-second observation ended");
        private final Request receiver;
        private int packetCount;
        private int openResponseCount;
        private boolean openAttempted;
        private boolean observing;
        private boolean closeQueued;
        private long closeRequestedAt;
        private int postClosePacketCount;
        private int postCloseResponseCount;

        Session(HuaweiSupportProvider support) {
            this.support = support;
            receiver = new Request(support) {
                @Override
                public boolean handleResponse(HuaweiPacket packet) {
                    if (packet.serviceId != HrRriTest.id
                            || (packet.commandId != 0x03 && packet.commandId != HrRriTest.OpenOrClose.id)) {
                        return false;
                    }
                    receivedPacket = packet;
                    return true;
                }

                @Override
                public boolean autoRemoveFromResponseHandler() {
                    return false;
                }

                @Override
                protected void processResponse() {
                    logPacket(receivedPacket);
                }
            };
        }

        synchronized void start() {
            if (!support.beginHeartRateProbe(cancelAction)) {
                LOG.warn("Heart rate probe rejected: another heart rate probe or stress calibration is active");
                notifyStatus("心率探针未开始：另一次探针或压力校准正在进行", GB.WARN);
                return;
            }
            try {
                support.addInProgressRequest(receiver);
                handler.postDelayed(timeoutAction, WINDOW_MS);
                openAttempted = true;
                new SendHROpenCloseRequest(support, (byte) 1).doPerform();
                LOG.info("Heart rate probe: receiver registered, type=1 queued; window={} ms", WINDOW_MS);
                notifyStatus("心率探针命令已排队，等待手环回包（15 秒）", GB.INFO);
            } catch (IOException | RuntimeException e) {
                LOG.error("Heart rate probe open failed", e);
                notifyStatus("心率探针开启命令发送失败，请查看调试日志", GB.ERROR);
                stop("open failed");
            }
        }

        private synchronized void logPacket(HuaweiPacket packet) {
            if (stopped.get()) {
                return;
            }
            if (packet.commandId == HrRriTest.OpenOrClose.id) {
                int count = observing ? ++postCloseResponseCount : ++openResponseCount;
                if (count <= MAX_LOGGED_PACKETS) {
                    logTlv(packet, count);
                }
                return;
            }
            if (observing) {
                postClosePacketCount++;
                if (postClosePacketCount <= MAX_LOGGED_PACKETS) {
                    logTlv(packet, postClosePacketCount);
                }
                return;
            }
            packetCount++;
            if (packetCount == 1) {
                notifyStatus("已收到手环心率协议包，内容已记录到调试日志", GB.INFO);
            }
            if (packetCount > MAX_LOGGED_PACKETS) {
                return;
            }
            logTlv(packet, packetCount);
        }

        private void logTlv(HuaweiPacket packet, int count) {
            LOG.info("Heart rate probe phase={}, elapsedSinceCloseRequestMs={}",
                    observing ? "after-close-request" : "measurement",
                    observing ? SystemClock.elapsedRealtime() - closeRequestedAt : -1);
            HuaweiTLV tlv = packet.getTlv();
            byte[] bytes = tlv == null ? null : tlv.serialize();
            String command = packet.commandId == 0x03 ? "0x03" : "0x01";
            if (bytes == null) {
                LOG.info("Heart rate probe packet #{}: service=0x19 command={}, TLV unavailable", count, command);
            } else if (bytes.length > MAX_TLV_BYTES) {
                LOG.info("Heart rate probe packet #{}: service=0x19 command={}, TLV length={} (not logged)",
                        count, command, bytes.length);
            } else {
                LOG.info("Heart rate probe packet #{}: service=0x19 command={}, decryptedTlv={}",
                        count, command, StringUtils.bytesToHex(bytes));
            }
        }

        private void notifyStatus(String message, int level) {
            handler.post(() -> GB.toast(support.getContext(), message, Toast.LENGTH_LONG, level));
        }

        private synchronized void cancelSession() {
            if (observing) {
                finish("observation interrupted by cancellation or disconnect; not a stop verification");
            } else {
                stop("cancelled or disconnected");
            }
        }

        private synchronized void stop(String reason) {
            if (stopped.get() || observing) {
                return;
            }
            handler.removeCallbacks(timeoutAction);
            observing = true;
            closeRequestedAt = SystemClock.elapsedRealtime();
            try {
                if (openAttempted) {
                    new SendHROpenCloseRequest(support, (byte) 2).doPerform();
                    closeQueued = true;
                }
            } catch (IOException | RuntimeException e) {
                LOG.error("Heart rate probe type=2 close attempt failed", e);
            } finally {
                LOG.info("Heart rate probe close requested: reason={}, type=2, queued={}, observationMs={}",
                        reason, closeQueued, OBSERVATION_MS);
                handler.postDelayed(observationAction, OBSERVATION_MS);
                notifyStatus(closeQueued
                                ? "停止命令 type=2 已排队，继续观察 30 秒；回包不代表已停止"
                                : "停止命令未排队，继续记录 30 秒；请查看日志，不能确认停止",
                        closeQueued ? GB.INFO : GB.WARN);
            }
        }

        private synchronized void finish(String reason) {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            handler.removeCallbacks(timeoutAction);
            handler.removeCallbacks(observationAction);
            try {
                support.removeInProgressRequests(receiver);
            } finally {
                support.finishHeartRateProbe(cancelAction);
                LOG.info("Heart rate probe finished: reason={}, packets={}, openResponses={}, closeQueued={}, "
                                + "postClosePackets={}, postCloseResponses={}, elapsedSinceCloseRequestMs={}; "
                                + "inspect device timestamps and connection continuity before judging stop",
                        reason, packetCount, openResponseCount, closeQueued, postClosePacketCount,
                        postCloseResponseCount, SystemClock.elapsedRealtime() - closeRequestedAt);
                notifyStatus("心率探针记录结束：停止请求后收到 " + postClosePacketCount
                        + " 个心率协议包；是否停止需核对日志时间戳和连接状态", GB.WARN);
            }
        }
    }
}
