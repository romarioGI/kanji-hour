package ru.romariogi.kanjihour;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Reads only the current static lock background; never substitutes a stock image. */
public final class CurrentWallpaperImporter {
    private CurrentWallpaperImporter() {}

    public static final class Snapshot {
        public final int lockId, systemId;
        Snapshot(int lockId, int systemId) { this.lockId = lockId; this.systemId = systemId; }
        public String key() { return lockId > 0 ? "lock:" + lockId : "shared:" + systemId; }
    }

    public static final class Source implements AutoCloseable {
        public final Snapshot snapshot;
        public final File file;
        private final boolean temporary;
        private boolean retained;
        Source(Snapshot snapshot, File file, boolean temporary) {
            this.snapshot = snapshot; this.file = file; this.temporary = temporary;
        }
        void retain() { retained = true; }
        @Override public void close() { if (temporary && !retained) file.delete(); }
    }

    public static boolean hasPermission() { return Environment.isExternalStorageManager(); }

    public static Snapshot inspect(Context context) throws IOException {
        try {
            WallpaperManager manager = WallpaperManager.getInstance(context);
            if (!manager.isWallpaperSupported()) throw new IOException("Прошивка не предоставляет доступ к обоям.");
            int lockId = manager.getWallpaperId(WallpaperManager.FLAG_LOCK);
            int systemId = lockId < 0 ? manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) : -1;
            boolean lockLive = manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK) != null;
            boolean systemLive = lockId < 0 && manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM) != null;
            WallpaperImportPolicy.Route route = WallpaperImportPolicy.select(lockId, systemId, -1, false, lockLive, systemLive);
            if (route == WallpaperImportPolicy.Route.LIVE)
                throw new IOException("Живые обои не изменены. Для кандзи на блокировке нужен статичный фон.");
            if (route == WallpaperImportPolicy.Route.UNAVAILABLE)
                throw new IOException("Не удалось определить текущий фон. Обои не изменены.");
            return new Snapshot(lockId, systemId);
        } catch (RuntimeException error) {
            throw new IOException("Не удалось проверить текущие обои. Обои не изменены.", error);
        }
    }

    public static void ensureCurrent(Context context, Snapshot expected) throws IOException {
        Snapshot current = inspect(context);
        if (!WallpaperImportPolicy.unchanged(expected.lockId, expected.systemId, current.lockId, current.systemId))
            throw new IOException("Фон изменился во время обработки. Новые обои будут проверены при следующем обновлении.");
    }

    /** Caller serializes this with wallpaper writes and closes the result. */
    public static Source read(Context context, Snapshot snapshot) throws IOException {
        SharedPreferences prefs = Config.prefs(context);
        boolean own = WallpaperImportPolicy.isOwnWallpaper(snapshot.lockId, prefs.getInt("lock_last_wallpaper_id", 0));
        File saved = Config.sourceFile(context);
        if (own || snapshot.key().equals(prefs.getString("lock_source_key", ""))) {
            if (!saved.isFile() || saved.length() == 0)
                throw new IOException("Чистый исходник обоев недоступен. Установите фон заново в настройках Android.");
            ensureCurrent(context, snapshot);
            return new Source(snapshot, saved, false);
        }
        if (!hasPermission()) throw permissionError();
        File file = File.createTempFile("lock-source-", ".png", context.getFilesDir());
        boolean success = false;
        try {
            int which = snapshot.lockId > 0 ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
            try (ParcelFileDescriptor descriptor = WallpaperManager.getInstance(context).getWallpaperFile(which)) {
                if (descriptor == null) throw new IOException("Android не выдал текущие обои. Старый фон не применяется.");
                writeSource(descriptor, file);
            }
            ensureCurrent(context, snapshot);
            success = true;
            return new Source(snapshot, file, true);
        } catch (SecurityException error) {
            if (!hasPermission()) throw permissionError();
            throw new IOException("Прошивка запретила чтение обоев. Старый фон не применяется.", error);
        } finally {
            if (!success) file.delete();
        }
    }

    private static IOException permissionError() {
        return new IOException("Для чтения новых обоев разрешите «Доступ ко всем файлам» в настройках приложения.");
    }

    private static void writeSource(ParcelFileDescriptor descriptor, File target) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Не удалось прочитать изображение обоев.");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = WallpaperImportPolicy.sampleSize(bounds.outWidth, bounds.outHeight, 2800);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
        if (bitmap == null) throw new IOException("Не удалось декодировать обои.");
        try (FileOutputStream stream = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Не удалось сохранить чистый фон.");
            stream.getFD().sync();
        } finally { bitmap.recycle(); }
    }
}
