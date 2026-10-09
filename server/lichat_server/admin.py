"""Server administration on the Pi:  python3 -m lichat_server.admin invite [note]  |  purge"""

import os
import sys

from .store import Store


def main(argv):
    store = Store(os.environ.get("LICHAT_DATA", "./data"))
    if argv[:1] == ["invite"]:
        code = store.create_invite(" ".join(argv[1:]))
        public = (os.environ.get("LICHAT_OEFFENTLICH") or "https://lichat.lisoftware.de").rstrip("/")
        print(code)
        print(f"{public}/e/{code}", file=sys.stderr)   # the link (page with QR) on stderr – the code alone stays on stdout
    elif argv[:1] == ["purge"]:
        print(f"{store.purge()} alte Nachrichten gelöscht")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
