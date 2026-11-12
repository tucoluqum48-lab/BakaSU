package org.bakasu.bakasu.data.kpm

import com.topjohnwu.superuser.Shell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KpmModule(
    val name: String,
    val version: String,
    val license: String,
    val author: String,
    val description: String,
    val args: String,
)

/**
 * 通过 KPatch-Next 的 kpatch CLI 管理 KPM 模块。
 * 该能力来自内核侧的 KPatch-Next，不依赖管理器内置的 KPM 支持。
 */
class KpmRepository(private val context: android.content.Context) {

    companion object {
        /** 自理目录：不依赖 KPatch-Next 模块 */
        const val TOOL_DIR = "/data/adb/kpm-tool"
        const val BIN_DIR = "$TOOL_DIR/bin"
        const val KPM_DIR = "$TOOL_DIR/kpm"
        const val BOOT_SCRIPT = "/data/adb/service.d/99-kpm-autoload.sh"

        /** 内置 WebUI 放在 app 私有目录，不落 /data/adb/modules */
        const val WEBUI_DIR = "kpmwebui"
        const val ASSET_NAME = "kpatch-arm64"

        /** 随 APK 一起发布的内置工具链（重装后自动补齐） */
        val BUNDLED_BINS = listOf("kpimg", "kptools", "magiskboot")

        /** 内置补丁脚本（补丁页需要，随 APK 发布） */
        val BUNDLED_PATCH = listOf("boot_extract.sh", "boot_patch.sh", "boot_unpatch.sh", "util_functions.sh")
        const val PATCH_DIR = "$TOOL_DIR/patch"

        /** 内置开机脚本：缺失时自动释放 */
        val BUNDLED_SCRIPTS = listOf(
            "99-uidhide.sh" to "/data/adb/boot-completed.d/99-uidhide.sh",
            "97-color-saturation.sh" to "/data/adb/service.d/97-color-saturation.sh",
            // WebUI 的 rehook 开关会调用 sh /data/adb/kpm-tool/status.sh
            "status.sh" to "/data/adb/kpm-tool/status.sh",
            // 关掉 binder 调试日志（内核默认 debug_mask=7，会刷爆 dmesg）
            "96-binder-quiet.sh" to "/data/adb/service.d/96-binder-quiet.sh",
        )

        /** 模块路径：仅作为自举前的兜底 */
        val FALLBACK_BIN = "/data/adb/modules/KPatch-Next/bin/kpatch"

        /** 自理目录：管理器自举释放的 kpatch，优先使用 */
        const val SELF_MANAGED_BIN = BIN_DIR + "/kpatch"

        val CANDIDATE_DIRS = listOf(
            BIN_DIR,
            "/data/adb/modules/KPatch-Next/bin",
            "/data/adb/kpm/bin",
        )
    }

    /**
     * 注意：/data/adb/kpm-tool 是 0700，App 进程无法 stat，
     * 所以这里不能做 exists() 判断，直接用固定路径，
     * 是否存在由 shell 侧（root）判断。
     */
    val binaryPath: String by lazy {
        // /data/adb/kpm-tool 是 0700，App 进程 stat 不到，先交给 root shell 探测，
        // 命中自理目录就用它，否则回退到 KPatch-Next 模块路径。
        val probe = Shell.cmd("test -x " + SELF_MANAGED_BIN + " && echo KPM_OK").exec()
        if (probe.out.any { it.trim() == "KPM_OK" }) {
            SELF_MANAGED_BIN
        } else {
            CANDIDATE_DIRS.map { File(it, "kpatch") }.firstOrNull { it.exists() }?.absolutePath
                ?: FALLBACK_BIN
        }
    }

    private val bootScriptBody: String = """
#!/system/bin/sh
# 由 BakaSU 的 KPM 页面生成：开机自动加载 /data/adb/kpm-tool/kpm/*.kpm
# 逻辑对齐原版 KPatch-Next service.sh
TOOL=/data/adb/kpm-tool
KP=/data/adb/kpm-tool/bin/kpatch
CONFIG=/data/adb/kpm-tool/package_config
REHOOKFILE=/data/adb/kpm-tool/rehook
LOG=/data/adb/kpm-tool/autoload.log

[ -x "${'$'}KP" ] || exit 0
[ -n "${'$'}("${'$'}KP" hello 2>/dev/null)" ] || exit 0

# 1. 先加载模块（不等 boot_completed，尽早注入）；损坏的 .kpm 直接删除
for kpm in ${'$'}TOOL/kpm/*.kpm; do
    [ -s "${'$'}kpm" ] || continue
    "${'$'}KP" kpm load "${'$'}kpm" >> "${'$'}LOG" 2>&1 || rm -f "${'$'}kpm"
done

# 2. rehook 重放（enable/disable 或 0/1/2）
REHOOK="${'$'}(cat ${'$'}REHOOKFILE 2>/dev/null)"
if [ -n "${'$'}REHOOK" ]; then
    case "${'$'}REHOOK" in
        enable|disable|0|1|2) "${'$'}KP" rehook "${'$'}REHOOK" >> "${'$'}LOG" 2>&1 ;;
        *) rm -f "${'$'}REHOOKFILE" ;;
    esac
fi

until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 1; done

[ -f "${'$'}CONFIG" ] || exit 0

# 3. 应用级排除重放：跳过表头，优先 pkg+uid 精确匹配，再退化 pkg
tail -n +2 "${'$'}CONFIG" | while IFS=, read -r pkg exclude allow uid; do
    [ "${'$'}exclude" = "1" ] || continue
    U="${'$'}(grep "^${'$'}pkg ${'$'}uid" /data/system/packages.list 2>/dev/null | cut -d' ' -f2)"
    [ -z "${'$'}U" ] && U="${'$'}(grep "^${'$'}pkg " /data/system/packages.list 2>/dev/null | cut -d' ' -f2)"
    [ -n "${'$'}U" ] && "${'$'}KP" exclude_set "${'$'}U" 1 >> "${'$'}LOG" 2>&1
done

# 4. 记录一次运行状态（等价原版 status.sh 的职责）
[ -x "${'$'}TOOL/status.sh" ] && sh "${'$'}TOOL/status.sh" >/dev/null 2>&1
    """.trimIndent()

    /**
     * 首次运行时把 kpatch 释放到 /data/adb/kpm-tool/bin，
     * 并写好开机加载脚本，从此不再依赖 KPatch-Next 模块。
     */
    suspend fun bootstrap(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bin = File(BIN_DIR, "kpatch")
            // App 无法 stat /data/adb 下的 700 目录，交给 shell 判断
            val alreadyDeployed = Shell.cmd("test -x " + quote(bin.absolutePath)).exec().isSuccess
            if (!alreadyDeployed) {
                val cache = File(context.cacheDir, "kpatch")
                context.assets.open(ASSET_NAME).use { input ->
                    cache.outputStream().use { output -> input.copyTo(output) }
                }
                val cmd = Shell.cmd(
                    "mkdir -p " + BIN_DIR + " " + KPM_DIR + " /data/adb/service.d" +
                        " && cp " + quote(cache.absolutePath) + " " + quote(bin.absolutePath) +
                        " && chmod 755 " + quote(bin.absolutePath),
                ).exec()
                if (!cmd.isSuccess) return@runCatching false

                val script = File(context.cacheDir, "99-kpm-autoload.sh")
                script.writeText(bootScriptBody)
                Shell.cmd(
                    "cp " + quote(script.absolutePath) + " " + quote(BOOT_SCRIPT) +
                        " && chmod 755 " + quote(BOOT_SCRIPT),
                ).exec()
            }
            // 其余内置工具链：缺哪个补哪个（重装/清数据后自动恢复）
            deployBundledBins()

            // 内置的其它开机脚本（UidHide 同步、饱和度）缺失时补回
            deployBundledScripts()

            // 始终按最新模板重写一次，保证开机重放排除列表的逻辑存在
            writeBootScript()
        }.getOrDefault(false)
    }

    /** 把 assets/kpmscripts 下的开机脚本补齐（已存在则跳过，避免覆盖用户改动） */
    private suspend fun deployBundledScripts(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            for ((assetName, dest) in BUNDLED_SCRIPTS) {
                val present = Shell.cmd("test -s " + quote(dest)).exec().isSuccess
                if (present) continue
                val cache = File(context.cacheDir, "boot_" + assetName)
                runCatching {
                    context.assets.open("kpmscripts/" + assetName).use { input ->
                        cache.outputStream().use { output -> input.copyTo(output) }
                    }
                }.getOrElse { continue }
                val dir = dest.substringBeforeLast('/')
                Shell.cmd(
                    "mkdir -p " + dir +
                        " && cp " + quote(cache.absolutePath) + " " + quote(dest) +
                        " && chmod 755 " + quote(dest),
                ).exec()
            }
            true
        }.getOrDefault(false)
    }

    /** 把 assets/kpmbin 下的工具链补齐到 BIN_DIR（已存在则跳过） */
    private suspend fun deployBundledBins(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Shell.cmd("mkdir -p " + BIN_DIR).exec()
            for (name in BUNDLED_BINS) {
                val target = BIN_DIR + "/" + name
                val present = Shell.cmd("test -s " + quote(target)).exec().isSuccess
                if (present) continue
                val cache = File(context.cacheDir, name)
                runCatching {
                    context.assets.open("kpmbin/" + name).use { input ->
                        cache.outputStream().use { output -> input.copyTo(output) }
                    }
                }.getOrElse { continue }
                Shell.cmd(
                    "cp " + quote(cache.absolutePath) + " " + quote(target) +
                        " && chmod 755 " + quote(target),
                ).exec()
            }
            // 补丁脚本一并补齐（补丁页会用到）
            Shell.cmd("mkdir -p " + PATCH_DIR).exec()
            for (name in BUNDLED_PATCH) {
                val target = PATCH_DIR + "/" + name
                val present = Shell.cmd("test -s " + quote(target)).exec().isSuccess
                if (present) continue
                val cache = File(context.cacheDir, "patch_" + name)
                runCatching {
                    context.assets.open("kmppatch/" + name).use { input ->
                        cache.outputStream().use { output -> input.copyTo(output) }
                    }
                }.getOrElse { continue }
                Shell.cmd(
                    "cp " + quote(cache.absolutePath) + " " + quote(target) +
                        " && chmod 755 " + quote(target),
                ).exec()
            }
            true
        }.getOrDefault(false)
    }

    private fun quote(s: String): String {
        if (s.isEmpty()) return "\"\""
        val need = s.any { it == ' ' || it == '"' || it == '$' || it == '=' || it == '(' || it == ')' }
        if (!need) return s
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        return "\"" + escaped + "\""
    }

    private suspend fun exec(vararg args: String): List<String> = withContext(Dispatchers.IO) {
        val cmd = (listOf(binaryPath) + args.map { quote(it) }).joinToString(" ")
        val result = Shell.cmd(cmd).exec()
        if (result.out.isNotEmpty()) result.out else result.err
    }

    suspend fun isAvailable(): Boolean = exec("hello").any { it.contains("hello2026") }

    suspend fun toolVersion(): String = exec("kpver").firstOrNull()?.trim().orEmpty()

    suspend fun kernelVersion(): String = exec("kver").firstOrNull()?.trim().orEmpty()

    suspend fun rehookStatus(): String {
        val raw = exec("rehook_status").joinToString(" ").trim()
        // 原始输出形如 "Rehook syscall status: enabled"，只保留状态值
        if (raw.isEmpty()) return ""
        return raw.substringAfterLast(':').trim().ifEmpty { raw }
    }

    suspend fun count(): Int = exec("kpm", "num").firstOrNull()?.trim()?.toIntOrNull() ?: 0

    suspend fun listNames(): List<String> = exec("kpm", "list").map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun info(name: String): KpmModule? {
        val lines = exec("kpm", "info", name)
        if (lines.isEmpty()) return null
        val map = lines.mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
        if (map.isEmpty()) return null
        return KpmModule(
            name = map["name"] ?: name,
            version = map["version"].orEmpty(),
            license = map["license"].orEmpty(),
            author = map["author"].orEmpty(),
            description = map["description"].orEmpty(),
            args = map["args"].orEmpty(),
        )
    }

    /** 开机自启目录：由本 App 写的 service.d 脚本负责加载 */
    val persistentDir: String get() = KPM_DIR

    suspend fun persistentList(): List<String> = withContext(Dispatchers.IO) {
        val r = Shell.cmd("ls -1 " + persistentDir + " 2>/dev/null").exec()
        r.out.map { it.trim() }.filter { it.endsWith(".kpm") }
    }

    /** 递归把 assets 目录写到本地 File */
    private fun copyAssetDir(assetPath: String, dest: File) {
        val am = context.assets
        val children = am.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in children) copyAssetDir("$assetPath/$name", File(dest, name))
    }

    /**
     * 部署内置 KPM 管理界面（原版 WebUI）到 app 私有目录：
     * - 释放 assets/kpmwebui → filesDir/kpmwebui/webroot
     * - 登记到 BuiltinWebUI，WebViewHelper 会直接加载它（不创建任何模块目录）
     * - 把 kpatch 软链到 /data/adb/ksu/bin，保证界面里 `kpatch ...` 走 PATH
     */
    suspend fun ensureWebUiModule(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(context.filesDir, WEBUI_DIR)
            val stage = File(context.cacheDir, "kpmwebui-assets")
            if (stage.exists()) stage.deleteRecursively()
            copyAssetDir("kpmwebui", stage)

            val webroot = File(root, "webroot")
            if (webroot.exists()) webroot.deleteRecursively()
            webroot.mkdirs()
            stage.copyRecursively(webroot, overwrite = true)
            stage.deleteRecursively()

            org.bakasu.bakasu.ui.webui.BuiltinWebUI.register(root, "KPM")

            // WebUI 里调用的是裸命令 kpatch，需要它在 root shell 的 PATH 中；
            Shell.cmd(
                "mkdir -p /data/adb/ksu/bin " + BIN_DIR +
                    " && ln -sf " + SELF_MANAGED_BIN + " /data/adb/ksu/bin/kpatch",
            ).exec()
            webroot.isDirectory
        }.getOrDefault(false)
    }

    /** 始终按最新模板重写开机脚本（幂等） */
    suspend fun writeBootScript(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val script = File(context.cacheDir, "99-kpm-autoload.sh")
            script.writeText(bootScriptBody)
            Shell.cmd(
                "mkdir -p /data/adb/service.d" +
                    " && cp " + quote(script.absolutePath) + " " + quote(BOOT_SCRIPT) +
                    " && chmod 755 " + quote(BOOT_SCRIPT),
            ).exec().isSuccess
        }.getOrDefault(false)
    }

    /**
     * 开机自动加载：把 .kpm 复制进自启目录并立刻加载一次。
     * 下次开机由 KPatch-Next 的 service.sh 自动加载。
     */
    suspend fun installPersistent(srcPath: String, fileName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val safeName = if (fileName.endsWith(".kpm")) fileName else fileName + ".kpm"
        // 同名文件已存在时追加序号，避免覆盖已有模块
        var target = safeName
        var index = 1
        while (Shell.cmd("test -e " + quote(persistentDir + "/" + target)).exec().isSuccess && index < 100) {
            target = safeName.removeSuffix(".kpm") + "_" + index + ".kpm"
            index++
        }
        val dest = persistentDir + "/" + target
        val copy = Shell.cmd(
            "mkdir -p " + persistentDir +
                " && cp " + quote(srcPath) + " " + quote(dest) +
                " && chmod 644 " + quote(dest),
        ).exec()
        if (!copy.isSuccess) return@withContext false to copy.err.joinToString(" ").trim()
        val load = Shell.cmd(binaryPath + " kpm load " + quote(dest)).exec()
        load.isSuccess to (if (load.isSuccess) dest else (load.out + load.err).joinToString(" ").trim())
    }

    suspend fun removePersistent(name: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val dest = persistentDir + "/" + name
        val r = Shell.cmd("rm -f " + quote(dest)).exec()
        r.isSuccess to (r.out + r.err).joinToString(" ").trim()
    }

    suspend fun unload(name: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val r = Shell.cmd(binaryPath + " kpm unload " + quote(name)).exec()
        r.isSuccess to (r.out + r.err).joinToString(" ").trim()
    }

    suspend fun load(path: String, extraArgs: String = ""): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        fun attempt(p: String) = buildString {
            append(binaryPath).append(" kpm load ").append(quote(p))
            if (extraArgs.isNotBlank()) append(' ').append(extraArgs)
        }
        var r = Shell.cmd(attempt(path)).exec()
        if (!r.isSuccess) {
            // App 私有目录可能被 SELinux 挡住，复制到 /data/local/tmp 再试一次
            val tmp = "/data/local/tmp/kpm_import.kpm"
            val copied = Shell.cmd(
                "cp " + quote(path) + " " + tmp + " && chmod 644 " + tmp,
            ).exec()
            if (copied.isSuccess) r = Shell.cmd(attempt(tmp)).exec()
        }
        r.isSuccess to (r.out + r.err).joinToString(" ").trim()
    }

    suspend fun ctl0(name: String, args: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // 模块要求参数整体作为一个 argv 传入（"add 10123"），裸传会被拆成两个参数，
        // 模块只会收到 "add" 并回 ERROR: unknown command。空参数则直接拒发。
        if (args.isEmpty()) {
            return@withContext false to "参数不能为空，例如 add 10123 / list / clear"
        }
        val escaped = args
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
        val argToken = "\"" + escaped + "\""
        val cmd = binaryPath + " kpm ctl0 " + quote(name) + " " + argToken
        val r = Shell.cmd(cmd).exec()
        val text = (if (r.out.isNotEmpty()) r.out else r.err).joinToString(" ").trim()
        // 模块自身用 ERROR: 前缀表示失败，kpatch 的退出码不一定非零，这里一并判定
        val ok = r.isSuccess && !text.contains("ERROR")
        if (ok) true to text.ifEmpty { "已发送（无返回）" } else false to (text.ifEmpty { "无输出" } + " ｜命令：" + cmd)
    }
}
