"""LiChat for Ubuntu – end-to-end encrypted chats in the look of Messages on the Mac (macOS 15).

    python3 main.py            the window (first start: sign in with an invitation)

The account (account.py) is only touched by one worker thread: fetching, sending and starting run there one after the
other; the window gets the results through GLib.idle_add. The mailbox is fetched every few seconds while LiChat runs.
"""

import os
import queue
import threading
import time
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, GLib, Gtk, Pango  # noqa: E402

from .account import Account, LiChatError, short  # noqa: E402

APP_ID = "io.github.veritasx1.LiChat"
HERE = Path(__file__).resolve().parent
POLL_SECONDS = 4
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


def avatar(name, size):
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
        self.busy = False
        manager = Adw.StyleManager.get_default()
        manager.connect("notify::dark", lambda *_: self.follow_appearance())
        self.follow_appearance()
        if self.account is None:
            self.account = self.open_existing()
        if self.account is None:
            self.show_setup()
        else:
            self.show_main()

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
        self.fetch()
        GLib.timeout_add_seconds(POLL_SECONDS, self.poll)

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
            texts = " ".join(m["text"] for m in chat["messages"]).lower()
            if wanted and wanted not in title.lower() and wanted not in texts:
                continue
            last = chat["messages"][-1] if chat["messages"] else None
            row = Gtk.ListBoxRow()
            row.cid = cid
            box = Gtk.Box(spacing=10, margin_start=8, margin_end=10, margin_top=8, margin_bottom=8)
            dot = Gtk.Box(css_classes=["lichat-unread"], valign=Gtk.Align.CENTER,
                          opacity=1 if len(chat["messages"]) > self.seen.get(cid, 0) and cid != self.current else 0)
            box.append(dot)
            box.append(avatar(title, 40))
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True, spacing=1)
            head = Gtk.Box()
            head.append(Gtk.Label(label=title, css_classes=["lichat-name"], xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END))
            if last:
                head.append(Gtk.Label(label=when_short(last["at"]), css_classes=["lichat-time"]))
            text.append(head)
            preview = (("Du: " if last["from"] == self.account.username else "") + last["text"]) if last else "Noch keine Nachrichten"
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
            mine = m["from"] == self.account.username
            if previous is None or m["at"] - previous["at"] > GAP:
                self.bubbles.append(Gtk.Label(label=when_long(m["at"]), css_classes=["lichat-stamp"]))
            if group and not mine and (previous is None or previous["from"] != m["from"]):
                self.bubbles.append(Gtk.Label(label=m["from"], css_classes=["lichat-sender"], xalign=0))
            bubble = Gtk.Label(label=m["text"], wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, xalign=0, selectable=True,
                               max_width_chars=48, css_classes=["lichat-bubble", "lichat-mine" if mine else "lichat-theirs"],
                               halign=Gtk.Align.END if mine else Gtk.Align.START, margin_top=2)
            self.bubbles.append(bubble)
            previous = m
        self.seen[cid] = len(chat["messages"])
        GLib.idle_add(self.scroll_down)

    def scroll_down(self):
        adj = self.scroller.get_vadjustment()
        adj.set_value(adj.get_upper())
        return False

    @locked
    def fill_info(self, button):
        cid = self.current
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_start=14, margin_end=14, margin_top=12, margin_bottom=12)
        box.append(Gtk.Label(label=self.account.title(cid), css_classes=["lichat-title"]))
        for name in self.account.members(cid):
            row = Gtk.Box(spacing=8)
            row.append(avatar(name, 24))
            row.append(Gtk.Label(label=name + (" (ich)" if name == self.account.username else ""), xalign=0))
            box.append(row)
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
                return
            self.input.set_text("")
            self.composing = False
            self.current = cid
            self.fill_list()
            self.show_conversation()

        self.worker.do(job, done)

    def fetch(self):
        if self.busy:
            return
        self.busy = True
        self.worker.do(self.account.fetch, self.fetched)

    def fetched(self, news, error):
        self.busy = False
        if error:
            self.show_notice(error)
            return
        self.show_notice("")
        if news:
            self.fill_list()
            if self.current and any(n.get("chat") == self.current for n in news):
                self.show_conversation()

    def poll(self):
        self.fetch()
        return True


class App(Adw.Application):
    def __init__(self, account=None, folder=None, key_store=None):
        super().__init__(application_id=APP_ID)
        self.options = (account, folder, key_store)

    def do_activate(self):
        load_css()
        window = self.get_active_window() or Window(self, *self.options)
        window.present()


def main(argv=None):
    from . import keys
    return App(key_store=keys).run(argv)
