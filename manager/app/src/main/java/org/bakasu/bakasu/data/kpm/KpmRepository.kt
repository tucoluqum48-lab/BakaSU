package org.bakasu.bakasu.data.kpm

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        const val ASSET_NAME = "kpatch-arm64"

        /** 自理目录：管理器自举释放的 kpatch，优先使用 */
        const val SELF_MANAGED_BIN = "/data/adb/kpm-tool/bin/kpatch"

        val CANDIDATE_DIRS = listOf(
            "/data/adb/kpm-tool/bin",
            "/data/adb/modules/KPatch-Next/bin",
            "/data/adb/kp-next/bin",
            "/data/adb/kpm/bin",
        )
    }

    /**
     * 注意：/data/adb/kpm-tool 权限是 0700，App 进程 stat 不到，
     * 所以自理目录必须先交给 root shell 判断，不能直接 File.exists()。
     */
    val binaryPath: String by lazy {
        val probe = Shell.cmd("test -x " + SELF_MANAGED_BIN + " && echo KPM_OK").exec()
        if (probe.out.any { it.trim() == "KPM_OK" }) {
            SELF_MANAGED_BIN
        } else {
            CANDIDATE_DIRS.map { File(it, "kpatch") }.firstOrNull { it.exists() }?.absolutePath
                ?: "/data/adb/modules/KPatch-Next/bin/kpatch"
        }
    }

    private val bootScriptBody: String = """
#!/system/bin/sh
# 由 BakaSU 的 KPM 页面生成：开机自动加载 /data/adb/kpm-tool/kpm/*.kpm
[ -x /data/adb/kpm-tool/bin/kpatch ] || exit 0
until [ "${'$'}(getprop sys.boot_completed)" = "1" ]; do sleep 1; done
for f in /data/adb/kpm-tool/kpm/*.kpm; do
    [ -s "${'$'}f" ] || continue
    /data/adb/kpm-tool/bin/kpatch kpm load "${'$'}f" >> /data/adb/kpm-tool/autoload.log 2>&1
done
""".trimIndent()

    /**
     * 首次运行时把 APK 内置的 kpatch 释放到 /data/adb/kpm-tool/bin，
     * 并写好开机自动加载脚本，从此不再依赖 KPatch-Next 模块。
     */
    suspend fun bootstrap(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bin = File(BIN_DIR, "kpatch")
            // App 无法 stat /data/adb 下的 0700 目录，交给 shell 判断
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

    suspend fun listNames(): List<String> =
        exec("kpm", "list").map { it.trim() }.filter { it.isNotEmpty() }

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

    suspend fun unload(name: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val r = Shell.cmd(binaryPath + " kpm unload " + quote(name)).exec()
        r.isSuccess to (r.out + r.err).joinToString(" ").trim()
    }

    suspend fun load(path: String, extraArgs: String = ""): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            fun attempt(p: String) = buildString {
                append(binaryPath).append(" kpm load ").append(quote(p))
                if (extraArgs.isNotBlank()) append(' ').append(extraArgs)
            }
            var r = Shell.cmd(attempt(path)).exec()
            if (!r.isSuccess) {
                // App 私有目录可能被 SELinux 挡住，复制到 /data/local/tmp 再试一次
                val tmp = "/data/local/tmp/kpm_import.kpm"
                val copied = Shell.cmd(
                    "cp " + quote(path) + " " + tmp + " && chmod 644 " + tmp
                ).exec()
                if (copied.isSuccess) r = Shell.cmd(attempt(tmp)).exec()
            }
            r.isSuccess to (r.out + r.err).joinToString(" ").trim()
        }

    suspend fun ctl0(name: String, args: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val r = Shell.cmd(binaryPath + " kpm ctl0 " + quote(name) + " " + args).exec()
        r.isSuccess to (r.out + r.err).joinToString(" ").trim()
    }
}
