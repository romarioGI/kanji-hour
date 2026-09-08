package ru.romariogi.kanjihour;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/** A best-effort change signal while this process exists, never a keep-alive service. */
public final class KanjiApplication extends Application {
    @SuppressWarnings("deprecation")
    @Override public void onCreate() {
        super.onCreate();
        try {
            registerReceiver(new RefreshReceiver(), new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), RECEIVER_NOT_EXPORTED);
        } catch (RuntimeException error) {
            Log.w("KanjiHour", "Wallpaper change signal unavailable; periodic checks remain active", error);
        }
    }
}
