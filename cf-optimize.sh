
#!/bin/bash
set -euo pipefail

# 用法：
# ./cf-optimize.sh 你的节点域名
#
# 可选环境参数：
# SAMPLE=1000 REPEAT=4 PARALLEL=25 ./cf-optimize.sh your.domain.com

DOMAIN="${1:-}"
SAMPLE="${SAMPLE:-800}"
REPEAT="${REPEAT:-3}"
PARALLEL="${PARALLEL:-25}"
TIMEOUT="${TIMEOUT:-4}"
OUT="${OUT:-$PWD/cf-ip-result.csv}"

if [[ -z "$DOMAIN" ]]; then
    echo "用法：$0 your.domain.com"
    exit 1
fi

case "$DOMAIN" in
    *[!A-Za-z0-9.-]*|.*|*.|*..*)
        echo "域名格式不正确：$DOMAIN"
        exit 1
        ;;
esac

DOMAIN="$(echo "$DOMAIN" | tr 'A-Z' 'a-z')"

for cmd in curl perl awk sort xargs; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "缺少命令：$cmd"
        exit 1
    }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

RANGES="$TMP/ips-v4.txt"
CANDIDATES="$TMP/candidates.txt"
RAW="$TMP/raw.csv"
SORTED="$TMP/sorted.csv"

echo "======================================"
echo "Cloudflare 优选 IP"
echo "域名：$DOMAIN"
echo "候选数量：$SAMPLE"
echo "每个 IP 测试次数：$REPEAT"
echo "并发数：$PARALLEL"
echo "======================================"

# 1. 下载 Cloudflare 官方 IPv4 网段
echo "[1/4] 获取 Cloudflare 官方 IP 网段"

curl --noproxy "*" -fsSL \
    --connect-timeout 8 \
    --max-time 30 \
    https://www.cloudflare.com/ips-v4/ \
    -o "$RANGES"

# 2. 每个 /24 网段随机抽取一个 IP
echo "[2/4] 生成候选 IP"

perl - "$RANGES" "$CANDIDATES" "$SAMPLE" <<'PERL'
use strict;
use warnings;
use Socket qw(inet_aton);

my ($src, $dst, $wanted) = @ARGV;

open my $in, "<", $src or die "无法读取 IP 网段\n";

my %blocks;

while (my $line = <$in>) {
    chomp $line;

    next unless $line =~ m{^(\d+\.\d+\.\d+\.\d+)/(\d+)$};

    my ($ip, $prefix) = ($1, $2);

    next if $prefix > 32 || $prefix < 0;

    my $packed = inet_aton($ip);
    next unless $packed;

    my $num = unpack("N", $packed);
    my $mask = $prefix == 0
        ? 0
        : ((0xffffffff << (32 - $prefix)) & 0xffffffff);

    my $network = $num & $mask;
    my $size = 2 ** (32 - $prefix);

    my $step = $prefix <= 24 ? 256 : $size;

    for (my $n = $network; $n < $network + $size; $n += $step) {
        $blocks{$n} = 1;
    }
}

close $in;

my @blocks = keys %blocks;

die "没有生成候选 IP\n" unless @blocks;

# 随机打乱网段
for (my $i = $#blocks; $i > 0; $i--) {
    my $j = int(rand($i + 1));
    @blocks[$i, $j] = @blocks[$j, $i];
}

my $count = $wanted < @blocks ? $wanted : scalar @blocks;

open my $out, ">", $dst or die "无法写入候选 IP\n";

for (my $i = 0; $i < $count; $i++) {
    # 当前 Cloudflare 网段均可按 /24 抽样
    my $host = 2 + int(rand(253));
    my $num = $blocks[$i] + $host;

    my @octets = unpack("C4", pack("N", $num));

    print $out join(".", @octets), "\n";
}

close $out;

print "生成候选 IP：$count\n";
PERL

export DOMAIN REPEAT TIMEOUT

# 3. 对每个候选 IP 进行 TCP + TLS 握手测试
echo "[3/4] 开始测速"

cat > "$TMP/probe-one.sh" <<'PROBE'
#!/bin/sh

ip="$1"
ok=0
tcp_values=""
tls_values=""
i=1

while [ "$i" -le "$REPEAT" ]; do

    # --resolve 强制连接指定 IP
    # URL 仍使用原始域名，因此 TLS SNI 和 HTTP Host 不变
    metrics="$(
        curl --noproxy "*" \
            --http1.1 \
            -sS -o /dev/null \
            --connect-timeout 2 \
            --max-time "$TIMEOUT" \
            --resolve "${DOMAIN}:443:${ip}" \
            -w "%{time_connect} %{time_appconnect}" \
            "https://${DOMAIN}/" \
            2>/dev/null || true
    )"

    set -- $metrics

    tcp="${1:-0.000000}"
    tls="${2:-0.000000}"

    case "$tls" in
        ""|0|0.0|0.00|0.000000)
            ;;
        *)
            ok=$((ok + 1))
            tcp_values="${tcp_values}${tcp}
"
            tls_values="${tls_values}${tls}
"
            ;;
    esac

    i=$((i + 1))
done

# 计算平均 TCP 连接时间和 TLS 握手时间，单位 ms
if [ "$ok" -gt 0 ]; then

    avg_tcp="$(
        printf "%s" "$tcp_values" |
        awk 'NF {s += $1; n++} END {
            if (n) printf "%.2f", s/n*1000
        }'
    )"

    avg_tls="$(
        printf "%s" "$tls_values" |
        awk 'NF {s += $1; n++} END {
            if (n) printf "%.2f", s/n*1000
        }'
    )"

    loss="$(
        awk -v n="$REPEAT" -v ok="$ok" \
            'BEGIN {printf "%.1f", (n-ok)*100/n}'
    )"

    printf "%s,%s,%s,%s,%s\n" \
        "$ip" "$loss" "$avg_tls" "$avg_tcp" "$ok"
fi
PROBE

chmod +x "$TMP/probe-one.sh"

xargs -I @@ -P "$PARALLEL" \
    "$TMP/probe-one.sh" @@ \
    < "$CANDIDATES" > "$RAW"

if [ ! -s "$RAW" ]; then
    echo "没有 IP 完成 TLS 握手，请检查网络和节点域名。"
    exit 1
fi

# 4. 先按失败率排序，再按 TLS 握手时间排序
echo "[4/4] 排序测速结果"

sort -t ',' -k2,2n -k3,3n "$RAW" > "$SORTED"

{
    echo "IP,失败率(%),平均TLS握手(ms),平均TCP连接(ms),成功次数"
    cat "$SORTED"
} > "$OUT"

BEST_IP="$(head -n 1 "$SORTED" | cut -d ',' -f 1)"

echo
echo "======================================"
echo "优选完成"
echo "======================================"
echo "Best IP: $BEST_IP"
echo "结果文件: $OUT"
echo
echo "排名前 10："
head -n 10 "$OUT"

echo
echo "请把 Best IP 填入路由器的 DNS 映射规则。"

