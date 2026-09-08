package ru.romariogi.kanjihour;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;

/** Scheduler/coordinator are production; surfaces and Android services are doubles. */
final class RecoveryJobService { }
final class RefreshReceiver { }
final class Kanji {
    final long hour;
    Kanji(long time) { hour = Math.floorDiv(time, HourlyScheduler.HOUR_MILLIS); }
}
final class KanjiRepository {
    static Kanji getAtTime(Context context, long time) { return new Kanji(time); }
}
final class KanjiWidgetProvider {
    static boolean unavailable;
    static int count;
    static int[] widgetIds(Context context) {
        if (unavailable) throw new IllegalStateException("launcher unavailable");
        return new int[count];
    }
    static void updateAll(Context context, Kanji kanji) { }
}
final class LockWallpaperController {
    static int writes;
    static void persist(SharedPreferences.Editor editor) throws IOException {
        if (!editor.commit()) throw new IOException("write failed");
    }
    static void refresh(Context context, Kanji kanji, long hour, boolean force,
                        RefreshCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        if (Config.prefs(context).getBoolean("lock_enabled", false)) writes++;
    }
    static void disableAndRemove(Context context) throws IOException {
        persist(Config.prefs(context).edit().putBoolean("lock_enabled", false));
        HourlyScheduler.schedule(context);
    }
}
