# CF 优选（Android）

Cloudflare 边缘 IP 并发测速与优选。`cf-optimize.sh` 的原生 App 版本：探测在手机本地完成，
结果只反映本机当前网络出口的链路质量。

## 构建

```bash
export JAVA_HOME="<JDK 21+ 的路径>"      # macOS + Android Studio 通常是
                                        # /Applications/Android Studio.app/Contents/jbr/Contents/Home

cd android
./gradlew :app:assembleDebug        # 产物 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 纯 JVM 单测，无需设备
```

> **Gradle 8.13 在 JDK 25 上会崩溃**，报错只有一行 `25.0.3`（`JavaVersion.parse` 认不出 25.x），
> 连 `gradle help` 都会失败。本工程用 Gradle 9.0.0，wrapper 已随仓库提供，直接 `./gradlew` 即可。

- compileSdk / targetSdk = 36，minSdk = 26（实测在 API 37 设备上可正常侧载）
- 首次构建需联网拉取 Compose / OkHttp（约 200 MB），之后可加 `--offline`
- 构建前需自建 `android/local.properties`，内容为 `sdk.dir=<你的 Android SDK 路径>`

## 安装

```bash
adb install -r app/build/outputs/apk/debug/CF优选-v0.1.0-debug.apk
```

> ColorOS / OxygenOS 会在安装前弹出"应用解析"安全检测，且**锁屏状态下会直接失败**
> （`adb install` 返回 `-99`，`pm install-commit` 长时间无响应）。请先解锁屏幕再安装。

## 使用

1. 点「开始优选」，填写节点域名（如 `node.example.com`）。
2. 按需调整候选数（默认 1000）、重复次数（3）、并发（100）、超时（4 s）。
3. 扫描中可看到已测/总数、有效、零丢包、实时速率与剩余时间。
4. 完成后可复制最优 IP、导出 CSV、生成 Clash hosts 片段。
5. 「历史」页累积每次的登顶结果，用于观察最优 IP 是否漂移。

## 与脚本的对齐

- 采样：/24 块内随机主机位 2..254，与脚本一致
- 成功判定：TLS 握手完成（不要求 HTTP 2xx），与脚本一致
- 排序：失败率升序 → TLS 升序，与 `sort -k2,2n -k3,3n` 一致
- CSV 列名：`IP,失败率(%),平均TLS握手(ms),平均TCP连接(ms),成功次数`

## 参数建议

实测（同一 Wi-Fi 出口，300 次探测）：

| 并发 | 吞吐 | TLS 中位 |
|---:|---:|---:|
| 20 | 7.4 次/s | 637 ms |
| 100 | 26.4 次/s | 681 ms |
| 300 | 63.8 次/s | 614 ms |

吞吐近似线性且时延不劣化，故默认 100；蜂窝网络建议降到 30（RRC 状态迁移会排队）。

候选 IP 的有效率约 34%（Cloudflare 网段内并非每个 /24 的每个主机都有监听），
因此 1000 个候选预期只有约 340 个可用、约 60 个零丢包。

## 结构

```
core/    CIDR 解析、无偏抽样、结果模型与排序
net/     Prober（自定义 Dns + EventListener 计时）、ScanEngine（并发）、RangeFetcher
data/    Settings（DataStore）、HistoryStore（JSON 原子写 + 聚合）
export/  CSV / Clash hosts
ui/      ScanScreen、HistoryScreen、ScanViewModel、HistoryViewModel、components
```
