# 独立桥接APP构建与使用

桥接版本0.1.11（versionCode12），当前提供arm64-v8a。原模块为xihan123的[QDReadHook](https://github.com/Xposed-Modules-Repo/cn.xihan.qdds)，需用户自行从原项目安装并启用，本仓库不打包原模块或起点APP。

## 直接使用公共APK

从本仓库Releases下载并核对SHA256。公共APK不内置维护者的服务器地址；可本机运行七项及额外积分。尚未配置云端时，手动上传会明确提示，不会偷偷连维护者服务。已经关联其他云端的账号需先正确停止/迁移原托管，不能用断开协调的本机执行与旧云端并跑。

LSPosed/Xposed框架中同时启用原QDReadHook及本桥接，作用域只选择自己的起点读书。重新打开起点，进入“设置 → QD模块设置 → 云端托管”。来源说明在该页“项目来源与许可”中，独立桥接APP主页也能打开。不替换原模块关于页，不改其其他选项。

## 为自建云端构建

需要JDK17、Gradle8.9、Android SDK Platform35和对应Build Tools。这里只能构建桥接层，**没有Go源码，不能自行重建Go核心**。

1. 先按仓库`docs/DEPLOYMENT.md`部署云端，获得自己的HTTPS站点，例如`https://qdapi.example.com`。
2. 从同一版本Release取`libqdapi_engine-arm64-v8a.so`及`SHA256SUMS.txt`，核对摘要后放入`app/src/main/jniLibs/arm64-v8a/libqdapi_engine.so`。不要使用来历不明的引擎。
3. 设置`ANDROID_HOME`（或未提交的local.properties中的sdk.dir），确认`java -version`和`gradle --version`。
4. 在仓库根目录运行：

```bash
gradle -p android-cloud-bridge --no-daemon assembleDebug lintDebug -PcloudServer=https://qdapi.example.com
```

Windows可使用Gradle发行版中的`bin/gradle.bat`；Linux/macOS可用`gradle`。Xposed API82从官方API仓库`https://api.xposed.info/`获取，只是`compileOnly`，不打进APK。未设置cloudServer时为空，代表本机模式；仅接受没有账号信息、路径、查询及片段的HTTPS站点根地址。

输出为`app/build/outputs/apk/debug/app-debug.apk`。debug包仅用于测试，会使用构建机的调试签名，**不是可任意覆盖其他签名版本的正式更新**。运营者应使用自己长期保管的签名密钥生成正式版本；密钥、密码、local.properties和环境文件不得提交Git。不同签名无法原位升级，不要为安装新包盲目卸载导致本机配置或日志丢失。

第一次启动、刷新、自动任务和令牌失效不自动上传凭据；云端只在用户点击同步并确认说明后接收。修改公开来源说明不得删除原作者和许可范围。测试APK、QA证书指纹、localhost QA服务配置均不得作为正式安装包发布。

## 测试

`assembleDebugAndroidTest`构建设备测试包；`projectCreditsTest=true`为来源界面只读验收，需要先解锁手机，只打开独立桥接说明对话框，不点击任务、上传或外部链接。其他设备测试含隔离QA服务或原生任务动作，请先读测试代码，不在生产账号上盲目运行。

系统冻结/关闭后不能执行不等于异常；保持系统默认，由已授权云端兜底。本项目不承诺后台永久运行，也不通过关闭系统冻结或常亮屏幕来伪造后台验收。
