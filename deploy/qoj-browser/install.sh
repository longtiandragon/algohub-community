#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run as root'; exit 1; }
cd -- "$(dirname -- "$0")"
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=l
apt-get update -qq
apt-get install -y --no-install-recommends ca-certificates curl xvfb xauth x11-utils x11vnc novnc websockify openbox fonts-noto-cjk
if ! command -v google-chrome-stable >/dev/null; then
    curl --fail --location --retry 2 --proto '=https' \
        https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
        -o /opt/algohub-qoj-browser/google-chrome.deb
    apt-get install -y --no-install-recommends /opt/algohub-qoj-browser/google-chrome.deb
fi
if ! id qoj-browser >/dev/null 2>&1; then
    useradd --system --user-group --home-dir /var/lib/algohub-qoj-browser --shell /usr/sbin/nologin qoj-browser
fi
install -d -o qoj-browser -g qoj-browser -m 700 /var/lib/algohub-qoj-browser
install -m 644 algohub-qoj-browser.service /etc/systemd/system/algohub-qoj-browser.service
chmod 755 start.sh
systemctl daemon-reload
# Manual feasibility session: deliberately not enabled at boot.
systemctl start algohub-qoj-browser
