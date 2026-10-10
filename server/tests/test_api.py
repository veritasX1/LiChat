"""Tests of the step-1 server API, without network: requests go straight into the WSGI app.
Run:  cd server && python3 -m unittest discover -s tests -v"""

import base64
import io
import json
import os
import sqlite3
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from lichat_server.app import create_app  # noqa: E402
from lichat_server.store import KEEP_UNFETCHED  # noqa: E402



def b64(data):
    return base64.b64encode(data).decode()


class Clock:
    def __init__(self):
        self.now = 1_800_000_000.0

    def __call__(self):
        return self.now


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.clock = Clock()
        self.app = create_app(self.tmp.name, clock=self.clock)

    def tearDown(self):
        self.app.store.db.close()
        self.tmp.cleanup()

    def call(self, method, path, body=None, token=None, ip="10.0.0.1", query=""):
        raw = json.dumps(body).encode() if body is not None else b""
        environ = {"REQUEST_METHOD": method, "PATH_INFO": path, "CONTENT_LENGTH": str(len(raw)),
                   "wsgi.input": io.BytesIO(raw), "REMOTE_ADDR": ip, "QUERY_STRING": query}
        if token:
            environ["HTTP_AUTHORIZATION"] = "Bearer " + token
        result = {}

        def start(status, headers):
            result["status"] = int(status.split()[0])
            result["headers"] = dict(headers)
        data = b"".join(self.app(environ, start))
        return result["status"], json.loads(data)

    def raw(self, method, path, data=b"", token=None, length=None):
        path, _, query = path.partition("?")
        environ = {"REQUEST_METHOD": method, "PATH_INFO": path, "CONTENT_LENGTH": str(len(data) if length is None else length),
                   "wsgi.input": io.BytesIO(data), "REMOTE_ADDR": "10.0.0.1", "QUERY_STRING": query}
        if token:
            environ["HTTP_AUTHORIZATION"] = "Bearer " + token
        result = {}

        def start(status, headers):
            result["status"] = int(status.split()[0])
            result["headers"] = dict(headers)
        body = b"".join(self.app(environ, start))
        return result["status"], result["headers"], body

    def account(self, name):
        invite = self.app.store.create_invite()
        status, data = self.call("POST", "/v1/register", {"invite": invite, "username": name,
                                                          "identity": b64(b"id-" + name.encode() * 8)})
        self.assertEqual(status, 200, data)
        return data["device"], data["token"]

    # Registration --------------------------------------------------------------------------------

    def test_link_a_second_device(self):
        """Card da5cbfcb: the new device opens a code, an existing device accepts it, the new one signs in – once, in time,
        with its own key; then it is in the directory with its name, can be claimed alone and signed out from the other."""
        anna_device, anna = self.account("anna")
        status, opened = self.call("POST", "/v1/kopplung", ip="10.0.0.9")
        self.assertEqual(status, 200)
        code = opened["code"]
        self.assertEqual(self.call("GET", f"/v1/kopplung/{code}"), (200, {}))
        self.assertEqual(self.call("POST", f"/v1/kopplung/{code}/geraet", {"identity": b64(b"neu" * 12)})[0], 409, "erst annehmen")
        self.assertEqual(self.call("POST", "/v1/kopplung/annehmen", {"code": code.lower().replace("-", "")}, token=anna)[0], 200)
        _, bernd = self.account("bernd")
        self.assertEqual(self.call("POST", "/v1/kopplung/annehmen", {"code": code}, token=bernd)[0], 404, "schon vergeben")
        self.assertEqual(self.call("GET", f"/v1/kopplung/{code}"), (200, {"username": "anna"}))
        status, signed = self.call("POST", f"/v1/kopplung/{code}/geraet", {"identity": b64(b"neu" * 12), "name": "Anna Laptop"})
        self.assertEqual(status, 200)
        self.assertEqual(signed["username"], "anna")
        self.assertEqual(self.call("POST", f"/v1/kopplung/{code}/geraet", {"identity": b64(b"zwei" * 12)})[0], 409, "nur einmal")
        self.assertEqual(self.call("GET", f"/v1/kopplung/{code}")[1], {"username": "anna", "device": signed["device"]})
        directory = self.call("GET", "/v1/users/anna", token=bernd)[1]["devices"]
        self.assertEqual([d["device"] for d in directory], [anna_device, signed["device"]])
        own = self.call("GET", "/v1/devices", token=anna)[1]["devices"]
        self.assertEqual([d["device"] for d in own], [anna_device, signed["device"]])
        # Michelle: no device name in plain text on the server – not in the answers, not in the database
        dump = "\n".join(self.app.store.db.iterdump())
        self.assertNotIn("Anna Laptop", dump)
        # claim only the new device's package
        self.call("POST", "/v1/key-packages", {"key_packages": [b64(b"kp-neu")]}, token=signed["token"])
        self.call("POST", "/v1/key-packages", {"key_packages": [b64(b"kp-alt")]}, token=anna)
        claimed = self.call("POST", "/v1/key-packages/anna", {"device": signed["device"]}, token=anna)[1]["key_packages"]
        self.assertEqual([c["device"] for c in claimed], [signed["device"]])
        # sign it out from the first device; not someone else's, not oneself this way
        self.assertEqual(self.call("DELETE", f"/v1/devices/{signed['device']}", token=bernd)[0], 404)
        self.assertEqual(self.call("DELETE", f"/v1/devices/{anna_device}", token=anna)[0], 400)
        self.call("POST", "/v1/messages", {"to": [signed["device"]], "blob": b64(b"M" + b"x" * 40)}, token=bernd)
        self.call("PUT", "/v1/key-packages/last-resort", {"key_package": b64(b"lr")}, token=signed["token"])
        self.assertEqual(self.call("DELETE", f"/v1/devices/{signed['device']}", token=anna)[0], 200)
        # Michelle: everything of the signed-out device is gone at once – mailbox, key packages, last resort
        for table in ("messages", "key_packages", "last_resort"):
            self.assertEqual(self.app.store.db.execute(f"SELECT COUNT(*) FROM {table} WHERE device = ?", (signed["device"],)).fetchone()[0], 0, table)
        self.assertEqual(self.call("GET", "/v1/messages", token=signed["token"])[0], 401)
        # expiry and guessing
        late = self.call("POST", "/v1/kopplung", ip="10.0.0.9")[1]["code"]
        self.clock.now += 11 * 60
        self.assertEqual(self.call("POST", "/v1/kopplung/annehmen", {"code": late}, token=anna)[0], 404)
        self.assertEqual(self.call("GET", f"/v1/kopplung/{late}")[0], 404)
        statuses = [self.call("POST", "/v1/kopplung/annehmen", {"code": f"AAAA-AA{i:02d}"}, token=anna)[0] for i in range(12)]
        self.assertEqual(statuses[-1], 429)

    def test_register_needs_a_valid_single_use_invite(self):
        status, _ = self.call("POST", "/v1/register", {"invite": "falsch", "username": "anna",
                                                       "identity": b64(b"x" * 32)})
        self.assertEqual(status, 403)
        invite = self.app.store.create_invite()
        body = {"invite": invite, "username": "anna", "identity": b64(b"x" * 32)}
        self.assertEqual(self.call("POST", "/v1/register", body)[0], 200)
        body["username"] = "bernd"
        self.assertEqual(self.call("POST", "/v1/register", body)[0], 403, "Einladung nur einmal")

    def test_expired_invite_and_taken_name(self):
        invite = self.app.store.create_invite(days=1)
        self.clock.now += 2 * 86400
        self.assertEqual(self.call("POST", "/v1/register", {"invite": invite, "username": "anna",
                                                            "identity": b64(b"x" * 32)})[0], 403)
        self.account("anna")
        invite = self.app.store.create_invite()
        self.assertEqual(self.call("POST", "/v1/register", {"invite": invite, "username": "anna",
                                                            "identity": b64(b"y" * 32)})[0], 409)

    def test_username_rules_and_no_phone_number(self):
        for name in ["A", "Anna", "anna bernd", "+491701234567", "x" * 33]:
            invite = self.app.store.create_invite()
            status, _ = self.call("POST", "/v1/register", {"invite": invite, "username": name,
                                                           "identity": b64(b"x" * 32)})
            self.assertEqual(status, 400, name)

    def test_invite_codes_are_easy_to_type(self):
        """Olaf 09.10.: „I“ or „l“? Codes without I, L, O, 0, 1, in groups of four; case, spaces and dashes do not matter."""
        code = self.app.store.create_invite()
        self.assertRegex(code, r"^[A-HJKMNP-Z2-9]{4}(-[A-HJKMNP-Z2-9]{4}){3}$")
        typed = code.replace("-", " ").lower()
        status, data = self.call("POST", "/v1/register", {"invite": typed, "username": "olaf", "identity": b64(b"x" * 32)})
        self.assertEqual(status, 200, data)

    def test_taken_name_keeps_the_invite(self):
        self.account("olaf")
        code = self.app.store.create_invite()
        status, _ = self.call("POST", "/v1/register", {"invite": code, "username": "olaf", "identity": b64(b"x" * 32)})
        self.assertEqual(status, 409)
        status, _ = self.call("POST", "/v1/register", {"invite": code, "username": "erika.mustermann", "identity": b64(b"x" * 32)})
        self.assertEqual(status, 200, "die Einladung gilt nach einem vergebenen Namen noch")

    def test_renewed_token_voids_the_old_one(self):
        _, old = self.account("anna")
        status, data = self.call("POST", "/v1/devices/me/token", {}, token=old)
        self.assertEqual(status, 200)
        self.assertEqual(self.call("GET", "/v1/messages", token=old)[0], 401, "die alte Kopie gilt nicht mehr")
        self.assertEqual(self.call("GET", "/v1/messages", token=data["token"])[0], 200)

    def test_taken_name_comes_with_free_suggestions(self):
        """ebc3d629: free alternatives – from the real name first; never without a valid invitation."""
        self.account("anna")
        self.account("anna.mueller")
        status, data = self.call("POST", "/v1/register", {"invite": "falsch", "username": "anna", "identity": b64(b"x" * 32)})
        self.assertEqual((status, data.get("vorschlaege")), (403, None), "ohne gültige Einladung nichts")
        code = self.app.store.create_invite()
        status, data = self.call("POST", "/v1/register", {"invite": code, "username": "anna", "vorname": "Anna", "nachname": "Müller",
                                                          "identity": b64(b"x" * 32)})
        self.assertEqual(status, 409)
        self.assertEqual(data["vorschlaege"], ["anna.m", "a.mueller", "annamueller"])
        status, data = self.call("POST", "/v1/register", {"invite": code, "username": "anna", "identity": b64(b"x" * 32)})
        self.assertEqual(data["vorschlaege"][:2], ["anna2", "anna3"])
        status, _ = self.call("POST", "/v1/register", {"invite": code, "username": data["vorschlaege"][0], "identity": b64(b"x" * 32)})
        self.assertEqual(status, 200, "ein Vorschlag ist wirklich frei")

    def test_contact_pin_once_and_short_lived(self):
        _, anna = self.account("anna")
        _, bernd = self.account("bernd")
        pin = self.call("POST", "/v1/pins", {}, token=anna)[1]["pin"]
        self.assertRegex(pin, r"^[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$")
        self.assertEqual(self.call("POST", "/v1/pins/einloesen", {"pin": pin.lower().replace("-", " ")}, token=bernd)[1], {"username": "anna"})
        self.assertEqual(self.call("POST", "/v1/pins/einloesen", {"pin": pin}, token=bernd)[0], 404, "nur einmal")
        late = self.call("POST", "/v1/pins", {}, token=anna)[1]["pin"]
        self.clock.now += 601
        self.assertEqual(self.call("POST", "/v1/pins/einloesen", {"pin": late}, token=bernd)[0], 404, "nach 10 Minuten weg")
        statuses = [self.call("POST", "/v1/pins/einloesen", {"pin": "AAAA-AAAA"}, token=bernd)[0] for _ in range(12)]
        self.assertIn(429, statuses, "Fehlversuche gebremst")

    def test_people_invite_by_qr_code(self):
        """Olaf 10.10.: someone already here makes an invitation (QR code: server + code); the new person only chooses a
        name and learns who invited them. At most 5 open per person, valid 7 days; strangers cannot make any."""
        anna = self.account("anna")[1]
        self.assertEqual(self.call("POST", "/v1/einladungen", {})[0], 401)
        status, made = self.call("POST", "/v1/einladungen", {}, token=anna)
        self.assertEqual(status, 200)
        self.assertTrue(made["link"].endswith("/e/" + made["code"]))
        status, data = self.call("POST", "/v1/register", {"invite": made["code"], "username": "tante.erna", "identity": b64(b"e" * 32)})
        self.assertEqual((status, data.get("von")), (200, "anna"))
        for _ in range(5):
            self.assertEqual(self.call("POST", "/v1/einladungen", {}, token=anna)[0], 200)
        self.assertEqual(self.call("POST", "/v1/einladungen", {}, token=anna)[0], 429, "höchstens 5 offen")
        self.clock.now += 8 * 86400
        self.assertEqual(self.call("POST", "/v1/einladungen", {}, token=anna)[0], 200, "abgelaufene zählen nicht")
        admin = self.app.store.create_invite()
        self.assertNotIn("von", self.call("POST", "/v1/register", {"invite": admin, "username": "olaf", "identity": b64(b"o" * 32)})[1])

    def test_invite_page_has_link_qr_and_code(self):
        code = self.app.store.create_invite()
        environ = {"REQUEST_METHOD": "GET", "PATH_INFO": f"/e/{code.lower()}", "CONTENT_LENGTH": "0", "wsgi.input": io.BytesIO(b""),
                   "REMOTE_ADDR": "10.0.0.1", "QUERY_STRING": ""}
        result = {}
        page = b"".join(self.app(environ, lambda status, headers: result.update(status=status, headers=dict(headers)))).decode()
        self.assertTrue(result["status"].startswith("200"))
        self.assertIn(f"code={code}", page)
        self.assertIn("data:image/svg+xml", page)
        self.assertIn("default-src 'none'", result["headers"]["Content-Security-Policy"])
        self.assertNotIn("<script", page)

    def test_no_push_service_is_ever_stored(self):
        """Olaf 09.10.: no ntfy, no Google – a push address sent along is ignored, not kept."""
        invite = self.app.store.create_invite()
        status, _ = self.call("POST", "/v1/register", {"invite": invite, "username": "anna",
                                                       "identity": b64(b"x" * 32), "push": "https://fcm.googleapis.com/x"})
        self.assertEqual(status, 200)
        columns = [r[1] for r in self.app.store.db.execute("PRAGMA table_info(devices)")]
        self.assertNotIn("push", columns)
        self.assertNotIn(b"fcm.googleapis", b"".join(str(r).encode() for r in self.app.store.db.execute("SELECT * FROM devices")))

    def test_old_database_loses_the_push_column(self):
        old = os.path.join(self.tmp.name, "alt")
        os.makedirs(old)
        db = sqlite3.connect(os.path.join(old, "lichat.db"))
        db.executescript("""CREATE TABLE accounts (username TEXT PRIMARY KEY, created REAL NOT NULL);
            CREATE TABLE devices (id TEXT PRIMARY KEY, username TEXT NOT NULL, token TEXT NOT NULL UNIQUE,
                identity TEXT NOT NULL, push TEXT, created REAL NOT NULL);
            INSERT INTO accounts VALUES ('anna', 1); INSERT INTO devices VALUES ('0123456789abcdef', 'anna', 't', 'i', 'https://ntfy.x/geheim', 1);""")
        db.commit()
        db.close()
        store = create_app(old).store
        self.assertEqual([r[1] for r in store.db.execute("PRAGMA table_info(devices)")], ["id", "username", "token", "identity", "created"])
        self.assertEqual(store.devices("anna"), [{"device": "0123456789abcdef", "identity": "i"}])
        store.db.close()

    def test_wrong_token_is_rejected_and_limited(self):
        self.assertEqual(self.call("GET", "/v1/messages")[0], 401)
        codes = [self.call("GET", "/v1/messages", token="falsch")[0] for _ in range(25)]
        self.assertIn(429, codes)

    # Directory and key packages ------------------------------------------------------------------

    def test_directory_shows_devices_and_identity_only(self):
        _, anna = self.account("anna")
        self.account("bernd")
        status, data = self.call("GET", "/v1/users/bernd", token=anna)
        self.assertEqual(status, 200)
        self.assertEqual(set(data["devices"][0]), {"device", "identity"})
        self.assertEqual(self.call("GET", "/v1/users/niemand", token=anna)[0], 404)

    def test_key_packages_are_handed_out_once_per_device(self):
        _, anna = self.account("anna")
        bernd_dev, bernd = self.account("bernd")
        self.call("POST", "/v1/key-packages", {"key_packages": [b64(b"kp1"), b64(b"kp2")]}, token=bernd)
        first = self.call("POST", "/v1/key-packages/bernd", token=anna)[1]["key_packages"]
        second = self.call("POST", "/v1/key-packages/bernd", token=anna)[1]["key_packages"]
        third = self.call("POST", "/v1/key-packages/bernd", token=anna)[1]["key_packages"]
        self.assertEqual([p["key_package"] for p in first + second], [b64(b"kp1"), b64(b"kp2")])
        self.assertEqual(first[0]["device"], bernd_dev)
        self.assertEqual(third, [])

    def test_last_resort_package_and_claim_limit(self):
        """3c7ea47d: an emptied supply still leaves the last-resort package (kept); claiming is limited per claimer."""
        _, anna = self.account("anna")
        _, erna = self.account("erna")
        _, bernd = self.account("bernd")
        self.call("POST", "/v1/key-packages", {"key_packages": [b64(b"kp1")]}, token=bernd)
        self.assertEqual(self.call("PUT", "/v1/key-packages/last-resort", {"key_package": b64(b"reserve")}, token=bernd)[0], 200)
        first = self.call("POST", "/v1/key-packages/bernd", token=anna)[1]["key_packages"]
        self.assertEqual((first[0]["key_package"], first[0]["last_resort"]), (b64(b"kp1"), False))
        for _ in range(9):
            again = self.call("POST", "/v1/key-packages/bernd", token=anna)[1]["key_packages"]
            self.assertEqual((again[0]["key_package"], again[0]["last_resort"]), (b64(b"reserve"), True))
        self.assertEqual(self.call("POST", "/v1/key-packages/bernd", token=anna)[0], 429)
        self.assertEqual(self.call("POST", "/v1/key-packages/bernd", token=erna)[1]["key_packages"][0]["key_package"], b64(b"reserve"))

    def test_real_ip_header_only_from_trusted_proxy(self):
        """ddb9b934: X-Real-IP from anyone else does not dodge the brakes."""
        def wrong(ip, header):
            environ_ip = {"ip": ip}
            raw = b""
            environ = {"REQUEST_METHOD": "GET", "PATH_INFO": "/v1/messages", "CONTENT_LENGTH": "0", "wsgi.input": io.BytesIO(raw),
                       "REMOTE_ADDR": environ_ip["ip"], "HTTP_X_REAL_IP": header, "HTTP_AUTHORIZATION": "Bearer falsch"}
            out = {}
            self.app(environ, lambda status, headers: out.setdefault("status", int(status.split()[0])))
            return out["status"]
        statuses = [wrong("10.0.0.9", f"192.0.2.{i}") for i in range(25)]
        self.assertIn(429, statuses, "fremder X-Real-IP umgeht die Bremse")
        self.app.trusted_proxies = {"127.0.0.1"}
        self.assertEqual(self.app.address({"REMOTE_ADDR": "127.0.0.1", "HTTP_X_REAL_IP": "198.51.100.7"}), "198.51.100.7")
        self.assertEqual(self.app.address({"REMOTE_ADDR": "10.0.0.9", "HTTP_X_REAL_IP": "198.51.100.7"}), "10.0.0.9")

    def test_mailbox_quota(self):
        """4ba8dc07: nobody fills a stranger's mailbox – beyond the quota the device is reported as full."""
        from lichat_server import store as store_module
        _, anna = self.account("anna")
        bernd_dev, _ = self.account("bernd")
        old = store_module.MAILBOX_BYTES
        store_module.MAILBOX_BYTES = 3000
        try:
            results = [self.call("POST", "/v1/messages", {"to": [bernd_dev], "blob": b64(b"x" * 1000)}, token=anna)[1] for _ in range(4)]
        finally:
            store_module.MAILBOX_BYTES = old
        self.assertEqual([r["delivered"] for r in results], [[bernd_dev]] * 3 + [[]])
        self.assertEqual(results[3]["full"], [bernd_dev])

    def test_secure_delete_is_on(self):
        """a255c433: deleted messages are overwritten in the database file."""
        self.assertEqual(self.app.store.db.execute("PRAGMA secure_delete").fetchone()[0], 1)

    # Messages ------------------------------------------------------------------------------------

    def test_message_round_trip_and_deleted_after_ack(self):
        _, anna = self.account("anna")
        bernd_dev, bernd = self.account("bernd")
        status, data = self.call("POST", "/v1/messages", {"to": [bernd_dev], "blob": b64(b"\x00mls")}, token=anna)
        self.assertEqual((status, data["delivered"]), (200, [bernd_dev]))
        box = self.call("GET", "/v1/messages", token=bernd)[1]["messages"]
        self.assertEqual([m["blob"] for m in box], [b64(b"\x00mls")])
        self.call("POST", "/v1/messages/ack", {"ids": [box[0]["id"]]}, token=bernd)
        self.assertEqual(self.call("GET", "/v1/messages", token=bernd)[1]["messages"], [])
        rows = self.app.store.db.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
        self.assertEqual(rows, 0, "zugestellt = sofort gelöscht (S3)")

    def test_server_does_not_store_the_sender(self):
        """Sealed sender: the messages table has no sender column at all."""
        columns = [r[1] for r in self.app.store.db.execute("PRAGMA table_info(messages)")]
        self.assertEqual(columns, ["id", "device", "blob", "received"])

    def test_waiting_fetch_wakes_up_when_a_message_arrives(self):
        """The apps' standing connection: GET ?warten=… answers as soon as something lands, not only after the time."""
        _, anna = self.account("anna")
        bernd_dev, bernd = self.account("bernd")
        got = {}

        def wait():
            started = time.monotonic()
            got["box"] = self.call("GET", "/v1/messages", token=bernd, query="warten=20")[1]["messages"]
            got["seconds"] = time.monotonic() - started

        waiter = threading.Thread(target=wait)
        waiter.start()
        time.sleep(0.3)
        self.call("POST", "/v1/messages", {"to": [bernd_dev], "blob": b64(b"\x00hallo")}, token=anna)
        waiter.join(5)
        self.assertEqual([m["blob"] for m in got["box"]], [b64(b"\x00hallo")])
        self.assertLess(got["seconds"], 3)

    def test_many_threads_at_once(self):
        """09.10. (Michelle): waiting fetches, sends and acks in parallel broke the one shared SQLite connection (500)."""
        devices = [self.account(name) for name in ("anna", "bernd", "erna", "otto")]
        errors = []

        def hammer(device, token):
            for _ in range(40):
                for method, path, body in (("POST", "/v1/messages", {"to": [d for d, _ in devices], "blob": b64(b"x")}),
                                           ("GET", "/v1/messages", None)):
                    status, data = self.call(method, path, body, token=token)
                    if status != 200:
                        errors.append((status, data))
                box = self.call("GET", "/v1/messages", token=token)[1]["messages"]
                self.call("POST", "/v1/messages/ack", {"ids": [m["id"] for m in box]}, token=token)

        threads = [threading.Thread(target=hammer, args=d) for d in devices]
        for t in threads:
            t.start()
        for t in threads:
            t.join(60)
        self.assertEqual(errors, [])

    def test_waiting_fetch_ends_empty_after_its_time_and_is_capped(self):
        _, bernd = self.account("bernd")
        started = time.monotonic()
        self.assertEqual(self.call("GET", "/v1/messages", token=bernd, query="warten=1")[1]["messages"], [])
        self.assertGreaterEqual(time.monotonic() - started, 0.9)
        self.assertEqual(self.call("GET", "/v1/messages", token=bernd, query="warten=x")[0], 400)

    def test_waiting_fetch_answers_at_once_when_something_is_there(self):
        _, anna = self.account("anna")
        bernd_dev, bernd = self.account("bernd")
        self.call("POST", "/v1/messages", {"to": [bernd_dev], "blob": b64(b"x")}, token=anna)
        started = time.monotonic()
        self.assertEqual(len(self.call("GET", "/v1/messages", token=bernd, query="warten=30")[1]["messages"]), 1)
        self.assertLess(time.monotonic() - started, 1)

    def test_ack_cannot_delete_someone_elses_messages(self):
        _, anna = self.account("anna")
        bernd_dev, bernd = self.account("bernd")
        self.call("POST", "/v1/messages", {"to": [bernd_dev], "blob": b64(b"x")}, token=anna)
        msg = self.call("GET", "/v1/messages", token=bernd)[1]["messages"][0]
        self.call("POST", "/v1/messages/ack", {"ids": [msg["id"]]}, token=anna)
        self.assertEqual(len(self.call("GET", "/v1/messages", token=bernd)[1]["messages"]), 1)

    def test_unfetched_messages_go_after_30_days(self):
        _, anna = self.account("anna")
        bernd_dev, bernd = self.account("bernd")
        self.call("POST", "/v1/messages", {"to": [bernd_dev], "blob": b64(b"x")}, token=anna)
        self.clock.now += KEEP_UNFETCHED - 60
        self.assertEqual(self.app.store.purge(), 0)
        self.clock.now += 120
        self.assertEqual(self.app.store.purge(), 1)
        self.assertEqual(self.call("GET", "/v1/messages", token=bernd)[1]["messages"], [])

    def test_server_tidies_up_by_itself(self):
        """1.0: the 30 days hold without anyone running „purge“ – a request after an hour starts the tidy-up."""
        _, anna = self.account("anna")
        bernd_dev, bernd = self.account("bernd")
        self.call("POST", "/v1/messages", {"to": [bernd_dev], "blob": b64(b"x")}, token=anna)
        self.clock.now += KEEP_UNFETCHED + 60
        self.call("GET", "/v1/health")
        for _ in range(100):
            if self.app.purging.acquire(blocking=False):
                self.app.purging.release()
                break
            time.sleep(0.02)
        count = self.app.store.db.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
        self.assertEqual(count, 0)

    def test_unknown_devices_are_reported(self):
        _, anna = self.account("anna")
        status, data = self.call("POST", "/v1/messages", {"to": ["0123456789abcdef"], "blob": b64(b"x")}, token=anna)
        self.assertEqual((status, data["delivered"], data["unknown"]), (200, [], ["0123456789abcdef"]))

    def test_bad_input(self):
        dev, anna = self.account("anna")
        self.assertEqual(self.call("POST", "/v1/messages", {"to": [dev], "blob": "%%%"}, token=anna)[0], 400)
        big = b64(b"x" * (256 * 1024 + 1))
        self.assertEqual(self.call("POST", "/v1/messages", {"to": [dev], "blob": big}, token=anna)[0], 413)
        self.assertEqual(self.call("POST", "/v1/messages", {"to": ["../x"], "blob": b64(b"x")}, token=anna)[0], 400)
        self.assertEqual(self.call("PUT", "/v1/messages", {}, token=anna)[0], 405)
        self.assertEqual(self.call("GET", "/v1/gibtsnicht")[0], 404)

    # Devices -------------------------------------------------------------------------------------

    def test_second_device_and_sign_out(self):
        _, anna = self.account("anna")
        status, data = self.call("POST", "/v1/devices", {"identity": b64(b"zweites-geraet!!")}, token=anna)
        self.assertEqual(status, 200)
        devices = self.call("GET", "/v1/users/anna", token=anna)[1]["devices"]
        self.assertEqual(len(devices), 2)
        self.call("DELETE", "/v1/devices/me", token=data["token"])
        self.assertEqual(self.call("GET", "/v1/messages", token=data["token"])[0], 401)
        self.assertEqual(len(self.call("GET", "/v1/users/anna", token=anna)[1]["devices"]), 1)

    def test_tokens_are_stored_hashed(self):
        _, token = self.account("anna")
        db = sqlite3.connect(os.path.join(self.tmp.name, "lichat.db"))
        stored = [r[0] for r in db.execute("SELECT token FROM devices")]
        db.close()
        self.assertNotIn(token, stored)


    # Attachments (card 9780f3a0) ----------------------------------------------------------------

    def test_attachment_round_trip_opaque(self):
        _, anna = self.account("anna")
        _, bernd = self.account("bernd")
        secret = os.urandom(300_000)
        status, _, body = self.raw("PUT", "/v1/anhaenge", secret, token=anna)
        self.assertEqual(status, 200, body)
        attachment = json.loads(body)["id"]
        self.assertRegex(attachment, r"^[0-9a-f]{32}$")
        status, headers, got = self.raw("GET", f"/v1/anhaenge/{attachment}", token=bernd)
        self.assertEqual((status, headers["Content-Type"], got), (200, "application/octet-stream", secret))
        columns = [r[1] for r in self.app.store.db.execute("PRAGMA table_info(attachments)")]
        self.assertEqual(columns, ["id", "device", "size", "created"], "kein Name, keine Art")

    def test_attachment_needs_sign_in_and_known_id(self):
        _, anna = self.account("anna")
        self.assertEqual(self.raw("PUT", "/v1/anhaenge", b"x")[0], 401)
        self.assertEqual(self.raw("GET", "/v1/anhaenge/" + "0" * 32, token=anna)[0], 404)
        self.assertEqual(self.raw("GET", "/v1/anhaenge/../lichat.db", token=anna)[0], 404)
        self.assertEqual(self.raw("PUT", "/v1/anhaenge", b"", token=anna)[0], 400)

    def test_attachment_limits(self):
        from lichat_server import store as store_module
        _, anna = self.account("anna")
        self.assertEqual(self.raw("PUT", "/v1/anhaenge", b"", token=anna, length=101 * 1024 * 1024)[0], 413)
        self.assertEqual(self.raw("PUT", "/v1/anhaenge", b"abc", token=anna, length=10)[0], 400, "abgebrochen")
        self.assertEqual(list((self.app.store.dir / "anhaenge").glob("*.teil")), [], "Rest weg")
        before = store_module.ATTACHMENTS_PER_DEVICE
        store_module.ATTACHMENTS_PER_DEVICE = 1000
        try:
            self.assertEqual(self.raw("PUT", "/v1/anhaenge", b"x" * 800, token=anna)[0], 200)
            self.assertEqual(self.raw("PUT", "/v1/anhaenge", b"x" * 300, token=anna)[0], 413)
        finally:
            store_module.ATTACHMENTS_PER_DEVICE = before

    def test_once_gone_when_every_other_person_opened_it(self):
        """fe9f2029: a one-time picture for two people – each may fetch it until they opened it, then it is deleted."""
        _, anna = self.account("anna")
        _, bernd = self.account("bernd")
        _, erna = self.account("erna")
        once = json.loads(self.raw("PUT", "/v1/anhaenge?einmal=2", b"geheim", token=anna)[2])["id"]
        self.assertEqual(self.raw("GET", f"/v1/anhaenge/{once}", token=bernd)[2], b"geheim")
        self.assertEqual(self.call("POST", f"/v1/anhaenge/{once}/gesehen", {}, token=bernd)[0], 200)
        self.assertEqual(self.raw("GET", f"/v1/anhaenge/{once}", token=bernd)[0], 410, "Bernd hat es gesehen")
        self.assertEqual(self.raw("GET", f"/v1/anhaenge/{once}", token=erna)[2], b"geheim")
        self.call("POST", f"/v1/anhaenge/{once}/gesehen", {}, token=erna)
        self.assertEqual(self.raw("GET", f"/v1/anhaenge/{once}", token=erna)[0], 404)
        self.assertFalse(self.app.store.attachment_path(once).exists(), "vom Server gelöscht")
        # unopened: gone after 14 days; a normal attachment stays
        later = json.loads(self.raw("PUT", "/v1/anhaenge?einmal=1", b"x", token=anna)[2])["id"]
        normal = json.loads(self.raw("PUT", "/v1/anhaenge", b"y", token=anna)[2])["id"]
        self.assertEqual(self.call("POST", f"/v1/anhaenge/{normal}/gesehen", {}, token=bernd)[0], 404)
        self.clock.now += 15 * 24 * 3600
        self.app.store.purge()
        self.assertEqual(self.raw("GET", f"/v1/anhaenge/{later}", token=bernd)[0], 404)
        self.assertEqual(self.raw("GET", f"/v1/anhaenge/{normal}", token=bernd)[2], b"y")

    def test_attachment_gone_after_30_days_and_with_its_device(self):
        _, anna = self.account("anna")
        _, bernd = self.account("bernd")
        first = json.loads(self.raw("PUT", "/v1/anhaenge", b"alt", token=anna)[2])["id"]
        self.clock.now += KEEP_UNFETCHED + 60
        second = json.loads(self.raw("PUT", "/v1/anhaenge", b"neu", token=anna)[2])["id"]
        self.app.store.purge()
        self.assertEqual(self.raw("GET", f"/v1/anhaenge/{first}", token=bernd)[0], 404)
        self.assertFalse(self.app.store.attachment_path(first).exists())
        self.assertEqual(self.raw("GET", f"/v1/anhaenge/{second}", token=bernd)[2], b"neu")
        self.call("DELETE", "/v1/devices/me", token=anna)
        self.app.store.purge()
        self.assertFalse(self.app.store.attachment_path(second).exists(), "abgemeldet = Anhänge weg")



if __name__ == "__main__":
    unittest.main()
