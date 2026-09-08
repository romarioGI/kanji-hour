package ru.romariogi.kanjihour;
import android.content.Context;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A launcher query failure must not prevent enabling the independent lock surface. */
public final class LauncherIsolationTest {
    public static void main(String[] args) throws Exception {
        try {
            Context context = new Context(new File("unused-scheduler-test"));
            KanjiWidgetProvider.unavailable = true;
            CountDownLatch done = new CountDownLatch(1);
            UpdateCoordinator.enableLock(context, .38f, 1f, done::countDown);
            if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback not completed");
            if (!context.prefs.getBoolean("lock_enabled", false) || LockWallpaperController.writes != 1)
                throw new AssertionError("launcher query failure blocked enabling lock wallpaper");
            if (context.jobs.pending == null || context.alarms.next <= 0)
                throw new AssertionError("independent schedules missing");
            System.out.println("LauncherIsolationTest: 3 checks passed");
        } finally {
            UpdateCoordinator.executor().shutdownNow();
            UpdateCoordinator.executor().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
