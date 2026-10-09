"""lichat-cli / account.py against a real LiChat server in this process (isolated folders, port chosen by the system).

    cd linux && python3 -m unittest discover -s tests
"""

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
        self.assertEqual([(n["from"], n["text"]) for n in news], [("anna", "Hallo Bernd!")])
        self.assertEqual(bernd.fetch(), [], "quittiert – nichts doppelt")

        # Both restart; the conversation goes on, the codes agree.
        anna, bernd = self.reopen(anna), self.reopen(bernd)
        bernd.send(cid, "Hallo Anna")
        self.assertEqual([(n["from"], n["text"]) for n in anna.fetch()], [("bernd", "Hallo Anna")])
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
    def test_three_with_title(self):
        anna, bernd, erna = self.person("anna"), self.person("bernd"), self.person("erna")
        cid = anna.start(["bernd", "erna"], title="Grillabend")
        for other in (bernd, erna):
            self.assertEqual(other.fetch(), [{"chat": cid, "joined": True}])
            self.assertEqual(other.title(cid), "Grillabend")
        erna.send(cid, "Ich bringe Salat mit")
        self.assertEqual([n["text"] for n in anna.fetch()], ["Ich bringe Salat mit"])
        self.assertEqual([n["text"] for n in bernd.fetch()], ["Ich bringe Salat mit"])
        self.assertEqual(sorted(erna.members(cid)), ["anna", "bernd", "erna"])
        self.assertTrue(anna.safety_code(cid) == bernd.safety_code(cid) == erna.safety_code(cid))


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
        env = dict(os.environ, XDG_DATA_HOME=str(self.root / "cli"))
        result = subprocess.run([sys.executable, str(ROOT / "linux" / "lichat-cli"), "--profil", profile, *args],
                                capture_output=True, text=True, env=env, timeout=60)
        return result.returncode, result.stdout + result.stderr

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
        code, out = self.run_cli("erna", "ich")
        self.assertEqual(code, 1)
        self.assertIn("Noch kein Konto", out)


if __name__ == "__main__":
    unittest.main()
