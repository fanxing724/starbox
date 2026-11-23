# starbox

[![Build APK](https://github.com/fanxing724/starbox/actions/workflows/build.yml/badge.svg)](https://github.com/fanxing724/starbox/actions/workflows/build.yml)

安卓网盘下载器 · 全本地 · 全开源 · 零 native 依赖

粘贴分享链接 → 解析文件列表 → 多线程分片下载。所有解析与下载都在设备本地完成，**不经过任何第三方服务器**。

---

## 特性

- **14 家网盘**，多数支持免登录解析
- **多线程分片下载**，1–512 连接可调，断点续传，分片级重试
- **多节点镜像并发**：迅雷等有 CDN 镜像的网盘可跨节点同时拉取
- **一键转存下载**：游客直下被拒时，转存到自己网盘再取直链（UC / 夸克）
- **保存到系统 Download 文件夹**：Android 10+ 走 MediaStore，文件管理器直接可见；重名自动加序号
- **目录浏览**：进入子文件夹后可一键返回上层，系统返回键同样逐级回退
- **后台保活**：前台服务 + 通知栏进度，防止切后台被系统终止
- **内置播放器与 PDF 预览**：Media3/ExoPlayer + 系统 PdfRenderer，**不打包任何 .so**
- **自带运行日志**：记录解析/转存/取链/分片各步与错误码，已脱敏，可复制粘贴到 Issue
- **权限管理**：通知 / 电池优化 / 自启动状态一览，被拒后可一键跳系统设置开启
- 解析历史、**8 种自定义配色**、Material 3 界面
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

## 实测状态

**诚实标注**：作者环境无安卓设备，多数驱动只做过容器侧接口验证。「真机实测」指在真实设备上跑通了完整流程。

| 网盘 | 状态 | 说明 |
|---|---|---|
| **夸克网盘** | ✅ **真机实测通过** | 转存 → 取链 → 下载全链路跑通；**快传线路实测 6.0 MB/s** |
| **UC 网盘** | ⚠️ 接口实测通过 | 容器侧取链 + Range 请求返回 206、总长一致；真机曾报 41020（列表接口用错），已修但**尚未回传复测结果** |
| 光鸭云盘 | ⚠️ 接口实测通过 | 接口连通；测试分享返回 207「分享者未开启免登录」，属分享设置而非缺陷 |
| 蓝奏云 | ⚠️ 算法比对通过 | acw_sc__v2 双实现同输入输出一致；未做真机下载 |
| 123 / 移动139 / 城通 / 天翼 / 迅雷 / PikPak | ❓ 仅代码核对 | 接口路径、签名、请求头逐条对照参考实现，**未实际请求** |
| 阿里 / 百度 / 115 / 微云 | ❓ 未验证 | 需登录凭据，作者无账号，**接口第一步就过不去** |

**下载引擎**：分片边界、Range 校验、URL 拼接、链接解析有 37 项 JVM 单测覆盖；
**并发路径（暂停/继续竞态、meta 原子写）与 MediaStore 落盘仅有真机零散验证，无自动化测试。**

## 反馈问题（重要）

**本应用自带运行日志，遇到任何解析/下载问题请先取日志再开 Issue。**

1. 打开 **设置 → 排查 → 运行日志**
2. 点 **清空**，然后**复现一次问题**
3. 点 **复制**（或 **导出** 到系统 Download 目录）
4. 到 [Issues](../../issues) 新建，选择「问题反馈」模板，把日志粘进去

日志会记录：识别到哪家网盘、列表结果、转存 task_id、取链业务错误码、
每个分片的 HTTP 状态与 Content-Range、每 5 秒的进度与速度、失败原因。

**日志已脱敏**：Cookie / Token / stoken / BDUSS / p_skey 等一律打码，
签名 URL 只保留 host 与 path，查询串整体省略。有单测验证脱敏生效，可放心公开粘贴。

> 没有日志的「下不了」「很慢」很难定位。夸克那条 13 倍提速就是靠日志一步步查出来的：
> 先确认转存与取链都成功、再发现是普通线路被限速、最后找到官方快传通道。

## 关于「加速」

网盘限制的是**单连接速度**，不是总速度。本应用把文件切成多段并发拉取，速度随之叠加。

实测（UC 网盘）：

| 并发连接 | 速度 |
|---|---|
| 1 | ~0.1 MB/s |
| 8 | ~0.8 MB/s |
| 32 | ~3.1 MB/s |

**本应用不破解、不绕过任何账号级速率限制或会员付费墙。**

### 两种限速要分清

**① 单连接限速** —— 多线程可解。UC 就是这种，堆连接数速度线性上涨。

**② 线路限速** —— 换官方通道可解。**夸克是这种**：

| 夸克线路 | 36 连接总速 | 每连接 | 36 片首响应耗时 |
|---|---|---|---|
| 普通线路 | ~470 KB/s | ~13 KB/s | 20+ 秒 |
| **快传线路** | **6.0 MB/s** | ~170 KB/s | **0.4 秒** |

**约 13 倍差距。** 本应用取链时会自动申请快传令牌走快传线路，失败则回退普通线路。

> 判断教训：只看「每连接均摊速度低」容易误判成账号级封顶。
> 遇到慢速**先查有没有第二条官方通道**，别急着下「无解」结论。

### 真的无解的情况

若某条线路受**账号级总带宽**限制（如非会员百度网盘），多线程与换线路都无效，只能升级账号权益。

> 实现提示：
> - OkHttp 默认 `maxRequestsPerHost = 5`，不调大的话开 32 个请求也只有 5 个在跑。
> - 分片用阻塞式 `execute()`，协程 `Dispatchers.IO` 默认仅 64 线程，
>   高并发会被锁死，本项目改用专用弹性线程池。
> - 小文件别开太多分片：100MB 切 256 段每段仅 395KB，建连开销可能大于收益，建议 32–64。

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

未提供该文件时会自动降级：debug 使用默认调试 key，release 产出 **未签名包**（`app-release-unsigned.apk`）。

## 自动打包（GitHub Actions）

推送到 `main`、提交 Pull Request、或打 `v*` 标签时自动构建，产物在 Actions 的 Artifacts 里；**打标签还会自动发布 Release** 并附上 APK 与 `SHA256SUMS.txt`。也可在 Actions 页面手动触发。

默认产出：

| 文件 | 说明 |
|---|---|
| `starbox-<版本>-debug.apk` | CI 调试签名，可直接安装 |
| `starbox-<版本>-release-unsigned.apk` | 未签名，需自行签名后才能安装 |

**想让 CI 产出正式签名包**，在仓库 Settings → Secrets and variables → Actions 里配置：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64（`base64 -w0 your.jks`） |
| `KEYSTORE_PASSWORD` | store 密码 |
| `KEY_ALIAS` | 别名，留空默认 `starbox` |
| `KEY_PASSWORD` | key 密码 |

配置后 release 产物变为 `starbox-<版本>-release.apk`（正式签名，可覆盖升级）。

> 密钥只存在于 GitHub Secrets 与 CI 运行期，**不会进入仓库**。
> 注意：CI 调试签名包与正式签名包**签名不同**，互相覆盖安装会失败，需先卸载。

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
