"""One LiChat account on this device: talking to the server, the MLS core, and what is kept on disk.

Shared by lichat-cli and (later) the Ubuntu app. What leaves the device are only the core's MLS bytes; the server sees
device ids and opaque blobs (docs/ARCHITEKTUR.md).

On the wire (the server's blob, opaque to it): one tag byte and the core's bytes –
    b"W" + welcome   joins a conversation
    b"M" + MLS       an application message or a commit
Inside the MLS application messages (encrypted, only members read it) a small JSON:
    {"t": "roster", "devices": {"anna": ["<device>"], …}, "title": "…"}   who gets the conversation's packets
    {"t": "text", "text": "…", "at": <unix seconds>}

On disk, in the profile folder (files 600, folder 700):
    account.json   server, user name, device id, token
    key            32 random bytes sealing the two files below – only lichat-cli; the app keeps it in the keyring
    state.bin      the core's state, sealed by the core (LICHAT1)
    chats.bin      rosters, titles and the history, sealed here (LICHATC1, AES-256-GCM)
    profile.bin    my picture and the pictures the others sent me (LICHATP1, AES-256-GCM)

Attachments (card 9780f3a0): each file gets its own random key (AES-256-GCM, LICHATA1); the server keeps only the
encrypted bytes under a random id. The MLS message carries id, key, SHA-256, name, type and kind:
    {"t": "anhang", "id", "schluessel", "sha256", "name", "mime", "groesse", "art": bild|video|sprache|datei,
     "dauer"?, "vorschau"? (small JPEG, also without EXIF)}
Photos leave without EXIF and at most 2048 px, like Messages sends them.
    "einmal": true   one-time picture (fe9f2029): no preview, opened once on one of my devices, then gone everywhere –
                     here, on my other devices ({"t": "einmal_geoeffnet", "mid"} into the chat) and on the server
                     (POST /v1/anhaenge/<id>/gesehen); unopened it lapses after 14 days. {"t": "bildschirmfoto", "mid"}
                     puts a fixed line into the chat when the phone saw a screenshot while it was open.
    "geheim": true   secret picture (f9e3f055): pixelated everywhere (made here, from the picture), a tap shows it sharp.

Delivery state (card db70d358, Olaf's scheme with Jaques): every message carries a „mid“. Devices that received it
answer end-to-end {"t": "quittung", "art": "zugestellt" | "gelesen", "mids": […], "at"} – the server learns nothing.
„gelesen“ only when read receipts are on; they are off by default and mutual (whoever has them off sends none and sees
none, like the iPhone). Under each own bubble: ◷ wird gesendet · ✓ gesendet · ✓✓ zugestellt · ✓✓ blau gelesen.

Replies and reactions (Olaf 09.10.: swipe to answer, long press for an emoji): a text may carry
    "antwort": {"mid", "from", "text"}   – the message it answers, with a short quote (also only end-to-end)
and {"t": "reaktion", "mid", "emoji"} sets the sender's one reaction on a message ("" removes it).

Display names (Olaf 09.10.: „groß und Kleinschreibung … vor und Nachnamen“): {"t": "name", "vorname", "nachname"}
travels like the picture, only inside the conversations; the user name stays the lower-case handle on the server.

Contacts (card 89aae55d): kept only on the device (sealed in profile.bin). A contact QR / link carries user name, server
and the fingerprint of the person's identity keys – scanning it checks the fingerprint against the directory, so the
contact is „verifiziert“ (nobody slipped in other keys). A PIN (10 minutes, once) only names the user: not verified.
    lichat://kontakt?server=…&name=…&fp=…        lichat://einladung?server=…&code=…   (or https://<server>/e/<code>)

Profile pictures (Michelle B7) travel only inside the conversations, end-to-end like a text: {"t": "profile", "bild":
<JPEG base64>}. Made square, 512 px, JPEG, without EXIF (no place, no camera, Michelle 09.10.) before they leave.
"""

import base64
import hashlib
import io
import re
import secrets
import socket
import ssl
import fcntl
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

try:
    from . import lichat_core
except ImportError:  # run from inside the folder
    import lichat_core

WELCOME, MLS = b"W", b"M"
CHATS_MAGIC = b"LICHATC1"
PROFILE_MAGIC = b"LICHATP1"
TOKEN_MAGIC = b"LICHATT1"     # the device's server token, sealed like the rest (Michelle f09a8086)
PICTURE_PX = 512
PICTURE_MAX = 150 * 1024   # a received picture larger than this is not kept
ATTACHMENT_MAGIC = b"LICHATA1"
ATTACHMENT_MAX = 100 * 1024 * 1024 - 64
OUTBOX = "ausgang-"        # an attachment's local id until it is up (cf647f61)
PHOTO_PX = 2048
KINDS = ("bild", "video", "sprache", "datei")
ONCE_DAYS = 14             # an unopened one-time picture lapses (the server deletes it too)
TYPING_EVERY = 3     # s between two „tippt“ of mine
TYPING_FRESH = 6     # s a „tippt“ stays shown after it arrived
TYPING_LATE = 30     # s after which an arriving „tippt“ is ignored (fetched late; allows for clocks a little apart)
EDIT_WINDOW = 15 * 60   # s – like iOS: a message can be edited for 15 minutes …
EDIT_MAX = 5            # … at most five times
UNSEND_WINDOW = 2 * 60  # s – and called back for two minutes
LATE = 60               # s of slack for clocks a little apart (only the sender's own times are compared)
TYPING_PACE = 3.5    # characters per second with thumbs on a phone (Olaf 09.10.: everyone types as long as the text is)
TYPING_LONGEST = 180 # s – nobody types longer than this, however long the text
SUPPLY = 20          # key packages uploaded at the start
TIMEOUT = 20


class UploadCancelled(Exception):
    """The upload was stopped from the bubble (cf647f61): the bubble goes."""


class LiChatError(Exception):
    """Something the user is told plainly (server refused, unknown chat, wrong key package …). [suggestions]: free user
    names when the wanted one is taken (ebc3d629); [detail]: the raw cause, for the log only (8e7a493b)."""

    def __init__(self, message="", suggestions=None, detail=None):
        super().__init__(message)
        self.suggestions = list(suggestions or [])
        self.detail = detail


def unreachable(error):
    """No way to the server: one plain sentence per kind – the raw text (public IP, port, own address) never on the
    screen, only as detail (Michelle 8e7a493b)."""
    reason = getattr(error, "reason", error)
    if isinstance(reason, socket.gaierror):
        text = "Server nicht gefunden. Besteht eine Internetverbindung?"
    elif isinstance(reason, ssl.SSLError):
        text = "Keine sichere Verbindung zum Server möglich."
    else:
        text = "Keine Verbindung zum Server. LiChat versucht es gleich wieder."
    return LiChatError(text, detail=repr(reason))


def b64(data):
    return base64.b64encode(data).decode()


def unb64(text):
    return base64.b64decode(text)


def short(conversation):
    """The id shown to people: the first 8 hex digits of the MLS group id."""
    return conversation.hex()[:8]


MENTION = re.compile(r"(?<![\w@])@([a-z0-9][a-z0-9._-]{1,31})", re.IGNORECASE)
EVERYONE = ("alle", "all")


LINK_WEB = re.compile(r"""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
LINK_MAIL = re.compile(r"(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b")
LINK_PHONE = re.compile(r"(?<![\w/])\+?\d[\d \-/]{5,}\d(?![\w/])")


def find_links(text):
    """Web, mail and phone in a message (Olaf 22c918ce) as [(start, end, target)] – recognised here only, nothing is
    fetched for a preview. The twin of Kotlin's findLinks."""
    found = []

    def free(a, b):
        return all(b <= s or a >= e for s, e, _ in found)
    for m in LINK_WEB.finditer(text):
        value = m.group(0).rstrip(".,;:!?)]»“")
        found.append((m.start(), m.start() + len(value), ("https://" + value) if value.lower().startswith("www.") else value))
    for m in LINK_MAIL.finditer(text):
        if free(m.start(), m.end()):
            found.append((m.start(), m.end(), "mailto:" + m.group(0)))
    for m in LINK_PHONE.finditer(text):
        digits = sum(c.isdigit() for c in m.group(0))
        if 7 <= digits <= 15 and free(m.start(), m.end()):
            found.append((m.start(), m.end(), "tel:" + "".join(c for c in m.group(0) if c.isdigit() or c == "+")))
    return sorted(found)


def gif_without_metadata(data):
    """A GIF block by block (Michelle 999f37be): frames, colour tables, timing (graphic control) and the loop
    (NETSCAPE2.0 / ANIMEXTS1.0) stay; comments, XMP and every other application or text block go. Not a GIF: refused."""
    if data[:6] not in (b"GIF87a", b"GIF89a") or len(data) < 13:
        raise LiChatError("Kein GIF")
    out = bytearray(data[:13])
    flags = data[10]
    pos = 13
    if flags & 0x80:                                   # global colour table
        size = 3 * (2 ** ((flags & 7) + 1))
        out += data[pos:pos + size]
        pos += size

    def sub_blocks(at):
        while True:
            if at >= len(data):
                raise LiChatError("GIF unvollständig")
            length = data[at]
            at += 1 + length
            if length == 0:
                return at
    while pos < len(data):
        block = data[pos]
        if block == 0x3B:                              # trailer
            out.append(0x3B)
            return bytes(out)
        if block == 0x2C:                              # image: descriptor, local colour table, LZW data
            start = pos
            local = data[pos + 9]
            pos += 10
            if local & 0x80:
                pos += 3 * (2 ** ((local & 7) + 1))
            pos = sub_blocks(pos + 1)
            out += data[start:pos]
        elif block == 0x21:                            # extension
            label = data[pos + 1]
            end = sub_blocks(pos + 2)
            keep = label == 0xF9                       # graphic control: timing, transparency
            if label == 0xFF and data[pos + 2] == 11 and data[pos + 3:pos + 14] in (b"NETSCAPE2.0", b"ANIMEXTS1.0"):
                keep = True                            # the loop
            if keep:
                out += data[pos:end]
            pos = end
        else:
            raise LiChatError("GIF beschädigt")
    out.append(0x3B)
    return bytes(out)


def _pictographic(cp):
    """Roughly Unicode's Extended_Pictographic: the emoji blocks, the symbol ranges (❤ ✅ ‼ ⁉ ™ …), © ®, minus letters."""
    import unicodedata
    if cp >= 0x1F000 or cp in (0x00A9, 0x00AE, 0x3030, 0x303D, 0x3297, 0x3299, 0x2139):
        return True
    return 0x2000 <= cp <= 0x2BFF and unicodedata.category(chr(cp))[0] not in "LNZ"


def single_emoji(text):
    """Exactly one emoji – also as a sequence (👍🏽, 👩‍👩‍👧‍👦, 🇫🇷, 1️⃣, ❤️) – and nothing else (Michelle 09.10.: a reaction must
    not be „Gelesen“ or „OK“, else anyone could fake a status under a bubble)."""
    cps = [ord(c) for c in text]
    if not cps or len(text) > 16:
        return False
    joins = lambda cp: cp in (0xFE0F, 0xFE0E, 0x20E3) or 0x1F3FB <= cp <= 0x1F3FF or 0xE0020 <= cp <= 0xE007F  # noqa: E731
    regional = lambda cp: 0x1F1E6 <= cp <= 0x1F1FF  # noqa: E731
    first = cps[0]
    keycap = (first in range(0x30, 0x3A) or first in (0x23, 0x2A)) and len(cps) > 1 and cps[1] in (0xFE0F, 0x20E3)
    if not keycap and not _pictographic(first):
        return False
    end = 1
    if regional(first):
        if len(cps) < 2 or not regional(cps[1]):
            return False
        end = 2
    while end < len(cps):
        if joins(cps[end]):
            end += 1
        elif cps[end] == 0x200D and end + 1 < len(cps) and _pictographic(cps[end + 1]):
            end += 2
        else:
            return False
    return True


def default_folder(profile):
    base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
    return base / "lichat-cli" / profile


def seal_file(path, key, magic, data):
    """magic + nonce + AES-GCM(JSON) – the format of chats.bin, profile.bin and token.bin."""
    nonce = os.urandom(12)
    write_private(path, magic + nonce + AESGCM(key).encrypt(nonce, json.dumps(data, ensure_ascii=False).encode(), magic))


def write_private(path, data):
    """Written whole or not at all, readable only by the owner."""
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def check_server(url):
    """https only – except for a server on this computer (tests, trying it out)."""
    parsed = urllib.parse.urlparse(url)
    local = parsed.hostname in ("127.0.0.1", "localhost", "::1")
    if parsed.scheme != "https" and not (parsed.scheme == "http" and local):
        raise LiChatError("Server nur über https (http nur auf diesem Rechner)")
    return url.rstrip("/")


class Server:
    def __init__(self, url, token=None):
        self.url, self.token = check_server(url), token

    def raw(self, method, path, data=None, timeout=120, progress=None, cancelled=None):
        """Bytes in, bytes out – the attachments (no JSON around 100 MB). Going up in 64 KB pieces: [progress](sent, total)
        after each, [cancelled]() asked before each (cf647f61)."""
        body = data
        if data is not None and (progress or cancelled):
            def pieces():
                for start in range(0, len(data), 64 * 1024):
                    if cancelled and cancelled():
                        raise UploadCancelled()
                    yield data[start:start + 64 * 1024]
                    if progress:
                        progress(min(start + 64 * 1024, len(data)), len(data))
            body = pieces()
        request = urllib.request.Request(self.url + path, data=body, method=method)
        request.add_header("Content-Type", "application/octet-stream")
        if body is not data:
            request.add_header("Content-Length", str(len(data)))
        if self.token:
            request.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            try:
                message = json.loads(error.read()).get("error", "")
            except ValueError:
                message = ""
            raise LiChatError(f"Server: {message or error.reason} ({error.code})") from None
        except (urllib.error.URLError, OSError) as error:
            raise unreachable(error) from None

    def call(self, method, path, body=None, timeout=TIMEOUT):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.url + path, data=data, method=method)
        request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            try:
                answer = json.loads(error.read())
            except ValueError:
                answer = {}
            names = [n for n in answer.get("vorschlaege") or [] if isinstance(n, str)][:3]
            raise LiChatError(f"Server: {answer.get('error') or error.reason} ({error.code})", names) from None
        except (urllib.error.URLError, OSError) as error:
            raise unreachable(error) from None


class ProfileLock:
    """A lock for one profile – threads of this process (RLock) and other processes (flock on .sperre). Taking it first
    loads what another process saved meanwhile, so nobody works on an old state and overwrites the newer one."""

    def __init__(self, account):
        self.account = account
        self.thread = threading.RLock()
        self.depth = 0
        self.fd = None

    def __enter__(self):
        self.thread.acquire()
        if self.depth == 0:
            self.fd = os.open(self.account.folder / ".sperre", os.O_CREAT | os.O_RDWR, 0o600)
            fcntl.flock(self.fd, fcntl.LOCK_EX)
            self.account._reload_if_changed()
        self.depth += 1
        return self

    def __exit__(self, *_):
        self.depth -= 1
        if self.depth == 0:
            self.account._seen = self.account._disk_mark()
            fcntl.flock(self.fd, fcntl.LOCK_UN)
            os.close(self.fd)
            self.fd = None
        self.thread.release()
        return False


class Account:
    """An account in [folder]. Use Account.register(…) once, then Account(folder) to open it. [key]: the sealing key
    from the keyring (the app); without it the key file in the folder (lichat-cli)."""

    def __init__(self, folder, key=None):
        self.folder = Path(folder)
        try:
            self.info = json.loads((self.folder / "account.json").read_text())
            self.key = key if key is not None else (self.folder / "key").read_bytes()
        except FileNotFoundError:
            raise LiChatError("Noch kein Konto – erst „register“") from None
        self.device = lichat_core.Device.load((self.folder / "state.bin").read_bytes(), self.key)
        self.chats = self._unseal("chats.bin", CHATS_MAGIC, {})
        self.profiles = self._unseal("profile.bin", PROFILE_MAGIC, {"me": None, "people": {}})
        # Held while the state changes (never during a wait at the server): the app's window reads under it too – and a
        # second lichat-cli on the same profile waits for it (Michelle 09.10.: „warten“ + „abholen“ overwrote each other).
        self.lock = ProfileLock(self)
        self._seen = self._disk_mark()
        self._owe_picture = set()   # chats that learn my picture once their roster is known
        self.uploads = {}           # mid → share sent (0…1) while an attachment goes up (cf647f61)
        self._cancelling = set()
        self._typing = {}           # chat → {sender: when their „tippt“ came} – only in memory, never saved (884f7c8a)
        self._typed = {}            # chat → when I last said „tippt“ there
        self._owe_delivered = {}    # chat → mids that arrived and still get their „zugestellt“
        self._owe_removal = {}      # chat → people who left and whose devices I take out of the MLS group
        token = self.info.get("token")
        if token:
            # An account from before 09.10. evening: the token moves out of account.json into token.bin (sealed), no new
            # sign-in (Michelle f09a8086: a copied account.json let someone fetch – and so lose – this device's packets).
            # The old token stays in backups of account.json – it gets replaced by a new one (Michelle, „erneuern“ below).
            seal_file(self.folder / "token.bin", self.key, TOKEN_MAGIC, {"token": token, "erneuern": True})
            self.info.pop("token")
            write_private(self.folder / "account.json", json.dumps(self.info, indent=1).encode())
        else:
            token = self._unseal("token.bin", TOKEN_MAGIC, {}).get("token")
            if not token:
                raise LiChatError("Anmeldedaten fehlen (token.bin) – dieses Gerät bitte neu koppeln")
        self.server = Server(self.info["server"], token)
        self._renew_token()

    def _renew_token(self):
        """After the move out of account.json: a new token from the server, the old one void – copies of the old
        account.json fetch nothing any more. Offline or an older server: tried again at the next start."""
        with self.lock:
            stored = self._unseal("token.bin", TOKEN_MAGIC, {})
            if not stored.get("erneuern"):
                self.server.token = stored.get("token") or self.server.token
                return
            try:
                fresh = self.server.call("POST", "/v1/devices/me/token", {})["token"]
            except (LiChatError, KeyError):
                return
            seal_file(self.folder / "token.bin", self.key, TOKEN_MAGIC, {"token": fresh})
            self.server.token = fresh

    # ------------------------------------------------------------------------------------------ setup

    @classmethod
    def register(cls, folder, server, invite, username, key=None, first="", last=""):
        folder = Path(folder)
        if (folder / "account.json").exists():
            raise LiChatError(f"In {folder} gibt es schon ein Konto")
        folder.mkdir(parents=True, exist_ok=True)
        os.chmod(folder, 0o700)
        anonymous = Server(server)
        device = lichat_core.Device(username)
        own_key = key is None
        key = os.urandom(32) if own_key else key
        # The real name goes along only for suggestions when the user name is taken (ebc3d629); the server keeps none of it.
        answer = anonymous.call("POST", "/v1/register", {"invite": invite, "username": username, "vorname": first[:40],
                                                         "nachname": last[:40], "identity": b64(device.identity_key())})
        info = {"server": anonymous.url, "username": username, "device": answer["device"]}
        # Key packages are made (and their private halves kept in the state) before they are offered.
        packages = device.key_packages(SUPPLY)
        reserve = device.last_resort_key_package()
        if own_key:
            write_private(folder / "key", key)
        write_private(folder / "state.bin", bytes(device.save(key)))
        seal_file(folder / "token.bin", key, TOKEN_MAGIC, {"token": answer["token"]})
        write_private(folder / "account.json", json.dumps(info, indent=1).encode())
        account = cls(folder, None if own_key else key)
        account.save()
        account.server.call("POST", "/v1/key-packages", {"key_packages": [b64(p) for p in packages]})
        account.server.call("PUT", "/v1/key-packages/last-resort", {"key_package": b64(reserve)})
        return account

    # ------------------------------------------------------------------------------------------ linking (card da5cbfcb)
    # Like Signal/WhatsApp: the NEW device shows a code (QR + 8 signs), an existing device of the person accepts it, then
    # the new device makes its keys for that name and signs in once. The existing device then puts it into every
    # conversation (MLS add); it reads what comes after – older messages stay on the other devices.

    @staticmethod
    def link_open(server):
        """New device, step 1: a code to show. Returns (server url, code)."""
        anonymous = Server(server)
        return anonymous.url, anonymous.call("POST", "/v1/kopplung", {})["code"]

    @staticmethod
    def link_uri(server, code):
        """What the new device's QR code says (Olaf: „per QR-Code koppeln“): lichat://koppeln?server=…&code=…"""
        return f"lichat://koppeln?server={urllib.parse.quote(server, safe='')}&code={code}"

    @staticmethod
    def link_state(server, code):
        """{} while waiting, {username} once an existing device accepted, {username, device} once signed in."""
        return Server(server).call("GET", f"/v1/kopplung/{urllib.parse.quote(code)}")

    @classmethod
    def link_finish(cls, folder, server, code, name="", key=None):
        """New device, step 3 (after an existing device accepted): own keys for that name, signed in once."""
        folder = Path(folder)
        if (folder / "account.json").exists():
            raise LiChatError(f"In {folder} gibt es schon ein Konto")
        anonymous = Server(server)
        username = anonymous.call("GET", f"/v1/kopplung/{urllib.parse.quote(code)}").get("username")
        if not username:
            raise LiChatError("Noch nicht angenommen – auf dem anderen Gerät „Gerät hinzufügen“ und den Code eingeben")
        folder.mkdir(parents=True, exist_ok=True)
        os.chmod(folder, 0o700)
        device = lichat_core.Device(username)
        own_key = key is None
        key = os.urandom(32) if own_key else key
        answer = anonymous.call("POST", f"/v1/kopplung/{urllib.parse.quote(code)}/geraet",
                                {"identity": b64(device.identity_key())})
        info = {"server": anonymous.url, "username": username, "device": answer["device"]}
        packages = device.key_packages(SUPPLY)
        reserve = device.last_resort_key_package()
        if own_key:
            write_private(folder / "key", key)
        write_private(folder / "state.bin", bytes(device.save(key)))
        seal_file(folder / "token.bin", key, TOKEN_MAGIC, {"token": answer["token"]})
        write_private(folder / "account.json", json.dumps(info, indent=1).encode())
        account = cls(folder, None if own_key else key)
        account.profiles["gekoppelt"] = int(time.time())
        if name:
            account.profiles["geraetename"] = str(name)[:40]     # only here – never on the server (Michelle)
        account.save()
        account.server.call("POST", "/v1/key-packages", {"key_packages": [b64(p) for p in packages]})
        account.server.call("PUT", "/v1/key-packages/last-resort", {"key_package": b64(reserve)})
        return account

    def accept_link(self, code, wait=600, poll=2.0):
        """Existing device: that code is my new device. Waits until it signed in, then puts it into every conversation.
        Returns the new device's id. [code]: as typed, or the scanned/pasted lichat://koppeln link."""
        parsed = self.parse_link(code)
        if parsed and parsed["art"] == "koppeln":
            if parsed["server"] and parsed["server"].rstrip("/") != self.info["server"].rstrip("/"):
                raise LiChatError("Das neue Gerät hat einen anderen Server gewählt")
            code = parsed["code"]
        self.server.call("POST", "/v1/kopplung/annehmen", {"code": code})
        end = time.time() + wait
        while time.time() < end:
            state = self.server.call("GET", f"/v1/kopplung/{urllib.parse.quote(code)}")
            if state.get("device"):
                self.sync_own_devices()
                self.own_group()                      # my devices' private group (holds the new one now)
                self.send_history(state["device"])    # Olaf: „synchronisieren sich einmal und bleiben dann synchronisiert“
                return state["device"]
            time.sleep(poll)
        raise LiChatError("Das neue Gerät hat sich nicht gemeldet – noch einmal versuchen")

    # ------------------------------------------------------------------------------------------ my devices' own group
    # A conversation of my devices only (marked „intern“, never shown): the history goes through it once when a device is
    # linked, and my settings follow on every change – end-to-end like everything else, the server sees opaque packets.

    HISTORY_MAGIC = b"LICHATV1"

    def own_group_id(self):
        return next((cid for cid, chat in self.chats.items() if chat.get("intern") and not chat.get("removed")), None)

    def visible_chats(self):
        """The conversations people see (without my devices' own group)."""
        return {cid: chat for cid, chat in self.chats.items() if not chat.get("intern")}

    def own_group(self):
        """My devices' own group – made when there is a second device and none yet (sync_own_devices keeps it complete)."""
        cid = self.own_group_id()
        if cid:
            return cid
        listed = self.directory(self.username)
        packages, devices = [], []
        for device in sorted(set(listed) - {self.device_id}):
            for item in self.server.call("POST", f"/v1/key-packages/{urllib.parse.quote(self.username)}", {"device": device})["key_packages"]:
                package = unb64(item["key_package"])
                owner, key = lichat_core.Device.key_package_owner(package)
                if item["device"] == device and owner == self.username and listed.get(device) == bytes(key):
                    packages.append(package)
                    devices.append(device)
        if not packages:
            return None
        roster = {self.username: sorted([self.device_id] + devices)}
        with self.lock:
            conversation, welcome = self.device.start_conversation(packages)
            cid = bytes(conversation).hex()
            self.chats[cid] = {"title": "", "roster": roster, "messages": [], "intern": True}
            hello = self.device.encrypt(bytes(conversation), self._payload({"t": "roster", "devices": roster, "title": "", "intern": True}))
            self.save()
        self.server.call("POST", "/v1/messages", {"to": devices, "blob": b64(WELCOME + bytes(welcome))})
        self.server.call("POST", "/v1/messages", {"to": devices, "blob": b64(MLS + bytes(hello))})
        return cid

    def _tell_own_devices(self, payload):
        """A note to my other devices (settings), if I have any."""
        cid = self.own_group_id()
        if cid and self.targets(cid):
            self._group_message(cid, payload)

    def send_history(self, device):
        """Everything I have – conversations with their messages, names, pictures, settings – once to my new device:
        sealed with a fresh key, put on the server as an attachment, the key only inside my devices' own group."""
        cid = self.own_group_id()
        if not cid:
            return False
        import zlib
        with self.lock:
            archive = {"chats": {c: {k: chat.get(k) for k in ("title", "bild", "stumm", "messages")}
                                 for c, chat in self.visible_chats().items()},
                       "profile": {k: v for k, v in self.profiles.items() if k not in ("gekoppelt", "geraetename")}}
        data = zlib.compress(json.dumps(archive, ensure_ascii=False).encode(), 9)
        key, nonce = os.urandom(32), os.urandom(12)
        sealed = nonce + AESGCM(key).encrypt(nonce, data, self.HISTORY_MAGIC)
        if len(sealed) > ATTACHMENT_MAX:
            raise LiChatError("Der Verlauf ist zu groß für eine Übertragung")
        attachment = json.loads(self.server.raw("PUT", "/v1/anhaenge", sealed))["id"]
        self._group_message(cid, {"t": "verlauf", "fuer": device, "id": attachment, "schluessel": b64(key),
                                  "sha256": hashlib.sha256(sealed).hexdigest()})
        return True

    def _take_history(self, data):
        """On the new device: the history from my other device, merged in front of what came since."""
        import zlib
        sealed = self.server.raw("GET", f"/v1/anhaenge/{data['id']}")
        if hashlib.sha256(sealed).hexdigest() != data.get("sha256"):
            raise LiChatError("Verlauf verändert – nicht übernommen")
        archive = json.loads(zlib.decompress(AESGCM(unb64(data["schluessel"])).decrypt(sealed[:12], sealed[12:], self.HISTORY_MAGIC)))
        for cid, old in archive.get("chats", {}).items():
            chat = self.chats.setdefault(cid, {"title": "", "roster": {}, "messages": []})
            kept = [m for m in chat["messages"] if m.get("text") != "Ältere Nachrichten gibt es nur auf deinen anderen Geräten."]
            known = {m.get("mid") for m in old.get("messages") or [] if m.get("mid")}
            chat["messages"] = list(old.get("messages") or []) + [m for m in kept if not m.get("mid") or m["mid"] not in known]
            for key in ("title", "bild"):
                if old.get(key) and not chat.get(key):
                    chat[key] = old[key]
            if old.get("stumm"):
                chat["stumm"] = True
        profile = archive.get("profile", {})
        for key, value in profile.items():
            if key in ("people", "names", "kontakte") and isinstance(value, dict):
                self.profiles.setdefault(key, {}).update(value)
            elif key not in self.profiles or self.profiles.get(key) in (None, {}, ""):
                self.profiles[key] = value
        return [{"chat": None, "synced": True}]

    def my_devices(self):
        """My devices: [{device, since, this, name}] – the time each signed in; a name only for this one (it stays here)."""
        listed = self.server.call("GET", "/v1/devices")["devices"]
        return [{"device": d["device"], "since": d.get("created", 0), "this": d["device"] == self.device_id,
                 "name": self.profiles.get("geraetename", "") if d["device"] == self.device_id else ""} for d in listed]

    def remove_my_device(self, device):
        """Signs out another of my devices and takes it out of every conversation – it reads nothing that follows."""
        if device == self.device_id:
            raise LiChatError("Dieses Gerät meldest du in seinen eigenen Einstellungen ab")
        self.server.call("DELETE", f"/v1/devices/{device}")
        self.sync_own_devices()

    def sync_own_devices(self):
        """Every conversation holds exactly my devices from the directory: new ones are added (MLS), signed-out ones
        taken out. Cheap when nothing changed (one directory call). Returns how many conversations changed."""
        listed = self.directory(self.username)
        changed = 0
        for cid in list(self.chats):
            chat = self.chats[cid]
            if chat.get("removed") or cid not in {bytes(c).hex() for c in self.device.conversations()}:
                continue
            have = set(chat["roster"].get(self.username, []))
            missing = sorted(set(listed) - have)
            gone_keys = [bytes(k) for k in self.device.device_keys(bytes.fromhex(cid), self.username)
                         if bytes(k) not in set(listed.values())]
            for key in gone_keys:
                with self.lock:
                    before = self.targets(cid)
                    commit = self.device.remove_device(bytes.fromhex(cid), key)
                    chat["roster"][self.username] = [d for d in chat["roster"].get(self.username, []) if d in listed]
                    self.save()
                self.server.call("POST", "/v1/messages", {"to": before, "blob": b64(MLS + bytes(commit))})
            packages, adding = [], []
            for device in missing:
                claimed = self.server.call("POST", f"/v1/key-packages/{urllib.parse.quote(self.username)}", {"device": device})["key_packages"]
                for item in claimed:
                    package = unb64(item["key_package"])
                    owner, key = lichat_core.Device.key_package_owner(package)
                    if item["device"] == device and owner == self.username and listed.get(device) == bytes(key):
                        packages.append(package)
                        adding.append(device)
            if packages:
                with self.lock:
                    before = self.targets(cid)
                    commit, welcome = self.device.add_members(bytes.fromhex(cid), packages)
                    chat["roster"][self.username] = sorted(set(chat["roster"].get(self.username, [])) | set(adding))
                    payloads = [{"t": "roster", "devices": chat["roster"], "title": chat.get("title", "")}]
                    if chat.get("intern"):
                        payloads[0]["intern"] = True      # a third device knows it as my devices' group, never shown
                    if chat.get("bild"):
                        payloads.append({"t": "gruppenbild", "bild": chat["bild"]})
                    packets = [self.device.encrypt(bytes.fromhex(cid), self._payload(p)) for p in payloads]
                    self.save()
                    everyone = self.targets(cid)
                self.server.call("POST", "/v1/messages", {"to": before, "blob": b64(MLS + bytes(commit))})
                self.server.call("POST", "/v1/messages", {"to": adding, "blob": b64(WELCOME + bytes(welcome))})
                for packet in packets:
                    self.server.call("POST", "/v1/messages", {"to": everyone, "blob": b64(MLS + bytes(packet))})
            if packages or gone_keys:
                changed += 1
        return changed

    @property
    def username(self):
        return self.info["username"]

    @property
    def device_id(self):
        return self.info["device"]

    # ------------------------------------------------------------------------------------------ storage

    def _disk_mark(self):
        try:
            stat = (self.folder / "state.bin").stat()
            return stat.st_mtime_ns, stat.st_size, stat.st_ino
        except FileNotFoundError:
            return None

    def _reload_if_changed(self):
        """Another process saved since we last looked: take its state (device, chats, pictures) before changing anything."""
        if self._disk_mark() == getattr(self, "_seen", None):
            return
        self.device = lichat_core.Device.load((self.folder / "state.bin").read_bytes(), self.key)
        self.chats = self._unseal("chats.bin", CHATS_MAGIC, {})
        self.profiles = self._unseal("profile.bin", PROFILE_MAGIC, {"me": None, "people": {}})

    def _mine(self, cid, mid):
        """One of my messages, looked up again (after a reload the old dict is gone)."""
        return next(m for m in self.chats[cid]["messages"] if m.get("mid") == mid)

    def _unseal(self, name, magic, empty):
        path = self.folder / name
        if not path.exists():
            return empty
        sealed = path.read_bytes()
        if not sealed.startswith(magic):
            raise LiChatError(f"{name} ist beschädigt")
        body = sealed[len(magic):]
        return json.loads(AESGCM(self.key).decrypt(body[:12], body[12:], magic))

    def _seal(self, name, magic, data):
        nonce = os.urandom(12)
        plain = json.dumps(data, ensure_ascii=False).encode()
        write_private(self.folder / name, magic + nonce + AESGCM(self.key).encrypt(nonce, plain, magic))

    def save(self):
        """The core's state first, then the chats – both before anything is acknowledged on the server."""
        write_private(self.folder / "state.bin", bytes(self.device.save(self.key)))
        self._seal("chats.bin", CHATS_MAGIC, self.chats)
        self._seal("profile.bin", PROFILE_MAGIC, self.profiles)

    # ------------------------------------------------------------------------------------------ conversations

    def chat(self, ref):
        """A chat by its short id (or a longer prefix of it)."""
        found = [c for c in self.chats if c.startswith(ref.lower())]
        if len(found) != 1:
            raise LiChatError(f"Kein eindeutiges Gespräch „{ref}“")
        return found[0]

    def directory(self, username):
        """The user's devices and public keys, as the server lists them ({} when the name is unknown)."""
        try:
            return {d["device"]: unb64(d["identity"]) for d in self.server.call("GET", f"/v1/users/{urllib.parse.quote(username)}")["devices"]}
        except LiChatError as error:
            if "(404)" in str(error):
                return {}
            raise

    def start(self, usernames, title=""):
        """A conversation with these people (all their devices). Every claimed key package must belong to the user and
        the device the directory names – the server cannot slip in a device of its own."""
        others = sorted({u for u in usernames if u != self.username})
        if not others:
            raise LiChatError("Mit wem? Mindestens eine andere Person")
        roster, packages = self._claim(others)
        roster[self.username] = [self.device_id]
        with self.lock:
            conversation, welcome = self.device.start_conversation(packages)
            conversation = bytes(conversation)
            cid = conversation.hex()
            self.chats[cid] = {"title": title, "roster": roster, "messages": []}
            hello = self._payload({"t": "roster", "devices": roster, "title": title})
            packets = [self.device.encrypt(conversation, hello)]
            if self.profiles["me"]:
                packets.append(self.device.encrypt(conversation, self._payload({"t": "profile", "bild": self.profiles["me"]})))
            if self.profiles.get("name"):
                packets.append(self.device.encrypt(conversation, self._payload({"t": "name", **self.profiles["name"]})))
            self.save()
            targets = self.targets(cid)
        self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(WELCOME + bytes(welcome))})
        for packet in packets:
            self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})
        self.sync_own_devices()      # my other devices belong in it too (card da5cbfcb)
        return cid

    def _claim(self, users):
        """One key package per device of each user – each checked against the directory (name and identity key)."""
        roster, packages = {}, []
        for user in users:
            listed = self.directory(user)
            claimed = self.server.call("POST", f"/v1/key-packages/{urllib.parse.quote(user)}", {})["key_packages"]
            if not claimed:
                raise LiChatError(f"{user} hat gerade keinen Schlüssel frei")
            for item in claimed:
                package = unb64(item["key_package"])
                owner, key = lichat_core.Device.key_package_owner(package)
                if owner != user or listed.get(item["device"]) != bytes(key):
                    raise LiChatError(f"Key-Package passt nicht zu {user} – nichts geändert")
                roster.setdefault(user, []).append(item["device"])
                packages.append(package)
        return roster, packages

    # ------------------------------------------------------------------------------------------ groups (card d45b319a)

    def add(self, cid, usernames):
        """Adds people to a conversation: the commit to everyone so far, the welcome to the new devices, then the new
        roster (with the group's name and picture) to all. Newcomers read only what comes after (MLS)."""
        chat = self.chats[cid]
        if chat.get("removed"):
            raise LiChatError("Du bist nicht mehr in diesem Gespräch")
        new = sorted({u for u in usernames if u != self.username and u not in chat["roster"]})
        if not new:
            raise LiChatError("Wen hinzufügen? Die Person ist schon dabei")
        added, packages = self._claim(new)
        with self.lock:
            before = self.targets(cid)
            commit, welcome = self.device.add_members(bytes.fromhex(cid), packages)
            chat["roster"].update(added)
            for name in new:
                self._system(chat, f"Du hast {name} hinzugefügt.")
            payloads = [{"t": "roster", "devices": chat["roster"], "title": chat.get("title", "")}]
            if chat.get("bild"):
                payloads.append({"t": "gruppenbild", "bild": chat["bild"]})
            if self.profiles["me"]:
                payloads.append({"t": "profile", "bild": self.profiles["me"]})
            if self.profiles.get("name"):
                payloads.append({"t": "name", **self.profiles["name"]})
            packets = [self.device.encrypt(bytes.fromhex(cid), self._payload(p)) for p in payloads]
            self.save()
            everyone = self.targets(cid)
        self.server.call("POST", "/v1/messages", {"to": before, "blob": b64(MLS + bytes(commit))})
        self.server.call("POST", "/v1/messages", {"to": sorted(set(everyone) - set(before)), "blob": b64(WELCOME + bytes(welcome))})
        for packet in packets:
            self.server.call("POST", "/v1/messages", {"to": everyone, "blob": b64(MLS + bytes(packet))})

    def _group_message(self, cid, payload, line=None):
        chat = self.chats[cid]
        if chat.get("removed"):
            raise LiChatError("Du bist nicht mehr in diesem Gespräch")
        with self.lock:
            if line:
                self._system(chat, line)
            packet = self.device.encrypt(bytes.fromhex(cid), self._payload(payload))
            self.save()
            targets = self.targets(cid)
        self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})

    def rename(self, cid, title):
        """The group's name – only inside the conversation, never on the server."""
        title = str(title).strip()[:80]
        self.chats[cid]["title"] = title
        self._group_message(cid, {"t": "titel", "titel": title},
                            f"Du hast die Gruppe „{title}“ genannt." if title else "Du hast den Gruppennamen entfernt.")

    def set_group_picture(self, cid, path):
        """The group's picture (512 px, without EXIF) – end-to-end like everything else; None removes it."""
        picture = b64(self.prepare_picture(path)) if path else ""
        self.chats[cid]["bild"] = picture or None
        self._group_message(cid, {"t": "gruppenbild", "bild": picture},
                            "Du hast das Gruppenbild geändert." if picture else "Du hast das Gruppenbild entfernt.")

    def leave(self, cid):
        """Leaving: the others are told and the first of them takes this device out of the MLS group; here the
        conversation stays readable, nothing more can be written."""
        self._group_message(cid, {"t": "verlassen"}, "Du hast die Unterhaltung verlassen.")
        with self.lock:
            self.chats[cid]["removed"] = self.username
            self.save()

    def mute(self, cid, on):
        """Stummschalten: no notifications for this conversation – on all my devices (their own group)."""
        self._chat_setting(cid, "stumm", bool(on))

    def group_picture(self, cid):
        value = self.chats.get(cid, {}).get("bild")
        return unb64(value) if value else None

    # ------------------------------------------------------------------------------------------ profile pictures

    @staticmethod
    def prepare_picture(path):
        """Square from the middle, 512 px, JPEG – and nothing else from the file: no EXIF (place, camera, time)."""
        from PIL import Image, ImageOps
        with Image.open(path) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            side = min(image.size)
            left, top = (image.width - side) // 2, (image.height - side) // 2
            image = image.crop((left, top, left + side, top + side)).resize((PICTURE_PX, PICTURE_PX), Image.LANCZOS)
            out = io.BytesIO()
            image.save(out, "JPEG", quality=82, optimize=True)
        return out.getvalue()

    def set_picture(self, path):
        """My picture (None: remove it), sent end-to-end into every conversation."""
        picture = b64(self.prepare_picture(path)) if path else ""
        sends = []
        with self.lock:
            self.profiles["me"] = picture or None
            for cid in self.chats:
                sends.append((self.targets(cid), self.device.encrypt(bytes.fromhex(cid), self._payload({"t": "profile", "bild": picture}))))
            self.save()
        for targets, packet in sends:
            if targets:
                self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})

    # ------------------------------------------------------------------------------------------ contacts (89aae55d)

    @staticmethod
    def fingerprint(identities):
        """16 bytes of SHA-256 over a user's identity keys (sorted) – what a contact QR carries and scanning checks."""
        digest = hashlib.sha256(b"".join(sorted(bytes(i) for i in identities))).hexdigest()
        return digest[:32]

    def contact_link(self):
        """My contact as a link (and QR): no phone number, only name, server and my keys' fingerprint."""
        fp = self.fingerprint(list(self.directory(self.username).values()) or [bytes(self.device.identity_key())])
        return f"lichat://kontakt?server={urllib.parse.quote(self.info['server'], safe='')}&name={self.username}&fp={fp}"

    @staticmethod
    def parse_link(text):
        """A pasted or scanned text: {"art": "kontakt", "server", "name", "fp"} | {"art": "einladung", "server", "code"} | None."""
        text = str(text).strip()
        match = re.match(r"^(https?://[^/\s]+)/e/([A-Za-z0-9-]{4,40})/?$", text)
        if match:
            return {"art": "einladung", "server": match.group(1), "code": match.group(2).upper()}
        parsed = urllib.parse.urlparse(text)
        if parsed.scheme != "lichat":
            return None
        query = {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}
        if parsed.netloc == "einladung" and query.get("code"):
            return {"art": "einladung", "server": query.get("server", ""), "code": query["code"].upper()}
        if parsed.netloc == "koppeln" and query.get("code"):
            return {"art": "koppeln", "server": query.get("server", ""), "code": query["code"].upper()}
        if parsed.netloc == "kontakt" and query.get("name"):
            return {"art": "kontakt", "server": query.get("server", ""), "name": query["name"], "fp": query.get("fp", "")}
        return None

    @property
    def contacts(self):
        return self.profiles.setdefault("kontakte", {})

    def add_contact(self, username, fingerprint=None):
        """Adds a contact – verified when the fingerprint from their QR matches the directory now."""
        username = str(username).strip().lower()
        listed = self.directory(username)
        if not listed:
            raise LiChatError(f"{username} gibt es auf diesem Server nicht")
        verified = bool(fingerprint) and self.fingerprint(list(listed.values())) == fingerprint
        if fingerprint and not verified:
            raise LiChatError(f"Der Schlüssel von {username} passt nicht zum QR-Code – Kontakt nicht übernommen")
        with self.lock:
            known = self.contacts.get(username, {})
            self.contacts[username] = {"verifiziert": verified or known.get("verifiziert", False), "seit": known.get("seit", int(time.time()))}
            self.save()
        return self.contacts[username]

    def add_contact_link(self, text):
        link = self.parse_link(text)
        if not link or link["art"] != "kontakt":
            raise LiChatError("Das ist kein LiChat-Kontakt")
        if link["server"] and check_server(urllib.parse.unquote(link["server"])) != self.info["server"]:
            raise LiChatError("Dieser Kontakt ist auf einem anderen LiChat-Server")
        return link["name"], self.add_contact(link["name"], link["fp"])

    def new_pin(self):
        """A PIN for someone to add me (10 minutes, once)."""
        return self.server.call("POST", "/v1/pins", {})["pin"]

    def redeem_pin(self, pin):
        username = self.server.call("POST", "/v1/pins/einloesen", {"pin": pin})["username"]
        return username, self.add_contact(username)

    def display(self, username):
        """What people see: „Vorname Nachname“ when that person told us (end to end), else the user name."""
        if username == self.username:
            mine = self.profiles.get("name") or {}
        else:
            mine = (self.profiles.get("names") or {}).get(username) or {}
        full = " ".join(x for x in (mine.get("vorname", ""), mine.get("nachname", "")) if x).strip()
        return full or username

    def set_name(self, first, last):
        """My first and last name – any letters, upper and lower case, spaces – sent end-to-end into every conversation."""
        first, last = str(first).strip()[:40], str(last).strip()[:40]
        sends = []
        with self.lock:
            self.profiles["name"] = {"vorname": first, "nachname": last}
            for cid in self.chats:
                if self.chats[cid].get("removed"):
                    continue
                sends.append((self.targets(cid), self.device.encrypt(bytes.fromhex(cid), self._payload({"t": "name", "vorname": first, "nachname": last}))))
            self.save()
        for targets, packet in sends:
            if targets:
                self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})

    @staticmethod
    def suggest_username(first, last=""):
        """„Erika Mustermann“ → „erika.mustermann“: what the server accepts, made from the name instead of refusing input."""
        import unicodedata
        text = f"{first} {last}".strip().lower()
        for a, b in (("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("ß", "ss")):
            text = text.replace(a, b)
        text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
        text = re.sub(r"[^a-z0-9._-]+", ".", text).strip("._-")
        return re.sub(r"\.{2,}", ".", text)[:32]

    def picture(self, username):
        """The JPEG bytes of [username]'s picture (mine too), or None – my own contact picture for them first (8534fe11)."""
        own = self.profiles.get("kontaktbilder", {}).get(username)
        value = own or (self.profiles["me"] if username == self.username else self.profiles["people"].get(username))
        return unb64(value) if value else None

    def has_contact_picture(self, username):
        return bool(self.profiles.get("kontaktbilder", {}).get(username))

    def set_contact_picture(self, username, path_or_bytes):
        """A picture of someone only for me, like Apple Contacts (Olaf 8534fe11): it follows on my devices (their own group),
        the person learns nothing. None: their shared picture again („Zurücksetzen“)."""
        if isinstance(path_or_bytes, (bytes, bytearray)):
            path_or_bytes = io.BytesIO(bytes(path_or_bytes))
        value = b64(self.prepare_picture(path_or_bytes)) if path_or_bytes is not None else None
        with self.lock:
            pictures = self.profiles.setdefault("kontaktbilder", {})
            if value:
                pictures[username] = value
            else:
                pictures.pop(username, None)
            self.save()
        self._tell_own_devices({"t": "kontaktbild", "name": username, "bild": value})

    def targets(self, cid):
        return sorted({d for devices in self.chats[cid]["roster"].values() for d in devices} - {self.device_id})

    @staticmethod
    def _payload(data):
        return json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()

    def send(self, cid, text, reply_to=None):
        """[reply_to]: the mid of the message this answers – its sender and a short quote travel along."""
        text = text.strip()
        if not text:
            raise LiChatError("Leere Nachricht")
        if self.chats[cid].get("removed"):
            raise LiChatError(f"{self.chats[cid]['removed']} hat dich aus dem Gespräch entfernt")
        at = int(time.time())
        message = {"from": self.username, "text": text, "at": at, "mid": secrets.token_hex(8), "status": "wartet"}
        payload = {"t": "text", "text": text, "at": at, "mid": message["mid"]}
        if reply_to:
            quoted = next((m for m in self.chats[cid]["messages"] if m.get("mid") == reply_to), None)
            if quoted:
                message["antwort"] = payload["antwort"] = {"mid": reply_to, "from": quoted["from"],
                                                           "text": (self.describe(quoted) or quoted.get("text", ""))[:120]}
        with self.lock:
            self.chats[cid]["messages"].append(message)
            self.save()
        return self._deliver(cid, message, payload)

    def react(self, cid, mid, emoji):
        """My reaction on a message (one per person, like iOS); "" takes it back."""
        emoji = str(emoji).strip()          # any emoji (Olaf: „+“), ZWJ sequences like 👩‍👩‍👧 included – but only an emoji
        if emoji and not single_emoji(emoji):
            raise LiChatError("Als Reaktion geht genau ein Emoji")
        with self.lock:
            target = next(m for m in self.chats[cid]["messages"] if m.get("mid") == mid)
            reactions = target.setdefault("reaktionen", {})
            if emoji:
                reactions[self.username] = emoji
            else:
                reactions.pop(self.username, None)
        self._group_message(cid, {"t": "reaktion", "mid": mid, "emoji": emoji})

    # ------------------------------------------------------------------------------------------ mentions (d54175ed)

    def mentioned(self, cid, text):
        """Who „@name“ in this text means: members of this conversation only, „@alle“ = everyone else. Worked out by every
        receiver from the text itself – nobody can claim a mention that is not in the message."""
        names = {name.lower().rstrip("._-") for name in MENTION.findall(text or "")}
        members = set(self.chats.get(cid, {}).get("roster", {}))
        if names & set(EVERYONE):
            return sorted(members)
        return sorted(names & members)

    def mentions_me(self, cid, text):
        names = {name.lower().rstrip("._-") for name in MENTION.findall(text or "")}
        return self.username in names or bool(names & set(EVERYONE))

    def mention_candidates(self, cid, typed):
        """While typing „@…“ in a group: the members that fit (name or display name), me left out – for the suggestions."""
        typed = typed.lower()
        return [u for u in sorted(self.chats.get(cid, {}).get("roster", {}))
                if u != self.username and (u.startswith(typed) or self.display(u).lower().startswith(typed))]

    # ------------------------------------------------------------------------------------------ edit / unsend (4ceca163)

    def can_edit(self, message, now=None):
        now = now or time.time()
        return (message.get("from") == self.username and bool(message.get("text")) and not message.get("zurueckgerufen")
                and message.get("mid") and now - message.get("at", 0) <= EDIT_WINDOW
                and len(message.get("bearbeitet", [])) < EDIT_MAX)

    def can_unsend(self, message, now=None):
        now = now or time.time()
        return (message.get("from") == self.username and message.get("mid") and not message.get("zurueckgerufen")
                and now - message.get("at", 0) <= UNSEND_WINDOW)

    def edit(self, cid, mid, text):
        """My message, corrected (iOS: 15 minutes, five times). Everyone sees „Bearbeitet“ and can look at the earlier
        versions – end-to-end like the message itself."""
        text = text.strip()
        if not text:
            raise LiChatError("Leere Nachricht – zum Entfernen „Senden widerrufen“")
        with self.lock:
            message = self._mine(cid, mid)
            if not self.can_edit(message):
                raise LiChatError("Bearbeiten geht nur 15 Minuten lang und höchstens fünfmal")
            now = int(time.time())
            self._apply_edit(message, text, now)
            self.save()
        self._group_message(cid, {"t": "bearbeiten", "mid": mid, "text": text, "at": now})

    def unsend(self, cid, mid):
        """My message taken back (iOS: two minutes) – for everyone a grey line instead of the bubble."""
        with self.lock:
            message = self._mine(cid, mid)
            if not self.can_unsend(message):
                raise LiChatError("Widerrufen geht nur in den ersten zwei Minuten")
            self._apply_unsend(self.chats[cid], message)
            self.save()
        self._group_message(cid, {"t": "zurueckrufen", "mid": mid, "at": int(time.time())})

    @staticmethod
    def _apply_edit(message, text, at):
        message.setdefault("bearbeitet", []).append({"text": message.get("text", ""), "at": message.get("bearbeitet_am", message.get("at"))})
        message["text"] = text
        message["bearbeitet_am"] = at

    @staticmethod
    def _apply_unsend(chat, message):
        for key in ("text", "anhang", "bearbeitet", "bearbeitet_am", "antwort", "reaktionen"):
            message.pop(key, None)
        message["text"] = ""
        message["zurueckgerufen"] = True
        for other in chat["messages"]:     # quotes of it go too
            if (other.get("antwort") or {}).get("mid") == message.get("mid"):
                other["antwort"]["text"] = ""
                other["antwort"]["zurueckgerufen"] = True

    def _deliver(self, cid, message, payload):
        """Encrypts and posts one of my messages; its status follows: wartet → gesendet, or fehler (a red line, retry)."""
        try:
            if not self.targets(cid):
                # Just joined: the welcome is there, the roster (who has which devices) comes with the next fetch.
                # Fetching here would swallow news meant for the app's listener – so say it plainly (Jean-Marie 09.10.).
                raise LiChatError("Das Gespräch wird gerade noch eingerichtet – in ein paar Sekunden noch einmal senden")
            with self.lock:
                packet = self.device.encrypt(bytes.fromhex(cid), self._payload(payload))
                self.save()
                targets = self.targets(cid)
            answer = self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})
        except (LiChatError, ValueError):
            with self.lock:
                self._mine(cid, message["mid"])["status"] = "fehler"
                self.save()
            raise
        with self.lock:
            mine = self._mine(cid, message["mid"])
            mine["status"] = "gesendet"
            mine["gesendet"] = int(time.time())
            self.save()
        return answer

    def retry(self, cid, mid, changed=None):
        """„Nicht gesendet“ tapped: the same message (same mid) once more – an attachment still in the outbox goes up first."""
        message = next(m for m in self.chats[cid]["messages"] if m.get("mid") == mid)
        if (message.get("anhang") or {}).get("id", "").startswith(OUTBOX):
            message["status"] = "wartet"
            return self._upload(cid, message, changed)
        payload = {"t": "anhang", "at": message["at"], "mid": mid, **message["anhang"]} if message.get("anhang") \
            else {"t": "text", "text": message["text"], "at": message["at"], "mid": mid}
        if message.get("anhang") and message.get("text"):
            payload["text"] = message["text"]
        message["status"] = "wartet"
        return self._deliver(cid, message, payload)

    # ------------------------------------------------------------------------------------------ delivery state (db70d358)

    @property
    def read_receipts(self):
        return bool(self.profiles.get("lesebestaetigungen", False))

    def set_read_receipts(self, on):
        with self.lock:
            self.profiles["lesebestaetigungen"] = bool(on)
            self.save()
        self._tell_own_devices({"t": "einstellungen", "lesebestaetigungen": bool(on)})

    # ------------------------------------------------------------------------------------------ typing (884f7c8a)
    # Olaf 09.10.: „… tippt“ only with the switch on (off by default, privacy) and mutual like read receipts – off means I
    # neither send it nor see it. {"t": "tippt", "at"} travels end-to-end, at most every TYPING_EVERY s, nothing is stored.

    @property
    def typing_indicator(self):
        return bool(self.profiles.get("tippanzeige", False))

    def set_typing_indicator(self, on):
        with self.lock:
            self.profiles["tippanzeige"] = bool(on)
            if not on:
                self._typing.clear()
            self.save()
        self._tell_own_devices({"t": "einstellungen", "tippanzeige": bool(on)})

    def typing(self, cid):
        """I am typing in this chat: tell the others (rate-limited; nothing when switched off)."""
        now = time.monotonic()
        if not self.typing_indicator or self.chats[cid].get("removed") or now - self._typed.get(cid, -TYPING_EVERY) < TYPING_EVERY:
            return False
        self._typed[cid] = now
        with self.lock:
            packet = self.device.encrypt(bytes.fromhex(cid), self._payload({"t": "tippt", "at": int(time.time())}))
            self.save()
            targets = self.targets(cid)
        self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})
        return True

    def type_like_a_person(self, cid, text, sleep=time.sleep):
        """Olaf 09.10.: everyone in the chat takes as long as a person would to type this text on a phone – short
        quickly, long slowly – with „…“ for those who see it (both have the switch on). Then the caller sends."""
        import random
        seconds = min(TYPING_LONGEST, max(1.5, len(text) / TYPING_PACE)) * random.uniform(0.85, 1.2)
        waited = 0.0
        while True:
            self._typed.pop(cid, None)
            try:
                self.typing(cid)
            except LiChatError:
                pass        # „…“ is a nicety – the message still goes
            left = seconds - waited
            if left <= 0:
                return seconds
            step = min(TYPING_EVERY, left)
            sleep(step)
            waited += step

    def typing_now(self, cid):
        """Who is typing in this chat right now (shown as „…“ under the last bubble)."""
        if not self.typing_indicator:
            return []
        now = time.time()
        return sorted(u for u, at in self._typing.get(cid, {}).items() if now - at < TYPING_FRESH)

    def _receipt(self, cid, kind, mids):
        if not mids or self.chats[cid].get("removed"):
            return None
        with self.lock:
            packet = self.device.encrypt(bytes.fromhex(cid), self._payload({"t": "quittung", "art": kind, "mids": mids, "at": int(time.time())}))
            self.save()
            targets = self.targets(cid)
        return targets, packet

    def mark_read(self, cid):
        """The chat is on screen: it counts as read up to its last message (on all my devices – card 300129f8), and with
        read receipts on (for this chat – 3c0400ad) the others learn which of their messages were read."""
        self._read_up_to(cid)
        if not self.receipts_for(cid):
            return
        with self.lock:
            mids = [m["mid"] for m in self.chats[cid]["messages"]
                    if m.get("mid") and m["from"] not in ("", self.username) and not m.get("gelesen_gemeldet")]
            for m in self.chats[cid]["messages"]:
                if m.get("mid") in mids:
                    m["gelesen_gemeldet"] = True
        sent = self._receipt(cid, "gelesen", mids)
        if sent:
            self.server.call("POST", "/v1/messages", {"to": sent[0], "blob": b64(MLS + bytes(sent[1]))})

    # ------------------------------------------------------------------------------------------ Mac points (Michelle 09.10.)
    # Pinned (206118bf), unread marker + „als ungelesen“ (300129f8), read receipts per chat (3c0400ad), blocking + filters
    # (1f800b4f), „Zuletzt gelöscht“ (a0ceee6e). Chat settings follow on all my devices (their own group) – deleting
    # stays on the device (Michelle).

    SYNCED_CHAT_KEYS = ("stumm", "angeheftet", "lesebestaetigungen", "ungelesen", "gelesen_mid")
    DELETED_DAYS = 30

    def _chat_setting(self, cid, key, value, tell=True):
        with self.lock:
            if value is None:
                self.chats[cid].pop(key, None)
            else:
                self.chats[cid][key] = value
            self.save()
        if tell and key in self.SYNCED_CHAT_KEYS:
            self._tell_own_devices({"t": "gespraech", "chat": cid, "key": key, "value": value})

    def receipts_for(self, cid):
        """Read receipts in this chat: its own setting, else the general one (Apple: per conversation)."""
        own = self.chats.get(cid, {}).get("lesebestaetigungen")
        return self.read_receipts if own is None else bool(own)

    def set_receipts_for(self, cid, on):
        """None = like the general setting again."""
        self._chat_setting(cid, "lesebestaetigungen", None if on is None else bool(on))

    def pin(self, cid, on):
        self._chat_setting(cid, "angeheftet", int(time.time()) if on else None)

    def pinned(self):
        """Pinned conversations, first pinned first (like the Mac's grid)."""
        chats = self.visible_chats()
        return sorted((c for c, chat in chats.items() if chat.get("angeheftet")), key=lambda c: chats[c]["angeheftet"])

    def _read_up_to(self, cid):
        chat = self.chats[cid]
        last = next((m.get("mid") for m in reversed(chat["messages"]) if m.get("mid")), None)
        if chat.get("ungelesen"):
            self._chat_setting(cid, "ungelesen", None)
        if last and chat.get("gelesen_mid") != last:
            self._chat_setting(cid, "gelesen_mid", last)

    def mark_unread(self, cid):
        """„Als ungelesen markieren“ – the blue dot comes back (on all my devices)."""
        self._chat_setting(cid, "ungelesen", True)

    def unread(self, cid):
        """How many messages of others came after the read marker (a chat marked unread counts at least 1)."""
        chat = self.chats[cid]
        messages = [m for m in chat["messages"] if not m.get("geloescht")]
        marker = chat.get("gelesen_mid")
        index = next((i for i, m in enumerate(messages) if marker and m.get("mid") == marker), None)
        after = messages[index + 1:] if index is not None else ([] if marker is None else messages)
        count = sum(1 for m in after if m.get("from") not in ("", self.username) and not m.get("system"))
        return max(count, 1 if chat.get("ungelesen") else 0)

    def first_unread(self, cid):
        """The mid of the first unread message (for „↑ Zur ersten ungelesenen“), or None."""
        chat = self.chats[cid]
        messages = [m for m in chat["messages"] if not m.get("geloescht")]
        marker = chat.get("gelesen_mid")
        index = next((i for i, m in enumerate(messages) if marker and m.get("mid") == marker), None)
        if index is None:
            return None
        return next((m.get("mid") for m in messages[index + 1:] if m.get("from") not in ("", self.username) and m.get("mid")), None)

    # blocking and filters (1f800b4f)

    @property
    def blocked(self):
        return self.profiles.setdefault("blockiert", {})

    def block(self, username, on=True):
        """Nothing more from this person: messages, reactions, „tippt“ are dropped, no receipts go back, no notification."""
        with self.lock:
            if on:
                self.blocked[username] = int(time.time())
            else:
                self.blocked.pop(username, None)
            self.save()
        self._tell_own_devices({"t": "blockiert", "name": username, "an": bool(on)})

    def known(self, cid):
        """Bekannt: someone in my contacts, or a chat I wrote in myself; else Unbekannt (Apple's filter)."""
        chat = self.chats[cid]
        others = [u for u in chat.get("roster", {}) if u != self.username]
        return any(u in self.contacts for u in others) or any(m.get("from") == self.username and not m.get("system") for m in chat["messages"])

    def filtered(self, which="alle"):
        """alle | bekannt | unbekannt | ungelesen | geloescht – the list as the filter shows it."""
        chats = {c: chat for c, chat in self.visible_chats().items()}
        if which == "geloescht":
            return [c for c, chat in chats.items() if chat.get("geloescht") or any(m.get("geloescht") for m in chat["messages"])]
        shown = [c for c, chat in chats.items() if not chat.get("geloescht")]
        if which == "bekannt":
            return [c for c in shown if self.known(c)]
        if which == "unbekannt":
            return [c for c in shown if not self.known(c)]
        if which == "ungelesen":
            return [c for c in shown if self.unread(c)]
        return shown

    # „Zuletzt gelöscht“ (a0ceee6e) – only on this device, 30 days, then gone

    def delete_chat(self, cid):
        self._chat_setting(cid, "geloescht", int(time.time()), tell=False)

    def delete_message(self, cid, mid):
        with self.lock:
            message = next(m for m in self.chats[cid]["messages"] if m.get("mid") == mid)
            message["geloescht"] = int(time.time())
            self.save()

    def restore(self, cid, mid=None):
        """Back from „Zuletzt gelöscht“: the conversation, or one message."""
        with self.lock:
            if mid is None:
                self.chats[cid].pop("geloescht", None)
                for m in self.chats[cid]["messages"]:
                    m.pop("geloescht", None)
            else:
                next(m for m in self.chats[cid]["messages"] if m.get("mid") == mid).pop("geloescht", None)
            self.save()

    def purge(self, cid=None, mid=None, now=None):
        """Gone for good: what was deleted 30 days ago (or now, when asked). A conversation stays a member of its group
        (empty and hidden) – a new message brings it back."""
        now = now or time.time()
        limit = now - self.DELETED_DAYS * 86400
        with self.lock:
            for c, chat in self.chats.items():
                if cid and c != cid:
                    continue
                old = chat.get("geloescht")
                if old and (cid or old < limit) and mid is None:
                    chat["messages"] = []
                    chat["geloescht"] = 1          # hidden, nothing left to restore
                chat["messages"] = [m for m in chat["messages"] if not (m.get("geloescht") and (
                    (mid and m.get("mid") == mid) or (not mid and (cid or m["geloescht"] < limit))))]
            self.save()

    def tick(self, cid, message):
        """(state, time) of one of my messages: wartet | fehler | gesendet | zugestellt | gelesen. In a group „zugestellt“ /
        „gelesen“ once everyone has it; „gelesen“ only while my own read receipts are on (mutual)."""
        state = message.get("status", "gesendet")
        if state in ("wartet", "fehler"):
            return state, message["at"]
        others = [u for u in self.chats[cid]["roster"] if u != self.username]
        delivered, read = message.get("zugestellt", {}), message.get("gelesen", {})
        if others and self.receipts_for(cid) and all(u in read for u in others):
            return "gelesen", max(read[u] for u in others)
        if others and all(u in delivered or u in read for u in others):
            return "zugestellt", max((delivered.get(u) or read.get(u)) for u in others)
        return "gesendet", message.get("gesendet", message["at"])

    def remove(self, cid, usernames):
        """Takes these people out of the conversation (test plan S2). The commit reaches everyone who was in it – the
        removed devices learn it and can read nothing after it."""
        names = sorted({u for u in usernames if u != self.username})
        with self.lock:
            targets = self.targets(cid)
            commit = self.device.remove_members(bytes.fromhex(cid), names)
            roster = self.chats[cid]["roster"]
            for name in names:
                roster.pop(name, None)
                self._system(self.chats[cid], f"Du hast {name} aus der Unterhaltung entfernt.")
            self.save()
        self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(commit))})

    @staticmethod
    def _system(chat, text):
        """A grey line in the history (no bubble): who joined, who was taken out."""
        line = {"from": "", "system": True, "text": text, "at": int(time.time())}
        chat["messages"].append(line)
        return line

    # ------------------------------------------------------------------------------------------ attachments

    @staticmethod
    def prepare_photo(data):
        """A photo as it may leave: upright, at most 2048 px, JPEG – nothing else of the file (no EXIF: place, camera)."""
        from PIL import Image, ImageOps
        with Image.open(io.BytesIO(data)) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            image.thumbnail((PHOTO_PX, PHOTO_PX), Image.LANCZOS)
            out = io.BytesIO()
            image.save(out, "JPEG", quality=85, optimize=True)
            preview = image.copy()
            preview.thumbnail((320, 320), Image.LANCZOS)
            small = io.BytesIO()
            preview.save(small, "JPEG", quality=70)
        return out.getvalue(), small.getvalue()

    @staticmethod
    def picture_without_metadata(data):
        from PIL import Image, ImageOps
        with Image.open(io.BytesIO(data)) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            out = io.BytesIO()
            image.save(out, "JPEG", quality=95)
        return out.getvalue()

    @staticmethod
    def clean_media(data, suffix):
        """The tracks copied unchanged into a fresh MP4 – no place, no times, no user data (ffmpeg, no re-encoding)."""
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory(prefix="lichat-") as folder:
            source, target = Path(folder) / f"ein.{suffix}", Path(folder) / f"aus.{suffix}"
            source.write_bytes(data)
            try:
                result = subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(source), "-map", "0:v?", "-map", "0:a?",
                                         "-map_metadata", "-1", "-map_chapters", "-1", "-c", "copy",
                                         "-fflags", "+bitexact", "-flags:v", "+bitexact", "-flags:a", "+bitexact",
                                         "-movflags", "+faststart", str(target)], capture_output=True, timeout=300)
            except (OSError, subprocess.TimeoutExpired):
                raise LiChatError("Zum Bereinigen von Videos und Aufnahmen fehlt ffmpeg") from None
            if result.returncode != 0 or not target.exists():
                raise LiChatError("Video/Aufnahme lässt sich nicht ohne Metadaten verschicken")
            return target.read_bytes()

    def send_attachment(self, cid, data, name, mime, kind, duration=None, preview=None, once=False, secret=False, changed=None,
                        caption=""):
        """Encrypts [data] with its own key, uploads the encrypted bytes, then announces it end-to-end in the chat.
        [once]: a one-time picture (fe9f2029), [secret]: pixelated until tapped (f9e3f055) – both only for photos."""
        if kind not in KINDS:
            raise LiChatError("unbekannte Art von Anhang")
        if self.chats[cid].get("removed"):
            raise LiChatError(f"{self.chats[cid]['removed']} hat dich aus dem Gespräch entfernt")
        if kind in ("bild", "datei") and (mime == "image/gif" or data[:6] in (b"GIF87a", b"GIF89a")):
            # A GIF stays a GIF – animated, looping (Michelle 999f37be): only comments, XMP and other extra blocks go.
            data, mime, kind = gif_without_metadata(data), "image/gif", "bild"
            name = (name.rsplit(".", 1)[0] or "Bild") + ".gif"
            preview = self.prepare_photo(data)[1]          # the first frame as the preview
        elif kind == "bild":
            data, preview = self.prepare_photo(data)
            mime, name = "image/jpeg", (name.rsplit(".", 1)[0] or "Foto") + ".jpg"
        elif kind == "datei" and mime.startswith("image/") and mime not in ("image/gif", "image/svg+xml"):
            # Sent as a file: full size – but never with its metadata (Michelle: place, camera, time); shown as a picture
            # like on the iPhone (with its preview).
            data, mime, name = self.picture_without_metadata(data), "image/jpeg", name.rsplit(".", 1)[0] + ".jpg"
            preview, kind = self.prepare_photo(data)[1], "bild"
        elif kind == "datei" and (mime.startswith("video/") or mime.startswith("audio/")):
            video = mime.startswith("video/")
            data = self.clean_media(data, "mp4" if video else "m4a")
            mime, name = ("video/mp4", name.rsplit(".", 1)[0] + ".mp4") if video else ("audio/mp4", name.rsplit(".", 1)[0] + ".m4a")
        elif kind in ("video", "sprache"):
            # Without the container's metadata: an MP4 carries the place (©xyz) and the time of recording (Michelle).
            data = self.clean_media(data, "mp4" if kind == "video" else "m4a")
            mime, name = ("video/mp4", name.rsplit(".", 1)[0] + ".mp4") if kind == "video" else ("audio/mp4", name.rsplit(".", 1)[0] + ".m4a")
        if len(data) > ATTACHMENT_MAX:
            raise LiChatError("Anhang zu groß (höchstens 100 MB)")
        if (once or secret) and kind != "bild":
            raise LiChatError("Einmal-Ansicht und Verpixeln gibt es nur für Fotos")
        if once:
            preview = None          # nothing of the picture stays in the chat
        key, nonce = os.urandom(32), os.urandom(12)
        sealed = nonce + AESGCM(key).encrypt(nonce, data, ATTACHMENT_MAGIC)
        # The bubble is there at once (cf647f61): the sealed bytes wait in the outbox until they are up.
        mid = secrets.token_hex(8)
        self._keep_sealed(OUTBOX + mid, sealed)
        info = {"id": OUTBOX + mid, "schluessel": b64(key), "sha256": hashlib.sha256(sealed).hexdigest(),
                "name": str(name)[:200], "mime": str(mime)[:100], "groesse": len(data), "art": kind}
        if duration is not None:
            info["dauer"] = round(float(duration), 1)
        if preview:
            info["vorschau"] = b64(preview)
        if once:
            info["einmal"] = True       # the sender cannot open it either (nothing kept here once it is up)
        if secret:
            info["geheim"] = True
        at = int(time.time())
        # The caption lives in the same message as the picture (2fcc3eef, like Messages) – one bubble, one delivery.
        message = {"from": self.username, "text": str(caption or "").strip(), "at": at, "anhang": info, "mid": mid, "status": "wartet"}
        with self.lock:
            self.chats[cid]["messages"].append(message)
            self.save()
        if changed:
            changed()
        return self._upload(cid, message, changed)

    def cancel_upload(self, mid):
        if mid in self.uploads:
            self._cancelling.add(mid)

    def _upload(self, cid, message, changed=None):
        """Up with it (if it still waits in the outbox), then delivered. Fails → „Nicht gesendet“, the bytes stay for a
        retry; cancelled → the bubble and the bytes go."""
        info, mid = message["anhang"], message["mid"]
        if info["id"].startswith(OUTBOX):
            path = self.folder / "anhaenge" / info["id"]
            if not path.exists():
                with self.lock:
                    self._mine(cid, mid)["status"] = "fehler"
                    self.save()
                raise LiChatError("Anhang ist nicht mehr da – bitte neu senden")
            self.uploads[mid] = 0.0
            if changed:
                changed()

            def step(sent, total):
                share = sent / total
                if share - self.uploads.get(mid, 0) >= 0.04 or sent == total:
                    self.uploads[mid] = share
                    if changed:
                        changed()
            try:
                people = len([m for m in self.members(cid) if m != self.username])
                once = info.get("einmal")
                answer = self.server.raw("PUT", f"/v1/anhaenge?einmal={max(1, people)}" if once else "/v1/anhaenge",
                                         path.read_bytes(), progress=step, cancelled=lambda: mid in self._cancelling)
                attachment = json.loads(answer)["id"]
                with self.lock:
                    mine = self._mine(cid, mid)
                    mine["anhang"]["id"] = attachment
                    if once:
                        path.unlink(missing_ok=True)
                    else:
                        path.rename(path.with_name(attachment))
                    self.save()
                    message = mine
            except UploadCancelled:
                with self.lock:
                    self.chats[cid]["messages"] = [m for m in self.chats[cid]["messages"] if m.get("mid") != mid]
                    self.save()
                path.unlink(missing_ok=True)
                return {}
            except LiChatError:
                with self.lock:
                    self._mine(cid, mid)["status"] = "fehler"
                    self.save()
                raise
            finally:
                self.uploads.pop(mid, None)
                self._cancelling.discard(mid)
                if changed:
                    changed()
        payload = {"t": "anhang", "at": message["at"], "mid": mid, **message["anhang"]}
        if message.get("text"):
            payload["text"] = message["text"]
        return self._deliver(cid, message, payload)

    def _keep_sealed(self, attachment, sealed):
        folder = self.folder / "anhaenge"
        folder.mkdir(exist_ok=True, mode=0o700)
        write_private(folder / attachment, sealed)

    def open_attachment(self, info):
        """The plain bytes: from the local (still encrypted) copy or fetched again – checked against the SHA-256 the sender
        announced, then decrypted. Nothing plain is stored here; the app writes a temporary file only to show it."""
        path = self.folder / "anhaenge" / info["id"]
        sealed = path.read_bytes() if path.exists() else self.server.raw("GET", f"/v1/anhaenge/{info['id']}")
        if hashlib.sha256(sealed).hexdigest() != info["sha256"]:
            raise LiChatError("Anhang beschädigt oder verändert")
        if not path.exists():
            self._keep_sealed(info["id"], sealed)
        return AESGCM(unb64(info["schluessel"])).decrypt(sealed[:12], sealed[12:], ATTACHMENT_MAGIC)

    def _forget_sealed(self, attachment):
        (self.folder / "anhaenge" / attachment).unlink(missing_ok=True)

    def once_state(self, message, now=None):
        """A one-time picture: "eigen" (I sent it), "neu", "geoeffnet" or "abgelaufen" (14 days unopened); else None."""
        info = message.get("anhang") or {}
        if not info.get("einmal"):
            return None
        if message.get("from") == self.username:
            return "eigen"
        if message.get("geoeffnet"):
            return "geoeffnet"
        if (now or time.time()) - message.get("at", 0) > ONCE_DAYS * 86400:
            return "abgelaufen"
        return "neu"

    def open_once(self, cid, mid):
        """The one-time picture's bytes – only in memory, once: afterwards it is marked opened here, my other devices drop
        it, the sender sees „Geöffnet“ and the server deletes it when every other person has opened it."""
        message = next((m for m in self.chats[cid]["messages"] if m.get("mid") == mid), None)
        if message is None or self.once_state(message) != "neu":
            raise LiChatError("Dieses Einmal-Bild lässt sich nicht mehr öffnen")
        info = message["anhang"]
        try:
            sealed = self.server.raw("GET", f"/v1/anhaenge/{info['id']}")
        except LiChatError as error:
            if "(404)" not in str(error) and "(410)" not in str(error):
                raise               # offline: try again later
            with self.lock:
                message["geoeffnet"] = int(time.time())
                self.save()
            raise LiChatError("Dieses Einmal-Bild ist nicht mehr da")
        if hashlib.sha256(sealed).hexdigest() != info["sha256"]:
            raise LiChatError("Anhang beschädigt oder verändert")
        data = AESGCM(unb64(info["schluessel"])).decrypt(sealed[:12], sealed[12:], ATTACHMENT_MAGIC)
        with self.lock:
            message["geoeffnet"] = int(time.time())
            self.save()
        try:
            self.server.call("POST", f"/v1/anhaenge/{info['id']}/gesehen", {})
        except LiChatError:
            pass                    # it lapses after 14 days anyway
        self._group_message(cid, {"t": "einmal_geoeffnet", "mid": mid})
        return data

    def report_screenshot(self, cid, mid):
        """The phone saw a screenshot while a one-time picture was open: a fixed line for everyone in the chat."""
        self._group_message(cid, {"t": "bildschirmfoto", "mid": mid}, "Du hast ein Bildschirmfoto vom Einmal-Bild gemacht.")

    def media(self, cid):
        """The info's overview like on the iPhone (43358d35): photos and videos, links, documents, voice messages – sent and
        received, newest first. One-time pictures never; secret ones carry "geheim" (the app pixelates them)."""
        found = {"fotos": [], "links": [], "dokumente": [], "sprache": []}
        for message in reversed(self.chats[cid]["messages"]):
            if message.get("system") or message.get("zurueckgerufen") or message.get("geloescht"):
                continue
            info = message.get("anhang")
            if info:
                if info.get("einmal"):
                    continue
                group = {"bild": "fotos", "video": "fotos", "sprache": "sprache"}.get(info.get("art"), "dokumente")
                found[group].append(message)
            else:
                for link in find_links(message.get("text", "")):
                    if link[2].startswith(("http://", "https://")):
                        found["links"].append({"url": link[2], "message": message})
        return found

    @staticmethod
    def describe(message):
        """One line for the list and notifications: the text, or what the attachment is."""
        if message.get("zurueckgerufen"):
            return "Nachricht widerrufen"
        info = message.get("anhang")
        if not info:
            return message.get("text", "")
        if info.get("einmal"):
            return "Foto · Einmal-Ansicht"
        label = {"bild": "Foto", "video": "Video", "sprache": "Sprachnachricht"}.get(info.get("art"), "Datei: " + info.get("name", ""))
        caption = (message.get("text") or "").strip()
        return f"{label}: {caption}" if caption else label        # with its caption (2fcc3eef)

    def members(self, cid):
        """The people in it – each once, however many devices they have (Michelle 8534fe11: Olaf's second device made his
        chat with Michelle look like a group of three)."""
        if self.chats.get(cid, {}).get("removed"):
            return [self.username]
        return list(dict.fromkeys(self.device.members(bytes.fromhex(cid))))

    def safety_code(self, cid):
        if self.chats.get(cid, {}).get("removed"):
            return ""
        """Groups of five digits, the same on every member's device (compare in person or by phone)."""
        raw = bytes(self.device.safety_code(bytes.fromhex(cid)))
        return " ".join(f"{int.from_bytes(raw[i:i + 4], 'big') % 100000:05d}" for i in range(0, len(raw) - 3, 4))

    # ------------------------------------------------------------------------------------------ mailbox

    def fetch(self, wait=0):
        """Fetches the mailbox, decrypts, keeps – and only then acknowledges. Returns what is new for the user:
        [{"chat", "from", "text", "at"} | {"chat", "joined": True} | {"chat", "problem"}].
        [wait] seconds: the server answers as soon as something arrives (the app's standing connection, no push
        service). Only one fetch at a time per account – the app's listener thread."""
        if wait:
            # The long wait happens outside the lock; what arrived is fetched again under it, so two processes on one
            # profile never handle (and acknowledge) the same packets twice.
            if not self.server.call("GET", f"/v1/messages?warten={int(wait)}", timeout=TIMEOUT + wait)["messages"]:
                return []
        with self.lock:
            waiting = self.server.call("GET", "/v1/messages")["messages"]
            news, joined, done = [], 0, []
            for item in waiting:
                blob = unb64(item["blob"])
                tag, body = blob[:1], blob[1:]
                try:
                    if tag == WELCOME:
                        cid = bytes(self.device.join(body)).hex()
                        self.chats.setdefault(cid, {"title": "", "roster": {}, "messages": []})
                        joined += 1
                        news.append({"chat": cid, "joined": True})
                    elif tag == MLS:
                        news.extend(self._take(self.device.receive(body)))
                    else:
                        news.append({"chat": None, "problem": "unbekanntes Paket"})
                except ValueError as error:
                    # Replayed, forged or from an epoch long gone: the core refused it. Not swallowed (Michelle 09.10.): its
                    # conversation shows a grey line, so nobody believes a message never came.
                    news.append(self._unreadable(tag, body, error))
                done.append(item["id"])
            if not done:
                return news
            self.save()
            # Acknowledged before the lock opens: another process on this profile must not get these packets again.
            self.server.call("POST", "/v1/messages/ack", {"ids": done})
        if joined:
            # Each welcome used up a key package of the supply: replace it (state saved before offering).
            with self.lock:
                packages = self.device.key_packages(joined)
                self.save()
            self.server.call("POST", "/v1/key-packages", {"key_packages": [b64(p) for p in packages]})
        if self._owe_removal:
            owed, self._owe_removal = self._owe_removal, {}
            for cid, names in owed.items():
                with self.lock:
                    targets = self.targets(cid)
                    commit = self.device.remove_members(bytes.fromhex(cid), sorted(names))
                    self.save()
                # The one who left gets the commit too (and forgets the group); the roster no longer names them.
                left = [d for name in names for d in self.directory(name)] if names else []
                self.server.call("POST", "/v1/messages", {"to": sorted(set(targets) | set(left)), "blob": b64(MLS + bytes(commit))})
        if self._owe_delivered:
            # „zugestellt“ for what just arrived (always on – only „gelesen“ can be switched off).
            owed, self._owe_delivered = self._owe_delivered, {}
            for cid, mids in owed.items():
                sent = self._receipt(cid, "zugestellt", mids)
                if sent:
                    self.server.call("POST", "/v1/messages", {"to": sent[0], "blob": b64(MLS + bytes(sent[1]))})
        if self._owe_picture:
            # Joined a conversation: the others get my picture once I know who they are (the roster).
            with self.lock:
                owed, self._owe_picture = self._owe_picture, set()
                sends = []
                for cid in owed:
                    if self.profiles["me"]:
                        sends.append((self.targets(cid), self.device.encrypt(bytes.fromhex(cid), self._payload({"t": "profile", "bild": self.profiles["me"]}))))
                    if self.profiles.get("name"):
                        sends.append((self.targets(cid), self.device.encrypt(bytes.fromhex(cid), self._payload({"t": "name", **self.profiles["name"]}))))
                self.save()
            for targets, packet in sends:
                self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})
        return news

    def _take(self, received):
        cid = bytes(received["conversation"]).hex()
        chat = self.chats.setdefault(cid, {"title": "", "roster": {}, "messages": []})
        if received["kind"] == "removed":
            if chat.get("removed") == self.username:
                return []      # I left myself – the commit only confirms it
            chat["removed"] = received["sender"]
            return [{"chat": cid, "removed": received["sender"]}]
        if received["kind"] != "message":
            # Keys or members changed: whoever is no longer in the group gets no more packets – and everyone still in it
            # reads who took whom out, like Messages (Michelle 31ec7628).
            members = set(self.members(cid))
            gone = sorted(u for u in chat["roster"] if u not in members and not chat.get("removed"))
            chat["roster"] = {u: d for u, d in chat["roster"].items() if u in members}
            news = []
            for name in gone:
                line = self._system(chat, f"{self.display(received['sender'])} hat {self.display(name)} aus der Unterhaltung entfernt.")
                news.append({"chat": cid, "system": line["text"]})
            return news
        sender = received["sender"]
        try:
            data = json.loads(bytes(received["payload"]))
        except ValueError:
            return [{"chat": cid, "problem": f"unlesbare Nachricht von {sender}"}]
        if chat.get("intern") or data.get("intern"):
            # My devices' own group: only I may write in it (MLS says who sent it).
            if sender != self.username:
                return [{"chat": cid, "problem": "fremde Nachricht in der Gerätegruppe – verworfen"}]
            chat["intern"] = True
            if data.get("t") == "roster":
                chat["roster"] = {self.username: list((data.get("devices") or {}).get(self.username, []))}
                return []
            if data.get("t") == "verlauf":
                return self._take_history(data) if data.get("fuer") == self.device_id else []
            if data.get("t") == "einstellungen":
                for key in ("lesebestaetigungen", "tippanzeige"):
                    if key in data:
                        self.profiles[key] = bool(data[key])
                return [{"chat": None, "settings": True}]
            if data.get("t") == "gespraech" and data.get("chat") in self.chats and data.get("key") in self.SYNCED_CHAT_KEYS:
                if data.get("value") is None:
                    self.chats[data["chat"]].pop(data["key"], None)
                else:
                    self.chats[data["chat"]][data["key"]] = data["value"]
                return [{"chat": None, "settings": True}]
            if data.get("t") == "kontaktbild" and isinstance(data.get("name"), str):
                pictures = self.profiles.setdefault("kontaktbilder", {})
                if data.get("bild") and len(str(data["bild"])) <= PICTURE_MAX * 2:
                    pictures[data["name"]] = data["bild"]
                else:
                    pictures.pop(data["name"], None)
                return [{"chat": None, "settings": True}]
            if data.get("t") == "blockiert" and isinstance(data.get("name"), str):
                if data.get("an"):
                    self.blocked[data["name"]] = int(time.time())
                else:
                    self.blocked.pop(data["name"], None)
                return [{"chat": None, "settings": True}]
            if data.get("t") == "stumm" and data.get("chat") in self.chats:
                self.chats[data["chat"]]["stumm"] = bool(data.get("an"))
                return [{"chat": None, "settings": True}]
            if data.get("t") == "name":
                self.profiles["name"] = {"vorname": str(data.get("vorname") or "")[:40], "nachname": str(data.get("nachname") or "")[:40]}
                self.profiles.setdefault("names", {})[sender] = self.profiles["name"]
                return [{"chat": None, "settings": True}]
            if data.get("t") == "profile":
                self.profiles["me"] = data.get("bild") or None
                return [{"chat": None, "settings": True}]
            return []
        if sender in self.blocked and data.get("t") in ("text", "anhang", "reaktion", "tippt", "bearbeiten", "zurueckrufen", "profile", "name"):
            return []          # blocked (1f800b4f): nothing from them, no receipt, no notification
        if chat.get("geloescht") and data.get("t") in ("text", "anhang"):
            # A new message brings a deleted conversation back – what was there stays in „Zuletzt gelöscht“.
            for m in chat["messages"]:
                m.setdefault("geloescht", chat["geloescht"] if chat["geloescht"] > 1 else int(time.time()))
            chat.pop("geloescht", None)
        if data.get("t") == "roster":
            devices = data.get("devices") or {}
            # Only names that really are in the MLS group may get packets (a member cannot add a stranger's device).
            if set(devices) != set(self.members(cid)) or not all(isinstance(v, list) for v in devices.values()):
                return [{"chat": cid, "problem": f"Teilnehmerliste von {sender} passt nicht zur Gruppe – nicht übernommen"}]
            # Per device too (card da5cbfcb): as many devices per person as the MLS group has leaves for them – the server
            # cannot slip in a device that is not in the group.
            for name, listed in devices.items():
                if len(set(listed)) != len(self.device.device_keys(bytes.fromhex(cid), name)):
                    return [{"chat": cid, "problem": f"Geräteliste von {name} passt nicht zur Gruppe – nicht übernommen"}]
            known = set(chat["roster"])
            news = []
            if known:    # not the first roster after joining: say who came in, like Messages
                for name in sorted(set(devices) - known):
                    line = self._system(chat, f"{self.display(sender)} hat {self.display(name)} hinzugefügt." if name != self.username else f"{self.display(sender)} hat dich hinzugefügt.")
                    news.append({"chat": cid, "system": line["text"]})
                for name in sorted(set(devices) & known):
                    # Another device of someone I write with – worth knowing, like a changed safety code.
                    if name != self.username and len(devices[name]) > len(chat["roster"].get(name, [])):
                        line = self._system(chat, f"{self.display(name)} benutzt jetzt ein weiteres Gerät.")
                        news.append({"chat": cid, "system": line["text"]})
            elif self.profiles.get("gekoppelt") and len(devices.get(self.username, [])) > 1 and not chat["messages"]:
                # This device came later: what was written before stays on my other devices (MLS – no copies).
                self._system(chat, "Ältere Nachrichten gibt es nur auf deinen anderen Geräten.")
            chat["roster"] = devices
            chat["title"] = str(data.get("title") or chat["title"])
            if (self.profiles["me"] or self.profiles.get("name")) and sender != self.username:
                self._owe_picture.add(cid)
            return news
        if data.get("t") == "titel":
            title = str(data.get("titel") or "")[:80]
            chat["title"] = title
            line = self._system(chat, f"{self.display(sender)} hat die Gruppe „{title}“ genannt." if title else f"{self.display(sender)} hat den Gruppennamen entfernt.")
            return [{"chat": cid, "system": line["text"]}]
        if data.get("t") == "gruppenbild":
            picture = str(data.get("bild") or "")
            try:
                raw = unb64(picture) if picture else b""
            except ValueError:
                raw = b"?"
            if raw and (len(raw) > PICTURE_MAX or not raw.startswith(b"\xff\xd8\xff")):
                return [{"chat": cid, "problem": f"Gruppenbild von {sender} nicht übernommen"}]
            chat["bild"] = picture or None
            line = self._system(chat, f"{self.display(sender)} hat das Gruppenbild geändert." if picture else f"{self.display(sender)} hat das Gruppenbild entfernt.")
            return [{"chat": cid, "system": line["text"]}]
        if data.get("t") == "verlassen":
            # No more packets for the one who left; the first remaining member (by name) takes the device out of the group.
            chat["roster"].pop(sender, None)
            line = self._system(chat, f"{self.display(sender)} hat die Unterhaltung verlassen.")
            remaining = sorted(chat["roster"])
            if remaining and remaining[0] == self.username and sender in self.members(cid):
                self._owe_removal.setdefault(cid, set()).add(sender)
            return [{"chat": cid, "system": line["text"]}]
        if data.get("t") == "name":
            first, last = str(data.get("vorname") or "").strip()[:40], str(data.get("nachname") or "").strip()[:40]
            names = self.profiles.setdefault("names", {})
            if first or last:
                names[sender] = {"vorname": first, "nachname": last}
            else:
                names.pop(sender, None)
            return [{"chat": cid, "picture": sender}]     # like a new picture: shown, nothing to answer
        if data.get("t") == "profile":
            picture = data.get("bild") or ""
            try:
                raw = unb64(picture) if picture else b""
            except ValueError:
                raw = b"?"
            if raw and (len(raw) > PICTURE_MAX or not raw.startswith(b"\xff\xd8\xff")):
                return [{"chat": cid, "problem": f"Profilbild von {sender} nicht übernommen"}]
            if raw:
                self.profiles["people"][sender] = picture
            else:
                self.profiles["people"].pop(sender, None)
            return [{"chat": cid, "picture": sender}]
        if data.get("t") == "einmal_geoeffnet":
            target = next((m for m in chat["messages"] if m.get("mid") == data.get("mid") and (m.get("anhang") or {}).get("einmal")), None)
            if target is None:
                return []
            if sender == self.username:          # opened on another of my devices: gone here too
                target["geoeffnet"] = int(time.time())
                self._forget_sealed(target["anhang"]["id"])
            elif target.get("from") == self.username:
                target.setdefault("geoeffnet_von", {})[sender] = int(time.time())
            return [{"chat": cid, "opened": target.get("mid")}]
        if data.get("t") == "bildschirmfoto" and isinstance(data.get("mid"), str):
            who = "Du hast" if sender == self.username else f"{self.display(sender)} hat"
            line = self._system(chat, f"{who} ein Bildschirmfoto vom Einmal-Bild gemacht.")
            return [{"chat": cid, "system": line["text"]}]
        if data.get("t") == "reaktion":
            mid, emoji = str(data.get("mid", "")), str(data.get("emoji") or "").strip()
            target = next((m for m in chat["messages"] if m.get("mid") == mid), None)
            if target is None or (emoji and not single_emoji(emoji)):
                return []           # no text dressed up as a reaction („Gelesen“, „OK“)
            reactions = target.setdefault("reaktionen", {})
            before = reactions.get(sender, "")
            if emoji:
                reactions[sender] = emoji
            else:
                reactions.pop(sender, None)
            return [{"chat": cid, "reaction": emoji, "before": before, "from": sender,
                     "target": (self.describe(target) or target.get("text", ""))[:60]}]
        if data.get("t") in ("bearbeiten", "zurueckrufen"):
            # Only the one who wrote it, only within the windows – measured on the sender's own clock (message „at“
            # against the edit's „at“), so a phone that was offline for a day still takes a correction made in time.
            mid = str(data.get("mid", ""))
            target = next((m for m in chat["messages"] if m.get("mid") == mid), None)
            at = int(data.get("at") or 0)
            if target is None or target.get("from") != sender or target.get("zurueckgerufen"):
                return []
            if data["t"] == "bearbeiten":
                text = str(data.get("text") or "").strip()
                if (not text or at - target.get("at", 0) > EDIT_WINDOW + LATE or len(target.get("bearbeitet", [])) >= EDIT_MAX
                        or not target.get("text")):
                    return []
                self._apply_edit(target, text, at)
                return [{"chat": cid, "edited": mid, "from": sender}]
            if at - target.get("at", 0) > UNSEND_WINDOW + LATE:
                return []
            self._apply_unsend(chat, target)
            return [{"chat": cid, "unsent": mid, "from": sender}]
        if data.get("t") == "tippt":
            # Late ones (fetched after a while offline) mean nothing any more.
            if sender == self.username or not self.typing_indicator or abs(time.time() - int(data.get("at") or 0)) > TYPING_LATE:
                return []
            self._typing.setdefault(cid, {})[sender] = time.time()
            return [{"chat": cid, "typing": sender}]
        if data.get("t") == "quittung":
            kind = data.get("art")
            mids = {str(m) for m in (data.get("mids") or []) if isinstance(m, str)}
            if kind in ("zugestellt", "gelesen") and sender != self.username:
                at = int(data.get("at") or time.time())
                for m in chat["messages"]:
                    if m.get("from") == self.username and m.get("mid") in mids:
                        m.setdefault(kind, {})[sender] = at
            return [{"chat": cid, "receipt": kind}]
        if data.get("t") == "anhang":
            info = {k: data.get(k) for k in ("id", "schluessel", "sha256", "name", "mime", "groesse", "art", "dauer", "vorschau") if data.get(k) is not None}
            for flag in ("einmal", "geheim"):
                if data.get(flag) is True and info.get("art") == "bild":
                    info[flag] = True
            if info.get("einmal"):
                info.pop("vorschau", None)
            try:
                good = (re.fullmatch(r"[0-9a-f]{32}", str(info.get("id"))) and len(unb64(info.get("schluessel", ""))) == 32
                        and re.fullmatch(r"[0-9a-f]{64}", str(info.get("sha256"))) and info.get("art") in KINDS
                        and len(info.get("vorschau", "")) <= 64 * 1024)
            except (ValueError, TypeError):
                good = False
            if not good:
                return [{"chat": cid, "problem": f"Anhang von {sender} unvollständig – nicht übernommen"}]
            info["name"] = str(info.get("name", "Datei"))[:200].replace("/", "_")
            message = {"from": sender, "text": str(data.get("text") or ""), "at": int(data.get("at") or time.time()), "anhang": info}
            self._typing.get(cid, {}).pop(sender, None)
            self._remember_mid(cid, message, data)
            chat["messages"].append(message)
            return [{"chat": cid, "from": sender, "text": self.describe(message), "at": message["at"], "anhang": info}]
        if data.get("t") == "text":
            message = {"from": sender, "text": str(data.get("text", "")), "at": int(data.get("at") or time.time())}
            answer = data.get("antwort")
            if isinstance(answer, dict) and re.fullmatch(r"[0-9a-f]{16}", str(answer.get("mid", ""))):
                message["antwort"] = {"mid": answer["mid"], "from": str(answer.get("from", ""))[:40], "text": str(answer.get("text", ""))[:120]}
            self._typing.get(cid, {}).pop(sender, None)
            self._remember_mid(cid, message, data)
            chat["messages"].append(message)
            news = {"chat": cid, **{k: v for k, v in message.items() if k != "mid"}}
            if (len(chat.get("roster", {})) > 2 or chat.get("title")) and self.mentions_me(cid, message["text"]):
                news["mention"] = True      # „@ich“ or „@alle“: a notification even when the chat is muted (iOS)
            return [news]
        return []

    UNREADABLE = "Eine Nachricht konnte nicht entschlüsselt werden."

    def _unreadable(self, tag, body, error):
        try:
            cid = bytes(lichat_core.Device.conversation_of(body)).hex() if tag == MLS else None
        except ValueError:
            cid = None
        if cid in self.chats:
            line = self._system(self.chats[cid], self.UNREADABLE)
            return {"chat": cid, "system": line["text"]}
        return {"chat": None, "problem": f"Paket verworfen: {error}"}

    def _remember_mid(self, cid, message, data):
        mid = data.get("mid")
        if isinstance(mid, str) and re.fullmatch(r"[0-9a-f]{16}", mid):
            message["mid"] = mid
            if message.get("from") != self.username:      # my own, written on another of my devices: no receipt
                self._owe_delivered.setdefault(cid, []).append(mid)

    def title(self, cid):
        chat = self.chats[cid]
        if chat.get("title"):
            return chat["title"]
        names = [u for u in chat["roster"] if u != self.username] or [u for u in self.members(cid) if u != self.username]
        return ", ".join(sorted(self.display(u) for u in names)) or "(leer)"
