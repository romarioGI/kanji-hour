package ru.romariogi.kanjihour;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

public final class Config {
    public static final int WALL_WIDTH = 1080;
    public static final int WALL_HEIGHT = 2400;
    public static final float DEFAULT_Y = .382f;
    private Config() {}
    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("kanji_hour", Context.MODE_PRIVATE);
    }
    public static File sourceFile(Context context) {
        String name = prefs(context).getString("lock_source_name", "lock-background.jpg");
        // Only app-created basenames; the fallback is the clean source from version 0.2.
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains(".."))
            throw new IllegalStateException("Некорректный путь исходного фона.");
        return new File(context.getFilesDir(), name);
    }
}
