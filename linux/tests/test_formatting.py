"""Card bcb40e06 (f005–f011): formatting.py gives for every case what docs/formatierung-faelle.json says – Android's
core/Format.kt is checked against the same file (FormatTest)."""

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "linux"))

from lichat.formatting import formatted, preview_text  # noqa: E402


class SameAsAndroid(unittest.TestCase):
    def test_cases(self):
        for case in json.loads((ROOT / "docs" / "formatierung-faelle.json").read_text())["faelle"]:
            with self.subTest(case["ein"]):
                plain, spans = formatted(case["ein"])
                self.assertEqual(plain, case["text"])
                self.assertEqual([tuple(s) for s in spans], sorted(tuple(s) for s in case["spans"]))

    def test_emoji_only(self):
        """f015: only emoji (1–3 shown big) – sequences count once, any letter makes it ordinary text."""
        from lichat.account import emoji_only
        cases = {"😀": 1, "😀 👍🏽": 2, "👩‍👩‍👧‍👦🇫🇷1️⃣": 3, "❤️": 1, "😀😀😀😀": 4, "hi 😀": 0, "*😀*": 0, "": 0, "2": 0}
        for text, count in cases.items():
            self.assertEqual(emoji_only(text), count, text)

    def test_sums(self):
        """f028: the same results as Android's CalcTest – docs/rechnen-faelle.json."""
        from lichat.calc import suggestion
        for case in json.loads((ROOT / "docs" / "rechnen-faelle.json").read_text())["faelle"]:
            self.assertEqual(suggestion(case["ein"]), case["aus"], case["ein"])

    def test_preview_hides_spoiler(self):
        self.assertEqual(preview_text("Täter: ||Barney|| *sicher*"), "Täter: ••• sicher")


if __name__ == "__main__":
    unittest.main()
