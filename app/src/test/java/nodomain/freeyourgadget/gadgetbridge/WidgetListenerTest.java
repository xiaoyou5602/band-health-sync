package nodomain.freeyourgadget.gadgetbridge;

import android.appwidget.AppWidgetManager;
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.junit.After;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class WidgetListenerTest extends TestBase {
    @After
    public void unregisterWidgetReceiver() {
        if (Widget.broadcastReceiver != null) {
            new Widget().onDisabled(getContext());
        }
    }

    @Test
    public void updateRestoresOneListenerAndDisableRemovesIt() {
        CountingWidget widget = new CountingWidget();

        widget.onUpdate(getContext(), null, new int[0]);
        widget.onUpdate(getContext(), null, new int[0]);

        assertNotNull(Widget.broadcastReceiver);

        LocalBroadcastManager broadcasts = LocalBroadcastManager.getInstance(getContext());
        broadcasts.sendBroadcastSync(new Intent(GBApplication.ACTION_NEW_DATA));
        assertEquals(1, widget.updateCount);

        Intent deleteOneWidget = new Intent(Widget.APPWIDGET_DELETED)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 42)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{42});
        widget.onReceive(getContext(), deleteOneWidget);
        assertNotNull(Widget.broadcastReceiver);

        broadcasts.sendBroadcastSync(new Intent(GBApplication.ACTION_NEW_DATA));
        assertEquals(2, widget.updateCount);

        widget.onDisabled(getContext());
        assertNull(Widget.broadcastReceiver);

        broadcasts.sendBroadcastSync(new Intent(GBApplication.ACTION_NEW_DATA));
        assertEquals(2, widget.updateCount);
    }

    private static class CountingWidget extends Widget {
        int updateCount;

        @Override
        public void updateWidget() {
            updateCount++;
        }
    }
}
