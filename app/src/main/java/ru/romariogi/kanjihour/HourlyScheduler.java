package ru.romariogi.kanjihour;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** A one-shot wall-clock alarm is renewed after each update. */
public final class HourlyScheduler {
    public static final String ACTION_REFRESH = "ru.romariogi.kanjihour.action.REFRESH_HOUR";
    public static final long HOUR_MILLIS = 3_600_000L;
    private static final String TAG = "KanjiHourScheduler";
    private static final int ALARM_REQUEST = 20;

    private HourlyScheduler() {}

    public static boolean hasExactPermission(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (manager == null) return false;
        try {
            return manager.canScheduleExactAlarms();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static long nextHour(long nowMillis) {
        return (Math.floorDiv(nowMillis, HOUR_MILLIS) + 1L) * HOUR_MILLIS;
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, RefreshReceiver.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, ALARM_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager manager = app.getSystemService(AlarmManager.class);
        if (manager == null) {
            Config.prefs(app).edit().putString("schedule_error",
                    "Служба обновлений Android недоступна.").apply();
            return;
        }
        try {
            boolean needed = Config.prefs(app).getBoolean("lock_enabled", false)
                    || KanjiWidgetProvider.widgetIds(app).length > 0;
            PendingIntent pending = pendingIntent(app);
            if (!needed) {
                manager.cancel(pending);
                Config.prefs(app).edit().remove("next_update_at")
                        .putString("schedule_mode", "off").remove("schedule_error").apply();
                return;
            }

            long next = nextHour(System.currentTimeMillis());
            boolean exact = hasExactPermission(app);
            if (exact) {
                try {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
                } catch (SecurityException revokedDuringCall) {
                    exact = false;
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
                }
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
            }
            Config.prefs(app).edit().putLong("next_update_at", next)
                    .putString("schedule_mode", exact ? "exact" : "inexact")
                    .remove("schedule_error").apply();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not schedule the next update", failure);
            Config.prefs(app).edit().remove("next_update_at")
                    .putString("schedule_mode", "error")
                    .putString("schedule_error", "Не удалось запланировать обновление. "
                            + "Откройте приложение и проверьте разрешения.").apply();
        }
    }
}
