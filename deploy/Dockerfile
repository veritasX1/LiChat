# LiChat server on the Pi: Python standard library + gunicorn, nothing else.
FROM python:3.12-slim
RUN pip install --no-cache-dir gunicorn==23.0.0 && useradd --uid 1000 --create-home lichat
WORKDIR /app
COPY server/lichat_server /app/lichat_server
COPY server/wsgi.py /app/wsgi.py
USER lichat
ENV LICHAT_DATA=/daten
# ONE worker: the doorbell (waiting fetches) and the limits live in the process. Many threads: every app keeps one
# waiting fetch open (GET /v1/messages?warten=50).
CMD ["gunicorn", "--workers", "1", "--worker-class", "gthread", "--threads", "128", "--timeout", "90", \
     "--bind", "0.0.0.0:8000", "--access-logfile", "-", "--access-logformat", "%(m)s %(U)s %(s)s", "wsgi:app"]
