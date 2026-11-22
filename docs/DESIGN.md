# CF 优选 · Android 应用设计方案

> 目标：把 `cf-optimize.sh` 的 Cloudflare CDN IP 优选能力做成 Android 原生应用。
> 本文只覆盖设计，不含实现代码。确认后按第 13 节分阶段落地。

---

## 1. 结论先行

1. **并发完全可行，且是必须的。** 实测在同一出口下，并发从 20 提到 300，吞吐近似线性增长（7.4 → 63.8 次/秒），TLS 时延中位数维持在 600–680 ms 不劣化，说明瓶颈不在本机、不在协议栈。
2. **失败率高是 IP 池的固有属性，不是并发造成的。** 串行（并发=1）测试 40 个 IP，仍有 21 个超时。历史 CSV 也印证：800 个候选只有 269 个完成握手（33.6%），其中零丢包仅 45 个（5.6%）。
3. **单 APK，不需要任何云端后端。** 探测在手机本地完成，结果只对手机当前出口有效。
4. **界面用 Jetpack Compose + Material 3 Expressive。** 这台设备 23.6 GB 内存 / arm64，不存在手表项目里的低内存约束。

---

## 2. 实测依据

测试位置：一台 Wi-Fi 接入的 Mac，与目标手机处于同一 AP、同一出口。
目标域名：`www.cloudflare.com`，`--http1.1`，超时 4 s，每 IP 1 次。

### 2.1 并发与吞吐

| 并发 | 耗时 (s) | 完成探测 (次) | 吞吐 (次/s) | TLS 中位 (ms) | TLS p90 (ms) |
|---:|---:|---:|---:|---:|---:|
| 20 | 40.29 | 300 | 7.4 | 637 | 1594 |
| 50 | 18.29 | 300 | 16.4 | 635 | 1572 |
| 100 | 11.38 | 300 | 26.4 | 681 | 971 |
| 150 | 8.18 | 300 | 36.7 | 655 | 1285 |
| 300 | 4.71 | 300 | 63.8 | 614 | 912 |

结论：**无饱和迹象，时延未因排队而失真**。1000 候选 × 3 次重复 = 3000 次探测，按并发 100 估算约 25 s 完成，符合"一次扫描几十秒"的交互预期。

> 注：前三行与后两行使用不同随机样本，故"完成探测"列不可横向比较；吞吐与时延可比较。

### 2.2 失败构成（串行 40 个样本）

| 结果 | 数量 | 说明 |
|---|---:|---|
| HTTP 200 | 19 | 可用 |
| 超时（curl 28） | 20 | 该 IP 在 /24 内无监听，属池内噪声 |
| TLS 错误（curl 35） | 2 | 被拒握手 |

推论：**默认 sample 应比"想要的可用数"高约 3 倍**。取 1000 候选，预期约 340 个可用、60 个零丢包，足够筛出稳定的 top-10。
界面必须同时展示"已测 / 有效 / 零丢包"三层计数，单一进度条会让人误以为工具坏了。

---

## 3. 功能范围

| 优先级 | 功能 |
|---|---|
| **P0** | 工程骨架、主题、可编译可安装的空界面 |
| **P1** | 网段获取与快照回退、/24 抽样、并发探测、CSV 导出 |
| **P2** | 进度 UI、实时结果列表、参数面板、时延分布图、**历史落库与统计页** |
| **P3** | Clash / v2rayNG 片段导出、排序切换、失败明细、并发自校准 |

明确不做（本期）：VpnService 接管 DNS、前台服务与熄屏常驻扫描、跨设备同步、桌面小组件。

---

## 4. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Kotlin 2.2.0 | 与本机已缓存的 Kotlin 插件版本一致 |
| UI | Jetpack Compose + Material 3（BOM 2025.07.00） | 动态取色、无 XML 布局；Expressive 专属组件需 material3 1.4+，本期用 1.3.2 的稳定组件 |
| 网络 | OkHttp 4.12 | `EventListener` 直接给出 connect / secureConnect 分段计时 |
| 并发 | Coroutines + `Dispatchers.IO.limitedParallelism` | 结构化并发，取消即释放 |
| 状态 | ViewModel + `StateFlow` + `SnapshotStateList` | 单一数据源，进度与结果分离更新 |
| 持久化 | DataStore（参数）+ JSON 文件（扫描历史） | 历史需跨次数统计，但数据量小，不值得引 Room/KSP |
| 构建 | Gradle 9.0.0 + AGP 8.13.0 + JDK 25 | 本机唯一可行组合（Gradle 8.13 在 JDK 25 上崩溃） |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 26 | 本机只装到 platform 36；设备 API 37 可正常侧载 |
| 包名 | `com.wlh.cfoptimize` | — |

依赖总量约 200 MB（首次构建需联网拉取，之后可 `--offline`）。

---

## 5. 架构与模块

```
cf-optimize-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties / local.properties
├── gradle/wrapper/            Gradle 9.0.0
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/ips-v4.txt                 内置网段快照（接口失败时兜底）
        ├── res/                              自适应图标、strings
        └── java/com/wlh/cfoptimize/
            ├── MainActivity.kt
            ├── core/
            │   ├── Cidr.kt                   CIDR 解析 → /24 块集合
            │   ├── Sampler.kt                无偏抽样（Fisher-Yates）
            │   └── Model.kt                  Candidate / ProbeSample / IpResult
            ├── net/
            │   ├── RangeFetcher.kt           拉 ips-v4 + 24h 缓存 + 快照回退
            │   ├── Prober.kt                 单 IP 探测（Dns 覆盖 + 计时）
            │   └── ScanEngine.kt             并发调度、进度流、取消
            ├── export/
            │   ├── CsvExport.kt              列名严格对齐脚本
            │   └── HostsExport.kt            Clash hosts / 纯 IP 复制
            ├── data/Settings.kt              DataStore 读写参数
            ├── data/RunRecord.kt             单次扫描记录 + IP 快照
            ├── data/HistoryStore.kt          JSON 落盘、原子写、跨次聚合
            └── ui/
                ├── ScanViewModel.kt          状态机 + 副作用
                ├── ScanScreen.kt             主界面
                ├── HistoryScreen.kt          历史与统计
                ├── HistoryViewModel.kt       聚合结果供给
                ├── components/               ProgressHeader / ResultRow / Histogram / ParamSheet
                └── theme/                    Color / Type / Theme（动态取色）
```

分层原则：`net` 与 `core` 不依赖 Android 框架（除 OkHttp），保证可在 JVM 单测里跑。

---

## 6. 扫描管道

```
[1] 取网段   GET cloudflare.com/ips-v4 → 解析 CIDR → 缓存 24h；失败读 assets 快照
[2] 展开     prefix ≤ 24 的段按 /24 切分 → 当前 15 段 / 1,524,736 地址 → 5,956 个 /24
[3] 抽样     全量洗牌 → 取前 N 个 /24 → 每块随机主机位 2..254（避开网络/广播地址）
[4] 探测     每 IP 重复 R 次，独立 TCP+TLS 握手，记录 tcp/tls/失败类型
[5] 聚合     失败率 = (R-成功)/R；TLS/TCP 取成功样本均值；排序 (失败率↑, TLS↑)
[6] 落库     写入本次 best 与 top-20；取消/失败的扫描不写
```

### 6.1 与脚本的对齐与差异

| 项 | 脚本 | 应用 | 说明 |
|---|---|---|---|
| 采样粒度 | /24 | /24 | 一致 |
| 主机位 | `2 + rand(253)` → 2..254 | 同 | 一致 |
| 成功判定 | TLS 握手完成 | TLS 握手完成 | 一致（不要求 HTTP 2xx） |
| HTTP 状态码 | 不记录 | **额外记录** | 能识别"握手成功但 403"的坑 |
| 失败分类 | 不区分 | 超时 / 拒绝 / TLS 三类 | 便于定位 |
| CSV 列 | 5 列 | 默认 5 列一致，可选附加状态码列 | 不破坏下游流程 |

### 6.2 计时语义对齐（易错点）

curl 的 `time_connect` / `time_appconnect` 都**从请求开始计**；OkHttp 的 `connectStart` 在 DNS 之后。因此换算必须是：

```
tcp = connectEnd        - callStart    // 含 DNS，与 curl 一致
tls = secureConnectEnd  - callStart
```

若写成 `connectEnd - connectStart`，结果会系统性偏小。

---

## 7. 并发模型

### 7.1 三层限流

1. **协程层**：`limitedParallelism(concurrency)`，作为唯一真实闸门。
2. **OkHttp Dispatcher**：`maxRequests` 默认 64，但它**只约束 `enqueue()` 的异步调用**。本项目用同步 `execute()`，所以 64 不构成瓶颈 —— 这一条在设计阶段判断有误，实现后据实测修正。真正的闸门只有协程信号量。
3. **连接池**：`ConnectionPool(0, 1, MILLISECONDS)` 禁用 keep-alive，保证每次探测都是全新握手；否则复用会让第二次的 TLS 时间变成 0。

### 7.2 单 IP 探测的实现要点

- **Dns 覆盖**：`base.newBuilder().dns { listOf(InetAddress.getByAddress(ipBytes)) }.build()`。
  用 4 字节 `getByAddress` 而非 `getByName`：前者必然返回 `Inet4Address`，从根上排除 IPv6 混入，且不触发任何 DNS IO。
- **客户端克隆**：必须用 `newBuilder()` 克隆共享线程池与 Dispatcher；`OkHttpClient()` 每次新建会各自起线程池。
- **必设**：`retryOnConnectionFailure(false)`（否则静默重试污染失败率）、`protocols(listOf(HTTP_1_1))`（对齐 curl 的 `--http1.1`）。
- **只读响应头**：`GET /` 后不读 body 直接关闭，省流量与时间。

### 7.3 时间与端口

- 3000 次主动关闭的连接会产生同量 TIME_WAIT，占临时端口范围约 1/10，**不会耗尽**。
- 但连续多轮全量扫描需间隔约 60 s，UI 上做冷却提示。

### 7.4 网络类型分流

| 网络 | 建议并发 | 原因 |
|---|---:|---|
| Wi-Fi | 100（默认） | 实测 300 仍线性，留余量给手机射频 |
| 蜂窝 | 30 | RRC 状态迁移会排队，高并发下时延失真 |
| 以太网/其他 | 100 | 同 Wi-Fi |

切换网络时若正在扫描，直接中止并提示，避免结果混入两种链路。

### 7.5 生命周期（不做前台服务）

按你的决定，本期不申请 `FOREGROUND_SERVICE`、不常驻通知。由此确定三条规则：

| 场景 | 行为 |
|---|---|
| 扫描期间 | `FLAG_KEEP_SCREEN_ON`，阻止自动熄屏；用户手动锁屏则视为中断 |
| 短暂切到后台 | **不主动取消**，扫描继续（几十秒内通常不会被冻结），回来可无缝续上 |
| 进程被系统回收 | 本次扫描**不写入历史**；下次启动读到未完成标记，提示「上次扫描被中断」 |

代价是明确的：锁屏或长时间后台会丢进度。这是"不做前台服务"的必然取舍，UI 上会有一行说明，不做隐藏。

---

## 8. UI 设计

### 8.1 状态机

```
Idle → FetchingRanges → Sampling → Scanning → Finalizing → Completed
                                        ↘ Cancelled
                                        ↘ Failed(reason)
```

单一 `ScanUiState`，由 ViewModel 通过 `StateFlow` 暴露；任何阶段都可取消。

### 8.2 界面结构（单页，360 dp 宽）

```
┌─────────────────────────────────────┐
│ LargeTopAppBar  "CF 优选"   [Wi-Fi] │  ← 网络类型 chip，常驻
├─────────────────────────────────────┤
│ 参数摘要卡（可点开 BottomSheet）      │
│ node.example.com · 1000 · ×3 · 100  │
├─────────────────────────────────────┤
│         ◯ 42%                        │  ← 进度环 + 等宽大字百分比
│   已测 1260 / 3000                  │
│   [有效 402] [零丢包 61] [超时 857]  │  ← 三段计数 chip
│   118 次/s · 预计剩余 15 s           │
├─────────────────────────────────────┤
│ 01  104.17.153.99   364 ms  0.0%    │  ← LazyColumn 实时追加
│ 02  172.67.194.119  410 ms  0.0%    │
│ …                                    │
├─────────────────────────────────────┤
│                          [ 导出 FAB ]│
└─────────────────────────────────────┘
```

一级导航用顶部 `SegmentedButton`（扫描 / 历史），不引入 Navigation 组件；两个页面各自持有 ViewModel，共享 `HistoryStore` 单例。

### 8.3 控件选型（Material 3）

| 位置 | 控件 |
|---|---|
| 标题栏 | `LargeTopAppBar` + 折叠滚动行为 |
| 参数摘要 | `ElevatedCard` + `AssistChip` 行 |
| 进度 | `CircularProgressIndicator`（大号）+ `LinearProgressIndicator`（顶部细条） |
| 计数 | `FilterChip` ×3，颜色区分：有效=primary、零丢包=tertiary、超时=outline |
| 结果列表 | `LazyColumn` + `ListItem`，`key = ip` |
| 排序切换 | `SingleChoiceSegmentedButtonRow`（完成后才启用） |
| 参数面板 | `ModalBottomSheet` + `Slider` / `OutlinedTextField` / `InputChip` |
| 导出 | `FloatingActionButton` → 第二个 `ModalBottomSheet` 选格式 |
| 空态 | 居中插画 + `FilledTonalButton`「开始优选」 |
| 错误 | `Card` + 错误类型 + 「重试」 |

### 8.4 视觉规范

- **配色**：`dynamicLightColorScheme` / `dynamicDarkColorScheme`（Android 12+ 跟随壁纸），低版本回退自定义蓝绿双色。深色模式必须完整支持。
- **数字排版**：所有时延、百分比使用 `FontFeatureSettings("tnum")` 等宽数字，避免刷新时抖动。
- **圆角**：卡片 20 dp、面板顶部 28 dp、chip 全圆角。
- **动效**：进度环 300 ms 缓动；列表新项 `animateItem()` 淡入位移 8 dp；取消时整体 150 ms 淡出。
- **列表行**：左排名（等宽 2 位）、中 IP（等宽）+ 状态码小 chip、右 TLS 大数字（20 sp，primary 色）；行高 64 dp。

### 8.5 性能约束（关键）

- **进度与结果分离更新**：进度用 `StateFlow<Int>` 节流到 10 Hz；结果用 `SnapshotStateList.add()` 逐条追加，绝不整体替换列表。
- **扫描中不排序**：按完成顺序展示，完成后一次性 `sortedWith`。实时重排会让列表跳动。
- **不做逐条重组**：`LazyColumn` 用 `key = ip`，保证追加时只组合新增项。

---

## 9. 参数与默认值

| 参数 | 默认 | 范围 | 说明 |
|---|---:|---|---|
| 域名 | 空（必填） | — | 校验为合法主机名 |
| 候选数 sample | **1000** | 50 – 5956 | 超过 5956 自动 clamp 并提示 |
| 重复次数 repeat | 3 | 1 – 5 | 与原脚本一致 |
| 并发 concurrency | **100** | 20 – 300 | 蜂窝默认降到 30 |
| 超时 timeout | 4 s | 2 – 8 s | 连接超时固定 2 s |
| 网段来源 | 官方接口 | 接口 / 内置快照 | 接口失败静默回退 |

参数面板底部常驻一行提示：`预计可用 ≈ 候选数 × 34%`，随输入实时变化——这是本应用与脚本最大的体验差异，避免"测了 1000 个怎么只有 300 条"的困惑。

---

## 10. 历史与统计

单次结果只能回答"现在谁最快"，回答不了"谁长期稳"。同一出口在不同时段的最优 IP 会漂移，所以每次扫描结束自动落一条记录，用于跨次数聚合 —— 这是本应用相对脚本的核心增量。

### 10.1 数据模型

```kotlin
data class RunRecord(
    val id: String,              // UUID
    val finishedAt: Long,        // epoch millis
    val domain: String,
    val networkType: String,     // WIFI / CELLULAR / ETHERNET
    val sample: Int,
    val repeat: Int,
    val concurrency: Int,
    val timeoutMs: Int,
    val tested: Int,             // 实际发起探测的 IP 数
    val valid: Int,              // 完成握手的 IP 数
    val zeroLoss: Int,           // 零丢包的 IP 数
    val durationMs: Long,
    val rangesSource: String,    // remote / snapshot
    val best: IpSnapshot,        // 本次第一
    val top: List<IpSnapshot>    // 前 20，用于回看
)

data class IpSnapshot(
    val ip: String,
    val lossPct: Double,
    val tlsMs: Double,
    val tcpMs: Double,
    val okCount: Int,
    val httpCode: Int?
)
```

只存前 20 而非全量：统计只需要头部样本，全量落盘会让文件随扫描次数线性膨胀（1000 条 × 每天一次 × 一年 ≈ 36 万行）。

### 10.2 存储选型

**JSON 文件 + 内存聚合**，用 Android 内置 `org.json`，不引入 Room。

- 路径：`filesDir/history.json`
- 原子写：先写 `history.json.tmp`，再 `renameTo` 覆盖，避免进程被杀时产生半损文件
- 上限：保留最近 **365 条**，超出按 `finishedAt` 淘汰
- 载入：启动时一次性读入内存（365 × 20 ≈ 7300 条，约 1 MB，可忽略）
- 容错：解析失败时把原文件改名为 `history.corrupt.<时间戳>` 留证，然后从空库重建，绝不静默丢数据
- 未完成标记：扫描开始时在 DataStore 写 `pendingScan=true`，完成或取消时清除；启动时若读到 true，说明上次被系统终止，提示用户而不落库（与历史文件解耦，详见 7.5）

选型理由：数据量与查询复杂度都够不上 Room 的门槛，而 Room 需要额外引入 KSP 插件与注解处理器，会明显增加本机构建的失败面。若将来要做"某 IP 的时延时间序列"这类查询，再迁移到 Room，届时只需替换 `HistoryStore` 一个类。

### 10.3 聚合口径

| 指标 | 定义 |
|---|---|
| best 次数 | 该 IP 在历史中被评为第一的次数 |
| 首次 / 最近 | 首次与最近一次成为第一的时间 |
| 当 best 时的平均 TLS | **仅统计它胜出那几次**，不混入它失败的次数 |
| 稳定度（登顶率） | best 次数 ÷ **进入前 20 的次数**。历史只存 top-20，拿不到"是否被抽样"这个分母，所以它衡量的是"进了前列之后能否夺冠"，不是"被采到之后的胜率"。 |

最后一项是关键：只看 best 次数会把"常被采到"误当成"稳定"。稳定度才是用户真正想要的结论。

### 10.4 界面

扫描页与历史页用顶部 `SegmentedButton` 切换，不引入 Navigation 组件。

```
┌─────────────────────────────────────┐
│  [ 扫描 ]  [ 历史 ]                  │
├─────────────────────────────────────┤
│  汇总卡                              │
│  扫描 12 次 · 3 个 IP 登顶 · 104.17… │
├─────────────────────────────────────┤
│  [ IP 榜 ]  [ 时间线 ]               │
├─────────────────────────────────────┤
│ 1  104.17.153.99   7 次   364 ms    │
│ 2  172.67.194.119  3 次   410 ms    │
├─────────────────────────────────────┤
│ 时间线：11-22 20:47 · node.x.com     │
│         Wi-Fi · 269/800 · 104.17…    │
└─────────────────────────────────────┘
```

- **IP 榜**：排名、IP、best 次数、稳定度、最近一次 TLS、最近时间
- **时间线**：每次扫描一行，点开看该次 top-10 与参数；长按删除单条；顶部菜单可清空
- 空态：一句"完成首次优选后，这里会累积统计"

写入时机严格限定在 `Finalizing → Completed`，取消与失败不落库，避免污染统计。

---

## 11. 导出

| 格式 | 内容 |
|---|---|
| **CSV** | `IP,失败率(%),平均TLS握手(ms),平均TCP连接(ms),成功次数`，与脚本逐字一致；设置里可附加 `状态码` 列 |
| **Clash.Meta** | `hosts:\n  <域名>: <IP>` 片段，直接粘进配置 |
| **v2rayNG** | 仅复制最优 IP 到剪贴板，并提示填入节点「地址」字段 |
| 系统分享 | `FileProvider` + `ACTION_SEND`，不落外部存储 |

写文件统一走 SAF（`ACTION_CREATE_DOCUMENT`），无需存储权限。

---

## 12. 权限与合规

- 仅 `INTERNET` + `ACCESS_NETWORK_STATE`，**无任何运行时授权弹窗**。
- 不读 Wi-Fi SSID：Android 13+ 读 SSID 需要 `NEARBY_WIFI_DEVICES`，为保持零授权，只显示「Wi-Fi / 蜂窝 / 以太网」。
- 首次启动展示一句说明：*结果仅反映本机当前网络出口的链路质量*。
- 无统计、无联网上报（除官方网段接口）。

---

## 13. 交付阶段与验收

| 阶段 | 交付 | 验收方式 |
|---|---|---|
| P0 | 可编译空壳 | `assembleDebug` 通过，`adb install` 成功，桌面图标可见 |
| P1 | 核心链路 | 单测通过；真机跑 200 候选，CSV 行数 = 有效数 + 1 |
| P2 | 完整 UI + 历史 | 1000 候选全程 < 60 s，进度单调不回退，列表滚动 60 fps；历史页统计与实际一致 |
| P3 | 导出与打磨 | 三种导出均可用，Clash 片段能被客户端解析 |

### 可执行验收清单

1. 单测：5956 块抽样无重复、分布通过 χ² 检验；排序严格按 (失败率, TLS)；CSV 表头逐字匹配。
2. 真机：`sample=1000, repeat=3, concurrency=100`，Wi-Fi 下完成时间 < 60 s。
3. 交叉验证：应用 top-10 与 Mac 上 `curl --resolve` 的 top-10 有交集（同出口才可比）。
4. 历史：连续两次扫描后，IP 榜的次数、稳定度、最近时间与实际一致；`adb shell am force-stop` 后重进数据仍在；手工损坏 JSON 能触发重建而非崩溃。
5. 稳定性：连续跑 3 轮，无 ANR、无内存增长（`dumpsys meminfo` 对比）。

---

## 14. 风险与未决

| 风险 | 影响 | 应对 |
|---|---|---|
| 首次构建需联网拉 Compose/OkHttp（约 200 MB） | 构建失败 | 先跑一次联网构建，之后可 `--offline` |
| 手机 Wi-Fi 并发上限可能低于 Mac | 高并发下时延失真 | 参数可调；P3 做「并发自校准」：三档各测 30 s，选吞吐最高且 p90 不劣化的一档 |
| 蜂窝网络失败率更高 | 用户误判工具失效 | 蜂窝下默认并发 30 并在结果页标注「蜂窝链路」 |
| 设备为 API 37，本机只有 platform 36 | 只能用 targetSdk 36 | 已验证可侧载；不引入任何 API 37 特性 |
| 部分 IP 握手成功但返回 403 | 排序选到不可用 IP | 记录并展示状态码，P3 提供「仅显示 2xx」筛选 |

遗留决策：

1. 历史保留上限取 365 次是否合适（超出按时间淘汰）。
2. 域名输入是否需要剪贴板一键粘贴（见第 9 节）。

已关闭：

- 历史持久化 → JSON 文件 + 内存聚合，不引 Room（第 10 节）。
- 前台服务 → 不做，用 `FLAG_KEEP_SCREEN_ON`；锁屏或长驻后台会丢进度（第 7.5 节）。

---

*本文档为设计稿。工程实现见 `android/`，落地状态如下。*

## 15. 实现状态

工程路径：`android/`（`rootProject.name = "cf-optimize"`，包名 `com.wlh.cfoptimize`）。

| 项 | 状态 | 证据 |
|---|---|---|
| 工具链 | 通过 | Gradle 9.0.0 + JDK 25 + AGP 8.13.0 + Kotlin 2.2.0，`assembleDebug` 成功 |
| 核心逻辑 | 通过 | 14 个 JVM 单测全绿：CIDR 解析、/24 展开（真实网段 5956 块）、无偏抽样、排序、CSV、聚合 |
| 界面 | 已实现，未真机验收 | Compose 单页 + 参数 BottomSheet + 历史页 |
| 真机安装 | **受阻** | 设备锁屏，ColorOS `PackageParsingV2Activity` 拦截，`pm install-commit` 阻塞，`adb install` 返回 `-99` |

已知实现与设计的偏差：

1. **Dispatcher 限流**：见 7.1，同步调用不受 `maxRequests` 约束，已修正文档。
2. **稳定度口径**：见 10.3，实现为"登顶次数 ÷ 进 top-20 次数"。
3. **Material 3 Expressive**：改用稳定版 M3 1.3.2，未用 Expressive 专属组件。
4. **状态码列**：CSV 默认不加，与脚本严格一致；界面上展示状态码。

