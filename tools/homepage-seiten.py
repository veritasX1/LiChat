#!/usr/bin/env python3
"""Hilfe, Server-Anleitung und Transparenzbericht für die Homepage – aus docs/*.md erzeugt (eine Quelle für Repo und
Seite): homepage/hilfe.html, server.html, transparenz.html. Links zwischen den Dokumenten zeigen auf die Seiten."""

import html
import re
from pathlib import Path

import markdown

HIER = Path(__file__).resolve().parent.parent
SEITEN = [("HILFE.md", "hilfe.html", "LiChat – Hilfe", "Hilfe"),
          ("SERVER.md", "server.html", "Eigener LiChat-Server – Schritt für Schritt", "Eigener Server"),
          ("TRANSPARENZ-1.0.md", "transparenz.html", "LiChat 1.0 – Transparenzbericht", "Transparenzbericht")]
LINKS = {"HILFE.md": "hilfe.html", "SERVER.md": "server.html", "TRANSPARENZ-1.0.md": "transparenz.html",
         "ARCHITEKTUR.md": "https://github.com/veritasX1/LiChat/blob/main/docs/ARCHITEKTUR.md"}

KOPF = """<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{titel}</title>
<link rel="icon" href="lichat.svg" type="image/svg+xml">
<link rel="stylesheet" href="site.css">
</head>
<body>
<header class="nav">
  <div class="wrap">
    <a class="brand" href="./"><img src="lichat.svg" alt="">LiChat</a>
    <nav class="links">
      <a href="./">Start</a>
      <a href="hilfe.html"{h}>Hilfe</a>
      <a href="server.html"{s}>Eigener Server</a>
      <a href="transparenz.html"{t}>Transparenz</a>
      <a href="https://github.com/veritasX1/LiChat">GitHub</a>
    </nav>
    <a class="btn small cta" href="./#laden">Laden</a>
  </div>
</header>
<main>
<div class="wrap docs">
  <nav class="toc">
{inhalt}
  </nav>
  <article>
{text}
  </article>
</div>
</main>
<footer>
  <div class="wrap made">
    <img src="lichat.svg" alt="" width="34" height="34">
    <p>Ein privates, nichtkommerzielles Projekt von LiSoftware aus Schleswig-Holstein.</p>
  </div>
</footer>
<script src="site.js"></script>
</body>
</html>
"""


def seite(quelle, ziel, titel, name):
    md = (HIER / "docs" / quelle).read_text()
    for alt, neu in LINKS.items():
        md = md.replace(f"({alt})", f"({neu})")
    text = markdown.markdown(md, extensions=["tables", "fenced_code", "toc"])
    # sections between the h2 headings, like the other Li pages
    teile = re.split(r"(?=<h2 id=)", text)
    text = teile[0] + "".join(f"<section>{t}</section>" for t in teile[1:])
    inhalt = "\n".join(f'    <a href="#{a}">{re.sub("<[^>]+>", "", b)}</a>' for a, b in re.findall(r'<h2 id="([^"]+)">(.*?)</h2>', text))
    aktiv = ' aria-current="page"'
    (HIER / "homepage" / ziel).write_text(KOPF.format(titel=html.escape(titel), inhalt=inhalt, text=text,
                                                     h=aktiv if ziel == "hilfe.html" else "", s=aktiv if ziel == "server.html" else "",
                                                     t=aktiv if ziel == "transparenz.html" else ""))
    print(ziel)


for args in SEITEN:
    seite(*args)
