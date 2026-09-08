package ru.romariogi.kanjihour;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Renders from a verified clean source. No photo picker or generated fallback. */
public final class WallpaperRenderer {
    private WallpaperRenderer() {}

    public static Bitmap render(File source, Kanji kanji, int width, float y, float scale) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Чистый исходник обоев повреждён.");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = WallpaperImportPolicy.sampleSize(bounds.outWidth, bounds.outHeight,
                Math.max(width, Math.round(width * Config.WALL_HEIGHT / (float) Config.WALL_WIDTH)));
        Bitmap input = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (input == null) throw new IOException("Чистый исходник обоев недоступен.");
        Bitmap output = null;
        try {
            int height = Math.round(width * Config.WALL_HEIGHT / (float) Config.WALL_WIDTH);
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            float crop = Math.max(width / (float) input.getWidth(), height / (float) input.getHeight());
            float w = input.getWidth() * crop, h = input.getHeight() * crop;
            canvas.drawBitmap(input, null, new RectF((width - w) / 2, (height - h) / 2,
                    (width + w) / 2, (height + h) / 2), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            if (kanji != null) drawKanji(canvas, kanji, width, height, y, scale);
            return output;
        } catch (RuntimeException | Error failure) {
            if (output != null) output.recycle();
            throw failure;
        } finally { input.recycle(); }
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
        float textX = x + 179f * size, usable = width - 64f * unit - textX;
        fitText(canvas, "ОН  " + reading(kanji.on), textX, center - 38f * size, 29f * size, usable, paint);
        fitText(canvas, "КУН  " + reading(kanji.kun), textX, center + 4f * size, 29f * size, usable, paint);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fitText(canvas, kanji.meaning, textX, center + 48f * size, 34f * size, usable, paint);
    }

    private static String reading(String value) { return value == null || value.isEmpty() ? "—" : value; }
    private static void fitText(Canvas canvas, String text, float x, float baseline, float size, float width, Paint paint) {
        paint.setTextSize(size);
        float measured = paint.measureText(text);
        if (measured > width) paint.setTextSize(size * width / measured);
        canvas.drawText(text, x, baseline, paint);
    }

    /** Ownership checks and write journal are the caller's responsibility. */
    static int setLockBitmap(Context context, Bitmap bitmap) throws IOException {
        WallpaperManager manager = WallpaperManager.getInstance(context);
        if (!manager.isWallpaperSupported() || !manager.isSetWallpaperAllowed())
            throw new IOException("Прошивка не разрешила смену обоев.");
        int id = manager.setBitmap(bitmap, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                false, WallpaperManager.FLAG_LOCK);
        if (id <= 0) throw new IOException("Система не подтвердила применение обоев.");
        return id;
    }
}
