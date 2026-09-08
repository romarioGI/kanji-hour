package android.content;

/** Test-only subset; never included in app builds. */
public interface SharedPreferences {
    boolean getBoolean(String key, boolean fallback);
    int getInt(String key, int fallback);
    long getLong(String key, long fallback);
    float getFloat(String key, float fallback);
    String getString(String key, String fallback);
    Editor edit();
    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putString(String key, String value);
        Editor remove(String key);
        void apply();
        boolean commit();
    }
}
