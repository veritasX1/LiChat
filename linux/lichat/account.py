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

Profile pictures (Michelle B7) travel only inside the conversations, end-to-end like a text: {"t": "profile", "bild":
<JPEG base64>}. Made square, 512 px, JPEG, without EXIF (no place, no camera, Michelle 09.10.) before they leave.
"""

import base64
import io
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
PICTURE_PX = 512
PICTURE_MAX = 150 * 1024   # a received picture larger than this is not kept
SUPPLY = 20          # key packages uploaded at the start
TIMEOUT = 20


class LiChatError(Exception):
    """Something the user is told plainly (server refused, unknown chat, wrong key package …)."""


def b64(data):
    return base64.b64encode(data).decode()


def unb64(text):
    return base64.b64decode(text)


def short(conversation):
    """The id shown to people: the first 8 hex digits of the MLS group id."""
    return conversation.hex()[:8]


def default_folder(profile):
    base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
    return base / "lichat-cli" / profile


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
                message = json.loads(error.read()).get("error", "")
            except ValueError:
                message = ""
            raise LiChatError(f"Server: {message or error.reason} ({error.code})") from None
        except (urllib.error.URLError, OSError) as error:
            raise LiChatError(f"Server nicht erreichbar: {getattr(error, 'reason', error)}") from None


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
        # Held while the state changes (never during a call to the server): the app's window reads under it too.
        self.lock = threading.RLock()
        self.chats = self._unseal("chats.bin", CHATS_MAGIC, {})
        self.profiles = self._unseal("profile.bin", PROFILE_MAGIC, {"me": None, "people": {}})
        self._owe_picture = set()   # chats that learn my picture once their roster is known
        self.server = Server(self.info["server"], self.info["token"])

    # ------------------------------------------------------------------------------------------ setup

    @classmethod
    def register(cls, folder, server, invite, username, key=None):
        folder = Path(folder)
        if (folder / "account.json").exists():
            raise LiChatError(f"In {folder} gibt es schon ein Konto")
        folder.mkdir(parents=True, exist_ok=True)
        os.chmod(folder, 0o700)
        anonymous = Server(server)
        device = lichat_core.Device(username)
        own_key = key is None
        key = os.urandom(32) if own_key else key
        answer = anonymous.call("POST", "/v1/register", {"invite": invite, "username": username,
                                                         "identity": b64(device.identity_key())})
        info = {"server": anonymous.url, "username": username, "device": answer["device"], "token": answer["token"]}
        # Key packages are made (and their private halves kept in the state) before they are offered.
        packages = device.key_packages(SUPPLY)
        reserve = device.last_resort_key_package()
        if own_key:
            write_private(folder / "key", key)
        write_private(folder / "state.bin", bytes(device.save(key)))
        write_private(folder / "account.json", json.dumps(info, indent=1).encode())
        account = cls(folder, None if own_key else key)
        account.save()
        account.server.call("POST", "/v1/key-packages", {"key_packages": [b64(p) for p in packages]})
        account.server.call("PUT", "/v1/key-packages/last-resort", {"key_package": b64(reserve)})
        return account

    @property
    def username(self):
        return self.info["username"]

    @property
    def device_id(self):
        return self.info["device"]

    # ------------------------------------------------------------------------------------------ storage

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
        """The user's devices and public keys, as the server lists them."""
        return {d["device"]: unb64(d["identity"]) for d in self.server.call("GET", f"/v1/users/{urllib.parse.quote(username)}")["devices"]}

    def start(self, usernames, title=""):
        """A conversation with these people (all their devices). Every claimed key package must belong to the user and
        the device the directory names – the server cannot slip in a device of its own."""
        others = sorted({u for u in usernames if u != self.username})
        if not others:
            raise LiChatError("Mit wem? Mindestens eine andere Person")
        roster, packages = {self.username: [self.device_id]}, []
        for user in others:
            listed = self.directory(user)
            claimed = self.server.call("POST", f"/v1/key-packages/{urllib.parse.quote(user)}", {})["key_packages"]
            if not claimed:
                raise LiChatError(f"{user} hat gerade keinen Schlüssel frei")
            for item in claimed:
                package = unb64(item["key_package"])
                owner, key = lichat_core.Device.key_package_owner(package)
                if owner != user or listed.get(item["device"]) != bytes(key):
                    raise LiChatError(f"Key-Package passt nicht zu {user} – Gespräch nicht begonnen")
                roster.setdefault(user, []).append(item["device"])
                packages.append(package)
        with self.lock:
            conversation, welcome = self.device.start_conversation(packages)
            conversation = bytes(conversation)
            cid = conversation.hex()
            self.chats[cid] = {"title": title, "roster": roster, "messages": []}
            hello = self._payload({"t": "roster", "devices": roster, "title": title})
            packets = [self.device.encrypt(conversation, hello)]
            if self.profiles["me"]:
                packets.append(self.device.encrypt(conversation, self._payload({"t": "profile", "bild": self.profiles["me"]})))
            self.save()
            targets = self.targets(cid)
        self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(WELCOME + bytes(welcome))})
        for packet in packets:
            self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})
        return cid

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

    def picture(self, username):
        """The JPEG bytes of [username]'s picture (mine too), or None."""
        value = self.profiles["me"] if username == self.username else self.profiles["people"].get(username)
        return unb64(value) if value else None

    def targets(self, cid):
        return sorted({d for devices in self.chats[cid]["roster"].values() for d in devices} - {self.device_id})

    @staticmethod
    def _payload(data):
        return json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()

    def send(self, cid, text):
        text = text.strip()
        if not text:
            raise LiChatError("Leere Nachricht")
        if self.chats[cid].get("removed"):
            raise LiChatError(f"{self.chats[cid]['removed']} hat dich aus dem Gespräch entfernt")
        at = int(time.time())
        with self.lock:
            packet = self.device.encrypt(bytes.fromhex(cid), self._payload({"t": "text", "text": text, "at": at}))
            self.chats[cid]["messages"].append({"from": self.username, "text": text, "at": at})
            self.save()
            targets = self.targets(cid)
        answer = self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})
        return answer

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

    def members(self, cid):
        if self.chats.get(cid, {}).get("removed"):
            return [self.username]
        return list(self.device.members(bytes.fromhex(cid)))

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
        path = f"/v1/messages?warten={int(wait)}" if wait else "/v1/messages"
        waiting = self.server.call("GET", path, timeout=TIMEOUT + wait)["messages"]
        with self.lock:
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
                    # Replayed, forged or from an epoch long gone: the core refused it – it is dropped, not kept.
                    news.append({"chat": None, "problem": f"Paket verworfen: {error}"})
                done.append(item["id"])
            if not done:
                return news
            self.save()
        self.server.call("POST", "/v1/messages/ack", {"ids": done})
        if joined:
            # Each welcome used up a key package of the supply: replace it (state saved before offering).
            with self.lock:
                packages = self.device.key_packages(joined)
                self.save()
            self.server.call("POST", "/v1/key-packages", {"key_packages": [b64(p) for p in packages]})
        if self._owe_picture:
            # Joined a conversation: the others get my picture once I know who they are (the roster).
            with self.lock:
                owed, self._owe_picture = self._owe_picture, set()
                sends = [(self.targets(cid), self.device.encrypt(bytes.fromhex(cid), self._payload({"t": "profile", "bild": self.profiles["me"]})))
                         for cid in owed if self.profiles["me"]]
                self.save()
            for targets, packet in sends:
                self.server.call("POST", "/v1/messages", {"to": targets, "blob": b64(MLS + bytes(packet))})
        return news

    def _take(self, received):
        cid = bytes(received["conversation"]).hex()
        chat = self.chats.setdefault(cid, {"title": "", "roster": {}, "messages": []})
        if received["kind"] == "removed":
            chat["removed"] = received["sender"]
            return [{"chat": cid, "removed": received["sender"]}]
        if received["kind"] != "message":
            # Keys or members changed: whoever is no longer in the group gets no more packets – and everyone still in it
            # reads who took whom out, like Messages (Michelle 31ec7628).
            members = set(self.members(cid))
            gone = sorted(u for u in chat["roster"] if u not in members)
            chat["roster"] = {u: d for u, d in chat["roster"].items() if u in members}
            news = []
            for name in gone:
                line = self._system(chat, f"{received['sender']} hat {name} aus der Unterhaltung entfernt.")
                news.append({"chat": cid, "system": line["text"]})
            return news
        sender = received["sender"]
        try:
            data = json.loads(bytes(received["payload"]))
        except ValueError:
            return [{"chat": cid, "problem": f"unlesbare Nachricht von {sender}"}]
        if data.get("t") == "roster":
            devices = data.get("devices") or {}
            # Only names that really are in the MLS group may get packets (a member cannot add a stranger's device).
            if set(devices) != set(self.members(cid)) or not all(isinstance(v, list) for v in devices.values()):
                return [{"chat": cid, "problem": f"Teilnehmerliste von {sender} passt nicht zur Gruppe – nicht übernommen"}]
            chat["roster"] = devices
            chat["title"] = str(data.get("title") or chat["title"])
            if self.profiles["me"] and sender != self.username:
                self._owe_picture.add(cid)
            return []
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
        if data.get("t") == "text":
            message = {"from": sender, "text": str(data.get("text", "")), "at": int(data.get("at") or time.time())}
            chat["messages"].append(message)
            return [{"chat": cid, **message}]
        return []

    def title(self, cid):
        chat = self.chats[cid]
        if chat.get("title"):
            return chat["title"]
        names = [u for u in chat["roster"] if u != self.username] or [u for u in self.members(cid) if u != self.username]
        return ", ".join(sorted(names)) or "(leer)"
