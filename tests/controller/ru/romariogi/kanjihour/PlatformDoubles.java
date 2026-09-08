package ru.romariogi.kanjihour;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;
import java.io.IOException;

/** Deterministic platform failures around the actual production controller. */
final class Kanji { }
final class HourlyScheduler {
    static int calls;
    static void schedule(Context context) { calls++; }
}
final class CurrentWallpaperImporter {
    static int currentId, reads, checks;
    static boolean unavailable, denied;
    static Runnable onCheck;
    static final class Snapshot {
        final int lockId, systemId = -1;
        Snapshot(int id) { lockId = id; }
        String key() { return "lock:" + lockId; }
    }
    static final class Source implements AutoCloseable {
        final Snapshot snapshot;
        final File file;
        Source(Snapshot snapshot, File file) { this.snapshot = snapshot; this.file = file; }
        void retain() { }
        public void close() { }
    }
    static Snapshot inspect(Context context) throws IOException {
        if (unavailable) throw new IOException("unknown or live wallpaper");
        return new Snapshot(currentId);
    }
    static Source read(Context context, Snapshot snapshot) throws IOException {
        reads++;
        if (denied) throw new IOException("permission denied");
        File source = Config.sourceFile(context);
        if (!source.isFile()) throw new IOException("missing clean source");
        return new Source(snapshot, source);
    }
    static void ensureCurrent(Context context, Snapshot snapshot) throws IOException {
        checks++;
        if (onCheck != null) onCheck.run();
        if (unavailable || snapshot.lockId != currentId) throw new IOException("changed while rendering");
    }
}
final class WallpaperRenderer {
    static int writes;
    static boolean failAfterInstall;
    static Bitmap last;
    static Runnable onRender;
    static Bitmap render(File file, Kanji kanji, int width, float y, float scale) {
        if (onRender != null) onRender.run();
        last = new Bitmap(kanji);
        return last;
    }
    static int setLockBitmap(Context context, Bitmap bitmap) throws IOException {
        writes++;
        CurrentWallpaperImporter.currentId = 1000 + writes;
        if (failAfterInstall) throw new IOException("interrupted after system write");
        return CurrentWallpaperImporter.currentId;
    }
}
