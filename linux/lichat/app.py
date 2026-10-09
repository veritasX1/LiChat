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
import os
import queue
import sys
import threading
import time
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango  # noqa: E402

from .account import Account, LiChatError, short, unb64  # noqa: E402

APP_ID = "io.github.veritasx1.LiChat"
HERE = Path(__file__).resolve().parent
WAIT_SECONDS = 50     # one waiting fetch; the server answers at once when something arrives
AUTOSTART = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "autostart" / f"{APP_ID}.desktop"
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


class Window(Adw.ApplicationWindow):
    def __init__(self, app, account=None, folder=None, key_store=None):
        super().__init__(application=app, title="LiChat", default_width=980, default_height=640)
        self.add_css_class("lichat")
        self.folder = Path(folder or data_folder())
        self.key_store = key_store
        self.worker = Worker()
        self.account = account
        self.current = None        # the chosen conversation (hex id)
        self.composing = False     # „Neue Nachricht“: the An: line instead of a transcript
        self.seen = {}             # chat → number of messages already looked at (the blue dot)
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
        self.f_server = Gtk.Entry(placeholder_text="Server (https://…)", css_classes=["lichat-field"])
        self.f_invite = Gtk.Entry(placeholder_text="Einladung", css_classes=["lichat-field"])
        self.f_name = Gtk.Entry(placeholder_text="Benutzername (a–z, 0–9)", css_classes=["lichat-field"])
        for entry in (self.f_server, self.f_invite, self.f_name):
            box.append(entry)
            entry.connect("activate", lambda *_: self.sign_in())
        self.setup_note = Gtk.Label(css_classes=["lichat-notice"], wrap=True, justify=Gtk.Justification.CENTER,
                                    label=getattr(self, "problem", ""))
        box.append(self.setup_note)
        self.setup_button = Gtk.Button(label="Anmelden", css_classes=["lichat-blue"], halign=Gtk.Align.END)
        self.setup_button.connect("clicked", lambda *_: self.sign_in())
        box.append(self.setup_button)
        page = Gtk.WindowHandle(child=box)
        page.add_css_class("lichat-setup")
        self.set_content(Adw.ToolbarView(content=page, top_bar_style=Adw.ToolbarStyle.FLAT))
        self.get_content().add_top_bar(Adw.HeaderBar(show_title=False, css_classes=["lichat-bar"]))

    def sign_in(self):
        server, invite, name = self.f_server.get_text().strip(), self.f_invite.get_text().strip(), self.f_name.get_text().strip()
        if not (server and invite and name):
            self.setup_note.set_label("Bitte alle drei Felder ausfüllen.")
            return
        self.setup_button.set_sensitive(False)
        self.setup_note.set_label("")
        folder, store = self.folder, self.key_store

        def job():
            key = store.create(folder) if store else None
            return Account.register(folder, server, invite, name, key)

        def done(account, error):
            self.setup_button.set_sensitive(True)
            if error:
                self.setup_note.set_label(error)
                return
            self.account = account
            if self.settings["hintergrund"] and self.key_store is not None:
                autostart(True)
            self.show_main()

        self.worker.do(job, done)

    # ------------------------------------------------------------------------------------------ main window

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
        picture_action = Gio.SimpleAction.new("bild", None)
        picture_action.connect("activate", lambda *_: self.choose_picture())
        self.add_action(picture_action)
        quit_action = Gio.SimpleAction.new("beenden", None)
        quit_action.connect("activate", lambda *_: self.quit_all())
        self.add_action(quit_action)
        self.menu_button = Gtk.MenuButton(icon_name="view-more-symbolic", menu_model=menu, css_classes=["lichat-tool"],
                                          tooltip_text="Einstellungen")
        bar.pack_start(self.menu_button)
        sidebar.append(Gtk.WindowHandle(child=bar))
        self.search = Gtk.SearchEntry(placeholder_text="Suchen", css_classes=["lichat-search"])
        self.search.connect("search-changed", lambda *_: self.fill_list())
        sidebar.append(self.search)
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
        self.to_entry = Gtk.Entry(placeholder_text="Benutzernamen, durch Komma getrennt", hexpand=True, css_classes=["lichat-to-entry"])
        self.to_entry.connect("activate", lambda *_: self.input.grab_focus())
        self.to_line.append(self.to_entry)
        self.pane.append(self.to_line)
        self.to_hairline = Gtk.Box(css_classes=["lichat-hairline"], visible=False)
        self.pane.append(self.to_hairline)
        self.bubbles = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_start=16, margin_end=16,
                               margin_top=8, margin_bottom=8, valign=Gtk.Align.END)
        self.scroller = Gtk.ScrolledWindow(child=self.bubbles, vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER,
                                           css_classes=["lichat-transcript"])
        self.pane.append(self.scroller)
        self.notice = Gtk.Label(css_classes=["lichat-notice"], visible=False, margin_bottom=4)
        self.pane.append(self.notice)
        line = Gtk.Box(spacing=8, margin_start=16, margin_end=16, margin_bottom=12, margin_top=4)
        # ⊕ like Messages on the Mac: a photo, a video, a voice recording or any file (card 9780f3a0).
        attach = Gtk.Button(icon_name="list-add-symbolic", css_classes=["lichat-tool", "lichat-attach"], tooltip_text="Anhang …",
                            valign=Gtk.Align.CENTER)
        attach.connect("clicked", lambda *_: self.choose_attachment())
        line.append(attach)
        self.input = Gtk.Entry(placeholder_text="LiChat", hexpand=True, css_classes=["lichat-input"])
        self.input.connect("activate", lambda *_: self.send())
        line.append(self.input)
        self.input_line = line
        self.pane.append(line)

        split = Gtk.Box()
        split.append(sidebar)
        split.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL, css_classes=["lichat-hairline"]))
        split.append(self.pane)
        self.set_content(split)

        keys = Gtk.ShortcutController()
        keys.add_shortcut(Gtk.Shortcut(trigger=Gtk.ShortcutTrigger.parse_string("<Control>n"),
                                       action=Gtk.CallbackAction.new(lambda *_: self.start_composing() or True)))
        self.add_controller(keys)
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
        return sorted(self.account.chats, key=last, reverse=True)

    @locked
    def fill_list(self):
        wanted = self.search.get_text().strip().lower() if hasattr(self, "search") else ""
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
                          opacity=1 if len(chat["messages"]) > self.seen.get(cid, 0) and cid != self.current else 0)
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
            text.append(Gtk.Label(label=preview, css_classes=["lichat-preview"], xalign=0, wrap=True, lines=2,
                                  ellipsize=Pango.EllipsizeMode.END, max_width_chars=30, natural_wrap_mode=Gtk.NaturalWrapMode.WORD))
            box.append(text)
            row.set_child(box)
            self.list.append(row)
            if cid == self.current:
                self.list.select_row(row)
        self.filling = False

    def on_row(self, listbox, row):
        if getattr(self, "filling", False) or row is None:
            return
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
        title = self.account.title(cid)
        self.title_box.append(Gtk.Label(label=title, css_classes=["lichat-title"]))
        others = [u for u in chat["roster"] if u != self.account.username]
        if len(others) > 1:
            self.title_box.append(Gtk.Label(label=f"{len(others) + 1} Personen", css_classes=["lichat-subtitle"]))
        group = len(others) > 1
        previous = None
        for m in chat["messages"]:
            if m.get("system"):
                self.bubbles.append(Gtk.Label(label=m["text"], css_classes=["lichat-stamp"], wrap=True))
                previous = m      # the next bubble names its sender again, no extra time stamp
                continue
            mine = m["from"] == self.account.username
            if previous is None or m["at"] - previous["at"] > GAP:
                self.bubbles.append(Gtk.Label(label=when_long(m["at"]), css_classes=["lichat-stamp"]))
            if group and not mine and (previous is None or previous["from"] != m["from"]):
                self.bubbles.append(Gtk.Label(label=m["from"], css_classes=["lichat-sender"], xalign=0))
            if m.get("anhang"):
                piece = self.attachment_bubble(m["anhang"], mine)
            else:
                piece = Gtk.Label(label=m["text"], wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, xalign=0, selectable=True,
                                  max_width_chars=48, css_classes=["lichat-bubble", "lichat-mine" if mine else "lichat-theirs"],
                                  halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=2)
            if group and not mine:
                # In a group: the sender's picture next to the bubble, like Messages (card d45b319a).
                row = Gtk.Box(spacing=6, halign=Gtk.Align.START)
                face = avatar(m["from"], 24, self.account.picture(m["from"]))
                face.set_valign(Gtk.Align.END)
                row.append(face)
                row.append(piece)
                piece = row
            self.bubbles.append(piece)
            if mine:
                self.bubbles.append(self.tick_line(cid, m))
            previous = m
        self.seen[cid] = len(chat["messages"])
        if self.is_active() and self.get_visible():
            account = self.account
            self.worker.do(lambda: account.mark_read(cid))
        if chat.get("removed"):
            # Taken out of the group: the history stays readable, nothing more can be written.
            self.bubbles.append(Gtk.Label(label=f"{chat['removed']} hat dich aus dem Gespräch entfernt.", css_classes=["lichat-stamp"]))
            self.input_line.set_visible(False)
            self.info.set_visible(False)
        GLib.idle_add(self.scroll_down)

    def scroll_down(self):
        adj = self.scroller.get_vadjustment()
        adj.set_value(adj.get_upper())
        return False

    @locked
    def fill_info(self, button):
        cid = self.current
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_start=14, margin_end=14, margin_top=12, margin_bottom=12)
        members = self.account.members(cid)
        group = len(members) > 2 or bool(self.account.chats[cid].get("title"))
        top = avatar(self.account.title(cid), 64, self.account.group_picture(cid) if group else None)
        top.set_halign(Gtk.Align.CENTER)
        box.append(top)
        if group and not self.account.chats[cid].get("removed"):
            # Name and picture of the group (card d45b319a) – end to end, never on the server.
            name = Gtk.Entry(text=self.account.chats[cid].get("title", ""), placeholder_text="Gruppenname", css_classes=["lichat-field"])
            name.connect("activate", lambda e: (button.popdown(), self.group_job(lambda a: a.rename(cid, e.get_text()))))
            box.append(name)
            picture = Gtk.Button(label="Gruppenbild wählen …", css_classes=["flat"])
            picture.connect("clicked", lambda *_: (button.popdown(), self.choose_picture(cid)))
            box.append(picture)
        else:
            box.append(Gtk.Label(label=self.account.title(cid), css_classes=["lichat-title"]))
        for name in members:
            row = Gtk.Box(spacing=8)
            row.append(avatar(name, 24, self.account.picture(name)))
            row.append(Gtk.Label(label=name + (" (ich)" if name == self.account.username else ""), xalign=0, hexpand=True))
            # In a group, someone can be taken out (test plan S2); a conversation of two is simply left alone.
            if len(members) > 2 and name != self.account.username:
                out = Gtk.Button(label="Entfernen", css_classes=["flat", "lichat-remove"])
                out.connect("clicked", lambda _b, who=name: (button.popdown(), self.remove_member(cid, who)))
                row.append(out)
            box.append(row)
        if not self.account.chats[cid].get("removed"):
            adding = Gtk.Entry(placeholder_text="Person hinzufügen (Benutzername)", css_classes=["lichat-field"], margin_top=4)
            adding.connect("activate", lambda e: (button.popdown(), self.group_job(
                lambda a: a.add(cid, [n.strip().lower() for n in e.get_text().replace(";", ",").split(",") if n.strip()]))))
            box.append(adding)
            mute = Gtk.CheckButton(label="Mitteilungen stummschalten", active=bool(self.account.chats[cid].get("stumm")))
            mute.connect("toggled", lambda c: self.account.mute(cid, c.get_active()))
            box.append(mute)
            if group:
                leave = Gtk.Button(label="Unterhaltung verlassen", css_classes=["flat", "lichat-remove"], halign=Gtk.Align.START)
                leave.connect("clicked", lambda *_: (button.popdown(), self.group_job(lambda a: a.leave(cid))))
                box.append(leave)
        box.append(Gtk.Label(label="Sicherheitscode", css_classes=["lichat-subtitle"], xalign=0, margin_top=8))
        code = self.account.safety_code(cid).split()
        box.append(Gtk.Label(label=" ".join(code[:4]) + "\n" + " ".join(code[4:]), css_classes=["lichat-code"], selectable=True))
        box.append(Gtk.Label(label="Vergleicht den Code auf allen Geräten – gleich heißt: niemand liest mit.", wrap=True,
                             max_width_chars=34, css_classes=["lichat-subtitle"], xalign=0))
        button.set_popover(Gtk.Popover(child=box))

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
        else:
            cid = self.current

            def job():
                account.send(cid, text)
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
            if item.get("text") is None or item.get("system") or item["from"] == self.account.username:
                continue
            if self.is_active() and self.get_visible() and item["chat"] == self.current:
                continue
            if self.account.chats.get(item["chat"], {}).get("stumm"):
                continue     # muted (card d45b319a)
            self.notify(item)

    def notify(self, item):
        """A GNOME notification. By default only „Neue Nachricht“ (Michelle S4) – sender and text only when switched on."""
        note = Gio.Notification.new("LiChat")
        note.set_body(f"{item['from']}: {item['text']}" if self.settings["vorschau"] else "Neue Nachricht")
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
        if action.get_name() == "hintergrund" and self.key_store is not None:   # a LICHAT_ORDNER LiChat never touches autostart
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

        self.worker.do(lambda: account.retry(cid, mid), done)

    # ------------------------------------------------------------------------------------------ attachments

    def attachment_bubble(self, info, mine):
        """A photo or video as its small picture, a voice message with ▶ and its length, a file with its name."""
        kind = info.get("art")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=2)
        button = Gtk.Button(css_classes=["lichat-attachment", "flat"], tooltip_text=info.get("name"))
        if kind in ("bild", "video") and info.get("vorschau"):
            try:
                texture = Gdk.Texture.new_from_bytes(GLib.Bytes.new(unb64(info["vorschau"])))
                picture = Gtk.Picture(paintable=texture, can_shrink=True, content_fit=Gtk.ContentFit.COVER)
                width, height = texture.get_width(), texture.get_height()
                scale = min(240 / max(width, 1), 240 / max(height, 1))
                picture.set_size_request(int(width * scale), int(height * scale))
                frame = Gtk.Overlay(child=picture, css_classes=["lichat-photo"], overflow=Gtk.Overflow.HIDDEN)
                if kind == "video":
                    frame.add_overlay(Gtk.Label(label="▶", css_classes=["lichat-play"], halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER))
                button.set_child(frame)
            except GLib.Error:
                kind = "datei"
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
        return box

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
        dialog.open(self, None, self.on_attachment_chosen)

    def on_attachment_chosen(self, dialog, result):
        try:
            file = dialog.open_finish(result)
        except GLib.Error:
            return
        self.send_attachment(Path(file.get_path()))

    def send_attachment(self, path):
        """The kind from the file's type: photo, video, audio (as a voice message) or any other file."""
        import mimetypes
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        kind = "bild" if mime.startswith("image/") and mime != "image/svg+xml" else "video" if mime.startswith("video/") \
            else "sprache" if mime.startswith("audio/") else "datei"
        account, cid = self.account, self.current
        if cid is None:
            self.show_notice("Erst das Gespräch beginnen, dann den Anhang schicken.")
            return

        def job():
            account.send_attachment(cid, path.read_bytes(), path.name, mime, kind)
            return cid

        def done(_cid, error):
            self.show_notice(error)
            self.fill_list()
            self.show_conversation()

        self.worker.do(job, done)

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

    def choose_picture(self, cid=None):
        self.picture_for = cid      # None: my profile picture; a chat: that group's picture
        dialog = Gtk.FileDialog(title="Gruppenbild wählen" if cid else "Profilbild wählen")
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
        if getattr(self, "picture_for", None):
            cid, path = self.picture_for, file.get_path()
            self.group_job(lambda a: a.set_group_picture(cid, path))
        else:
            self.set_picture(file.get_path())

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
    def __init__(self, account=None, folder=None, key_store=None, background=False):
        super().__init__(application_id=APP_ID)
        self.options = (account, folder, key_store)
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
        app = App(folder=Path(folder), key_store=None, background=background)
        import hashlib
        app.set_application_id(APP_ID + ".Ordner" + hashlib.sha256(str(Path(folder).resolve()).encode()).hexdigest()[:8])
        return app.run(argv)
    return App(key_store=keys, background=background).run(argv)
