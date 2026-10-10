"""The real window against a real server in this process, with invented people (isolated folders, no keyring):
first start, the list, a conversation both ways, a group, „Neue Nachricht“, dark. Pictures with --shots <folder>.

    python3 tests/test_gui.py [--shots DIR]
"""

import os
import sys
import tempfile
import threading
import time
from pathlib import Path
from socketserver import ThreadingMixIn
from wsgiref.simple_server import WSGIRequestHandler, WSGIServer, make_server

SCRATCH = tempfile.mkdtemp(prefix="lichat-gui-")
os.environ["LICHAT_MIKROFON"] = "audiotestsrc is-live=true wave=sine volume=0.3"     # a test tone, never the real microphone
os.environ["XDG_DATA_HOME"] = os.environ["XDG_CONFIG_HOME"] = os.environ["XDG_CACHE_HOME"] = SCRATCH
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "linux"))
sys.path.insert(0, str(ROOT / "server"))

import gi  # noqa: E402

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Graphene", "1.0")
from gi.repository import Adw, GLib, Graphene, Gtk  # noqa: E402

from lichat import app as app_module  # noqa: E402
from lichat.account import Account  # noqa: E402
from lichat_server.app import create_app  # noqa: E402

SHOTS = Path(sys.argv[sys.argv.index("--shots") + 1]) if "--shots" in sys.argv else None


class Threading(ThreadingMixIn, WSGIServer):
    daemon_threads = True   # a waiting fetch must not hold up the others


class Quiet(WSGIRequestHandler):
    def log_message(self, *args):
        pass


def settle(seconds=0.3):
    context = GLib.MainContext.default()
    end = time.time() + seconds
    while time.time() < end:
        while context.pending():
            context.iteration(False)
        time.sleep(0.01)


def until(check, seconds=10):
    end = time.time() + seconds
    while time.time() < end:
        settle(0.1)
        if check():
            return True
    raise AssertionError("nicht eingetreten")


def shot(widget, name):
    if SHOTS is None:
        return
    SHOTS.mkdir(parents=True, exist_ok=True)
    for _attempt in range(10):
        settle(0.2)
        width, height = widget.get_width(), widget.get_height()
        snapshot = Gtk.Snapshot()
        Gtk.WidgetPaintable.new(widget).snapshot(snapshot, width, height)
        node = snapshot.to_node()
        if node is not None:
            widget.get_native().get_renderer().render_texture(node, Graphene.Rect().init(0, 0, width, height)).save_to_png(str(SHOTS / name))
            return


def walk(widget):
    yield widget
    child = widget.get_first_child()
    while child is not None:
        yield from walk(child)
        child = child.get_next_sibling()


def texts(widget):
    return [w.get_label() for w in walk(widget) if isinstance(w, Gtk.Label)]


def main():
    server = create_app(str(Path(SCRATCH) / "server"))
    httpd = make_server("127.0.0.1", 0, server, handler_class=Quiet, server_class=Threading)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{httpd.server_port}"
    people = Path(SCRATCH) / "andere"
    bernd = Account.register(people / "bernd", url, server.store.create_invite(), "bernd")
    erna = Account.register(people / "erna", url, server.store.create_invite(), "erna")

    Adw.init()
    app_module.load_css()
    app = Adw.Application(application_id="io.github.veritasx1.LiChat.Test")
    app.register(None)

    # First start: the sign-in page; signing in with an invitation.
    window = app_module.Window(app, folder=Path(SCRATCH) / "anna")
    window.present()
    settle(0.5)
    assert "Bei LiChat anmelden" in texts(window)
    shot(window, "01-anmelden.png")
    window.f_server.set_text(url)
    window.f_invite.set_text(server.store.create_invite())
    window.f_first.set_text("Anna")
    window.f_last.set_text("Beispiel")
    assert window.f_name.get_text() == "anna.beispiel", window.f_name.get_text()   # suggested from the name
    window.f_name.set_text("anna")
    window.sign_in()
    until(lambda: getattr(window, "list", None) is not None)
    assert "Keine Konversation ausgewählt" in texts(window)
    # First start: what „im Hintergrund“ means, once, with a way to switch it off (Michelle Z6/Z7).
    until(lambda: getattr(window, "explained", None) is not None)
    window.explained.emit("response", "an")
    window.explained.force_close()
    assert window.settings["erklaert"] and window.settings["hintergrund"]
    anna = window.account

    assert anna.display("anna") == "Anna Beispiel"

    # Bernd writes to Anna; the list shows it with the blue dot.
    cid = bernd.start(["anna"])
    sent = time.monotonic()
    bernd.send(cid, "Hallo Anna! Kommst du Samstag zum Grillen?")
    until(lambda: cid in anna.chats and anna.chats[cid]["messages"])
    assert time.monotonic() - sent < 3, "nicht sofort – die stehende Verbindung wartet nicht"
    settle(0.3)
    assert "bernd" in texts(window.list)
    # Not the open chat: a notification – by default without sender and text (Michelle S4).
    until(lambda: getattr(window, "last_notification", None) is not None)
    assert window.settings["vorschau"] is False
    shot(window, "02-liste-neu.png")

    bernd.fetch()
    assert bernd.display("anna") == "Anna Beispiel", "Anzeigename Ende-zu-Ende bei Bernd"

    # Open it, answer – the blue bubble, Bernd reads it.
    window.list.select_row(window.list.get_row_at_index(0))
    settle(0.2)
    shot(window, "02b-gewaehlt.png")   # the chosen row keeps the grey circle with „B“ (Michelle 73bbcdfc)
    window.input.set_text("Ja, gern! Ich bringe Kartoffelsalat mit.")
    window.send()
    # Olaf 10.10.: straight on writing – the field is empty and open at once, nothing waits for the server
    assert window.input.get_text() == "" and window.input.get_sensitive()
    until(lambda: len(anna.chats[cid]["messages"]) == 2)
    assert [n["text"] for n in bernd.fetch() if "text" in n] == ["Ja, gern! Ich bringe Kartoffelsalat mit."]
    bernd.send(cid, "Super, bis dann 🙂")
    until(lambda: len(anna.chats[cid]["messages"]) == 3)
    settle(0.3)
    assert "Super, bis dann 🙂" in texts(window.bubbles)
    shot(window, "03-gespraech.png")

    # Bernd's profile picture arrives end-to-end and shows in the list (our own test picture, never a real photo).
    from PIL import Image
    Image.new("RGB", (600, 600), (230, 140, 40)).save(Path(SCRATCH) / "bernd.jpg")
    bernd.set_picture(Path(SCRATCH) / "bernd.jpg")
    until(lambda: anna.picture("bernd") is not None)
    settle(0.3)
    assert any(w.has_css_class("lichat-avatar-picture") for w in walk(window.list))


    # Attachments (card 9780f3a0): Bernd's photo shows as a picture; Anna sends a file, it opens from a private folder.
    from PIL import Image as PilImage
    PilImage.new("RGB", (1600, 1200), (60, 160, 90)).save(Path(SCRATCH) / "garten.jpg")
    bernd.send_attachment(cid, (Path(SCRATCH) / "garten.jpg").read_bytes(), "garten.jpg", "image/jpeg", "bild")
    until(lambda: any(m.get("anhang") for m in anna.chats[cid]["messages"]))
    settle(0.3)
    assert any(w.has_css_class("lichat-photo") for w in walk(window.bubbles)), "Foto nicht als Bild"
    (Path(SCRATCH) / "Einkauf.txt").write_text("Kohle, Würstchen")
    window.send_attachment(Path(SCRATCH) / "Einkauf.txt")
    until(lambda: sum(1 for m in anna.chats[cid]["messages"] if m.get("anhang")) == 2)
    got = [n for n in bernd.fetch() if n.get("anhang")]
    assert got and bernd.open_attachment(got[-1]["anhang"]) == "Kohle, Würstchen".encode()
    launched = []
    Gtk.FileLauncher.launch = lambda self, parent, cancel, callback: launched.append(self.get_file().get_path())
    window.open_attachment(got[-1]["anhang"] | {"id": [m for m in anna.chats[cid]["messages"] if m.get("anhang")][-1]["anhang"]["id"]})
    until(lambda: launched)
    opened = Path(launched[0])
    assert opened.read_text() == "Kohle, Würstchen" and oct(opened.stat().st_mode & 0o777) == "0o600"
    assert str(opened).startswith(os.environ.get("XDG_RUNTIME_DIR", "/tmp")), opened
    # ed0686a1: the photo itself replaces the small preview in the bubble (sharp), loaded once
    until(lambda: any(t.get_width() > 320 for t in window.full_images.values()))
    shot(window, "03b-anhaenge.png")

    # Ticks (card db70d358): my last message is delivered once Bernd fetched it – two grey ticks under the bubble.
    until(lambda: anna.tick(cid, [m for m in anna.chats[cid]["messages"] if m["from"] == "anna"][-1])[0] == "zugestellt")
    window.show_conversation()
    settle(0.3)
    marks = [w for w in walk(window.bubbles) if isinstance(w, Gtk.DrawingArea)]
    assert marks and all(w.get_parent().get_tooltip_text().startswith(("Zugestellt", "Gesendet")) for w in marks), \
        [w.get_parent().get_tooltip_text() for w in marks]
    shot(window, "03c-haken.png")

    # Replies and reactions (card a7d9d987): Bernd answers Anna's message and reacts; Anna answers back from the menu.
    asked = [m for m in bernd.chats[cid]["messages"] if m.get("text") == "Ja, gern! Ich bringe Kartoffelsalat mit."][0]
    bernd.send(cid, "Prima!", reply_to=asked["mid"])
    bernd.react(cid, asked["mid"], "❤️")
    until(lambda: any(m.get("antwort") for m in anna.chats[cid]["messages"]))
    until(lambda: any(m.get("reaktionen") for m in anna.chats[cid]["messages"]))
    window.show_conversation()
    settle(0.3)
    assert any("↩ Du: Ja, gern!" in (t or "") for t in texts(window.bubbles))
    assert "❤️" in texts(window.bubbles)
    prima = [m for m in anna.chats[cid]["messages"] if m.get("text") == "Prima!"][0]
    window.start_reply(prima)
    window.input.set_text("Freut mich")
    window.send()
    until(lambda: any(m.get("text") == "Freut mich" for m in anna.chats[cid]["messages"]))
    assert [m for m in anna.chats[cid]["messages"] if m.get("text") == "Freut mich"][0]["antwort"]["mid"] == prima["mid"]
    assert not window.reply_line.get_visible()
    shot(window, "03d-antwort-reaktion.png")

    # „+“ (a7d9d987): the message menu offers GTK's emoji chooser; a picked emoji is my reaction.
    window.message_menu(cid, prima, window.bubbles.get_last_child())
    settle(0.2)
    chooser = next(w for w in walk(window.open_menu) if isinstance(w, Gtk.MenuButton)).get_popover()
    assert isinstance(chooser, Gtk.EmojiChooser)
    chooser.emit("emoji-picked", "🦄")
    until(lambda: any((m.get("reaktionen") or {}).get("anna") == "🦄" for m in anna.chats[cid]["messages"]))

    # Bearbeiten / Senden widerrufen (4ceca163): from the menu, the input line takes the text; „Bearbeitet“ shows up.
    anna.send(cid, "Grillen um 6")
    window.show_conversation()
    mine_now = [m for m in anna.chats[cid]["messages"] if m.get("text") == "Grillen um 6"][0]
    window.start_edit(mine_now)
    assert window.input.get_text() == "Grillen um 6" and "bearbeiten" in window.reply_label.get_label()
    window.input.set_text("Grillen um 7")
    window.send()
    until(lambda: mine_now["mid"] in [m.get("mid") for m in anna.chats[cid]["messages"] if m.get("text") == "Grillen um 7"])
    window.show_conversation()
    settle(0.2)
    assert "Bearbeitet" in [w.get_label() for w in walk(window.bubbles) if isinstance(w, Gtk.Button)]
    anna.send(cid, "Ups")
    window.unsend(cid, anna.chats[cid]["messages"][-1]["mid"])
    until(lambda: anna.chats[cid]["messages"][-1].get("zurueckgerufen"))
    window.show_conversation()
    settle(0.2)
    assert "Du hast eine Nachricht widerrufen." in texts(window.bubbles)
    shot(window, "03e-bearbeitet.png")

    # 18cfe3d2 / 22c918ce: a wrapped bubble no wider than its longest line; links as <a>
    bernd.send(cid, "für Gruppen wie du\nvorgeschlagen hast")
    bernd.send(cid, "Mehr auf www.lisoftware.de oder olaf@example.org")
    until(lambda: any("www.lisoftware.de" in (m.get("text") or "") for m in anna.chats[cid]["messages"]))
    window.show_conversation()
    settle(0.3)
    wrapped = next(w for w in walk(window.bubbles) if isinstance(w, Gtk.Label) and w.get_text() == "für Gruppen wie du\nvorgeschlagen hast")
    assert wrapped.get_width() < 260, wrapped.get_width()
    linked = next(w for w in walk(window.bubbles) if isinstance(w, Gtk.Label) and "lisoftware" in w.get_text())
    assert '<a href="https://www.lisoftware.de">' in linked.get_label() and '<a href="mailto:olaf@example.org">' in linked.get_label()
    shot(window, "03f-links.png")

    # 864041bf: a voice message plays in the bubble – ⏸ while playing, pause keeps the place, a click on the wave jumps
    import subprocess as _sp
    tone = Path(SCRATCH) / "ton.m4a"
    _sp.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=4", "-c:a", "aac", str(tone)], check=True)
    bernd.send_attachment(cid, tone.read_bytes(), "Sprachnachricht.m4a", "audio/mp4", "sprache", duration=4.0)
    until(lambda: any((m.get("anhang") or {}).get("art") == "sprache" and (m.get("anhang") or {}).get("dauer") == 4.0
                      for m in anna.chats[cid]["messages"]))
    window.show_conversation()
    settle(0.3)
    toggle = [w for w in walk(window.bubbles) if isinstance(w, Gtk.Button) and w.has_css_class("lichat-voice-button")][-1]
    toggle.emit("clicked")
    until(lambda: getattr(window, "voice", None) is not None and window.voice["media"].is_prepared(), seconds=10)
    window.voice["media"].set_muted(True)
    until(lambda: window.voice["media"].get_timestamp() > 300_000, seconds=10)
    assert toggle.get_label() == "⏸"
    toggle.emit("clicked")
    settle(0.3)
    held = window.voice["media"].get_timestamp()
    settle(0.5)
    assert toggle.get_label() == "▶" and window.voice["media"].get_timestamp() == held, "Pause hält die Stelle"
    shot(window, "03g-sprachnachricht.png")
    toggle.emit("clicked")
    settle(0.3)
    assert window.voice["media"].get_timestamp() >= held, "weiter von der Stelle"
    window.voice["media"].set_playing(False)

    # „… tippt“ (card 884f7c8a): off by default; with both switched on Bernd's typing shows as a grey „•••“ bubble.
    assert not window.lookup_action("tippanzeige").get_state().get_boolean()
    window.lookup_action("tippanzeige").change_state(GLib.Variant.new_boolean(True))
    bernd.set_typing_indicator(True)
    until(lambda: anna.typing_indicator)
    assert bernd.typing(cid)
    until(lambda: any(isinstance(w, Gtk.DrawingArea) and "tippt" in (w.get_tooltip_text() or w.get_name() or "")
                      for w in walk(window.bubbles)) or window.account.typing_now(cid))
    until(lambda: bool(window.account.typing_now(cid)))
    settle(0.3)
    # The newest bubble is wholly in view, not under the input line (card 0c01f599).
    ok, bounds = window.bubbles.get_last_child().compute_bounds(window.scroller)
    assert ok and bounds.get_y() + bounds.get_height() <= window.scroller.get_height(), (bounds.get_y(), bounds.get_height(), window.scroller.get_height())
    shot(window, "03e-tippt.png")
    # … and in the left column the blue wave with „schreibt …“ instead of the preview (Olaf zu 884f7c8a)
    assert "schreibt …" in " ".join(texts(window.list)), texts(window.list)

    # Reading further up, a new message does not pull the view away: „↓ Neue Nachricht“ appears, a click goes to the end.
    window.scroller.get_vadjustment().set_value(0)
    settle(0.2)
    assert not window.stick
    bernd.send(cid, "Noch was: Grillkohle?")
    until(lambda: window.new_pill.get_visible())
    settle(0.3)
    assert window.scroller.get_vadjustment().get_value() < 50, "bleibt oben"
    shot(window, "03f-neue-nachricht.png")
    window.new_pill.emit("clicked")
    settle(0.3)
    adjustment = window.scroller.get_vadjustment()
    assert window.stick and not window.new_pill.get_visible()
    assert adjustment.get_value() >= adjustment.get_upper() - adjustment.get_page_size() - 1

    # The safety code in the info popover equals Bernd's.
    window.fill_info(window.info)
    window.info.popup()
    settle(0.3)
    assert bernd.safety_code(cid).split()[0] in " ".join(texts(window.info.get_popover()))
    shot(window, "04-info-code.png")
    window.info.popdown()

    # Olaf zu 8534fe11: my own picture of Bernd, only for me – shown big on a click, „Zurücksetzen“ removes it again.
    from PIL import Image
    own = Path(tempfile.mkdtemp()) / "kontakt.png"
    Image.new("RGB", (300, 200), (30, 90, 200)).save(own)
    window.account.set_contact_picture("bernd", own)
    window.fill_info(window.info)
    labels = [w.get_label() for w in walk(window.info.get_popover()) if isinstance(w, Gtk.Button)]
    assert "Bild ändern" in labels and "Zurücksetzen" in labels, labels
    big = window.show_picture(window.account.picture("bernd"), "bernd")
    settle(0.3)
    assert isinstance(big.get_child(), Gtk.Picture)
    big.close()
    window.account.set_contact_picture("bernd", None)
    window.fill_info(window.info)
    labels = [w.get_label() for w in walk(window.info.get_popover()) if isinstance(w, Gtk.Button)]
    assert "Zurücksetzen" not in labels and "Bild ändern" in labels

    # fe9f2029 / f9e3f055 / 43358d35: a photo asks how; one-time only on the phone, secret pixelated until tapped, the overview.
    photo = Path(tempfile.mkdtemp()) / "foto.png"
    Image.new("RGB", (640, 480), (200, 60, 60)).save(photo)
    window.send_attachment(photo)
    settle(0.3)
    dialog, once, secret = window.photo_dialog
    secret.set_active(True)
    dialog.emit("response", "senden")
    dialog.force_close()
    until(lambda: any((m.get("anhang") or {}).get("geheim") for m in window.account.chats[cid]["messages"]))
    bernd.send_attachment(cid, photo.read_bytes(), "einmal.png", "image/png", "bild", once=True)
    until(lambda: any((m.get("anhang") or {}).get("einmal") for m in window.account.chats[cid]["messages"]))
    settle(0.5)
    shown = " ".join(texts(window.bubbles))
    assert "Nur auf dem Handy ansehen" in shown, shown
    assert "Geheimbild" in shown, shown
    shot(window, "03g-einmal-geheim.png")
    media = window.show_media(cid)
    settle(0.3)
    shot(media, "04b-medien.png")
    media.close()

    # „Neue Nachricht“ to a group: An: bernd, erna.
    window.start_composing()
    window.to_entry.set_text("bernd, erna")
    window.input.set_text("Wer bringt Getränke mit?")
    Image.new("RGB", (800, 500), (220, 90, 120)).save(Path(SCRATCH) / "anna.jpg")
    window.set_picture(str(Path(SCRATCH) / "anna.jpg"))
    until(lambda: anna.picture("anna") is not None)
    shot(window, "05-neue-nachricht.png")
    window.send()
    until(lambda: len(anna.chats) == 2)
    group = window.current
    erna.fetch()
    erna.send(group, "Ich nehme Apfelschorle mit")
    bernd.fetch()
    until(lambda: len(anna.chats[group]["messages"]) == 2)
    settle(0.3)
    assert "3 Personen" in texts(window.title_box)
    assert "erna" in texts(window.bubbles)          # the sender's name over the bubble in a group
    shot(window, "06-gruppe.png")

    # @Erwähnungen (d54175ed): „@er“ suggests erna, a click writes „@erna “; the bubble shows it bold; erna's notice says so.
    window.input.set_text("Super, @er")
    settle(0.1)
    assert window.suggestions.get_visible()
    next(w for w in walk(window.suggestions) if isinstance(w, Gtk.Button)).emit("clicked")
    assert window.input.get_text() == "Super, @erna "
    window.input.set_text(window.input.get_text() + "danke!")
    window.send()
    until(lambda: any("@erna" in (m.get("text") or "") for m in anna.chats[group]["messages"]))
    assert [n for n in erna.fetch() if n.get("text") == "Super, @erna danke!"][0].get("mention") is True
    window.show_conversation()
    settle(0.2)
    assert any("<b>@erna</b>" in (w.get_label() or "") for w in walk(window.bubbles) if isinstance(w, Gtk.Label))

    # Group life (card d45b319a): rename and picture end to end, the info popover offers add/mute/leave.
    window.current = group
    window.group_job(lambda acc: acc.rename(group, "Grillabend"))
    until(lambda: anna.chats[group]["title"] == "Grillabend")
    bernd.fetch()
    assert bernd.title(group) == "Grillabend"
    window.fill_info(window.info)
    labels = " ".join(w.get_label() or "" for w in walk(window.info.get_popover()) if isinstance(w, (Gtk.Label, Gtk.Button, Gtk.CheckButton)))
    assert "Unterhaltung verlassen" in labels and "Nur bei Erwähnung benachrichtigen" in labels, labels
    assert any(w.has_css_class("lichat-avatar") for w in walk(window.bubbles)), "Absenderbild in der Gruppe"

    # Take Erna out of the group (test plan S2): her side shows it, the input is gone.
    window.current = group
    window.show_conversation()
    window.remove_member(group, "erna")
    until(lambda: "erna" not in anna.chats[group]["roster"])
    assert "anna" in [n.get("removed") for n in erna.fetch()]
    window.fill_info(window.info)
    assert "erna" not in texts(window.info.get_popover()), texts(window.info.get_popover())
    assert "Du hast erna aus der Unterhaltung entfernt." in texts(window.bubbles)

    # Mac points (Michelle 09.10.): pinned on top, filters, unread pill, per-chat receipts, delete + „Zuletzt gelöscht“,
    # shortcuts with Strg.
    anna.pin(cid, True)
    window.fill_list()
    settle(0.2)
    assert window.pins.get_visible() and "bernd" in texts(window.pins)
    shot(window, "09-angeheftet.png")
    window.open_number(1)
    settle(0.2)
    assert window.current == cid
    window.open_number(2)
    settle(0.2)
    assert window.current == group
    window.next_chat()
    settle(0.2)
    assert window.current == cid
    window.lookup_action("filter").change_state(GLib.Variant.new_string("ungelesen"))
    settle(0.2)
    assert window.filter_label.get_visible() and window.filter_label.get_label() == "Ungelesen"
    window.lookup_action("filter").change_state(GLib.Variant.new_string("alle"))
    bernd.send(cid, "Neu eins"); bernd.send(cid, "Neu zwei")
    window.open_chat(group)
    until(lambda: anna.unread(cid) == 2)
    window.open_chat(cid)
    settle(0.3)
    assert "2 ungelesene" in window.first_pill.get_label()
    assert not window.first_pill.get_visible(), "both unread are in view – no pill (like the Mac)"
    window.to_first_unread()
    victim = [m for m in anna.chats[cid]["messages"] if m.get("text") == "Neu eins"][0]
    anna.delete_message(cid, victim["mid"])
    window.show_conversation()
    settle(0.2)
    assert "Neu eins" not in texts(window.bubbles)
    window.lookup_action("filter").change_state(GLib.Variant.new_string("geloescht"))
    settle(0.2)
    assert window.list.get_row_at_index(0) is not None
    anna.restore(cid)
    window.lookup_action("filter").change_state(GLib.Variant.new_string("alle"))
    window.fill_info(window.info)
    assert "Lesebestätigungen senden" in texts(window.info.get_popover())
    assert any("blockieren" in (t or "") for t in texts(window.info.get_popover()))
    anna.pin(cid, False)

    # A second device (da5cbfcb): a new window shows a code, Anna's „Meine Geräte“ accepts it, the new one is Anna too.
    second = app_module.Window(app, folder=Path(SCRATCH) / "anna-zwei")
    second.present()
    settle(0.3)
    second.show_link_setup()
    settle(0.2)
    entry = next(w for w in walk(second.get_content()) if isinstance(w, Gtk.Entry))
    entry.set_text(url)
    next(w for w in walk(second.get_content()) if isinstance(w, Gtk.Button) and w.get_label() == "Code anzeigen").emit("clicked")
    until(lambda: second.link_code.get_visible())
    shot(second, "08-koppeln.png")
    window.show_devices()
    settle(0.3)
    dialog_entries = [w for w in walk(window.devices_dialog) if isinstance(w, Gtk.Entry)]
    dialog_entries[0].set_text(second.link_code.get_label())
    next(w for w in walk(window.devices_dialog) if isinstance(w, Gtk.Button) and w.get_label() == "Gerät hinzufügen").emit("clicked")
    until(lambda: second.account is not None and second.account.username == "anna", seconds=30)
    until(lambda: len(anna.chats[cid]["roster"]["anna"]) == 2, seconds=30)
    assert any("Dieses Gerät" in (t or "") for t in texts(window.devices_dialog))
    shot(window, "08b-geraete.png")
    window.devices_dialog.close()
    second.close()

    # Search in the list.
    window.search.set_text("Kartoffel")
    settle(0.5)
    assert len(list(walk(window.list))) and window.list.get_row_at_index(1) is None
    window.search.set_text("")
    settle(0.5)

    # Dark.
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
    settle(0.3)
    assert window.has_css_class("lichat-dark")
    window.list.select_row(window.list.get_row_at_index(1))
    settle(0.3)
    shot(window, "07-dunkel.png")

    # Closing the window keeps LiChat listening in the background.
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.DEFAULT)
    window.close()
    settle(0.3)
    assert not window.get_visible() and window.listener.running
    bernd.send(cid, "Noch wach?")
    until(lambda: anna.chats[cid]["messages"][-1]["text"] == "Noch wach?")
    window.open_chat(cid)
    settle(0.3)
    assert window.get_visible() and "Noch wach?" in texts(window.bubbles)

    # Autostart can be switched off and on again (Michelle Z7).
    window.activate_action("win.hintergrund", None)
    settle(0.2)
    assert window.settings["hintergrund"] is False and not app_module.AUTOSTART.exists()
    window.activate_action("win.hintergrund", None)
    settle(0.2)
    assert app_module.AUTOSTART.exists() and "--hintergrund" in app_module.AUTOSTART.read_text()

    # After a restart the conversations are there again (sealed on disk).
    again = Account(window.folder)
    assert set(again.visible_chats()) == {cid, group} and again.own_group_id() and len(again.chats[cid]["messages"]) == 18  # + Geheim- und Einmal-Bild, „Noch was: Grillkohle?“, Grillen um 7, widerrufen, Neu eins/zwei

    # f0-07: two fingers to the left show every time, letting go hides them; to the right on a bubble answers it.
    window.open_chat(cid)
    settle(0.3)
    assert window.peek_times and not any(t.get_visible() for t in window.peek_times)
    window.peek_scrolled(window.peek_swipe, 30.0, 0.0)
    assert all(t.get_visible() for t in window.peek_times)
    shot(window, "08b-uhrzeiten.png")
    window.peek(False)
    assert not any(t.get_visible() for t in window.peek_times)
    window.peek_scrolled(window.peek_swipe, -30.0, 0.0)          # to the right: not the times
    assert not any(t.get_visible() for t in window.peek_times)
    last = [m for m in anna.chats[cid]["messages"] if m.get("mid") and not m.get("system") and not m.get("zurueckgerufen")][-1]
    answer = [c for c in window.bubble_of[last["mid"]].observe_controllers() if isinstance(c, Gtk.EventControllerScroll)][0]
    window.answer_scrolled(answer, -40.0, last)
    window.answer_scrolled(answer, -40.0, last)
    window.answer_scrolled(answer, None, last)
    assert window.reply_to is last and window.reply_line.get_visible()
    window.end_reply()

    # f0-01: ⊕ opens the menu like Messages – Fotos, Audio, Dateien with round coloured symbols.
    menu = window.attachment_menu(window.attach_button)
    settle(0.3)
    labels = []
    child = menu.get_child().get_first_child()
    while child is not None:
        labels.append(child.get_child().get_last_child().get_label())
        child = child.get_next_sibling()
    assert labels == ["Fotos", "Audio", "Dateien"], labels
    shot(menu, "08c-plus-menue.png")
    menu.popdown()
    settle(0.2)

    # f0-05/f0-12: the field empty → a microphone; record (a test tone, never a real microphone), stop, hear, send.
    window.input.set_text("")
    settle(0.2)
    assert window.mic.get_visible()
    window.input.set_text("a")
    assert not window.mic.get_visible()
    window.input.set_text("")
    bernd.fetch()
    window.record()
    settle(1.5)
    assert window.record_box.get_visible() and not window.input.get_visible() and "Aufnahme  0:01" in window.record_label.get_label()
    window.recording_pressed()                  # ■ – keep it for listening first
    settle(0.2)
    assert window.recorded is not None and window.record_play.get_tooltip_text() == "Anhören"
    shot(window, "08-aufnahme.png")
    window.end_recording(send=True)
    assert window.input.get_visible() and not window.record_box.get_visible()
    until(lambda: any((m.get("anhang") or {}).get("art") == "sprache" and anna.tick(cid, m)[0] == "gesendet" for m in anna.chats[cid]["messages"]))
    voice = [n for n in bernd.fetch() if n.get("anhang")][0]["anhang"]
    assert voice["art"] == "sprache" and len(voice["welle"]) == 50 and voice["dauer"] >= 1.0
    assert bernd.open_attachment(voice)[4:8] == b"ftyp"
    window.record()
    settle(0.6)
    window.end_recording(send=False)             # ✕ – nothing sent
    settle(0.5)
    assert [n for n in bernd.fetch() if n.get("anhang")] == []
    window.quit_all()
    httpd.shutdown()
    print("ok – LiChat-Fenster wie Nachrichten am Mac (Ubuntu)")


if __name__ == "__main__":
    main()
