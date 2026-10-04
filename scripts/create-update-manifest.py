#!/usr/bin/env python3
"""Generate the update feed from a signed APK and Gradle version metadata."""
import hashlib
import json
from pathlib import Path
import re
import sys

project = Path(__file__).resolve().parents[1]
source = (project / 'android/app/build.gradle.kts').read_text()
def value(pattern):
    match = re.search(pattern, source)
    if not match: raise ValueError(f'Missing version metadata: {pattern}')
    return match.group(1)
version = value(r'versionName\s*=\s*"([^"]+)"')
code = int(value(r'versionCode\s*=\s*(\d+)'))
minimum = int(value(r'minSdk\s*=\s*(\d+)'))
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else project / f'artifacts/pc-hotspot-{version}.apk'
output = apk.parent / 'update.json'
output.write_text(json.dumps(dict(versionName=version, versionCode=code, minSdk=minimum,
    url=f'https://github.com/Mangom72/pc-hotspot/releases/download/v{version}/{apk.name}',
    size=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest()), indent=2) + '\n')
print(output)
