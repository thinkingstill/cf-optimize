# Cloudflare 优选工具

从 Cloudflare 官方 IPv4 网段随机抽样，逐个做 TCP + TLS 握手，按「失败率 → TLS 时延」排序，
挑出当前网络下最快的边缘 IP。

同一套测量口径，两个交付物：

| 形态 | 实现 | 适用场景 |
|---|---|---|
| **CLI** | `cf-optimize.sh`（bash + curl + perl + awk） | 路由器旁的 Mac / Linux / NAS，可挂 cron |
| **Android** | 原生 App（Kotlin + Compose） | 手机直接测；结果落库，可观察多次优选的变化 |

## 为什么需要它

Cloudflare 的边缘 IP 是 anycast：同一个域名在不同运营商、不同地区会被调度到不同机房，
握手时延能差出几倍。把域名的 DNS 解析固定到一个更近的 IP，可以明显降低首包与 TLS 握手时间。

但"哪个 IP 更快"没有通用答案 —— 它取决于你的出口线路。所以必须在**你自己的网络里**测。

## 实测数据

测试条件：Wi-Fi 接入，`www.cloudflare.com`，HTTP/1.1，超时 4 s，每 IP 1 次，共 300 次探测。

| 并发 | 耗时 | 吞吐 | TLS 中位 | TLS p90 |
|---:|---:|---:|---:|---:|
| 20 | 40.3 s | 7.4 次/s | 637 ms | 1594 ms |
| 50 | 18.3 s | 16.4 次/s | 635 ms | 1572 ms |
| 100 | 11.4 s | 26.4 次/s | 681 ms | 971 ms |
| 150 | 8.2 s | 36.7 次/s | 655 ms | 1285 ms |
| 300 | 4.7 s | 63.8 次/s | 614 ms | 912 ms |

吞吐近似线性、时延不因排队而劣化，说明瓶颈不在协议栈。1000 候选 × 3 次重复，
并发 100 下约 25 秒完成。

**有效率约 34%。** 串行（并发=1）测 40 个 IP 仍有 21 个超时，历史结果也吻合：
800 个候选只有 269 个完成握手，零丢包仅 45 个。原因是 Cloudflare 网段内并非每个
/24 的每个主机位都有监听。所以默认候选数取 1000，预期约 340 个可用。

## CLI

```bash
# 基本用法
./cf-optimize.sh your.domain.com

# 指定候选数 / 重复次数 / 并发
SAMPLE=1000 REPEAT=3 PARALLEL=25 ./cf-optimize.sh your.domain.com
```

依赖：`curl`、`perl`、`awk`、`sort`、`xargs`。

输出 `cf-ip-result.csv`：

```
IP,失败率(%),平均TLS握手(ms),平均TCP连接(ms),成功次数
104.17.153.99,0.0,364.70,179.51,3
```

## Android

源码在 [`android/`](android/)，构建与使用见 [android/README.md](android/README.md)。

- 候选数可调，默认 1000
- 扫描过程有实时进度：已测/总数、有效、零丢包、速率、剩余时间
- 完成后可复制最优 IP、导出 CSV、生成 Clash `hosts` 片段
- 「历史」页累积每次的登顶结果，用来看最优 IP 是否漂移

## 测量口径

三件事决定了结果是否可信：

1. **按 /24 抽样**。官方网段展开成 5956 个 /24 块，全量洗牌后取前 N 个，每块内随机主机位 `2..254`。
2. **SNI 保持原域名**。CLI 用 `curl --resolve 域名:443:IP`；Android 用 OkHttp 自定义 `Dns` 把域名解析固定到目标 IP。
   两者都保证 TLS 的 SNI、HTTP 的 `Host`、证书校验看到的仍是原域名 —— 否则握手会被服务端直接拒绝。
3. **计时从请求开始算**。`tcp = connectEnd - callStart`、`tls = secureConnectEnd - callStart`，
   与 curl 的 `time_connect` / `time_appconnect` 语义一致。

成功判定是**TLS 握手完成**，不要求 HTTP 2xx，与脚本一致；但 App 会额外记录状态码，
用于识别"握手成功却返回 403"的 IP。

## 目录

```
.
├── cf-optimize.sh          命令行版本
├── docs/
│   └── DESIGN.md           Android 版设计方案（含实测与取舍）
└── android/                Android 工程
    ├── app/src/main/java/com/wlh/cfoptimize/
    │   ├── core/           CIDR 解析、无偏抽样、结果模型与排序
    │   ├── net/            Prober（自定义 Dns + EventListener 计时）、ScanEngine、RangeFetcher
    │   ├── data/           Settings（DataStore）、HistoryStore（JSON 原子写 + 聚合）
    │   ├── export/         CSV / Clash hosts
    │   └── ui/             Compose 界面与 ViewModel
    └── app/src/test/       纯 JVM 单测（无需设备）
```

## 注意

- **结果只对测量时的网络出口有效。** 手机用蜂窝网络测出的结果，填到家里路由器上是错的。
- 候选 IP 的有效率约 34%，测出几百个可用 IP 属正常。
- 排序把不可用 IP 放在末尾并标注失败原因（超时 / 拒绝 / TLS 失败）。
- 请遵守所在地区的法律法规与网络服务商条款。

## License

[MIT](LICENSE) © 2026 thinkingstill
