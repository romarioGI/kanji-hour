package android.content;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class Context {
    public static final int MODE_PRIVATE = 0;
    public final Memory prefs = new Memory();
    private final File directory;
    public Context(File directory) { this.directory = directory; }
    public Context getApplicationContext() { return this; }
    public File getFilesDir() { return directory; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    public static final class Memory implements SharedPreferences {
        private final Map<String, Object> values = java.util.Collections.synchronizedMap(new HashMap<>());
        public int commits, failAt = -1;
        public boolean getBoolean(String key, boolean value) { return (Boolean) values.getOrDefault(key, value); }
        public int getInt(String key, int value) { return (Integer) values.getOrDefault(key, value); }
        public long getLong(String key, long value) { return (Long) values.getOrDefault(key, value); }
        public float getFloat(String key, float value) { return (Float) values.getOrDefault(key, value); }
        public String getString(String key, String value) { return (String) values.getOrDefault(key, value); }
        public Editor edit() { return new Change(); }
        private final class Change implements Editor {
            private final Map<String, Object> changes = new HashMap<>();
            public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
            public Editor putInt(String key, int value) { changes.put(key, value); return this; }
            public Editor putLong(String key, long value) { changes.put(key, value); return this; }
            public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
            public Editor putString(String key, String value) { changes.put(key, value); return this; }
            public Editor remove(String key) { changes.put(key, null); return this; }
            public void apply() { commit(); }
            public boolean commit() {
                // Android updates memory even when disk persistence reports failure.
                changes.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                return ++commits != failAt;
            }
        }
    }
}
