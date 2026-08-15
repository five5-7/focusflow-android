# FocusFlow Android prototype

当前稳定版本：**1.6**。V2 活动模式正在 `v2/activity-mode` 分支开发，尚未合并到主分支。功能更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## First open in Android Studio

The project uses Android Gradle Plugin 8.6.1 and its matching Gradle 8.7 wrapper.
Open the `focusflow-android` folder itself in Android Studio and let it download
`gradle-8.7-bin.zip` on the first sync. Do not upgrade Gradle or the Android
Gradle Plugin before the first build.

AndroidX is enabled in `gradle.properties`, which is required by the Compose
dependencies used by this prototype.

## Optional cloud APK build

The included GitHub Actions workflow builds a debug APK on a GitHub runner.
Push this folder to a GitHub repository, then open **Actions** and run
**Build FocusFlow APK**. Download the `FocusFlow-1.6-apk` artifact after the
workflow succeeds.

本项目是一个面向 Android 的本地优先日程与执行辅助原型。核心目标不是维护一张完整日历，而是降低记录压力、按当前状态调整提醒，并在错过计划后帮助恢复。

## 当前实现

- 今日建议页、收集箱、计划、设置四个入口
- 无需分类或定时的快速捕捉，内容保存在设备本地
- V2 分支的“开始活动”支持活动类型、自定义名称、预计时长／截至时间，以及可选的下一步
- 今日页实时显示剩余时间、预计结束、下一步和延长记录；到点后进入明确的转场确认
- 活动结束前温和预告；到点通知可直接结束、延长十分钟或打开完整转场
- 完成、推迟与跳过会写入本地活动记录；跳过后自动进入收集箱，供之后改期、缩短或暂停
- 收集箱中的重新安排项目可选择改期时间、缩短为十分钟、暂停或放弃
- 已改到明天的项目会以 Android 后台提醒重新出现；暂停项目可从计划页恢复
- 改期任务的通知支持完成、延后一小时，或送回收集箱重新决定
- 快速记录支持“明天要做（不定时间）”：翌日上午温和提醒，但不把它伪装成固定预约
- 设置页可保存单程通勤时长、校内出行方式、楼内缓冲与电动车电量；不需位置权限，初始值可随实际体验校正
- 内置紫金港教学区、实验中心、图书馆与东田径场的区域级目录；路线为保守的初始估计，支持后续校正
- 根据已上传的课表截图生成待确认的课程预览；只有用户确认的课程才会保存并用于计算课程间空档
- 可手动新增和编辑课程，以处理截图地点不完整、临时调课与课表变化
- 可在应用内创建每周目标；依据已确认课程、区域通行与缓冲推荐候选空档，并一键安排下一次本地提醒
- 目标任务完成后自动计入本周进度；未达标时保留下一处候选空档，而非判定计划失败
- 目标任务超过两小时仍未确认时会回到收集箱；同一目标已有待执行安排时不会重复塞入日程
- 目标将“预计占用时间”与“完成标准”分开：支持时长、次数或成果型任务，并可设置状态不好时的最低版本
- 目标任务在应用内和通知中都可标记为“完整完成”或“最低版本”，两类完成次数分别统计
- 教程／链接可先进入资料收集箱，再由用户选择为当前标准；新目标可关联到具体教程章节或练习
- 完成目标任务后可选记录难度与主要阻碍；目标卡会展示最常见阻碍，作为调整时段、地点与方法的依据
- 设置页提供本地“改进清单”入口，便于记录后续希望深化、修复或新增的功能
- 设置页还提供 V2／V3 路线图候选项；可勾选希望保留或优先的功能，作为后续开发清单
- 计划页提供本周低压力回顾：根据目标进度、最低版本与反馈阻碍提出保持、缩小、换时段或调整方法的建议
- 启动时请求通知权限；没有通知权限时，其他功能仍可离线使用
- V2 分支可设置活动提醒、提前预告分钟数、连续延长上限与到点提醒强度

## 下一步

1. 在 OPPO ColorOS 设备上验证 V2 预告、到点通知、通知动作和重启恢复。
2. 根据真机结果补充省电限制引导，并校正转场文案与默认时长。

## 构建

用 Android Studio 打开本目录，等待它下载 Gradle 与 Android 依赖后运行 `app`。目标设备需 Android 8.0（API 26）或更高版本。
