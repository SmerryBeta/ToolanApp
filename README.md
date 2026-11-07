# TOOLAN App

TOOLAN 安卓客户端（纯 Java）。TOOLAN = Tool + LAN，配套的 Go 后端在 [SmerryBeta/TOOLAN](https://github.com/SmerryBeta/TOOLAN)，手机端连上同一局域网即可接入内网文件仓库：手机和电脑互相传文件、收通知，全程不出内网、不耗流量。

## 功能

- **控制面板**：内嵌 WebView 直接打开后端网页，浏览/上传/管理仓库文件；文件由 App 自己下载落盘到「下载」目录，不触发系统的"仅 WiFi 下载"、"大文件二次确认"等弹窗
- **分享直传**：在任何 App 里点"分享"，选 TOOLAN 即可把文件（含多选）上传进仓库，同名文件会先询问是否覆盖
- **自动发现服务器**：扫描局域网 254 个地址 × 端口（8000 / 旧版 1225），通过 `/server_info` 识别 TOOLAN 后端，不用手填 IP
- **同步到手机**：电脑端网页勾选"同步到手机"后，服务端经 WebSocket 推送文件邀请，App 后台接收并下载
- **电脑触发手机通知**：App 内置一个局域网 HTTP 服务（Bearer token 鉴权），电脑发一条 JSON 就能让手机响铃/弹通知，配合三种音效（普通/重要/警报）
- **传输加密**：与 Go 后端、网页端共用同一套 A1 加密协议（PBKDF2 + AES-GCM 分块信封），开启后链路加密，并有密钥一致性校验
- **常驻可靠**：前台服务 + WakeLock + 开机自启 + 请求电池优化白名单，WebSocket 断线指数退避自动重连

## 权限说明

| 权限 | 用途 |
|---|---|
| `POST_NOTIFICATIONS` | 弹通知（就是干这个的） |
| `INTERNET` / `ACCESS_WIFI_STATE` | 局域网扫描与通信 |
| `FOREGROUND_SERVICE` + `WAKE_LOCK` | 常驻后台等推送 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 防休眠断连 |
| `READ/WRITE_EXTERNAL_STORAGE` | 旧版本系统落盘兼容 |
| `REQUEST_INSTALL_PACKAGES` | 应用自更新安装 |

## 构建

需要 JDK 11+、Android SDK（compileSdk 34）。minSdk 28（Android 9），targetSdk 34。

```bash
gradlew.bat assembleDebug   # Windows
./gradlew assembleDebug     # macOS / Linux
```

产物在 `app/build/outputs/apk/debug/`。签名密钥不入库，正式签名方式见 [RELEASE_BUILD_GUIDE.md](RELEASE_BUILD_GUIDE.md)。

## 项目结构

```
app/src/main/java/me/smerrybeta/toolanapp/
├── MainActivity.java              # 主界面（底部导航：控制面板/设置）
├── ControlPanelFragment.java      # 内嵌 WebView 的仓库控制面板
├── SettingsFragment.java          # 服务器地址、密钥等设置
├── ShareUploadActivity.java       # 系统分享接收入口
├── NotifyNotificationFactory.java # 通知构建
├── SoundManager.java              # 通知音效
├── SPUtils.java                   # 配置存取
├── AppConfig.java                 # 协议常量（端口/路径/加密头）
├── services/
│   ├── NotifyService.java         # 前台常驻服务
│   ├── NotifyWebSocketClient.java # 连后端 /ws，收同步邀请等事件
│   ├── NotifyHttpServer.java      # 手机侧 HTTP 服务，收 JSON 触发通知
│   ├── SyncOfferManager.java      # "同步到手机"文件邀请管理
│   └── BootReceiver.java          # 开机自启
└── utils/
    ├── NetworkScanner.java        # 局域网服务器扫描
    ├── ServerEndpoint.java        # 服务端点（地址/端口/协议）
    ├── SharedFileUploader.java    # 上传（含加密分块）
    ├── WebFileUtils.java          # WebView 下载/保存/分享
    └── crypto/                    # A1 加密协议（含 JVM 单测）
```

`FirstFragment` / `SecondFragment` / `MessageFragment` 为项目模板残留，未接入底部导航。
