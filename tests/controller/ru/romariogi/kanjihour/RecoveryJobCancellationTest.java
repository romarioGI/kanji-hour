package ru.romariogi.kanjihour;

import android.app.job.JobParameters;
import android.os.Handler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Actual service/coordinator/controller; Android APIs are deterministic test doubles. */
public final class RecoveryJobCancellationTest {
    private static int checks;
    private static Path directory;

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        void block() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        void await() throws Exception {
            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker did not reach gate");
        }
        public void close() { release.countDown(); }
    }

    private static void drain() throws Exception {
        UpdateCoordinator.executor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        Handler.drain();
    }

    private static RecoveryJobService reset() throws Exception {
        drain();
        RecoveryJobService service = new RecoveryJobService();
        Files.write(Config.sourceFile(service).toPath(), new byte[] {1});
        service.prefs.edit().putBoolean("lock_enabled", true).commit();
        CurrentWallpaperImporter.currentId = 10;
        CurrentWallpaperImporter.reads = CurrentWallpaperImporter.checks = 0;
        CurrentWallpaperImporter.denied = CurrentWallpaperImporter.unavailable = false;
        CurrentWallpaperImporter.onCheck = null;
        WallpaperRenderer.writes = 0;
        WallpaperRenderer.failAfterInstall = false;
        WallpaperRenderer.onRender = WallpaperRenderer.onWrite = null;
        HourlyScheduler.calls = 0;
        return service;
    }

    private static void noFailure(RecoveryJobService service) {
        require(service.prefs.getString("lock_error", "").isEmpty(), "cancellation is not a lock error");
        require(service.prefs.getString("update_error", "").isEmpty(), "cancellation is not an update error");
    }

    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory("kanji-recovery-test-");
        System.setProperty("test.files", directory.toString());
        try {
            RecoveryJobService service = reset();
            try (Gate gate = new Gate()) {
                UpdateCoordinator.executor().execute(gate::block);
                gate.await();
                require(service.onStartJob(new JobParameters(21)), "async run");
                // Android may deliver a different parcelled object for the same job ID.
                require(service.onStopJob(new JobParameters(21)), "request a later retry");
                require(WallpaperRenderer.writes == 0, "no write while queued");
            }
            drain();
            require(WallpaperRenderer.writes == 0, "stopped queued job must not write");
            require(CurrentWallpaperImporter.reads == 0, "stopped queued job must not import");
            require(service.finishes == 0, "no jobFinished for stopped job");
            require(HourlyScheduler.calls > 0, "independent schedule preserved");
            noFailure(service);

            service = reset();
            JobParameters replacement = new JobParameters(21);
            try (Gate gate = new Gate()) {
                UpdateCoordinator.executor().execute(gate::block);
                gate.await();
                service.onStartJob(new JobParameters(21));
                service.onStopJob(new JobParameters(21));
                service.onStartJob(replacement);
            }
            drain();
            require(WallpaperRenderer.writes == 1, "only replacement may write");
            require(service.finishes == 1 && service.lastFinished == replacement, "old callback cannot finish new run");

            service = reset();
            try (Gate gate = new Gate()) {
                UpdateCoordinator.executor().execute(gate::block);
                gate.await();
                service.onStartJob(new JobParameters(21));
                service.onStopJob(new JobParameters(21));
                UpdateCoordinator.refresh(service, true, "alarm_test", null);
            }
            drain();
            require(WallpaperRenderer.writes == 1, "alarm is not cancelled with recovery");
            require(service.finishes == 0, "independent alarm does not finish stopped job");

            service = reset();
            try (Gate gate = new Gate()) {
                WallpaperRenderer.onRender = gate::block;
                service.onStartJob(new JobParameters(21));
                gate.await();
                service.onStopJob(new JobParameters(21));
            }
            drain();
            require(WallpaperRenderer.writes == 0, "cancel while rendering prevents write");
            require(WallpaperRenderer.last.recycled, "cancelled render bitmap released");
            require(!service.prefs.getBoolean("lock_write_pending", false), "no journal before write phase");
            require(service.finishes == 0, "cancelled rendering has no jobFinished");
            noFailure(service);

            service = reset();
            try (Gate gate = new Gate()) {
                CurrentWallpaperImporter.onCheck = () -> {
                    if (CurrentWallpaperImporter.checks == 2) gate.block();
                };
                service.onStartJob(new JobParameters(21));
                gate.await();
                require(service.prefs.getBoolean("lock_write_pending", false), "journal exists at final recheck");
                service.onStopJob(new JobParameters(21));
            }
            drain();
            require(WallpaperRenderer.writes == 0, "cancel at final recheck prevents system write");
            require(!service.prefs.getBoolean("lock_write_pending", true), "known non-write clears pending journal");
            require(service.prefs.getString("lock_write_uncertain", "").isEmpty(), "cancel is not quarantined");
            noFailure(service);
            CurrentWallpaperImporter.onCheck = null;
            service.onStartJob(new JobParameters(21));
            drain();
            require(WallpaperRenderer.writes == 1 && service.finishes == 1, "retry after cancellation works");

            service = reset();
            try (Gate gate = new Gate()) {
                WallpaperRenderer.onWrite = gate::block;
                service.onStartJob(new JobParameters(21));
                gate.await();
                require(WallpaperRenderer.writes == 1, "system write already started");
                service.onStopJob(new JobParameters(21));
            }
            drain();
            require(WallpaperRenderer.writes == 1, "no additional write on stop");
            require(service.prefs.getInt("lock_last_wallpaper_id", 0) == 1001, "finish ownership commit after started write");
            require(!service.prefs.getBoolean("lock_write_pending", true), "started write safely committed");
            require(service.finishes == 0, "no late jobFinished after stop during write");
            require(WallpaperRenderer.last.recycled, "completed bitmap released");
            noFailure(service);

            service = reset();
            try (Gate gate = new Gate()) {
                UpdateCoordinator.executor().execute(gate::block);
                gate.await();
                service.onStartJob(new JobParameters(21));
                service.onDestroy();
            }
            drain();
            require(WallpaperRenderer.writes == 0 && service.finishes == 0, "destroy cancels queued work");

            service = reset();
            try (Gate gate = new Gate()) {
                UpdateCoordinator.executor().execute(gate::block);
                gate.await();
                service.onStartJob(new JobParameters(21));
                service.onStopJob(new JobParameters(99));
            }
            drain();
            require(WallpaperRenderer.writes == 1 && service.finishes == 1, "unrelated job ID cannot cancel active run");
            System.out.println("RecoveryJobCancellationTest: " + checks + " checks passed");
        } finally {
            UpdateCoordinator.executor().shutdownNow();
            UpdateCoordinator.executor().awaitTermination(5, TimeUnit.SECONDS);
            Handler.drain();
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
