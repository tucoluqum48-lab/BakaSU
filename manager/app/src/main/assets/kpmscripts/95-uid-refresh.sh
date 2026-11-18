#!/system/bin/sh
# uid 名单自动跟随：重装 app 换 uid 后无需重启
#
# 数据源优先直接读 /data/system/packages.list（root 可读，约 15ms）
# 读不到时退回 pm list packages -U（约 33ms）
#
# 轮询策略：每 60 秒看一次文件 mtime（约 7ms），
#           只有变化时才做完整解析与下发（约 33ms）。
#           → 跟随延迟 ≤60 秒，常态开销接近 0

CTL=/proc/oplus_mem_zone
[ -e "$CTL" ] || CTL=/proc/susfs_hide_modules
LOG=/data/adb/kpm-tool/uidhide.log
STATE=/data/adb/kpm-tool/uid_sig
PKGLIST=/data/system/packages.list

[ -w "$CTL" ] || exit 0

# $1: 包名列表（空格分隔）→ 输出 uid 列表（空格分隔、升序去重）
resolve_uids() {
    if [ -r "$PKGLIST" ]; then
        awk -v list="$1" '
            BEGIN { n=split(list, a, " "); for (i=1;i<=n;i++) if (a[i]!="") w[a[i]]=1 }
            w[$1] && $2 ~ /^[0-9]+$/ { print $2 }
        ' "$PKGLIST" 2>/dev/null | sort -n -u | tr '\n' ' '
    else
        PMDUMP=$(pm list packages -U 2>/dev/null)
        for p in $1; do
            echo "$PMDUMP" | sed -n "s/^package:$p uid:\([0-9]*\).*/\1/p" | head -1
        done | sort -n -u | tr '\n' ' '
    fi
}

# 单实例保护：避免被多次拉起（service.d 重复执行、手动启动等）
REFRESH_LOCK=/data/adb/kpm-tool/.refresh.lock
if [ -f "$REFRESH_LOCK" ]; then
    OLD_PID=$(cat "$REFRESH_LOCK" 2>/dev/null)
    # 只判断 PID 会误判：锁文件跨重启保留，且 PID 会被复用
    # 必须校验该进程的命令行确实是本脚本
    if [ -n "$OLD_PID" ] && [ -r "/proc/$OLD_PID/cmdline" ]; then
        OLD_CMD=$(tr '\0' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null)
        case "$OLD_CMD" in
            *95-uid-refresh.sh*)
                echo "$(date) refresh: 已有实例 $OLD_PID 在跑，退出" >> "$LOG" 2>/dev/null
                exit 0
                ;;
        esac
    fi
fi
echo $$ > "$REFRESH_LOCK"

# 探测内核支持的过滤命令（同 99-uidhide 的逻辑）
MODE=none
if [ -w "$CTL" ]; then
    OUT=$(cat "$CTL" 2>/dev/null)
    case "$OUT" in
        *"filter:"*) MODE=new ;;
        *"prio:"*)   MODE=old ;;
    esac
fi
[ "$MODE" = none ] && exit 0

LAST_MTIME=""
while true; do
    HMA=$(ls -t /data/misc/hide_my_applist_*/config.json 2>/dev/null | head -1)
    # 两个来源都要看：packages.list（app 装卸）和 HMA 配置（名单增删）
    M="$(stat -c %Y "$PKGLIST" 2>/dev/null)-$(stat -c %Y "$HMA" 2>/dev/null)"
    if [ "$M" != "$LAST_MTIME" ]; then
        LAST_MTIME="$M"
        if [ -r "$HMA" ]; then
            PKGS=$(sed -n 's/.*"appList":\[\([^]]*\)\].*/\1/p' "$HMA" 2>/dev/null \
                | tr ',' ' ' | tr -d '"' | tr -s ' ')
            if [ -z "$PKGS" ]; then
                # HMA 名单为空 → 清掉内核里的旧过滤，避免幽灵条目
                if echo "puid clear" > "$CTL" 2>/dev/null; then
                    [ "$MODE" = new ] && echo "papp clear" > "$CTL" 2>/dev/null
                    rm -f "$STATE"
                    echo "$(date) refresh: HMA 名单为空，已清空内核过滤" >> "$LOG"
                fi
            elif [ -n "$PKGS" ]; then
                SIG=$(echo "$PKGS" | md5sum | cut -d' ' -f1)
                OLD=$(cat "$STATE" 2>/dev/null)

                # 双发：puid 立即生效，papp 供内核侧跟随（补丁已加空位图保护）
                if [ "$SIG" != "$OLD" ] && [ -n "$PKGS" ]; then
                    UIDS=$(resolve_uids "$PKGS")
                    CMD="puid $(echo $UIDS)"
                    CNT="$(echo $UIDS | wc -w) 条"
                    if echo "$CMD" > "$CTL" 2>/dev/null; then
                        [ "$MODE" = new ] && echo "papp $(echo $PKGS)" > "$CTL" 2>/dev/null
                        echo "$SIG" > "$STATE"
                        echo "$(date) refresh: 已重推 $CNT" >> "$LOG"
                    fi
                fi
            fi
        fi
    fi
    sleep 60
done
