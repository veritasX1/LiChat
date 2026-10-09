# android

LiChat für Android im Look von Nachrichten auf iOS 18 (Kotlin/Compose). Kern: `core/` über uniffi (JNA).

| Datei | Inhalt |
|---|---|
| `core/Account.kt` | Zwilling von `linux/lichat/account.py`: gleiche Dateien, Pakete, Prüfungen (Key-Packages gegen Verzeichnis, Teilnehmerliste gegen MLS-Gruppe) |
| `core/KeyVault.kt` | Geräteschlüssel, umhüllt vom Android-Keystore (`key.wrapped`) |
| `core/Pictures.kt` | Profilbild: quadratisch, 512 px, JPEG ohne EXIF |
| `Service.kt` | Vordergrunddienst (remoteMessaging) mit stehender Verbindung (Warteabruf), Mitteilungen, Start nach Neustart |
| `MainActivity.kt`, `ui/` | Liste, Gespräch, Neue Nachricht, Info (Mitglieder, Sicherheitscode, Entfernen), Einstellungen, Anmelden, Erklärung vor den Zustimmungen |

Kern bauen (nie gleichzeitig mit Gradle): `../tools/kern-android.sh` – legt `jniLibs/arm64-v8a/liblichat_core.so`
(nicht im Repo) und die Kotlin-Bindung `uniffi/lichat_core/lichat_core.kt` an und baut die Bibliothek für die Tests.

Tests: `./gradlew testDebugUnitTest` – startet einen echten LiChat-Server (Python) und spricht auch mit `lichat-cli`.
Bilder: `./gradlew recordRoborazziDebug --tests '*ScreensShotTest' -Pshots=ORDNER`.

Testfassung: `io.github.veritasx1.lichat.test` „LiChat Test“. Nur dort schreibt der Dienst je empfangener Nachricht eine
Logzeile `LiChatZ5` (Hash, server_ms, gezeigt_ms, verbunden_seit – ohne Inhalt, ohne Namen; Michelle Z5).
Ohne Pi-Server: `adb reverse tcp:8090 tcp:8090` und `server/wsgi.py` auf dem Rechner, dann Server `http://127.0.0.1:8090`.
