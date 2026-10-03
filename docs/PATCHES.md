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

### Debug / release 共用的无界面安装前备份

- 目的：公开 release 不可 `run-as`，但 ADB 覆盖升级仍应先取得最新、可验证的数据备份，且不要求
  人工点屏幕。
- 行为：
  - manifest 注册 `InstallerBackupReceiver`，只接受持有系统 `android.permission.DUMP` 的调用方；
    ADB shell 可以调用，普通第三方 App 不可以；
  - receiver 通过 WorkManager 复用 `ZipBackupExportJob`，在应用 external cache 生成带随机 request id
    的临时 ZIP 与状态文件；完成前使用 `.partial`，不会把半成品报成成功；
  - 调用方拉取并核验 ZIP 后发送 cleanup action，应用删除对应 ZIP、partial 与状态文件，不在手机
    长期积累安装备份；
  - debug 与公开 release 都保留相同入口，不依赖 `android:debuggable`。
- 覆盖区：
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/externalevents/InstallerBackupReceiver.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/backup/InstallerBackupWorker.kt`
  - `app/src/main/AndroidManifest.xml`
- 验证：对应 debug APK 已在 Android 16 实机由 ADB 无界面生成并拉取有效 ZIP；数据库
  `integrity_check = ok`，cleanup 后手机临时目录为空。

安装请求使用唯一 WorkManager 任务并以 `.partial`/状态文件发布结果，避免并发请求互相覆盖或
安装器在备份尚未完成时继续执行。

### 内置免费天气

- 行为：可选启用 Open-Meteo 天气源，使用手机保存的位置获取当前天气、逐小时和 8 天预报，
  复用 Gadgetbridge 现有设备天气协议；支持设置页定位、立即刷新和每小时 WorkManager 刷新。
- 不需要天气 App、账号或 API key；关闭开关会取消周期任务。
- 预报天数从 7 天增至 8 天（部分华为设备要求）；逐小时预报改为严格提供未来 24 小时，
  不再把已经过去的当前小时计入，避免手表侧少一小时或错位；新增月相计算，逐日预报和
  当日天气都带上月相角度。
- 定位坐标来源从「每次读取 Android 系统最近已知位置」改为「读取已保存的位置偏好」，
  避免网络定位在使用 VPN 时跳到出口地区、导致天气与实际所在地不符；发送到手表前会先
  校验坐标范围合法。
- 天气请求增加响应状态码校验，请求被手表拒绝时明确抛错，不再静默当作成功。
- 覆盖区：`util/builtinweather/`、`HuaweiWeatherManager`、`SendWeatherForecastRequest`、
  天气设置页、天气偏好与对应单测。
- 验证：`BuiltinWeatherFetcherTest`、`SendWeatherForecastRequestTest` 定向单测通过。

### 经期上下文（自托管）

- 行为：设备设置 → 健康 → 经期，使用一个默认关闭的开关维护开始日、周期天数和经期天数，
  只通过已配置的自托管健康服务器 `POST /cycle` 上传；服务端独立保存 `cycle.json`，读取时
  动态附加经期中或未来 3 天内的提示。
- 关闭后立即删除手机端经期字段，并发送 `{ "enabled": false }` 清除服务端副本；失败时只保留
  不含经期数据的待清除标记，网络恢复后自动重试。
- 不上传到手环或厂商云，不生成排卵、受孕窗口或避孕建议，也不写入每日健康 JSON。
- 覆盖区：`util/cycle/`、经期设置页面、`GBPrefs`、设备设置入口、对应资源与单测。

### 导出触发记录与历史保留

- 新数据到达后以 `ACTION_NEW_DATA` 触发 5 秒防抖导出，同时保留 15 分钟周期任务作为兜底；设置页
  可查看最近导出及触发来源。
- 自动导出写入目标快照前会合并目标文件中当前库没有的旧记录，并在完整性校验失败时保留原文件，
  避免华为新快照覆盖旧设备历史。

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

### Huawei 后台心率快同步

- 目的：缩短“手表已经记录心率，但手机长时间没有解锁时 Gadgetbridge 仍未取回”的等待。
- 行为：支持心率的 Huawei 设备初始化完成后按设备设置周期拉取历史 step 数据；间隔可选关闭、
  1、3、5、10 分钟，默认 3 分钟。请求链不做即时测量，也不拉睡眠、压力、ECG 或 P2P 数据。
- 调度边界：一轮完成后再安排下一轮；断连、关闭或服务退出时停止，重连初始化后恢复。快同步
  复用 Huawei 同步锁，完整活动同步占用时跳过本轮，两个方向都不重叠。
- 通知边界：只有数据库中最新有效心率时间前进才发送 `ACTION_NEW_DATA`，避免空轮触发导出、
  Health Connect 或自托管上传；解锁触发的完整同步继续补齐其他健康数据。
- 覆盖区：Huawei 支持层、step history 请求、设备设置、调度与互斥测试。
- 验证：调度、忙碌跳过、完成后续排、停止条件和双向互斥测试通过；Huawei Band 10 在应用后台时
  已观察到定时快同步执行并推进最新有效心率时间戳。

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
  - 运动摘要使用独立开关、默认关闭；只有明确打开后才读取 Huawei 已有运动汇总，并只上传
    类型、时间、时长、距离、热量和汇总心率，不读取或发送路线、位置、设备地址与自由文本；
    单条异常运动会单独跳过，整个运动读取失败时仍发送原有步数、心率和睡眠 payload；
  - 时间戳一律带时区偏移的 ISO 8601，服务端不需要猜时区；
  - 上传游标按设备存偏好；失败不推进游标，`Result.retry()` 走 WorkManager 自带退避。
  - 设置页可查看最近的上传日志、按结果筛选、查看完整 Payload 并复制；日志列表和详情页沿用
    应用原生主题文字色、点击反馈与分隔线，不额外引入红绿状态色、圆角卡片或胶囊标签。
- 幂等边界：服务端是合并不是覆盖——步数取较大值、心率按时间戳去重、睡眠按时间跨度重叠
  判断同一晚并保留更完整版本，运动摘要按稳定 ID 更新。fork 侧保留 24 小时回看窗口和睡眠上传
  游标；同一晚后续变长时，新结束时间会越过旧游标并重传，由服务端替换较短版本，不会重复计入汇总。
- 睡眠 session 只在「结束时间比我们手上最新样本早 10 分钟以上」时上传。这段等待只用于避免
  暂时展示仍在生长的半截睡眠，不再承担防重复职责；醒来后首次取得足够新的样本即可上传。
- 需要 `android.permission.INTERNET`：上游在 `AndroidManifest.xml` 里用 `tools:node="remove"`
  主动摘掉了它，网络功能全部走独立的 Internet Helper App。走 Internet Helper 等于把刚踢掉的
  第二个 App 请回来，所以 fork 保留该权限。INTERNET 是安装期权限，用户侧不会多一次弹窗。
  副作用：Internet Helper 设置项自动隐藏；Garmin 设备会多出「互联网」设置屏（默认关闭，
  防火墙默认 BLOCK，不构成放宽）。
- 覆盖区：
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedHealthPayload.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedWorkoutPayload.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedWorkoutReader.kt`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth/SelfHostedWorkoutSync.kt`
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
- 验证：`SelfHosted*` 定向单测 42 项通过，其中运动摘要覆盖默认关闭、明确开启、逐条异常隔离、
  整体失败回退、隐私白名单、稳定 ID 和空值语义；
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

### 首页切后台不再序列化共享缓存

- 目的：修复打开 Dashboard 后切到其他应用时偶发的 `ConcurrentModificationException` 崩溃。
- 根因：旧实现把正在由多个卡片后台任务写入的 `DashboardData` 整体放进 Fragment saved state；
  Android 在 `ControlCenterv2` 停止时序列化其中的 `generalizedActivities`，可能与 Today 卡片的
  清空／追加撞在一起。
- 行为：
  - `DashboardFragment` 只保存当前日期，不再保存整份运行中缓存；
  - `DashboardData` 只携带日期范围、设备筛选等查询参数；
  - 每张卡片在自己的后台任务中计算数据，再把独立结果交回主线程绘制；
  - Today 卡片不再从后台线程修改共享、可序列化的活动列表。
- 来源：选择性回移上游 commit `0e598a9a1`（`Dashboard: Refactor away cached values from
  DashboardData`）。
- 覆盖区：`DashboardFragment`、`activities/dashboard/` 下 24 个 Dashboard 文件、
  `DashboardUtils`，并移除已不再使用的 `CachedValue`。
- 验证：崩溃路径静态检查、针对性单测和 release 构建通过；真机两次冷启动后从首页切到桌面，
  `onStop` 正常、进程继续存活，安装时间窗内无新崩溃。

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

### HyperOS 手机闹钟联动 Band 10

- 目的：手机闹钟响铃时，让已配对的 Huawei Band 10 同步震动提醒，铃声结束后自动停止。
- 行为：
  - 通知设置页新增开关「手机闹钟同步到手环」，默认开启；关闭后动态注销监听并结束当前
    提醒；应用退出或服务销毁时同样清理未结束的提醒；
  - 同时识别系统闹钟通知（`category=alarm`，结合全屏 intent、insistent flag，或 ongoing
    加 alarm 音频用途判断正在响铃）与经典 AOSP/Google Clock 公开广播，两条链路统一桥接到
    同一个状态机，避免重复或遗漏；不读取通知标题、正文或私人备注；
  - 响铃开始下发原生闹钟指令（service 0x08 / command 0x09），结束时下发停止指令
    （0x08 / 0x0A）；只对 Huawei Band 10 生效；
  - 附带一个仅 debug 编译可见的「闹钟探针」调试工具（其他调试项 → Huawei phone alarm
    probe），用于手动构造并发送单个未加密候选协议包、查看规范化 hex，辅助后续验证时间
    单位、snooze 语义、ACK 方向等尚未完全确认的协议细节；每次发送需弹窗人工确认，每个
    请求令牌只能触发一次，正式 release 编译不含该入口。
- 覆盖区：
  - `externalevents/PhoneAlarmBridge.java`、`PhoneAlarmNotificationDetector.java`、
    `AlarmClockReceiver.java`、`NotificationListener.java`
  - `devices/huawei/packets/PhoneAlarm.java`
  - `service/devices/huawei/requests/PhoneAlarmRequest.java`、`SendNotificationRequest.java`
  - `service/devices/huawei/HuaweiSupportProvider.java`、`ResponseManager.java`
  - `service/DeviceCommunicationService.java`
  - debug-only：`activities/debug/HuaweiPhoneAlarmDebugProbeFragment.kt`、
    `devices/huawei/packets/HuaweiPhoneAlarmDebug*`、
    `service/devices/huawei/HuaweiPhoneAlarmDebugProbeContract.java`、
    `HuaweiPhoneAlarmDebugProbeSender.java`
- 验证：`PhoneAlarmTest`、`PhoneAlarmBridgeTest`、`PhoneAlarmNotificationDetectorTest` 共
  19 项定向单测通过；私仓已在真实 HyperOS 闹钟响铃与停止场景实机验证。
- 限制：手表侧时间单位、snooze 语义、ACK 方向仍未完全确认，探针工具用于继续验证，
  不代表协议已固化。

### 服务被系统重建后自动恢复连接

- 目的：HyperOS 等系统在后台清理场景下可能只重新创建 `DeviceCommunicationService`
  而不携带任何明确指令，此前这种情况下应用不会自动恢复连接，需要用户手动打开 App。
- 行为：
  - 服务创建后 5 秒内若没有收到任何明确的连接、断开指令或 `START_STICKY` 空 intent，
    按设备原有的自动重连设置尝试恢复连接；
  - 收到 `START_STICKY` 空 intent（常见于系统一键清理后重建服务）时同样主动恢复连接；
  - 收到明确的连接或断开指令时取消上述延迟兜底，避免重复直连或覆盖用户主动发起的断开；
  - 启用「扫描后重连」的设备继续走原有扫描路径，不被这个兜底重复触发。
- 覆盖区：`service/DeviceCommunicationService.java`
- 验证：`DeviceCommunicationServiceRestartTest` 4 项定向测试通过（空 intent 触发恢复、
  只创建服务触发延迟恢复、明确指令不误判、明确指令取消兜底）；私仓已在真机通过一键清理
  与单独上滑清理两种场景验证自动重连。

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
