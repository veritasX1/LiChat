# LiChat auf dem Pi

Ziel: `https://exampleserver.lisoftware.de/v1/…` → nginx → Container `lichat-server` (127.0.0.1:8440). Daten auf der 6-TB-Platte.

| Schritt | wer | Befehl |
|---|---|---|
| 1. DNS `lichat` → wie lisoftware.de | Claude (deSEC-Token „Pi“) | CNAME `exampleserver.lisoftware.de.` → `lisoftware.de.` |
| 2. Code auf den Pi | Claude | `rsync -a --exclude target --exclude .git ~/LiChat/ user@192.168.178.20:~/LiChat/` |
| 3. Datenordner | Claude (root, Olaf gibt frei) | `sudo install -d -o 1000 -g 1000 -m 700 /media/olaf/6TB/lichat/daten` |
| 4. Container | Claude (docker, Olaf gibt frei) | `cd ~/LiChat && docker compose -f deploy/docker-compose.yml up -d --build` |
| 5. nginx | Claude (root, Olaf gibt frei) | `sudo cp deploy/nginx-lichat.conf /etc/nginx/sites-available/lichat && sudo ln -s ../sites-available/lichat /etc/nginx/sites-enabled/ && sudo nginx -t && sudo systemctl reload nginx` |
| 6. Zertifikat | Claude (root, Olaf gibt frei) | `sudo certbot --nginx -d exampleserver.lisoftware.de` |
| 7. Prüfen | Claude | `curl https://exampleserver.lisoftware.de/v1/health` |
| 8. Einladungen | Claude | `docker exec lichat-server python3 -m lichat_server.admin invite olaf` |

Keine Zugriffsprotokolle in nginx; gunicorn schreibt nur Methode, Pfad, Status (keine IP, keine Zeit, kein Token).
Die Datenbank enthält nur verschlüsselte Pakete, Benutzernamen, Geräte-IDs und öffentliche Schlüssel.
