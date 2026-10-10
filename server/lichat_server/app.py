"""LiChat server, step 1 (single conversations): a small JSON API over WSGI, Python standard library only.

The server is deliberately "dumb": it relays end-to-end encrypted MLS blobs between devices and forgets
them once fetched. Endpoints (all JSON, blobs base64):

    POST   /v1/register             {invite, username, identity}              -> {device, token}
    GET    /v1/users/<name>         directory: devices + identity keys        (signed in)
    POST   /v1/devices              {identity} another own device             -> {device, token}
    DELETE /v1/devices/me           sign this device out, mailbox goes too
    POST   /v1/devices/me/token     a new token for this device, the old one is void at once -> {token}
    POST   /v1/key-packages         {key_packages: [b64, …]}                  -> {count}
    POST   /v1/key-packages/<name>  claim one package per device of <name> ({device}: only that one) -> {key_packages: [...]}  (10/h per claimer)
    PUT    /v1/key-packages/last-resort {key_package}  kept, handed out when the supply is empty
    POST   /v1/messages             {to: [device, …], blob}                   -> {delivered, unknown, full}
    GET    /v1/messages[?warten=s]  own mailbox; with warten (1–60) the answer waits until something
                                     arrives or the time is up (the apps' standing connection) -> {messages: [...]}
    POST   /v1/messages/ack         {ids: [...]}  delivered, delete now
    PUT    /v1/anhaenge             raw encrypted bytes (≤ 100 MB, 1 GB per device) -> {id}   (card 9780f3a0)
    PUT    /v1/anhaenge?einmal=<n>  a one-time picture for n other people (fe9f2029): gone after 14 days unopened
    GET    /v1/anhaenge/<id>        the bytes again (signed in; gone after 30 days; a one-time one: 410 once I opened it)
    POST   /v1/anhaenge/<id>/gesehen  I opened the one-time picture – deleted when every other person has
    POST   /v1/kopplung             a new device asks to join an account       -> {code}   (card da5cbfcb)
    GET    /v1/kopplung/<code>      {} waiting | {username} accepted | {username, device} signed in   (404: gone)
    POST   /v1/kopplung/annehmen    {code}  an existing device: that is my new device  (signed in; wrong codes limited)
    POST   /v1/kopplung/<code>/geraet {identity}  the new device signs in, once -> {device, token, username}
    GET    /v1/devices              my own devices with the time they signed in (no names on the server)
    DELETE /v1/devices/<id>         sign out another of my own devices (signed in)
    POST   /v1/pins                 a contact PIN for me (10 min, once)        -> {pin, gueltig}   (card 89aae55d)
    POST   /v1/einladungen          an invitation by me for someone new (7 days, at most 5 open) -> {code, link, gueltig}
                                    (Olaf 10.10.: invite by QR code; /v1/register then answers {…, von: me})
    POST   /v1/pins/einloesen       {pin} -> {username}  (wrong PINs: 10 per 15 min and device)
    GET    /e/<code>                an invitation as a page: „In LiChat öffnen“, QR code, the code to type (card 4fe6d0ee)
    GET    /v1/health

Signed in = header "Authorization: Bearer <token>" (the device token from register).

Instant delivery (Olaf 09.10.: „wie bei einem echten Messenger“): every app keeps one waiting GET /v1/messages?warten=…
open, like Signal without Google. No push service, no third party learns when someone gets a message.
"""

import base64
import binascii
import json
import os
import re
import secrets
import threading
import time
import urllib.parse
from collections import defaultdict, deque

from .store import ATTACHMENT_BYTES, Store, invite_key

USERNAME = re.compile(r"^[a-z0-9._-]{2,32}$")
DEVICE = re.compile(r"^[0-9a-f]{16}$")
MAX_BLOB = 256 * 1024          # one MLS message; attachments come later as separate encrypted uploads
MAX_IDENTITY = 1024
MAX_BODY = 4 * 1024 * 1024
MAX_WAIT = 60                  # seconds one waiting fetch may stay open (nginx: proxy_read_timeout above it)


class Limiter:
    """Sliding-window limit per key (like LiNotes)."""

    def __init__(self, limit, window, clock=time.time):
        self.limit, self.window, self.clock = limit, window, clock
        self.events = defaultdict(deque)
        self.lock = threading.Lock()

    def hit(self, key):
        with self.lock:
            events, now = self.events[key], self.clock()
            while events and events[0] < now - self.window:
                events.popleft()
            if len(events) >= self.limit:
                return False
            events.append(now)
            return True


def name_candidates(username, first="", last=""):
    """Alternatives for a taken user name (ebc3d629): from the real name first (anna.mueller, anna.m, a.mueller,
    annamueller), then the name with a short number – all within USERNAME."""
    def clean(text):
        text = text.strip().lower()
        for a, b in (("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("ß", "ss")):
            text = text.replace(a, b)
        return re.sub(r"[^a-z0-9]+", "", text)
    first, last = clean(first), clean(last)
    found = []
    if first and last:
        found += [f"{first}.{last}", f"{first}.{last[0]}", f"{first[0]}.{last}", f"{first}{last}", f"{first}_{last}"]
    base = username.strip("._-")[:28] or first or "lichat"
    found += [f"{base}{n}" for n in (2, 3, 7, 12, 24, 77)]
    found += [f"{base}.{secrets.randbelow(900) + 100}" for _ in range(3)]
    return [n for n in dict.fromkeys(found) if USERNAME.fullmatch(n) and n != username]


class HttpError(Exception):
    def __init__(self, status, message, extra=None):
        super().__init__(message)
        self.status, self.message, self.extra = status, message, extra or {}


class Doorbell:
    """Rings for a device when something lands in its mailbox – the waiting fetch of that device wakes up."""

    def __init__(self):
        self.condition = threading.Condition()
        self.rings = defaultdict(int)

    def count(self, device):
        with self.condition:
            return self.rings[device]

    def ring(self, devices):
        with self.condition:
            for device in devices:
                self.rings[device] += 1
            self.condition.notify_all()

    def wait(self, device, seen, seconds):
        with self.condition:
            return self.condition.wait_for(lambda: self.rings[device] != seen, timeout=seconds)


def b64(data):
    return base64.b64encode(data).decode()


def unb64(text, limit):
    if not isinstance(text, str):
        raise HttpError(400, "blob fehlt")
    try:
        data = base64.b64decode(text, validate=True)
    except (binascii.Error, ValueError):
        raise HttpError(400, "blob ist kein base64")
    if not data or len(data) > limit:
        raise HttpError(413, "blob zu groß oder leer")
    return data


class App:
    def __init__(self, data_dir=None, clock=time.time, trusted_proxies=None):
        self.store = Store(data_dir or os.environ.get("LICHAT_DATA", "./data"), clock=clock)
        self.doorbell = Doorbell()
        # X-Real-IP only from our own reverse proxy (Michelle ddb9b934): LICHAT_TRUSTED_PROXIES="127.0.0.1" on the Pi.
        proxies = os.environ.get("LICHAT_TRUSTED_PROXIES", "") if trusted_proxies is None else trusted_proxies
        self.trusted_proxies = {p.strip() for p in proxies.split(",") if p.strip()}
        self.register_limit = Limiter(10, 3600, clock)
        # Claiming key packages: per claimer and user, so nobody empties a supply (3c7ea47d).
        self.claim_limit = Limiter(10, 3600, clock)
        self.send_limit = Limiter(300, 600, clock)
        self.auth_failures = Limiter(20, 900, clock)
        self.pin_failures = Limiter(10, 900, clock)
        self.link_limit = Limiter(20, 3600, clock)          # new link codes per address
        self.link_failures = Limiter(10, 900, clock)        # wrong codes typed on an existing device
        self.public = (os.environ.get("LICHAT_OEFFENTLICH") or "https://lichat.lisoftware.de").rstrip("/")
        self.routes = [
            ("POST", r"/v1/register", self.register, False),
            ("GET", r"/v1/users/(?P<name>[^/]+)", self.user, True),
            ("POST", r"/v1/devices", self.new_device, True),
            ("DELETE", r"/v1/devices/me", self.remove_me, True),
            ("POST", r"/v1/devices/me/token", lambda env, body, me: {"token": self.store.renew_token(me[0])}, True),
            ("GET", r"/v1/devices", lambda env, body, me: {"devices": self.store.own_devices(me[1])}, True),
            ("DELETE", r"/v1/devices/(?P<device>[0-9a-f]{16})", self.remove_own_device, True),
            ("POST", r"/v1/kopplung", self.link_open, False),
            ("POST", r"/v1/kopplung/annehmen", self.link_accept, True),
            ("GET", r"/v1/kopplung/(?P<code>[A-Za-z0-9-]{4,20})", self.link_state, False),
            ("POST", r"/v1/kopplung/(?P<code>[A-Za-z0-9-]{4,20})/geraet", self.link_finish, False),
            ("POST", r"/v1/key-packages", self.upload_key_packages, True),
            ("POST", r"/v1/key-packages/(?P<name>[^/]+)", self.claim_key_packages, True),
            ("PUT", r"/v1/key-packages/last-resort", self.upload_last_resort, True),
            ("POST", r"/v1/messages", self.send, True),
            ("GET", r"/v1/messages", self.mailbox, True),
            ("POST", r"/v1/messages/ack", self.ack, True),
            ("GET", r"/v1/health", lambda env, body, me: {"ok": True}, False),
            ("PUT", r"/v1/anhaenge", self.put_attachment, True),
            ("POST", r"/v1/pins", self.new_pin, True),
            ("POST", r"/v1/einladungen", self.new_invite, True),
            ("POST", r"/v1/pins/einloesen", self.redeem_pin, True),
            ("GET", r"/e/(?P<code>[A-Za-z0-9-]{4,40})", self.invite_page, False),
            ("GET", r"/v1/anhaenge/(?P<attachment>[0-9a-f]{32})", self.get_attachment, True),
            ("POST", r"/v1/anhaenge/(?P<attachment>[0-9a-f]{32})/gesehen", self.seen_once, True),
        ]

    # WSGI ----------------------------------------------------------------------------------------

    def __call__(self, environ, start_response):
        try:
            status, payload = 200, self.dispatch(environ)
        except HttpError as error:
            status, payload = error.status, {"error": error.message, **error.extra}
        if isinstance(payload, Page):
            data = payload.html.encode()
            start_response("200 OK", [("Content-Type", "text/html; charset=utf-8"), ("Content-Length", str(len(data))),
                                      ("Cache-Control", "no-store"), ("Referrer-Policy", "no-referrer"),
                                      ("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:")])
            return [data]
        if isinstance(payload, Raw):
            start_response("200 OK", [("Content-Type", "application/octet-stream"), ("Content-Length", str(payload.size)),
                                      ("Cache-Control", "no-store")])
            return payload
        data = json.dumps(payload).encode()
        reason = {200: "OK", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 404: "Not Found",
                  405: "Method Not Allowed", 409: "Conflict", 413: "Payload Too Large",
                  429: "Too Many Requests"}.get(status, "Error")
        start_response(f"{status} {reason}", [("Content-Type", "application/json"), ("Content-Length", str(len(data))),
                                              ("Cache-Control", "no-store")])
        return [data]

    def dispatch(self, environ):
        method, path = environ["REQUEST_METHOD"], environ.get("PATH_INFO", "")
        allowed = False
        for verb, pattern, handler, signed_in in self.routes:
            match = re.fullmatch(pattern, path)
            if not match:
                continue
            allowed = True
            if verb != method:
                continue
            me = self.authenticate(environ) if signed_in else None
            body = {} if handler == self.put_attachment else self.body(environ, method)
            return handler(environ, body, me, **match.groupdict())
        raise HttpError(405 if allowed else 404, "unbekannt")

    def address(self, environ):
        remote = environ.get("REMOTE_ADDR") or "?"
        if remote in self.trusted_proxies and environ.get("HTTP_X_REAL_IP"):
            return environ["HTTP_X_REAL_IP"]
        return remote

    def authenticate(self, environ):
        header = environ.get("HTTP_AUTHORIZATION", "")
        if not header.startswith("Bearer "):
            raise HttpError(401, "nicht angemeldet")
        me = self.store.device_for_token(header[7:])
        if me is None:
            if not self.auth_failures.hit(self.address(environ)):
                raise HttpError(429, "zu viele Versuche")
            raise HttpError(401, "nicht angemeldet")
        return me

    @staticmethod
    def body(environ, method):
        if method not in ("POST", "PUT"):
            return {}
        try:
            length = int(environ.get("CONTENT_LENGTH") or 0)
        except ValueError:
            length = 0
        if length > MAX_BODY:
            raise HttpError(413, "zu groß")
        raw = environ["wsgi.input"].read(length) if length else b""
        try:
            data = json.loads(raw or b"{}")
        except ValueError:
            raise HttpError(400, "kein JSON")
        if not isinstance(data, dict):
            raise HttpError(400, "kein JSON-Objekt")
        return data

    # Handlers ------------------------------------------------------------------------------------

    def check_identity(self, identity):
        if not isinstance(identity, str) or not 16 <= len(identity) <= MAX_IDENTITY:
            raise HttpError(400, "Schlüssel fehlt")
        unb64(identity, MAX_IDENTITY)
        return identity

    def register(self, environ, body, me):
        if not self.register_limit.hit(self.address(environ)):
            raise HttpError(429, "zu viele Versuche")
        username = str(body.get("username", ""))
        if not USERNAME.fullmatch(username):
            raise HttpError(400, "Benutzername: 2–32 Zeichen a–z, 0–9, . _ -")
        identity = self.check_identity(body.get("identity"))
        inviter = self.store.inviter(str(body.get("invite", "")))
        try:
            device, token = self.store.register(str(body.get("invite", "")), username, identity)
        except KeyError:
            raise HttpError(403, "Einladung ungültig")
        except ValueError:
            # ebc3d629: free alternatives – only reached with a valid invitation and under the register limit (10/h)
            raise HttpError(409, "Benutzername vergeben", {"vorschlaege": self.store.free_names(
                name_candidates(username, str(body.get("vorname", "")), str(body.get("nachname", ""))))})
        answer = {"device": device, "token": token}
        if inviter:
            answer["von"] = inviter          # the new person lands in the conversation with whoever invited them
        return answer

    def user(self, environ, body, me, name):
        devices = self.store.devices(name)
        if not devices:
            raise HttpError(404, "unbekannt")
        return {"username": name, "devices": devices}

    def new_device(self, environ, body, me):
        device, token = self.store.add_device(me[1], self.check_identity(body.get("identity")))
        return {"device": device, "token": token}

    def remove_own_device(self, environ, body, me, device):
        if device == me[0]:
            raise HttpError(400, "Dieses Gerät meldet sich mit DELETE /v1/devices/me ab")
        if not self.store.remove_own_device(me[1], device):
            raise HttpError(404, "kein Gerät von dir")
        return {"ok": True}

    def link_open(self, environ, body, me):
        if not self.link_limit.hit(self.address(environ)):
            raise HttpError(429, "zu viele Versuche")
        return {"code": self.store.link_open(), "gueltig": 600}

    def link_accept(self, environ, body, me):
        if not self.store.link_accept(str(body.get("code", "")), me[1]):
            if not self.link_failures.hit(me[0]):
                raise HttpError(429, "zu viele falsche Codes – bitte später")
            raise HttpError(404, "Code unbekannt, abgelaufen oder schon benutzt")
        return {"ok": True}

    def link_state(self, environ, body, me, code):
        state = self.store.link_state(code)
        if state is None:
            raise HttpError(404, "Code unbekannt oder abgelaufen")
        return state

    def link_finish(self, environ, body, me, code):
        done = self.store.link_finish(code, self.check_identity(body.get("identity")))
        if done is None:
            raise HttpError(409, "Noch nicht angenommen oder schon benutzt")
        return {"device": done[0], "token": done[1], "username": done[2]}

    def remove_me(self, environ, body, me):
        self.store.remove_device(me[0])
        return {"ok": True}

    def upload_key_packages(self, environ, body, me):
        packages = body.get("key_packages")
        if not isinstance(packages, list) or not packages:
            raise HttpError(400, "key_packages fehlt")
        blobs = [unb64(p, MAX_BLOB) for p in packages]
        try:
            return {"count": self.store.add_key_packages(me[0], blobs)}
        except ValueError:
            raise HttpError(409, "zu viele Key-Packages")

    def upload_last_resort(self, environ, body, me):
        self.store.set_last_resort(me[0], unb64(body.get("key_package"), MAX_BLOB))
        return {"ok": True}

    def claim_key_packages(self, environ, body, me, name):
        if not self.store.devices(name):
            raise HttpError(404, "unbekannt")
        if not self.claim_limit.hit((me[0], name)):
            raise HttpError(429, "zu viele Abrufe")
        return {"key_packages": [{"device": p["device"], "key_package": b64(p["key_package"]), "last_resort": p.get("last_resort", False)}
                                 for p in self.store.claim_key_packages(name, only=str(body.get("device") or "") or None)]}

    def send(self, environ, body, me):
        if not self.send_limit.hit(me[0]):
            raise HttpError(429, "zu viele Nachrichten")
        to = body.get("to")
        if not isinstance(to, list) or not to or len(to) > 1000 or not all(
                isinstance(d, str) and DEVICE.fullmatch(d) for d in to):
            raise HttpError(400, "Empfänger fehlen")
        known, unknown, full = self.store.deliver(sorted(set(to)), unb64(body.get("blob"), MAX_BLOB))
        self.doorbell.ring(known)
        return {"delivered": known, "unknown": unknown, "full": full}

    def mailbox(self, environ, body, me):
        query = urllib.parse.parse_qs(environ.get("QUERY_STRING", ""))
        try:
            wait = min(max(int(query.get("warten", ["0"])[0]), 0), MAX_WAIT)
        except ValueError:
            raise HttpError(400, "warten: Sekunden")
        seen = self.doorbell.count(me[0])      # before looking: a delivery in between is not missed
        found = self.store.fetch(me[0])
        if not found and wait and self.doorbell.wait(me[0], seen, wait):
            found = self.store.fetch(me[0])
        return {"messages": [{"id": m["id"], "blob": b64(m["blob"]), "received": m["received"]} for m in found]}

    def put_attachment(self, environ, body, me):
        try:
            size = int(environ.get("CONTENT_LENGTH") or 0)
        except ValueError:
            size = 0
        if size <= 0:
            raise HttpError(400, "Anhang leer")
        if size > ATTACHMENT_BYTES:
            raise HttpError(413, "Anhang zu groß (höchstens 100 MB)")
        if not self.send_limit.hit(me[0]):
            raise HttpError(429, "zu viele Anhänge")
        try:
            attachment = self.store.keep_attachment(me[0], environ["wsgi.input"], size)
        except ValueError:
            raise HttpError(413, "Speicher für Anhänge voll – ältere verschwinden nach 30 Tagen")
        except EOFError:
            raise HttpError(400, "Anhang unvollständig")
        people = urllib.parse.parse_qs(environ.get("QUERY_STRING", "")).get("einmal", [""])[0]
        if people:
            self.store.keep_once(attachment, max(1, min(1000, int(people) if people.isdigit() else 1)))
        return {"id": attachment}

    def get_attachment(self, environ, body, me, attachment):
        found = self.store.attachment(attachment)
        if found is None:
            raise HttpError(404, "Anhang nicht mehr da")
        if self.store.once_seen_by(attachment, me[1]):
            raise HttpError(410, "Schon angesehen")
        return Raw(*found)

    def seen_once(self, environ, body, me, attachment):
        if not self.store.seen_once(attachment, me[1]):
            raise HttpError(404, "Kein Einmal-Bild")
        return {}

    def new_invite(self, environ, body, me):
        """Someone already here invites someone new (Olaf 10.10.): the QR code carries server and code, the new person
        only types their name. At most 5 open per person, valid for 7 days – the register limit stays as it is."""
        note = f"von:{me[1]}"
        if self.store.open_invites(note) >= 5:
            raise HttpError(429, "Höchstens 5 offene Einladungen – eine davon muss erst genutzt werden oder ablaufen")
        code = self.store.create_invite(note, days=7)
        return {"code": code, "link": f"{self.public}/e/{code}", "gueltig": 7 * 86400}

    def new_pin(self, environ, body, me):
        return {"pin": self.store.create_pin(me[1]), "gueltig": 600}

    def redeem_pin(self, environ, body, me):
        username = self.store.redeem_pin(str(body.get("pin", "")))
        if username is None:
            if not self.pin_failures.hit(me[0]):
                raise HttpError(429, "zu viele falsche PINs – bitte später")
            raise HttpError(404, "PIN unbekannt oder abgelaufen")
        return {"username": username}

    def invite_page(self, environ, body, me, code):
        """The invitation as a page (QR, link, code). Says nothing about whether the code is valid – no oracle."""
        import html
        from urllib.parse import quote
        from .qrcodegen import QrCode
        code = invite_key(code)
        link = f"{self.public}/e/{code}"
        app = f"lichat://einladung?server={quote(self.public, safe='')}&code={code}"
        qr = QrCode.encode_text(link, QrCode.Ecc.MEDIUM)
        cells = "".join(f'<rect x="{x + 4}" y="{y + 4}" width="1" height="1"/>' for y in range(qr.get_size()) for x in range(qr.get_size()) if qr.get_module(x, y))
        size = qr.get_size() + 8
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="240" height="240" shape-rendering="crispEdges">'
               f'<rect width="100%" height="100%" fill="#fff"/><g fill="#000">{cells}</g></svg>')
        import base64 as b
        image = "data:image/svg+xml;base64," + b.b64encode(svg.encode()).decode()
        return Page(f"""<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Einladung zu LiChat</title><style>body{{font:17px -apple-system,Inter,sans-serif;text-align:center;margin:40px 16px;color:#1d1d1f}}
a.b{{display:inline-block;background:#0a7aff;color:#fff;padding:12px 22px;border-radius:12px;text-decoration:none;font-weight:600}}
code{{font-size:22px;letter-spacing:2px}} p{{color:#6e6e73}}</style></head><body>
<h1>Einladung zu LiChat</h1><p>Ende-zu-Ende verschlüsselt, ohne Telefonnummer.</p>
<p><a class="b" href="{html.escape(app)}">In LiChat öffnen</a></p>
<p>Oder mit LiChat scannen:</p><img alt="QR-Code der Einladung" src="{image}">
<p>Zum Abtippen: <code>{html.escape(code)}</code><br>Server: {html.escape(self.public)}</p></body></html>""")

    def ack(self, environ, body, me):
        ids = body.get("ids")
        if not isinstance(ids, list) or not all(isinstance(i, int) for i in ids):
            raise HttpError(400, "ids fehlen")
        self.store.acknowledge(me[0], ids)
        return {"ok": True}


class Page:
    def __init__(self, html):
        self.html = html


class Raw:
    """A file answered as bytes, read in pieces (an attachment can be 100 MB)."""

    def __init__(self, path, size):
        self.path, self.size = path, size

    def __iter__(self):
        with open(self.path, "rb") as f:
            while chunk := f.read(1 << 20):
                yield chunk


def create_app(data_dir=None, **kwargs):
    return App(data_dir, **kwargs)
