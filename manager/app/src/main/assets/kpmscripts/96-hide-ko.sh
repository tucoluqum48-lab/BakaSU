#!/system/bin/sh
# /data/adb/service.d/96-hide-ko.sh
#
# 把 /data/adb/modules 下第三方 ko 的 /proc/modules + /proc/kallsyms 痕迹抹掉。
#
# 原理：
#   内核补丁编译期只写了 qpace_drv / qpace_zram（开机瞬间就生效），
#   之后新装的 ko 由本脚本扫描 /data/adb/modules 自动登记到 /proc/susfs_hide_modules，
#   无需重新编译内核。
#
# 生效时机：
#   · 开机：post-fs-data.d 之后（本脚本在 service.d，模块此时已 insmod）
#   · 新装模块后：重启即可，或手动执行 `sh /data/adb/service.d/96-hide-ko.sh scan`
#
# 日志：/data/adb/hide-ko/state.log（0700，仅 root）

CTL=/proc/susfs_hide_modules
LOGDIR=/data/adb/hide-ko
LOG=$LOGDIR/state.log
MODDIR=/data/adb/modules

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
