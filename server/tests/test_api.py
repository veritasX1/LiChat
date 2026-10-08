"""Tests of the step-1 server API, without network: requests go straight into the WSGI app.
Run:  cd server && python3 -m unittest discover -s tests -v"""

import base64
import io
import json
import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from lichat_server.app import PUSH_TEXT, create_app  # noqa: E402
from lichat_server.store import KEEP_UNFETCHED  # noqa: E402

NTFY = "https://ntfy.test/"


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
        self.pushes = []
        self.app = create_app(self.tmp.name, notifier=self.pushes.append, clock=self.clock, push_hosts=NTFY)

    def tearDown(self):
        self.app.store.db.close()
        self.tmp.cleanup()

    def call(self, method, path, body=None, token=None, ip="10.0.0.1"):
        raw = json.dumps(body).encode() if body is not None else b""
        environ = {"REQUEST_METHOD": method, "PATH_INFO": path, "CONTENT_LENGTH": str(len(raw)),
                   "wsgi.input": io.BytesIO(raw), "REMOTE_ADDR": ip}
        if token:
            environ["HTTP_AUTHORIZATION"] = "Bearer " + token
        result = {}

        def start(status, headers):
            result["status"] = int(status.split()[0])
            result["headers"] = dict(headers)
        data = b"".join(self.app(environ, start))
        return result["status"], json.loads(data)

    def account(self, name, push=None):
        invite = self.app.store.create_invite()
        status, data = self.call("POST", "/v1/register", {"invite": invite, "username": name,
                                                          "identity": b64(b"id-" + name.encode() * 8), "push": push})
        self.assertEqual(status, 200, data)
        return data["device"], data["token"]

    # Registration --------------------------------------------------------------------------------

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

    def test_push_only_to_own_ntfy(self):
        invite = self.app.store.create_invite()
        status, _ = self.call("POST", "/v1/register", {"invite": invite, "username": "anna",
                                                       "identity": b64(b"x" * 32), "push": "https://fcm.googleapis.com/x"})
        self.assertEqual(status, 400)

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
        bernd_dev, bernd = self.account("bernd", push=NTFY + "bernd-geheim")
        status, data = self.call("POST", "/v1/messages", {"to": [bernd_dev], "blob": b64(b"\x00mls")}, token=anna)
        self.assertEqual((status, data["delivered"]), (200, [bernd_dev]))
        self.assertEqual(self.pushes, [NTFY + "bernd-geheim"])
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

    def test_push_text_has_no_sender_and_no_content(self):
        self.assertEqual(PUSH_TEXT, "Neue Nachricht")

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


if __name__ == "__main__":
    unittest.main()
