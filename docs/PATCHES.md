# 长期补丁清单

公开版相对 Gadgetbridge 基线 `a5013a932` 保留的行为。使用方法见 [GUIDE.md](GUIDE.md)，版本变化见 [Releases](https://github.com/xiaoyou5602/band-health-sync/releases)。

## 身份与构建

- 独立包名 `nodomain.freeyourgadget.gadgetbridge.toge` 与 Pebble provider authority，可与原版共存；应用和启动入口显示「健康数据」。包名与签名是覆盖升级的身份边界。
- 使用 JDK 17、compile SDK / build tools 37；公开 release 使用固定签名、不混淆。

## 连接与设备

- **Huawei 重连恢复同步**：断线作废旧请求、回包处理和调度；重新初始化时重建协议与同步队列。同步五分钟没有相关协议进展时关闭旧连接，按已有自动重连设置恢复，不取消陪伴绑定。
- **陪伴绑定与在场监听**：绑定成功立即开始监听，兼容 Android 16 的旧接口回调；设备卡片菜单可直接申请绑定，已绑定时禁用入口。取消授权不解绑，不重新进行蓝牙配对。上述修复通过定向测试，手环侧重连与绑定保留仍待实机验收，尚未确认绑定自行消失的根因。
- **系统重建服务后恢复连接**：处理 `START_STICKY` 空指令，以及服务创建五秒后仍无明确指令的情况；遵守自动重连和扫描设置，不覆盖主动断开。系统强行停止或禁止后台运行时无法兜底。
- **保护睡眠监测设置**：连接初始化跳过未经主动设置的 TruSleep / 睡眠呼吸关闭命令；已开启的偏好继续下发，设置页主动开启或关闭均生效。
- **后台心率快同步**：支持的 Huawei 设备默认每三分钟拉取历史心率，可关闭或选 1 / 3 / 5 / 10 分钟。与完整同步互斥，完成后再排下一轮；最新有效心率时间前进才通知其他数据链。

主要代码：`service/DeviceCommunicationService`、`service/GBCompanionDeviceService`、`service/devices/huawei/`、`activities/CompanionPairingFragment`、`adapter/GBDeviceAdapterv2`。

## 天气、闹钟与显示

- **内置天气**：Open-Meteo 提供当前天气、未来 24 小时、今天及未来七天预报，并补充月相；每小时或手动刷新。后台与 Huawei 天气共用保存的坐标，主动使用手机位置才更新坐标，避免网络定位漂移；发送预报检查设备响应状态。
- **手机闹钟联动**：默认识别 AOSP / Google Clock 广播及正在响铃的通知，兼容 HyperOS 专用闹钟通道；Band 10 使用原生开始与停止指令，其他设备使用普通通知。多来源合并为同一会话，通知替换有 1.5 秒宽限；静态未来闹钟、倒计时和普通通知不触发。Band 10 的关闭／贪睡会调用当前 HyperOS 响铃通知的对应动作，实际贪睡间隔遵循手机设置；限定 Android 12+ 系统时钟的已确认按钮结构，通知失效、歧义或未知动作不执行，同轮成功发送后去重。双向联动已获得实机体验确认。连接中断无离线兜底；debug 保留需人工确认的单包协议探针。
- **睡眠与小组件**：睡眠按完整 session 的醒来日期归属，不计清醒阶段；应用卡片和今日小组件显示最新有效心率，睡眠为零仍显示。小组件保留完整布局，进程重建后恢复数据监听且不重复注册。
- **首页后台切换**：保存查询参数，不序列化各卡片共享的活动缓存，避免并发写入引起崩溃。

主要代码：`util/builtinweather/`、`externalevents/PhoneAlarm*`、`Widget`、`activities/charts/SleepAnalysis`、`activities/dashboard/`。

## 导出与备份

- 自动数据库导出默认每 15 分钟兜底；开启取数后导出时，`ACTION_NEW_DATA` 触发五秒防抖。设置页可查看最近结果、触发来源和日志。
- 导出前合回目标快照中当前库缺失的历史，映射设备与用户身份；同主键以当前库为准。合并或完整性校验失败保留原目标。
- debug / release 均提供受 `android.permission.DUMP` 保护的安装前 ZIP 备份入口。使用独立请求标识、唯一任务与 `.partial` 文件，支持校验后清理；普通第三方应用不能调用。

主要代码：`util/PeriodicExporter`、`util/backup/`、`externalevents/InstallerBackupReceiver`。

## 健康同步

- **Health Connect**：有效写入完成后显式唤醒 HCWebhook；广播失败不影响已写入的数据，HCWebhook 自己的周期任务继续兜底，应用不读取其 token。
- **自托管直传**：独立开关，将本地步数、心率和睡眠以 Bearer 鉴权上传至 `/api/health`，使用取数事件与十秒防抖、按设备游标和失败退避；设置页可查看日志及 payload。步数按本地日、心率按五分钟分桶、睡眠按醒来日；时间戳带时区。保留 24 小时回看，睡眠结束至少稳定十分钟后上传，后续变长可重传，由配套服务器幂等合并。
- **可选运动摘要**：默认关闭，上传 Huawei 已有运动的类型、时间、时长、距离、热量和汇总心率；排除路线、位置、设备地址与自由文本。逐条跳过异常，运动读取整体失败不阻断原有健康数据。
- **可选经期上下文**：默认关闭，通过 `/cycle` 单独同步开始日、周期与经期天数；关闭后清除本地字段并请求服务器清除，失败保留待清除标记重试。不下发手环，不计算排卵或受孕窗口。
- 网络同步保留 `INTERNET` 权限；鉴权 token 不写入日志或健康 payload。

主要代码：`util/healthconnect/`、`util/selfhostedhealth/`、`util/cycle/`、`externalevents/NewDataReceiver`。

## 摘取上游改动时

核对身份与签名、导出历史保护、睡眠开关护栏、Huawei 连接生命周期与同步互斥、健康同步触发及游标、醒来日口径和首页缓存边界。涉及的行为运行对应测试，发布前核对 APK。
