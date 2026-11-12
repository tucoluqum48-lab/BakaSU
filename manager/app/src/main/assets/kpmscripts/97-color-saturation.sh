#!/system/bin/sh
# 开机自动把屏幕饱和度设为 1.28（128%）
# 由 dsh 生成，2026-11-11
LOG=/data/adb/kpm-tool/saturation.log

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 1; done

# 等 SurfaceFlinger 完全就绪
sleep 3

setprop persist.sys.sf.color_saturation 1.28
service call SurfaceFlinger 1022 f 1.28 >/dev/null 2>&1

echo "$(date) set saturation -> $(getprop persist.sys.sf.color_saturation)" >> "$LOG"
