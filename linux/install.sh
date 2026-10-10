#!/bin/sh
# LiChat für Ubuntu einrichten: Starter, Eintrag im Programm-Menü und Symbol – nur für dich, ohne sudo.
# Aufruf aus dem entpackten Ordner:  ~/.local/share/lichat/install.sh
set -eu
HIER="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HOME/.local/bin" "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/scalable/apps"
cat > "$HOME/.local/bin/lichat" <<EOT
#!/bin/sh
exec /usr/bin/python3 "$HIER/main.py" "\$@"
EOT
chmod 755 "$HOME/.local/bin/lichat"
cp "$HIER/io.github.veritasx1.LiChat.svg" "$HOME/.local/share/icons/hicolor/scalable/apps/"
cat > "$HOME/.local/share/applications/io.github.veritasx1.LiChat.desktop" <<EOT
[Desktop Entry]
Type=Application
Name=LiChat
GenericName=Messenger
Comment=Ende-zu-Ende-verschlüsselt chatten auf deinem eigenen Server
Exec=$HOME/.local/bin/lichat
Icon=io.github.veritasx1.LiChat
Terminal=false
Categories=Network;InstantMessaging;Chat;GTK;
Keywords=Chat;Nachrichten;Messenger;LiChat;
StartupNotify=true
StartupWMClass=io.github.veritasx1.LiChat
EOT
update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
gtk-update-icon-cache -q "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
echo "LiChat ist eingerichtet – im Programm-Menü oder mit „lichat“ im Terminal starten."
