"""LiChat server, step 1 (single conversations): a small JSON API over WSGI, Python standard library only.

The server is deliberately "dumb": it relays end-to-end encrypted MLS blobs between devices and forgets
them once fetched. Endpoints (all JSON, blobs base64):

    POST   /v1/register             {invite, username, identity, push?}      -> {device, token}
    GET    /v1/users/<name>         directory: devices + identity keys        (signed in)
    POST   /v1/devices              {identity, push?} another own device      -> {device, token}
    DELETE /v1/devices/me           sign this device out, mailbox goes too
    POST   /v1/key-packages         {key_packages: [b64, …]}                  -> {count}
    POST   /v1/key-packages/<name>  claim one package per device of <name>    -> {key_packages: [...]}  (10/h per claimer)
    PUT    /v1/key-packages/last-resort {key_package}  kept, handed out when the supply is empty
    POST   /v1/messages             {to: [device, …], blob}                   -> {delivered, unknown, full}
    GET    /v1/messages             own mailbox                               -> {messages: [...]}
    POST   /v1/messages/ack         {ids: [...]}  delivered, delete now
    GET    /v1/health

Signed in = header "Authorization: Bearer <token>" (the device token from register). A push target is an
ntfy topic URL on our own ntfy; the push only says "Neue Nachricht" – no sender, no text (test plan S4).
"""

import base64
import binascii
import json
import os
import re
import threading
import time
import urllib.request
from collections import defaultdict, deque

from .store import Store

USERNAME = re.compile(r"^[a-z0-9._-]{2,32}$")
DEVICE = re.compile(r"^[0-9a-f]{16}$")
MAX_BLOB = 256 * 1024          # one MLS message; attachments come later as separate encrypted uploads
MAX_IDENTITY = 1024
MAX_BODY = 4 * 1024 * 1024
PUSH_TEXT = "Neue Nachricht"


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


class HttpError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status, self.message = status, message


def ntfy_push(url):
    """Default notifier: a content-free nudge to the device's own ntfy topic."""
    request = urllib.request.Request(url, data=PUSH_TEXT.encode(), method="POST",
                                     headers={"Title": "LiChat", "Priority": "default"})
    try:
        urllib.request.urlopen(request, timeout=5).close()
    except OSError:
        pass   # push is only a hint; the app also fetches on its own


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
    def __init__(self, data_dir=None, notifier=ntfy_push, clock=time.time, push_hosts=None, trusted_proxies=None):
        self.store = Store(data_dir or os.environ.get("LICHAT_DATA", "./data"), clock=clock)
        self.notifier = notifier
        # Push only to our own ntfy (no third parties); LICHAT_PUSH_HOSTS="https://ntfy.example/,…"
        hosts = push_hosts if push_hosts is not None else os.environ.get("LICHAT_PUSH_HOSTS", "")
        self.push_hosts = [h.strip() for h in hosts.split(",") if h.strip()]
        # X-Real-IP only from our own reverse proxy (Michelle ddb9b934): LICHAT_TRUSTED_PROXIES="127.0.0.1" on the Pi.
        proxies = os.environ.get("LICHAT_TRUSTED_PROXIES", "") if trusted_proxies is None else trusted_proxies
        self.trusted_proxies = {p.strip() for p in proxies.split(",") if p.strip()}
        self.register_limit = Limiter(10, 3600, clock)
        # Claiming key packages: per claimer and user, so nobody empties a supply (3c7ea47d).
        self.claim_limit = Limiter(10, 3600, clock)
        self.send_limit = Limiter(300, 600, clock)
        self.auth_failures = Limiter(20, 900, clock)
        self.routes = [
            ("POST", r"/v1/register", self.register, False),
            ("GET", r"/v1/users/(?P<name>[^/]+)", self.user, True),
            ("POST", r"/v1/devices", self.new_device, True),
            ("DELETE", r"/v1/devices/me", self.remove_me, True),
            ("POST", r"/v1/key-packages", self.upload_key_packages, True),
            ("POST", r"/v1/key-packages/(?P<name>[^/]+)", self.claim_key_packages, True),
            ("PUT", r"/v1/key-packages/last-resort", self.upload_last_resort, True),
            ("POST", r"/v1/messages", self.send, True),
            ("GET", r"/v1/messages", self.mailbox, True),
            ("POST", r"/v1/messages/ack", self.ack, True),
            ("GET", r"/v1/health", lambda env, body, me: {"ok": True}, False),
        ]

    # WSGI ----------------------------------------------------------------------------------------

    def __call__(self, environ, start_response):
        try:
            status, payload = 200, self.dispatch(environ)
        except HttpError as error:
            status, payload = error.status, {"error": error.message}
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
            return handler(environ, self.body(environ, method), me, **match.groupdict())
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

    def check_push(self, push):
        if push is None:
            return None
        if not isinstance(push, str) or not any(push.startswith(h) for h in self.push_hosts):
            raise HttpError(400, "Push nur über den eigenen ntfy")
        return push

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
        identity, push = self.check_identity(body.get("identity")), self.check_push(body.get("push"))
        try:
            device, token = self.store.register(str(body.get("invite", "")), username, identity, push)
        except KeyError:
            raise HttpError(403, "Einladung ungültig")
        except ValueError:
            raise HttpError(409, "Benutzername vergeben")
        return {"device": device, "token": token}

    def user(self, environ, body, me, name):
        devices = self.store.devices(name)
        if not devices:
            raise HttpError(404, "unbekannt")
        return {"username": name, "devices": devices}

    def new_device(self, environ, body, me):
        device, token = self.store.add_device(me[1], self.check_identity(body.get("identity")),
                                              self.check_push(body.get("push")))
        return {"device": device, "token": token}

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
                                 for p in self.store.claim_key_packages(name)]}

    def send(self, environ, body, me):
        if not self.send_limit.hit(me[0]):
            raise HttpError(429, "zu viele Nachrichten")
        to = body.get("to")
        if not isinstance(to, list) or not to or len(to) > 1000 or not all(
                isinstance(d, str) and DEVICE.fullmatch(d) for d in to):
            raise HttpError(400, "Empfänger fehlen")
        known, unknown, full = self.store.deliver(sorted(set(to)), unb64(body.get("blob"), MAX_BLOB))
        for device in known:
            target = self.store.push_target(device)
            if target and device != me[0]:
                self.notifier(target)
        return {"delivered": known, "unknown": unknown, "full": full}

    def mailbox(self, environ, body, me):
        return {"messages": [{"id": m["id"], "blob": b64(m["blob"]), "received": m["received"]}
                             for m in self.store.fetch(me[0])]}

    def ack(self, environ, body, me):
        ids = body.get("ids")
        if not isinstance(ids, list) or not all(isinstance(i, int) for i in ids):
            raise HttpError(400, "ids fehlen")
        self.store.acknowledge(me[0], ids)
        return {"ok": True}


def create_app(data_dir=None, **kwargs):
    return App(data_dir, **kwargs)
