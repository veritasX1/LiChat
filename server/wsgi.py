"""Entry point, e.g. `gunicorn wsgi:app` behind the reverse proxy, or for a local try:
    LICHAT_DATA=/tmp/lichat python3 wsgi.py   (listens on 127.0.0.1:8090 only)"""

import threading
import time
from socketserver import ThreadingMixIn
from wsgiref.simple_server import WSGIServer, make_server

from lichat_server.app import create_app

app = create_app()


def purge_hourly():
    while True:
        app.store.purge()
        time.sleep(3600)


threading.Thread(target=purge_hourly, daemon=True).start()

class ThreadingServer(ThreadingMixIn, WSGIServer):
    """One thread per request: every app keeps a waiting fetch open (GET /v1/messages?warten=…)."""
    daemon_threads = True


if __name__ == "__main__":
    with make_server("127.0.0.1", 8090, app, server_class=ThreadingServer) as server:
        print("LiChat-Server auf http://127.0.0.1:8090")
        server.serve_forever()
