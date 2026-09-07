package ru.romariogi.kanjihour;

/** One compact learning card; readings are kana and meaning is Russian. */
public final class Kanji {
    public final String glyph;
    public final String on;
    public final String kun;
    public final String meaning;

    public Kanji(String glyph, String on, String kun, String meaning) {
        this.glyph = glyph;
        this.on = on == null || on.isEmpty() ? "—" : on;
        this.kun = kun == null || kun.isEmpty() ? "—" : kun;
        this.meaning = meaning;
    }
}
