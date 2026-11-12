#!/system/bin/sh
# 关闭 binder 驱动的调试日志（内核默认 debug_mask=7，会在检测/轮询时刷爆 dmesg）
# 由 dsh 生成，2026-11-13
[ -w /sys/module/binder/parameters/debug_mask ] && echo 0 > /sys/module/binder/parameters/debug_mask
