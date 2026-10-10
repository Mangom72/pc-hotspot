# PC 핫스팟 빠른 설정 타일

삼성/Android 10 이상 폰에서 **빠른 설정 → PC 핫스팟**을 눌러 이 PC의 기존 `Hotspot` 프로필을 켜고 끕니다. BLE로 제어하므로 핫스팟이 꺼져 있어도 연결할 수 있습니다. 설치용 APK는 [GitHub 최신 릴리스](https://github.com/Mangom72/pc-hotspot/releases/latest)입니다.

## 최초 사용

이 PC에는 서비스와 F9 연동을 설치했습니다. 폰 등록은 아직 하지 않았습니다.

1. APK를 폰으로 옮겨 설치합니다. 파일을 여는 앱에 ‘알 수 없는 앱 설치’ 허용이 필요할 수 있습니다. USB 디버깅을 사용하는 경우 `adb install -r artifacts/pc-hotspot-1.0.4.apk`로 설치할 수 있습니다.
2. PC와 폰의 블루투스를 켭니다. PC 터미널에서 `~/.local/bin/pc-hotspot enroll`을 실행합니다. 등록 시간은 120초입니다.
3. 폰 앱을 열고 **근처 기기 권한 허용 → PC 검색 → PC Hotspot 선택**을 누릅니다. Android 10·11에서는 검색 중 위치 권한과 위치 설정도 필요합니다.
4. 폰과 PC 터미널에 **같은 6자리 번호**가 보이는지 확인합니다. 폰에서 페어링을 승인하고 PC 터미널에는 `yes`를 입력합니다. 번호가 다르면 승인하지 않습니다. PC 승인 응답 시간은 최대 60초입니다.
5. 앱의 **PC 등록 완료**와 터미널의 **등록 완료**를 확인합니다. 인증된 상태 조회가 성공하면 PC가 폰을 저장하고 등록 모드를 자동으로 닫습니다.
6. 앱에서 **빠른 설정 타일 추가**를 누릅니다. 또는 빠른 설정창을 두 번 내려 편집에서 **PC 핫스팟**을 추가합니다.
7. 이후에는 빠른 설정창을 열어 상태를 보고 타일을 누릅니다. 잠겨 있으면 잠금을 해제한 뒤 실행합니다. 앱 화면을 열어 둘 필요는 없습니다.

타일 모양·배치·색상은 One UI가 결정합니다. 타일은 ‘켜짐 / 꺼짐 / 처리 중 / 연결 안 됨’을 표시합니다. 오류 이유는 타일 부제목 또는 알림 메시지에 표시하며, 앱의 **등록된 PC 상태 확인**에서도 확인할 수 있습니다. 범위 밖이거나 블루투스가 꺼졌으면 실패하고, 나중에 자동으로 명령을 실행하지 않습니다. 상태 조회 자체에는 PC 핫스팟의 인터넷 연결이 필요하지 않습니다.

## 핫스팟 자동 연결 (1.0.4부터)

앱의 **연결 → 핫스팟에 자동 연결**을 켜고 `archHotspot` 이름과 비밀번호를 한 번 저장합니다. Android의 Wi-Fi 제안 허용 창이 나오면 허용합니다. 등록된 PC의 BLE 상태 알림을 백그라운드에서 받아, 핫스팟이 켜지면 공식 Wi-Fi 제안 API에 연결 후보를 등록합니다. 자동 연결 알림에서 상태 확인, Wi-Fi 설정 열기, 중지가 가능합니다.

Android가 연결할 Wi-Fi를 최종 선택합니다. 이미 다른 Wi-Fi에 잘 연결되어 있으면 유지할 수 있으므로, 즉시 강제 전환을 보장하지 않습니다. 폰과 PC의 Bluetooth, 폰의 Wi-Fi가 켜져 있어야 합니다. 재부팅 후에는 앱을 한 번 열어 수신을 재개합니다. 현재 PC 등록은 기존과 같이 폰 한 대를 지원합니다.

**설정** 화면에서 업데이트 자동 확인을 켜거나 끄고, 마지막 확인 시도와 설치 준비 상태를 확인할 수 있습니다. 화면은 삼성의 공개 One UI 지침과 Android 시스템 여백 지침을 참고하며, 시스템 다크 모드와 글자 크기를 따릅니다. 세부 동작은 [설계 문서](docs/auto-connect-design.md)를 참고하세요.

## 자동 업데이트 (1.0.2부터)

앱은 실행할 때와 Android 백그라운드 작업으로 약 6시간마다 [GitHub 최신 릴리스](https://github.com/Mangom72/pc-hotspot/releases/latest)를 확인합니다. Android 배터리/백그라운드 정책에 따라 주기는 늦어질 수 있습니다. 인터넷 연결이 필요하며, 확인 실패가 BLE 핫스팟 조작을 막지 않습니다.

새 버전이면 APK를 자동 다운로드하고 크기·SHA-256·패키지 이름·증가한 버전 코드·현재 앱과 동일한 서명을 검증합니다. 검증한 파일만 앱 내부 저장소에 보관하고 설치 알림을 띄웁니다. 앱의 **업데이트 알림 허용**을 한 번 눌러 알림 권한을 허용하세요. 알림을 허용하지 않아도 앱을 열거나 **업데이트 확인**을 누르면 확인할 수 있습니다.

일반 Android 앱은 무인 설치를 보장할 수 없으므로 **알림/앱 → 설치 확인**은 필요합니다. 최초 업데이트 설치 때 PC 핫스팟 앱의 **이 출처 허용**을 켠 뒤 **다운로드한 업데이트 설치**를 다시 누르세요. 이후에는 새 APK를 이메일로 옮길 필요가 없습니다. 업데이트해도 PC 등록 정보는 유지됩니다. 인터넷 업데이트와 BLE 핫스팟 명령은 서로 독립적입니다.

소스와 APK는 사용자 요청에 따라 [Mangom72/pc-hotspot](https://github.com/Mangom72/pc-hotspot)에 공개합니다. GitHub 계정 토큰과 Wi-Fi 비밀번호는 APK에 포함하지 않습니다. 서명 개인 키는 저장소 파일에 넣지 않고 GitHub Actions의 암호화된 Secret으로만 설정했습니다.

새 수정 버전을 배포하려면 `android/app/build.gradle.kts`의 `versionCode`를 증가시키고 `versionName`을 변경해 커밋한 뒤:

```bash
./scripts/publish-release.sh
```

스크립트는 로컬 빌드·린트·업데이트 정책 테스트를 실행한 후 `v<versionName>` 태그를 푸시합니다. GitHub Actions는 태그의 소스를 다시 테스트하고 동일 키로 서명한 APK, SHA-256, `update.json`을 릴리스에 게시합니다. APK와 버전 정보가 함께 릴리스된 뒤 앱이 새 버전을 인식합니다. 서명 키나 Actions Secret을 삭제·교체하면 기존 앱의 업데이트가 불가능해지므로 보존하세요.

## PC 설치 및 복구

새로 설치하거나 소스를 업데이트할 때:

```bash
./scripts/install-pc.sh
systemctl --user status pc-hotspot.service
~/.local/bin/pc-hotspot status
```

일반 데스크톱 사용자로 실행합니다. `uv`, Python 3, NetworkManager(`nmcli`), BlueZ(`bluetoothctl`) 및 BLE peripheral/GATT 지원 어댑터가 필요합니다. 기존 `Hotspot` AP 프로필과 `wlp0s20f3` 장치를 사용하며 새 Wi-Fi 프로필을 만들지 않습니다. 이 PC에는 필요한 조건이 확인되어 있습니다.

서비스는 `systemd --user`의 `default.target`에 등록되며, 이 PC는 `Linger=yes`여서 부팅 때 사용자 서비스가 시작될 수 있습니다. 블루투스가 꺼져 있거나 BlueZ가 아직 준비되지 않으면 5초마다 서비스 시작을 재시도합니다. 이 재시도는 서비스 등록에만 적용되고, 폰의 핫스팟 명령은 재실행하지 않습니다. 다른 PC의 로그아웃/부팅 환경에서는 NetworkManager 권한도 확인해야 합니다.

기존 F9 원본은 `~/.local/state/pc-hotspot/f9-action.original`에 한 번만 백업됩니다. F9 한 번은 GFN 로그인, 두 번은 핫스팟 전환, 세 번째 연속 입력은 무시하며 기존 상태 위젯을 사용합니다. 폰과 F9가 공통 파일 잠금을 사용하고, 처리 중인 요청과 겹치는 요청은 대기열에 넣지 않고 거절합니다. 다른 Wi-Fi에 연결되어 있으면 켜기를 거절하며, 끄기는 기존 핫스팟 프로필에만 적용됩니다.

복구:

```bash
./scripts/restore-pc.sh
```

서비스를 중지·해제하고 기존 F9를 복구하며 PC의 폰 등록 정보를 지웁니다. 원본 백업, 소스, APK 서명 키는 보존합니다. 핫스팟의 현재 상태를 자동으로 변경하지 않습니다. 폰과 PC의 블루투스 설정에서 페어링은 직접 삭제하세요. 복구 후 재설치도 가능합니다.

## 등록 변경 및 문제 해결

```bash
~/.local/bin/pc-hotspot close     # 등록 모드 즉시 닫기
~/.local/bin/pc-hotspot revoke    # 현재 폰의 제어 권한 해제
~/.local/bin/pc-hotspot status
journalctl --user -u pc-hotspot.service -n 50 --no-pager
systemctl --user restart pc-hotspot.service
```

한 PC에 폰 한 대를 등록합니다. 폰을 바꾸거나 재등록하려면 PC에서 `revoke`를 실행하고, 양쪽 블루투스 설정에서 이전 페어링을 삭제한 뒤 앱의 **이 폰의 PC 등록 지우기**를 누릅니다. 기존 페어링만 남은 상태에서는 PC가 새 등록의 번호 확인을 받을 수 없으므로 페어링 삭제가 필요합니다. 페어링 번호 확인만 끝내고 앱의 등록 완료가 안 뜨는 경우에도 동일하게 정리하고 다시 등록하세요.

다른 Wi-Fi 오류는 해당 Wi-Fi를 PC에서 직접 해제한 뒤 다시 누릅니다. NetworkManager 오류는 `nmcli general permissions`와 서비스 로그를 확인합니다. 타일 동작 중 연결이 끊기면 앱은 한 번 재연결해 **실제 상태만 조회**합니다. 결과를 확인하지 못했으면 직접 상태를 확인한 후 새로 누릅니다.

AdGuard와 NordVPN을 설정하거나 끄는 코드, Tailscale 의존성, 외부 인터넷 제어, PC 전원 켜기 기능은 없습니다. 자동 연결 수신은 사용자가 켠 경우에만 실행합니다. 인터넷 공유에 대한 기존 VPN/방화벽 동작은 사용자의 기존 PC 설정을 따릅니다.

## APK 빌드

```bash
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk ./scripts/build-apk.sh
```

JDK 17, Android SDK Platform 36, Build Tools 36.0.0이 필요합니다. Gradle wrapper는 8.13(SHA-256 검증), Android Gradle Plugin은 8.13.2, Kotlin은 2.2.21로 고정했습니다. 이 PC의 기존 도구 경로는 빌드 스크립트의 기본값으로 설정되어 있습니다. 빌드는 release APK와 Android lint를 실행하고 APK 서명 및 SHA-256을 검증합니다.

서명 키·비밀번호는 저장소 밖 `~/.local/share/pc-hotspot-signing/`에 저장합니다. 같은 앱의 업데이트를 계속 설치하려면 이 디렉터리를 안전하게 보존하세요. APK에는 서명 개인 키나 비밀번호가 들어가지 않습니다. 키를 잃으면 기존 앱을 제거하고 새 APK로 다시 등록해야 합니다.

## 구현 및 검증

- [pc/hotspot_control.py](pc/hotspot_control.py): 공통 NetworkManager 제어·잠금·위젯
- [pc/ble_server.py](pc/ble_server.py): BlueZ GATT·짧은 등록 세션·폰 허용 목록
- [android/app/src/main/java/kr/pc/hotspot](android/app/src/main/java/kr/pc/hotspot): Kotlin 앱·BLE 연결·TileService
- [docs/protocol.md](docs/protocol.md): 프로토콜 및 보안 경계
- [docs/verification.md](docs/verification.md): 완료된 검증과 실제 폰에서 확인할 항목

```bash
uv venv .venv --python /usr/bin/python3
uv pip install --python .venv/bin/python -r pc/requirements.txt
.venv/bin/python -m unittest discover -s tests -v
```

Android [Quick Settings 공식 안내](https://developer.android.com/develop/ui/views/quicksettings-tiles), [Bluetooth 권한 공식 안내](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), BlueZ [GATT 공식 API](https://github.com/bluez/bluez/blob/master/doc/org.bluez.GattCharacteristic.rst)와 [Agent 공식 API](https://github.com/bluez/bluez/blob/master/doc/org.bluez.Agent.rst)를 기준으로 구현했습니다. 실제 삼성 폰의 페어링·타일·AdGuard/VPN/이어폰 동시 사용 검증은 아직 완료하지 않았습니다.
