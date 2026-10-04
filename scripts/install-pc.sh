#!/usr/bin/env bash
set -euo pipefail
project=$(cd -- "$(dirname -- "$0")/.." && pwd)
[[ $(id -u) != 0 ]] || { echo '데스크톱 사용자로 실행하세요 (sudo 사용 금지).' >&2; exit 1; }
command -v uv >/dev/null || { echo 'uv가 필요합니다: https://docs.astral.sh/uv/getting-started/installation/' >&2; exit 1; }
command -v nmcli >/dev/null
command -v bluetoothctl >/dev/null
nmcli -g connection.uuid connection show Hotspot >/dev/null
nmcli -g GENERAL.STATE device show wlp0s20f3 >/dev/null
install_dir="$HOME/.local/share/pc-hotspot"
state_dir="$HOME/.local/state/pc-hotspot"
bin_dir="$HOME/.local/bin"
unit_dir="$HOME/.config/systemd/user"
mkdir -p "$install_dir" "$state_dir" "$bin_dir" "$unit_dir"
chmod 700 "$state_dir"
# Keep the original F9 only once, across reinstallations.
if [[ -e "$bin_dir/f9-action" && ! -e "$state_dir/f9-action.original" ]]; then
    cp -p "$bin_dir/f9-action" "$state_dir/f9-action.original"
fi
if [[ ! -e "$state_dir/installation.json" ]]; then
    python3 - "$state_dir/installation.json" "$bin_dir/f9-action" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({'f9_existed': pathlib.Path(sys.argv[2]).exists()}))
PY
fi
systemctl --user stop pc-hotspot.service 2>/dev/null || true
install -m 644 "$project/pc/hotspot_control.py" "$project/pc/ble_server.py" "$project/pc/requirements.txt" "$install_dir/"
uv venv --allow-existing "$install_dir/venv" --python /usr/bin/python3
uv pip install --python "$install_dir/venv/bin/python" -r "$install_dir/requirements.txt"
install -m 755 "$project/pc/f9-action" "$bin_dir/f9-action"
install -m 755 "$project/pc/pc-hotspot" "$bin_dir/pc-hotspot"
cat > "$unit_dir/pc-hotspot.service" <<UNIT
[Unit]
Description=PC Hotspot BLE control
StartLimitIntervalSec=0

[Service]
Type=simple
ExecStart="%h/.local/share/pc-hotspot/venv/bin/python" "%h/.local/share/pc-hotspot/ble_server.py"
Restart=always
RestartSec=5
TimeoutStopSec=10
UMask=0077
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=%h/.local/state %t
Environment=PYTHONDONTWRITEBYTECODE=1

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now pc-hotspot.service
sleep 2
systemctl --user is-active --quiet pc-hotspot.service
"$bin_dir/pc-hotspot" status
printf '%s\n' '설치 완료. 최초 등록: pc-hotspot enroll' '복구: scripts/restore-pc.sh'
