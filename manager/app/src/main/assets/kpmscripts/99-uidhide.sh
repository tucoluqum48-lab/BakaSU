#!/system/bin/sh
# UidHide KPM：开机后从 HMA-OSS 黑名单自动同步并下发控制参数
# 由 dsh 生成，2026-11-14  v4（HMA 路径改通配，仅从黑名单同步）
KP=/data/adb/kpm-tool/bin/kpatch
LOG=/data/adb/kpm-tool/uidhide.log
# HMA-OSS 的数据目录带随机后缀，用通配找最新的那份（换机/重装都能用）
HMA=$(ls -t /data/misc/hide_my_applist_*/config.json 2>/dev/null | head -1)

[ -x "$KP" ] || exit 0

echo "=== $(date) boot apply (auto-sync) ===" >> "$LOG"

# 1. 等 KPM 模块加载完（最多 60 秒）
i=0
while [ $i -lt 60 ]; do
    "$KP" kpm list 2>/dev/null | grep -q "^UidHide$" && break
    sleep 1
    i=$((i+1))
done
"$KP" kpm list 2>/dev/null | grep -q "^UidHide$" || { echo "UidHide 未加载，退出" >> "$LOG"; exit 0; }

# 2. 从 HMA 配置提取黑名单包名（appList 是纯字符串数组）
PKGS=""
if [ -r "$HMA" ]; then
    PKGS=$(sed -n 's/.*"appList":\[\([^]]*\)\].*/\1/p' "$HMA" 2>/dev/null \
        | tr ',' ' ' | tr -d '"' | tr -s ' ')
fi

# 2b. 读不到或解析失败 → 直接退出，不改动现有排除列表
if [ -z "$PKGS" ]; then
    echo "HMA 配置不可用或黑名单为空，跳过本次同步" >> "$LOG"
    exit 0
fi

# 3. 包名 -> UID
PMDUMP=$(pm list packages -U 2>/dev/null)
UIDS=""
for p in $PKGS; do
    u=$(echo "$PMDUMP" | sed -n "s/^package:$p uid:\([0-9]*\).*/\1/p" | head -1)
    [ -n "$u" ] && UIDS="$UIDS $u"
done
UIDS=$(echo $UIDS | tr ' ' '\n' | sort -u | tr '\n' ' ')
echo "包数=$(echo $PKGS | wc -w) UID:$UIDS" >> "$LOG"

[ -z "$(echo $UIDS)" ] && { echo "未解析到任何 UID，退出" >> "$LOG"; exit 0; }

# 4. 先清空再逐条下发（clear 保证是"全量覆盖"而不是累积）
"$KP" kpm ctl0 UidHide "clear" >> "$LOG" 2>&1
for u in $UIDS; do
    "$KP" kpm ctl0 UidHide "add $u" >> "$LOG" 2>&1
done

echo "--- result ---" >> "$LOG"
"$KP" kpm ctl0 UidHide "list" >> "$LOG" 2>&1
