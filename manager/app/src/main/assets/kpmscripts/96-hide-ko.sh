#!/system/bin/sh
# /data/adb/service.d/96-hide-ko.sh
#
# 把 /data/adb 下第三方 ko 的痕迹抹掉（/proc/modules、/proc/kallsyms、/sys/module）。
#
# 原理：
#   内核编译期名单是空的（通用版），实际名单由本脚本扫描 /data/adb 递归查找
#   所有 .ko，把"此刻真的加载在内核里"的那些登记到 /proc/oplus_mem_zone。
#   登记后内核会同时处理三处：
#     · /proc/modules 整行不输出（含 root）
#     · /proc/kallsyms 相关符号消失（含 root）
#     · /sys/module/<name> 非 root ENOENT（strict 位）
#
#   扫描范围是 /data/adb（包含子目录 modules），所以下面两种位置都能覆盖：
#     /data/adb/abc.ko
#     /data/adb/modules/xxx/abc.ko
#   厂商自带同名模块（/vendor/lib/modules/<name>.ko）会被跳过，避免误藏。
#
# 生效时机：
#   · 开机：post-fs-data.d 之后（本脚本在 service.d，模块此时已 insmod）
#   · 新装模块后：重启即可，或手动执行 `sh /data/adb/service.d/96-hide-ko.sh scan`
#
# 日志：/data/adb/hide-ko/state.log（0700，仅 root）

CTL=/proc/oplus_mem_zone
LOGDIR=/data/adb/hide-ko
LOG=$LOGDIR/state.log
MODDIR=/data/adb

# 日志放 /data/adb 下（0700 root-only），不要放 /data/local/tmp —— 那里 1777，检测方会扫
mkdir -p "$LOGDIR"
chmod 700 "$LOGDIR"
[ -f "$LOG" ] && chmod 600 "$LOG"

log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }

scan_and_register() {
    [ -w "$CTL" ] || { log "no $CTL, skip"; return 1; }
    n=0
    for ko in $(find "$MODDIR" -name '*.ko' -type f 2>/dev/null); do
        name=$(basename "$ko" .ko | tr '-' '_')
        # 厂商自带同名模块不碰（避免把 zram 这类系统模块藏掉）
        [ -f "/vendor/lib/modules/$name.ko" ] && continue
        # 只登记此刻真在内核里的，避免把同名厂商模块误伤
        if cut -d' ' -f1 /proc/modules 2>/dev/null | grep -qx "$name"; then
            if echo "add $name" > "$CTL" 2>/dev/null; then
                n=$((n+1))
                log "registered $name"
            fi
        fi
    done
    log "scan done: $n new, list=$(tr '\n' ' ' < "$CTL" 2>/dev/null)"
    return 0
}

show_list() {
    [ -r "$CTL" ] && cat "$CTL" || log "no $CTL"
}

unregister_all() {
    # 只清运行时表；编译期名单（qpace_*）不受影响
    [ -w "$CTL" ] && echo clear > "$CTL" && log "runtime list cleared"
}

if [ -z "$1" ]; then
    # 开机多轮扫描：模块可能在 post-fs-data / service.sh 的不同阶段 insmod
    scan_and_register
    for delay in 10 30 60; do
        sleep "$delay"
        scan_and_register
    done
    exit 0
fi

case "$1" in
    scan)   scan_and_register ;;
    watch)  # 常驻模式：每 120 秒扫一次，覆盖"开机后手动 insmod"的场景
            while true; do scan_and_register; sleep 120; done ;;
    list)   show_list ;;
    clear)  unregister_all ;;
    *)      echo "usage: $0 [scan|watch|list|clear]" ;;
esac
