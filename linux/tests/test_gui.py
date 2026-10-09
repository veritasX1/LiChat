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

    # „… tippt“ (card 884f7c8a): off by default; with both switched on Bernd's typing shows as a grey „•••“ bubble.
    assert not window.lookup_action("tippanzeige").get_state().get_boolean()
    window.lookup_action("tippanzeige").change_state(GLib.Variant.new_boolean(True))
    bernd.set_typing_indicator(True)
    until(lambda: anna.typing_indicator)
    assert bernd.typing(cid)
    until(lambda: "•••" in texts(window.bubbles))
    settle(0.3)
    # The newest bubble is wholly in view, not under the input line (card 0c01f599).
    ok, bounds = window.bubbles.get_last_child().compute_bounds(window.scroller)
    assert ok and bounds.get_y() + bounds.get_height() <= window.scroller.get_height(), (bounds.get_y(), bounds.get_height(), window.scroller.get_height())
    shot(window, "03e-tippt.png")

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
    assert set(again.chats) == {cid, group} and len(again.chats[cid]["messages"]) == 11  # + „Noch was: Grillkohle?“, Grillen um 7, widerrufen
    window.quit_all()
    httpd.shutdown()
    print("ok – LiChat-Fenster wie Nachrichten am Mac (Ubuntu)")


if __name__ == "__main__":
    main()
