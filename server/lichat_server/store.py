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


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


class Store:
    def __init__(self, data_dir, clock=time.time):
        self.dir = Path(data_dir)
        self.dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.clock = clock
        self.lock = threading.Lock()
        self.db = sqlite3.connect(self.dir / "lichat.db", check_same_thread=False, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        # Deleted rows are overwritten, not only unlinked – on every system, not only where SQLite was built so (a255c433).
        self.db.execute("PRAGMA secure_delete=ON")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, expires REAL NOT NULL, note TEXT);
            CREATE TABLE IF NOT EXISTS accounts (username TEXT PRIMARY KEY, created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY, username TEXT NOT NULL REFERENCES accounts(username) ON DELETE CASCADE,
                token TEXT NOT NULL UNIQUE, identity TEXT NOT NULL, push TEXT, created REAL NOT NULL);  -- push: unused since 09.10. (no ntfy)
            CREATE TABLE IF NOT EXISTS key_packages (
                id INTEGER PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                blob BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS last_resort (
                device TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE, blob BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY, device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                blob BLOB NOT NULL, received REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS messages_device ON messages(device, id);
        """)

    # Invitations ---------------------------------------------------------------------------------

    def create_invite(self, note="", days=INVITE_DAYS):
        code = secrets.token_urlsafe(18)
        with self.lock:
            self.db.execute("INSERT INTO invites VALUES (?, ?, ?)", (code, self.clock() + days * 86400, note))
        return code

    # Accounts and devices -----------------------------------------------------------------------

    def register(self, invite, username, identity):
        """Creates the account with its first device. Returns (device_id, token) or raises KeyError /
        ValueError. The invitation is used up, also if the name is taken (no guessing with one code)."""
        with self.lock:
            row = self.db.execute("SELECT expires FROM invites WHERE code = ?", (invite,)).fetchone()
            if row is None or row[0] < self.clock():
                raise KeyError("invite")
            self.db.execute("DELETE FROM invites WHERE code = ?", (invite,))
            if self.db.execute("SELECT 1 FROM accounts WHERE username = ?", (username,)).fetchone():
                raise ValueError("taken")
            self.db.execute("INSERT INTO accounts VALUES (?, ?)", (username, self.clock()))
            return self._add_device(username, identity)

    def add_device(self, username, identity):
        """Another device of an existing account (called by an already signed-in device)."""
        with self.lock:
            return self._add_device(username, identity)

    def _add_device(self, username, identity):
        device = secrets.token_hex(8)
        token = secrets.token_urlsafe(32)
        self.db.execute("INSERT INTO devices VALUES (?, ?, ?, ?, ?, ?)",
                        (device, username, token_hash(token), identity, None, self.clock()))
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
        """Unfetched messages older than 30 days and expired invitations go."""
        with self.lock:
            now = self.clock()
            gone = self.db.execute("DELETE FROM messages WHERE received < ?", (now - KEEP_UNFETCHED,)).rowcount
            self.db.execute("DELETE FROM invites WHERE expires < ?", (now,))
        return gone
