package android.graphics;
public final class Bitmap {
    public final Object glyph;
    public boolean recycled;
    public Bitmap(Object glyph) { this.glyph = glyph; }
    public void recycle() { recycled = true; }
}
