#!/system/bin/sh
# ============================================================
# 音量键救机（post-fs-data.d 版）
# 触发条件（满足其一）：
#   A) 15 秒内出现 ≥3 次 KEY_VOLUMEDOWN DOWN  → 连按
#   B) 单次 DOWN 到 UP 的间隔 ≥1.2 秒，或窗口结束仍未 UP → 长按
# 动作：给其它所有模块打 disable 标记 → 2 秒后重启
# 设计：不写一次性触发标记；若已无"可禁用"的模块则静默退出，
#       既避免重启循环，又不用手动复位。
# ============================================================
RDIR=/data/adb/volkey-rescue
LOG=$RDIR/log
mkdir -p "$RDIR"

log_vk() { echo "volkey: $*" > /dev/kmsg 2>/dev/null; }

# 先看还有没有"活着的"模块；全被禁用则本次什么都不做
pending=0
for d in /data/adb/modules/*; do
    [ -d "$d" ] || continue
    [ -f "$d/disable" ] || pending=1
done
[ "$pending" -eq 0 ] && exit 0

(
    # 流式读取 15 秒内的按键事件，交给 awk 判定
    verdict=$(timeout 15 getevent -ltq 2>/dev/null | awk '
        function fnum(s,   v) { v = s; gsub(/[^0-9.]/, "", v); return v + 0 }
        /KEY_VOLUMEDOWN/ {
            ts = fnum($1)
            if ($NF == "DOWN") { downs++; if (down_ts == 0) down_ts = ts }
            else if ($NF == "UP") { ups++; if (down_ts > 0 && (ts - down_ts) >= 1.2) longpress = 1; down_ts = 0 }
        }
        END {
            if (downs >= 3) { print "go"; exit }
            if (longpress)  { print "go"; exit }
            if (downs >= 1 && ups < downs) { print "go"; exit }
            print "no"
        }')

    [ "$verdict" = "go" ] || exit 0

    # 二次确认：判定到执行之间可能已有变化
    n=0
    for d in /data/adb/modules/*; do
        [ -d "$d" ] || continue
        [ -f "$d/disable" ] && continue
        n=$((n + 1))
    done
    [ "$n" -eq 0 ] && exit 0

    log_vk "命中音量键手势，开始救机（待禁用 $n 个模块）"
    echo "$(date) rescue: 禁用 $n 个模块" >> "$LOG"

    for d in /data/adb/modules/*; do
        [ -d "$d" ] || continue
        [ -f "$d/disable" ] && continue
        touch "$d/disable" 2>/dev/null
        log_vk "已禁用 $(basename "$d")"
    done
    ls -1 /data/adb/modules > "$RDIR/disabled_at.txt" 2>/dev/null

    log_vk "2 秒后重启"
    sleep 2
    reboot
) &
