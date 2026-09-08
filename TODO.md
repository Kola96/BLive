# TODO - 弹幕渲染重写（待验证）

> 2026-09-08 工作状态快照。弹幕渲染已从「N 个 TextView + N 个 ObjectAnimator」重写为
> 「单 View 每帧统一 Canvas 绘制」。代码已完成，**尚未在本仓库的 Gradle 环境中验证**
> （写代码的机器没有 Android SDK）。晚上在开发机上从「待办」继续。

## 背景

- 用户反馈老机顶盒上 App 非常卡；自家电视看「静态画面 + 竖屏」的电台直播时弹幕明显掉帧。
- 分析结论：弹幕渲染是链路中最脆弱的一环（N View + N 动画 + 每帧大面积脏区域），
  无论是否为用户掉帧问题的唯一根因，都值得重写。另外两个嫌疑（竖屏视频被迫 GPU 合成、
  静态画面导致 SoC 降频）属于系统层，待验证，见文末「延伸排查」。
- 设计文档：`docs/superpowers/specs/2026-09-08-danmu-rendering-rewrite-design.md`
- 实现计划：`docs/superpowers/plans/2026-09-08-danmu-rendering-rewrite.md`

## 已完成

- `danmu/DanmuEngine.kt`（新增）：纯 Kotlin 引擎，无 Android 依赖，负责轨道分配、
  位置/进度计算、变速重锚定（速度切换不瞬移）、过期剔除。时钟与文本宽度测量均为注入式。
- `danmu/SimpleDanmuView.kt`（整体重写）：单 View 统一绘制，每帧 1 次 invalidate + 1 次
  onDraw（先全量描边、再全量填充）；`postOnAnimation` 帧循环，无弹幕时自动停止。
  **对外 API 完全不变**（addDanmu/clear/isDanmuEnabled/danmuSizeScale/danmuAlpha/
  danmuSpeedScale/danmuAreaRatio），LivePlayActivity 与布局 XML 零改动。
- `app/src/test/.../DanmuEngineTest.kt`（新增）：8 个 JVM 用例，已用独立
  kotlinc 1.9.20 + JUnit 4.13.2 真实跑过：**8/8 通过**（先 RED 后 GREEN 的 TDD 流程）。

## 待办（开发机）

1. [ ] `./gradlew test` —— 应为 42 个用例全过（原 34 + 新 8）
2. [ ] `./gradlew assembleDebug` —— 编译通过（SimpleDanmuView 依赖 android.jar，
      写代码的机器没 SDK，编译未验证过，如有编译错误大概率是小的 API 笔误）
3. [ ] 真机验证：
   - [ ] 弹幕颜色/描边观感与旧版一致
   - [ ] 设置面板调字号/透明度/速度/显示区域：**在屏弹幕立即生效**（这是新行为，旧版只有字号立即生效）
   - [ ] 调速度时在屏弹幕不瞬移
   - [ ] 关闭弹幕开关立即清屏，重开正常
   - [ ] 电台/高密度弹幕直播间流畅度对比旧版
   - [ ] 老机顶盒验证（如有条件）
4. [ ] 验证无误后删除本文件

## 关键设计决策（勿轻易改）

- 轨道规则保持原样：轨道高 = 字号 × 1.6，数量按显示区域自适应、钳制 4–30；
  无空闲轨道时丢弃新弹幕；基础时长 8000ms ÷ 速度缩放。
- 轨道复用判定从「绝对时间戳」改为「占用者进度」（width+spacing)/(viewWidth+width)，
  这样对变速免疫。
- 所有设置对在屏弹幕立即生效（已与需求方确认，替代旧版的不一致行为）。
- 透明度只作用于填充色 alpha，描边保持不透明黑（与旧版一致）。

## 延伸排查（与本次重写独立的掉帧嫌疑，暂不动手）

1. 竖屏直播流可能带旋转元数据，电视 HWC 不支持图层旋转 → SurfaceFlinger 退化 GPU 合成，
   弹幕每帧触发全屏 2MP 缓冲合成。验证：播放问题直播间时
   `adb shell dumpsys SurfaceFlinger` 看视频图层是 DEVICE 还是 GLES 合成。
2. 静态画面解码负载低 → SoC 降频 → UI 帧超时。验证：对比播放静态/动态直播间时的
   CPU 频率（/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq）。
3. 待确认数据点：竖屏+动态画面的直播间弹幕是否也卡（区分上面两个嫌疑的关键实验）。
