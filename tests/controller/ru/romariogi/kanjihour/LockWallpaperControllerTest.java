package ru.romariogi.kanjihour;

import android.content.Context;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Runs the real controller against test doubles; not Android instrumentation or e2e. */
public final class LockWallpaperControllerTest {
    private static int checks;
    private static Context context;
    private static final Kanji KANJI = new Kanji();
    private interface Operation { void run() throws IOException; }
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void fails(Operation operation) throws IOException {
        try { operation.run(); throw new AssertionError("failure expected"); }
        catch (IOException expected) { checks++; }
    }
    private static void refresh() throws IOException { LockWallpaperController.refresh(context, KANJI, 10, false); }
    private static void reset(Path directory) throws IOException {
        context = new Context(directory.toFile());
        Files.write(Config.sourceFile(context).toPath(), new byte[] {1});
        context.prefs.edit().putBoolean("lock_enabled", true).commit();
        CurrentWallpaperImporter.currentId = 10;
        CurrentWallpaperImporter.reads = CurrentWallpaperImporter.checks = 0;
        CurrentWallpaperImporter.denied = CurrentWallpaperImporter.unavailable = false;
        CurrentWallpaperImporter.onCheck = null;
        WallpaperRenderer.writes = 0;
        WallpaperRenderer.failAfterInstall = false;
        WallpaperRenderer.onRender = null;
        HourlyScheduler.calls = 0;
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("kanji-controller-");
        try {
            reset(directory);
            refresh();
            require(WallpaperRenderer.writes == 1, "first write");
            require(context.prefs.getInt("lock_last_wallpaper_id", 0) == 1001, "remember installed ID");
            require(!context.prefs.getBoolean("lock_write_pending", true), "journal committed");
            require(WallpaperRenderer.last.recycled, "bitmap released");
            refresh();
            require(WallpaperRenderer.writes == 1, "own broadcast does not loop");
            CurrentWallpaperImporter.currentId = 20;
            refresh();
            require(WallpaperRenderer.writes == 2, "manual change within same hour is applied");
            LockWallpaperController.refresh(context, KANJI, 11, false);
            require(WallpaperRenderer.writes == 3, "next hour");
            LockWallpaperController.disableAndRemove(context);
            require(!context.prefs.getBoolean("lock_enabled", true), "stopped");
            require(WallpaperRenderer.last.glyph == null, "restore clean source only for owned wallpaper");
            require(!context.prefs.getBoolean("lock_has_glyph", true), "remember removal");
            require(HourlyScheduler.calls == 1, "cancel schedule immediately on disable");

            reset(directory);
            refresh();
            CurrentWallpaperImporter.currentId = 30;
            LockWallpaperController.disableAndRemove(context);
            require(WallpaperRenderer.writes == 1, "disable must preserve external replacement");

            reset(directory);
            CurrentWallpaperImporter.unavailable = true;
            fails(LockWallpaperControllerTest::refresh);
            require(WallpaperRenderer.writes == 0 && CurrentWallpaperImporter.reads == 0, "unknown/live never written");
            require(context.prefs.getBoolean("lock_enabled", false), "temporary failure must not disable retry");
            CurrentWallpaperImporter.unavailable = false;
            CurrentWallpaperImporter.denied = true;
            fails(LockWallpaperControllerTest::refresh);
            require(WallpaperRenderer.writes == 0, "no old background on access denial");
            CurrentWallpaperImporter.denied = false;
            refresh();
            require(WallpaperRenderer.writes == 1, "recover after grant");

            reset(directory);
            WallpaperRenderer.onRender = () -> CurrentWallpaperImporter.currentId = 40;
            fails(LockWallpaperControllerTest::refresh);
            require(WallpaperRenderer.writes == 0 && WallpaperRenderer.last.recycled, "render race leaves new background alone");

            reset(directory);
            CurrentWallpaperImporter.onCheck = () -> {
                if (CurrentWallpaperImporter.checks == 2) CurrentWallpaperImporter.currentId = 50;
            };
            fails(LockWallpaperControllerTest::refresh);
            require(WallpaperRenderer.writes == 0, "recheck immediately before write");
            require(!context.prefs.getBoolean("lock_write_pending", true), "known pre-write change is not uncertain");
            CurrentWallpaperImporter.onCheck = null;
            refresh();
            require(WallpaperRenderer.writes == 1, "automatically retry new background after pre-write race");

            reset(directory);
            context.prefs.failAt = context.prefs.commits + 1;
            fails(LockWallpaperControllerTest::refresh);
            require(WallpaperRenderer.writes == 0, "failed journal persistence prevents system write");

            reset(directory);
            WallpaperRenderer.failAfterInstall = true;
            fails(LockWallpaperControllerTest::refresh);
            require(context.prefs.getBoolean("lock_write_pending", false), "uncertain write recorded");
            WallpaperRenderer.failAfterInstall = false;
            fails(LockWallpaperControllerTest::refresh);
            fails(LockWallpaperControllerTest::refresh);
            require(WallpaperRenderer.writes == 1, "do not re-import a possibly burnt-in glyph");
            CurrentWallpaperImporter.currentId = 60;
            refresh();
            require(WallpaperRenderer.writes == 2, "recover after a fresh external wallpaper");

            reset(directory);
            Files.delete(Config.sourceFile(context).toPath());
            fails(LockWallpaperControllerTest::refresh);
            require(WallpaperRenderer.writes == 0, "missing source never selects a generated background");
            context.prefs.edit().putBoolean("lock_enabled", false).commit();
            refresh();
            require(WallpaperRenderer.writes == 0, "disabled stays disabled");
            System.out.println("LockWallpaperControllerTest: " + checks + " checks passed");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
