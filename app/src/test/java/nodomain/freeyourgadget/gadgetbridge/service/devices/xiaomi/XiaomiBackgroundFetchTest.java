package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import org.junit.Test;
import java.util.Collections;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileFetcher;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiHealthService;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class XiaomiBackgroundFetchTest extends TestBase {
    @Test public void reservesBeforeMetadataAndEmptyHistoryReleasesOnlyAfterPastResponse() {
        GBDevice device = new GBDevice("00:11:22:33:44:55", "Test", null, null, DeviceType.MIBAND9PRO);
        device.setState(GBDevice.State.INITIALIZED);
        XiaomiSupport support = mock(XiaomiSupport.class);
        when(support.getDevice()).thenReturn(device);
        when(support.getContext()).thenReturn(getContext());
        XiaomiHealthService health = mock(XiaomiHealthService.class);
        when(health.getSupport()).thenReturn(support);
        XiaomiActivityFileFetcher fetcher = new XiaomiActivityFileFetcher(health);

        assertTrue(fetcher.beginFetch());
        assertTrue(device.isBusy());
        assertFalse(fetcher.beginFetch());
        fetcher.setAwaitingPastResponse(true);
        fetcher.fetch(Collections.emptyList());
        assertTrue(device.isBusy());
        assertFalse(fetcher.beginFetch());
        fetcher.fetch(Collections.emptyList());
        fetcher.setAwaitingPastResponse(false);
        fetcher.resumeFetching();
        assertFalse(device.isBusy());
        assertTrue(fetcher.beginFetch());
        fetcher.dispose();
    }

    @Test public void disconnectAndDisposeAllowFreshConnection() {
        GBDevice device = new GBDevice("00:11:22:33:44:55", "Test", null, null, DeviceType.MIBAND9PRO);
        XiaomiSupport support = mock(XiaomiSupport.class);
        when(support.getDevice()).thenReturn(device);
        when(support.getContext()).thenReturn(getContext());
        XiaomiHealthService health = new XiaomiHealthService(support);
        XiaomiActivityFileFetcher fetcher = health.getActivityFetcher();
        assertFalse(fetcher.beginFetch());
        device.setState(GBDevice.State.INITIALIZED);
        assertTrue(fetcher.beginFetch());
        device.setState(GBDevice.State.NOT_CONNECTED);
        health.onDisconnect();
        assertFalse(fetcher.beginFetch());
        device.setState(GBDevice.State.INITIALIZED);
        assertTrue(fetcher.beginFetch());
        fetcher.dispose();
    }
}
