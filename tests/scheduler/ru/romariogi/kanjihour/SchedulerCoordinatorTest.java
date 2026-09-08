package ru.romariogi.kanjihour;
import android.content.Context;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test actual dispatch and scheduling, including a cancellation after scheduling. */
public final class SchedulerCoordinatorTest {
    private static int checks;
    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) throws Exception {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback timeout");
    }
    public static void main(String[] args) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try {
            Context context = new Context(new File("unused-scheduler-test"));
            context.prefs.edit().putBoolean("lock_enabled", true).commit();
            long due = System.currentTimeMillis() - HourlyScheduler.HOUR_MILLIS;
            context.prefs.edit().putLong("next_update_at", due).commit();
            context.alarms.next = due;
            CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
            UpdateCoordinator.executor().execute(() -> {
                entered.countDown();
                try { await(release); } catch (Exception error) { throw new AssertionError(error); }
            });
            await(entered);
            RefreshCancellation cancellation = new RefreshCancellation();
            UpdateCoordinator.refresh(context, false, "recovery", cancellation, done::countDown);
            cancellation.cancel();
            release.countDown();
            await(done);
            require(LockWallpaperController.writes == 0, "cancelled work must not write");
            require(context.alarms.next == due,
                    "cancelled second trigger discarded independent due alarm");
            done = new CountDownLatch(1);
            long before = System.currentTimeMillis();
            UpdateCoordinator.refresh(context, false, HourlyScheduler.ACTION_REFRESH, done::countDown);
            await(done);
            require(LockWallpaperController.writes == 1, "delivered alarm performs current refresh");
            require(context.alarms.next > before, "consumed alarm advances, avoiding an immediate loop");
            require(context.jobs.schedules == 1, "recovery period is preserved");

            context = new Context(new File("unused-scheduler-test"));
            KanjiWidgetProvider.unavailable = true;
            done = new CountDownLatch(1);
            UpdateCoordinator.enableLock(context, .38f, 1f, done::countDown);
            await(done);
            require(context.prefs.getBoolean("lock_enabled", false), "launcher failure must not block enable");
            require(LockWallpaperController.writes == 2, "lock surface remains independent");
            System.out.println("SchedulerCoordinatorTest: " + checks + " checks passed");
        } finally {
            release.countDown();
            UpdateCoordinator.executor().shutdownNow();
            UpdateCoordinator.executor().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
