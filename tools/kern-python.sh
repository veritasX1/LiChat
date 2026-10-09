#!/bin/sh
# The Rust core for Python (Ubuntu app, lichat-cli). Installed by renaming, never by copying over the old file: running
# LiChats keep the library they loaded (09.10.: cp over a loaded .so made running „warten“ crash with SIGSEGV/SIGILL).
set -e
cd "$(dirname "$0")/../core"
nice cargo build -j 3 --release --features python
cp target/release/liblichat_core.so ../linux/lichat/.lichat_core.abi3.so.neu
mv -f ../linux/lichat/.lichat_core.abi3.so.neu ../linux/lichat/lichat_core.abi3.so
echo "Kern für Python installiert"
