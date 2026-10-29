/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.util.NotificationUtils;

/**
 * Deduplicates phone-alarm signals from broadcasts and notification-listener callbacks.
 */
public final class PhoneAlarmBridge {
    public static final String PREF_KEY = "phone_alarm_band_link";
    public static final String DEVICE_CATEGORY = "gadgetbridge_phone_alarm_link";
    private static final long NOTIFICATION_REPLACEMENT_GRACE_MS = 1500;

    private static final Logger LOG = LoggerFactory.getLogger(PhoneAlarmBridge.class);
    private static final PhoneAlarmBridge INSTANCE = new PhoneAlarmBridge(
            new DeviceAlarmDispatcher(),
            () -> GBApplication.getPrefs().getBoolean(PREF_KEY, true)
    );

    private final AlarmDispatcher dispatcher;
    private final BooleanSupplier enabledProvider;
    private final Map<String, String> activeSources = new HashMap<>();
    private final Map<String, Runnable> pendingRemovals = new HashMap<>();
    private final Scheduler scheduler;
    private int activeNotificationId;
    private final Map<String, PhoneActions> phoneActions = new HashMap<>();
    private boolean actionSent;

    private static final class PhoneActions {
        final BooleanSupplier dismiss;
        final BooleanSupplier snooze;

        PhoneActions(BooleanSupplier dismiss, BooleanSupplier snooze) {
            this.dismiss = dismiss;
            this.snooze = snooze;
        }
    }

    public synchronized void setNotificationActions(String key, BooleanSupplier dismiss, BooleanSupplier snooze) {
        phoneActions.remove(notificationSource(key));
        if (activeSources.containsKey(notificationSource(key)) && dismiss != null && snooze != null) {
            phoneActions.put(notificationSource(key), new PhoneActions(dismiss, snooze));
        }
    }

    public synchronized boolean onDeviceAction(int notificationId, int action) {
        if (!enabledProvider.getAsBoolean() || activeNotificationId == 0
                || notificationId != activeNotificationId || actionSent || (action != 1 && action != 2)) return false;
        // Several independent ringing notifications cannot be mapped to one native alarm ID safely.
        final long liveNotifications = activeSources.keySet().stream()
                .filter(source -> source.startsWith("notification:") && !pendingRemovals.containsKey(source))
                .count();
        if (phoneActions.size() != 1 || liveNotifications != 1) {
            LOG.warn("Phone alarm action ignored: no unique live notification action");
            return false;
        }
        final PhoneActions actions = phoneActions.values().iterator().next();
        actionSent = true;
        final boolean sent = (action == 2 ? actions.dismiss : actions.snooze).getAsBoolean();
        if (!sent) actionSent = false;
        LOG.info("Phone alarm device action: action={}, sent={}", action, sent);
        // Wait for the clock's notification/broadcast to end the session, rather than claiming success here.
        return sent;
    }

    interface AlarmDispatcher {
        int start(Context context, String packageName);

        void stop(int notificationId);
    }

    interface Scheduler {
        void schedule(Runnable action, long delayMillis);

        void cancel(Runnable action);
    }

    PhoneAlarmBridge(final AlarmDispatcher dispatcher) {
        this(dispatcher, () -> true);
    }

    PhoneAlarmBridge(final AlarmDispatcher dispatcher, final BooleanSupplier enabledProvider) {
        this(dispatcher, enabledProvider, new MainThreadScheduler());
    }

    PhoneAlarmBridge(final AlarmDispatcher dispatcher, final BooleanSupplier enabledProvider, final Scheduler scheduler) {
        this.dispatcher = dispatcher;
        this.enabledProvider = enabledProvider;
        this.scheduler = scheduler;
    }

    public static PhoneAlarmBridge getInstance() {
        return INSTANCE;
    }

    public static boolean isEnabled() {
        return INSTANCE.enabledProvider.getAsBoolean();
    }

    public void onBroadcastChanged(final Context context, final String packageName, final boolean ringing) {
        updateSource(context, "broadcast:" + packageName, packageName, ringing, enabledProvider.getAsBoolean());
    }

    public void onNotificationChanged(
            final Context context,
            final String key,
            final String packageName,
            final boolean ringing
    ) {
        updateSource(context, notificationSource(key), packageName, ringing, enabledProvider.getAsBoolean());
    }

    public synchronized void onNotificationRemoved(final String key) {
        setNotificationActions(key, null, null);
        updateSource(null, notificationSource(key), null, false, enabledProvider.getAsBoolean());
    }

    public synchronized boolean isNotificationTracked(final String key) {
        return activeSources.containsKey(notificationSource(key));
    }

    public synchronized void onEnabledChanged(final boolean enabled) {
        if (!enabled) {
            stopCurrentAlarm("phone alarm bridge disabled");
        }
    }

    public synchronized void reset() {
        stopCurrentAlarm("phone alarm bridge reset");
    }

    synchronized void updateSource(
            final Context context,
            final String source,
            final String packageName,
            final boolean ringing,
            final boolean enabled
    ) {
        if (!enabled) {
            stopCurrentAlarm("phone alarm bridge disabled");
            return;
        }

        if (ringing) {
            if (source.startsWith("notification:")) {
                cancelPendingRemovals(packageName);
            }
            if (activeSources.put(source, packageName) != null) {
                LOG.debug("Ignoring duplicate phone alarm start from {}", source);
                return;
            }
            if (activeNotificationId == 0) {
                activeNotificationId = dispatcher.start(context, packageName);
                LOG.info("Phone alarm started from {}, device notification id {}", source, activeNotificationId);
            } else {
                LOG.debug("Merged phone alarm start from {} into notification {}", source, activeNotificationId);
            }
            return;
        }

        if (source.startsWith("broadcast:")) {
            if (activeNotificationId != 0 && activeSources.containsKey(source)) {
                stopCurrentAlarm("phone alarm done from " + source);
            }
        } else if (activeSources.containsKey(source) && !pendingRemovals.containsKey(source)) {
            phoneActions.remove(source);
            final Runnable removal = () -> expireNotificationSource(source);
            pendingRemovals.put(source, removal);
            scheduler.schedule(removal, NOTIFICATION_REPLACEMENT_GRACE_MS);
        }
    }

    private synchronized void expireNotificationSource(final String source) {
        if (pendingRemovals.remove(source) == null) {
            return;
        }
        activeSources.remove(source);
        if (activeSources.isEmpty()) {
            stopCurrentAlarm("phone alarm notification ended");
        }
    }

    private void cancelPendingRemovals(final String packageName) {
        for (final Map.Entry<String, Runnable> entry : new HashMap<>(pendingRemovals).entrySet()) {
            if (packageName.equals(activeSources.get(entry.getKey()))) {
                scheduler.cancel(entry.getValue());
                pendingRemovals.remove(entry.getKey());
                activeSources.remove(entry.getKey());
                phoneActions.remove(entry.getKey());
            }
        }
    }

    private void stopCurrentAlarm(final String reason) {
        for (final Runnable pendingRemoval : pendingRemovals.values()) {
            scheduler.cancel(pendingRemoval);
        }
        pendingRemovals.clear();
        if (activeNotificationId != 0) {
            dispatcher.stop(activeNotificationId);
            LOG.info("Phone alarm stopped: {}", reason);
        }
        activeSources.clear();
        phoneActions.clear();
        actionSent = false;
        activeNotificationId = 0;
    }

    private static String notificationSource(final String key) {
        return "notification:" + key;
    }

    private static final class MainThreadScheduler implements Scheduler {
        private Handler handler;

        private Handler handler() {
            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
            }
            return handler;
        }

        @Override
        public void schedule(final Runnable action, final long delayMillis) {
            handler().postDelayed(action, delayMillis);
        }

        @Override
        public void cancel(final Runnable action) {
            handler().removeCallbacks(action);
        }
    }

    private static final class DeviceAlarmDispatcher implements AlarmDispatcher {
        @Override
        public int start(final Context context, final String packageName) {
            final NotificationSpec notificationSpec = new NotificationSpec();
            notificationSpec.type = NotificationType.GENERIC_ALARM_CLOCK;
            notificationSpec.category = DEVICE_CATEGORY;
            notificationSpec.sourceAppId = packageName;

            final String appLabel = NotificationUtils.getApplicationLabel(context, packageName);
            notificationSpec.sourceName = appLabel != null ? appLabel : "Alarm Clock";
            notificationSpec.title = context.getString(R.string.menuitem_alarm);
            notificationSpec.body = DateFormat.getTimeFormat(context).format(new Date());
            notificationSpec.attachedActions = new ArrayList<>();

            final NotificationSpec.Action dismissAllAction = new NotificationSpec.Action();
            dismissAllAction.title = context.getString(R.string.notifications_dismiss_all);
            dismissAllAction.type = NotificationSpec.Action.TYPE_SYNTECTIC_DISMISS_ALL;
            notificationSpec.attachedActions.add(dismissAllAction);

            GBApplication.deviceService().onNotification(notificationSpec);
            return notificationSpec.getId();
        }

        @Override
        public void stop(final int notificationId) {
            GBApplication.deviceService().onDeleteNotification(notificationId);
        }
    }
}
