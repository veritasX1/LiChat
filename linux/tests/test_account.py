"""lichat-cli / account.py against a real LiChat server in this process (isolated folders, port chosen by the system).

    cd linux && python3 -m unittest discover -s tests
"""

import io
import json
import os
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from socketserver import ThreadingMixIn
from wsgiref.simple_server import WSGIRequestHandler, WSGIServer, make_server

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "linux"))
sys.path.insert(0, str(ROOT / "server"))

from lichat import account as account_module  # noqa: E402
from lichat.account import Account, LiChatError  # noqa: E402
from lichat_server.app import create_app  # noqa: E402


class Threading(ThreadingMixIn, WSGIServer):
    daemon_threads = True   # a waiting fetch must not hold up the others


class Quiet(WSGIRequestHandler):
    def log_message(self, *args):
        pass


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="lichat-test-")
        self.root = Path(self.tmp.name)
        self.app = create_app(str(self.root / "server"))
        self.httpd = make_server("127.0.0.1", 0, self.app, handler_class=Quiet, server_class=Threading)
        self.url = f"http://127.0.0.1:{self.httpd.server_port}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.tmp.cleanup()

    def person(self, name):
        return Account.register(self.root / "geraete" / name, self.url, self.app.store.create_invite(name), name)

    def reopen(self, account):
        """As after a restart: everything from disk."""
        return Account(account.folder)


class TwoPeople(Base):
    def test_hello_and_back_after_restart(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        self.assertEqual(bernd.fetch(), [{"chat": cid, "joined": True}])
        self.assertEqual(bernd.chats[cid]["roster"], {"anna": [anna.device_id], "bernd": [bernd.device_id]})
        anna.send(cid, "Hallo Bernd!")
        news = bernd.fetch()
        self.assertEqual([(n["from"], n["text"]) for n in news if "text" in n], [("anna", "Hallo Bernd!")])
        self.assertEqual(bernd.fetch(), [], "quittiert – nichts doppelt")

        # Both restart; the conversation goes on, the codes agree.
        anna, bernd = self.reopen(anna), self.reopen(bernd)
        bernd.send(cid, "Hallo Anna")
        self.assertEqual([(n["from"], n["text"]) for n in anna.fetch() if "text" in n], [("bernd", "Hallo Anna")])
        self.assertEqual(anna.safety_code(cid), bernd.safety_code(cid))
        self.assertEqual(len(anna.safety_code(cid).split()), 8)
        self.assertEqual(anna.members(cid), ["anna", "bernd"])
        self.assertEqual([m["text"] for m in anna.chats[cid]["messages"]], ["Hallo Bernd!", "Hallo Anna"])

    def test_nothing_readable_on_disk_or_server(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        anna.send(cid, "Geheimrezept Kartoffelsalat")
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn(b"Geheimrezept", server_files)
        self.assertNotIn(b"Kartoffelsalat", server_files)
        bernd.fetch()
        for account in (anna, bernd):
            for name in ("state.bin", "chats.bin", "key", "account.json"):
                path = account.folder / name
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600, name)
                self.assertNotIn(b"Geheimrezept", path.read_bytes(), name)
                self.assertNotIn(b"bernd", path.read_bytes() if name in ("state.bin", "chats.bin") else b"", name)
            self.assertEqual(stat.S_IMODE(account.folder.stat().st_mode), 0o700)

    def test_waiting_fetch_brings_the_message_at_once(self):
        """The app's standing connection (Olaf 09.10.): bernd waits, anna writes – bernd has it within a second."""
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        got = {}

        def listen():
            started = time.monotonic()
            got["news"], got["seconds"] = bernd.fetch(wait=30), time.monotonic() - started

        listener = threading.Thread(target=listen)
        listener.start()
        time.sleep(0.5)
        anna.send(cid, "Bist du da?")
        listener.join(10)
        self.assertEqual([n["text"] for n in got["news"]], ["Bist du da?"])
        self.assertLess(got["seconds"], 2.5)

    def test_wrong_key_does_not_open(self):
        anna = self.person("anna")
        (anna.folder / "key").write_bytes(os.urandom(32))
        with self.assertRaises(Exception):
            Account(anna.folder)


class Groups(Base):
    def test_mentions(self):
        """Card d54175ed: „@name“ means members only, „@alle“ everyone; the receiver works it out from the text, a mention of me
        is flagged (a notification even when muted); in a chat of two there is nothing to flag."""
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        cid = anna.start(["bernd", "erna"], title="Grillabend")
        bernd.fetch(); erna.fetch()
        self.assertEqual(anna.mentioned(cid, "Hi @Bernd, @fremder und @erna."), ["bernd", "erna"])
        self.assertEqual(anna.mentioned(cid, "@alle bitte melden"), ["anna", "bernd", "erna"])
        self.assertEqual(anna.mentioned(cid, "mail@bernd.de"), [])
        self.assertEqual(anna.mention_candidates(cid, "e"), ["erna"])
        anna.send(cid, "@bernd bringst du Kohle?")
        self.assertTrue([n for n in bernd.fetch() if n.get("text")][0].get("mention"))
        self.assertFalse([n for n in erna.fetch() if n.get("text")][0].get("mention"))
        anna.send(cid, "@alle um 18 Uhr!")
        self.assertTrue([n for n in erna.fetch() if n.get("text")][0].get("mention"))
        two = anna.start(["bernd"])
        bernd.fetch()
        anna.send(two, "@bernd hallo")
        self.assertNotIn("mention", [n for n in bernd.fetch() if n.get("text")][0])

    def test_edit_and_unsend(self):
        """Card 4ceca163, like iOS: edit for 15 minutes and five times („Bearbeitet“ with the earlier versions), call back for
        two minutes (a grey line, quotes of it emptied) – end-to-end, only by the one who wrote it, windows on the sender's clock."""
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        anna.send(cid, "Treffen um 18 Uhr")
        mid = anna.chats[cid]["messages"][-1]["mid"]
        bernd.fetch()
        anna.edit(cid, mid, "Treffen um 19 Uhr")
        self.assertEqual([n["edited"] for n in bernd.fetch() if "edited" in n], [mid])
        theirs = bernd.chats[cid]["messages"][-1]
        self.assertEqual(theirs["text"], "Treffen um 19 Uhr")
        self.assertEqual([v["text"] for v in theirs["bearbeitet"]], ["Treffen um 18 Uhr"])
        for i in range(4):
            anna.edit(cid, mid, f"Treffen um {20 + i} Uhr")
        with self.assertRaises(LiChatError):
            anna.edit(cid, mid, "noch einmal")              # sechstes Mal: nein
        bernd.fetch()
        self.assertEqual(bernd.chats[cid]["messages"][-1]["text"], "Treffen um 23 Uhr")
        # Bernd cannot edit Anna's message on Anna's devices (a forged edit is ignored)
        bernd._group_message(cid, {"t": "bearbeiten", "mid": mid, "text": "gefälscht", "at": int(time.time())})
        anna.fetch()
        self.assertEqual(anna.chats[cid]["messages"][-1]["text"], "Treffen um 23 Uhr")
        # Too late: a message older than 15 minutes (on the sender's clock) is not changed
        anna.send(cid, "alt")
        old = anna.chats[cid]["messages"][-1]
        old["at"] -= 17 * 60
        bernd.fetch()
        bernd.chats[cid]["messages"][-1]["at"] -= 17 * 60
        self.assertFalse(anna.can_edit(old))
        anna._group_message(cid, {"t": "bearbeiten", "mid": old["mid"], "text": "neu", "at": int(time.time())})
        bernd.fetch()
        self.assertEqual(bernd.chats[cid]["messages"][-1]["text"], "alt")
        # Call back within two minutes – the reply quoting it loses its quote too
        anna.send(cid, "Ups, falscher Chat")
        oops = anna.chats[cid]["messages"][-1]["mid"]
        bernd.fetch()
        bernd.send(cid, "Hä?", reply_to=oops)
        anna.fetch()
        anna.unsend(cid, oops)
        self.assertEqual([n["unsent"] for n in bernd.fetch() if "unsent" in n], [oops])
        gone = next(m for m in bernd.chats[cid]["messages"] if m.get("mid") == oops)
        self.assertTrue(gone["zurueckgerufen"])
        self.assertEqual(gone["text"], "")
        self.assertEqual(Account.describe(gone), "Nachricht widerrufen")
        self.assertTrue(bernd.chats[cid]["messages"][-1]["antwort"]["zurueckgerufen"])
        self.assertNotIn("falscher Chat", json.dumps(bernd.chats, ensure_ascii=False))
        self.assertNotIn("falscher Chat", json.dumps(anna.chats, ensure_ascii=False))

    def test_only_an_emoji_is_a_reaction(self):
        """Michelle 09.10.: „ab“, „x😀“, „Gelesen von allen“ must not appear under a bubble – refused when sending,
        dropped when receiving (a modified client could still send them)."""
        from lichat.account import single_emoji
        for good in ("❤️", "👍", "👍🏽", "👩‍👩‍👧‍👦", "🇫🇷", "1️⃣", "‼️", "❓", "🦄", "✅", "™️"):
            self.assertTrue(single_emoji(good), good)
        for bad in ("", "ab", "x😀", "😀x", "😀😀", "Gelesen von allen", "OK", "1", "🇫", " ", "❤️ ", "a\u200d😀"):
            self.assertFalse(single_emoji(bad), repr(bad))
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        anna.send(cid, "Hallo")
        bernd.fetch()
        mid = bernd.chats[cid]["messages"][-1]["mid"]
        with self.assertRaises(LiChatError):
            bernd.react(cid, mid, "Gelesen")
        bernd._group_message(cid, {"t": "reaktion", "mid": mid, "emoji": "Gelesen von allen"})
        anna.fetch()
        self.assertNotIn("reaktionen", anna.chats[cid]["messages"][-1])

    def test_any_emoji_as_reaction(self):
        """Olaf „+“: any emoji, also long ZWJ sequences, travels whole."""
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        anna.send(cid, "Neuer Hund!")
        bernd.fetch()
        mid = bernd.chats[cid]["messages"][-1]["mid"]
        bernd.react(cid, mid, "👩‍👩‍👧‍👦")
        anna.fetch()
        self.assertEqual(anna.chats[cid]["messages"][-1]["reaktionen"], {"bernd": "👩‍👩‍👧‍👦"})

    def test_typing_takes_as_long_as_the_text(self):
        """Olaf 09.10.: short quickly, long slowly, at most 3 minutes – and „…“ goes out on the way (both switched on)."""
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        anna.set_typing_indicator(True)
        bernd.set_typing_indicator(True)
        short_one = anna.type_like_a_person(cid, "ok", sleep=lambda s: None)
        long_one = anna.type_like_a_person(cid, "x" * 300, sleep=lambda s: None)
        too_long = anna.type_like_a_person(cid, "x" * 9000, sleep=lambda s: None)
        self.assertLess(short_one, 2)
        self.assertGreater(long_one, 70)
        self.assertLessEqual(too_long, 180 * 1.2)
        self.assertIn({"chat": cid, "typing": "anna"}, bernd.fetch())

    def test_send_before_the_roster_says_so_plainly(self):
        """Jean-Marie 09.10.: right after joining, the roster (devices) may not be there yet – no „400“, a clear word,
        the message marked „nicht gesendet“ and sent with a retry once the roster came."""
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        roster = bernd.chats[cid]["roster"]
        bernd.chats[cid]["roster"] = {}
        with self.assertRaises(LiChatError) as caught:
            bernd.send(cid, "Hallo!")
        self.assertIn("noch eingerichtet", str(caught.exception))
        mine = bernd.chats[cid]["messages"][-1]
        self.assertEqual(bernd.tick(cid, mine)[0], "fehler")
        bernd.chats[cid]["roster"] = roster
        bernd.retry(cid, mine["mid"])
        self.assertEqual([n["text"] for n in anna.fetch() if "text" in n], ["Hallo!"])

    def test_three_with_title(self):
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        cid = anna.start(["bernd", "erna"], title="Grillabend")
        for other in (bernd, erna):
            self.assertEqual(other.fetch(), [{"chat": cid, "joined": True}])
            self.assertEqual(other.title(cid), "Grillabend")
        erna.send(cid, "Ich bringe Salat mit")
        self.assertEqual([n["text"] for n in anna.fetch() if "text" in n], ["Ich bringe Salat mit"])
        self.assertEqual([n["text"] for n in bernd.fetch() if "text" in n], ["Ich bringe Salat mit"])
        self.assertEqual(sorted(erna.members(cid)), ["anna", "bernd", "erna"])
        self.assertTrue(anna.safety_code(cid) == bernd.safety_code(cid) == erna.safety_code(cid))


def test_image(path, size=(1200, 800), gps=True):
    """Our own test picture (never a real photo): a coloured surface, with camera and GPS data in its EXIF."""
    from PIL import Image
    image = Image.new("RGB", size, (200, 120, 40))
    exif = Image.Exif()
    exif[0x010F] = "Testkamera"          # Make
    if gps:
        exif[0x8825] = {1: "N", 2: (52.0, 31.0, 12.0), 3: "E", 4: (13.0, 24.0, 18.0)}   # GPSInfo: Berlin
    image.save(path, "JPEG", exif=exif.tobytes())
    return path


class Pictures(Base):
    def test_picture_travels_end_to_end_without_exif(self):
        from PIL import Image
        anna, bernd = self.person("anna"), self.person("bernd")
        source = test_image(self.root / "anna.jpg")
        self.assertIn(b"Testkamera", source.read_bytes())
        anna.set_picture(source)
        cid = anna.start(["bernd"])
        news = bernd.fetch()
        self.assertIn({"chat": cid, "picture": "anna"}, news)
        got = bernd.picture("anna")
        self.assertNotIn(b"Testkamera", got)
        with Image.open(io.BytesIO(got)) as image:
            self.assertEqual(image.size, (512, 512))
            self.assertEqual(len(image.getexif()), 0, "EXIF muss weg sein")
        # Nothing of the picture on the server or in the clear on disk.
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn(got[:64], server_files)
        self.assertNotIn(got[:64], (bernd.folder / "profile.bin").read_bytes())

        # Bernd joined – once he knows who is in the conversation, Anna gets his picture; then he removes it.
        bernd.set_picture(test_image(self.root / "bernd.jpg", size=(300, 900), gps=False))
        bernd.fetch()
        anna.fetch()
        self.assertIsNotNone(anna.picture("bernd"))
        bernd.set_picture(None)
        anna.fetch()
        self.assertIsNone(anna.picture("bernd"))
        self.assertIsNotNone(self.reopen(bernd).picture("anna"), "nach Neustart noch da")

    def test_joiner_sends_its_picture_after_the_roster(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        bernd.set_picture(test_image(self.root / "b.jpg"))
        anna.start(["bernd"])
        bernd.fetch()          # joins, learns the roster, sends its picture
        anna.fetch()
        self.assertIsNotNone(anna.picture("bernd"))

    def test_a_too_big_or_foreign_picture_is_not_kept(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        for fake in (b"\xff\xd8\xff" + b"x" * (160 * 1024), b"<svg onload=alert(1)>"):
            packet = anna.device.encrypt(bytes.fromhex(cid), json.dumps({"t": "profile", "bild": account_module.b64(fake)}).encode())
            anna.server.call("POST", "/v1/messages", {"to": [bernd.device_id], "blob": account_module.b64(b"M" + bytes(packet))})
        news = bernd.fetch()
        self.assertEqual(sum("Profilbild" in n.get("problem", "") for n in news), 2)
        self.assertIsNone(bernd.picture("anna"))


class Attachments(Base):
    """Card 9780f3a0: photos, videos, voice and files end to end – the server only ever sees encrypted bytes."""

    def test_photo_without_exif_and_file_both_ways(self):
        from PIL import Image
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        photo = test_image(self.root / "foto.jpg", size=(4000, 3000)).read_bytes()
        anna.send_attachment(cid, photo, "IMG_0001.JPG", "image/jpeg", "bild")
        news = bernd.fetch()
        self.assertEqual([n["text"] for n in news if "text" in n], ["Foto"])
        info = news[0]["anhang"]
        got = bernd.open_attachment(info)
        with Image.open(io.BytesIO(got)) as image:
            self.assertEqual(max(image.size), 2048)
            self.assertEqual(len(image.getexif()), 0, "EXIF (GPS, Kamera) muss weg sein")
        self.assertNotIn(b"Testkamera", got)
        self.assertTrue(info["vorschau"])
        # A file goes unchanged, back the other way; voice keeps its duration.
        document = os.urandom(200_000)
        bernd.send_attachment(cid, document, "Plan.pdf", "application/pdf", "datei")
        import subprocess
        voice = self.root / "stimme.m4a"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=300:duration=3", "-c:a", "aac",
                        "-metadata", "creation_time=2026-10-01T12:00:00Z", str(voice)], check=True)
        bernd.send_attachment(cid, voice.read_bytes(), "Sprachnachricht.m4a", "audio/mp4", "sprache", duration=3.4)
        news = [n for n in anna.fetch() if "text" in n]
        self.assertEqual([n["text"] for n in news], ["Datei: Plan.pdf", "Sprachnachricht"])
        self.assertEqual(anna.open_attachment(news[0]["anhang"]), document)
        self.assertEqual(news[1]["anhang"]["dauer"], 3.4)
        # Nothing readable on the server; the name and type are not there either.
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn(document[:64], server_files)
        self.assertNotIn(b"Plan.pdf", server_files)
        self.assertNotIn(b"application/pdf", server_files)
        # The sender's own copy stays readable after a restart (kept encrypted, key in the sealed chats).
        again = self.reopen(bernd)
        mine = [m for m in again.chats[cid]["messages"] if m.get("anhang", {}).get("name") == "Plan.pdf"][0]
        self.assertEqual(again.open_attachment(mine["anhang"]), document)

    def test_video_leaves_without_place_and_time(self):
        """Michelle: an MP4 carries the place (©xyz) and the recording time – both must be gone, the picture must stay."""
        import subprocess
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        video = self.root / "test.mov"     # QuickTime, like a phone: here ffmpeg writes the place as ©xyz
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
                        "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-shortest", "-c:v", "libx264", "-c:a", "aac",
                        "-metadata", "location=+52.5200+013.4050/", "-metadata", "creation_time=2026-10-01T12:00:00Z",
                        "-metadata", "title=Geheimer Ort", str(video)], check=True)
        raw = video.read_bytes()
        self.assertIn(b"+52.5200", raw)
        anna.send_attachment(cid, raw, "urlaub.mov", "video/quicktime", "video")
        info = bernd.fetch()[0]["anhang"]
        self.assertEqual((info["name"], info["mime"]), ("urlaub.mp4", "video/mp4"))
        got = bernd.open_attachment(info)
        for secret in (b"+52.5200", b"Geheimer Ort", b"2026-10-01"):
            self.assertNotIn(secret, got)
        out = self.root / "empfangen.mp4"
        out.write_bytes(got)
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", str(out)],
                               capture_output=True, text=True).stdout.split()
        self.assertEqual(sorted(probe), ["audio", "video"], "Bild und Ton bleiben")

    def test_media_as_file_lose_metadata_too(self):
        """Michelle 9780f3a0: via „Datei“ an MP4 kept its place – files that are pictures, videos or sound are cleaned too."""
        import subprocess
        from PIL import Image
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        video = self.root / "ort.mov"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10", "-c:v", "libx264",
                        "-metadata", "location=+52.5200+013.4050/", str(video)], check=True)
        anna.send_attachment(cid, video.read_bytes(), "ort.mov", "video/quicktime", "datei")
        anna.send_attachment(cid, test_image(self.root / "voll.jpg", size=(3000, 2000)).read_bytes(), "voll.jpg", "image/jpeg", "datei")
        news = [n for n in bernd.fetch() if n.get("anhang")]
        clip, picture = (bernd.open_attachment(n["anhang"]) for n in news)
        self.assertNotIn(b"+52.5200", clip)
        self.assertEqual(news[1]["anhang"]["art"], "bild")
        self.assertTrue(news[1]["anhang"]["vorschau"], "als Bild mit Vorschau wie am iPhone")
        with Image.open(io.BytesIO(picture)) as image:
            self.assertEqual(image.size, (3000, 2000), "als Datei in voller Größe")
            self.assertEqual(len(image.getexif()), 0)

    def test_changed_attachment_is_refused(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        anna.send_attachment(cid, b"Inhalt", "a.txt", "text/plain", "datei")
        info = bernd.fetch()[0]["anhang"]
        path = self.app.store.attachment_path(info["id"])
        data = bytearray(path.read_bytes())
        data[20] ^= 1
        path.write_bytes(bytes(data))
        with self.assertRaises(LiChatError):
            bernd.open_attachment(info)

    def test_incomplete_announcement_is_not_kept(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        packet = anna.device.encrypt(bytes.fromhex(cid), json.dumps({"t": "anhang", "id": "../../etc", "art": "datei"}).encode())
        anna.server.call("POST", "/v1/messages", {"to": [bernd.device_id], "blob": account_module.b64(b"M" + bytes(packet))})
        news = bernd.fetch()
        self.assertIn("unvollständig", news[0]["problem"])
        self.assertEqual(bernd.chats[cid]["messages"], [])


class Ticks(Base):
    """Card db70d358: ✓ sent, ✓✓ delivered, blue ✓✓ read – end to end, read receipts off by default and mutual."""

    def test_sent_delivered_read(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        anna.send(cid, "Hallo")
        mine = anna.chats[cid]["messages"][-1]
        self.assertEqual(anna.tick(cid, mine)[0], "gesendet")
        bernd.fetch()                       # arrives → bernd answers „zugestellt“ by itself
        anna.fetch()
        self.assertEqual(anna.tick(cid, mine)[0], "zugestellt")
        bernd.mark_read(cid)                # read receipts off by default: nothing goes out
        anna.fetch()
        self.assertEqual(anna.tick(cid, mine)[0], "zugestellt")
        bernd.set_read_receipts(True)
        bernd.mark_read(cid)
        anna.fetch()
        self.assertEqual(anna.tick(cid, mine)[0], "zugestellt", "gegenseitig: Anna hat sie aus, sieht also kein Blau")
        anna.set_read_receipts(True)
        self.assertEqual(anna.tick(cid, mine)[0], "gelesen")
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn(b"quittung", server_files)
        self.assertNotIn(b"gelesen", server_files)

    def test_typing_off_by_default_mutual_and_fleeting(self):
        """Card 884f7c8a: „… tippt“ only when both have it on, at most every 3 s, gone with the message, never stored."""
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        self.assertFalse(anna.typing(cid), "ab Werk aus: nichts geht hinaus")
        anna.set_typing_indicator(True)
        self.assertTrue(anna.typing(cid))
        self.assertFalse(anna.typing(cid), "höchstens alle 3 s")
        self.assertEqual(bernd.fetch(), [], "Bernd hat sie aus: er sieht nichts")
        self.assertEqual(bernd.typing_now(cid), [])
        bernd.set_typing_indicator(True)
        anna._typed.clear()
        anna.typing(cid)
        self.assertEqual(bernd.fetch(), [{"chat": cid, "typing": "anna"}])
        self.assertEqual(bernd.typing_now(cid), ["anna"])
        anna.send(cid, "Fertig getippt")
        bernd.fetch()
        self.assertEqual(bernd.typing_now(cid), [], "die Nachricht beendet das Tippen")
        bernd._typing[cid] = {"anna": 0}
        self.assertEqual(bernd.typing_now(cid), [], "nach ein paar Sekunden vorbei")
        self.assertEqual([m["text"] for m in bernd.chats[cid]["messages"]], ["Fertig getippt"])
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn(b"tippt", server_files)

    def test_group_needs_everyone(self):
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        cid = anna.start(["bernd", "erna"])
        bernd.fetch(), erna.fetch()
        anna.send(cid, "Wer kommt?")
        mine = anna.chats[cid]["messages"][-1]
        bernd.fetch()
        anna.fetch()
        self.assertEqual(anna.tick(cid, mine)[0], "gesendet", "Erna hat es noch nicht")
        erna.fetch()
        anna.fetch()
        self.assertEqual(anna.tick(cid, mine)[0], "zugestellt")

    def test_failed_send_is_marked_and_retried(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        real = anna.server.call
        anna.server.call = lambda *a, **k: (_ for _ in ()).throw(LiChatError("Server nicht erreichbar"))
        with self.assertRaises(LiChatError):
            anna.send(cid, "Kommt das an?")
        mine = anna.chats[cid]["messages"][-1]
        self.assertEqual(anna.tick(cid, mine)[0], "fehler")
        anna.server.call = real
        anna.retry(cid, mine["mid"])
        self.assertEqual(anna.tick(cid, mine)[0], "gesendet")
        self.assertEqual([n["text"] for n in bernd.fetch() if "text" in n], ["Kommt das an?"])


class RepliesAndReactions(Base):
    """Olaf 09.10.: swipe to answer, long press for an emoji – end to end."""

    def test_reply_and_reaction(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        anna.send(cid, "Kommst du Samstag?")
        bernd.fetch()
        asked = bernd.chats[cid]["messages"][-1]
        bernd.send(cid, "Ja!", reply_to=asked["mid"])
        answer = [n for n in anna.fetch() if "text" in n][0]
        mine = anna.chats[cid]["messages"][-1]
        self.assertEqual(mine["antwort"]["text"], "Kommst du Samstag?")
        self.assertEqual(mine["antwort"]["from"], "anna")
        anna.react(cid, mine["mid"], "❤️")
        bernd.fetch()
        self.assertEqual(next(m for m in bernd.chats[cid]["messages"] if m.get("mid") == mine["mid"])["reaktionen"], {"anna": "❤️"})
        anna.react(cid, mine["mid"], "👍")          # one per person – replaced
        anna.react(cid, mine["mid"], "")            # and taken back
        bernd.fetch()
        self.assertEqual(next(m for m in bernd.chats[cid]["messages"] if m.get("mid") == mine["mid"])["reaktionen"], {})
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn("❤️".encode(), server_files)
        self.assertNotIn(b"Kommst du", server_files)


class DisplayNames(Base):
    """Olaf 09.10.: „Olaf Winkler“ with capitals and a space – end to end; the user name stays the handle."""

    def test_name_travels_end_to_end(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        anna.set_name("Anna", "Müller-Lüdenscheidt")
        cid = anna.start(["bernd"])
        bernd.fetch()
        self.assertEqual(bernd.display("anna"), "Anna Müller-Lüdenscheidt")
        self.assertEqual(bernd.title(cid), "Anna Müller-Lüdenscheidt")
        bernd.set_name("Bernd", "")
        anna.fetch()
        self.assertEqual(anna.title(cid), "Bernd")
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn("Lüdenscheidt".encode(), server_files)
        self.assertEqual(Account.suggest_username("Olaf", "Winkler"), "olaf.winkler")
        self.assertEqual(Account.suggest_username("Jean-Marie", "Lefèvre"), "jean-marie.lefevre")
        self.assertEqual(Account.suggest_username("Jürgen Groß"), "juergen.gross")


class Contacts(Base):
    """Card 89aae55d: contacts by QR (verified) or PIN (10 minutes, once); kept only on the device."""

    def test_qr_verifies_pin_does_not(self):
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        link = anna.contact_link()
        self.assertTrue(link.startswith("lichat://kontakt?") and "name=anna" in link and "fp=" in link)
        name, contact = bernd.add_contact_link(link)
        self.assertEqual((name, contact["verifiziert"]), ("anna", True))
        # A link with a foreign fingerprint is refused.
        with self.assertRaises(LiChatError):
            bernd.add_contact_link(link.replace("fp=", "fp=00"))
        pin = anna.new_pin()
        self.assertEqual(erna.redeem_pin(pin)[0], "anna")
        self.assertFalse(erna.contacts["anna"]["verifiziert"])
        with self.assertRaises(LiChatError):
            erna.redeem_pin(pin)                      # once
        self.assertIn("anna", self.reopen(bernd).contacts)
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn(b"verifiziert", server_files)

    def test_links_are_recognised(self):
        self.assertEqual(Account.parse_link("https://lichat.lisoftware.de/e/7stv-nq2t-a8xb-4urg"),
                         {"art": "einladung", "server": "https://lichat.lisoftware.de", "code": "7STV-NQ2T-A8XB-4URG"})
        self.assertEqual(Account.parse_link("lichat://einladung?server=https%3A%2F%2Flichat.lisoftware.de&code=AB2C-DE3F")["code"], "AB2C-DE3F")
        self.assertIsNone(Account.parse_link("https://example.org/irgendwas"))


class Remove(Base):
    def test_removed_member_reads_nothing_after(self):
        """Test plan S2: Anna takes Erna out – Bernd and Anna go on, Erna learns it and gets nothing after."""
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        cid = anna.start(["bernd", "erna"], title="Verein")
        bernd.fetch(), erna.fetch()
        anna.remove(cid, ["erna"])
        self.assertEqual(sorted(anna.members(cid)), ["anna", "bernd"])
        self.assertNotIn("erna", anna.chats[cid]["roster"])
        self.assertEqual(erna.fetch(), [{"chat": cid, "removed": "anna"}])
        self.assertEqual(bernd.fetch(), [{"chat": cid, "system": "anna hat erna aus der Unterhaltung entfernt."}])
        self.assertTrue(bernd.chats[cid]["messages"][-1]["system"])
        self.assertEqual(anna.chats[cid]["messages"][-1]["text"], "Du hast erna aus der Unterhaltung entfernt.")
        self.assertNotIn("erna", bernd.chats[cid]["roster"])
        bernd.send(cid, "Jetzt ohne Erna")
        self.assertEqual([n["text"] for n in anna.fetch() if "text" in n], ["Jetzt ohne Erna"])
        self.assertEqual(erna.fetch(), [], "nichts mehr für Erna")
        with self.assertRaises(LiChatError):
            erna.send(cid, "Hallo?")
        erna = self.reopen(erna)
        self.assertEqual(erna.chats[cid]["removed"], "anna")   # history stays, the conversation is closed


class GroupLife(Base):
    """Card d45b319a: add, rename, picture, leave – end to end, with a grey line for everyone."""

    def test_add_rename_picture_leave(self):
        anna, bernd, erna, otto = self.person("anna"), self.person("bernd"), self.person("erna"), self.person("otto")
        cid = anna.start(["bernd"], title="Grillen")
        bernd.fetch()
        anna.send(cid, "vor Erna")
        anna.add(cid, ["erna"])
        self.assertEqual(sorted(anna.members(cid)), ["anna", "bernd", "erna"])
        news = [n for n in erna.fetch() if n.get("joined") or "text" in n]
        self.assertTrue(news[0]["joined"])
        self.assertEqual(erna.title(cid), "Grillen")
        self.assertNotIn("vor Erna", [m["text"] for m in erna.chats[cid]["messages"]], "keine alte Geschichte")
        self.assertIn({"chat": cid, "system": "anna hat erna hinzugefügt."}, bernd.fetch())
        erna.send(cid, "Hallo zusammen")
        self.assertIn("Hallo zusammen", [n.get("text") for n in bernd.fetch()])
        # Name and picture only end to end.
        anna.rename(cid, "Grillabend Samstag")
        anna.set_group_picture(cid, test_image(self.root / "gruppe.jpg"))
        for other in (bernd, erna):
            lines = [n.get("system") for n in other.fetch()]
            self.assertIn("anna hat die Gruppe „Grillabend Samstag“ genannt.", lines)
            self.assertIn("anna hat das Gruppenbild geändert.", lines)
            self.assertEqual(other.title(cid), "Grillabend Samstag")
            self.assertIsNotNone(other.group_picture(cid))
        server_files = b"".join(p.read_bytes() for p in (self.root / "server").rglob("*") if p.is_file())
        self.assertNotIn(b"Grillabend", server_files)
        # Erna leaves: the others are told, the first of them (anna) takes her device out.
        erna.leave(cid)
        with self.assertRaises(LiChatError):
            erna.send(cid, "noch da?")
        self.assertIn("erna hat die Unterhaltung verlassen.", [n.get("system") for n in anna.fetch()])
        self.assertIn("erna hat die Unterhaltung verlassen.", [n.get("system") for n in bernd.fetch()])
        bernd.fetch()
        self.assertEqual(sorted(bernd.members(cid)), ["anna", "bernd"])
        self.assertEqual(erna.fetch(), [], "die Bestätigung des Rauswurfs zeigt nichts mehr an")
        bernd.send(cid, "ohne Erna")
        self.assertEqual(erna.fetch(), [])
        # Otto joins later; mute is only local.
        bernd.add(cid, ["otto"])
        otto.fetch()
        self.assertEqual(sorted(otto.members(cid)), ["anna", "bernd", "otto"])
        anna.mute(cid, True)
        self.assertTrue(self.reopen(anna).chats[cid]["stumm"])


class TwoProcesses(Base):
    """Michelle 09.10.: „warten“ and „abholen“ on one profile at once overwrote each other's state (NoMatchingKeyPackage)."""

    def test_two_accounts_on_one_profile(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        other = Account(bernd.folder)          # a second lichat-cli on bernd's profile
        anna.send(cid, "eins")
        self.assertEqual([n["text"] for n in other.fetch() if "text" in n], ["eins"])
        # The first one has an old state in memory – it must take the newer one before acting, not overwrite it.
        bernd.send(cid, "zwei")
        anna.add(cid, [self.person("erna").username])
        self.assertTrue(any(n.get("system") for n in other.fetch()))
        bernd.send(cid, "drei")
        texts = [n["text"] for n in anna.fetch() if "text" in n]
        self.assertEqual(texts, ["zwei", "drei"])
        self.assertEqual(sorted(Account(bernd.folder).members(cid)), ["anna", "bernd", "erna"])

    def test_parallel_fetches_handle_each_packet_once(self):
        anna, bernd = self.person("anna"), self.person("bernd")
        cid = anna.start(["bernd"])
        bernd.fetch()
        second = Account(bernd.folder)
        for i in range(5):
            anna.send(cid, f"Nachricht {i}")
        got, problems = [], []

        def run(account):
            for news in (account.fetch(wait=2), account.fetch()):
                got.extend(n["text"] for n in news if "text" in n)
                problems.extend(n["problem"] for n in news if n.get("problem"))

        threads = [threading.Thread(target=run, args=(a,)) for a in (bernd, second)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(30)
        self.assertEqual(sorted(got), [f"Nachricht {i}" for i in range(5)])
        self.assertEqual(problems, [])


class Supply(Base):
    def test_last_resort_keeps_a_device_reachable(self):
        account_module.SUPPLY, before = 1, account_module.SUPPLY
        self.addCleanup(setattr, account_module, "SUPPLY", before)
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        first = anna.start(["bernd"])     # Bernd's only ordinary key package
        second = erna.start(["bernd"])    # now the reserve
        joined = {n["chat"] for n in bernd.fetch() if n.get("joined")}
        self.assertEqual(joined, {first, second})
        third = anna.start(["bernd", "erna"])   # Bernd uploaded fresh ones after joining
        self.assertIn(third, {n["chat"] for n in bernd.fetch() if n.get("joined")})


class Server(Base):
    def test_a_foreign_key_package_is_refused(self):
        """The server hands out Erna's key package as one of Bernd's devices – the conversation is not started."""
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        real = anna.server.call

        def lying(method, path, body=None):
            if method == "POST" and path == "/v1/key-packages/bernd":
                answer = real(method, "/v1/key-packages/erna", body)
                for item in answer["key_packages"]:
                    item["device"] = bernd.device_id
                return answer
            return real(method, path, body)

        anna.server.call = lying
        with self.assertRaises(LiChatError):
            anna.start(["bernd"])
        self.assertEqual(anna.chats, {})

    def test_roster_must_match_the_group(self):
        """A member names a device of someone outside the group in the roster – it is not taken over."""
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        cid = anna.start(["bernd"])
        bernd.fetch()
        packet = anna.device.encrypt(bytes.fromhex(cid), json.dumps(
            {"t": "roster", "devices": {"anna": [anna.device_id], "bernd": [bernd.device_id], "erna": [erna.device_id]}}).encode())
        anna.server.call("POST", "/v1/messages", {"to": [bernd.device_id], "blob": account_module.b64(b"M" + bytes(packet))})
        news = bernd.fetch()
        self.assertTrue(news and "passt nicht" in news[0]["problem"])
        self.assertNotIn("erna", bernd.chats[cid]["roster"])

    def test_http_only_on_this_computer(self):
        with self.assertRaises(LiChatError):
            account_module.check_server("http://lichat.example")
        self.assertEqual(account_module.check_server("https://lichat.example/"), "https://lichat.example")


class Cli(Base):
    def run_cli(self, profile, *args):
        env = dict(os.environ, XDG_DATA_HOME=str(self.root / "cli"), LICHAT_SOFORT="1")
        result = subprocess.run([sys.executable, str(ROOT / "linux" / "lichat-cli"), "--profil", profile, *args],
                                capture_output=True, text=True, env=env, timeout=60)
        return result.returncode, result.stdout + result.stderr

    def test_warten_survives_a_server_restart(self):
        """Michelle: after a server restart (502) „warten“ ended – an instance was deaf. Now it keeps listening."""
        self.run_cli("anna", "register", self.url, self.app.store.create_invite(), "anna")
        self.run_cli("bernd", "register", self.url, self.app.store.create_invite(), "bernd")
        chat = self.run_cli("anna", "neu", "bernd")[1].split()[1]
        self.run_cli("bernd", "abholen")
        env = dict(os.environ, XDG_DATA_HOME=str(self.root / "cli"), LICHAT_SOFORT="1")
        # From now on the first two fetches answer 502, like nginx while the container restarts.
        real, failures = self.app, {"left": 2}

        def restarting(environ, start_response):
            if environ["PATH_INFO"] == "/v1/messages" and environ["REQUEST_METHOD"] == "GET" and failures["left"] > 0:
                failures["left"] -= 1
                start_response("502 Bad Gateway", [("Content-Type", "text/plain")])
                return [b"Bad Gateway"]
            return real(environ, start_response)

        self.httpd.set_app(restarting)
        listening = subprocess.Popen([sys.executable, str(ROOT / "linux" / "lichat-cli"), "--profil", "anna", "warten", "--bis", "60"],
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
        time.sleep(1)
        self.run_cli("bernd", "senden", chat, "Nach", "dem", "Neustart")
        out, err = listening.communicate(timeout=60)
        self.assertEqual(listening.returncode, 0, err)
        self.assertIn("bernd: Nach dem Neustart", out)
        self.assertIn("neuer Versuch", err)

    def test_cli_round_trip(self):
        code, out = self.run_cli("anna", "register", self.url, self.app.store.create_invite(), "anna")
        self.assertEqual(code, 0, out)
        self.run_cli("bernd", "register", self.url, self.app.store.create_invite(), "bernd")
        code, out = self.run_cli("anna", "neu", "--titel", "Test", "bernd")
        self.assertEqual(code, 0, out)
        chat = out.split()[1]
        self.assertIn("neues Gespräch", self.run_cli("bernd", "abholen")[1])
        self.assertEqual(self.run_cli("bernd", "senden", chat, "Hallo", "aus", "der", "Konsole")[0], 0)
        self.assertIn("bernd: Hallo aus der Konsole", self.run_cli("anna", "abholen")[1])
        self.assertIn("Test", self.run_cli("anna", "gespraeche")[1])
        self.assertIn("bernd: Hallo aus der Konsole", self.run_cli("anna", "verlauf", chat)[1])
        self.assertEqual(self.run_cli("anna", "code", chat)[1], self.run_cli("bernd", "code", chat)[1])
        # reagieren / zuruecknehmen / bearbeiten / zurueckrufen – Michelles Tests ohne Handy
        self.assertIn("reagiert 👍🏽", self.run_cli("anna", "reagieren", chat, "Konsole", "👍🏽")[1])
        self.assertIn("anna reagiert 👍🏽", self.run_cli("bernd", "abholen")[1])
        self.assertIn("zurückgenommen", self.run_cli("anna", "zuruecknehmen", chat, "letzte")[1])
        self.assertIn("nimmt die Reaktion 👍🏽", self.run_cli("bernd", "abholen")[1])
        self.run_cli("bernd", "senden", chat, "Grillen", "um", "6")
        self.assertIn("bearbeitet", self.run_cli("bernd", "bearbeiten", chat, "Grillen", "um", "7")[1])
        self.assertIn("bernd hat bearbeitet: Grillen um 7", self.run_cli("anna", "abholen")[1])
        self.assertIn("(bearbeitet)", self.run_cli("anna", "verlauf", chat)[1])
        self.assertIn("widerrufen", self.run_cli("bernd", "widerrufen", chat)[1])
        self.assertIn("bernd hat eine Nachricht widerrufen", self.run_cli("anna", "abholen")[1])
        # warten: ends as soon as something comes (here: bernd writes while anna waits).
        import threading as _t
        later = _t.Timer(1.5, lambda: self.run_cli("bernd", "senden", chat, "Bist", "du", "da?"))
        later.start()
        started = time.monotonic()
        code, out = self.run_cli("anna", "warten", "--bis", "30")
        later.join()
        self.assertIn("bernd: Bist du da?", out)
        self.assertLess(time.monotonic() - started, 15)
        self.assertIn("nichts Neues", self.run_cli("anna", "warten", "--bis", "1")[1])
        # datei / anhang: send a file from the console and save it again, byte for byte.
        (self.root / "notiz.txt").write_bytes(b"Kohle \x00 Wurst")
        self.assertIn("gesendet_ms=", self.run_cli("anna", "datei", chat, str(self.root / "notiz.txt"))[1])
        self.run_cli("bernd", "abholen")
        self.assertIn("[Anhang 1]", self.run_cli("bernd", "verlauf", chat)[1])
        out_dir = self.root / "raus"
        out_dir.mkdir()
        self.assertEqual(self.run_cli("bernd", "anhang", chat, "1", str(out_dir))[0], 0)
        self.assertEqual((out_dir / "notiz.txt").read_bytes(), b"Kohle \x00 Wurst")
        code, out = self.run_cli("erna", "ich")
        self.assertEqual(code, 1)
        self.assertIn("Noch kein Konto", out)


if __name__ == "__main__":
    unittest.main()
