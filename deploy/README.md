# deploy/

Dateien, um den LiChat-Server mit Docker und nginx zu betreiben:

- `Dockerfile` – der Server (Python-Standardbibliothek und gunicorn)
- `docker-compose.yml` – Container, nur auf 127.0.0.1, schreibgeschützt, ohne Rechte; liest `deploy/.env`
- `env.beispiel` – Vorlage für `deploy/.env` (Adresse und Datenordner)
- `nginx-lichat.conf` – nginx davor, ohne Zugriffsprotokoll, mit größerem Limit nur für Anhänge

Schritt für Schritt: [docs/SERVER.md](../docs/SERVER.md).
