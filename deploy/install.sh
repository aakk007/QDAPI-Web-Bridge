#!/usr/bin/env bash
set -euo pipefail
umask 077
test "$(id -u)" = 0 || { echo '请以root运行'; exit 1; }
bundle=$(realpath "${1:?请指定已解压的发行包目录}")
public=${2:?请指定自己的HTTPS根地址}
domain=$(python3 - "$public" <<'PY'
import re,sys,urllib.parse
u=urllib.parse.urlsplit(sys.argv[1])
assert u.scheme=='https' and u.hostname and not u.username and not u.password and not u.port and u.path in ('','/') and not u.query and not u.fragment
assert re.fullmatch(r'[A-Za-z0-9.-]+',u.hostname) and '.' in u.hostname
assert not u.hostname.endswith('.example.com'), '请使用自己的真实域名，不要直接使用示例'
print(u.hostname)
PY
)
test ! -e /etc/qdapi/qdapi.env || { echo '发现已有配置，拒绝覆盖；升级请按教程备份后进行'; exit 1; }
test ! -e /opt/qdapi/releases/v0.1.13 || { echo '目标发行目录已存在，拒绝覆盖'; exit 1; }
(cd "$bundle" && sha256sum -c SHA256SUMS)
read -r -s -p '请输入12～72字节管理员密码（不会显示）: ' password
printf '\n'
read -r -s -p '请再次输入: ' repeated
printf '\n'
test "$password" = "$repeated" || { echo '两次密码不同'; exit 1; }
hash=$(printf '%s' "$password" | "$bundle/qdapi-hashpassword")
unset password repeated
key=$(python3 -c 'import secrets,base64; print(base64.b64encode(secrets.token_bytes(32)).decode())')
if ! id qdapi >/dev/null 2>&1; then useradd --system --home /opt/qdapi --shell /usr/sbin/nologin qdapi; fi
install -d -m 755 /opt/qdapi/releases/v0.1.13
install -d -m 700 -o qdapi -g qdapi /opt/qdapi/data
install -d -m 750 -o root -g qdapi /etc/qdapi
install -m 755 "$bundle/qdapi-web" /opt/qdapi/releases/v0.1.13/qdapi-web
install -m 755 "$bundle/qdapi-hashpassword" /opt/qdapi/releases/v0.1.13/qdapi-hashpassword
test ! -e /opt/qdapi/current || { echo 'current已存在，拒绝改动旧部署'; exit 1; }
ln -s /opt/qdapi/releases/v0.1.13 /opt/qdapi/current
printf '%s\n' 'QDAPI_LISTEN=127.0.0.1:18080' "QDAPI_PUBLIC_URL=https://$domain" 'QDAPI_DATABASE=/opt/qdapi/data/qdapi.db' 'QDAPI_COOKIE_SECURE=true' "QDAPI_ENCRYPTION_KEY=$key" "QDAPI_ADMIN_PASSWORD_HASH=$hash" 'QDAPI_CLAIM_EARNED_BENEFITS=true' 'QDAPI_WECHAT_TOKEN=' 'QDAPI_WECHAT_APP_ID=' 'QDAPI_WECHAT_APP_SECRET=' 'QDAPI_WECHAT_TEMPLATE_ID=' > /etc/qdapi/qdapi.env
unset key hash
chown root:qdapi /etc/qdapi/qdapi.env
chmod 640 /etc/qdapi/qdapi.env
install -m 644 "$bundle/deploy/qdapi.service" /etc/systemd/system/qdapi.service
systemctl daemon-reload
systemctl enable --now qdapi.service
curl --fail --retry 5 --retry-connrefused http://127.0.0.1:18080/healthz
printf '\n本机服务已启动；按docs/DEPLOYMENT.md配置HTTPS后再登录，不要开放18080。\n'
