package ru.romariogi.kanjihour;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** An hourly alarm plus an independent, persistent recovery job. */
public final class HourlyScheduler {
    public static final String ACTION_REFRESH = "ru.romariogi.kanjihour.action.REFRESH_HOUR";
    public static final long HOUR_MILLIS = 3_600_000L;
    private static final String TAG = "KanjiHourScheduler";
    private static final int ALARM_REQUEST = 20;
    static final int RECOVERY_JOB = 21;
    static final long RECOVERY_INTERVAL = 30 * 60_000L;

    private HourlyScheduler() {}

    public static boolean hasExactPermission(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        try { return manager != null && manager.canScheduleExactAlarms(); }
        catch (RuntimeException unavailable) { return false; }
    }

    public static long nextHour(long nowMillis) {
        return (Math.floorDiv(nowMillis, HOUR_MILLIS) + 1L) * HOUR_MILLIS;
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, RefreshReceiver.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, ALARM_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static synchronized void schedule(Context context) {
        Context app = context.getApplicationContext();
        boolean needed = Config.prefs(app).getBoolean("lock_enabled", false)
                || KanjiWidgetProvider.widgetIds(app).length > 0;
        // Independent of exact-alarm access and of whether a home widget exists.
        scheduleRecovery(app, needed);
        AlarmManager manager = app.getSystemService(AlarmManager.class);
        try {
            if (manager == null) throw new IllegalStateException("AlarmManager unavailable");
            PendingIntent pending = pendingIntent(app);
            if (!needed) {
                manager.cancel(pending);
                Config.prefs(app).edit().remove("next_update_at").putString("schedule_mode", "off")
                        .remove("schedule_error").apply();
                return;
            }
            long next = nextHour(System.currentTimeMillis());
            boolean exact = hasExactPermission(app);
            if (exact) {
                try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending); }
                catch (SecurityException revokedDuringCall) {
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
            Log.w(TAG, "Could not schedule hourly alarm", failure);
            Config.prefs(app).edit().remove("next_update_at").putString("schedule_mode", "error")
                    .putString("schedule_error", "Не удалось назначить почасовое обновление.").apply();
        }
    }

    private static void scheduleRecovery(Context context, boolean needed) {
        try {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs == null) throw new IllegalStateException("JobScheduler unavailable");
            if (!needed) {
                jobs.cancel(RECOVERY_JOB);
            } else {
                JobInfo existing = jobs.getPendingJob(RECOVERY_JOB);
                // Replacing a periodic job on every refresh would postpone its execution.
                if (existing == null || !existing.isPersisted()
                        || existing.getIntervalMillis() != RECOVERY_INTERVAL) {
                    JobInfo job = new JobInfo.Builder(RECOVERY_JOB,
                            new ComponentName(context, RecoveryJobService.class))
                            .setPeriodic(RECOVERY_INTERVAL).setPersisted(true).build();
                    if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS)
                        throw new IllegalStateException("Recovery job rejected");
                }
            }
            Config.prefs(context).edit().remove("recovery_error").apply();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not schedule recovery", failure);
            Config.prefs(context).edit().putString("recovery_error",
                    "Резервное обновление недоступно. Откройте приложение повторно.").apply();
        }
    }
}
