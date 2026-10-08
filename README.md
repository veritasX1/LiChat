# LiChat

Ein Messenger nach dem Vorbild von Apple Nachrichten – mit eigenem Server, immer Ende-zu-Ende-verschlüsselt,
ohne Telefonnummer, ohne Werbung, ohne Datensammlung. Teil der Li-Apps von LiSoftware.

> Arbeitsname „LiChat“ (Board). In Olafs Konzept-Notiz steht als Antwort „LiTalk“ – der endgültige Name ist
> noch offen (Frage A unten).

## Grundsätze (Wertekompass, gilt für alle Li-Apps)
1. **Privatsphäre:** Der Server sieht nur verschlüsselte Pakete. Keine Kontaktlisten, keine Gruppennamen,
   kein Absender (Sealed Sender), Zugestelltes wird sofort gelöscht, nicht Abgeholtes nach 30 Tagen.
2. **Kontrolle:** Eigener Server (Pi im Heimnetz), Registrierung nur mit Einladung, alles abschaltbar.
3. **Einfachheit:** Bedienung wie Apple Nachrichten (Apple HIG, iOS 26 als Vorbild).
4. **Kostenlos für immer:** keine Käufe, keine Abos, keine Werbung, keine Telemetrie.

KI ist ab Werk aus. Es wird kein Modell mitgeliefert. Eine KI kann höchstens als **sichtbares Mitglied** in ein
Gespräch eingeladen werden, mit deutlichem Hinweis und Schlüsselwechsel beim Entfernen.

## Aufbau

| Ordner | Inhalt | Stand |
|---|---|---|
| `server/` | LiChat-Server, Stufe 1: Einladungen, Geräte, MLS-Key-Packages, Postfach je Gerät, ntfy-Anstoß. Python, nur Standardbibliothek | **Grundgerüst mit Tests** |
| `core/` | Gemeinsamer Kern in Rust: OpenMLS (RFC 9420), verschlüsselter Speicher; Bindings für Kotlin (uniffi) und Python (pyo3) | geplant |
| `android/` | Android-App (Kotlin/Compose), Look wie Nachrichten auf dem iPhone | geplant |
| `linux/` | Ubuntu-App (GTK4/libadwaita, Python), Look wie Nachrichten auf dem Mac; dazu `lichat-cli` fürs Team | geplant |
| `shared/` | Gemeinsames: Übersetzungen (DE/EN/FR), Hilfe, Testdaten | geplant |
| `docs/` | Architektur, Protokoll, später Transparenzbericht | begonnen |

Ausbaustufen (Konzept): **1** Einzelgespräche · **2** Gruppen · **3** Signal auf dem Gerät (libsignal als
verknüpftes Gerät, keine Brücke auf dem Server, AGPL). Telegram vorerst nicht.

## Server ausprobieren (nur lokal)

```
cd server
python3 -m unittest discover -s tests -v          # Tests, ohne Netz
LICHAT_DATA=/tmp/lichat python3 wsgi.py            # lauscht nur auf 127.0.0.1:8090
LICHAT_DATA=/tmp/lichat python3 -m lichat_server.admin invite "für Anna"
```

Die Schnittstelle steht oben in `server/lichat_server/app.py`.

## Offene Fragen an Olaf
- **A** Name: LiChat oder LiTalk?
- **B** Gruppen schon in Stufe 1, damit das Team früh dabei ist, oder erst Einzelgespräche?
- **C** Farbe des eigenen Netzes: Blau wie iMessage (Grün bleibt für „unsicherer“) oder Grün wie in der Notiz?
- Lizenz (wegen libsignal in Stufe 3 vermutlich AGPL-3.0) – entscheidet Olaf.

Grundlagen: Olafs Notiz „LiChat – Konzept“ (LiNotes 1a8a84fc), Anforderungskarte 37ad8505,
Konzeptabgleich `~/Team/Jean-Marie/lichat-konzept.md`, Testplan `~/Team/Michelle/lichat-testplan.md`.
