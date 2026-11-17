#!/system/bin/sh
# ============================================================
# 音量键救机（post-fs-data.d 版）
# 触发条件（满足其一）：
#   A) 15 秒内出现 ≥3 次 KEY_VOLUMEDOWN DOWN  → 连按
#   B) 单次 DOWN 到 UP 的间隔 ≥1.2 秒，或窗口结束仍未 UP → 长按
# 动作：给其它所有模块打 disable 标记 → 2 秒后重启
# ============================================================
RDIR=/data/adb/volkey-rescue
mkdir -p "$RDIR"

# 已触发过就不再重复（避免重启循环）
[ -f "$RDIR/triggered" ] && exit 0

log_vk() { echo "volkey: $*" > /dev/kmsg 2>/dev/null; }

(
    END_TS=$(( $(date +%s) + 15 ))

    # 流式读取 15 秒内的按键事件，交给 awk 判定
    verdict=$(timeout 15 getevent -ltq 2>/dev/null | awk '
        function fnum(s,   v) { v = s; gsub(/[^0-9.]/, "", v); return v + 0 }
        /KEY_VOLUMEDOWN/ {
            ts = fnum($1)
            if ($NF == "DOWN") {
                downs++
                if (down_ts == 0) down_ts = ts
                last_down = ts
            } else if ($NF == "UP") {
                ups++
                if (down_ts > 0 && (ts - down_ts) >= 1.2) longpress = 1
                down_ts = 0
            }
        }
        END {
            if (downs >= 3) { print "go"; exit }
            if (longpress)  { print "go"; exit }
            if (downs >= 1 && ups < downs) { print "go"; exit }   # 窗口结束仍按住
            print "no"
        }')

    [ "$verdict" = "go" ] || exit 0

    log_vk "命中音量键手势，开始救机"
    date > "$RDIR/triggered"

    for d in /data/adb/modules/*; do
        [ -d "$d" ] || continue
        touch "$d/disable" 2>/dev/null
        log_vk "已禁用 $(basename "$d")"
    done
    ls -1 /data/adb/modules > "$RDIR/disabled_at.txt" 2>/dev/null

    log_vk "2 秒后重启"
    sleep 2
    reboot
) &
