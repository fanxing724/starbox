# starbox

安卓网盘下载器 · 全本地 · 全开源 · 零 native 依赖

粘贴分享链接 → 解析文件列表 → 多线程分片下载。所有解析与下载都在设备本地完成，**不经过任何第三方服务器**。

---

## 特性

- **14 家网盘**，多数支持免登录解析
- **多线程分片下载**，1–512 连接可调，断点续传，分片级重试
- **多节点镜像并发**：迅雷等有 CDN 镜像的网盘可跨节点同时拉取
- **一键转存下载**：游客直下被拒时，转存到自己网盘再取直链（UC / 夸克）
- **后台保活**：前台服务 + 通知栏进度，防止切后台被系统终止
- **内置播放器与 PDF 预览**：Media3/ExoPlayer + 系统 PdfRenderer，**不打包任何 .so**
- 解析历史、深色模式、Material 3 界面
- **无广告、无统计、无埋点、无服务器**

## 支持的网盘

| 网盘 | 免登录解析 | 转存下载 | 备注 |
|---|:---:|:---:|---|
| UC 网盘 | ✅ | ✅ | 游客链路需 `__pugs` + 客户端 UA + Referer |
| 夸克网盘 | ✅ | ✅ | 小文件游客可下，大文件需登录 |
| 123 云盘 | ✅ | — | 分享列表需 Dart UA |
| 蓝奏云 | ✅ | — | 需解 `acw_sc__v2` 人机校验 |
| 光鸭云盘 | ✅ | — | 取决于分享者是否开启免登录 |
| 移动云盘 139 | ✅ | — | 请求体 AES-128-CBC 加密 |
| 城通网盘 | ✅ | — | VIP 文件走多线路 |
| 天翼云盘 | ✅ | — | 有访问码时需带 share Cookie |
| 迅雷网盘 | ✅ | — | 游客可解析，下载需登录 |
| PikPak | ✅ | — | 游客可列目录，下载需登录 |
| 阿里云盘 | 需登录 | — | |
| 百度网盘 | 需登录 | — | 下载 UA 必须为 `pan.baidu.com` |
| 115 网盘 | 需登录 | — | |
| 腾讯微云 | 需登录 | — | **官方不开放他人分享解析**，仅可浏览自己的网盘 |

## 关于「加速」

网盘限制的是**单连接速度**，不是总速度。本应用把文件切成多段并发拉取，速度随之叠加。

实测（UC 网盘）：

| 并发连接 | 速度 |
|---|---|
| 1 | ~0.1 MB/s |
| 8 | ~0.8 MB/s |
| 32 | ~3.1 MB/s |

**本应用不破解、不绕过任何账号级速率限制或会员付费墙。** 非会员的账号限速，多线程也救不了。

> 实现提示：OkHttp 默认 `maxRequestsPerHost = 5`，不显式调大的话开 32 个请求也只有 5 个在跑。

## 构建

需要 **JDK 17+** 与 **Android SDK（compileSdk 34）**。

```bash
git clone https://github.com/fanxing724/starbox.git
cd starbox
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。

**签名**：仓库不含任何密钥。如需自行签名，在项目根目录创建 `keystore.properties`：

```properties
storeFile=keystore/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

未提供该文件时，release 构建会因缺少签名配置而失败；debug 构建同理。请自备 keystore。

## 架构

```
app/src/main/java/com/fanxing/xbox/
├── drive/          网盘驱动层
│   ├── Models.kt       Drive 接口 / ShareRef / RemoteFile / DirectLink
│   ├── Registry.kt     按链接自动识别网盘
│   ├── Http.kt         OkHttp 客户端（大 Dispatcher）
│   └── *Drive.kt       各网盘实现
├── download/       下载引擎
│   ├── Engine.kt       分片并发 / 断点续传 / 多节点镜像
│   └── DownloadService.kt  前台服务 + 通知栏
├── store/          本地存储（凭据 / 设置 / 历史）
└── ui/             界面（底部导航 + 三 Fragment）
```

新增一家网盘只需实现 `Drive` 接口并注册到 `Registry`。

## 依赖

全部开源，**无 native 二进制库**：

| 组件 | 许可证 | 用途 |
|---|---|---|
| Kotlin / coroutines | Apache 2.0 | 语言与异步 |
| AndroidX | Apache 2.0 | 基础框架 |
| Material Components | Apache 2.0 | 界面 |
| OkHttp / Okio | Apache 2.0 | 网络 |
| Media3 / ExoPlayer | Apache 2.0 | 视频播放 |

PDF 预览使用系统 `PdfRenderer`，下载引擎为本项目原创实现，均无第三方依赖。

## 许可证

**AGPL-3.0** — 详见 [LICENSE](LICENSE)。

本应用的网盘驱动移植自以下 AGPL-3.0 项目，作为衍生作品整体以 AGPL-3.0 授权：

- **[YunX-Desktop / 云析](https://github.com/tidain/YunX-Desktop)**（Kotlin）— UC、夸克、百度、迅雷、123、蓝奏、115、光鸭、移动 139
- **[wenxi / 文析助手](https://github.com/z7786/wenxi)**（Dart）— 微云、天翼、PikPak
- **[LinkSwift / 网盘直链下载助手](https://github.com/hmjz100/LinkSwift)**（JS）— 百度 UA 规则、迅雷镜像节点

完整归属见 [NOTICE](NOTICE)。感谢上述项目作者。

应用图标由用户提供，版权归用户所有，**不适用**本项目的 AGPL 授权。

## 免责声明

本应用是工具，不是资源站。所有内容均来自用户自行输入的第三方分享链接，本应用不存储、不索引、不分发任何文件。

- 请仅下载您拥有合法权利或已获授权的内容
- 请遵守各网盘服务商的服务条款
- 使用非官方客户端存在账号被限制的风险，请自行评估
- 各网盘接口可能随时变更，本应用不承诺任何网盘功能的持续可用性

本应用不是任何网盘服务商的官方客户端，与相关公司无隶属或授权关系。
