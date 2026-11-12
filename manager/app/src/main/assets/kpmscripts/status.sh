#!/system/bin/sh
# 管理器内置版 status.sh：适配 /data/adb/kpm-tool 布局（无模块目录）
# 原版会改写 module.prop，这里改为写日志，保证 WebUI 的 rehook 链式调用成功
TOOL=/data/adb/kpm-tool
KP=$TOOL/bin/kpatch
LOG=$TOOL/status.log

[ -x "$KP" ] || exit 0

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 1; done

if [ -n "$("$KP" hello 2>/dev/null)" ]; then
    N=$("$KP" kpm num 2>/dev/null); [ -z "$N" ] && N=0
    R=$("$KP" rehook_status 2>/dev/null | awk '{print $NF}'); [ -z "$R" ] && R=unknown
    echo "$(date '+%Y-%m-%d %H:%M:%S') active | kpmodule: $N | rehook: $R" >> "$LOG"
else
    echo "$(date '+%Y-%m-%d %H:%M:%S') inactive | kernel not patched" >> "$LOG"
fi
exit 0
