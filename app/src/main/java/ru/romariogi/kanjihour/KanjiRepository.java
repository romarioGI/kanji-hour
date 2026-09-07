package ru.romariogi.kanjihour;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Offline asset loading and one shared card for all display surfaces. */
public final class KanjiRepository {
    private static final String PREFERENCES = "kanji_hour";
    private static final String SEED_KEY = "install_seed";
    private static final Kanji FALLBACK = new Kanji("山", "サン", "やま", "гора");
    private static List<Kanji> cached;

    private KanjiRepository() { }

    /** Loads and validates UTF-8 TSV data. Empty reading columns are allowed. */
    public static synchronized List<Kanji> load(Context context) throws IOException {
        if (cached != null) {
            return cached;
        }
        List<Kanji> entries = new ArrayList<>();
        Set<String> glyphs = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("kanji.tsv"), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header != null && header.startsWith("\uFEFF")) {
                header = header.substring(1);
            }
            if (!"glyph\ton\tkun\tmeaning".equals(header)) {
                throw new IOException("Unexpected kanji.tsv header");
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields.length != 4) {
                    throw new IOException("Expected four columns at line " + lineNumber);
                }
                for (int i = 0; i < fields.length; i++) {
                    fields[i] = fields[i].trim();
                }
                if (fields[0].codePointCount(0, fields[0].length()) != 1
                        || !glyphs.add(fields[0])
                        || (fields[1].isEmpty() && fields[2].isEmpty())
                        || fields[3].isEmpty()) {
                    throw new IOException("Invalid kanji card at line " + lineNumber);
                }
                entries.add(new Kanji(fields[0], fields[1], fields[2], fields[3]));
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("kanji.tsv has no cards");
        }
        cached = Collections.unmodifiableList(entries);
        return cached;
    }

    /** Returns a valid card even if an installation's asset cannot be read. */
    public static Kanji getCurrent(Context context) {
        return getAtTime(context, System.currentTimeMillis());
    }

    public static Kanji getAtTime(Context context, long timeMillis) {
        try {
            List<Kanji> entries = load(context);
            long hour = Math.floorDiv(timeMillis, 3_600_000L);
            int index = HourlySelection.indexForHour(hour, installSeed(context), entries.size());
            return entries.get(index);
        } catch (IOException exception) {
            return FALLBACK;
        }
    }

    private static synchronized long installSeed(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, 0);
        if (preferences.contains(SEED_KEY)) {
            return preferences.getLong(SEED_KEY, 0L);
        }
        long seed = new Random().nextLong();
        // Synchronous persistence also covers a receiver process ending immediately.
        preferences.edit().putLong(SEED_KEY, seed).commit();
        return seed;
    }
}
