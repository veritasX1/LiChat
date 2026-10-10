# Transparenzbericht LiChat 1.0

Stand: 10. Oktober 2026 · geprüft von Michelle (Test) · Quelltext: github.com/veritasX1/LiChat

Dieser Bericht sagt in einfachen Worten, was LiChat auf deinem Gerät und auf dem Server tut, wohin es Verbindungen aufbaut und was es speichert. Jede Angabe lässt sich im Quelltext nachprüfen. Wir haben den Bericht gegen unseren Wertekompass geprüft: Privatsphäre zuerst, Kontrolle beim Menschen, einfach zu bedienen, kostenlos für immer.

## Kurz gesagt

- LiChat spricht nur mit **einem** Server: dem, den du eingetragen hast. Ab Werk ist das lichat.lisoftware.de, du kannst aber jeden eigenen LiChat-Server eintragen.
- Nachrichten, Fotos, Sprachnachrichten und Dateien sind **Ende-zu-Ende-verschlüsselt** (MLS nach RFC 9420). Der Server leitet nur Pakete weiter, die er nicht lesen kann.
- Keine Werbung, keine Tracker, keine Telemetrie, keine Google-Dienste, kein Firebase.
- Keine Telefonnummer, kein Adressbuch-Hochladen. Du meldest dich mit einer Einladung und einem frei gewählten Namen an.
- Der Server löscht Nachrichten, sobald dein Gerät sie abgeholt hat.

## Verbindungen

| Wohin | Wann | Wozu |
|---|---|---|
| Dein LiChat-Server (ab Werk lichat.lisoftware.de, änderbar) | solange die App läuft, als wartende Abfrage | Nachrichten senden und abholen, Schlüssel austauschen |
| sonst nirgendwohin | | |

- Mitteilungen laufen über eine eigene, dauerhafte Verbindung zu deinem Server, nicht über Google-Push.
- Links in Nachrichten werden erkannt, aber erst geöffnet, wenn du sie antippst, im Browser deiner Wahl. LiChat lädt **keine Link-Vorschauen** aus dem Netz.
- Keine Update-Prüfung, keine Karten, keine GIF-Suche, keine Schriftarten aus dem Netz.
- Die App erlaubt nur verschlüsselte Verbindungen (HTTPS). Unverschlüsseltes HTTP ist gesperrt.

## Berechtigungen (Android)

| Berechtigung | Wofür | Wann gefragt |
|---|---|---|
| Internet, Netzwerkstatus | Verbindung zum Server, Neuverbindung bei Netzwechsel | nie gefragt (Systemrecht) |
| Mitteilungen | neue Nachrichten anzeigen | beim ersten Start |
| Vordergrunddienst, Start nach Neustart | die Verbindung halten, auch nach einem Neustart | nie gefragt (Systemrecht) |
| Akku-Optimierung ausnehmen | damit Nachrichten im Ruhezustand ankommen | einmal, du kannst ablehnen |
| Mikrofon | Sprachnachrichten | erst beim Tippen aufs Mikrofon |
| Kamera | QR-Code scannen, Foto aufnehmen | erst bei Benutzung, Kamera ist optional |
| Bildschirmfoto erkennen (Android 14+) | Hinweis, wenn jemand ein Einmal-Bild abfotografiert | nie gefragt (Systemrecht) |
| Biometrie | App-Sperre mit Fingerabdruck oder Bildschirmsperre | nur wenn du die App-Sperre einschaltest |

Keine Berechtigung für Kontakte, Standort, Speicher oder Fotos. Fotos wählst du über die Fotoauswahl des Systems aus, LiChat sieht nur die Bilder, die du antippst. Bibliotheken bringen keine weiteren Berechtigungen mit.

## Was auf deinem Gerät liegt

- Konto, Schlüssel, Gespräche und Profil, verschlüsselt mit AES-256-GCM. Der Schlüssel liegt auf Android im Keystore, unter Ubuntu im GNOME-Schlüsselbund.
- Keine Sicherung in eine Cloud (Android-Backup ist ausgeschaltet).
- Beim Senden entfernt LiChat aus Fotos die Ortsangabe, Kameradaten und Zeitstempel (EXIF). Auch aus Videos, Sprachnachrichten und GIFs werden Zusatzdaten entfernt.
- Entwürfe bleiben nur auf diesem Gerät.

## Was der Server sieht und speichert

**Er kann nicht lesen:** Inhalte von Nachrichten und Anhängen, Gruppennamen, Gruppenbilder, Profilbilder, wer eine Nachricht geschrieben hat (Sealed Sender).

**Er sieht:** an welche Geräte ein Paket geht, wie groß es ist und wann es kommt, die Benutzernamen und die Anzahl der Geräte je Konto. IP-Adressen nutzt er nur kurz im Speicher, um Missbrauch zu bremsen. Ein Zugriffsprotokoll führt der Server nicht.

**Er speichert und löscht:**

| Was | wie lange |
|---|---|
| Nachrichten | bis dein Gerät sie abgeholt hat, sonst höchstens 30 Tage |
| Anhänge (verschlüsselt) | 30 Tage |
| Einmal-Bilder | bis alle sie geöffnet haben, sonst 14 Tage |
| Einladungen | 7 Tage (vom Nutzer), 14 Tage (vom Betreiber) |
| Kontakt-PIN, Kopplungscode | 10 Minuten |
| Anmeldeschlüssel der Geräte | nur als Prüfsumme |

## Bibliotheken

- **Android:** AndroidX (Core, Activity, Lifecycle, Compose, ExifInterface, CameraX), ZXing (nur die QR-Code-Berechnung, ohne Google-Dienste), JNA.
- **Verschlüsselung (Rust-Kern):** OpenMLS mit RustCrypto, AES-GCM.
- **Ubuntu:** GTK 4, libadwaita, GStreamer (Sprachaufnahme), libsecret (Schlüsselbund), Python cryptography.
- **Server:** nur die Python-Standardbibliothek, im Docker-Abbild zusätzlich gunicorn.
- Keine Werbe-, Analyse- oder Absturz-Bibliotheken.

## Gegen den Wertekompass

| Grundsatz | Stand in 1.0 |
|---|---|
| Privatsphäre zuerst | Ende-zu-Ende-verschlüsselt, keine Telefonnummer, Metadaten aus Medien entfernt, Server löscht sofort. ✔ |
| Kontrolle beim Menschen | eigener Server möglich, Lesebestätigungen und Tipp-Anzeige ab Werk aus, Mitteilungsvorschau ab Werk aus, App-Sperre wählbar. ✔ |
| Netz nur, wenn nötig | eine einzige Verbindung zum eigenen Server, keine Vorschauen aus dem Netz. ✔ |
| Keine KI ab Werk | LiChat enthält keine KI. ✔ |
| Kostenlos für immer | keine Käufe, keine Abos, keine Werbung. Quelltext unter GPL-3.0. ✔ |

## Was wir noch verbessern wollen

- Die Anmeldung am Server nutzt einen geheimen Geräteschlüssel (Bearer-Token über HTTPS). Geplant ist, dass jedes Gerät seine Anfragen zusätzlich selbst signiert.
- Der Server kann sehen, welche Pakete zur selben Unterhaltung gehören (die MLS-Gruppenkennung ist ein zufälliger Wert, aber sie wiederholt sich).
- Neue Gruppenmitglieder und neue Geräte sehen ältere Nachrichten nicht (außer der einmaligen Übertragung beim Koppeln). Das ist so gewollt, kann aber überraschen.

Fragen oder Fehler? Schreib uns über die Hilfeseite.
