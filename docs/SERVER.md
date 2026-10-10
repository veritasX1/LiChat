# Einen eigenen LiChat-Server betreiben

LiChat braucht einen Server, der die verschlüsselten Nachrichten zwischen den Geräten weiterreicht. Lesen kann er sie
nicht: Er sieht nur Pakete, die auf den Geräten verschlüsselt wurden. Diese Anleitung richtet ihn auf einem Raspberry Pi
oder einem anderen Rechner mit Debian, Ubuntu oder Raspberry Pi OS ein. Rechne mit einer halben Stunde.

## Was du brauchst

- Einen Rechner, der dauerhaft läuft, z. B. einen Raspberry Pi 4 mit einer externen Festplatte oder SSD.
  Die Daten sollen nicht auf der SD-Karte liegen.
- Eine eigene Adresse im Internet, z. B. `chat.example.org`, die auf deinen Anschluss zeigt.
  Bei wechselnder IP hilft ein DynDNS-Dienst (deSEC, DuckDNS, der eigene Router).
- Im Router die Ports **80** und **443** auf diesen Rechner weitergeleitet.
- Zugang per Terminal mit `sudo`.

## 1 · Programme installieren

```
sudo apt update
sudo apt install -y git docker.io docker-compose-v2 nginx certbot python3-certbot-nginx
```

## 2 · LiChat holen und einstellen

```
git clone https://github.com/veritasX1/LiChat.git
cd LiChat
cp deploy/env.beispiel deploy/.env
nano deploy/.env
```

In `deploy/.env` stehen zwei Zeilen:

```
LICHAT_DOMAIN=chat.example.org        # deine Adresse, ohne https://
LICHAT_DATEN=/srv/lichat/daten        # wo die Datenbank liegt
```

Den Datenordner anlegen. Er gehört dem Benutzer 1000, mit dem der Server im Container läuft:

```
sudo install -d -o 1000 -g 1000 -m 700 /srv/lichat/daten
```

## 3 · Server starten

```
sudo docker compose -f deploy/docker-compose.yml up -d --build
curl http://127.0.0.1:8440/v1/health
```

Die Antwort sieht so aus: `{"ok": true, "version": "1.0.0"}`. Der Server lauscht nur auf `127.0.0.1`. Von außen
erreicht man ihn erst über nginx. Er startet nach einem Neustart des Rechners von selbst.

## 4 · nginx und HTTPS

```
sudo cp deploy/nginx-lichat.conf /etc/nginx/sites-available/lichat
sudo sed -i 's/DEINE-DOMAIN/chat.example.org/g' /etc/nginx/sites-available/lichat
sudo ln -s ../sites-available/lichat /etc/nginx/sites-enabled/lichat
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d chat.example.org
```

certbot holt ein kostenloses Zertifikat von Let's Encrypt und erneuert es später von selbst. Danach prüfen, diesmal
von außen:

```
curl https://chat.example.org/v1/health
```

nginx schreibt für LiChat kein Zugriffsprotokoll (`access_log off`). Der Server selbst notiert nur Methode, Pfad und
Status, keine IP-Adressen.

## 5 · Einladen

Registrieren kann sich nur, wer eine Einladung hat. Für jede Person eine erzeugen:

```
sudo docker exec lichat-server python3 -m lichat_server.admin invite "für Anna"
```

Ausgegeben werden der Code und ein Link wie `https://chat.example.org/e/…`. Die Seite hinter dem Link zeigt einen
QR-Code. Wer LiChat installiert hat, scannt ihn beim Einrichten, gibt nur noch einen Namen ein und ist drin. Ohne Kamera
gehen auch Serveradresse und Code von Hand.

Wer schon dabei ist, kann in der App weitere Leute einladen („Jemanden einladen“). Pro Person sind höchstens fünf
offene Einladungen möglich, jede gilt sieben Tage.

## Sicherung

Alles Wichtige liegt in dem Ordner aus `LICHAT_DATEN`: die Datenbank und die verschlüsselten Anhänge. Darin stehen nur
Benutzernamen, Geräte-IDs, öffentliche Schlüssel und noch nicht abgeholte, verschlüsselte Pakete. Die Gespräche selbst
liegen auf den Geräten, nicht auf dem Server.

Eine Sicherung im laufenden Betrieb:

```
sudo sqlite3 /srv/lichat/daten/lichat.db ".backup '/srv/lichat/sicherung-$(date +%F).db'"
```

## Aktualisieren

```
cd LiChat
git pull
sudo docker compose -f deploy/docker-compose.yml up -d --build
```

Die Apps verbinden sich danach von selbst neu. Was in der kurzen Pause gesendet wurde, geht automatisch nach.

## Was der Server von allein tut

- Abgeholte Nachrichten löscht er sofort.
- Nicht abgeholte Nachrichten und Anhänge löscht er nach 30 Tagen. Ein Einmal-Bild ist weg, sobald alle es geöffnet
  haben, ungeöffnet nach 14 Tagen. Abgelaufene Einladungen und PINs fallen ebenfalls weg. Der Aufräumlauf startet höchstens einmal
  pro Stunde von selbst.
- Gegen Missbrauch begrenzt er Registrierungen, Anmeldeversuche, Einladungen, PINs, das Abholen von Schlüsseln und die
  Größe der Anhänge (höchstens 100 MB).

## Wenn etwas hakt

**`curl http://127.0.0.1:8440/v1/health` antwortet nicht.**
`sudo docker logs lichat-server` zeigt den Grund. Meist gehört der Datenordner nicht dem Benutzer 1000 (Schritt 2).

**certbot meldet einen Fehler.**
Die Adresse zeigt noch nicht auf deinen Anschluss, oder Port 80 ist im Router nicht weitergeleitet. Mit
`dig +short chat.example.org` prüfen, ob die richtige IP herauskommt.

**Zu Hause geht es, unterwegs nicht.**
Port 443 ist nicht weitergeleitet, oder der Anbieter vergibt keine öffentliche IPv4-Adresse (DS-Lite). Dann hilft IPv6
oder ein Tunnel; dein Anbieter kann dir sagen, was bei dir geht.

**Die App sagt „Keine Verbindung zum Server“.**
Ist der Server erreichbar (`curl https://chat.example.org/v1/health`)? Die App versucht es von selbst weiter. Nicht
gesendete Nachrichten gehen raus, sobald die Verbindung zurück ist.

## Bekannte Grenzen

- Geräte melden sich mit einem Zugangs-Token an, über HTTPS. In der Datenbank liegt nur dessen Prüfsumme, auf dem
  Gerät ist es verschlüsselt. Signierte Anfragen kommen in einer späteren Version.
- Der Server sieht, welches Gerät wann Nachrichten abholt und an welche Geräte gesendet wird. Was in den Nachrichten
  steht, wer in einer Gruppe ist und wie sie heißt, sieht er nicht.

Mehr dazu: [Architektur](ARCHITEKTUR.md) · [Transparenzbericht](TRANSPARENZ-1.0.md)
