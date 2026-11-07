# 自建云端完整教程（Linux amd64 / systemd）

本教程不包含维护者的真实服务器、域名、账号或凭据。示例统一`qdapi.example.com`，**必须替换为自己的域名**。发行包提供授权Go核心二进制，Go源码不公开；公开源码压缩包不能重建核心。先读仓库许可边界、免责声明和隐私说明。

## 1. 前提与下载

准备有sudo权限的Linux amd64服务器、独立域名、可正常访问的HTTPS及你有权操作的起点账号。以下以Debian/Ubuntu的systemd+Nginx为例，其他发行版需调整包管理。若要微信绑定，还需具备对应能力的公众号；并非所有公众号类型均拥有相同OAuth/模板消息权限，以微信后台和官方文档为准。

```bash
uname -m  # 本包应为x86_64；不能在arm64服务器直接运行
sudo apt-get update
sudo apt-get install -y ca-certificates curl python3 nginx certbot
mkdir -m 700 qdapi-release
cd qdapi-release
```

从本仓库Releases下载`QDAPI-Web-Bridge-v0.1.14-linux-amd64.tar.gz`和外层`SHA256SUMS.txt`。本轮Web核心仍为0.1.13，仅桥接与来源文档更新。不要从聊天附件或未知镜像获取。对照GitHub发布页/可信渠道的摘要后校验：

```bash
sha256sum --ignore-missing -c SHA256SUMS.txt
tar -xzf QDAPI-Web-Bridge-v0.1.14-linux-amd64.tar.gz
cd QDAPI-Web-Bridge-v0.1.14-linux-amd64
sha256sum -c SHA256SUMS
```

校验只能证明与所信任摘要一致，不证明下载源没有被入侵。包中不含数据库、真实环境、签名密钥或第三方原APP。

## 2. 初次安装与独立密钥

先把域名A/AAAA指向自己的服务器。只发布实际可用的IPv6记录，不要复制任何他人IP。确认自己的SSH端口已经放行，再按需要放行80/443；不要盲目启用防火墙导致断开SSH，**18080始终只监听127.0.0.1**。

```bash
sudo bash deploy/install.sh "$PWD" https://qdapi.example.com
```

把示例换成真实域名，脚本会拒绝示例和已有部署。交互输入至少12字节管理员密码两次；密码通过标准输入交给bcrypt工具，不放命令行参数。脚本生成独立32字节随机AES密钥，创建最小权限用户qdapi及以下目录：

| 位置 | 用途 |
| --- | --- |
| `/opt/qdapi/releases/v0.1.13/` | 程序版本 |
| `/opt/qdapi/current` | 当前版本符号链接 |
| `/opt/qdapi/data/qdapi.db` | SQLite及账号数据，qdapi可写 |
| `/etc/qdapi/qdapi.env` | 域名、密钥、密码哈希及可选公众号配置，root:qdapi 0640 |
| `/etc/systemd/system/qdapi.service` | systemd最小权限服务 |

示例环境见`deploy/qdapi.env.example`。`QDAPI_PUBLIC_URL`必须为自己的HTTPS根地址；公众号登录链接、OAuth回调、设备连接及失效通知都基于它，不能仍指向维护者或示例域名。不要把`QDAPI_ENCRYPTION_KEY`换成示例，**已有数据时也不能随意重生成，否则旧凭据无法解密**。

```bash
sudo systemctl status qdapi.service --no-pager
curl -fsS http://127.0.0.1:18080/healthz
sudo ss -lntp  # 确认18080不是0.0.0.0或公网地址
```

可选地只导入自己保存在服务器私有目录的配置，设置`QDAPI_IMPORT_CONFIG`到该文件；不要上传配置到GitHub，也不要在公网HTTP状态下录入凭据。没有账号时服务仍可健康启动。

## 3. HTTPS反向代理

设置自己的域名变量，仅使用自己确认的正常域名字符；以下文件名`qdapi.conf`如已存在请先备份，不覆盖其他站点。

```bash
DOMAIN=qdapi.example.com
sudo install -d -m 755 /var/www/qdapi-acme /var/www/qdapi-verification
sed "s/qdapi.example.com/$DOMAIN/g" deploy/bootstrap-http.example.conf | sudo tee /etc/nginx/conf.d/qdapi-bootstrap.conf >/dev/null
sudo nginx -t && sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/qdapi-acme -d "$DOMAIN"
```

确认DNS、80端口和证书成功，按Certbot提示提供自己的联系信息。若服务器已有其他HTTP站点，不要停掉整个Nginx；只添加自己的虚拟主机。

```bash
sed "s/qdapi.example.com/$DOMAIN/g" deploy/nginx.example.conf | sudo tee /etc/nginx/conf.d/qdapi.conf >/dev/null
sudo mv /etc/nginx/conf.d/qdapi-bootstrap.conf /etc/nginx/qdapi-bootstrap.conf.disabled
sudo nginx -t && sudo systemctl reload nginx
curl -fsS "https://$DOMAIN/healthz"
sudo certbot renew --dry-run
```

配置中只有本地HTTP回源，外部使用HTTPS，长任务读取超时1500秒。模板直接面向公网；若另加CDN/Tunnel，必须按你实际部署配置可信代理和来源IP，不能无条件信任任意客户端传来的CF/Forwarded头。不要把数据库、环境或备份目录映射到Nginx根目录。

## 4. 管理员登录与TOTP

浏览器打开自己的HTTPS首页，输入安装密码，按页面绑定自己的TOTP（支持标准验证器）。保存恢复/迁移材料到安全位置；校准服务器和手机时间。不要把管理员密码、TOTP二维码/种子发给维护者、贴到Issue或截图公开。

账号通过页面上传/导入或APP手动确认后连接。服务器会验证必要登录信息，保存所选任务设置。七项在同一轮检查；阅读积分额外可选。确认任务日志及官方回读结果，不把“HTTP200”或“本轮已跳过”当作奖励到账。

## 5. 可选微信绑定

没有微信功能时四项环境保持空，不影响自建Web/APP手动连接。要启用时，以自己公众号后台实际支持项为准：

1. 在`/etc/qdapi/qdapi.env`设置自己的`QDAPI_WECHAT_APP_ID`、`QDAPI_WECHAT_APP_SECRET`、自行生成的`QDAPI_WECHAT_TOKEN`；模板通知按权限设置`QDAPI_WECHAT_TEMPLATE_ID`。
2. 公众号“服务器配置”URL为`https://你的域名/wechat/notify`，Token与环境完全一致。本实现按明文XML验签/消息接收；**不宣称支持微信消息AES安全模式**，不能选择未适配模式后假装通过。
3. 如有OAuth权限，将网页授权域名设置为自己的主机名，不带`https://`或路径；实际回调是`https://你的域名/wechat/callback`。相关网页/JS业务域名按后台要求填写。
4. 后台给出的`MP_verify_*.txt`自行下载到`/var/www/qdapi-verification/`，模板只公开此类验证文本；不要复制本项目维护者的验证文件名、Token或公众号ID。
5. 修改前备份环境，重启`qdapi.service`，以自己的微信发送“上传”，核对回复链接确实是自己的域名；用自己的测试账号完成绑定/重传，确认原绑定未丢失。

微信AppSecret只放服务器私有环境，绝不内置APK。OAuth和消息权限、服务器IP白名单及后台菜单可能随微信调整，请按微信官方文档和后台实际错误处理，不在日志中输出access_token。

## 6. APP与自己云端配对

公共APK为无预设服务的本机模式；要连接自己的云端，按`android-cloud-bridge/README.md`使用`-PcloudServer=https://你的域名`构建同版本模块。核对构建地址与`QDAPI_PUBLIC_URL`一致，保管自己的签名密钥。

保留从原项目自行获取的QDReadHook，在兼容框架中同时启用桥接，作用域仅起点。APP无需上传就能本机检查七项。用户主动点击“一键同步并领取福利”并确认说明后才上传登录凭据；取消不上传。前后台能实际执行时APP优先；关闭/系统冻结或不能执行，许可约90秒过期，再最多30秒云端兜底，保留整点调度。

解除设备连接不等于暂停云端；切换到另一云端前应先暂停原托管，保留日志和身份核对，避免失去协调后两端并跑。不要共享设备令牌，也不要为测试打开屏幕常亮或关闭系统冻结。

## 7. 日志与验收清单

- Web运行记录展开，核对七项+阅读积分各组结果；日读、周读800/1200、更新提醒、PK均应有资格日志。APP原日志分享入口应非空，待传日志只补传记录不重做任务。
- 选择一个已手动上传的测试账号：APP可执行时确认云端让路；自然冻结后确认一次`app-offline`接管；重新打开APP后确认恢复租约，云端不再重复启动。不要通过强写生产租约或反复手动领奖伪造验收。
- 已完成/冷却/未达标、免费次数0、非周日兑换均正常跳过。当前无资格时不能声称已经真实领取；要结合官方余额、标志及接口日志判断。
- 登录失效应退出活动调度并清空失效凭据，**原微信绑定保留**；网络故障/验证码/402等不应直接移除。
- `journalctl -u qdapi.service --since today`仅在私有服务器查看；分享前审查凭据、账号、IP、域名和原模块调试内容，不公开整库。

## 8. 备份、升级与回滚

先选择无任务执行的维护窗口，避免中断正在运行的轮次。把数据库、环境、旧程序和必要服务文件备份到**不对公网开放、仅管理员可读**的位置；环境密钥与数据库分开保存。SQLite运行时不要只复制主db而遗漏WAL，使用SQLite备份API：

```bash
sudo python3 - <<'PY'
import sqlite3,pathlib,datetime,os
root=pathlib.Path('/var/backups/qdapi')/datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
root.mkdir(mode=0o700,parents=True,exist_ok=False)
with sqlite3.connect('file:/opt/qdapi/data/qdapi.db?mode=ro',uri=True) as source, sqlite3.connect(root/'qdapi.db') as dest:
    source.backup(dest)
    assert dest.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
os.chmod(root/'qdapi.db',0o600)
print('数据库快照完成；请另行安全备份环境/密钥及旧版本路径，切勿公开备份内容')
PY
```

新版本解压后先校验SHA256及兼容性说明，安装到新的`/opt/qdapi/releases/版本/`，原子切换`current`并重启服务；不要覆盖`/opt/qdapi/data`、环境、微信绑定或已保存任务选项。v0.1.13升级前须补齐自己的`QDAPI_PUBLIC_URL`，不能沿用没有该项的旧环境而生成示例域名链接。

健康检查、版本、任务日志、活动账号数、微信绑定和任务选项均核对后才算完成。失败时把`current`指回旧程序并重启；**程序回退默认不恢复旧业务库**，避免覆盖升级后的新绑定/任务记录。需要回退数据库时，先保护现场并明确数据丢失边界，不可盲目覆盖。

## 9. 常见问题

- `Exec format error`：架构不匹配；本发行包仅Linux amd64，本机引擎仅Android arm64。
- 502或健康失败：检查`systemctl status`、私有日志、数据库目录权限及环境格式；不要临时公开18080绕过HTTPS。
- 微信链接到了示例或旧站：检查`QDAPI_PUBLIC_URL`，重启后重新获取一次性链接，旧链接不可复用。
- APP称未配置云端：公共APK本来不内置个人地址，需要部署者按教程构建，不是自动上传功能失效。
- 后台停止：系统冻结时由已授权云端兜底，不能仅以进程存在判断APP还可执行。
- 无法解密：不要重生成原加密密钥；按安全备份恢复正确环境，并保护现有库。

若无法确认源项目授权、账号授权或数据处理范围，应先核实相应条件。教程及免责声明不能代替这些条件。
