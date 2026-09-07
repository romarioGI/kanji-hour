package ru.romariogi.kanjihour;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Canvas dimensions and default placement are tailored to POCO X5 Pro (1080×2400). */
public final class WallpaperRenderer {
    private WallpaperRenderer() {}

    /** Decodes EXIF orientation with ImageDecoder; stores a bounded, app-private background. */
    public static void importPhoto(Context context, Uri uri) throws IOException {
        Bitmap input = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri),
            (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float scale = Math.min(1f, 2800f / Math.max(w, h));
                decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
            });
        Bitmap output = Bitmap.createBitmap(Config.WALL_WIDTH, Config.WALL_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(27, 52, 69));
        drawCenterCrop(canvas, input, Config.WALL_WIDTH, Config.WALL_HEIGHT);
        File temporary = new File(context.getFilesDir(), "lock-background.tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            if (!output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw new IOException("Не удалось сохранить фото");
            stream.getFD().sync();
        } finally {
            input.recycle();
            output.recycle();
        }
        if (!temporary.renameTo(Config.draftSourceFile(context))) {
            temporary.delete();
            throw new IOException("Не удалось заменить фото");
        }
    }

    public static Bitmap render(Context context, Kanji kanji, int width, float y, float scale) {
        return render(context, kanji, width, y, scale, Config.sourceFile(context));
    }

    public static Bitmap renderPreview(Context context, Kanji kanji, int width, float y, float scale, boolean solid) {
        File draft = Config.draftSourceFile(context);
        return render(context, kanji, width, y, scale, solid ? null : (draft.exists() ? draft : Config.sourceFile(context)));
    }

    public static void commitBackground(Context context, boolean solid) throws IOException {
        if (solid) {
            if (Config.sourceFile(context).exists() && !Config.sourceFile(context).delete())
                throw new IOException("Не удалось сменить фон");
            Config.draftSourceFile(context).delete();
        } else if (Config.draftSourceFile(context).exists() && !Config.draftSourceFile(context).renameTo(Config.sourceFile(context))) {
            throw new IOException("Не удалось сохранить выбранный фон");
        }
    }

    private static Bitmap render(Context context, Kanji kanji, int width, float y, float scale, File photo) {
        int height = Math.round(width * (Config.WALL_HEIGHT / (float) Config.WALL_WIDTH));
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (photo != null && photo.exists()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Config.WALL_WIDTH / width);
            Bitmap input = BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
            if (input != null) {
                drawCenterCrop(canvas, input, width, height);
                input.recycle();
            } else throw new IllegalStateException("Фото недоступно. Выберите его ещё раз.");
        } else {
            paint.setShader(new LinearGradient(0, 0, width * .6f, height,
                new int[]{0xFF6A9AC0, 0xFF315A7B, 0xFF182F48}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
        }
        if (kanji != null) drawKanji(canvas, kanji, width, height, y, scale);
        return output;
    }

    private static void drawCenterCrop(Canvas canvas, Bitmap input, int width, int height) {
        float scale = Math.max(width / (float) input.getWidth(), height / (float) input.getHeight());
        float w = input.getWidth() * scale, h = input.getHeight() * scale;
        canvas.drawBitmap(input, null, new RectF((width - w) / 2, (height - h) / 2,
            (width + w) / 2, (height + h) / 2), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
    }

    private static void drawKanji(Canvas canvas, Kanji kanji, int width, int height, float y, float scale) {
        float unit = width / 1080f;
        float size = unit * Math.max(.75f, Math.min(1.25f, scale));
        float x = 72f * unit, center = Math.max(.15f, Math.min(.8f, y)) * height;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextLocale(Locale.JAPANESE);
        paint.setShadowLayer(3f * unit, 0, 1.2f * unit, 0x66000000);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(138f * size);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText(kanji.glyph, x, center - (metrics.ascent + metrics.descent) / 2, paint);
        float textX = x + 179f * size;
        float usable = width - 64f * unit - textX;
        fitText(canvas, "ОН  " + reading(kanji.on), textX, center - 38f * size, 29f * size, usable, paint);
        fitText(canvas, "КУН  " + reading(kanji.kun), textX, center + 4f * size, 29f * size, usable, paint);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fitText(canvas, kanji.meaning, textX, center + 48f * size, 34f * size, usable, paint);
    }

    private static String reading(String value) { return value == null || value.isEmpty() ? "—" : value; }

    private static void fitText(Canvas canvas, String text, float x, float baseline, float textSize, float width, Paint paint) {
        paint.setTextSize(textSize);
        float measured = paint.measureText(text);
        if (measured > width) paint.setTextSize(textSize * width / measured);
        canvas.drawText(text, x, baseline, paint);
    }

    public static int apply(Context context, Kanji kanji) throws IOException {
        Bitmap bitmap = render(context, kanji, Config.WALL_WIDTH,
            Config.prefs(context).getFloat("lock_y", Config.DEFAULT_Y),
            Config.prefs(context).getFloat("lock_scale", 1f));
        return setLockBitmap(context, bitmap);
    }

    public static int applyPreview(Context context, Kanji kanji, float y, float scale, boolean solid) throws IOException {
        return setLockBitmap(context, renderPreview(context, kanji, Config.WALL_WIDTH, y, scale, solid));
    }

    private static int setLockBitmap(Context context, Bitmap bitmap) throws IOException {
        WallpaperManager manager = WallpaperManager.getInstance(context);
        try {
            if (!manager.isWallpaperSupported() || !manager.isSetWallpaperAllowed())
                throw new IOException("Прошивка не разрешила смену обоев");
            int id = manager.setBitmap(bitmap, new Rect(0, 0, Config.WALL_WIDTH, Config.WALL_HEIGHT),
                false, WallpaperManager.FLAG_LOCK);
            if (id <= 0) throw new IOException("Система не подтвердила применение обоев");
            return id;
        } finally { bitmap.recycle(); }
    }
}
