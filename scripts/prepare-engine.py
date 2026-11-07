"""下载或复制固定版本核心组件，摘要匹配后才交给Android构建。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / 'android-cloud-bridge/engine.lock.json').read_text(encoding='utf-8'))
parser = argparse.ArgumentParser()
parser.add_argument('--file', type=Path, help='使用从Release获得的本地组件，仍校验摘要')
args = parser.parse_args()
if args.file:
    blob = args.file.read_bytes()
else:
    with urllib.request.urlopen(lock['url'], timeout=60) as response:
        blob = response.read(20 * 1024 * 1024 + 1)
if len(blob) > 20 * 1024 * 1024 or hashlib.sha256(blob).hexdigest() != lock['sha256']:
    raise SystemExit('核心组件摘要不匹配，未安装')
target = root / 'android-cloud-bridge/app/src/main/jniLibs/arm64-v8a/libqdapi_engine.so'
target.parent.mkdir(parents=True, exist_ok=True)
pending = target.with_suffix('.pending')
pending.write_bytes(blob)
os.replace(pending, target)
print('固定版本arm64引擎摘要通过；Go源码及该二进制不适用本仓库MIT')
