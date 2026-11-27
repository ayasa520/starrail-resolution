# 星铁分辨率助手

通过修改设备配置资源，调整《崩坏：星穹铁道》国服与国际服的 3D 渲染分辨率。支持 **Root / Shizuku 授权**，目标尺寸按设备屏幕比例生成。

**当前版本：1.5.0** · [下载签名 APK](https://github.com/ayasa520/starrail-resolution/releases/download/v1.5.0/StarRailResolution-1.5.0.apk) · [发布说明](https://github.com/ayasa520/starrail-resolution/releases/tag/v1.5.0)

## 功能

- 国服与国际服选择，记住所选游戏；检测、应用、重启、撤销及恢复均针对所选版本。
- Root 管理器授权、Shizuku ADB 授权和 Shizuku Root 授权，应用会记住所选方式。
- 自动读取各设备的分辨率表，设置默认表和 GPU 独立配置表的最高档。
- 连续分辨率滑条与按屏幕长边生成的预设，实时显示完整像素尺寸。
- 资源校验、修改前备份、撤销、官方配置恢复和中断写入恢复。

## 授权方式

| 方式 | 准备 | 在助手中操作 |
| --- | --- | --- |
| Root | 在设备的 Root 管理器中允许助手获取权限 | 选择「Root」，点击「授权并检测」 |
| Shizuku ADB | 安装 [官方 Shizuku](https://shizuku.rikka.app/download/)，通过无线调试或电脑 ADB 启动服务 | 选择「Shizuku」，点击「授权并检测」，在弹窗中允许 |
| Shizuku Root | 在 Shizuku 中通过 Root 启动服务 | 选择「Shizuku」，点击「授权并检测」，在弹窗中允许 |

连接成功后，助手显示授权状态及资源检测结果。Shizuku 服务重启后，可回到助手重新检测。

Android 17 上，Shizuku 13.6.0 的授权应用列表可能为空：系统应用查询接口已改为返回 `PackageInfoList`，上游修复见 [Shizuku #2721](https://github.com/RikkaApps/Shizuku/pull/2721)。助手内的授权状态、执行 UID 和资源检测结果可用于确认连接。

## 使用

1. 安装 [1.5.0 APK](https://github.com/ayasa520/starrail-resolution/releases/download/v1.5.0/StarRailResolution-1.5.0.apk)，在「游戏版本」中选择国服或国际服，再按上表完成授权与检测。首次使用前先让所选游戏完成资源初始化。
2. 在游戏内把「分辨率」选择为最高可用档位；不同设备可能有四档或五档。其他画质和帧率设置按自己的需要保留。
3. 回到助手，拖动滑条，在 720 至当前屏幕长边之间连续选择（小于 720 的屏幕使用自身长边）；预设按当前屏幕长边比例生成，滑条与选中状态会同步。滑条上方实时显示「长边 × 短边」，短边按屏幕比例自动计算；再点「应用并全屏重启游戏」。拖动和点击预设只调整待应用目标，点击应用才会退出并重启游戏。
4. 需要撤销时，点「撤销上次修改」；要恢复设备原始配置，点「恢复官方分辨率档位」。恢复同样会备份并重启游戏。

| Y900 预设示例 | 预计 3D 尺寸（3840 × 2560 屏幕） |
| --- | --- |
| 1620（官方档） | 1620 × 1080 |
| 2160 | 2160 × 1440 |
| 2560 | 约 2560 × 1707 |
| 2880 | 2880 × 1920 |
| 3200 | 约 3200 × 2133 |
| 3840（原生） | 3840 × 2560 |

预设长边采用屏幕长边的 42.1875%、56.25%、2/3、3/4、5/6 和 100%，并限制在滑条范围内、去除重复值。点击预设后可继续自由拖动，不会吸附回预设；自定义位置也不会误亮某个预设。界面直接显示完整像素尺寸，避免混用 K 与 p 的命名口径。尺寸为配置目标和公式推算，非进程内运行时实测；引擎对齐可能产生约 1 像素差异。帧率是否稳定需要在实际场景中验证。

支持官方国服 `com.miHoYo.hkrpg` 与国际服 `com.HoYoverse.hkrpgoversea`。同时安装时可在「游戏版本」中切换，每次切换重新读取所选版本的配置与校验结果。1.5.0 移除 GPU 型号和固定屏幕尺寸限制，遍历配置里的所有显式分辨率表，统一修改每张表的最后一档：当前真实资源包含四档的 `Default` 和五档的 `Adreno VeryHigh`、`Mali VeryHigh`。没有独立分辨率表的设备配置由默认表覆盖；新增表名不需要添加 GPU 白名单。GPU 匹配只用于选择界面的预览表，不决定写入范围。**各表低档位、设备匹配条件及其他画质字段保持原样。**

不限制游戏版本号。每次检测都会解析当前资源清单、完整解码设备配置，并校验整个资源包。系统分辨率覆盖存在时使用当前生效尺寸计算，并记录物理尺寸和覆盖值，不更改系统设置。遇到未识别字段、资源校验失败或目标数值无法保持字段编码长度时，停止写入并记录具体原因。实机回归设备为 Y900；其他 GPU 的配置编辑由真实资源单元测试覆盖，尚未逐台验证其游戏表现。

Shizuku 支持 ADB 模式（UID 2000）及 Root 模式（UID 0），通过官方 UserService 执行文件操作。ADB 模式会检查资源目录及原文件的实际读写权限，检查在停止游戏之前完成。授权被拒绝或服务中断时会显示诊断，不会自动改用 `su`。ADB 模式不读取游戏私有偏好文件，界面会提示在游戏内确认最高分辨率档。两种授权共用资源校验、备份、撤销和恢复流程。权限差异见 [Shizuku 开发说明](https://github.com/RikkaApps/Shizuku-API#differences-of-the-privilege-between-adb-and-root)。

## 原理与修改范围

`BakedConfig/Config/Device/Android.bytes` 中包含 GPU 对应的分辨率目标表。本设备匹配 `Adreno VeryHigh`。正常横屏全屏下，已分析的代码使用：

```text
scale = clamp(min(targetWidth / screenWidth, targetHeight / screenHeight), 0.1, 1)
```

内部 3D 尺寸约为屏幕尺寸乘以此比例。因此，原始最高档的 2160 × 1080 目标矩形，在 3840 × 2560 屏幕上对应 1620 × 1080 的内部 3D 画面。1.5.0 直接写入用户选择的长边和按屏幕比例计算的短边，例如 Y900 原生档写入 3840 × 2560；不再使用固定 2:1 目标矩形，超宽手机、4:3 和接近方形的屏幕都按自身比例计算。更高分辨率会增加渲染负载。

该 APK 只修改设备配置资源。UI 输出尺寸、平板分辨率和密度、桌面布局、其他游戏画质设置均不在写入范围。它不注入或挂钩游戏，不操作游戏进程内存，没有网络权限。修改游戏资源仍可能触发游戏自身的资源校验或账号检测，不能据此保证零风险；程序不会绕过校验逻辑。

## 定位与写入

- 解析当前 `DesignV_*.bytes` 清单，以已验证的路径哈希寻找设备配置；不固定资源包文件名、偏移和长度。
- 完整反序列化设备配置，记录实际字段的字节范围。首次检测以当前清单中的完整包 MD5 认证原始配置；后续只虚拟恢复分辨率字段，再验证其他所有字节及完整包 MD5，不要求新版本匹配旧版配置指纹。
- 遍历所有显式 `renderResolutions` 表，只替换每张表最后一档的宽和高，并保持条目和包长度不变。每张表使用实际数组长度，不固定为五档；原始配置认证只归一化这些最高档字段，其他字段仍须通过完整包校验。
- 点击应用后停止游戏，记录原条目、目标条目、完整包前后 MD5、偏移、尺寸和操作类型。
- 在原包同目录生成完整临时副本并校验。Root 使用保留权限、属主的同目录原子替换。ADB 无权把新文件的属主改回游戏 UID，因此保留原文件 inode，仅同步写入包含分辨率差异的最小定长区间，写入前完整备份、写入后校验完整文件；I/O 失败时恢复并校验原条目。
- 对写入后的完整文件再次核验，然后全屏冷启动游戏。ADB 定长写入不具备原子重命名的断电保证；下次检测会识别最新未完成记录，在完整校验确认其余内容匹配后停止游戏并恢复原条目，同时保留中断内容。不会使用较旧的失败记录覆盖后续已完成操作。

Root 方式通过独立 `app_process` 调用 APK 自己的 `RootMain`；Shizuku 方式通过独立 UserService 调用相同文件助手。两条路径均与游戏进程没有附加、注入或调试关系。

## 备份与撤销

备份按游戏版本隔离：

| 游戏 | 备份目录 |
| --- | --- |
| 国际服 | `Download/StarRailResolution`（沿用历史目录） |
| 国服 | `Download/StarRailResolution/com.miHoYo.hkrpg` |

即使两个版本使用相同资源包哈希，撤销和恢复也只读取各自目录中的记录。

- 每一代官方资源包首次修改时保留一个完整基线副本及其校验值。
- 写入前保存该资源包对应的 `official-entry-资源包MD5.bytes`，恢复时使用这一代的原始配置，不套用旧版分辨率常量；旧版 4.6.0 的历史修改仍可通过其精确指纹识别。
- 每次修改、撤销、恢复官方档位，都创建独立的 `revert 时间戳` 目录，包含修改前后条目和详细 JSON 记录。
- 撤销要求当前完整包 MD5 与最近一次操作的结果一致，避免覆盖游戏更新或其他操作产生的变化。
- 如果在原子替换成功后、写回操作记录前发生进程中断，下次检测会根据完整文件 MD5 恢复该操作的撤销记录。
- 卸载 APK 不会删除 Download 中的备份。备份只包含资源数据和图形变更记录，没有账号存档或 SDK 遥测。

## 源码结构

- `core/GameTarget.java`：允许操作的游戏包、资源目录、偏好文件和备份命名空间。
- `core/ResourceManifest.java`：资源清单与条目定位。
- `core/ProfileConfig.java`：完整配置解码、历史配置识别和定长字段修改。
- `core/VerifiedProfile.java`：按当前资源包认证兼容性、保存各代原始配置及识别历史修改。
- `core/ResolutionSpec.java`：分辨率换算和输入边界。
- `core/ScreenGeometry.java`：读取当前生效显示尺寸及物理尺寸，保留系统覆盖值的诊断。
- `core/FileAccessProbe.java`：写入前验证目录创建、同步和原子重命名权限。
- `core/ResourceTransaction.java`：持久备份、全文件校验和提交策略。
- `core/InPlaceEntryCommitter.java`：ADB 保留属主的定长写入、失败回退及中断条目恢复。
- `RootMain.java`：版本与设备检查、文件权限、游戏停止/启动、回退操作。
- `RootGateway.java`：界面与独立 Root 文件助手之间的调用。
- `PrivilegedGateway.java`、`ShizukuGateway.java`、`ShizukuFileService.java`：统一操作接口及 Shizuku UserService 连接和清理。
- `MainActivity.java`：连续分辨率滑条、实时长边 × 短边、当前配置、恢复及可复制诊断。

## 构建

需要 JDK 17 以上、Android SDK 36，以及 Gradle 自动获取的 Android Gradle Plugin 9.2.1。

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```

也可使用 SDK 自带的 aapt2，将路径替换为本机实际安装位置：

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease \
  -Pandroid.aapt2FromMavenOverride="$ANDROID_SDK_ROOT/build-tools/36.1.0/aapt2"
```

未签名 APK 在 `app/build/outputs/apk/release/`。自行分发时须使用自己的密钥签名；自行签名的 APK 无法直接覆盖本项目 Release 中使用另一密钥签名的安装包。项目发布密钥不包含在仓库中，发布 APK 通过 GitHub Releases 分发，并附 SHA-256 校验文件。

## 验证

自动测试使用实际配置和清单副本，检查资源定位、三次 ADB 修改的精确 SHA-256 一致性、截断和异常数据拒绝、跨哈希缓冲区的替换、整包恢复、过期输入拒绝、备份失败和重命名失败时保留原文件。发布验证摘要见 [docs/VALIDATION.md](docs/VALIDATION.md)，版本说明见 [CHANGELOG.md](CHANGELOG.md)。本地设备日志和回退备份不包含在仓库中。

`ShizukuIntegrationTest` 是独立设备测试入口。先安装助手和同证书签名的测试 APK，在助手中完成 Shizuku 授权，再运行 `adb shell am instrument -w -r io.rikka.starrailresolution.test/io.rikka.starrailresolution.ShizukuIntegrationTest`。它通过真实 Shizuku Binder 检测资源、验证执行 UID，并在独立临时文件上测试备份、修改、恢复；前后核对真实游戏资源的完整 MD5。添加 `-e gamePackage com.miHoYo.hkrpg` 可测试国服，默认测试国际服。

设备验证仅通过独立文件助手读取真实游戏配置，并在独立测试文件上执行事务；不需要在测试中修改或停止正在运行的游戏。
