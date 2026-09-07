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
        return new File(context.getFilesDir(), "lock-background.jpg");
    }
    public static File draftSourceFile(Context context) {
        return new File(context.getFilesDir(), "lock-background-draft.jpg");
    }
}
