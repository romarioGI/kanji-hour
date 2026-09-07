package ru.romariogi.kanjihour;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Imports through public Android 14 APIs into a draft; never applies or enables wallpaper. */
public final class CurrentWallpaperImporter {
    private static final int MAX_DECODE_DIMENSION = 2800;
    private static final long MAX_OWN_SOURCE_BYTES = 64L * 1024 * 1024;

    private CurrentWallpaperImporter() {}

    public static final class Result {
        public final boolean solid;
        public final String message;

        private Result(boolean solid, String message) {
            this.solid = solid;
            this.message = message;
        }
    }

    public static final class PermissionRequiredException extends IOException {
        public PermissionRequiredException() {
            super("Для чтения текущих обоев разрешите «Доступ ко всем файлам» и повторите импорт.");
        }
    }

    public static boolean hasPermission() {
        return Environment.isExternalStorageManager();
    }

    /** Own clean backgrounds are app-private and can be reused without broad file access. */
    public static boolean needsPermission(Context context) {
        if (hasPermission()) return false;
        try {
            int lockId = WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_LOCK);
            int ownId = Config.prefs(context).getInt("lock_last_wallpaper_id", -1);
            return !WallpaperImportPolicy.isOwnWallpaper(lockId, ownId);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /** Call on UpdateCoordinator's worker, serialized with our wallpaper updates. */
    public static Result importCurrent(Context context) throws IOException {
        try {
            return importChecked(context);
        } catch (SecurityException e) {
            if (!hasPermission()) throw new PermissionRequiredException();
            throw new IOException("HyperOS не разрешила прочитать текущие обои. Выберите исходное фото вручную.", e);
        } catch (RuntimeException e) {
            throw new IOException("Не удалось прочитать текущие обои. Попробуйте ещё раз или выберите фото вручную.", e);
        }
    }

    private static Result importChecked(Context context) throws IOException {
        WallpaperManager manager = WallpaperManager.getInstance(context);
        if (!manager.isWallpaperSupported()) {
            throw new IOException("Эта прошивка не предоставляет доступ к обоям.");
        }
        int lockId = manager.getWallpaperId(WallpaperManager.FLAG_LOCK);
        int ownId = Config.prefs(context).getInt("lock_last_wallpaper_id", -1);
        File ownSource = Config.sourceFile(context);
        // Do not re-import an image with our glyph already burned into it.
        if (WallpaperImportPolicy.isOwnWallpaper(lockId, ownId)) {
            if (!ownSource.exists()) {
                ensureUnchanged(manager, lockId, -1);
                File draft = Config.draftSourceFile(context);
                if (draft.exists() && !draft.delete()) {
                    throw new IOException("Не удалось выбрать однотонный фон для предпросмотра.");
                }
                return new Result(true, "Выбран исходный однотонный фон приложения, без старого кандзи.");
            }
            File temporary = File.createTempFile("wallpaper-import-", ".jpg", context.getFilesDir());
            try {
                copyOwnSource(ownSource, temporary);
                ensureUnchanged(manager, lockId, -1);
                replaceDraft(context, temporary);
                return new Result(false, "Подтянут сохранённый исходный фон, без старого кандзи.");
            } finally {
                temporary.delete();
            }
        }

        if (!hasPermission()) throw new PermissionRequiredException();
        boolean lockLive = manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK) != null;
        int systemId = lockId < 0 ? manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) : -1;
        boolean systemLive = lockId < 0 && manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM) != null;
        WallpaperImportPolicy.Route route = WallpaperImportPolicy.select(lockId, systemId, ownId,
            ownSource.exists(), lockLive, systemLive);
        if (route == WallpaperImportPolicy.Route.LIVE) {
            throw new IOException("На экране блокировки используются живые обои. Выберите обычное исходное фото вручную.");
        }
        if (route != WallpaperImportPolicy.Route.LOCK_IMAGE
                && route != WallpaperImportPolicy.Route.SHARED_IMAGE) {
            throw new IOException("Система не смогла определить текущие обои. Выберите исходное фото вручную.");
        }
        ensureUnchanged(manager, lockId, systemId);
        int which = route == WallpaperImportPolicy.Route.LOCK_IMAGE
            ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
        File temporary = File.createTempFile("wallpaper-import-", ".jpg", context.getFilesDir());
        try {
            // Do not call getDrawable/getBitmap: their platform defaults can hide access failures.
            try (ParcelFileDescriptor descriptor = manager.getWallpaperFile(which)) {
                if (descriptor == null) {
                    if (!hasPermission()) throw new PermissionRequiredException();
                    throw new IOException("HyperOS не выдала файл текущих обоев. Выберите исходное фото вручную.");
                }
                writeWallpaper(descriptor, temporary);
            }
            // An external wallpaper change during decoding must not replace an existing draft.
            ensureUnchanged(manager, lockId, systemId);
            replaceDraft(context, temporary);
            return new Result(false, route == WallpaperImportPolicy.Route.SHARED_IMAGE
                ? "Подтянут общий фон главного экрана и блокировки. Проверьте кадрирование в предпросмотре."
                : "Обои экрана блокировки подтянуты. Проверьте кадрирование в предпросмотре.");
        } finally {
            temporary.delete();
        }
    }

    private static void ensureUnchanged(WallpaperManager manager, int lockId, int systemId) throws IOException {
        int nowLockId = manager.getWallpaperId(WallpaperManager.FLAG_LOCK);
        int nowSystemId = lockId < 0 ? manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) : -1;
        if (!WallpaperImportPolicy.unchanged(lockId, systemId, nowLockId, nowSystemId)) {
            throw new IOException("Обои изменились во время импорта. Нажмите «Взять текущие обои блокировки» ещё раз.");
        }
    }

    private static void copyOwnSource(File source, File target) throws IOException {
        long size = source.length();
        if (!source.isFile() || size <= 0 || size > MAX_OWN_SOURCE_BYTES) {
            throw new IOException("Сохранённый исходный фон повреждён. Выберите исходное фото заново.");
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            long copied = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > MAX_OWN_SOURCE_BYTES) throw new IOException("Исходный фон слишком большой.");
                output.write(buffer, 0, count);
            }
            if (copied != size) throw new IOException("Исходный фон изменился во время импорта. Повторите попытку.");
            output.getFD().sync();
        }
    }

    private static void writeWallpaper(ParcelFileDescriptor descriptor, File target) throws IOException {
        Bitmap input = null;
        Bitmap output = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            // decodeFileDescriptor preserves the descriptor offset for the second decode.
            BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("Файл текущих обоев не удалось прочитать как изображение.");
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = WallpaperImportPolicy.sampleSize(bounds.outWidth, bounds.outHeight,
                MAX_DECODE_DIMENSION);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            input = BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
            if (input == null) throw new IOException("Не удалось декодировать текущие обои.");
            output = Bitmap.createBitmap(Config.WALL_WIDTH, Config.WALL_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.rgb(27, 52, 69));
            float scale = Math.max(Config.WALL_WIDTH / (float) input.getWidth(),
                Config.WALL_HEIGHT / (float) input.getHeight());
            float width = input.getWidth() * scale;
            float height = input.getHeight() * scale;
            RectF destination = new RectF((Config.WALL_WIDTH - width) / 2f,
                (Config.WALL_HEIGHT - height) / 2f, (Config.WALL_WIDTH + width) / 2f,
                (Config.WALL_HEIGHT + height) / 2f);
            canvas.drawBitmap(input, null, destination, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            try (FileOutputStream stream = new FileOutputStream(target)) {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw new IOException("Не удалось сохранить текущие обои.");
                }
                stream.getFD().sync();
            }
        } finally {
            if (input != null) input.recycle();
            if (output != null) output.recycle();
        }
    }

    private static void replaceDraft(Context context, File temporary) throws IOException {
        // Both files are in filesDir, so rename is atomic on Android's local filesystem.
        if (!temporary.renameTo(Config.draftSourceFile(context))) {
            throw new IOException("Не удалось сохранить фон для предпросмотра.");
        }
    }
}
