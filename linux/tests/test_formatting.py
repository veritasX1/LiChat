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

    def test_preview_hides_spoiler(self):
        self.assertEqual(preview_text("Täter: ||Barney|| *sicher*"), "Täter: ••• sicher")


if __name__ == "__main__":
    unittest.main()
