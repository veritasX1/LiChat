# LiChat

Ein Messenger nach dem Vorbild von Apple Nachrichten – mit eigenem Server, immer Ende-zu-Ende-verschlüsselt,
ohne Telefonnummer, ohne Werbung, ohne Datensammlung. Teil der Li-Apps von Olaf Winkler.

> Name: **LiChat** (Olaf, 08.10.2026).

## Grundsätze (Wertekompass, gilt für alle Li-Apps)
1. **Privatsphäre:** Inhalte, Anhänge, Gruppennamen und Kontaktlisten sind Ende-zu-Ende verschlüsselt (MLS, RFC 9420) –
   der Server sieht nur verschlüsselte Pakete. Im Postfach speichert er keinen Absender. Zugestelltes löscht er sofort,
   nicht Abgeholtes nach 30 Tagen.
2. **Kontrolle:** Eigener Server (z. B. ein Raspberry Pi zu Hause), Registrierung nur mit Einladung, alles abschaltbar.
   Keine Telefonnummer, kein Push-Dienst von Google oder Apple – die App hält selbst die Verbindung.
3. **Einfachheit:** Bedienung wie Apple Nachrichten (iOS 18 / macOS 15 als Vorbild).
4. **Kostenlos für immer:** keine Käufe, keine Abos, keine Werbung, keine Telemetrie.

KI ist ab Werk aus. Es wird kein Modell mitgeliefert.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `server/` | LiChat-Server: Einladungen, Geräte, MLS-Key-Packages, Postfach je Gerät, verschlüsselte Anhänge. Python, nur Standardbibliothek |
| `core/` | Gemeinsamer Kern in Rust: OpenMLS (RFC 9420), Zustand verschlüsselt auf dem Gerät; Bindings für Kotlin (uniffi) und Python (pyo3) |
| `android/` | Android-App (Kotlin/Compose), Look wie Nachrichten auf dem iPhone |
| `linux/` | Ubuntu-App (GTK4/libadwaita, Python), Look wie Nachrichten auf dem Mac; dazu `lichat-cli` |
| `deploy/` | Docker, nginx – der Server auf dem eigenen Rechner ([docs/SERVER.md](docs/SERVER.md)) |
| `docs/` | Architektur, Server-Anleitung, Hilfe, Transparenzbericht |

Version **1.0.0** (10.10.2026). Hilfe: [docs/HILFE.md](docs/HILFE.md) · eigener Server: [docs/SERVER.md](docs/SERVER.md) ·
was die App tut und nicht tut: [docs/TRANSPARENZ-1.0.md](docs/TRANSPARENZ-1.0.md).

## Kern testen

```
cd core && nice cargo test -j 3                     # zwei Geräte, Gruppe, Schlüsselwechsel, Fälschungen
```

## Server ausprobieren (nur lokal)

```
cd server
python3 -m unittest discover -s tests -v          # Tests, ohne Netz
LICHAT_DATA=/tmp/lichat python3 wsgi.py            # lauscht nur auf 127.0.0.1:8090
LICHAT_DATA=/tmp/lichat python3 -m lichat_server.admin invite "für Anna"
```

Die Schnittstelle steht oben in `server/lichat_server/app.py`.

## Lizenz
GPL-3.0 für ganz LiChat (Server, Kern, Ubuntu, Android) wie bei den anderen Li-Apps – Olaf 10.10.2026. Siehe `LICENSE`.

Grundlagen: Olafs Notiz „LiChat – Konzept“ (LiNotes 1a8a84fc), Anforderungskarte 37ad8505,
Konzeptabgleich `~/Team/Jean-Marie/lichat-konzept.md`, Testplan `~/Team/Michelle/lichat-testplan.md`.
