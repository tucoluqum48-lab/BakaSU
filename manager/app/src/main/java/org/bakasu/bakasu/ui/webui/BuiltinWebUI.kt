package org.bakasu.bakasu.ui.webui

import java.io.File
import org.bakasu.bakasu.domain.model.WebUiModuleInfo

/**
 * 管理器内置的 WebUI（不依赖任何已安装模块）。
 *
 * WebViewHelper 走的是 `/data/adb/modules/<id>/webroot` 这一套；为了能在
 * 完全不安装模块的情况下加载内置界面，这里登记一个 app 私有目录，
 * prepareWebView 会优先用它代替模块目录。
 */
object BuiltinWebUI {
    /** 伪模块 id，仅用于 Intent 传参，不对应 /data/adb/modules 下的任何目录 */
    const val MODULE_ID = "kpm-builtin"

    @Volatile
    private var rootDir: File? = null

    @Volatile
    private var displayName: String = "KPM 管理界面"

    /** 登记/更新内置界面根目录（其下需有 webroot/） */
    fun register(root: File, name: String = "KPM 管理界面") {
        rootDir = root
        displayName = name
    }

    /** 若 id 命中内置界面，返回其根目录 */
    fun rootFor(moduleId: String): File? =
        if (moduleId == MODULE_ID) rootDir?.takeIf { it.isDirectory } else null

    /** 内置界面的伪模块信息 */
    fun info(): WebUiModuleInfo = WebUiModuleInfo(
        name = displayName,
        enabled = true,
        remove = false,
        hasWebUi = true,
    )
}
