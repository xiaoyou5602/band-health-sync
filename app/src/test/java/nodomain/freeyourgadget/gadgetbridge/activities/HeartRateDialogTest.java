package nodomain.freeyourgadget.gadgetbridge.activities;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HeartRateDialogTest {
    private final GBDevice device = new GBDevice("00:11:22:33:44:55", "Band", null, null, DeviceType.HUAWEIBAND10);
    private final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.GadgetbridgeTheme);

    private Intent result(String requestId) {
        GenericActivitySample sample = new GenericActivitySample(); sample.setHeartRate(74);
        return new Intent(DeviceService.ACTION_REALTIME_SAMPLES)
                .putExtra(GBDevice.EXTRA_DEVICE, device)
                .putExtra(DeviceService.EXTRA_HEARTRATE_REQUEST_ID, requestId)
                .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, sample);
    }

    @Test public void immediateResultWorksAndOldRequestCannotFinishLoading() {
        DeviceService service = mock(DeviceService.class);
        try (MockedStatic<GBApplication> app = mockStatic(GBApplication.class)) {
            app.when(() -> GBApplication.deviceService(device)).thenReturn(service);
            HeartRateDialog dialog = new HeartRateDialog(device, context);
            doAnswer(invocation -> {
                assertTrue(dialog.isShowing());
                LocalBroadcastManager.getInstance(context).sendBroadcastSync(result("old"));
                assertEquals(View.VISIBLE, dialog.heart_rate_dialog_loading_layout.getVisibility());
                LocalBroadcastManager.getInstance(context).sendBroadcastSync(result(invocation.getArgument(0)));
                return null;
            }).when(service).onHeartRateTest(anyString(), eq(true));
            dialog.show(); dialog.startMeasurement();
            assertEquals("74", dialog.heart_rate_widget_hr_value.getText().toString());
            assertEquals(View.GONE, dialog.heart_rate_dialog_loading_layout.getVisibility());
            dialog.dismiss();
            verify(service, never()).onHeartRateTest(anyString(), eq(false));
        }
    }

    @Test public void timeoutEndsLoadingAndCancelsOnlyOnce() {
        DeviceService service = mock(DeviceService.class);
        try (MockedStatic<GBApplication> app = mockStatic(GBApplication.class)) {
            app.when(() -> GBApplication.deviceService(device)).thenReturn(service);
            HeartRateDialog dialog = new HeartRateDialog(device, context);
            dialog.show(); dialog.startMeasurement();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25));
            assertEquals(View.GONE, dialog.heart_rate_dialog_loading_layout.getVisibility());
            assertEquals(context.getString(R.string.manual_hr_timeout), dialog.heart_rate_dialog_label.getText().toString());
            dialog.dismiss();
            verify(service, times(1)).onHeartRateTest(anyString(), eq(false));
        }
    }

    @Test public void dismissCancelsActiveMeasurement() {
        DeviceService service = mock(DeviceService.class);
        try (MockedStatic<GBApplication> app = mockStatic(GBApplication.class)) {
            app.when(() -> GBApplication.deviceService(device)).thenReturn(service);
            HeartRateDialog dialog = new HeartRateDialog(device, context);
            dialog.show(); dialog.startMeasurement(); dialog.dismiss();
            verify(service).onHeartRateTest(anyString(), eq(false));
        }
    }

    @Test public void busyResponseEndsLoadingImmediately() {
        DeviceService service = mock(DeviceService.class);
        try (MockedStatic<GBApplication> app = mockStatic(GBApplication.class)) {
            app.when(() -> GBApplication.deviceService(device)).thenReturn(service);
            doAnswer(invocation -> {
                LocalBroadcastManager.getInstance(context).sendBroadcastSync(
                        new Intent(DeviceService.ACTION_HEARTRATE_MEASUREMENT_RESULT)
                                .putExtra(GBDevice.EXTRA_DEVICE, device)
                                .putExtra(DeviceService.EXTRA_HEARTRATE_REQUEST_ID, (String) invocation.getArgument(0))
                                .putExtra(DeviceService.EXTRA_HEARTRATE_ERROR, "busy"));
                return null;
            }).when(service).onHeartRateTest(anyString(), eq(true));
            HeartRateDialog dialog = new HeartRateDialog(device, context);
            dialog.show(); dialog.startMeasurement();
            assertEquals(View.GONE, dialog.heart_rate_dialog_loading_layout.getVisibility());
            assertEquals(context.getString(R.string.manual_hr_busy), dialog.heart_rate_dialog_label.getText().toString());
            dialog.dismiss();
            verify(service, never()).onHeartRateTest(anyString(), eq(false));
        }
    }
}
