package ru.romariogi.kanjihour;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One writer for settings, clean sources and wallpaper updates. */
public final class UpdateCoordinator {
    private static final String TAG = "KanjiHourUpdate";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kanji-hour-updates");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private UpdateCoordinator() {}
    public static ExecutorService executor() { return EXECUTOR; }

    public static void refresh(Context context, boolean force, Runnable completion) {
        refresh(context, force, "ui_or_widget", completion);
    }

    public static void refresh(Context context, boolean force, String trigger, Runnable completion) {
        refresh(context, force, trigger, new RefreshCancellation(), completion);
    }

    public static void refresh(Context context, boolean force, String trigger,
                               RefreshCancellation cancellation, Runnable completion) {
        Context app = context.getApplicationContext();
        submit(app, trigger, () -> performRefresh(app, force, cancellation), completion, cancellation);
    }

    public static void enableLock(Context context, float y, float scale, Runnable completion) {
        Context app = context.getApplicationContext();
        submit(app, "enable_lock", () -> {
            try {
                LockWallpaperController.persist(Config.prefs(app).edit().putBoolean("lock_enabled", true)
                        .putFloat("lock_y", Math.max(.2f, Math.min(.65f, y)))
                        .putFloat("lock_scale", Math.max(.75f, Math.min(1.25f, scale))));
                // Enabling changes scheduling needs; arm before the first potentially slow write.
                HourlyScheduler.schedule(app);
                performRefresh(app, true, new RefreshCancellation());
            } catch (IOException error) { lockError(app, error); }
        }, completion);
    }

    public static void disableLock(Context context, Runnable completion) {
        Context app = context.getApplicationContext();
        submit(app, "disable_lock", () -> {
            try { LockWallpaperController.disableAndRemove(app); }
            catch (IOException | RuntimeException error) { lockError(app, error); }
        }, completion);
    }

    /** Completion also runs on dispatch failure, so receivers always release goAsync(). */
    private static void submit(Context app, String trigger, Runnable work, Runnable completion) {
        submit(app, trigger, work, completion, new RefreshCancellation());
    }

    private static void submit(Context app, String trigger, Runnable work, Runnable completion,
                               RefreshCancellation cancellation) {
        long received = System.currentTimeMillis(), started = SystemClock.elapsedRealtime();
        long expected = Config.prefs(app).getLong("next_update_at", 0);
        RefreshTask.submit(EXECUTOR, () -> HourlyScheduler.schedule(app), () -> {
            Config.prefs(app).edit().putString("last_trigger", trigger).putLong("last_trigger_at", received)
                    .putLong("last_expected_at", expected).apply();
            if (HourlyScheduler.ACTION_REFRESH.equals(trigger))
                Config.prefs(app).edit().putLong("last_alarm_at", received).putLong("last_alarm_expected_at", expected).apply();
            if ("recovery".equals(trigger))
                Config.prefs(app).edit().putLong("last_recovery_at", received).apply();
            work.run();
        }, failure -> {
            Log.w(TAG, "Update failed", failure);
            Config.prefs(app).edit().putString("update_error", "Не удалось обновить кандзи. Повторите попытку.").apply();
        }, () -> {
            try {
                Config.prefs(app).edit().putLong("last_refresh_finished_at", System.currentTimeMillis())
                        .putLong("last_refresh_duration_ms", SystemClock.elapsedRealtime() - started).apply();
            } finally { if (completion != null) completion.run(); }
        }, cancellation);
    }

    private static void performRefresh(Context context, boolean force, RefreshCancellation cancellation) {
        cancellation.throwIfCancelled();
        SharedPreferences prefs = Config.prefs(context);
        long now = System.currentTimeMillis();
        Kanji kanji = KanjiRepository.getAtTime(context, now);
        try {
            if (KanjiWidgetProvider.widgetIds(context).length > 0) {
                cancellation.throwIfCancelled();
                KanjiWidgetProvider.updateAll(context, kanji);
                prefs.edit().putLong("widget_last_success", System.currentTimeMillis()).apply();
            }
            prefs.edit().remove("update_error").apply();
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException error) {
            Log.w(TAG, "Home widget update failed", error);
            prefs.edit().putString("update_error", "Не удалось обновить домашний виджет.").apply();
        }
        cancellation.throwIfCancelled();
        try {
            LockWallpaperController.refresh(context, kanji, Math.floorDiv(now, HourlyScheduler.HOUR_MILLIS),
                    force, cancellation);
        } catch (CancellationException stopped) {
            throw stopped;
        } catch (IOException | RuntimeException error) { lockError(context, error); }
    }

    private static void lockError(Context context, Exception error) {
        Log.w(TAG, "Lock wallpaper update paused", error);
        String message = error.getMessage();
        Config.prefs(context).edit().putString("lock_error", message == null || message.trim().isEmpty()
                ? "Не удалось обновить экран блокировки. Повторите попытку." : message).apply();
    }
}
