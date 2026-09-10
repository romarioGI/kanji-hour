import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"


class WidgetLayoutTest(unittest.TestCase):
    def test_glyph_keeps_font_padding(self):
        layout = ET.parse(
            ROOT / "app/src/main/res/layout/kanji_widget.xml"
        ).getroot()
        glyph = next(
            view
            for view in layout.iter("TextView")
            if view.attrib.get(ANDROID + "id") == "@+id/widget_glyph"
        )

        self.assertEqual(
            glyph.attrib.get(ANDROID + "includeFontPadding"),
            "true",
            "The large widget glyph needs the font's top/bottom metrics so OEM "
            "CJK fonts are not clipped.",
        )


if __name__ == "__main__":
    unittest.main()
