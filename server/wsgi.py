"""Entry point, e.g. `gunicorn wsgi:app` behind the reverse proxy, or for a local try:
    LICHAT_DATA=/tmp/lichat python3 wsgi.py   (listens on 127.0.0.1:8090 only)"""

import threading
import time
from wsgiref.simple_server import make_server

from lichat_server.app import create_app

app = create_app()


def purge_hourly():
    while True:
        app.store.purge()
        time.sleep(3600)


threading.Thread(target=purge_hourly, daemon=True).start()

if __name__ == "__main__":
    with make_server("127.0.0.1", 8090, app) as server:
        print("LiChat-Server auf http://127.0.0.1:8090")
        server.serve_forever()
