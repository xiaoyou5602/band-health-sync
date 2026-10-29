package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothGatt;
import android.os.Handler;
import org.junit.Test;
import java.lang.reflect.Field;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.Request;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class HuaweiReconnectTest {
    @Test
    public void oldSyncCompletionCannotClearNewConnectionsBusyState() {
        HuaweiSupportProvider provider = mock(HuaweiSupportProvider.class);
        when(provider.isDisposed()).thenReturn(true);
        HuaweiSyncState state = new HuaweiSyncState(provider);
        state.updateState();
        verify(provider, never()).getDevice();
    }

    @Test
    public void peripheralReconnectReplacesProtocolStateWithoutReplacingDevice() throws Exception {
        HuaweiSupportProvider first = mock(HuaweiSupportProvider.class);
        HuaweiSupportProvider second = mock(HuaweiSupportProvider.class);
        GBDevice device = mock(GBDevice.class);
        HuaweiLESupport support = new HuaweiLESupport() {
            @Override HuaweiSupportProvider createSupportProvider() { return second; }
            @Override public GBDevice getDevice() { return device; }
        };
        Field field = HuaweiLESupport.class.getDeclaredField("supportProvider");
        field.setAccessible(true);
        field.set(support, first);
        TransactionBuilder builder = mock(TransactionBuilder.class);

        support.initializeDevice(builder);
        verify(first).initializeDevice(builder);
        verify(first, never()).dispose();
        support.onConnectionStateChange((BluetoothGatt) null, 0, BluetoothProfile.STATE_DISCONNECTED);
        verify(first).dispose();
        support.initializeDevice(builder);
        verify(second).setContext(device, null);
        verify(second).initializeDevice(builder);
        verify(device).unsetBusyTask();
        assertSame(device, support.getDevice());
    }

    @Test
    public void cancelledOrDisposedRequestsCannotContinueOldChain() throws Exception {
        HuaweiSupportProvider provider = mock(HuaweiSupportProvider.class);
        Request.RequestCallback callback = mock(Request.RequestCallback.class);
        Request next = mock(Request.class);
        Request old = new Request(provider);
        old.nextRequest(next);
        old.setFinalizeReq(callback);
        old.cancel();
        old.handleNext();
        old.handleResponse();
        old.doPerform();
        verify(next, never()).doPerform();
        verifyNoInteractions(callback);

        Request unregistered = new Request(provider);
        unregistered.setFinalizeReq(callback);
        when(provider.isDisposed()).thenReturn(true);
        unregistered.handleNext();
        unregistered.doPerform();
        verifyNoInteractions(callback);
    }

    @Test
    public void responseManagerCancelsEveryPendingRequest() {
        HuaweiSupportProvider provider = mock(HuaweiSupportProvider.class);
        ResponseManager manager = new ResponseManager(provider);
        Request first = mock(Request.class);
        Request second = mock(Request.class);
        manager.addHandler(first);
        manager.addHandler(second);
        manager.cancelAll();
        manager.cancelAll();
        verify(first).cancel();
        verify(second).cancel();
    }

    @Test
    public void disposedRequestTimeoutCannotInvokeCompletion() throws Exception {
        HuaweiSupportProvider provider = mock(HuaweiSupportProvider.class);
        Request request = new Request(provider);
        Request.RequestCallback callback = mock(Request.RequestCallback.class);
        request.setFinalizeReq(callback);
        Field field = Request.class.getDeclaredField("timeoutRunner");
        field.setAccessible(true);
        Runnable timeout = (Runnable) field.get(request);
        when(provider.isDisposed()).thenReturn(true);
        timeout.run();
        verifyNoInteractions(callback);
        when(provider.isDisposed()).thenReturn(false);
        timeout.run();
        verify(provider).removeInProgressRequests(request);
        verify(callback).timeout(request);
    }

    @Test
    public void stalledSyncClosesTransportBeforeAutomaticReconnect() throws Exception {
        HuaweiLESupport transport = mock(HuaweiLESupport.class);
        when(transport.getAutoReconnect()).thenReturn(true);
        HuaweiSupportProvider provider = new HuaweiSupportProvider(transport);
        Field stateField = HuaweiSupportProvider.class.getDeclaredField("syncState");
        stateField.setAccessible(true);
        HuaweiSyncState state = (HuaweiSyncState) stateField.get(provider);
        state.startActivitySync();
        Field timeoutField = HuaweiSupportProvider.class.getDeclaredField("syncTimeout");
        timeoutField.setAccessible(true);
        Runnable timeout = (Runnable) timeoutField.get(provider);
        timeout.run();
        assertTrue(provider.isDisposed());
        org.mockito.InOrder order = inOrder(transport);
        order.verify(transport).disconnect();
        order.verify(transport).connect();
        timeout.run();
        verify(transport, times(1)).connect();
    }

    @Test
    public void idleSyncDoesNotReconnectAndUnrelatedTrafficDoesNotExtendTimeout() throws Exception {
        HuaweiLESupport transport = mock(HuaweiLESupport.class);
        HuaweiSupportProvider provider = new HuaweiSupportProvider(transport);
        Field timeoutField = HuaweiSupportProvider.class.getDeclaredField("syncTimeout");
        timeoutField.setAccessible(true);
        ((Runnable) timeoutField.get(provider)).run();
        verifyNoInteractions(transport);

        Field handlerField = HuaweiSupportProvider.class.getDeclaredField("handler");
        handlerField.setAccessible(true);
        Handler timer = mock(Handler.class);
        handlerField.set(provider, timer);
        Field stateField = HuaweiSupportProvider.class.getDeclaredField("syncState");
        stateField.setAccessible(true);
        ((HuaweiSyncState) stateField.get(provider)).startActivitySync();
        provider.onSyncProtocolProgress(0x09, 0x0f); // OTA traffic observed while sync was stuck
        provider.onSyncProtocolProgress(0x07, 0x0f); // unsolicited fitness notification
        verifyNoInteractions(timer);
        provider.onSyncProtocolProgress(0x07, 0x0b);
        verify(timer).postDelayed(any(Runnable.class), eq(300_000L));
        ((Runnable) timeoutField.get(provider)).run();
        verify(transport).disconnect();
        verify(transport, never()).connect(); // respects disabled automatic reconnect
    }
}
