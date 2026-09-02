# BLive 代码质量重构任务清单

> 来源：2026-08-31 代码质量检查报告
> 分支：`refactor/code-quality`
> 执行顺序按投入产出比排序，每个任务完成后需通过编译验证。

---

## T1. 日志治理 🔴 严重

### 问题
1. **敏感信息泄露**：
   - `RetrofitClient.kt:22`：`HttpLoggingInterceptor.Level.BODY` 无条件开启，release 包泄露 SESSDATA Cookie、登录响应体。
   - `DanmuTcpClient.kt`：明文打印弹幕 token（`认证包JSON`）、WBI 密钥、完整 API 响应。
2. **热路径日志轰炸**：全项目 236 处 Log 调用，集中在最高频路径：
   - `DanmuTcpClient.readPacket()`：每个数据包 6~8 条日志（含每段字节数）。
   - `LivePlayActivity.handleDanmuMessages()`：每条弹幕 4 条日志。
   - `SimpleDanmuView.createAndShowDanmu()`：每条弹幕 1 条日志。

### 解决方案
- [x] OkHttp 日志拦截器级别按 `BuildConfig.DEBUG` 切换（DEBUG=BODY，RELEASE=NONE）。
- [x] 删除热路径日志：readPacket 循环、handleDanmuMessages 逐条日志、SimpleDanmuView 逐条日志。（readPacket 部分随 T4 完成）
- [x] 删除 `DanmuTcpClient` 中所有敏感日志（token、WBI key、完整响应体、auth JSON）——随 T4 重写一并完成。
- [x] release 通过 ProGuard `-assumenosideeffects` 全局剥离 `Log.d/v`（见 T2），避免 236 处调用点大改。

---

## T2. 构建配置清理 🔴 严重

### 问题
1. **未使用的 Compose 依赖**：`build.gradle` 引入完整 Compose BOM + material3 + activity-compose，但全项目无一个 `@Composable`（纯 Leanback + View 体系），白占数 MB APK 体积。
2. **`minifyEnabled = false`**：无 R8 混淆/压缩，日志代码无法剥离，反编译暴露全部 API 细节。

### 解决方案
- [x] 删除 compose 相关全部依赖、`buildFeatures.compose`、`composeOptions` 块（补充显式 `activity-ktx` 依赖）。
- [x] release 开启 `minifyEnabled = true` + `shrinkResources = true`。
- [x] `proguard-rules.pro` 添加：`-assumenosideeffects` 剥离 `Log.d/v`；补充 Gson/Retrofit/OkHttp/ExoPlayer/Glide 必要 keep 规则。
- [x] 构建 release APK 验证体积缩减与可运行性。（debug 12.4MB → release 4.5MB，R8 通过）

---

## T3. 死代码清理 🟡

### 问题
- `SimpleDanmuView.kt:35`：`danmuQueue` 声明后从未使用。
- `SimpleDanmuView.kt:205`：`updateRunningDanmuSpeed()` 死代码（速度设置已被屏蔽）。
- `DanmuTcpClient.kt:63`：空的 `companion object`。
- `DanmuTcpClient.kt:256-266` 等：大段注释掉的调试代码。
- `LivePlayActivity.kt`：被注释屏蔽的弹幕速度设置面板代码块。

### 解决方案
- [x] 删除以上全部死代码（历史可从 git 回溯）。（另清理 `DanmuParser` 全部 20 处每包日志，使其成为纯 JVM 可测类）

---

## T4. 重写 DanmuTcpClient 🟠 架构

### 问题
1. `stop()` 调用 `scope.cancel()` —— 协程作用域被永久销毁，client 成一次性用品，无法重连。
2. 混用裸 `Thread {}`（start）与协程（心跳/接收），线程模型混乱。
3. 重复造轮子：项目已有 `network/WbiSigner.kt` 和 OkHttp，却重新实现 WBI 签名 + 手写 `HttpURLConnection`。
4. 无自动重连：断线后弹幕直接死掉，只能重进直播间。
5. `isConnected` 跨线程读写，无 `@Volatile` / 无线程封闭。
6. 协议魔法数字散落（16 字节头、action 7/2 等）。

### 解决方案
- [x] 重写为纯协程实现：`SupervisorJob` scope + 单线程上下文封闭可变状态。
- [x] 连接循环：`while (isActive)` + 指数退避自动重连（1s→2s→…→上限 30s）。
- [x] WBI 签名复用 `network/WbiSigner`；BUVid/getDanmuInfo 请求改走 OkHttp（新增 `getDanmuInfoSigned` 端点，`DanmuInfoData.hostList` 兼容 `host_list`）。
- [x] `stop()` 只 cancel 当前连接 Job，scope 保持可重启。
- [x] 协议常量命名化（HEADER_SIZE、OP_AUTH、OP_HEARTBEAT 等）。
- [x] 保持对外 API 签名不变（`start()/stop()/isConnected()` + 两个回调），调用方零改动。
- [x] 顺带修复：`readPacket` 单次 `read()` 不保证填满缓冲区，TCP 分包时会误判断线——改为 `readFully` 循环读取；心跳/认证写操作加锁防帧交错。

---

## T5. 拆分 LivePlayActivity 🟠 架构

### 问题
- 1709 行上帝 Activity：同时管理 4 个 ExoPlayer 实例（player/helperPlayer/fastPlayer/targetPlayer）、Retrofit 回调、设置面板状态机、弹幕收发、房间信息、关注逻辑。
- 无 ViewModel：直接在 Activity `enqueue` Retrofit 回调，配置变更即状态全丢。
- 4 个播放器靠裸字段 + 注释约定的"所有权转移"手动管理，极易泄漏。
- `CountDownTimer` 做 90 分钟刷新，非生命周期感知。

### 解决方案
- [x] 新增 `LivePlayViewModel`：播放信息请求（`suspend` + viewModelScope）、清晰度/线路/编码选择状态、90 分钟定时刷新（协程 `delay` 循环，失败 5 分钟重试）。
- [x] 新增 `PlayerManager`：收敛 4 播放器生命周期（主/竞速双流/切换预加载），对外暴露 `play/playDual/seamlessSwitch` 与就绪/失败事件。
- [x] Activity 只负责：View 绑定、按键分发、订阅 ViewModel 状态渲染；1709 行 → 689 行。
- [x] 网络层：播放页请求改 suspend（ViewModel 内 `awaitCall`）。
- [x] 附带改进：ViewModel 持有弹幕连接（Activity 重建不断线）；配置变更后通过 `requestReplay()` 恢复播放（修复原实现旋转/重建即丢流+泄漏的问题）。

---

## T6. 设置状态统一 + 弹幕 View 池 🟡

### 问题
1. **设置双数据源**：`LivePlayActivity` 启动时读 `UserPreferencesManager`，但播放页内修改（字号/透明度/开关/画质）从不写回，仅 `SettingsDialogFragment` 会写——两处设置互相不同步。
2. **弹幕无 View 复用**：每条弹幕 `new TextView` + `new ObjectAnimator`，结束即弃，TV 盒子高峰期 GC 压力大。
3. `StrokedTextView.onDraw` 每帧绘制两遍文本（描边+填充），且在 `onDraw` 中修改 paint 状态。

### 解决方案
- [x] 播放页内修改设置即调用 `UserPreferencesManager.setXxx` 持久化，与设置页保持一致（随 `LivePlayViewModel` 落地：画质/弹幕开关/字号/透明度均修改即写回）。
- [x] `SimpleDanmuView` 引入 TextView 回收池（动画结束/取消回池复用，tag 防重复回收，池上限 20）；`clear()`/关闭弹幕时先取消动画再统一回收。
- [x] 描边改为在自定义 View 中缓存 paint 配置，避免 onDraw 内重复 setup。（保留二次绘制方案，paint 状态恢复由 isDrawing 守卫保证；未引入额外每帧分配）

---

## T7. 补核心单测 🟡

### 问题
现有 4 个测试全是焦点逻辑；最该测的纯逻辑零覆盖。

### 解决方案
- [x] `PlayStreamResolver`：清晰度/编码/CDN 解析矩阵、降级兜底、无可用流场景（11 个用例）。
- [x] `WbiSigner`：mixinKey 生成（已知向量 `ea1db124...`）、签名正确性（Python 参考实现交叉验证）、特殊字符过滤、确定性。
- [x] `DanmuParser`：二进制协议解析（心跳/认证/明文 JSON/zlib 压缩/多包拼接/未知 cmd/畸形 JSON）。
  - 注：brotli 压缩路径未覆盖（`org.brotli:dec` 仅含解码器，无法在测试中生成压缩数据）。
- [x] 顺带修复存量测试：`ui/main` 4 个焦点测试使用过期 `LiveRoom` 构造器，基线上就无法编译，改为具名参数。

### 测试揪出的真实 bug（已修复）
1. **`DanmuParser.decompressZlib`**：原实现"跳过 2 字节 zlib 头"后用默认 `Inflater`（需完整头），ver=2 压缩流永远解压失败被静默吞掉 → 去掉跳字节逻辑。
2. **`PlayStreamResolver.buildCodecPriority`**：avc 无条件排第一，用户手动选 HEVC 被静默忽略 → 显式选择优先，avc 仅作默认。

---

## T8. 杂项修复 🟡

- [x] UA 字符串收敛为单一常量（`RetrofitClient.WEB_USER_AGENT`，原散落 5 处）。
- [x] 去掉 `RetrofitClient` 的 `gson.setLenient()`（掩盖服务端格式异常）。
- [x] README 技术栈更正：声称 Media3，实际为 ExoPlayer 2.19.1（改为 Media2 描述）。

---

## 验收标准
1. [x] `./gradlew assembleDebug` 与 `assembleRelease` 均编译通过。
2. [x] `./gradlew test` 全部单测通过（34 个用例，含新增 22 个，0 失败）。
3. [x] release APK 体积显著小于重构前（Compose 移除 + R8：release 4.5MB，未压缩 debug 12.4MB）。
4. [x] release 包 logcat 无敏感信息与热路径日志输出（拦截器 NONE + ProGuard 剥离 Log.d/v）。
