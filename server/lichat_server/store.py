"""Server storage (SQLite). The server only ever sees opaque, end-to-end encrypted blobs:
MLS key packages and MLS messages. It keeps no contact lists, no group names, no message text
and – for messages – not even the sender (sealed sender: the sender is inside the ciphertext).

Rules from the concept (Olaf's note 1a8a84fc, Michelle's test plan S3):
- a delivered message is deleted as soon as the device acknowledges it,
- a message nobody fetched is deleted after 30 days,
- registration only with a single-use invitation, no phone number.
"""

import hashlib
import secrets
import sqlite3
import threading
import time
from pathlib import Path

KEEP_UNFETCHED = 30 * 24 * 3600      # seconds
MAX_KEY_PACKAGES = 100               # per device
MAILBOX_BYTES = 32 * 1024 * 1024     # per device: nobody fills a stranger's mailbox (Michelle 4ba8dc07)
MAILBOX_MESSAGES = 5000
INVITE_DAYS = 14
INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"


def invite_key(code):
    """What a person typed, as stored: upper case, without spaces and dashes, then in groups of four again.
    Older codes (before 09.10., mixed case) are looked up as they are."""
    plain = "".join(ch for ch in str(code).upper() if ch.isalnum())
    return "-".join(plain[i:i + 4] for i in range(0, len(plain), 4))
ATTACHMENT_BYTES = 100 * 1024 * 1024        # one attachment (a video from the phone)
ATTACHMENTS_PER_DEVICE = 1024 * 1024 * 1024  # all of one device's attachments still kept


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


class Store:
    def __init__(self, data_dir, clock=time.time):
        self.dir = Path(data_dir)
        self.dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.clock = clock
        self.lock = threading.Lock()
        # One connection per thread (09.10.: a shared one broke under parallel waiting fetches – „bad parameter or other
        # API misuse“, HTTP 500). WAL lets them read side by side; writes stay in order under self.lock.
        self.path = self.dir / "lichat.db"
        self.local = threading.local()
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, expires REAL NOT NULL, note TEXT);
            CREATE TABLE IF NOT EXISTS accounts (username TEXT PRIMARY KEY, created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY, username TEXT NOT NULL REFERENCES accounts(username) ON DELETE CASCADE,
                token TEXT NOT NULL UNIQUE, identity TEXT NOT NULL, created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS key_packages (
                id INTEGER PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                blob BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS last_resort (
                device TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE, blob BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                blob BLOB NOT NULL, received REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS messages_device ON messages(device, id);
            -- Attachments (card 9780f3a0): encrypted on the phone, the server keeps opaque bytes – no name, no type.
            CREATE TABLE IF NOT EXISTS attachments (
                id TEXT PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                size INTEGER NOT NULL, created REAL NOT NULL);
        """)
        # Databases from before 09.10. still have the ntfy column „push“ – gone with ntfy (Michelle).
        if "push" in [row[1] for row in self.db.execute("PRAGMA table_info(devices)")]:
            self.db.execute("ALTER TABLE devices DROP COLUMN push")

    @property
    def db(self):
        connection = getattr(self.local, "connection", None)
        if connection is None:
            connection = sqlite3.connect(self.path, isolation_level=None, timeout=30)
            connection.execute("PRAGMA foreign_keys=ON")
            # Deleted rows are overwritten, not only unlinked – on every system (a255c433).
            connection.execute("PRAGMA secure_delete=ON")
            self.local.connection = connection
        return connection

    # Invitations ---------------------------------------------------------------------------------

    def create_invite(self, note="", days=INVITE_DAYS):
        """A code people can type: 16 signs from an alphabet without I, L, O, 0, 1 (nothing to mix up – Olaf could not
        tell I from l), in groups of four: „K7QX-…“. 16 of 31 signs ≈ 79 bits, plus the register limit of 10 per hour."""
        code = "-".join("".join(secrets.choice(INVITE_ALPHABET) for _ in range(4)) for _ in range(4))
        with self.lock:
            self.db.execute("INSERT INTO invites VALUES (?, ?, ?)", (code, self.clock() + days * 86400, note))
        return code

    # Accounts and devices -----------------------------------------------------------------------

    def register(self, invite, username, identity):
        """Creates the account with its first device. Returns (device_id, token) or raises KeyError /
        ValueError. A taken name does not use up the invitation (Olaf 09.10. – trying another name must work); guessing
        names stays limited by the register limit (10 per hour and address)."""
        with self.lock:
            code = str(invite).strip()
            row = self.db.execute("SELECT expires FROM invites WHERE code = ?", (code,)).fetchone()
            if row is None:
                code = invite_key(code)
                row = self.db.execute("SELECT expires FROM invites WHERE code = ?", (code,)).fetchone()
            if row is None or row[0] < self.clock():
                raise KeyError("invite")
            if self.db.execute("SELECT 1 FROM accounts WHERE username = ?", (username,)).fetchone():
                raise ValueError("taken")
            self.db.execute("DELETE FROM invites WHERE code = ?", (code,))
            self.db.execute("INSERT INTO accounts VALUES (?, ?)", (username, self.clock()))
            return self._add_device(username, identity)

    def add_device(self, username, identity):
        """Another device of an existing account (called by an already signed-in device)."""
        with self.lock:
            return self._add_device(username, identity)

    def _add_device(self, username, identity):
        device = secrets.token_hex(8)
        token = secrets.token_urlsafe(32)
        self.db.execute("INSERT INTO devices (id, username, token, identity, created) VALUES (?, ?, ?, ?, ?)",
                        (device, username, token_hash(token), identity, self.clock()))
        return device, token

    def device_for_token(self, token):
        """(device_id, username) for a bearer token, or None."""
        row = self.db.execute("SELECT id, username FROM devices WHERE token = ?", (token_hash(token),)).fetchone()
        return tuple(row) if row else None

    def remove_device(self, device):
        with self.lock:
            self.db.execute("DELETE FROM devices WHERE id = ?", (device,))

    def devices(self, username):
        """Public directory entry: device ids and identity keys – what a sender needs to verify."""
        rows = self.db.execute("SELECT id, identity FROM devices WHERE username = ? ORDER BY created",
                               (username,)).fetchall()
        return [{"device": d, "identity": i} for d, i in rows]

    # MLS key packages ---------------------------------------------------------------------------

    def add_key_packages(self, device, blobs):
        with self.lock:
            have = self.db.execute("SELECT COUNT(*) FROM key_packages WHERE device = ?", (device,)).fetchone()[0]
            if have + len(blobs) > MAX_KEY_PACKAGES:
                raise ValueError("too many")
            self.db.executemany("INSERT INTO key_packages (device, blob) VALUES (?, ?)", [(device, b) for b in blobs])
            return have + len(blobs)

    def set_last_resort(self, device, blob):
        """The device's last-resort key package (MLS RFC 9420 §16.8): handed out when the supply is empty and never
        used up, so nobody can make a device unreachable by claiming all its packages (Michelle 3c7ea47d)."""
        with self.lock:
            self.db.execute("INSERT OR REPLACE INTO last_resort VALUES (?, ?)", (device, blob))

    def claim_key_packages(self, username):
        """One key package per device of the user; each package is used only once (MLS) – when none is left, the
        device's last-resort package (kept)."""
        out = []
        with self.lock:
            for device, _identity in self.db.execute(
                    "SELECT id, identity FROM devices WHERE username = ? ORDER BY created", (username,)).fetchall():
                row = self.db.execute("SELECT id, blob FROM key_packages WHERE device = ? ORDER BY id LIMIT 1",
                                      (device,)).fetchone()
                if row:
                    self.db.execute("DELETE FROM key_packages WHERE id = ?", (row[0],))
                    out.append({"device": device, "key_package": row[1]})
                else:
                    last = self.db.execute("SELECT blob FROM last_resort WHERE device = ?", (device,)).fetchone()
                    if last:
                        out.append({"device": device, "key_package": last[0], "last_resort": True})
        return out

    def key_package_count(self, device):
        return self.db.execute("SELECT COUNT(*) FROM key_packages WHERE device = ?", (device,)).fetchone()[0]

    # Mailboxes -----------------------------------------------------------------------------------

    def deliver(self, devices, blob):
        """Puts one encrypted message into the mailbox of each device. Unknown devices are skipped
        and reported, so the sender can refresh its view of the recipient; a full mailbox too."""
        known, unknown, full = [], [], []
        with self.lock:
            for device in devices:
                if self.db.execute("SELECT 1 FROM devices WHERE id = ?", (device,)).fetchone():
                    count, size = self.db.execute("SELECT COUNT(*), COALESCE(SUM(LENGTH(blob)), 0) FROM messages WHERE device = ?",
                                                  (device,)).fetchone()
                    if count >= MAILBOX_MESSAGES or size + len(blob) > MAILBOX_BYTES:
                        full.append(device)
                        continue
                    self.db.execute("INSERT INTO messages (device, blob, received) VALUES (?, ?, ?)",
                                    (device, blob, self.clock()))
                    known.append(device)
                else:
                    unknown.append(device)
        return known, unknown, full

    def fetch(self, device, limit=100):
        rows = self.db.execute("SELECT id, blob, received FROM messages WHERE device = ? ORDER BY id LIMIT ?",
                               (device, limit)).fetchall()
        return [{"id": i, "blob": b, "received": r} for i, b, r in rows]

    def acknowledge(self, device, ids):
        """Delivered: gone at once (only the device's own messages)."""
        with self.lock:
            self.db.executemany("DELETE FROM messages WHERE device = ? AND id = ?", [(device, i) for i in ids])

    def purge(self):
        """Unfetched messages and attachments older than 30 days and expired invitations go."""
        with self.lock:
            now = self.clock()
            gone = self.db.execute("DELETE FROM messages WHERE received < ?", (now - KEEP_UNFETCHED,)).rowcount
            self.db.execute("DELETE FROM invites WHERE expires < ?", (now,))
            old = [row[0] for row in self.db.execute("SELECT id FROM attachments WHERE created < ?", (now - KEEP_UNFETCHED,))]
            self.db.executemany("DELETE FROM attachments WHERE id = ?", [(i,) for i in old])
        for attachment in old:
            self.attachment_path(attachment).unlink(missing_ok=True)
        self.forget_orphans()
        return gone

    # Attachments ---------------------------------------------------------------------------------

    def attachment_path(self, attachment):
        return self.dir / "anhaenge" / attachment

    def keep_attachment(self, device, stream, size):
        """Stores [size] bytes from [stream] under a new random id (128 bits – whoever knows it may fetch it; it only
        travels inside the end-to-end encrypted message). Over the device's quota: ValueError."""
        with self.lock:
            used = self.db.execute("SELECT COALESCE(SUM(size), 0) FROM attachments WHERE device = ?", (device,)).fetchone()[0]
            if used + size > ATTACHMENTS_PER_DEVICE:
                raise ValueError("Kontingent")
        attachment = secrets.token_hex(16)
        folder = self.dir / "anhaenge"
        folder.mkdir(exist_ok=True, mode=0o700)
        part = folder / (attachment + ".teil")
        written = 0
        with open(part, "wb") as out:
            while written < size:
                chunk = stream.read(min(1 << 20, size - written))
                if not chunk:
                    break
                out.write(chunk)
                written += len(chunk)
        if written != size:
            part.unlink(missing_ok=True)
            raise EOFError("unvollständig")
        part.rename(folder / attachment)
        with self.lock:
            self.db.execute("INSERT INTO attachments VALUES (?, ?, ?, ?)", (attachment, device, size, self.clock()))
        return attachment

    def attachment(self, attachment):
        """(path, size) of a kept attachment, or None."""
        row = self.db.execute("SELECT size FROM attachments WHERE id = ?", (attachment,)).fetchone()
        path = self.attachment_path(attachment)
        return (path, row[0]) if row and path.exists() else None

    def forget_orphans(self):
        """Files whose device was signed out (rows gone by cascade) – removed from the disk too."""
        folder = self.dir / "anhaenge"
        if not folder.exists():
            return
        known = {row[0] for row in self.db.execute("SELECT id FROM attachments")}
        for path in folder.iterdir():
            if path.name not in known and not (path.name.endswith(".teil") and path.stat().st_mtime > self.clock() - 3600):
                path.unlink(missing_ok=True)
