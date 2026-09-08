package android.app;
/** Same-PendingIntent replacement only, not actual Android scheduling or Doze. */
public final class AlarmManager {
    public static final int RTC_WAKEUP = 0;
    public long next;
    public int sets, cancels;
    public boolean exact, revokedDuringSet, installedExact;
    public boolean canScheduleExactAlarms() { return exact; }
    public void setExactAndAllowWhileIdle(int type, long when, PendingIntent intent) {
        if (revokedDuringSet) throw new SecurityException("permission revoked");
        next = when; installedExact = true; sets++;
    }
    public void setAndAllowWhileIdle(int type, long when, PendingIntent intent) {
        next = when; installedExact = false; sets++;
    }
    public void cancel(PendingIntent intent) { next = 0; cancels++; }
}
