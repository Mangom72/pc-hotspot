#!/usr/bin/env bash
set -euo pipefail
[[ $(id -u) != 0 ]] || { echo '데스크톱 사용자로 실행하세요.' >&2; exit 1; }
state_dir="$HOME/.local/state/pc-hotspot"
bin_dir="$HOME/.local/bin"
# Explicit recovery restores the prior F9 and removes the BLE service/allowlist.
systemctl --user disable --now pc-hotspot.service 2>/dev/null || true
rm -f "$HOME/.config/systemd/user/pc-hotspot.service"
systemctl --user daemon-reload
if [[ -f "$state_dir/f9-action.original" ]]; then
    cp -p "$state_dir/f9-action.original" "$bin_dir/f9-action"
elif [[ -f "$state_dir/installation.json" ]]; then
    python3 - "$state_dir/installation.json" "$bin_dir/f9-action" <<'PY'
import json, pathlib, sys
if not json.loads(pathlib.Path(sys.argv[1]).read_text())['f9_existed']:
    pathlib.Path(sys.argv[2]).unlink(missing_ok=True)
PY
fi
rm -f "$bin_dir/pc-hotspot" "$state_dir/phones.json"
printf '%s\n' '기존 F9 복구 완료. 원본 백업과 APK 서명 키는 보존했습니다.' '폰과 PC의 블루투스 설정에서 PC Hotspot 페어링도 직접 삭제하세요.'
