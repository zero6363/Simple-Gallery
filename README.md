# 简单图库2 / Simple Gallery 2

简单图库2 是基于 [Simple Gallery Pro](https://github.com/SimpleMobileTools/Simple-Gallery)（GPLv3）的社区维护分支，在保留原版浏览、管理、编辑、隐藏相册等全部功能的基础上，补齐了现代手机照片的显示与播放能力。

包名为 `com.simplemobiletools.gallery2`，可以与原版 Simple Gallery 同时安装。

## 本分支新增功能

### 1. 完整的 HDR 支持（照片与视频）

- 自动识别主流 HDR 静态图标准：
  - Ultra HDR JPEG（Android 14 引入的增益图格式）
  - ISO 21496-1 增益图（HEIF / AVIF / JPEG，含 MPF 内嵌增益图）
  - Apple HDR 增益图（`auxC` 辅助图 URN）
  - HDR10（SMPTE ST 2084 / PQ）与 HLG
  - Dolby Vision（`dvcC` / `dvvC`）
  - HDR PNG（`cICP` 块中的 PQ / HLG 传输特性）
- 自动识别 HDR 视频：MP4/MOV/3GP 的 `colr`（PQ / HLG）、`mdcv` 与 Dolby Vision（`dvcC` / `dvvC`），以及 Matroska/WebM 的 TransferCharacteristics
- 在网格、列表和大图播放界面显示 **HDR** 徽标，HDR 视频同样标识。
- HDR 视频播放时自动切换为 `SurfaceView` 输出并请求窗口 HDR 色彩模式，分页大图与独立播放器均支持；SDR 视频继续使用 `TextureView` 以保留缩放手势。
- Android 14+ 上，带增益图的照片通过系统 gain map 管线以**真实 HDR** 显示；同时适配需要窗口 HDR 色彩模式的 ROM（如 HyperOS）。
- 检测在后台线程执行并缓存结果，滚动大文件夹时不会阻塞界面。

### 2. 实况照片（Live Photo / Motion Photo）

- 支持多种实况照片格式：
  - Google 旧版 `MVIMG`（`GCamera:MicroVideo` + `MicroVideoOffset`）
  - Google 新版 `MotionPhoto` / `Container:Directory` 格式
  - 三星 `SEFT` 结尾的格式
  - 部分厂商（如 LG）使用同名配对视频文件
- 缩略图显示 **LIVE** 徽标，点击徽标即可在大图界面原地播放动态部分。
- 新增“自动播放实况照片”开关，可在设置中关闭。
- 视频部分按需提取到缓存目录，并自动清理 7 天前的缓存。

### 3. 系统回收站（Android 11+）

- 删除文件时可选择使用 MediaStore 系统回收站，由系统统一保留和清理（约 30 天）。
- 主界面和媒体界面菜单新增“查看系统回收站”，支持恢复和彻底删除。
- 新增“使用系统回收站”开关，可随时切回应用内回收站。

### 4. 其他改进

- 幻灯片播放间隔支持毫秒级设置（旧版以秒为单位的设置会自动迁移）。
- 应用更名为「简单图库2 / Simple Gallery 2」，避免与原版混淆。



## 下载

请前往 [GitHub Releases](https://github.com/zero6363/Simple-Gallery/releases) 下载最新 APK。

## 构建

环境要求：JDK 17、Android SDK 34。

```bash
# 调试版（FOSS 变体，无需影像编辑 SDK 密钥）
./gradlew assembleFossDebug

# 发布版：从仓库外的固定签名目录读取证书与密码
./gradlew assembleFossRelease
```

Release 签名默认读取 `D:\code\sdk\signing\android-release.keystore` 与同目录的 `signing.properties`，密码不会进入 Git。可用环境变量 `ANDROID_SIGNING_DIR` 指向其他签名目录；旧版根目录 `keystore.properties` 仍作为回退方案。

`proprietary` 变体包含 IMG.LY 高级编辑器，需要额外的 SDK 配置（见 `gradle/imglysdk.gradle`）。

## 上游与许可

- 上游项目：[SimpleMobileTools/Simple-Gallery](https://github.com/SimpleMobileTools/Simple-Gallery)，本分支基于 6.28.26（versionCode 420）。
- 本项目与原 Simple Mobile Tools 团队无隶属关系，属于非官方社区分支。
- 代码以 [GNU GPLv3](LICENSE) 发布，本分支的所有修改同样遵循 GPLv3。

---

# English

Simple Gallery 2 is a community-maintained fork of [Simple Gallery Pro](https://github.com/SimpleMobileTools/Simple-Gallery) (GPLv3). It keeps the complete upstream feature set and adds support for modern HDR and live photos. The application id is `com.simplemobiletools.gallery2`, so it can be installed alongside the original app.

## Fork highlights

- **Full HDR support** — detects Ultra HDR JPEG, ISO 21496-1 gain maps (HEIF/AVIF/JPEG), Apple HDR gain maps, HDR10, HLG, Dolby Vision, HDR PNG and HDR videos (MP4/MOV/3GP colour metadata and Dolby Vision, Matroska/WebM TransferCharacteristics). Shows an HDR badge in grids, lists and the fullscreen viewer, plays HDR videos through a `SurfaceView` with the window HDR colour mode requested, and renders gain-map photos in true HDR on Android 14+, including ROMs that require the window HDR colour mode (e.g. HyperOS).
- **Live photos** — plays motion photos from Google (legacy MVIMG and current MotionPhoto), Samsung SEFT and paired-video formats, with an optional auto-play setting. Extracted videos are cached and pruned automatically.
- **System recycle bin (Android 11+)** — delete into the MediaStore trash, browse it, restore or permanently delete items, and toggle the behaviour in Settings.
- **Millisecond slideshow intervals**, with automatic migration of the old seconds-based setting.
- Renamed to **Simple Gallery 2** with a distinct application id.

## Download & build

Download APKs from [GitHub Releases](https://github.com/zero6363/Simple-Gallery/releases). To build locally (JDK 17, Android SDK 34):

```bash
./gradlew assembleFossDebug
```

Release builds read the fixed keystore and passwords from `D:\code\sdk\signing` (override with `ANDROID_SIGNING_DIR`); no signing secrets are stored in the repository.

## License

GPLv3. This is an unofficial fork and is not affiliated with Simple Mobile Tools.
