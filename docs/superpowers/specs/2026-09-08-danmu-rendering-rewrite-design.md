# 弹幕渲染重写设计（单 View 统一绘制）

日期：2026-09-08
状态：已获用户批准（2026-09-08 对话确认）

## 背景与问题

当前 `SimpleDanmuView` 采用「每条弹幕一个 `StrokedTextView` + 一个 `ObjectAnimator`」的实现：

- N 条同屏弹幕 = N 个子 View + N 个动画回调，每帧 N 次属性更新；
- 子 View 高频增删（虽有回收池缓解），测量/布局/层级开销随弹幕量线性增长；
- 每个弹幕文本绘制两遍（描边 + 填充）；
- 多个移动的子 View 把每帧脏区域撕成大量横向带状区域。

在性能较强的设备上尚可运行，但在老机顶盒等弱设备上（用户反馈"非常卡"），以及竖屏视频被迫 GPU 合成的场景下，该实现余量不足，成为整个渲染链路中最脆弱的一环。

## 目标

- 将弹幕渲染改为**单 View + Canvas 统一绘制**：每帧 1 次 `invalidate()`、1 次 `onDraw` 画完全部弹幕；
- 对外 API 完全不变，`LivePlayActivity` 等调用方零改动；
- 所有设置项（字号/透明度/速度/显示区域/开关）对在屏弹幕**立即生效**（用户已确认，优于现状的"仅字号立即生效"）；
- 核心逻辑抽取为无 Android 依赖的纯 Kotlin 引擎，可用 JVM 单元测试覆盖。

## 非目标

- 不新增弹幕类型（顶部/底部固定弹幕仍不支持，保持仅滚动弹幕）；
- 不修改弹幕网络层（`DanmuTcpClient`/`DanmuParser`）；
- 不解决竖屏视频 GPU 合成的系统层问题（另行验证处理）；
- 不改变弹幕视觉样式（黑色描边、描边宽度随字号缩放、轨道布局规则均保持一致）。

## 架构

拆为两层：

### 1. `DanmuEngine`（纯 Kotlin，无 Android import，JVM 可测）

职责：弹幕生命周期与运动学计算。

- 输入弹幕：`(text: String, colorRgb: Int)`，由 View 层从 `DanmuItem` 适配；文本宽度通过注入的测量函数 `(String, textSizePx) -> Float` 获得并**按当前字号缓存**；
- 时钟注入 `() -> Long`（毫秒），测试可替换；
- 状态：轨道数组（复用现有"按字号自适应 4–30 条轨道、轨道高 = 字号 × 1.6"规则）、在屏弹幕列表（起始时间锚点、起始进度、轨道号、缓存宽度、颜色、文本）；
- 行为：
  - `add(text, colorRgb)`：寻找最早空闲轨道，无空轨道则丢弃（与现状一致）；
  - `update(now)`：剔除已完全飞出屏幕的弹幕；
  - 位置计算：`progress = (now - anchorTime) / duration`，`x = viewWidth - progress × (viewWidth + textWidth)`；
  - 速度变化：以当前时刻重新锚定每条弹幕（记录当前 progress，重置 anchorTime=now、duration 按新速度），保证位置连续不瞬移；
  - 字号/区域变化：重建轨道并重新锚定在屏弹幕的纵向位置（字号变化同时刷新宽度缓存）；
  - `clear()`：清空弹幕与轨道占用。

### 2. `SimpleDanmuView`（薄壳，保持类名与 API）

- 保持公开 API：`addDanmu(DanmuItem)`、`clear()`、`isDanmuEnabled`、`danmuSizeScale`、`danmuAlpha`、`danmuSpeedScale`、`danmuAreaRatio`；
- 持有一个 `Paint`（描边/填充两态复用）+ 一个 `DanmuEngine`（注入 `paint.measureText` 与系统时钟）；
- 帧循环：`postInvalidateOnAnimation()` 驱动；有在屏弹幕时持续，无弹幕/开关关闭时停止（零功耗）；停止时绘制为空；
- `onDraw`：先以描边样式画完全部弹幕、再以填充样式画完全部弹幕（两次全量 pass，避免逐条切换 Paint 状态）；透明度作用于填充色的 alpha 通道（与现状一致）；
- 设置项 setter：更新 engine 状态并触发 `invalidate()`，在屏弹幕立即生效；
- `isDanmuEnabled = false`：立即清空在屏弹幕并停止帧循环（与现状一致）；
- `onSizeChanged`：同步 engine 的容器尺寸并重建轨道（与现状一致）。

## 行为对齐清单

| 行为 | 现状 | 新实现 |
| --- | --- | --- |
| 轨道数 | 按字号自适应，4–30 | 相同 |
| 轨道满时新弹幕 | 丢弃 | 相同 |
| 基础时长 | 8000ms ÷ 速度缩放 | 相同 |
| 关闭弹幕 | 立即清屏 | 相同 |
| 描边样式 | 黑色，宽度 = 1dp × 字号缩放 | 相同 |
| 设置生效时机 | 仅字号立即，其余仅新弹幕 | **全部立即生效**（已确认） |

## 测试

新增 `app/src/test/java/com/blive/tv/danmu/DanmuEngineTest.kt`（JUnit4，沿用现有测试风格，注入假时钟与固定测量函数）：

- 轨道分配：优先使用最早空闲轨道；轨道满时丢弃；
- 位置计算：给定时间点 x 坐标正确；完全飞出后被剔除；
- 速度重锚定：改变速度瞬间位置连续，后续按新速度运动；
- 区域比例变化：轨道重建且数量符合新区域；
- 清空后无在屏弹幕。

现有 `DanmuParserTest` 等不受影响；e2e 测试基于 logcat `DanmuTcpClient` 断言，不受影响。

## 验证

1. `./gradlew test` 全部通过；
2. `./gradlew assembleDebug` 编译通过；
3. 真机验证（用户侧）：弹幕开关、各项设置即调即生效、密集弹幕场景流畅度对比。
