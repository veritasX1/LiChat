"""LiChat for Ubuntu – end-to-end encrypted chats in the look of Messages on the Mac (macOS 15).

    python3 main.py            the window (first start: sign in with an invitation)

Sending and starting run in one worker thread; a listener thread keeps one waiting fetch open at the server (no push
service – Olaf 09.10.: „wie bei einem echten Messenger“). The window gets results through GLib.idle_add. Closing the
window keeps LiChat running in the background (GNOME notifications); it starts after login unless switched off.

    python3 main.py --hintergrund   start without the window (the autostart entry)
    LICHAT_ORDNER=/pfad python3 main.py   a second, separate LiChat (tests, a team account): its own folder, its key as a
                                          file there instead of the keyring, its own app id – Olaf's LiChat stays untouched
"""

import json
import math
import os
import re
import queue
import sys
import threading
import urllib.parse
import time
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Graphene, Gtk, Pango  # noqa: E402

from .account import ALBUM_MAX, MENTION, TYPING_FRESH, Account, LiChatError, find_links, short, unb64  # noqa: E402

APP_ID = "io.github.veritasx1.LiChat"
HERE = Path(__file__).resolve().parent
WAIT_SECONDS = 50     # one waiting fetch; the server answers at once when something arrives
AUTOSTART = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "autostart" / f"{APP_ID}.desktop"
# One-time pictures (fe9f2029): under Wayland nothing can block or even notice a screenshot, so they open only on the
# phone (Olaf 09.10. 19:16: „einverstanden“). True opens them here too, in memory only, never as a file.
ONCE_ON_DESKTOP = False

DEFAULTS = {"hintergrund": True, "vorschau": False, "erklaert": False}
GAP = 15 * 60          # a time stamp over the bubbles after this much quiet (like Messages)


def data_folder():
    base = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
    return base / "lichat" / "konto"


def load_css():
    provider = Gtk.CssProvider()
    provider.load_from_path(str(HERE / "style.css"))
    Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)


def initials(name):
    parts = [p for p in name.replace(".", " ").replace("_", " ").replace("-", " ").split() if p]
    return ("".join(p[0] for p in parts[:2]) or "?").upper()


def avatar(name, size, picture=None):
    """A round picture – the person's profile picture (end-to-end, account.picture) or their initials."""
    if picture:
        try:
            texture = Gdk.Texture.new_from_bytes(GLib.Bytes.new(picture))
        except GLib.Error:
            texture = None
        if texture is not None:
            image = Gtk.Image.new_from_paintable(texture)   # pixel_size keeps it at [size] (a Picture asks for 512 px)
            image.set_pixel_size(size)
            frame = Gtk.Box(css_classes=["lichat-avatar-picture"], overflow=Gtk.Overflow.HIDDEN,
                            valign=Gtk.Align.CENTER, halign=Gtk.Align.CENTER)
            frame.set_size_request(size, size)
            frame.append(image)
            return frame
    label = Gtk.Label(label=initials(name), css_classes=["lichat-avatar", "lichat-avatar-big" if size > 36 else "lichat-avatar-small"])
    label.set_size_request(size, size)
    label.set_valign(Gtk.Align.CENTER)
    return label


def when_short(at):
    """Like Messages' list: the time today, „Gestern“, the weekday this week, else the date."""
    then, now = time.localtime(at), time.localtime()
    days = (time.mktime(now[:3] + (0, 0, 0) + now[6:]) - time.mktime(then[:3] + (0, 0, 0) + then[6:])) / 86400
    if days < 1:
        return time.strftime("%H:%M", then)
    if days < 2:
        return "Gestern"
    if days < 7:
        return ("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")[then.tm_wday]
    return time.strftime("%d.%m.%y", then)


def when_long(at):
    then = time.localtime(at)
    day = when_short(at)
    return f"Heute {time.strftime('%H:%M', then)}" if ":" in day else f"{day}, {time.strftime('%H:%M', then)}"


def locked(method):
    """Reads the account under its lock – the worker may be changing it at the same moment."""
    def run(self, *args):
        with self.account.lock:
            return method(self, *args)
    return run


class Listener:
    """The standing connection: one waiting fetch after the other. After an error it tries again, waiting longer each
    time (2 s … 60 s) – no network, server restarting."""

    def __init__(self, account, on_news, on_error):
        self.account, self.on_news, self.on_error = account, on_news, on_error
        self.running = True
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        pause = 2
        while self.running:
            try:
                news = self.account.fetch(wait=WAIT_SECONDS)
            except (LiChatError, ValueError, OSError) as error:
                GLib.idle_add(self.on_error, str(error))
                time.sleep(pause)
                pause = min(pause * 2, 60)
                continue
            pause = 2
            GLib.idle_add(self.on_news, news)


def autostart(on):
    """The entry in ~/.config/autostart: LiChat starts after login without its window (Michelle Z7: can be switched off)."""
    if on:
        AUTOSTART.parent.mkdir(parents=True, exist_ok=True)
        main = Path(__file__).resolve().parent.parent / "main.py"
        AUTOSTART.write_text("[Desktop Entry]\nType=Application\nName=LiChat\nComment=Nachrichten im Hintergrund empfangen\n"
                             f"Exec={sys.executable} {main} --hintergrund\nIcon=mail-message-new\nX-GNOME-Autostart-enabled=true\n")
    elif AUTOSTART.exists():
        AUTOSTART.unlink()


def album_runs(messages):
    """Photos picked together (5749ebd0): {leader mid: its photos in order} and the mids drawn inside a leader. Only
    neighbours from the same sender with the same album id belong together; the last one draws the album."""
    leaders, hidden, k = {}, set(), 0
    while k < len(messages):
        album = (messages[k].get("album") or {}).get("id")
        end = k
        if album and not messages[k].get("zurueckgerufen"):
            while end + 1 < len(messages) and (messages[end + 1].get("album") or {}).get("id") == album \
                    and messages[end + 1].get("from") == messages[k].get("from") and not messages[end + 1].get("zurueckgerufen"):
                end += 1
        if end > k:
            leaders[messages[end].get("mid")] = sorted(messages[k:end + 1], key=lambda m: m["album"].get("nr", 0))
            hidden.update(m.get("mid") for m in messages[k:end])
        k = end + 1
    return leaders, hidden


def bubble_markup(text):
    """The bubble's text: links (web, mail, phone – 22c918ce) clickable, „@name“ bold (d54175ed); everything escaped."""
    links = find_links(text)
    out, at = [], 0

    def plain(piece):
        return MENTION.sub(lambda f: f"<b>{f.group(0)}</b>", GLib.markup_escape_text(piece))
    for start, end, target in links:
        out.append(plain(text[at:start]))
        out.append(f'<a href="{GLib.markup_escape_text(target)}">{GLib.markup_escape_text(text[start:end])}</a>')
        at = end
    out.append(plain(text[at:]))
    return "".join(out)


def typing_dots(dark=False, group=False):
    """„… tippt“ as Olaf chose it (884f7c8a): alone a thought bubble – the wave in a grey capsule with two small circles at
    its lower left; in a group a smaller wave (the line „X schreibt …“ above it comes from the window). The wave: each dot
    in turn darker (#B0B2B4 → #606060) and ~38 % bigger, peaks at 380/580/760 ms, a cycle of 1.84 s with a rest. Without
    animations (GNOME „Animationen“ off) the dots breathe together – lighter and darker, no movement."""
    width, height = (44, 24) if group else (66, 46)
    area = Gtk.DrawingArea(content_width=width, content_height=height, halign=Gtk.Align.START, margin_top=2)
    capsule = (0.15, 0.145, 0.165) if dark else (0.925, 0.925, 0.925)
    rest = (0.42, 0.42, 0.45) if dark else (0.69, 0.70, 0.71)
    peak = (0.90, 0.90, 0.92) if dark else (0.376, 0.376, 0.376)
    moving = Gtk.Settings.get_default().get_property("gtk-enable-animations")
    start = GLib.get_monotonic_time()

    def draw(_area, cr, w, h):
        tall = 24 if group else 34
        left = 0 if group else 8
        radius = tall / 2
        cr.set_source_rgb(*capsule)
        cr.new_sub_path()
        cr.arc(left + radius, radius, radius, math.pi / 2, 3 * math.pi / 2)
        cr.arc(w - radius, radius, radius, 3 * math.pi / 2, math.pi / 2)
        cr.close_path()
        cr.fill()
        if not group:          # the thought bubble's two little circles
            cr.arc(7, tall + 2, 6, 0, 2 * math.pi)
            cr.fill()
            cr.arc(2.5, tall + 9, 3, 0, 2 * math.pi)
            cr.fill()
        t = ((GLib.get_monotonic_time() - start) / 1000) % 1840
        size, gap = (3.0, 10) if group else (4.5, 14)
        for i, top in enumerate((380, 580, 760)):
            strength = max(0.0, 1 - abs(t - top) / 200) if moving else 0.5 - 0.5 * math.cos(2 * math.pi * t / 1840)
            color = tuple(r + (p - r) * strength for r, p in zip(rest, peak))
            cr.set_source_rgb(*color)
            cr.arc(left + (12 if group else 15) + i * gap, radius, size * (1 + 0.38 * strength if moving else 1), 0, 2 * math.pi)
            cr.fill()
    area.set_draw_func(draw)
    area.add_tick_callback(lambda widget, _clock: (widget.queue_draw(), True)[1])   # breathing needs frames too
    return area


def list_typing(label):
    """„schreibt …“ in the left column (Olaf zu 884f7c8a): three small dots in the accent blue as a wave, breathing when
    GNOME's animations are off; it stands in for the preview line."""
    row = Gtk.Box(spacing=6)
    area = Gtk.DrawingArea(content_width=26, content_height=14, valign=Gtk.Align.CENTER)
    moving = Gtk.Settings.get_default().get_property("gtk-enable-animations")
    start = GLib.get_monotonic_time()

    def draw(_area, cr, _w, h):
        t = ((GLib.get_monotonic_time() - start) / 1000) % 1840
        selected = (row_of := area.get_ancestor(Gtk.ListBoxRow)) is not None and row_of.is_selected()
        for i, top in enumerate((380, 580, 760)):
            strength = max(0.0, 1 - abs(t - top) / 200) if moving else 0.5 - 0.5 * math.cos(2 * math.pi * t / 1840)
            cr.set_source_rgba(*((1.0, 1.0, 1.0) if selected else (0.0, 0.48, 1.0)), 0.45 + 0.55 * strength)
            cr.arc(4 + i * 9, h / 2 - (3 * strength if moving else 0), 3, 0, 2 * math.pi)
            cr.fill()
    area.set_draw_func(draw)
    area.add_tick_callback(lambda widget, _clock: (widget.queue_draw(), True)[1])
    row.append(area)
    row.append(Gtk.Label(label=label, css_classes=["lichat-preview", "lichat-list-typing"], xalign=0, ellipsize=Pango.EllipsizeMode.END))
    row.update_property([Gtk.AccessibleProperty.LABEL], [label])
    return row


def typing_label(names):
    """„Anna schreibt …“, „Anna und Bernd schreiben …“, „Anna, Bernd und Erna schreiben …“"""
    if not names:
        return ""
    if len(names) == 1:
        return f"{names[0]} schreibt …"
    return ", ".join(names[:-1]) + f" und {names[-1]} schreiben …"


class Worker:
    """One thread for everything that talks to the server or changes the account."""

    def __init__(self):
        self.jobs = queue.Queue()
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        while True:
            job, done = self.jobs.get()
            try:
                result, error = job(), None
            except (LiChatError, ValueError, OSError) as e:
                result, error = None, str(e)
            if done:
                GLib.idle_add(done, result, error)

    def do(self, job, done=None):
        self.jobs.put((job, done))

    @staticmethod
    def wait(job, done=None):
        """A long wait (a device to link, up to 10 minutes) on its own thread – sending must not queue behind it."""
        def run():
            try:
                result, error = job(), None
            except (LiChatError, ValueError, OSError) as e:
                result, error = None, str(e)
            if done:
                GLib.idle_add(done, result, error)
        threading.Thread(target=run, daemon=True).start()


class Window(Adw.ApplicationWindow):
    def __init__(self, app, account=None, folder=None, key_store=None, separate=False):
        super().__init__(application=app, title="LiChat", default_width=980, default_height=640)
        self.add_css_class("lichat")
        self.folder = Path(folder or data_folder())
        self.key_store = key_store
        self.separate = separate      # LICHAT_ORDNER: a second LiChat – no autostart entry of its own
        self.worker = Worker()
        self.account = account
        self.current = None        # the chosen conversation (hex id)
        self.composing = False     # „Neue Nachricht“: the An: line instead of a transcript
        self.seen = {}             # chat → number of messages already looked at (the blue dot)
        self.full_images = {}      # attachment id → the photo itself (sharp in the bubble, ed0686a1)
        self.loading_images = set()
        self.revealed = set()      # secret pictures tapped open – covered again after 10 s or on leaving the chat (f9e3f055)
        self.gif_data = {}         # attachment id → GIF bytes (to run on in a rebuilt bubble)
        self.listener = None
        self.settings = self.load_settings()
        self.connect("close-request", self.on_close)
        manager = Adw.StyleManager.get_default()
        manager.connect("notify::dark", lambda *_: self.follow_appearance())
        self.follow_appearance()
        if self.account is None:
            self.account = self.open_existing()
        if self.account is None:
            self.show_setup()
        else:
            self.show_main()

    def load_settings(self):
        try:
            return {**DEFAULTS, **json.loads((self.folder / "einstellungen.json").read_text())}
        except (OSError, ValueError):
            return dict(DEFAULTS)

    def set_setting(self, name, value):
        self.settings[name] = value
        if self.folder.exists():
            (self.folder / "einstellungen.json").write_text(json.dumps(self.settings))

    def on_close(self, *_):
        """Closing keeps LiChat listening (like Messages on the Mac keeps receiving); „LiChat beenden“ ends it."""
        if self.account is not None and self.settings["hintergrund"]:
            self.set_visible(False)
            return True
        self.stop()
        return False

    def stop(self):
        if self.listener:
            self.listener.running = False

    def follow_appearance(self):
        if Adw.StyleManager.get_default().get_dark():
            self.add_css_class("lichat-dark")
        else:
            self.remove_css_class("lichat-dark")

    # ------------------------------------------------------------------------------------------ account

    def open_existing(self):
        if not (self.folder / "account.json").exists():
            return None
        try:
            key = self.key_store.load(self.folder) if self.key_store else None
            return Account(self.folder, key)
        except (LiChatError, ValueError) as error:
            self.problem = str(error)
            return None

    def show_setup(self):
        """Sign in once: server, invitation, user name (like LiNotes' first start)."""
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, css_classes=["lichat-setup"],
                      halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER, width_request=340)
        logo = avatar("Li Chat", 72)
        logo.set_halign(Gtk.Align.CENTER)
        box.append(logo)
        box.append(Gtk.Label(label="Bei LiChat anmelden", css_classes=["lichat-big"], margin_top=8))
        box.append(Gtk.Label(label="Mit der Einladung deines Servers. Alles, was du schreibst, ist Ende-zu-Ende verschlüsselt.",
                             wrap=True, justify=Gtk.Justification.CENTER, css_classes=["lichat-subtitle"], margin_bottom=8))
        # Our own server as a default (Tante Erna types nothing) – any other one can be entered.
        self.f_server = Gtk.Entry(text="https://exampleserver.lisoftware.de", placeholder_text="Server (https://…)", css_classes=["lichat-field"])
        self.f_invite = Gtk.Entry(placeholder_text="Einladung (Code oder Einladungs-Link)", css_classes=["lichat-field"])

        def invite_pasted(*_):
            # Olaf 10.10.: a pasted invitation link brings the server along – only the name is left to type
            link = Account.parse_link(self.f_invite.get_text())
            if link and link["art"] == "einladung":
                if link["server"]:
                    self.f_server.set_text(urllib.parse.unquote(link["server"]))
                self.f_invite.set_text(link["code"])
                self.f_first.grab_focus()
        self.f_invite.connect("changed", invite_pasted)
        # Name as people write it (Olaf: capitals, spaces) – the user name is made from it, and can still be changed.
        words = Gtk.InputHints.UPPERCASE_WORDS
        plain = Gtk.InputHints.NO_SPELLCHECK | Gtk.InputHints.LOWERCASE
        self.f_first = Gtk.Entry(placeholder_text="Vorname", css_classes=["lichat-field"], input_hints=words, input_purpose=Gtk.InputPurpose.NAME)
        self.f_last = Gtk.Entry(placeholder_text="Nachname (freiwillig)", css_classes=["lichat-field"], input_hints=words, input_purpose=Gtk.InputPurpose.NAME)
        self.f_name = Gtk.Entry(placeholder_text="Benutzername", css_classes=["lichat-field"], input_hints=plain)
        self.f_server.set_input_purpose(Gtk.InputPurpose.URL)
        self.f_server.set_input_hints(Gtk.InputHints.NO_SPELLCHECK)
        self.f_invite.set_input_hints(Gtk.InputHints.NO_SPELLCHECK | Gtk.InputHints.UPPERCASE_CHARS)
        self.name_touched = False

        def suggest(*_):
            if not self.name_touched:
                self.f_name.set_text(Account.suggest_username(self.f_first.get_text(), self.f_last.get_text()))
        self.f_first.connect("changed", suggest)
        self.f_last.connect("changed", suggest)
        self.f_name.connect("changed", lambda e: setattr(self, "name_touched", self.name_touched or (
            e.has_focus() and e.get_text() != Account.suggest_username(self.f_first.get_text(), self.f_last.get_text()))))
        for entry in (self.f_server, self.f_invite, self.f_first, self.f_last, self.f_name):
            box.append(entry)
            entry.connect("activate", lambda *_: self.sign_in())
        self.setup_note = Gtk.Label(css_classes=["lichat-notice"], wrap=True, justify=Gtk.Justification.CENTER,
                                    label=getattr(self, "problem", ""))
        box.append(Gtk.Label(label="Der Benutzername ist deine Kennung zum Finden (klein, ohne Leerzeichen). Deinen Namen sehen nur deine Gesprächspartner.",
                             wrap=True, max_width_chars=44, css_classes=["lichat-subtitle"], xalign=0))
        box.append(self.setup_note)
        # A taken user name: free ones to click (ebc3d629)
        self.setup_suggestions = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, halign=Gtk.Align.CENTER, max_children_per_line=3,
                                             column_spacing=6, visible=False)
        box.append(self.setup_suggestions)
        self.setup_button = Gtk.Button(label="Anmelden", css_classes=["lichat-blue"], halign=Gtk.Align.END)
        self.setup_button.connect("clicked", lambda *_: self.sign_in())
        box.append(self.setup_button)
        other = Gtk.Button(label="Mit vorhandenem Gerät verbinden", css_classes=["flat", "lichat-link"], halign=Gtk.Align.CENTER, margin_top=12)
        other.connect("clicked", lambda *_: self.show_link_setup())
        box.append(other)
        page = Gtk.WindowHandle(child=box)
        page.add_css_class("lichat-setup")
        self.set_content(Adw.ToolbarView(content=page, top_bar_style=Adw.ToolbarStyle.FLAT))
        self.get_content().add_top_bar(Adw.HeaderBar(show_title=False, css_classes=["lichat-bar"]))

    def show_link_setup(self):
        """A second device (card da5cbfcb), like Signal: this one shows a code, the existing device accepts it."""
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, css_classes=["lichat-setup"],
                      halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER, width_request=360)
        logo = avatar("Li Chat", 72)
        logo.set_halign(Gtk.Align.CENTER)
        box.append(logo)
        box.append(Gtk.Label(label="Mit vorhandenem Gerät verbinden", css_classes=["lichat-big"], margin_top=8))
        server = Gtk.Entry(text=self.f_server.get_text() if hasattr(self, "f_server") else "https://exampleserver.lisoftware.de",
                           css_classes=["lichat-field"])
        box.append(server)
        self.link_code = Gtk.Label(css_classes=["lichat-code"], visible=False)
        box.append(self.link_code)
        self.link_note = Gtk.Label(wrap=True, justify=Gtk.Justification.CENTER, max_width_chars=44, css_classes=["lichat-subtitle"])
        box.append(self.link_note)
        show = Gtk.Button(label="Code anzeigen", css_classes=["lichat-blue"], halign=Gtk.Align.CENTER)
        box.append(show)
        back = Gtk.Button(label="Abbrechen", css_classes=["flat", "lichat-link"], halign=Gtk.Align.CENTER)
        back.connect("clicked", lambda *_: (setattr(self, "linking", None), self.show_setup()))
        box.append(back)
        folder, store = self.folder, self.key_store

        def opened(result, error):
            if error:
                self.link_note.set_label(error)
                show.set_sensitive(True)
                return
            url, code = result
            self.linking = code
            server.set_visible(False)
            show.set_visible(False)
            self.link_code.set_label(code)
            self.link_code.set_visible(True)
            # The QR code for the phone's camera (Olaf: „per QR-Code koppeln“) – the code below it to type instead.
            from .qrcodegen import QrCode
            qr = QrCode.encode_text(Account.link_uri(url, code), QrCode.Ecc.MEDIUM)
            self.link_qr = qr

            def draw(_area, cr, width, height):
                size = qr.get_size() + 8
                scale = min(width, height) / size
                cr.set_source_rgb(1, 1, 1)
                cr.paint()
                cr.set_source_rgb(0, 0, 0)
                for y in range(qr.get_size()):
                    for x in range(qr.get_size()):
                        if qr.get_module(x, y):
                            cr.rectangle((x + 4) * scale, (y + 4) * scale, scale + 0.5, scale + 0.5)
                cr.fill()
            area = Gtk.DrawingArea(content_width=220, content_height=220, halign=Gtk.Align.CENTER, css_classes=["lichat-qr"])
            area.set_draw_func(draw)
            area.update_property([Gtk.AccessibleProperty.LABEL], [f"QR-Code zum Koppeln, Code {code}"])
            box.insert_child_after(area, self.link_code.get_prev_sibling())
            self.link_note.set_label("Auf deinem Handy: Einstellungen › Geräte › „Gerät hinzufügen …“ und den QR-Code scannen "
                                     "oder den Code eingeben. Er gilt 10 Minuten.\n\nWarte auf dein anderes Gerät …")

            def wait():
                end = time.time() + 600
                while not Account.link_state(url, code).get("username"):
                    if time.time() > end or getattr(self, "linking", None) != code:
                        raise LiChatError("Abgelaufen – noch einmal „Code anzeigen“")
                    time.sleep(2)
                return Account.link_finish(folder, url, code, "Ubuntu", store.create(folder) if store else None)

            def finished(account, error):
                if getattr(self, "linking", None) != code:
                    return
                if error:
                    self.link_note.set_label(error)
                    return
                self.linking = None
                self.account = account
                if self.settings["hintergrund"] and not self.separate:
                    autostart(True)
                self.show_main()
            Worker.wait(wait, finished)

        def start(*_):
            show.set_sensitive(False)
            self.link_note.set_label("")
            url = server.get_text().strip()
            self.worker.do(lambda: Account.link_open(url), opened)
        show.connect("clicked", start)
        page = Gtk.WindowHandle(child=box)
        page.add_css_class("lichat-setup")
        self.set_content(Adw.ToolbarView(content=page, top_bar_style=Adw.ToolbarStyle.FLAT))
        self.get_content().add_top_bar(Adw.HeaderBar(show_title=False, css_classes=["lichat-bar"]))

    def show_devices(self):
        """My devices (card da5cbfcb): this one, the others with „Abmelden“ (asked twice), „Gerät hinzufügen“ with the code
        the new device shows."""
        dialog = Adw.AlertDialog(heading="Meine Geräte",
                                 body="Ein neues Gerät liest alles, was ab dann kommt; ältere Nachrichten bleiben hier. "
                                      "Gerätenamen bleiben auf dem Gerät – der Server kennt sie nicht.")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        rows = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        box.append(rows)
        code = Gtk.Entry(placeholder_text="Code vom neuen Gerät", margin_top=8)
        add = Gtk.Button(label="Gerät hinzufügen", css_classes=["suggested-action"])
        note = Gtk.Label(wrap=True, xalign=0, css_classes=["lichat-subtitle"])
        box.append(code)
        box.append(add)
        box.append(note)
        dialog.set_extra_child(box)
        dialog.add_response("fertig", "Fertig")
        account = self.account

        def show(devices, error=None):
            while (child := rows.get_first_child()) is not None:
                rows.remove(child)
            note.set_label(error or "")
            for d in devices or []:
                line = Gtk.Box(spacing=8)
                since = time.strftime("%d.%m.%Y, %H:%M", time.localtime(d["since"])) if d["since"] else "?"
                title = ("Dieses Gerät" + (f" ({d['name']})" if d["name"] else "")) if d["this"] else "Gerät"
                line.append(Gtk.Label(label=f"{title} – seit {since}", xalign=0, hexpand=True))
                if not d["this"]:
                    out = Gtk.Button(label="Abmelden", css_classes=["flat", "lichat-destructive"])

                    def ask(button, device=d["device"]):
                        if button.get_label() == "Abmelden":
                            button.set_label("Wirklich abmelden?")
                            return
                        self.worker.do(lambda: (account.remove_my_device(device), account.my_devices())[1], show)
                    out.connect("clicked", ask)
                    line.append(out)
                rows.append(line)

        def link(*_):
            typed = code.get_text().strip()
            if len(typed.replace("-", "")) < 8:
                note.set_label("Den 8-stelligen Code vom neuen Gerät eingeben.")
                return
            add.set_sensitive(False)
            note.set_label("Warte auf das neue Gerät …")
            Worker.wait(lambda: (account.accept_link(typed), account.my_devices())[1],
                        lambda result, error: (add.set_sensitive(True), code.set_text(""), show(result, error),
                                               self.fill_list(), self.show_conversation()))
        add.connect("clicked", link)
        code.connect("activate", link)
        self.worker.do(account.my_devices, show)
        dialog.present(self)
        self.devices_dialog = dialog

    def sign_in(self):
        server, invite = self.f_server.get_text().strip(), self.f_invite.get_text().strip()
        name = Account.suggest_username(self.f_name.get_text()) or Account.suggest_username(self.f_first.get_text(), self.f_last.get_text())
        first, last = self.f_first.get_text().strip(), self.f_last.get_text().strip()
        if not (server and invite and name):
            self.setup_note.set_label("Bitte alle drei Felder ausfüllen.")
            return
        self.setup_button.set_sensitive(False)
        self.setup_note.set_label("")
        folder, store = self.folder, self.key_store

        def job():
            key = store.create(folder) if store else None
            try:
                account = Account.register(folder, server, invite, name, key, first, last)
            except LiChatError as error:
                if error.suggestions:
                    return error.suggestions
                raise
            if first or last:
                account.set_name(first, last)
            if account.invited_by:
                # invited by someone: they are a contact at once and their conversation is open – „direkt loslegen“
                account.add_contact(account.invited_by)
                self.after_setup = account.chat_with(account.invited_by)
            return account

        def done(account, error):
            self.setup_button.set_sensitive(True)
            self.setup_suggestions.remove_all()
            self.setup_suggestions.set_visible(isinstance(account, list))
            if isinstance(account, list):
                self.setup_note.set_label(f"„{name}“ ist schon vergeben. Frei wären:")
                for free in account:
                    choice = Gtk.Button(label=free, css_classes=["pill", "lichat-suggestion"])
                    choice.connect("clicked", lambda _b, n=free: (self.f_name.set_text(n), setattr(self, "name_touched", True),
                                                                  self.setup_suggestions.set_visible(False), self.setup_note.set_label("")))
                    self.setup_suggestions.append(choice)
                return
            if error:
                self.setup_note.set_label(error)
                return
            self.account = account
            if self.settings["hintergrund"] and not self.separate:
                autostart(True)
            self.show_main()
            if getattr(self, "after_setup", None):
                self.current = self.after_setup
                self.fill_list()
                self.show_conversation()

        self.worker.do(job, done)

    # ------------------------------------------------------------------------------------------ main window

    SHORTCUTS = (
        # Like Messages on the Mac, with Strg for ⌘ (Michelle 9b3b15d0)
        ("<Control>n", "start_composing"), ("<Control>f", "focus_search"), ("<Control>e", "edit_last"),
        ("<Control>r", "reply_last"), ("<Control>t", "tapback_last"), ("<Control><Shift>u", "toggle_unread"),
        ("<Control><Alt>i", "open_info"), ("<Control>Tab", "next_chat"), ("<Control><Shift>Tab", "previous_chat"),
        ("<Control>ISO_Left_Tab", "previous_chat"),
    ) + tuple((f"<Control>{n}", f"pinned_{n}") for n in range(1, 10))

    def add_shortcuts(self):
        controller = Gtk.ShortcutController(propagation_phase=Gtk.PropagationPhase.CAPTURE, scope=Gtk.ShortcutScope.GLOBAL)
        for trigger, name in self.SHORTCUTS:
            if name.startswith("pinned_"):
                job = lambda *_a, n=int(name[-1]): self.open_number(n)  # noqa: E731
            else:
                job = lambda *_a, m=getattr(self, name): (m(), True)[1]  # noqa: E731
            controller.add_shortcut(Gtk.Shortcut(trigger=Gtk.ShortcutTrigger.parse_string(trigger), action=Gtk.CallbackAction.new(job)))
        self.add_controller(controller)
        self.shortcuts = controller

    def focus_search(self):
        self.search.grab_focus()

    def last_message(self, mine):
        if not self.current:
            return None
        return next((m for m in reversed(self.account.chats[self.current]["messages"]) if m.get("mid") and not m.get("geloescht")
                     and not m.get("zurueckgerufen") and (m.get("from") == self.account.username) == mine), None)

    def edit_last(self):
        message = self.last_message(True)
        if message and self.account.can_edit(message):
            self.start_edit(message)

    def reply_last(self):
        message = self.last_message(False)
        if message:
            self.start_reply(message)

    def tapback_last(self):
        message = self.last_message(False)
        if message and message.get("mid") in getattr(self, "bubble_of", {}):
            self.message_menu(self.current, message, self.bubble_of[message["mid"]])

    def toggle_unread(self):
        if self.current:
            job = self.account.mark_read if self.account.unread(self.current) else self.account.mark_unread
            cid = self.current
            self.worker.do(lambda: job(cid), lambda *_: self.fill_list())

    def open_info(self):
        if self.current and self.info.get_visible():
            self.info.popup()

    def step_chat(self, step):
        order = self.account.pinned() + self.ordered()
        if not order:
            return
        index = order.index(self.current) if self.current in order else -1
        self.open_chat(order[(index + step) % len(order)])

    def next_chat(self):
        self.step_chat(1)

    def previous_chat(self):
        self.step_chat(-1)

    def open_number(self, n):
        """Strg+1…9: the pinned conversations first (like ⌘1…9 on the Mac), then the list."""
        order = self.account.pinned() + self.ordered()
        if len(order) >= n:
            self.open_chat(order[n - 1])
        return True

    def show_main(self):
        # Sidebar: search and „Neue Nachricht“ on top, the conversations below.
        sidebar = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, css_classes=["lichat-sidebar"], width_request=300)
        bar = Adw.HeaderBar(show_title=False, show_end_title_buttons=False, css_classes=["lichat-bar"])
        new = Gtk.Button(icon_name="document-edit-symbolic", css_classes=["lichat-tool"], tooltip_text="Neue Nachricht")
        new.connect("clicked", lambda *_: self.start_composing())
        bar.pack_end(new)
        menu = Gio.Menu()
        menu.append("Im Hintergrund empfangen und beim Anmelden starten", "win.hintergrund")
        menu.append("Absender und Text in Mitteilungen zeigen", "win.vorschau")
        menu.append("Lesebestätigungen senden", "win.lesebestaetigungen")
        menu.append("Tipp-Anzeige senden", "win.tippanzeige")
        menu.append("Name ändern …", "win.name")
        menu.append("Meine Geräte …", "win.geraete")
        menu.append("Profilbild wählen …", "win.bild")
        menu.append("LiChat beenden", "win.beenden")
        for name in ("hintergrund", "vorschau"):
            action = Gio.SimpleAction.new_stateful(name, None, GLib.Variant.new_boolean(self.settings[name]))
            action.connect("change-state", self.on_setting)
            self.add_action(action)
        # Read receipts (Olaf: off by default like the iPhone, on with a tap) – mutual: off means no blue ticks either.
        receipts = Gio.SimpleAction.new_stateful("lesebestaetigungen", None, GLib.Variant.new_boolean(self.account.read_receipts))
        receipts.connect("change-state", self.on_receipts)
        self.add_action(receipts)
        # „… tippt“ (Olaf 09.10.): off by default, mutual like the read receipts.
        typing = Gio.SimpleAction.new_stateful("tippanzeige", None, GLib.Variant.new_boolean(self.account.typing_indicator))
        typing.connect("change-state", self.on_typing_setting)
        self.add_action(typing)
        devices_action = Gio.SimpleAction.new("geraete", None)
        devices_action.connect("activate", lambda *_: self.show_devices())
        self.add_action(devices_action)
        name_action = Gio.SimpleAction.new("name", None)
        name_action.connect("activate", lambda *_: self.edit_name())
        self.add_action(name_action)
        picture_action = Gio.SimpleAction.new("bild", None)
        picture_action.connect("activate", lambda *_: self.choose_picture())
        self.add_action(picture_action)
        quit_action = Gio.SimpleAction.new("beenden", None)
        quit_action.connect("activate", lambda *_: self.quit_all())
        self.add_action(quit_action)
        self.menu_button = Gtk.MenuButton(icon_name="view-more-symbolic", menu_model=menu, css_classes=["lichat-tool"],
                                          tooltip_text="Einstellungen")
        bar.pack_start(self.menu_button)
        # Filter like the Mac's „Darstellung › Filter“ (1f800b4f, a0ceee6e).
        self.filter = "alle"
        filters = Gio.Menu()
        for key, label in (("alle", "Alle"), ("bekannt", "Bekannte Absender"), ("unbekannt", "Unbekannte Absender"),
                           ("ungelesen", "Ungelesen"), ("geloescht", "Zuletzt gelöscht")):
            filters.append(label, f"win.filter::{key}")
        filter_action = Gio.SimpleAction.new_stateful("filter", GLib.VariantType.new("s"), GLib.Variant.new_string("alle"))
        filter_action.connect("change-state", self.on_filter)
        self.add_action(filter_action)
        self.filter_button = Gtk.MenuButton(icon_name="view-list-symbolic", menu_model=filters, css_classes=["lichat-tool"],
                                            tooltip_text="Filter")
        bar.pack_end(self.filter_button)
        sidebar.append(Gtk.WindowHandle(child=bar))
        self.search = Gtk.SearchEntry(placeholder_text="Suchen", css_classes=["lichat-search"])
        self.search.connect("search-changed", lambda *_: self.fill_list())
        sidebar.append(self.search)
        # Pinned conversations as big round pictures on top, three in a row (206118bf).
        self.pins = Gtk.FlowBox(max_children_per_line=3, min_children_per_line=3, selection_mode=Gtk.SelectionMode.NONE,
                                homogeneous=True, css_classes=["lichat-pins"], margin_start=8, margin_end=8, visible=False)
        sidebar.append(self.pins)
        self.filter_label = Gtk.Label(css_classes=["lichat-filter"], xalign=0, margin_start=16, visible=False)
        sidebar.append(self.filter_label)
        self.list = Gtk.ListBox(css_classes=["lichat-list"], selection_mode=Gtk.SelectionMode.SINGLE)
        self.list.connect("row-selected", self.on_row)
        sidebar.append(Gtk.ScrolledWindow(child=self.list, vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER))

        # The conversation: name on top, the bubbles, the input line.
        self.pane = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, css_classes=["lichat-pane"], hexpand=True)
        top = Adw.HeaderBar(css_classes=["lichat-bar"], show_start_title_buttons=False)
        self.title_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        top.set_title_widget(self.title_box)
        self.info = Gtk.MenuButton(icon_name="dialog-information-symbolic", css_classes=["lichat-tool"], tooltip_text="Informationen",
                                   visible=False)
        self.info.set_create_popup_func(self.fill_info)
        top.pack_end(self.info)
        self.pane.append(Gtk.WindowHandle(child=top))
        self.pane.append(Gtk.Box(css_classes=["lichat-hairline"]))
        self.to_line = Gtk.Box(spacing=6, margin_start=16, margin_end=16, margin_top=8, margin_bottom=8, visible=False)
        self.to_line.append(Gtk.Label(label="An:", css_classes=["lichat-to"]))
        self.to_entry = Gtk.Entry(placeholder_text="Benutzernamen, durch Komma getrennt", hexpand=True, css_classes=["lichat-to-entry"],
                                  input_hints=Gtk.InputHints.NO_SPELLCHECK | Gtk.InputHints.LOWERCASE)
        self.to_entry.connect("activate", lambda *_: self.input.grab_focus())
        self.to_line.append(self.to_entry)
        # 89aae55d (Olaf 10.10.): nobody has to type an exact user name – my code, someone's code or PIN, people I know
        self.contact_button = Gtk.MenuButton(label="Kontakt", css_classes=["flat"], tooltip_text="QR-Code, PIN oder bekannte Personen")
        self.contact_button.set_create_popup_func(self.fill_contact_menu)
        self.to_line.append(self.contact_button)
        self.pane.append(self.to_line)
        self.to_hairline = Gtk.Box(css_classes=["lichat-hairline"], visible=False)
        self.pane.append(self.to_hairline)
        self.bubbles = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_start=16, margin_end=16,
                               margin_top=8, margin_bottom=8, valign=Gtk.Align.END)
        self.scroller = Gtk.ScrolledWindow(child=self.bubbles, vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER,
                                           css_classes=["lichat-transcript"])
        # The end stays in view – new bubbles, „•••“, a narrower window (cards 0c01f599, be8f93c3). Reading further up,
        # a new message shows „↓ Neue Nachricht“ instead of pulling the view away.
        self.stick, self.upper, self.held, self.unseen, self.shown, self.end_pending = True, 0.0, None, 0, None, False
        adjustment = self.scroller.get_vadjustment()
        adjustment.connect("changed", self.keep_end)
        adjustment.connect("value-changed", self.followed)
        self.new_pill = Gtk.Button(label="↓ Neue Nachricht", css_classes=["lichat-new-pill"], halign=Gtk.Align.CENTER,
                                   valign=Gtk.Align.END, margin_bottom=10, visible=False)
        self.new_pill.connect("clicked", lambda *_: self.to_end())
        overlay = Gtk.Overlay(child=self.scroller, vexpand=True)
        overlay.add_overlay(self.new_pill)
        self.first_pill = Gtk.Button(css_classes=["lichat-new-pill"], halign=Gtk.Align.CENTER, valign=Gtk.Align.START, margin_top=10, visible=False)
        self.first_pill.connect("clicked", lambda *_: self.to_first_unread())
        overlay.add_overlay(self.first_pill)
        self.shown_unread_for = None
        self.pane.append(overlay)
        self.notice = Gtk.Label(css_classes=["lichat-notice"], visible=False, margin_bottom=4)
        self.pane.append(self.notice)
        self.reply_to = None
        self.editing = None
        self.reply_line = Gtk.Box(spacing=8, margin_start=16, margin_end=16, margin_top=4, visible=False, css_classes=["lichat-replying"])
        self.reply_label = Gtk.Label(xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END, css_classes=["lichat-quote"])
        self.reply_line.append(self.reply_label)
        cancel = Gtk.Button(icon_name="window-close-symbolic", css_classes=["flat", "circular"], tooltip_text="Antwort aufheben")
        cancel.connect("clicked", lambda *_: self.end_reply())
        self.reply_line.append(cancel)
        self.pane.append(self.reply_line)
        line = Gtk.Box(spacing=8, margin_start=16, margin_end=16, margin_bottom=12, margin_top=4)
        # ⊕ like Messages on the Mac: a photo, a video, a voice recording or any file (card 9780f3a0).
        attach = Gtk.Button(icon_name="list-add-symbolic", css_classes=["lichat-tool", "lichat-attach"], tooltip_text="Anhang …",
                            valign=Gtk.Align.CENTER)
        attach.connect("clicked", lambda *_: self.choose_attachment())
        line.append(attach)
        # Capital letters at the start of a sentence, spelling checked (Olaf c81920e8: the keyboard wrote everything small).
        self.input = Gtk.Entry(placeholder_text="LiChat", hexpand=True, css_classes=["lichat-input"],
                               input_hints=Gtk.InputHints.UPPERCASE_SENTENCES | Gtk.InputHints.SPELLCHECK | Gtk.InputHints.EMOJI)
        self.input.connect("activate", lambda *_: self.send())
        self.input.connect("changed", self.typed)
        self.input.connect("changed", self.suggest_mentions)
        self.typing_timer = 0
        line.append(self.input)
        self.input_line = line
        # „@…“ in a group: the members that fit, as buttons above the input; a click writes „@name “ (card d54175ed).
        self.suggestions = Gtk.Box(spacing=6, margin_start=16, margin_end=16, margin_top=4, visible=False)
        self.pane.append(self.suggestions)
        self.pane.append(line)

        split = Gtk.Box()
        split.append(sidebar)
        split.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL, css_classes=["lichat-hairline"]))
        split.append(self.pane)
        self.set_content(split)

        if getattr(self, "shortcuts", None) is None:
            self.add_shortcuts()
        self.fill_list()
        self.show_conversation()
        self.listener = Listener(self.account, self.fetched, self.show_notice)
        if not self.settings["erklaert"]:
            GLib.idle_add(self.explain_background)

    # ------------------------------------------------------------------------------------------ list

    def ordered(self):
        def last(cid):
            messages = self.account.chats[cid]["messages"]
            return messages[-1]["at"] if messages else 0
        shown = self.account.filtered(getattr(self, "filter", "alle"))
        if getattr(self, "filter", "alle") == "alle":
            shown = [c for c in shown if not self.account.chats[c].get("angeheftet")]    # those are on top
        return sorted(shown, key=last, reverse=True)

    @locked
    def on_filter(self, action, value):
        action.set_state(value)
        self.filter = value.get_string()
        labels = {"bekannt": "Bekannte Absender", "unbekannt": "Unbekannte Absender", "ungelesen": "Ungelesen", "geloescht": "Zuletzt gelöscht"}
        self.filter_label.set_label(labels.get(self.filter, ""))
        self.filter_label.set_visible(self.filter != "alle")
        self.fill_list()

    def fill_pins(self):
        while (child := self.pins.get_first_child()) is not None:
            self.pins.remove(child)
        pinned = self.account.pinned() if self.filter == "alle" else []
        for cid in pinned:
            chat = self.account.chats[cid]
            others = [u for u in chat["roster"] if u != self.account.username]
            title = self.account.title(cid)
            cell = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=6, margin_bottom=6)
            face = avatar(title, 56, self.account.picture(others[0]) if len(others) == 1 else self.account.group_picture(cid))
            face.set_halign(Gtk.Align.CENTER)
            overlay = Gtk.Overlay(child=face, halign=Gtk.Align.CENTER)
            if self.account.unread(cid) and cid != self.current:
                overlay.add_overlay(Gtk.Box(css_classes=["lichat-unread"], halign=Gtk.Align.START, valign=Gtk.Align.START))
            cell.append(overlay)
            cell.append(Gtk.Label(label=title, ellipsize=Pango.EllipsizeMode.END, max_width_chars=10, css_classes=["lichat-pin-name"]))
            button = Gtk.Button(child=cell, css_classes=["flat", "lichat-pin"] + (["lichat-pin-on"] if cid == self.current else []),
                                tooltip_text=title)
            button.connect("clicked", lambda _b, c=cid: self.open_chat(c))
            self.add_row_menu(button, cid)
            self.pins.append(button)
        self.pins.set_visible(bool(pinned))

    def add_row_menu(self, widget, cid):
        """Right click on a conversation (list or pinned): anheften, (un)gelesen, stumm, löschen – or, in „Zuletzt
        gelöscht“, wiederherstellen / endgültig löschen."""
        def show(*_):
            chat = self.account.chats[cid]
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_start=6, margin_end=6, margin_top=6, margin_bottom=6)
            popover = Gtk.Popover(child=box)
            entries = []
            if self.filter == "geloescht":
                entries = [("Wiederherstellen", lambda: self.account.restore(cid)),
                           ("Endgültig löschen", lambda: self.account.purge(cid))]
            else:
                entries = [("Loslösen" if chat.get("angeheftet") else "Anheften", lambda: self.account.pin(cid, not chat.get("angeheftet"))),
                           ("Als gelesen markieren" if self.account.unread(cid) else "Als ungelesen markieren",
                            lambda: self.account.mark_read(cid) if self.account.unread(cid) else self.account.mark_unread(cid)),
                           ("Hinweise einblenden" if chat.get("stumm") else "Hinweise ausblenden", lambda: self.account.mute(cid, not chat.get("stumm"))),
                           ("Konversation löschen …", lambda: self.account.delete_chat(cid))]
            for label, job in entries:
                item = Gtk.Button(label=label, css_classes=["flat"] + (["lichat-destructive"] if "löschen" in label else []))
                item.connect("clicked", lambda _b, j=job: (popover.popdown(), self.worker.do(j, lambda *_: self.after_list_change(cid))))
                box.append(item)
            popover.set_parent(widget)
            popover.popup()
            self.row_menu = popover
        click = Gtk.GestureClick(button=3)
        click.connect("pressed", show)
        widget.add_controller(click)

    def after_list_change(self, cid):
        if self.current == cid and (self.account.chats[cid].get("geloescht")):
            self.current = None
        self.fill_list()
        self.show_conversation()

    def fill_list(self):
        wanted = self.search.get_text().strip().lower() if hasattr(self, "search") else ""
        if hasattr(self, "pins"):
            self.fill_pins()
        self.filling = True
        while (row := self.list.get_row_at_index(0)) is not None:
            self.list.remove(row)
        for cid in self.ordered():
            chat = self.account.chats[cid]
            title = self.account.title(cid)
            texts = " ".join(Account.describe(m) for m in chat["messages"]).lower()
            if wanted and wanted not in title.lower() and wanted not in texts:
                continue
            last = chat["messages"][-1] if chat["messages"] else None
            row = Gtk.ListBoxRow()
            row.cid = cid
            box = Gtk.Box(spacing=10, margin_start=8, margin_end=10, margin_top=8, margin_bottom=8)
            dot = Gtk.Box(css_classes=["lichat-unread"], valign=Gtk.Align.CENTER,
                          opacity=1 if self.account.unread(cid) and cid != self.current else 0)
            box.append(dot)
            others = [u for u in chat["roster"] if u != self.account.username]
            box.append(avatar(title, 40, self.account.picture(others[0]) if len(others) == 1 else self.account.group_picture(cid)))
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, spacing=1)
            head = Gtk.Box()
            head.append(Gtk.Label(label=title, css_classes=["lichat-name"], xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END))
            if last:
                head.append(Gtk.Label(label=when_short(last["at"]), css_classes=["lichat-time"]))
            text.append(head)
            preview = (last["text"] if last.get("system") else ("Du: " if last["from"] == self.account.username else "") + Account.describe(last)) \
                if last else "Noch keine Nachrichten"
            typists = self.account.typing_now(cid)
            if typists:
                group = len(self.account.members(cid)) > 2 or bool(chat.get("title"))
                text.append(list_typing("schreibt …" if not group else f"{self.account.display(typists[0])} schreibt …"
                                        if len(typists) == 1 else f"{len(typists)} Personen schreiben …"))
                if not getattr(self, "list_typing_timer", 0):
                    self.list_typing_timer = GLib.timeout_add(TYPING_FRESH * 1000 + 100, self.list_typing_over)
            else:
                text.append(Gtk.Label(label=preview, css_classes=["lichat-preview"], xalign=0, wrap=True, lines=2,
                                      ellipsize=Pango.EllipsizeMode.END, max_width_chars=30, natural_wrap_mode=Gtk.NaturalWrapMode.WORD))
            box.append(text)
            row.set_child(box)
            self.add_row_menu(row, cid)
            self.list.append(row)
            if cid == self.current:
                self.list.select_row(row)
        self.filling = False

    def list_typing_over(self):
        self.list_typing_timer = 0
        self.fill_list()          # the preview comes back (or the wave stays while it is still fresh)
        return False

    def reveal(self, key):
        """A secret picture sharp for 10 seconds, like on the iPhone – then covered again (Michelle: it stayed sharp)."""
        self.revealed.add(key)
        self.show_conversation()

        def cover():
            if key in self.revealed:
                self.revealed.discard(key)
                self.show_conversation()
            return False
        GLib.timeout_add_seconds(10, cover)

    def on_row(self, listbox, row):
        if getattr(self, "filling", False) or row is None:
            return
        if row.cid != self.current:
            self.revealed.clear()          # leaving a chat covers its secret pictures again
        self.composing = False
        self.current = row.cid
        self.show_conversation()
        self.fill_list()

    # ------------------------------------------------------------------------------------------ conversation

    @locked
    def show_conversation(self):
        while (child := self.bubbles.get_first_child()) is not None:
            self.bubbles.remove(child)
        while (child := self.title_box.get_first_child()) is not None:
            self.title_box.remove(child)
        self.to_line.set_visible(self.composing)
        self.to_hairline.set_visible(self.composing)
        self.info.set_visible(self.current is not None and not self.composing)
        self.input_line.set_visible(self.composing or self.current is not None)
        if self.composing:
            self.title_box.append(Gtk.Label(label="Neue Nachricht", css_classes=["lichat-title"]))
            return
        if self.current is None:
            self.bubbles.set_valign(Gtk.Align.CENTER)
            self.bubbles.append(Gtk.Label(label="Keine Konversation ausgewählt", css_classes=["lichat-empty"]))
            return
        self.bubbles.set_valign(Gtk.Align.END)
        cid = self.current
        chat = self.account.chats[cid]
        count = len(chat["messages"])
        if self.shown != cid:
            self.shown, self.unseen = cid, 0
            self.stick, self.held = True, None
            self.new_pill.set_visible(False)
        elif count > self.seen.get(cid, count):
            if chat["messages"][-1]["from"] == self.account.username:
                self.stick, self.held = True, None      # what I sent myself is always shown
            elif not self.stick:
                self.unseen += count - self.seen[cid]
                self.new_pill.set_label("↓ Neue Nachricht" if self.unseen == 1 else f"↓ {self.unseen} neue Nachrichten")
                self.new_pill.set_visible(True)
        title = self.account.title(cid)
        self.title_box.append(Gtk.Label(label=title, css_classes=["lichat-title"]))
        others = [u for u in chat["roster"] if u != self.account.username]
        if len(others) > 1:
            self.title_box.append(Gtk.Label(label=f"{len(others) + 1} Personen", css_classes=["lichat-subtitle"]))
        group = len(others) > 1 or bool(chat.get("title"))     # a chat with a name is a group too (Michelle)
        previous = None
        # „↑ Zur ersten ungelesenen“ (300129f8): worked out before this view counts as read.
        first = self.account.first_unread(cid) if self.shown_unread_for != cid else None
        self.shown_unread_for = cid
        self.bubble_of = {}
        leaders, hidden = album_runs([m for m in chat["messages"] if not m.get("geloescht")])
        for m in chat["messages"]:
            if m.get("geloescht"):
                continue        # in „Zuletzt gelöscht“
            if m.get("mid") in hidden:
                continue        # drawn inside its album's last photo (5749ebd0)
            if m.get("system"):
                self.bubbles.append(Gtk.Label(label=m["text"], css_classes=["lichat-stamp"], wrap=True))
                previous = m      # the next bubble names its sender again, no extra time stamp
                continue
            mine = m["from"] == self.account.username
            if previous is None or m["at"] - previous["at"] > GAP:
                self.bubbles.append(Gtk.Label(label=when_long(m["at"]), css_classes=["lichat-stamp"]))
            if m.get("zurueckgerufen"):
                # Called back (iOS „Senden widerrufen“): a grey line where the bubble was.
                who = "Du hast" if mine else f"{self.account.display(m['from'])} hat"
                self.bubbles.append(Gtk.Label(label=f"{who} eine Nachricht widerrufen.", css_classes=["lichat-unsent"],
                                              halign=Gtk.Align.END if mine else Gtk.Align.START))
                previous = m
                continue
            if group and not mine and (previous is None or previous["from"] != m["from"]):
                self.bubbles.append(Gtk.Label(label=self.account.display(m["from"]), css_classes=["lichat-sender"], xalign=0))
            if m.get("antwort"):
                # The message this answers, small above the bubble (Olaf 09.10., card a7d9d987).
                quoted = m["antwort"]
                who = "Du" if quoted.get("from") == self.account.username else self.account.display(quoted.get("from", ""))
                said = "Nachricht widerrufen" if quoted.get("zurueckgerufen") else quoted.get("text", "")
                self.bubbles.append(Gtk.Label(label=f"↩ {who}: {said}", css_classes=["lichat-quote"], wrap=True,
                                              max_width_chars=44, lines=2, ellipsize=Pango.EllipsizeMode.END, xalign=0,
                                              halign=Gtk.Align.END if mine else Gtk.Align.START))
            if m.get("anhang"):
                # an album (5749ebd0) or one attachment – the caption under it either way (Michelle: missing under albums)
                piece = self.album_bubble(leaders[m["mid"]], mine) if m.get("mid") in leaders else self.attachment_bubble(m["anhang"], mine, m)
                if (m.get("text") or "").strip():
                    # 2fcc3eef: the caption under the picture, in the same message – like Messages
                    caption = Gtk.Label(wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, xalign=0, selectable=True, max_width_chars=48,
                                        css_classes=["lichat-bubble", "lichat-mine" if mine else "lichat-theirs"],
                                        halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=2)
                    caption.set_markup(bubble_markup(m["text"]))
                    piece.append(caption)
            else:
                piece = Gtk.Label(wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, xalign=0, selectable=True,
                                  max_width_chars=48, css_classes=["lichat-bubble", "lichat-mine" if mine else "lichat-theirs"],
                                  halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=2)
                # „@name“ bold (card d54175ed)
                piece.set_markup(bubble_markup(m["text"]))
            if group and not mine:
                # In a group: the sender's picture next to the bubble, like Messages (card d45b319a).
                row = Gtk.Box(spacing=6, halign=Gtk.Align.START)
                face = avatar(self.account.display(m["from"]), 24, self.account.picture(m["from"]))
                face.set_valign(Gtk.Align.END)
                row.append(face)
                row.append(piece)
                piece = row
            if m.get("mid"):
                self.bubble_of[m["mid"]] = piece
                # Right click (or a long press on a touch screen): tapbacks, Antworten, Kopieren – like Messages on the Mac.
                for gesture in (Gtk.GestureClick(button=3), Gtk.GestureLongPress()):
                    gesture.connect("pressed" if isinstance(gesture, Gtk.GestureClick) else "pressed",
                                    lambda g, *a, msg=m, w=piece: self.message_menu(cid, msg, w))
                    piece.add_controller(gesture)
            reactions = m.get("reaktionen") or {}
            if reactions:
                # At the bubble's lower right, for mine and theirs (Olaf „unten rechts“, Michelle a7d9d987).
                counts = {}
                for emoji in reactions.values():
                    counts[emoji] = counts.get(emoji, 0) + 1
                badge = Gtk.Label(label=" ".join(f"{e} {n}" if n > 1 else e for e, n in counts.items()), css_classes=["lichat-reactions"],
                                  halign=Gtk.Align.END,
                                  tooltip_text=", ".join(f"{'Du' if u == self.account.username else self.account.display(u)} {e}"
                                                         for u, e in reactions.items()))
                column = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, halign=piece.get_halign())
                column.append(piece)
                column.append(badge)
                self.bubbles.append(column)
            else:
                self.bubbles.append(piece)
            if m.get("bearbeitet"):
                # „Bearbeitet“ (iOS): a click shows the earlier versions.
                edited = Gtk.Button(label="Bearbeitet", css_classes=["flat", "lichat-edited"], halign=Gtk.Align.END if mine else Gtk.Align.START,
                                    tooltip_text="Frühere Versionen zeigen")
                versions = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_start=10, margin_end=10, margin_top=8, margin_bottom=8)
                for old in m["bearbeitet"]:
                    versions.append(Gtk.Label(label=old.get("text", ""), wrap=True, max_width_chars=40, xalign=0, css_classes=["lichat-version"]))
                versions.append(Gtk.Label(label=m["text"], wrap=True, max_width_chars=40, xalign=0))
                popover = Gtk.Popover(child=versions)
                popover.set_parent(edited)
                edited.connect("clicked", lambda _b, p=popover: p.popup())
                self.bubbles.append(edited)
            if mine:
                self.bubbles.append(self.tick_line(cid, m))
            previous = m
        if first and first in self.bubble_of:
            count = self.account.unread(cid)
            self.first_pill.set_label("↑ 1 ungelesene Nachricht" if count == 1 else f"↑ {count} ungelesene Nachrichten")
            self.first_pill.target = self.bubble_of[first]
            self.first_pill.set_visible(True)
            GLib.timeout_add(250, self.first_unread_in_view)
        elif self.shown_unread_for != cid or first is None:
            self.first_pill.set_visible(False)
        typing = self.account.typing_now(cid)
        if typing and not chat.get("removed"):
            # „…“ in a grey bubble, in a group with who it is (card 884f7c8a); gone a few seconds after the last sign.
            if group:
                self.bubbles.append(Gtk.Label(label=typing_label([self.account.display(u) for u in typing]), css_classes=["lichat-sender"], xalign=0))
            dots = typing_dots(self.has_css_class("lichat-dark"), group)
            dots.update_property([Gtk.AccessibleProperty.LABEL], [f"{', '.join(self.account.display(u) for u in typing)} tippt"])
            self.bubbles.append(dots)
            if self.typing_timer:
                GLib.source_remove(self.typing_timer)
            self.typing_timer = GLib.timeout_add(TYPING_FRESH * 1000 + 100, self.typing_over, cid)
        self.seen[cid] = len(chat["messages"])
        if self.is_active() and self.get_visible() and self.get_application():
            self.get_application().withdraw_notification(f"lichat-{cid}")     # read: its notification goes (2e35e6a3)
        if self.is_active() and self.get_visible():
            account = self.account
            self.worker.do(lambda: account.mark_read(cid))
        if chat.get("removed"):
            # Taken out of the group: the history stays readable, nothing more can be written.
            self.bubbles.append(Gtk.Label(label=f"{chat['removed']} hat dich aus dem Gespräch entfernt.", css_classes=["lichat-stamp"]))
            self.input_line.set_visible(False)
            self.info.set_visible(False)
        GLib.idle_add(self.scroll_down)

    def typing_over(self, cid):
        self.typing_timer = 0
        if self.current == cid and not self.composing:
            self.show_conversation()
        return False

    def suggest_mentions(self, entry):
        while (child := self.suggestions.get_first_child()) is not None:
            self.suggestions.remove(child)
        cid = self.current
        found = re.search(r"(?:^|\s)@([\w.-]*)$", entry.get_text())
        chat = self.account.chats.get(cid, {}) if cid else {}
        group = cid and not self.composing and (len(chat.get("roster", {})) > 2 or bool(chat.get("title")))
        names = self.account.mention_candidates(cid, found.group(1)) if group and found else []
        for user in names:
            button = Gtk.Button(label=self.account.display(user), css_classes=["lichat-suggestion"], tooltip_text=f"@{user}")
            button.connect("clicked", lambda _b, u=user: self.insert_mention(u))
            self.suggestions.append(button)
        self.suggestions.set_visible(bool(names))

    def insert_mention(self, user):
        text = self.input.get_text()
        self.input.set_text(text[:text.rfind("@")] + f"@{user} ")
        self.input.grab_focus()
        self.input.set_position(-1)

    def typed(self, entry):
        """Something typed in a conversation: with the switch on, the others see „…“ (rate-limited in the account)."""
        cid = self.current
        if cid is None or self.composing or not entry.get_text() or not self.account.typing_indicator:
            return
        account = self.account
        self.worker.do(lambda: account.typing(cid))

    def keep_end(self, adjustment):
        """The content changed size: at the end stay there; further up stay on what was read."""
        self.upper = adjustment.get_upper()
        # Not now: a value set while GTK lays the transcript out is overtaken by that layout – on the next frame.
        if not self.end_pending:
            self.end_pending = True
            self.scroller.add_tick_callback(self.hold_place)

    def hold_place(self, *_):
        self.end_pending = False
        adjustment = self.scroller.get_vadjustment()
        end = adjustment.get_upper() - adjustment.get_page_size()
        if self.stick:
            adjustment.set_value(end)
        elif self.held is not None:
            adjustment.set_value(min(self.held, end))
        return False

    def followed(self, adjustment):
        if adjustment.get_upper() != self.upper:
            return                     # a size change, not the person scrolling
        self.stick = adjustment.get_value() >= adjustment.get_upper() - adjustment.get_page_size() - 40
        self.held = None if self.stick else adjustment.get_value()
        if self.stick and self.unseen:
            self.unseen = 0
            self.new_pill.set_visible(False)

    def to_end(self):
        self.stick, self.held, self.unseen = True, None, 0
        self.new_pill.set_visible(False)
        adjustment = self.scroller.get_vadjustment()
        adjustment.set_value(adjustment.get_upper() - adjustment.get_page_size())

    def first_unread_in_view(self):
        """Like the Mac: the pill only when the first unread message is above what is shown."""
        target = getattr(self.first_pill, "target", None)
        if target is not None and target.get_root() is self:
            ok, bounds = target.compute_bounds(self.scroller)
            if ok and bounds.get_y() >= 0:
                self.first_pill.set_visible(False)
        return False

    def to_first_unread(self):
        target = getattr(self.first_pill, "target", None)
        self.first_pill.set_visible(False)
        if target is None:
            return
        ok, point = target.compute_point(self.bubbles, Graphene.Point())
        if ok:
            self.stick, self.held = False, max(0.0, point.y - 40)
            self.scroller.get_vadjustment().set_value(self.held)

    def scroll_down(self):
        if self.stick:
            self.to_end()
        return False

    @locked
    def fill_info(self, button):
        cid = self.current
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_start=14, margin_end=14, margin_top=12, margin_bottom=12)
        members = self.account.members(cid)
        group = len(members) > 2 or bool(self.account.chats[cid].get("title"))
        others = [m for m in members if m != self.account.username]
        person = others[0] if not group and len(others) == 1 else None
        shown = self.account.picture(person) if person else self.account.group_picture(cid) if group else None
        top = avatar(self.account.title(cid), 64, shown)
        if shown:
            # Olaf zu 8534fe11: a click shows the picture big and whole
            top = Gtk.Button(child=top, css_classes=["flat", "circular"], tooltip_text="Bild groß zeigen")
            top.connect("clicked", lambda *_: (button.popdown(), self.show_picture(shown, self.account.title(cid))))
        top.set_halign(Gtk.Align.CENTER)
        box.append(top)
        media = Gtk.Button(label="Fotos, Links und Dokumente …", css_classes=["flat"])
        media.connect("clicked", lambda *_: (button.popdown(), self.show_media(cid)))
        if person:
            # Like Apple Contacts: my own picture of this person, only for me (on my devices) – „Zurücksetzen“ brings theirs back
            row = Gtk.Box(spacing=6, halign=Gtk.Align.CENTER)
            change = Gtk.Button(label="Bild ändern", css_classes=["flat"])
            change.connect("clicked", lambda *_: (button.popdown(), self.choose_picture(person=person)))
            row.append(change)
            if self.account.has_contact_picture(person):
                reset = Gtk.Button(label="Zurücksetzen", css_classes=["flat"])
                reset.connect("clicked", lambda *_: (button.popdown(), self.group_job(lambda a: a.set_contact_picture(person, None))))
                row.append(reset)
            box.append(row)
        if group and not self.account.chats[cid].get("removed"):
            # Name and picture of the group (card d45b319a) – end to end, never on the server.
            name = Gtk.Entry(text=self.account.chats[cid].get("title", ""), placeholder_text="Gruppenname", css_classes=["lichat-field"],
                             input_hints=Gtk.InputHints.UPPERCASE_WORDS)
            name.connect("activate", lambda e: (button.popdown(), self.group_job(lambda a: a.rename(cid, e.get_text()))))
            box.append(name)
            picture = Gtk.Button(label="Gruppenbild wählen …", css_classes=["flat"])
            picture.connect("clicked", lambda *_: (button.popdown(), self.choose_picture(cid)))
            box.append(picture)
        else:
            box.append(Gtk.Label(label=self.account.title(cid), css_classes=["lichat-title"]))
        # Members only in a group – a single chat shows the person on top (Michelle zu 8534fe11)
        for name in members if group else []:
            row = Gtk.Box(spacing=8)
            row.append(avatar(self.account.display(name), 24, self.account.picture(name)))
            row.append(Gtk.Label(label="Ich" if name == self.account.username else self.account.display(name),
                                 tooltip_text=name, xalign=0, hexpand=True))
            # In a group, someone can be taken out (test plan S2); a conversation of two is simply left alone.
            if len(members) > 2 and name != self.account.username:
                out = Gtk.Button(label="Entfernen", css_classes=["flat", "lichat-remove"])
                out.connect("clicked", lambda _b, who=name: (button.popdown(), self.remove_member(cid, who)))
                row.append(out)
            box.append(row)
        if not self.account.chats[cid].get("removed"):
            if group:
                # 4da136eb (Olaf 10.10.): pick from my contacts like Messages instead of typing an exact name
                adding = Gtk.Button(label="Personen hinzufügen …", css_classes=["flat"], halign=Gtk.Align.START, margin_top=4)
                adding.connect("clicked", lambda *_: (button.popdown(), self.add_people(cid, set(members))))
                box.append(adding)
            # In a group a muted chat still notifies for @me / @alle (iOS, card d54175ed).
            mute = Gtk.CheckButton(label="Nur bei Erwähnung benachrichtigen" if group else "Mitteilungen stummschalten",
                                   active=bool(self.account.chats[cid].get("stumm")))
            mute.connect("toggled", lambda c: self.account.mute(cid, c.get_active()))
            box.append(mute)
            # Read receipts for this conversation (3c0400ad) – otherwise the general setting applies.
            receipts = Gtk.CheckButton(label="Lesebestätigungen senden", active=self.account.receipts_for(cid))
            receipts.connect("toggled", lambda c: self.worker.do(lambda: self.account.set_receipts_for(cid, c.get_active()),
                                                                 lambda *_: self.show_conversation()))
            box.append(receipts)
            if not group:
                person = next((u for u in self.account.chats[cid]["roster"] if u != self.account.username), None)
                if person:
                    blocked = person in self.account.blocked
                    block = Gtk.Button(label=f"{self.account.display(person)} {'nicht mehr blockieren' if blocked else 'blockieren'}",
                                       css_classes=["flat", "lichat-remove"], halign=Gtk.Align.START)
                    block.connect("clicked", lambda *_: (button.popdown(), self.worker.do(lambda: self.account.block(person, not blocked),
                                                                                           lambda *_: self.fill_list())))
                    box.append(block)
            if group:
                leave = Gtk.Button(label="Unterhaltung verlassen", css_classes=["flat", "lichat-remove"], halign=Gtk.Align.START)
                leave.connect("clicked", lambda *_: (button.popdown(), self.group_job(lambda a: a.leave(cid))))
                box.append(leave)
        box.append(media)
        box.append(Gtk.Label(label="Sicherheitscode", css_classes=["lichat-subtitle"], xalign=0, margin_top=8))
        code = self.account.safety_code(cid).split()
        box.append(Gtk.Label(label=" ".join(code[:4]) + "\n" + " ".join(code[4:]), css_classes=["lichat-code"], selectable=True))
        box.append(Gtk.Label(label="Vergleicht den Code auf allen Geräten – gleich heißt: niemand liest mit.", wrap=True,
                             max_width_chars=34, css_classes=["lichat-subtitle"], xalign=0))
        button.set_popover(Gtk.Popover(child=box))

    def fill_contact_menu(self, button):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=8, margin_bottom=8, margin_start=8, margin_end=8)
        mine = Gtk.Button(label="Meinen QR-Code zeigen", css_classes=["flat"])
        mine.connect("clicked", lambda *_: (button.popdown(), self.show_my_code()))
        box.append(mine)
        invite = Gtk.Button(label="Jemanden einladen", css_classes=["flat"])
        invite.connect("clicked", lambda *_: (button.popdown(), self.show_invite()))
        box.append(invite)
        entry = Gtk.Entry(placeholder_text="Kontakt-Link oder PIN einfügen", width_chars=32)
        box.append(entry)

        def add(*_):
            text = entry.get_text().strip()
            if not text:
                return
            button.popdown()
            account = self.account
            if text.startswith("lichat://"):
                self.open_chat_with(lambda: account.add_contact_link(text)[0])
            else:
                self.open_chat_with(lambda: account.redeem_pin(text)[0])
        entry.connect("activate", add)
        go = Gtk.Button(label="Hinzufügen und schreiben", css_classes=["suggested-action"])
        go.connect("clicked", add)
        box.append(go)
        people = self.account.known_people() if self.account else []
        if people:
            box.append(Gtk.Label(label="KONTAKTE", css_classes=["lichat-subtitle"], xalign=0, margin_top=6))
            for user in people[:30]:
                name = self.account.display(user)
                row = Gtk.Button(label=name if name == user else f"{name} ({user})", css_classes=["flat"])
                row.connect("clicked", lambda _b, u=user: (button.popdown(), self.open_chat_with(lambda: u)))
                box.append(row)
        button.set_popover(Gtk.Popover(child=Gtk.ScrolledWindow(child=box, propagate_natural_height=True, max_content_height=480,
                                                               hscrollbar_policy=Gtk.PolicyType.NEVER)))

    def open_chat_with(self, find_user):
        """Someone found by code, PIN or a tap: into my contacts and straight into the conversation with them."""
        account = self.account

        def done(cid, error):
            self.show_notice(error)
            if cid:
                self.composing = False
                self.current = cid
                self.fill_list()
                self.show_conversation()
        self.worker.do(lambda: account.chat_with(find_user()), done)

    def add_people(self, cid, members):
        """Add people to a group (4da136eb): search, my contacts with check boxes, who is in already greyed out, an exact
        user name typed as its own entry; new members see the messages from now on."""
        account = self.account
        people = [(u, account.display(u)) for u in account.known_people()]
        dialog = Adw.AlertDialog(heading="Personen hinzufügen",
                                 body="Neue Mitglieder sehen die Nachrichten ab jetzt – frühere bleiben für sie verborgen.")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        search = Gtk.SearchEntry(placeholder_text="Name oder Benutzername")
        box.append(search)
        rows = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        box.append(Gtk.ScrolledWindow(child=rows, min_content_height=220, max_content_height=360, propagate_natural_height=True,
                                      hscrollbar_policy=Gtk.PolicyType.NEVER))
        chosen = set()

        def fill(*_):
            while (child := rows.get_first_child()) is not None:
                rows.remove(child)
            typed = search.get_text().strip().lower()
            shown = [(u, n) for u, n in people if not typed or typed in u or typed in n.lower()]
            if typed and re.fullmatch(r"[a-z0-9._-]{2,32}", typed) and typed not in members and all(u != typed for u, _ in people):
                shown.insert(0, (typed, typed))
            for user, name in shown:
                inside = user in members
                check = Gtk.CheckButton(label=name if name == user else f"{name} ({user})", active=user in chosen or inside,
                                        sensitive=not inside, tooltip_text="Schon in der Gruppe" if inside else None)
                check.connect("toggled", lambda c, u=user: chosen.add(u) if c.get_active() else chosen.discard(u))
                rows.append(check)
            if not shown:
                rows.append(Gtk.Label(label="Noch keine Kontakte – Benutzernamen eintippen", css_classes=["dim-label"]))
        search.connect("search-changed", fill)
        fill()
        dialog.set_extra_child(box)
        dialog.add_response("abbrechen", "Abbrechen")
        dialog.add_response("hinzufuegen", "Hinzufügen")
        dialog.set_response_appearance("hinzufuegen", Adw.ResponseAppearance.SUGGESTED)
        dialog.connect("response", lambda _d, answer: answer == "hinzufuegen" and chosen and self.group_job(lambda a: a.add(cid, sorted(chosen))))
        dialog.present(self)
        self.people_dialog = (dialog, search, rows, chosen)       # for the GUI test

    def show_invite(self):
        """An invitation for someone new (Olaf 10.10.): its QR code – any phone camera opens the invitation page, LiChat
        takes server and code from it – the code, and the link to copy."""
        from .qrcodegen import QrCode
        account = self.account
        dialog = Adw.AlertDialog(heading="Jemanden einladen",
                                 body="Die andere Person installiert LiChat und scannt den Code – sie gibt nur noch ihren Namen ein "
                                      "und landet direkt im Gespräch mit dir. 7 Tage gültig, einmal.")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        area = Gtk.DrawingArea(content_width=220, content_height=220, halign=Gtk.Align.CENTER, css_classes=["lichat-qr"])
        code_label = Gtk.Label(label="…", css_classes=["title-2"], selectable=True)
        link_label = Gtk.Label(label="", selectable=True, wrap=True, wrap_mode=Pango.WrapMode.CHAR, max_width_chars=40, css_classes=["dim-label"])
        box.append(area)
        box.append(code_label)
        box.append(link_label)
        dialog.set_extra_child(box)
        dialog.add_response("fertig", "Fertig")

        def made(result, error):
            if error:
                code_label.set_label(error)
                return
            code, link = result
            qr = QrCode.encode_text(link, QrCode.Ecc.MEDIUM)

            def draw(_area, cr, width, height):
                size = qr.get_size() + 8
                scale = min(width, height) / size
                cr.set_source_rgb(1, 1, 1)
                cr.paint()
                cr.set_source_rgb(0, 0, 0)
                for y in range(qr.get_size()):
                    for x in range(qr.get_size()):
                        if qr.get_module(x, y):
                            cr.rectangle((x + 4) * scale, (y + 4) * scale, scale + 0.5, scale + 0.5)
                cr.fill()
            area.set_draw_func(draw)
            area.queue_draw()
            area.update_property([Gtk.AccessibleProperty.LABEL], ["QR-Code der Einladung"])
            code_label.set_label(code)
            link_label.set_label(link)
        self.worker.do(account.new_invite, made)
        dialog.present(self)
        self.invite_dialog = (dialog, code_label, link_label)       # for the GUI test

    def show_my_code(self):
        """My contact code: the QR code a phone scans to reach me, my user name, and a PIN on request (89aae55d)."""
        from .qrcodegen import QrCode
        account = self.account
        dialog = Adw.AlertDialog(heading="Mein QR-Code", body=f"{account.username} – wer ihn mit LiChat scannt, landet direkt im Gespräch mit dir.")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        area = Gtk.DrawingArea(content_width=220, content_height=220, halign=Gtk.Align.CENTER, css_classes=["lichat-qr"])
        pin_label = Gtk.Label(label="", css_classes=["title-1"], selectable=True)
        pin_button = Gtk.Button(label="PIN erzeugen", css_classes=["flat"], halign=Gtk.Align.CENTER)
        link_label = Gtk.Label(label="", selectable=True, wrap=True, wrap_mode=Pango.WrapMode.CHAR, max_width_chars=40, css_classes=["dim-label"])
        box.append(area)
        box.append(link_label)
        box.append(pin_button)
        box.append(pin_label)
        dialog.set_extra_child(box)
        dialog.add_response("fertig", "Fertig")

        def drawn(link, error):
            if error:
                link_label.set_label(error)
                return
            qr = QrCode.encode_text(link, QrCode.Ecc.MEDIUM)

            def draw(_area, cr, width, height):
                size = qr.get_size() + 8
                scale = min(width, height) / size
                cr.set_source_rgb(1, 1, 1)
                cr.paint()
                cr.set_source_rgb(0, 0, 0)
                for y in range(qr.get_size()):
                    for x in range(qr.get_size()):
                        if qr.get_module(x, y):
                            cr.rectangle((x + 4) * scale, (y + 4) * scale, scale + 0.5, scale + 0.5)
                cr.fill()
            area.set_draw_func(draw)
            area.update_property([Gtk.AccessibleProperty.LABEL], [f"QR-Code zum Hinzufügen von {account.username}"])
            link_label.set_label(link)
        self.worker.do(account.contact_link, drawn)

        def pin_made(pin, error):
            pin_label.set_label(f"{pin}  · 10 Minuten gültig, einmal" if pin else (error or ""))
        pin_button.connect("clicked", lambda *_: self.worker.do(account.new_pin, pin_made))
        dialog.present(self)
        self.code_dialog = (dialog, link_label, pin_label)       # for the GUI test

    def start_composing(self):
        self.composing = True
        self.current = None
        self.list.unselect_all()
        self.to_entry.set_text("")
        self.show_conversation()
        self.to_entry.grab_focus()

    def show_notice(self, text):
        self.notice.set_label(text or "")
        self.notice.set_visible(bool(text))

    # ------------------------------------------------------------------------------------------ server work

    def send(self):
        text = self.input.get_text().strip()
        if not text:
            return
        account = self.account
        if self.composing:
            names = [n.strip().lower() for n in self.to_entry.get_text().replace(";", ",").split(",") if n.strip()]
            if not names:
                self.show_notice("An wen? Benutzernamen eintragen.")
                return

            def job():
                cid = account.start(names)
                account.send(cid, text)
                return cid
        elif getattr(self, "editing", None):
            cid, mid = self.current, self.editing["mid"]
            self.end_reply()

            def job():
                account.edit(cid, mid, text)
                return cid
        else:
            cid = self.current
            reply = self.reply_to["mid"] if getattr(self, "reply_to", None) else None
            self.end_reply()

            def job():
                account.send(cid, text, reply_to=reply)
                return cid

        self.input.set_sensitive(False)

        def done(cid, error):
            self.input.set_sensitive(True)
            self.input.grab_focus()
            self.show_notice(error)
            if error:
                if not self.composing:
                    # The message is kept with „Nicht gesendet“ under it – a click there sends it again.
                    self.input.set_text("")
                    self.show_conversation()
                return
            self.input.set_text("")
            self.composing = False
            self.current = cid
            self.fill_list()
            self.show_conversation()

        self.worker.do(job, done)

    def fetched(self, news):
        self.show_notice("")
        if not news:
            return
        self.fill_list()
        if self.current and any(n.get("chat") == self.current for n in news):
            self.show_conversation()   # new bubbles, or ticks that moved
        for item in news:
            if item.get("text") is None or item.get("system") or item.get("from") in (None, self.account.username):
                continue
            if self.is_active() and self.get_visible() and item["chat"] == self.current:
                continue
            if self.account.chats.get(item["chat"], {}).get("stumm") and not item.get("mention"):
                continue     # muted (card d45b319a) – a mention still comes through (d54175ed)
            self.notify(item)

    def notify(self, item):
        """A GNOME notification. By default only „Neue Nachricht“ (Michelle S4) – sender and text only when switched on."""
        note = Gio.Notification.new("LiChat")
        if self.settings["vorschau"]:
            who = self.account.display(item["from"])     # the display name, not the user name (Michelle)
            note.set_body(f"{who} hat dich erwähnt: {item['text']}" if item.get("mention") else f"{who}: {item['text']}")
        else:
            note.set_body("Neue Erwähnung" if item.get("mention") else "Neue Nachricht")
        note.set_default_action_and_target("app.oeffnen", GLib.Variant.new_string(item["chat"]))
        self.last_notification = note
        self.get_application().send_notification(f"lichat-{item['chat']}", note)

    def open_chat(self, cid):
        if cid in self.account.chats:
            self.composing = False
            self.current = cid
            self.fill_list()
            self.show_conversation()
        self.present()

    def on_setting(self, action, value):
        action.set_state(value)
        self.set_setting(action.get_name(), value.get_boolean())
        if action.get_name() == "hintergrund" and not self.separate:   # a LICHAT_ORDNER LiChat never touches autostart
            autostart(value.get_boolean())

    def explain_background(self):
        """Once, at the first start (Michelle Z6/Z7): what „im Hintergrund“ means, and that it can be switched off."""
        self.explained = Adw.AlertDialog(
            heading="Nachrichten auch bei geschlossenem Fenster",
            body="LiChat bleibt nach dem Schließen des Fensters verbunden und startet nach der Anmeldung am Rechner mit, "
                 "damit neue Nachrichten sofort ankommen. Es spricht dabei nur mit deinem LiChat-Server – kein anderer "
                 "Dienst erfährt davon. Ohne Hintergrund kommen Nachrichten erst, wenn du LiChat öffnest.\n\n"
                 "Du kannst das jederzeit im Menü ⋮ ändern.")
        self.explained.add_response("aus", "Ausschalten")
        self.explained.add_response("an", "Eingeschaltet lassen")
        self.explained.set_default_response("an")
        self.explained.connect("response", self.on_explained)
        self.explained.present(self)
        return False

    def on_explained(self, _dialog, response):
        self.set_setting("erklaert", True)
        if response == "aus":
            self.activate_action("win.hintergrund", None)

    # ------------------------------------------------------------------------------------------ ticks (card db70d358)

    TAPBACKS = ("❤️", "👍", "👎", "😂", "‼️", "❓")

    def message_menu(self, cid, message, widget):
        mine = (message.get("reaktionen") or {}).get(self.account.username, "")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_start=8, margin_end=8, margin_top=8, margin_bottom=8)
        row = Gtk.Box(spacing=2)
        popover = Gtk.Popover(child=box, has_arrow=True)
        for emoji in self.TAPBACKS:
            button = Gtk.Button(label=emoji, css_classes=["flat", "lichat-tapback"] + (["lichat-tapback-on"] if emoji == mine else []),
                                tooltip_text="Zurücknehmen" if emoji == mine else "Reagieren")
            button.connect("clicked", lambda _b, e=emoji: (popover.popdown(), self.react(cid, message["mid"], "" if e == mine else e)))
            row.append(button)
        if mine and mine not in self.TAPBACKS:
            # My own emoji from „+“ – shown chosen, a click takes it back.
            chosen = Gtk.Button(label=mine, css_classes=["flat", "lichat-tapback", "lichat-tapback-on"], tooltip_text="Zurücknehmen")
            chosen.connect("clicked", lambda *_: (popover.popdown(), self.react(cid, message["mid"], "")))
            row.append(chosen)
        # „+“: any emoji (Olaf 09.10., card a7d9d987) – GTK's own emoji chooser, like the Mac's.
        more = Gtk.MenuButton(label="+", css_classes=["flat", "lichat-tapback"], tooltip_text="Anderes Emoji")
        chooser = Gtk.EmojiChooser()
        chooser.connect("emoji-picked", lambda _c, e: (popover.popdown(), self.react(cid, message["mid"], e)))
        more.set_popover(chooser)
        row.append(more)
        box.append(row)
        answer = Gtk.Button(label="Antworten", css_classes=["flat"])
        answer.connect("clicked", lambda *_: (popover.popdown(), self.start_reply(message)))
        box.append(answer)
        if message.get("text"):
            copy = Gtk.Button(label="Kopieren", css_classes=["flat"])
            copy.connect("clicked", lambda *_: (popover.popdown(), self.get_clipboard().set(message["text"])))
            box.append(copy)
        delete = Gtk.Button(label="Löschen", css_classes=["flat", "lichat-destructive"])
        delete.connect("clicked", lambda *_: (popover.popdown(), self.worker.do(lambda: self.account.delete_message(cid, message["mid"]),
                                                                                 lambda *_: self.show_conversation())))
        box.append(delete)
        if self.account.can_edit(message):
            change = Gtk.Button(label="Bearbeiten", css_classes=["flat"])
            change.connect("clicked", lambda *_: (popover.popdown(), self.start_edit(message)))
            box.append(change)
        if self.account.can_unsend(message):
            back = Gtk.Button(label="Senden widerrufen", css_classes=["flat", "lichat-destructive"])
            back.connect("clicked", lambda *_: (popover.popdown(), self.unsend(cid, message["mid"])))
            box.append(back)
        popover.set_parent(widget)
        popover.popup()
        self.open_menu = popover

    def react(self, cid, mid, emoji):
        account = self.account
        self.worker.do(lambda: account.react(cid, mid, emoji), lambda _r, error: (self.show_notice(error), self.show_conversation()))

    def start_edit(self, message):
        """Correcting one of my messages (card 4ceca163): its text in the input line, „Nachricht bearbeiten“ above it."""
        self.reply_to = None
        self.editing = message
        self.reply_label.set_label(f"Nachricht bearbeiten: {message.get('text', '')}")
        self.reply_line.set_visible(True)
        self.input.set_text(message.get("text", ""))
        self.input.grab_focus()
        self.input.set_position(-1)

    def unsend(self, cid, mid):
        account = self.account
        self.worker.do(lambda: account.unsend(cid, mid), lambda _r, error: (self.show_notice(error), self.show_conversation(), self.fill_list()))

    def start_reply(self, message):
        """Answering: a line above the input with the quote, ✕ takes it back."""
        self.editing = None
        self.reply_to = message
        who = "dich" if message["from"] == self.account.username else self.account.display(message["from"])
        self.reply_label.set_label(f"Antwort an {who}: {Account.describe(message) or message.get('text', '')}")
        self.reply_line.set_visible(True)
        self.input.grab_focus()

    def end_reply(self):
        self.reply_to = None
        self.editing = None
        self.reply_line.set_visible(False)

    def tick_line(self, cid, message):
        """Under my bubble, grey: the time and ◷ / ✓ / ✓✓ / blue ✓✓ (17 × 11, line 1.6, round ends – Olaf's scheme with
        Jaques). Not sent: a red line, a click sends it again."""
        state, at = self.account.tick(cid, message)
        if state == "fehler":
            retry = Gtk.Button(label="Nicht gesendet – klicken zum Wiederholen", css_classes=["flat", "lichat-failed"], halign=Gtk.Align.END)
            retry.connect("clicked", lambda *_: self.retry(cid, message["mid"]))
            return retry
        line = Gtk.Box(spacing=3, halign=Gtk.Align.END, css_classes=["lichat-meta"])
        line.append(Gtk.Label(label=time.strftime("%H:%M", time.localtime(message["at"])), css_classes=["lichat-meta"]))
        mark = Gtk.DrawingArea(content_width=17, content_height=11)
        mark.set_draw_func(self.draw_tick, state)
        line.append(mark)
        words = {"wartet": "Wird gesendet", "gesendet": "Gesendet", "zugestellt": "Zugestellt", "gelesen": "Gelesen"}[state]
        line.set_tooltip_text(f"{words} {time.strftime('%H:%M', time.localtime(at))}")
        line.update_property([Gtk.AccessibleProperty.LABEL], [f"{words} {time.strftime('%H:%M', time.localtime(at))}"])
        return line

    def draw_tick(self, area, cr, width, height, state):
        dark = self.has_css_class("lichat-dark")
        if state == "gelesen":
            cr.set_source_rgb(*((0.04, 0.52, 1.0) if dark else (0.0, 0.48, 1.0)))
        else:
            cr.set_source_rgba(*((0.92, 0.92, 0.96, 0.6) if dark else (0.24, 0.24, 0.26, 0.6)))
        cr.set_line_cap(1)   # round
        cr.set_line_join(1)
        cr.set_line_width(1.3 if state == "wartet" else 1.6)
        if state == "wartet":            # a small clock
            cr.arc(8.5, 5.5, 4.3, 0, 6.2832)
            cr.move_to(8.5, 3.2); cr.line_to(8.5, 5.7); cr.line_to(10.1, 6.7)
        elif state == "gesendet":        # one tick
            cr.move_to(4, 5.8); cr.line_to(6.6, 8.4); cr.line_to(12.6, 2.4)
        else:                            # two ticks
            cr.move_to(1.2, 5.8); cr.line_to(3.8, 8.4); cr.line_to(9.8, 2.4)
            cr.move_to(7.4, 8.1); cr.line_to(7.7, 8.4); cr.line_to(13.7, 2.4)
        cr.stroke()

    def retry(self, cid, mid):
        account = self.account

        def done(_r, error):
            self.show_notice(error)
            self.show_conversation()

        self.worker.do(lambda: account.retry(cid, mid, changed=self.upload_moved), done)

    def upload_moved(self):
        """From the worker thread while an attachment goes up: redraw the conversation once, not for every piece."""
        if not getattr(self, "_redraw_waiting", False):
            self._redraw_waiting = True

            def redraw():
                self._redraw_waiting = False
                self.show_conversation()
                return False
            GLib.idle_add(redraw)

    def with_upload(self, box, message):
        """Under my bubble while it goes up: how far, and „Abbrechen“ (cf647f61, like the ring in Messages)."""
        share = self.account.uploads.get(message.get("mid")) if message is not None else None
        if share is None:
            return box
        row = Gtk.Box(spacing=6, halign=Gtk.Align.END, margin_top=4)
        bar = Gtk.ProgressBar(fraction=share, valign=Gtk.Align.CENTER, width_request=140,
                              tooltip_text=f"Wird gesendet, {int(share * 100)} %")
        cancel = Gtk.Button(label="Abbrechen", css_classes=["flat"])
        cancel.connect("clicked", lambda *_: self.account.cancel_upload(message.get("mid")))
        row.append(bar)
        row.append(cancel)
        box.append(row)
        return box

    # ------------------------------------------------------------------------------------------ attachments

    def once_bubble(self, message, mine, box):
        """A one-time picture: never a preview, only what it is and where it stands (fe9f2029)."""
        state = self.account.once_state(message)
        if state == "eigen":
            seen = message.get("geoeffnet_von") or {}
            note = "Geöffnet" if seen else "Einmal-Ansicht"
        else:
            note = {"neu": "Antippen zum Ansehen" if ONCE_ON_DESKTOP else "Nur auf dem Handy ansehen",
                    "geoeffnet": "Geöffnet", "abgelaufen": "Abgelaufen"}[state]
        label = Gtk.Label(label=f"◉  Foto\n{note}", xalign=0, css_classes=["lichat-bubble", "lichat-mine" if mine else "lichat-theirs"])
        button = Gtk.Button(child=label, css_classes=["lichat-attachment", "flat"])
        cid, mid = self.current, message.get("mid")

        def opened(data, error):
            self.show_notice(error)
            self.show_conversation()
            if data:
                self.show_picture(data, "Einmal-Bild")     # in memory only; gone with the window
        account = self.account

        def clicked(*_):
            if state != "neu":
                return
            if not ONCE_ON_DESKTOP:
                self.show_notice("Einmal-Bilder lassen sich nur auf dem Handy ansehen – hier kann nichts Bildschirmfotos verhindern.")
                return
            self.worker.do(lambda: account.open_once(cid, mid), opened)
        button.connect("clicked", clicked)
        box.append(button)
        return box

    @staticmethod
    def pixelated(data):
        """A secret picture (f9e3f055): made coarse here, from the picture itself – 6 blocks on the short side, then frosted
        grey (Michelle c5efaa9: with 14 blocks stripes and colours still showed)."""
        import io
        from PIL import Image
        with Image.open(io.BytesIO(data)) as image:
            image = image.convert("RGB")
            block = max(1, min(image.size) // 6)
            small = image.resize((max(1, image.width // block), max(1, image.height // block)), Image.BILINEAR)
            coarse = Image.blend(small.resize(image.size, Image.NEAREST), Image.new("RGB", image.size, (142, 142, 147)), 0.55)
            out = io.BytesIO()
            coarse.save(out, "PNG")
        return Gdk.Texture.new_from_bytes(GLib.Bytes.new(out.getvalue()))

    def attachment_bubble(self, info, mine, message=None):
        """A photo or video as its small picture, a voice message with ▶ and its length, a file with its name."""
        kind = info.get("art")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=2)
        if info.get("einmal") and message is not None:
            return self.once_bubble(message, mine, box)
        hidden = info.get("geheim") and info.get("id") not in self.revealed
        button = Gtk.Button(css_classes=["lichat-attachment", "flat"], tooltip_text="Antippen zum Aufdecken" if hidden else info.get("name"))
        if kind in ("bild", "video") and info.get("vorschau") and hidden:
            try:
                texture = self.pixelated(unb64(info["vorschau"]))
                picture = Gtk.Picture(paintable=texture, can_shrink=True, content_fit=Gtk.ContentFit.COVER)
                scale = min(240 / max(texture.get_width(), 1), 240 / max(texture.get_height(), 1))
                picture.set_size_request(int(texture.get_width() * scale), int(texture.get_height() * scale))
                frame = Gtk.Overlay(child=picture, css_classes=["lichat-photo"], overflow=Gtk.Overflow.HIDDEN)
                frame.add_overlay(Gtk.Label(label="Geheimbild", css_classes=["lichat-play"], halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER))
                button.set_child(frame)
                button.connect("clicked", lambda *_: self.reveal(info.get("id")))
                box.append(button)
                return box
            except (GLib.Error, OSError):
                pass
        if kind in ("bild", "video") and info.get("vorschau"):
            try:
                texture = self.full_images.get(info.get("id")) or Gdk.Texture.new_from_bytes(GLib.Bytes.new(unb64(info["vorschau"])))
                picture = Gtk.Picture(paintable=texture, can_shrink=True, content_fit=Gtk.ContentFit.COVER)
                if info.get("id") in self.gif_data and Gtk.Settings.get_default().get_property("gtk-enable-animations"):
                    self.animate_gif(picture, self.gif_data[info["id"]], info["id"])      # rebuilt bubble: run on
                elif (kind == "bild" and info.get("id") not in self.full_images and info.get("id") not in self.loading_images
                        and (info.get("groesse") or 0) <= 15 * 1024 * 1024):
                    self.loading_images.add(info.get("id"))
                    # The photo itself replaces the 320 px preview (Olaf ed0686a1: blurry) – loaded once, kept while LiChat runs.
                    account = self.account

                    def loaded(data, error, p=picture, key=info.get("id"), gif=info.get("mime") == "image/gif"):
                        if data and gif and Gtk.Settings.get_default().get_property("gtk-enable-animations"):
                            # A GIF runs in the bubble like in Messages (999f37be) – still when animations are off.
                            self.gif_data[key] = data
                            self.animate_gif(p, data, key)
                            return
                        if data:
                            try:
                                sharp = Gdk.Texture.new_from_bytes(GLib.Bytes.new(data))
                            except GLib.Error:
                                return
                            self.full_images[key] = sharp
                            p.set_paintable(sharp)
                            self.show_conversation()     # every bubble of it sharp (the widget may have been rebuilt)
                    Worker.wait(lambda i=info: account.open_attachment(i), loaded)
                width, height = texture.get_width(), texture.get_height()
                scale = min(240 / max(width, 1), 240 / max(height, 1))
                picture.set_size_request(int(width * scale), int(height * scale))
                frame = Gtk.Overlay(child=picture, css_classes=["lichat-photo"], overflow=Gtk.Overflow.HIDDEN)
                if kind == "video":
                    frame.add_overlay(Gtk.Label(label="▶", css_classes=["lichat-play"], halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER))
                button.set_child(frame)
            except GLib.Error:
                kind = "datei"
        if kind == "sprache":
            return self.with_upload(self.voice_bubble(info, mine, box, message), message)
        if button.get_child() is None:
            if kind == "sprache":
                seconds = int(round(info.get("dauer") or 0))
                text = f"▶  Sprachnachricht  {seconds // 60}:{seconds % 60:02d}"
            elif kind == "video":
                text = "▶  Video"
            else:
                text = f"📄  {info.get('name', 'Datei')}  ·  {self.size_text(info.get('groesse', 0))}"
            button.set_child(Gtk.Label(label=text, css_classes=["lichat-bubble", "lichat-mine" if mine else "lichat-theirs"]))
        button.connect("clicked", lambda *_: self.open_attachment(info))
        box.append(button)
        return self.with_upload(box, message)

    def album_bubble(self, photos, mine):
        """Like Messages: 2 photos side by side, 3 as one large and two small, from 4 a stack to page through (5749ebd0)."""
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=2)

        def tile(m, width, height):
            info = m["anhang"]
            try:
                texture = self.full_images.get(info.get("id")) or Gdk.Texture.new_from_bytes(GLib.Bytes.new(unb64(info.get("vorschau", ""))))
                picture = Gtk.Picture(paintable=texture, can_shrink=True, content_fit=Gtk.ContentFit.COVER)
            except (GLib.Error, ValueError):
                picture = Gtk.Label(label="Foto")
            picture.set_size_request(width, height)
            button = Gtk.Button(child=picture, css_classes=["lichat-attachment", "flat"], tooltip_text=info.get("name"))
            button.connect("clicked", lambda *_: self.open_attachment(info))
            return self.with_upload(self._boxed(button), m)

        frame = Gtk.Box(spacing=2, css_classes=["lichat-photo"], overflow=Gtk.Overflow.HIDDEN)
        if len(photos) == 2:
            for m in photos:
                frame.append(tile(m, 119, 150))
        elif len(photos) == 3:
            frame.append(tile(photos[0], 140, 240))
            right = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            right.append(tile(photos[1], 98, 119))
            right.append(tile(photos[2], 98, 119))
            frame.append(right)
        else:
            carousel = Adw.Carousel(spacing=8, allow_scroll_wheel=True)
            for m in photos:
                carousel.append(tile(m, 240, 240))
            stack = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
            stack.append(carousel)
            stack.append(Adw.CarouselIndicatorDots(carousel=carousel))
            stack.set_tooltip_text(f"Stapel mit {len(photos)} Fotos – wischen oder scrollen zum Blättern")
            frame.append(stack)
        box.append(frame)
        return box

    @staticmethod
    def _boxed(widget):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(widget)
        return box

    def animate_gif(self, picture, data, key):
        try:
            from gi.repository import GdkPixbuf
            loader = GdkPixbuf.PixbufLoader.new_with_type("gif")
            loader.write(data)
            loader.close()
            animation = loader.get_animation()
        except GLib.Error:
            return
        if animation.is_static_image():
            texture = Gdk.Texture.new_for_pixbuf(animation.get_static_image())
            self.full_images[key] = texture
            picture.set_paintable(texture)
            return
        frames = animation.get_iter(None)
        self.full_images[key] = Gdk.Texture.new_for_pixbuf(frames.get_pixbuf())

        def step():
            if picture.get_root() is None:
                return False                  # the bubble was rebuilt – its successor runs its own GIF
            frames.advance(None)
            picture.set_paintable(Gdk.Texture.new_for_pixbuf(frames.get_pixbuf()))
            GLib.timeout_add(max(20, frames.get_delay_time()), step)
            return False
        picture.set_paintable(self.full_images[key])
        GLib.timeout_add(max(20, frames.get_delay_time()), step)

    @staticmethod
    def size_text(size):
        for unit in ("Byte", "KB", "MB"):
            if size < 1024 or unit == "MB":
                return f"{size:.0f} {unit}" if unit == "Byte" else f"{size:.1f} {unit}".replace(".", ",")
            size /= 1024

    def private_folder(self):
        """Plain copies only for showing them: in the session's private folder in memory (XDG_RUNTIME_DIR, gone at logout)."""
        base = Path(os.environ.get("XDG_RUNTIME_DIR") or "/tmp") / "lichat"
        base.mkdir(mode=0o700, exist_ok=True)
        os.chmod(base, 0o700)
        return base

    # ------------------------------------------------------------------------------------------ voice messages (864041bf)
    # Like Messages: ▶ ⇄ ⏸, the wave fills as it plays, the time left counts down, a pause keeps the place, a click or drag
    # on the wave jumps, at the end it is back at the start. Played here (GStreamer via Gtk.MediaFile), not in another app.

    WAVE = (6, 12, 18, 10, 16, 8, 14, 20, 12, 6, 10, 16, 8, 12)

    def voice_bubble(self, info, mine, box, message=None):
        bubble = Gtk.Box(spacing=8, css_classes=["lichat-bubble", "lichat-mine" if mine else "lichat-theirs", "lichat-voice"])
        toggle = Gtk.Button(css_classes=["flat", "lichat-voice-button"], tooltip_text="Abspielen")
        # the message's real wave when it brought one (1a682a79, like Signal), else the fixed one
        bars = [max(1, min(31, int(v))) for v in info.get("welle") or []] or [h * 31 // 20 for h in self.WAVE]
        wave = Gtk.DrawingArea(content_width=200, content_height=26, valign=Gtk.Align.CENTER)
        time_label = Gtk.Label(css_classes=["lichat-voice-time"])
        bubble.append(toggle)
        bubble.append(wave)
        bubble.append(time_label)
        unheard = message is not None and not mine and not message.get("angehoert")
        if unheard:
            # never heard: a dot beside the time, like Signal
            bubble.append(Gtk.Label(label="●", css_classes=["lichat-unheard"], tooltip_text="Noch nicht angehört"))
        key = info.get("id")
        light = mine or self.has_css_class("lichat-dark")

        def state():
            voice = getattr(self, "voice", None)
            if not voice or voice["id"] != key:
                return None, 0.0, int(round(info.get("dauer") or 0))
            media = voice["media"]
            length = media.get_duration() or 1
            return media, media.get_timestamp() / length, max(0, (length - media.get_timestamp() + 999_999) // 1_000_000)

        def refresh():
            media, fraction, left = state()
            playing = media is not None and media.get_playing()
            toggle.set_label("⏸" if playing else "▶")
            toggle.set_tooltip_text("Anhalten" if playing else "Abspielen")
            time_label.set_label(f"{left // 60}:{left % 60:02d}")
            wave.queue_draw()
            return media is not None and wave.get_root() is not None and not media.get_ended()

        def draw(_area, cr, width, height):
            media, fraction, _left = state()
            step = width / len(bars)
            for i, h in enumerate(bars):
                done = media is not None and (i + 0.5) / len(bars) <= fraction
                alpha = 0.8 if media is None else (1.0 if done else 0.4)
                cr.set_source_rgba(*((1, 1, 1) if light else (0.11, 0.11, 0.12)), alpha)
                tall = max(2.0, height * h / 31)
                cr.rectangle(i * step + step * 0.2, (height - tall) / 2, max(1.5, step * 0.6), tall)
                cr.fill()
        wave.set_draw_func(draw)

        def jump(x):
            media, _f, _l = state()
            if media is not None and media.get_duration():
                media.seek(int(media.get_duration() * max(0.0, min(1.0, x / max(1, wave.get_width())))))
                refresh()
        click = Gtk.GestureClick()
        click.connect("pressed", lambda _g, _n, x, _y: jump(x))
        drag = Gtk.GestureDrag()
        drag.connect("drag-update", lambda g, dx, _dy: jump(g.get_start_point()[1] + dx))
        wave.add_controller(click)
        wave.add_controller(drag)

        def pressed(*_):
            media, _f, _l = state()
            if media is not None:
                if media.get_ended():
                    media.seek(0)
                media.set_playing(not media.get_playing())
                refresh()
                GLib.timeout_add(100, refresh)
                return
            account = self.account
            folder = self.private_folder()

            cid = self.current

            def job():
                if cid and message is not None and not mine:
                    account.mark_listened(cid, key)      # the dot goes (1a682a79)
                path = folder / f"{key[:8]}-sprache.m4a"
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "wb") as f:
                    f.write(account.open_attachment(info))
                return path

            def loaded(path, error):
                self.show_notice(error)
                if path is None:
                    return
                old = getattr(self, "voice", None)
                if old:
                    old["media"].set_playing(False)
                media = Gtk.MediaFile.new_for_filename(str(path))
                media.connect("notify::ended", lambda m, _p: (m.get_ended() and (m.set_playing(False), m.seek(0), refresh())))
                self.voice = {"id": key, "media": media}
                media.play()
                GLib.timeout_add(100, refresh)
            self.worker.do(job, loaded)
        toggle.connect("clicked", pressed)
        refresh()
        if state()[0] is not None:
            GLib.timeout_add(100, refresh)
        bubble.update_property([Gtk.AccessibleProperty.LABEL], [f"Sprachnachricht, {int(round(info.get('dauer') or 0))} Sekunden"])
        box.append(bubble)
        return box

    def open_attachment(self, info):
        account = self.account
        folder = self.private_folder()

        def job():
            data = account.open_attachment(info)
            path = folder / f"{info['id'][:8]}-{info.get('name') or 'anhang'}"
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(data)
            return path

        def done(path, error):
            self.show_notice(error)
            if path is not None:
                self.last_opened = path
                Gtk.FileLauncher.new(Gio.File.new_for_path(str(path))).launch(self, None, None)

        self.worker.do(job, done)

    def choose_attachment(self):
        if self.current is None and not self.composing:
            return
        dialog = Gtk.FileDialog(title="Anhang wählen")
        dialog.open_multiple(self, None, self.on_attachment_chosen)       # several at once (5749ebd0)

    def on_attachment_chosen(self, dialog, result):
        import mimetypes
        try:
            files = dialog.open_multiple_finish(result)
        except GLib.Error:
            return
        paths = [Path(files.get_item(i).get_path()) for i in range(files.get_n_items())]
        photos = [p for p in paths if (mimetypes.guess_type(p.name)[0] or "").startswith("image/")
                  and mimetypes.guess_type(p.name)[0] != "image/svg+xml"][:ALBUM_MAX]
        for path in paths:
            if path not in photos or len(photos) < 2:
                self.send_attachment(path)
        if len(photos) >= 2:
            self.album_options(photos)

    def album_options(self, paths):
        """Several photos: one question for all of them, text to them – they leave as an album (5749ebd0)."""
        dialog = Adw.AlertDialog(heading=f"{len(paths)} Fotos senden")
        options = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        thumbs = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=5, column_spacing=4, row_spacing=4)
        for path in paths:
            picture = Gtk.Picture(file=Gio.File.new_for_path(str(path)), can_shrink=True, content_fit=Gtk.ContentFit.COVER)
            picture.set_size_request(56, 56)
            thumbs.append(picture)
        options.append(thumbs)
        caption = Gtk.Entry(placeholder_text="Text zu den Fotos", text=self.input.get_text())
        options.append(caption)
        dialog.set_extra_child(options)
        dialog.add_response("abbrechen", "Abbrechen")
        dialog.add_response("senden", "Senden")
        dialog.set_response_appearance("senden", Adw.ResponseAppearance.SUGGESTED)
        dialog.set_default_response("senden")

        def answered(_d, answer):
            if answer == "senden":
                self.input.set_text("")
                self.send_album(paths, caption.get_text().strip())
        dialog.connect("response", answered)
        dialog.present(self)
        self.album_dialog = (dialog, caption)       # for the GUI test
        return dialog

    def send_album(self, paths, caption=""):
        account, cid = self.account, self.current
        if cid is None:
            return

        def job():
            part = account.new_album(len(paths))
            laid = [account.send_attachment(cid, p.read_bytes(), p.name, "image/jpeg", "bild", album=part(n + 1), defer=True,
                                            caption=caption if n == len(paths) - 1 else "", changed=self.upload_moved)
                    for n, p in enumerate(paths)]
            failed = None
            for message in laid:            # all bubbles are there – now up, one after the other
                try:
                    account.send_queued(cid, message, changed=self.upload_moved)
                except LiChatError as error:
                    failed = failed or error
            if failed:
                raise failed
            return cid

        def done(_cid, error):
            self.show_notice(error)
            self.fill_list()
            self.show_conversation()

        self.worker.do(job, done)

    def send_attachment(self, path, once=None, secret=False, caption=""):
        """The kind from the file's type: photo, video, audio (as a voice message) or any other file. A photo first asks
        how: normal, as a one-time picture (fe9f2029) or pixelated (f9e3f055)."""
        import mimetypes
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        kind = "bild" if mime.startswith("image/") and mime != "image/svg+xml" else "video" if mime.startswith("video/") \
            else "sprache" if mime.startswith("audio/") else "datei"
        account, cid = self.account, self.current
        if cid is None:
            self.show_notice("Erst das Gespräch beginnen, dann den Anhang schicken.")
            return
        if kind == "bild" and once is None:
            self.photo_options(path)
            return

        def job():
            account.send_attachment(cid, path.read_bytes(), path.name, mime, kind, once=bool(once), secret=secret,
                                    changed=self.upload_moved, caption=caption)
            return cid

        def done(_cid, error):
            self.show_notice(error)
            self.fill_list()
            self.show_conversation()

        self.worker.do(job, done)

    def photo_options(self, path):
        dialog = Adw.AlertDialog(heading="Foto senden", body=path.name)
        options = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        try:                                        # the photo itself, small (2fcc3eef)
            picture = Gtk.Picture(file=Gio.File.new_for_path(str(path)), can_shrink=True, content_fit=Gtk.ContentFit.CONTAIN)
            picture.set_size_request(240, 180)
            options.append(picture)
        except GLib.Error:
            pass
        # 2fcc3eef: text to the photo – one message, the caption under the picture (what was typed already goes along)
        caption = Gtk.Entry(placeholder_text="Text zum Foto", text=self.input.get_text())
        options.append(caption)
        once = Gtk.CheckButton(label="Einmal-Ansicht – nach dem Ansehen weg")
        secret = Gtk.CheckButton(label="Verpixeln – erst ein Tippen zeigt es")
        options.append(once)
        options.append(secret)
        dialog.set_extra_child(options)
        dialog.add_response("abbrechen", "Abbrechen")
        dialog.add_response("senden", "Senden")
        dialog.set_response_appearance("senden", Adw.ResponseAppearance.SUGGESTED)
        dialog.set_default_response("senden")
        def answered(_d, answer):
            if answer == "senden":
                self.input.set_text("")
                self.send_attachment(path, once.get_active(), secret.get_active(), caption.get_text().strip())
        dialog.connect("response", answered)
        dialog.present(self)
        self.photo_dialog = (dialog, once, secret)       # for the GUI test
        self.photo_caption = caption
        return dialog

    def show_media(self, cid):
        """Fotos, Links, Dokumente, Sprachnachrichten of this chat, sent and received (43358d35) – one-time pictures never,
        secret ones only pixelated."""
        media = self.account.media(cid)
        window = Adw.Window(title="Medien", transient_for=self, modal=True, default_width=640, default_height=560)
        stack = Adw.ViewStack()
        photos = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, min_children_per_line=3, max_children_per_line=4,
                             homogeneous=True, column_spacing=2, row_spacing=2, valign=Gtk.Align.START)
        for message in media["fotos"]:
            info = message["anhang"]
            try:
                data = unb64(info.get("vorschau", ""))
                texture = self.pixelated(data) if info.get("geheim") else Gdk.Texture.new_from_bytes(GLib.Bytes.new(data))
            except (GLib.Error, OSError, ValueError):
                continue
            picture = Gtk.Picture(paintable=texture, content_fit=Gtk.ContentFit.COVER, can_shrink=True)
            picture.set_size_request(110, 110)
            tile = Gtk.Button(child=picture, css_classes=["flat"], tooltip_text="Geheimbild" if info.get("geheim") else info.get("name"))
            tile.connect("clicked", lambda *_, i=info: self.open_attachment(i))
            photos.append(tile)

        def page(widget, name, title, icon, empty):
            if isinstance(widget, Gtk.FlowBox) and widget.get_first_child() is None or isinstance(widget, Gtk.ListBox) and widget.get_row_at_index(0) is None:
                widget = Adw.StatusPage(title=empty, icon_name=icon)
            stack.add_titled_with_icon(Gtk.ScrolledWindow(child=widget, vexpand=True), name, title, icon)

        def rows(items, line, action):
            box = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE, margin_start=12, margin_end=12, margin_top=12, valign=Gtk.Align.START)
            for item in items:
                title, sub = line(item)
                row = Adw.ActionRow(title=GLib.markup_escape_text(title), subtitle=sub, activatable=True)
                row.connect("activated", lambda *_, i=item: action(i))
                box.append(row)
            return box
        when = lambda m: time.strftime("%d.%m.%Y", time.localtime(m.get("at", 0)))
        who = lambda m: "Du" if m.get("from") == self.account.username else self.account.display(m.get("from", ""))
        page(photos, "fotos", "Fotos", "image-x-generic-symbolic", "Keine Fotos")
        page(rows(media["links"], lambda l: (l["url"], f"{who(l['message'])} · {when(l['message'])}"),
                  lambda l: Gtk.UriLauncher.new(l["url"]).launch(self, None, None)), "links", "Links", "web-browser-symbolic", "Keine Links")
        page(rows(media["dokumente"], lambda m: (m["anhang"].get("name", "Datei"), f"{self.size_text(m['anhang'].get('groesse', 0))} · {who(m)} · {when(m)}"),
                  lambda m: self.open_attachment(m["anhang"])), "dokumente", "Dokumente", "x-office-document-symbolic", "Keine Dokumente")
        page(rows(media["sprache"], lambda m: (f"Sprachnachricht · {int(round(m['anhang'].get('dauer') or 0)) // 60}:{int(round(m['anhang'].get('dauer') or 0)) % 60:02d}", f"{who(m)} · {when(m)}"),
                  lambda m: self.open_attachment(m["anhang"])), "sprache", "Sprache", "audio-x-generic-symbolic", "Keine Sprachnachrichten")
        header = Adw.HeaderBar(title_widget=Adw.ViewSwitcher(stack=stack, policy=Adw.ViewSwitcherPolicy.WIDE))
        view = Adw.ToolbarView(content=stack)
        view.add_top_bar(header)
        window.set_content(view)
        window.present()
        return window

    def group_job(self, job):
        """Adding, renaming, leaving: in the worker, then everything drawn again."""
        account = self.account

        def done(_r, error):
            self.show_notice(error)
            self.fill_list()
            self.show_conversation()

        self.worker.do(lambda: job(account), done)

    def remove_member(self, cid, name):
        account = self.account

        def done(_result, error):
            self.show_notice(error)
            self.fill_list()
            self.show_conversation()

        self.worker.do(lambda: account.remove(cid, [name]), done)

    def on_receipts(self, action, value):
        action.set_state(value)
        account = self.account
        self.worker.do(lambda: account.set_read_receipts(value.get_boolean()), lambda *_: self.show_conversation())

    def on_typing_setting(self, action, value):
        action.set_state(value)
        account = self.account
        self.worker.do(lambda: account.set_typing_indicator(value.get_boolean()), lambda *_: self.show_conversation())

    def edit_name(self):
        """Vorname and Nachname as they should be shown – sent end-to-end into every conversation."""
        mine = self.account.profiles.get("name") or {}
        dialog = Adw.AlertDialog(heading="Dein Name", body=f"So sehen dich deine Gesprächspartner. Dein Benutzername bleibt „{self.account.username}“.")
        fields = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        first = Gtk.Entry(text=mine.get("vorname", ""), placeholder_text="Vorname", input_hints=Gtk.InputHints.UPPERCASE_WORDS)
        last = Gtk.Entry(text=mine.get("nachname", ""), placeholder_text="Nachname", input_hints=Gtk.InputHints.UPPERCASE_WORDS)
        fields.append(first)
        fields.append(last)
        dialog.set_extra_child(fields)
        dialog.add_response("abbrechen", "Abbrechen")
        dialog.add_response("sichern", "Sichern")
        dialog.set_response_appearance("sichern", Adw.ResponseAppearance.SUGGESTED)
        dialog.set_default_response("sichern")
        account = self.account

        def chosen(_d, response):
            if response == "sichern":
                self.worker.do(lambda: account.set_name(first.get_text(), last.get_text()), lambda *_: self.fill_list())
        dialog.connect("response", chosen)
        dialog.present(self)
        self.name_dialog = dialog

    def choose_picture(self, cid=None, person=None):
        self.picture_for = cid      # None: my profile picture; a chat: that group's picture
        self.contact_picture_for = person      # a person: my own picture of them, only for me
        dialog = Gtk.FileDialog(title="Kontaktbild wählen" if person else "Gruppenbild wählen" if cid else "Profilbild wählen")
        images = Gtk.FileFilter(name="Bilder")
        images.add_mime_type("image/*")
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(images)
        dialog.set_filters(filters)
        dialog.open(self, None, self.on_picture_chosen)

    def on_picture_chosen(self, dialog, result):
        try:
            file = dialog.open_finish(result)
        except GLib.Error:
            return   # cancelled
        if getattr(self, "contact_picture_for", None):
            person, path = self.contact_picture_for, file.get_path()
            self.group_job(lambda a: a.set_contact_picture(person, path))
        elif getattr(self, "picture_for", None):
            cid, path = self.picture_for, file.get_path()
            self.group_job(lambda a: a.set_group_picture(cid, path))
        else:
            self.set_picture(file.get_path())

    def show_picture(self, data, title):
        """A picture big and whole in its own window (Esc or a click closes it)."""
        window = Gtk.Window(title=title, transient_for=self, modal=True, default_width=560, default_height=560)
        picture = Gtk.Picture.new_for_paintable(Gdk.Texture.new_from_bytes(GLib.Bytes.new(data)))
        picture.set_content_fit(Gtk.ContentFit.CONTAIN)
        window.set_child(picture)
        click = Gtk.GestureClick()
        click.connect("released", lambda *_: window.close())
        picture.add_controller(click)
        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", lambda _c, key, *_: key == Gdk.KEY_Escape and (window.close() or True))
        window.add_controller(keys)
        window.present()
        return window

    def set_picture(self, path):
        """Made small and without EXIF, then sent end-to-end into every conversation (account.set_picture)."""
        account = self.account

        def done(_result, error):
            self.show_notice(error)
            self.fill_list()

        self.worker.do(lambda: account.set_picture(path), done)

    def quit_all(self):
        self.stop()
        self.get_application().quit()


class App(Adw.Application):
    def __init__(self, account=None, folder=None, key_store=None, background=False, separate=False):
        super().__init__(application_id=APP_ID)
        self.options = (account, folder, key_store, separate)
        self.background = background
        self.window = None
        opening = Gio.SimpleAction.new("oeffnen", GLib.VariantType.new("s"))
        opening.connect("activate", lambda _a, cid: self.window and self.window.open_chat(cid.get_string()))
        self.add_action(opening)

    def do_activate(self):
        if self.window is None:
            load_css()
            self.window = Window(self, *self.options)
            self.hold()     # stays alive with the window hidden (background); „LiChat beenden“ releases it
            if self.background and self.window.account is not None:
                self.background = False
                return
        self.window.present()


def main(argv=None):
    from . import keys
    argv = list(argv or sys.argv)
    background = "--hintergrund" in argv
    argv = [a for a in argv if a != "--hintergrund"]
    folder = os.environ.get("LICHAT_ORDNER")
    if folder:
        # Michelle: an own LiChat beside Olaf's – no keyring, no shared app id, no autostart entry.
        app = App(folder=Path(folder), key_store=None, background=background, separate=True)
        import hashlib
        app.set_application_id(APP_ID + ".Ordner" + hashlib.sha256(str(Path(folder).resolve()).encode()).hexdigest()[:8])
        return app.run(argv)
    return App(key_store=keys, background=background).run(argv)
