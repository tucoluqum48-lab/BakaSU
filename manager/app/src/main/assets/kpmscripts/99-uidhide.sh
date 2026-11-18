#!/system/bin/sh
# 优先级过滤：开机后从 HMA-OSS 黑名单自动同步并下发
#
# 两个接收方（互相独立，谁在谁干活）：
#   ① 内核 priority uid filter（setpriority / getpriority / ioprio_set / ioprio_get）
#      → 写 /proc/oplus_mem_zone 的 puid 命令，不依赖任何 KPM
#   ② UidHide.kpm（只管 getpriority）
#      → kpatch ctl0 UidHide，模块不在就跳过
#
# 数据源：HMA-OSS 黑名单（唯一的真相来源，改 HMA 即可）
# 由 dsh 生成，当前 v11
#   v8  ：包名驱动 + 白名单
#   v10 ：曾只发 puid —— 当时误判 papp 无效（真实原因是我们保护逻辑的
#         bitmap_empty 单位写错：传了 BITMAP_LEN(157) 而非 UID_NUM(10000)）
#   v11 ：恢复 puid + papp 双发
#         · puid <uid...>   直接按 uid 填位图，立即生效
#         · papp <包名...>  内核解析包名，自己周期跟随（补丁已修好）
#         · sallow <包名...> 白名单（2.4.0 按包名），名单内 app 不受 strict 位影响
KP=/data/adb/kpm-tool/bin/kpatch
CTL=/proc/oplus_mem_zone
[ -e "$CTL" ] || CTL=/proc/susfs_hide_modules
LOG=/data/adb/kpm-tool/uidhide.log
# HMA-OSS 的数据目录带随机后缀，用通配找最新的那份（换机/重装都能用）
HMA=$(ls -t /data/misc/hide_my_applist_*/config.json 2>/dev/null | head -1)

echo "=== $(date) boot apply (auto-sync) ===" >> "$LOG"

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

# 4. 下发到内核（不依赖 KPM，先做这一步）
#    先探测内核支持哪种命令：
#      · 含 "filter:" 行 → SUSFS 2.4.0，按包名（papp）
#      · 含 "prio:" 行   → SUSFS 2.3.0，直接下发 uid（puid）
#      · 都没有          → 不下发，避免被当成模块名登记
MODE=none
if [ -w "$CTL" ]; then
    OUT=$(cat "$CTL" 2>/dev/null)
    case "$OUT" in
        *"filter:"*) MODE=new ;;
        *"prio:"*)   MODE=old ;;
    esac
fi

if [ -w "$CTL" ] && [ "$MODE" != none ]; then
    # 4a. 只下发 puid（直接按 uid 填位图，确定生效）
    echo "puid $(echo $UIDS)" > "$CTL" 2>>"$LOG" \
        && echo "内核 puid 已下发: $(echo $UIDS | wc -w) 条" >> "$LOG" \
        || echo "内核 puid 下发失败" >> "$LOG"

    # 双发：puid 立即生效（直接填位图），papp 让内核自己周期跟随
    #（papp 之前"无效"是我们保护逻辑的单位写错，已修：BITMAP_LEN → UID_NUM）
    if [ "$MODE" = new ]; then
        echo "papp $(echo $PKGS)" > "$CTL" 2>>"$LOG" \
            && echo "内核 papp 已下发: $(echo $PKGS | wc -w) 个包名（内核侧跟随）" >> "$LOG" \
            || echo "内核 papp 下发失败（不影响 puid 已生效的过滤）" >> "$LOG"
    fi

    # 4c. sus_path 白名单：这些 uid 不受隐藏影响（默认空，按需放开）
    if [ -r /data/adb/kpm-tool/sus_allow.txt ]; then
        ALLOW=$(tr '\n' ' ' < /data/adb/kpm-tool/sus_allow.txt | tr -s ' ')
        if [ -n "$ALLOW" ]; then
            echo "sallow $ALLOW" > "$CTL" 2>>"$LOG" \
                && echo "sallow 已下发: $ALLOW" >> "$LOG"
        fi
    fi
else
    echo "控制节点不可写（$CTL），跳过内核下发" >> "$LOG"
fi

# 5. 下发到 UidHide.kpm（可选：模块没装就跳过，不影响内核那层）
if [ -x "$KP" ] && "$KP" kpm list 2>/dev/null | grep -q "^UidHide$"; then
    "$KP" kpm ctl0 UidHide "clear" >> "$LOG" 2>&1
    for u in $UIDS; do
        "$KP" kpm ctl0 UidHide "add $u" >> "$LOG" 2>&1
    done
    echo "UidHide.kpm 已下发: $(echo $UIDS | wc -w) 条" >> "$LOG"
else
    echo "UidHide.kpm 不可用，跳过（内核那一层不受影响）" >> "$LOG"
fi

echo "--- result ---" >> "$LOG"
[ -r "$CTL" ] && cat "$CTL" >> "$LOG" 2>/dev/null
if [ -x "$KP" ] && "$KP" kpm list 2>/dev/null | grep -q "^UidHide$"; then
    "$KP" kpm ctl0 UidHide "list" >> "$LOG" 2>&1
fi
