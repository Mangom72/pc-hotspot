"""Shared, bounded, non-queuing NetworkManager control for F9 and BLE."""
from contextvars import ContextVar
import fcntl
import json
import os
from pathlib import Path
import subprocess
import time
from contextlib import contextmanager

HOTSPOT_NAME = 'Hotspot'
WIFI_DEVICE = 'wlp0s20f3'
# Stable across desktop keybind and systemd environments: one user, one lock.
STATE_DIR = Path.home() / '.local/state/pc-hotspot'
WIDGET_DIR = Path(os.environ.get('XDG_STATE_HOME', Path.home() / '.local/state')) / 'gfn-autologin-linux'
WIDGET_STATE = WIDGET_DIR / 'hotspot-widget.json'
WIDGET_LOCK = WIDGET_DIR / 'hotspot-widget.lock'
WIDGET_SCRIPT = Path.home() / '.local/share/gfn-autologin-linux/hotspot_widget.py'

COMMAND_DEADLINE = ContextVar('hotspot_command_deadline', default=None)

class ControlError(Exception):
    def __init__(self, code, detail):
        self.code = code
        super().__init__(detail)

# Protocol error codes: OK=0, BUSY=1, OTHER_WIFI=2, NM_FAILED=3.
@contextmanager
def control_lock():
    STATE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (STATE_DIR / 'control.lock').open('a+') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ControlError(1, '다른 핫스팟 요청 처리 중입니다. 다시 누르세요.')
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)

def nm(*args):
    try:
        deadline = COMMAND_DEADLINE.get()
        remaining = deadline - time.monotonic() if deadline is not None else 30
        if remaining <= 0:
            raise ControlError(3, '핫스팟 요청 처리 시간 초과')
        mutation = args[:2] in (('connection', 'up'), ('connection', 'down'))
        timeout = min(30 if mutation else 5, remaining)
        return subprocess.run(['nmcli', '--wait', str(max(1, min(25, int(timeout)))), *args], check=True,
                              capture_output=True, text=True, timeout=timeout).stdout.strip()
    except (OSError, subprocess.SubprocessError) as exc:
        raise ControlError(3, 'NetworkManager 명령 실패 또는 시간 초과') from exc

def current_wifi_connection():
    return nm('-g', 'GENERAL.CONNECTION', 'device', 'show', WIFI_DEVICE)

def current_state():
    # UUID avoids confusing another active profile with the same display name.
    active = nm('-g', 'GENERAL.CON-UUID', 'device', 'show', WIFI_DEVICE)
    profile = nm('-g', 'connection.uuid', 'connection', 'show', HOTSPOT_NAME)
    return active == profile and bool(profile)

def show_widget(title, detail='', *, tone='info', expires=0, countdown_until=0,
                visible=True, dismiss_on_expiry=False):
    WIDGET_DIR.mkdir(parents=True, exist_ok=True)
    payload = dict(title=title, detail=detail, tone=tone, visible=visible,
                   expires_at=time.time() + expires if expires else 0,
                   countdown_until=countdown_until, dismiss_on_expiry=dismiss_on_expiry)
    temporary = WIDGET_STATE.with_name(f'.{WIDGET_STATE.name}.{os.getpid()}.tmp')
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding='utf-8')
    os.replace(temporary, WIDGET_STATE)
    if not visible or not WIDGET_SCRIPT.exists():
        return
    try:
        with WIDGET_LOCK.open('a+') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return
        subprocess.Popen(['/usr/bin/python3', str(WIDGET_SCRIPT)], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError:
        pass

def restore_hotspot_status():
    if current_state():
        show_widget('핫스팟 켜짐', 'archHotspot · F9 두 번으로 끄기', tone='ok')
    else:
        show_widget('', visible=False)

def _set_locked(enabled):
    active = current_state()
    if active == enabled:
        restore_hotspot_status()
        return active
    if enabled:
        if current_wifi_connection() not in ('', '--'):
            raise ControlError(2, '다른 Wi-Fi에 연결되어 있어 핫스팟을 켤 수 없습니다.')
        if nm('-g', '802-11-wireless.mode', 'connection', 'show', HOTSPOT_NAME) != 'ap':
            raise ControlError(3, 'Hotspot 프로필이 AP 모드가 아닙니다.')
        nm('connection', 'up', HOTSPOT_NAME, 'ifname', WIFI_DEVICE)
    else:
        # Only deactivate the expected profile; never disconnect another Wi-Fi.
        profile = nm('-g', 'connection.uuid', 'connection', 'show', HOTSPOT_NAME)
        nm('connection', 'down', 'uuid', profile)
    actual = current_state()
    if actual != enabled:
        raise ControlError(3, '요청 후 실제 핫스팟 상태가 일치하지 않습니다.')
    if actual:
        restore_hotspot_status()
    else:
        show_widget('핫스팟 꺼짐', 'F9 두 번으로 다시 켜기', tone='idle', expires=10,
                    dismiss_on_expiry=True)
    return actual

def _operate(enabled):
    with control_lock():
        token = COMMAND_DEADLINE.set(time.monotonic() + 40)
        try:
            return _set_locked(not current_state() if enabled is None else enabled)
        finally:
            COMMAND_DEADLINE.reset(token)

def set_hotspot(enabled):
    return _operate(enabled)

def toggle_hotspot():
    return _operate(None)
