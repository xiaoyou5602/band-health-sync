package nodomain.freeyourgadget.gadgetbridge.service;

import android.companion.AssociationInfo;
import android.net.MacAddress;
import android.os.Build;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import nodomain.freeyourgadget.gadgetbridge.externalevents.BluetoothConnectReceiver;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class GBCompanionDeviceServiceTest {
    @Test
    public void legacyObservationStillReconnectsOnAndroid16() {
        int originalSdk = Build.VERSION.SDK_INT;
        ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 36);
        try (MockedStatic<BluetoothConnectReceiver> receiver = mockStatic(BluetoothConnectReceiver.class)) {
            AssociationInfo association = mock(AssociationInfo.class);
            MacAddress mac = mock(MacAddress.class);
            when(association.getDeviceMacAddress()).thenReturn(mac);
            when(mac.toString()).thenReturn("12:34:56:78:90:AB");
            GBCompanionDeviceService service = new GBCompanionDeviceService();
            service.onDeviceAppeared(association);
            receiver.verify(() -> BluetoothConnectReceiver.observedDevice("12:34:56:78:90:AB"));
            service.onDeviceDisappeared(association);
            receiver.verifyNoMoreInteractions();
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", originalSdk);
        }
    }
}
