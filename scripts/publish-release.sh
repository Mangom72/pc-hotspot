#!/usr/bin/env bash
set -euo pipefail
project=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project"
[[ -z $(git status --porcelain) ]] || { echo '릴리스할 변경을 먼저 커밋하세요.' >&2; exit 1; }
version=$(python3 - <<'PY'
import re
print(re.search(r'versionName\s*=\s*"([^"]+)"', open('android/app/build.gradle.kts').read()).group(1))
PY
)
"$project/scripts/build-apk.sh"
# The release workflow uses the tag's immutable source and the same signing key.
git push origin main
git tag "v$version"
git push origin "v$version"
printf '%s\n' "v$version 태그를 게시했습니다. GitHub Actions에서 테스트·서명·릴리스를 진행합니다."
