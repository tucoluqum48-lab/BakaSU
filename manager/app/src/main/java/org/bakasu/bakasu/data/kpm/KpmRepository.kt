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
class KpmRepository {

    private companion object {
        val CANDIDATE_DIRS = listOf(
            "/data/adb/modules/KPatch-Next/bin",
            "/data/adb/kp-next/bin",
            "/data/adb/kpm/bin",
        )
    }

    val binaryPath: String by lazy {
        CANDIDATE_DIRS.map { File(it, "kpatch") }.firstOrNull { it.exists() }?.absolutePath
            ?: "/data/adb/modules/KPatch-Next/bin/kpatch"
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

    /** 开机自启目录：service.sh 会遍历这里面的 .kpm 逐个加载 */
    val persistentDir = "/data/adb/kp-next/kpm"

    suspend fun persistentList(): List<String> = withContext(Dispatchers.IO) {
        val r = Shell.cmd("ls -1 " + persistentDir + " 2>/dev/null").exec()
        r.out.map { it.trim() }.filter { it.endsWith(".kpm") }
    }

    /**
     * 开机自动加载：把 .kpm 复制进自启目录并立刻加载一次。
     * 下次开机由 KPatch-Next 的 service.sh 自动加载。
     */
    suspend fun installPersistent(srcPath: String, fileName: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val safeName = if (fileName.endsWith(".kpm")) fileName else fileName + ".kpm"
            val dest = persistentDir + "/" + safeName
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
