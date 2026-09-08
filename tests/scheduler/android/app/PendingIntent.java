package android.app;
import android.content.Context;
import android.content.Intent;
public final class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT = 1, FLAG_IMMUTABLE = 2;
    private static final PendingIntent ALARM = new PendingIntent();
    public static PendingIntent getBroadcast(Context context, int request, Intent intent, int flags) {
        return ALARM;
    }
}
