#!/usr/bin/env bash
set -euo pipefail
umask 077
export DISPLAY=:91
export XDG_RUNTIME_DIR=/run/algohub-qoj-browser
export XAUTHORITY="$XDG_RUNTIME_DIR/Xauthority"
mkdir -p "$HOME/profile"
touch "$XAUTHORITY"
xauth add "$DISPLAY" . "$(mcookie)"
Xvfb "$DISPLAY" -screen 0 1280x800x24 -nolisten tcp -auth "$XAUTHORITY" &
for i in {1..50}; do
    if xdpyinfo >/dev/null 2>&1; then break; fi
    sleep 0.1
done
xdpyinfo >/dev/null
openbox >"$XDG_RUNTIME_DIR/openbox.log" 2>&1 &
x11vnc -display "$DISPLAY" -auth "$XAUTHORITY" -no6 -listen 127.0.0.1 -rfbport 5901 \
    -forever -shared -nopw -noxdamage -quiet >"$XDG_RUNTIME_DIR/vnc.log" 2>&1 &
websockify --web=/usr/share/novnc 127.0.0.1:6081 127.0.0.1:5901 \
    >"$XDG_RUNTIME_DIR/websockify.log" 2>&1 &
/usr/bin/google-chrome-stable --user-data-dir="$HOME/profile" \
    --no-first-run --no-default-browser-check --disable-dev-shm-usage \
    --remote-debugging-address=127.0.0.1 --remote-debugging-port=9223 \
    --window-size=1280,800 --start-maximized https://qoj.ac/login \
    >"$XDG_RUNTIME_DIR/chrome.log" 2>&1 &
# systemd owns the whole cgroup; any exited component stops the complete session.
wait -n
exit 1
