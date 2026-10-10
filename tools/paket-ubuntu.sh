#!/bin/bash
# Das Ubuntu-Paket für die Homepage: lichat-ubuntu-<version>.tar.gz mit App, lichat-cli, Kern (x86_64) und install.sh.
# Vorher den Kern bauen: tools/kern-python.sh (legt linux/lichat/lichat_core.abi3.so ab).
set -euo pipefail
HIER="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=$(python3 -c "import sys; sys.path.insert(0, '$HIER/linux'); from lichat import VERSION; print(VERSION)")
ZIEL="${1:-$HIER/homepage/download}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/lichat/lichat" "$ZIEL"
test -f "$HIER/linux/lichat/lichat_core.abi3.so" || { echo "Kern fehlt – erst tools/kern-python.sh"; exit 1; }
cp "$HIER"/linux/lichat/*.py "$HIER/linux/lichat/style.css" "$HIER/linux/lichat/lichat_core.abi3.so" "$TMP/lichat/lichat/"
cp "$HIER/linux/main.py" "$HIER/linux/lichat-cli" "$HIER/linux/install.sh" "$HIER/linux/io.github.veritasx1.LiChat.svg" "$HIER/LICENSE" "$TMP/lichat/"
tar -C "$TMP" --owner=0 --group=0 -czf "$ZIEL/lichat-ubuntu-$VERSION.tar.gz" lichat
echo "$ZIEL/lichat-ubuntu-$VERSION.tar.gz"
