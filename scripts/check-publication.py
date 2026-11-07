"""仅检查公开Git清单，不打印匹配到的潜在敏感值；不是全面安全审计。"""
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
blocked_suffixes = {'.go', '.apk', '.so', '.db', '.pem', '.key', '.jks', '.keystore', '.log', '.png', '.jpg', '.jpeg', '.jar'}
bad = []
for name in filter(None, tracked):
    path = Path(name)
    if path.suffix in blocked_suffixes or path.name in {'go.mod', 'go.sum', 'config.json', 'local.properties'} or name.startswith(('.backup/', 'work/', 'build/')):
        bad.append(name + ':不应公开的文件类型/目录')
        continue
    if path.name.endswith('.env'):
        bad.append(name + ':真实环境文件')
        continue
    blob = (root / path).read_bytes()
    if b'\x00' in blob:
        bad.append(name + ':未预期二进制')
        continue
    text = blob.decode('utf-8')
    patterns = [r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', r'gh[pousr]_[A-Za-z0-9]{30,}', r'github_pat_[A-Za-z0-9_]{30,}', r'https?://[^\s/@]+:[^\s/@]+@', r'(?i)(?:ywkey|sdksign|ibex)\s*[=:]\s*["\']?[A-Za-z0-9+/=]{32,}']
    if any(re.search(pattern, text) for pattern in patterns):
        bad.append(name + ':可能包含秘密，请私下核查')
if bad:
    raise SystemExit('\n'.join(bad))
print(f'公开Git清单检查通过：{len(list(filter(None, tracked)))}文件；无Go源码、账号数据库、环境、私钥、生产截图或构建二进制')
