"""The app's sealing key in the GNOME keyring (libsecret) – never as a file next to the data it seals."""

import base64
import os

import gi

gi.require_version("Secret", "1")
from gi.repository import Secret  # noqa: E402

SCHEMA = Secret.Schema.new("io.github.veritasx1.LiChat", Secret.SchemaFlags.NONE, {"folder": Secret.SchemaAttributeType.STRING})


def load(folder):
    """The key for the account in [folder], or None."""
    value = Secret.password_lookup_sync(SCHEMA, {"folder": str(folder)}, None)
    return base64.b64decode(value) if value else None


def create(folder):
    key = os.urandom(32)
    Secret.password_store_sync(SCHEMA, {"folder": str(folder)}, Secret.COLLECTION_DEFAULT, "LiChat – Schlüssel des Geräts",
                               base64.b64encode(key).decode(), None)
    return key
