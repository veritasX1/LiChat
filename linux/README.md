# linux

Ubuntu-Seite von LiChat. Heute: **lichat-cli** und die gemeinsame Konto-Logik; die GTK-App folgt.

| Datei | Inhalt |
|---|---|
| `lichat/account.py` | Konto auf dem Gerät: Server-Aufrufe, MLS-Kern, Ablage (versiegelt), Postfach holen und quittieren |
| `lichat/lichat_core.abi3.so` | der Kern aus `core/` (nicht im Repo, wird gebaut, s. u.) |
| `lichat-cli` | Kommandozeile: `register`, `neu`, `senden`, `abholen`, `gespraeche`, `verlauf`, `mitglieder`, `code` |
| `tests/test_account.py` | Tests gegen einen echten Server im Prozess (zu zweit, Gruppe, Neustart, Last-Resort, gefälschte Key-Packages) |

Kern bauen (nie gleichzeitig mit Gradle):

    cd ../core && nice cargo build -j 3 --release --features python && cp target/release/liblichat_core.so ../linux/lichat/lichat_core.abi3.so

Ausprobieren mit dem lokalen Server:

    cd ../server && LICHAT_DATA=/tmp/lichat python3 wsgi.py &
    LICHAT_DATA=/tmp/lichat python3 -m lichat_server.admin invite anna     # Einladung
    ./lichat-cli --profil anna register http://127.0.0.1:8090 <einladung> anna
    ./lichat-cli --profil anna neu bernd ; ./lichat-cli --profil bernd abholen

Tests: `python3 -m unittest discover -s tests` (braucht python3-cryptography).

Auf dem Draht ist ein Paket ein Kennbyte plus die Bytes des Kerns (`W` Welcome, `M` MLS). Wer ein Gespräch
bekommt, steht verschlüsselt in der ersten Nachricht („roster“) und muss genau zu den Mitgliedern der MLS-Gruppe
passen. Abgeholte Key-Packages müssen zum Namen und Identitätsschlüssel im Verzeichnis passen.
