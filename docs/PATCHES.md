# 长期补丁清单

本文档是当前 fork 相对 `upstream/master` 的状态快照。它只回答「现在需要保留什么」，
不记录安装日期、APK 哈希或返工过程。

## 当前基线

- 上游：`upstream/master` @ `a5013a932`
- fork 分支：`master`
- 技术包名：`nodomain.freeyourgadget.gadgetbridge.toge`
- 桌面名称：`健康数据`
- 当前设备：Huawei Band 10

## 已实现

### 独立包名与显示身份

- 目的：与原版 Gadgetbridge 共存，同时一眼辨认健康数据专用 fork。
- 行为：
  - `applicationId` 使用 `nodomain.freeyourgadget.gadgetbridge.toge`；
  - Pebble ContentProvider authority 使用 `com.getpebble.android.provider.toge`；
  - `app_name` 和启动 Activity label 都显示「健康数据」。
- 覆盖区：
  - `app/build.gradle`
  - `app/src/mainline/res/values/strings.xml`
- 验证：`assembleMainlineDebug` 通过；APK 实际解析的包名、应用 label 和启动 label 均正确。
- commits：`c16bd7b82`、`402b8cc7a`、`341ec6633`

### 数据库导出以分钟调度

- 目的：让橘瓣本地 Gadgetbridge 工具更快读到新快照。
- 行为：
  - 自动导出间隔从小时改为分钟；
  - 默认值为 15 分钟；
  - 计算、WorkManager 单位和中英文文案同步使用分钟。
- 限制：WorkManager 的周期任务最小间隔是 15 分钟。
- 覆盖区：
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/PeriodicExporter.kt`
  - `app/src/main/res/xml/auto_export_settings.xml`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-zh-rCN/strings.xml`
- 验证：`assembleMainlineDebug` 通过。
- commit：`c16bd7b82`

### 拿到新数据后即刻导出

- 目的：让橘瓣读到的快照跟随实际取数，而不是等下一个 15 分钟周期窗口。
- 行为：
  - 监听 `GBApplication.ACTION_NEW_DATA`（与 Health Connect 同步同一个事件），
    收到后调度一次 DB 导出；
  - 使用 5 秒防抖：`enqueueUniqueWork` + `ExistingWorkPolicy.REPLACE`，
    一次取数产生的多个事件合并成一次导出，避免重复重写整份文件；
  - 只在 DB 自动导出已开启时生效；
  - 额外开关 `auto_export_on_sync` 默认开启。
- 为什么不挂在设备状态上：`DeviceUpdateSubject.DEVICE_STATE` 只在 `setUpdateState()`
  时发出，Huawei 侧唯一的调用点是 init 队列结束（连接建立完成）。同步本身只调
  `setBusyTask()`，不发任何广播。因此挂在 `isInitialized()` 上的 hook 在「表已连接、
  靠解锁触发取数」这个日常场景下一次都不会触发，导出实际全靠周期任务兜底。
- 覆盖区：
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/DeviceCommunicationService.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/PeriodicExporter.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/GBPrefs.java`
  - `app/src/main/res/xml/auto_export_settings.xml`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-zh-rCN/strings.xml`
- 限制：`assembleMainlineDebug` 通过，尚未覆盖安装，未实机复验触发时机。
- commits：`c16bd7b82`、`59fa5d4d6`（原连接时实现）

### Health Connect 写入后即刻唤醒 HCWebhook

- 目的：消除「Health Connect 已有数据，但 HCWebhook 仍等待 Android 周期任务」造成的小时级延迟。
- 行为：
  - Health Connect worker 完成一次有效同步流程后，向
    `com.hcwebhook.app/.ScheduledSyncReceiver` 发送显式
    `com.hcwebhook.app.SCHEDULED_SYNC` 广播；
  - HCWebhook 沿用自己的读取、鉴权和上传逻辑，fork 不接触它的上传 token；
  - 广播异常只记录警告，HCWebhook 的周期任务仍是兜底，不影响已写入 Health Connect 的数据。
- 依据：在已安装的 HCWebhook 1.9.14 上手动发送同一显式广播，接收器立即启动同步，远端健康
  记录 2 秒内更新；此前 GB worker 完成点没有任何到 HCWebhook 的主动交接。
- 覆盖区：
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/healthconnect/HealthConnectSyncWorker.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/healthconnect/HcWebhookSyncTrigger.java`
- 限制：本地单元测试与 APK 构建已通过，仍需覆盖安装，实机确认普通应用身份发送广播时
  HCWebhook 的前台服务能正常启动。

### 自托管健康同步：fork 直传服务器，不经 Health Connect 与 HCWebhook

- 目的：把「取数 → Health Connect → HCWebhook → 服务器」压成「取数 → 服务器」。少装一个闭源
  第三方 App，也不再依赖 Health Connect，因此不需要 Play 商店和 Google 服务。
- 位置：设置 → 外部集成 → 自托管健康同步。与 Health Connect 并列，两个开关各管各的；
  仍在用 Health Connect 接别的 App 的人不受影响。
- 行为：
  - 取数已经落进 Gadgetbridge 自己的库，直接读 `getSampleProvider().getAllActivitySamples()`
    与 `SleepAnalysis`，按天组装 JSON，OkHttp POST 到 `<服务器>/api/health`，带 Bearer token；
  - 触发点复用 `ACTION_NEW_DATA` 与已有的 10 秒防抖（`NewDataReceiver`），不新增事件源；
  - 步数按本地日汇总为当日总数；心率按 5 分钟分桶取均值；睡眠按 `SleepAnalysis` 的
    session 归到醒来日，时长不计清醒阶段，与应用内、设备卡片、小组件一致；
  - 时间戳一律带时区偏移的 ISO 8601，服务端不需要猜时区；
  - 上传游标按设备存偏好；失败不推进游标，`Result.retry()` 走 WorkManager 自带退避。
  - 设置页可查看最近的上传日志、按结果筛选、查看完整 Payload 并复制；日志列表和详情页沿用
    应用原生主题文字色、点击反馈与分隔线，不额外引入红绿状态色、圆角卡片或胶囊标签。
- 幂等边界：服务端是合并不是覆盖——步数取较大值、心率按时间戳去重、睡眠按时间跨度重叠
  判断同一晚并保留更完整版本。fork 侧保留 24 小时回看窗口和睡眠上传游标；同一晚后续变长时，
  新结束时间会越过旧游标并重传，由服务端替换较短版本，不会重复计入汇总。
- 睡眠 session 只在「结束时间比我们手上最新样本早 10 分钟以上」时上传。这段等待只用于避免
  暂时展示仍在生长的半截睡眠，不再承担防重复职责；醒来后首次取得足够新的样本即可上传。
- 需要 `android.permission.INTERNET`：上游在 `AndroidManifest.xml` 里用 `tools:node="remove"`
  主动摘掉了它，网络功能全部走独立的 Internet Helper App。走 Internet Helper 等于把刚踢掉的
  第二个 App 请回来，所以 fork 保留该权限。INTERNET 是安装期权限，用户侧不会多一次弹窗。
  副作用：Internet Helper 设置项自动隐藏；Garmin 设备会多出「互联网」设置屏（默认关闭，
  防火墙默认 BLOCK，不构成放宽）。
- 覆盖区：
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedHealthPayload.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedHealthUploader.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedHealthSyncWorker.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/preferences/SelfHostedHealthPreferencesActivity.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/selfhostedhealth/`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/externalevents/NewDataReceiver.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/SettingsActivity.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/GBPrefs.java`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/res/xml/selfhosted_health_preferences.xml`
  - `app/src/main/res/layout/activity_selfhosted_health_log.xml`
  - `app/src/main/res/layout/activity_selfhosted_health_log_detail.xml`
  - `app/src/main/res/layout/item_selfhosted_health_log.xml`
  - `app/src/main/res/xml/preferences.xml`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/values-zh-rCN/strings.xml`
  - `app/src/test/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedHealthPayloadTest.java`
  - `app/src/test/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedHealthLogTest.java`
- 验证：`SelfHostedHealthPayloadTest` 10 项、`SelfHostedHealthLogTest` 5 项通过；
  `assembleMainlineDebug` 通过，合并后的
  manifest 确认带 INTERNET 且注册了新 Activity；构建产出的真实 payload 用 Node 回放进
  `mcp/health-server.js` 的 `mergeHealthData`，落盘结果正确（步数合计、心率分桶、睡眠归到
  醒来日、深/浅/REM 汇总非零），重复回放不产生重复记录。
- 限制：同步日志原生样式调整已覆盖安装并通过启动与数据保留验收，页面视觉仍待人工确认。

### 连接时不下发未经用户设置的睡眠开关

- 目的：阻止 Gadgetbridge 在每次连接时把手表自身的科学睡眠和睡眠呼吸监测关掉。
- 背景：上游 `SetTruSleepRequest` 和 `SendSleepBreathRequest` 都在 init 队列里，
  按 `getBoolean(pref, false)` 读取偏好。用户从未设置过时，两者会向手表下发
  「关闭」，而不是「不改动」。Huawei Band 10 上的表现是科学睡眠被静默关掉，
  用户要过一晚才发现，且在手表端手动打开也会被下次连接覆盖。
- 行为：
  - 两个 Request 增加 `userRequested` 构造参数，默认 `false`；
  - `requestSupported()` 在非用户操作且偏好为关时返回 `false`，即连接时跳过该命令，
    保持手表现状不变；
  - 偏好为开时连接仍会下发「开启」，保证手表状态与 Gadgetbridge 一致；
  - 设置页显式切换走 `setTrusleep()` / `setSleepBreath()`，传入 `userRequested=true`，
    因此用户主动关闭仍会立即下发关闭，两个方向都保留。
- 覆盖区：
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huawei/requests/SetTruSleepRequest.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huawei/requests/SendSleepBreathRequest.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huawei/HuaweiSupportProvider.java`
- 限制：`assembleMainlineDebug` 通过，但尚未覆盖安装，未在实机复验连接时的下发行为。
- 验证：`assembleMainlineDebug` 通过；init 队列（`HuaweiSupportProvider` 第 944、945 行）
  使用安全构造函数，运行时入口（第 2052、2073 行）传入 `true`。

### 今日小组件保留完整布局，应用与组件统一按醒来日显示睡眠

- 目的：保留上游完整信息密度，同时让白色底部清晰可读，并让睡眠日期与健康数据链一致。
- 行为：
  - 继续使用单一完整 `widget.xml`：平蓝色顶部、设备名、电量、三项指标和三条进度条全部保留；
  - 不使用 compact 布局、圆角卡片或自适应布局切换；
  - 底部改为纯白底，底部文字与图标使用深色，进度条使用蓝色进度和浅灰轨道；顶部不改；
  - 今日小组件和软件内设备卡片的中间一项都由距离改为今日最新有效心率，无有效值时显示 `--`；
    软件内点击该项进入心率页，设备卡片设置中的对应项目也显示为心率；
  - 今日小组件和软件内设备卡片的睡眠为 0 时都仍显示睡眠项；
  - 应用内日视图、周/月统计、设备卡片和今日小组件都先通过 `SleepAnalysis` 识别跨午夜的
    完整 session，再按 session 结束日期（醒来日）归属；睡眠总时长不计清醒阶段；
  - App 进程重建后，组件在系统 `onUpdate()` 或用户点击时重新注册
    `ACTION_NEW_DATA` / `ACTION_DEVICE_CHANGED` 本地监听，继续跟随新数据与设备连接状态刷新；
    多次更新不会重复注册，删除单个组件不会影响其他组件，最后一个组件移除时从同一个本地
    广播管理器注销。
- 覆盖区：
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/Widget.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/adapter/GBDeviceAdapterv2.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/charts/SleepAnalysis.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/charts/SleepDailyFragment.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/activities/charts/SleepPeriodFragment.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/model/DailyTotals.java`
  - `app/src/main/res/layout/widget.xml`
  - `app/src/main/res/layout/device_itemv2.xml`
  - `app/src/main/res/xml/devicesettings_device_card_activity_card_preferences.xml`
  - `app/src/test/java/nodomain/freeyourgadget/gadgetbridge/WidgetTest.java`
  - `app/src/test/java/nodomain/freeyourgadget/gadgetbridge/WidgetListenerTest.java`
  - `app/src/test/java/nodomain/freeyourgadget/gadgetbridge/activities/charts/SleepAnalysisTest.java`
  - `app/src/test/java/nodomain/freeyourgadget/gadgetbridge/model/DailyTotalsTest.java`
- 验证：widget、监听生命周期与 `SleepAnalysis` 针对性单测通过。

### 本地构建工具链

- 目的：使项目在当前 Windows/JDK 17/Android SDK 37 环境可重复构建。
- 行为：
  - app compile SDK 和 build tools 使用 37；
  - app、FitCodeGenerator 和 GBDaoGenerator 的 Java toolchain 使用 17。
- 覆盖区：
  - `app/build.gradle`
  - `FitCodeGenerator/build.gradle.kts`
  - `GBDaoGenerator/build.gradle.kts`
- 验证：`assembleMainlineDebug` 通过；当前 Android Gradle Plugin 对 SDK 37 会给出上游兼容性警告，不影响产物生成。
- commits：`c16bd7b82`、`838ede8c0`

## 上游合并检查

合并新的 `upstream/master` 时，至少重新核对：

1. `applicationId`、ContentProvider authority 和两个 label 没有被还原。
2. `PeriodicExporter` 的时间单位仍全部一致，没有出现「部分分钟、部分小时」。
3. `DeviceCommunicationService` 仍监听 `ACTION_NEW_DATA` 并走防抖导出，没有被上游改回设备状态事件；`PeriodicExporter.executeDebounced` 仍在。
4. Health Connect 的上游变更没有破坏 Huawei Band 10 的数据类型支持，worker 完成后仍调用
   `HcWebhookSyncTrigger`。
5. Huawei init 队列没有恢复成无条件下发 TruSleep / SleepBreath 的「关闭」状态。
6. `AndroidManifest.xml` 里的 INTERNET 没有被上游的 `tools:node="remove"` 改回去，
   自托管健康同步仍能发出请求；`NewDataReceiver` 仍同时调度 HC 与自托管两条同步。
7. 重新构建并解析 APK，不只依赖源码文本检查。
