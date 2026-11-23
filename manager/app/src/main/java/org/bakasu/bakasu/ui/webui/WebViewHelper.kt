package org.bakasu.bakasu.ui.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.R
import org.bakasu.bakasu.data.AppSettingsRepository
import org.bakasu.bakasu.data.packageinfo.AppIconDataSource
import org.bakasu.bakasu.data.packageinfo.InstalledPackageRepository
import org.bakasu.bakasu.data.webui.WebUiRepository
import org.bakasu.bakasu.ui.viewmodel.SuperUserUiAction
import org.bakasu.bakasu.ui.viewmodel.SuperUserViewModel

private const val WEB_DOMAIN = "mui.kernelsu.org"
private const val KSU_SCHEME = "ksu"
private const val ICON_HOST = "icon"
private const val INTERNAL_BLOB_DOWNLOAD_HOST = "blob-download.kernelsu.internal"

private fun loadDownloadJs(context: Context): String = context.assets.open("webview/download.js").bufferedReader(Charsets.UTF_8).use { it.readText() }

fun Activity.setTaskDescription(label: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(label))
    } else {
        val taskDescription = ActivityManager.TaskDescription.Builder()
            .setLabel(label)
            .build()
        setTaskDescription(taskDescription)
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal suspend fun prepareWebView(
    activity: Activity,
    moduleId: String,
    webUIState: WebUIState,
    superUserViewModel: SuperUserViewModel,
    settingsRepository: AppSettingsRepository,
    packageRepository: InstalledPackageRepository,
    appIconDataSource: AppIconDataSource,
    webUiRepository: WebUiRepository,
    colorsCssProvider: () -> String,
) {
    withContext(Dispatchers.IO) {
        // 内置界面（KPM）：命中时跳过模块查询，直接使用 app 私有目录
        val builtinRoot = BuiltinWebUI.rootFor(moduleId)
        val moduleInfo = if (builtinRoot != null) {
            null
        } else try {
            webUiRepository.getModuleInfo(moduleId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            withContext(Dispatchers.Main) {
                webUIState.uiEvent = WebUIEvent.Error(
                    activity.getString(R.string.module_unavailable, moduleId),
                )
            }
            return@withContext
        }

        if (builtinRoot == null && moduleInfo == null) {
            withContext(Dispatchers.Main) {
                webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.no_such_module, moduleId))
            }
            return@withContext
        }

        if (builtinRoot == null && (!moduleInfo!!.hasWebUi || !moduleInfo.enabled || moduleInfo.remove)) {
            withContext(Dispatchers.Main) {
                webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.module_unavailable, moduleInfo.name))
            }
            return@withContext
        }

        if (packageRepository.packages.value.isEmpty()) {
            superUserViewModel.dispatch(SuperUserUiAction.Refresh)
        }
        withContext(Dispatchers.Main) {
            val displayName = builtinRoot?.let { BuiltinWebUI.info().name } ?: moduleInfo!!.name
            webUIState.moduleName = displayName
            webUIState.modDir = builtinRoot?.absolutePath ?: "/data/adb/modules/$moduleId"

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                activity.setTaskDescription(ActivityManager.TaskDescription("KernelSU - $displayName"))
            } else {
                val taskDescription = ActivityManager.TaskDescription.Builder()
                    .setLabel("KernelSU - $displayName")
                    .build()
                activity.setTaskDescription(taskDescription)
            }

            val webView = WebView(activity)
            webView.setBackgroundColor(Color.TRANSPARENT)

            WebView.setWebContentsDebuggingEnabled(
                settingsRepository.getBoolean("enable_web_debugging", false),
            )

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
            }

            val webRoot = File("${webUIState.modDir}/webroot")
            // 内置界面用系统自带的 InternalStoragePathHandler（只要求目录在 app 私有存储内，
            // 不做 /data/data 黑名单校验）；模块界面仍走 SuFilePathHandler（需 root shell 读 /data/adb）
            val pathHandler: WebViewAssetLoader.PathHandler = if (builtinRoot != null) {
                WebViewAssetLoader.InternalStoragePathHandler(activity, webRoot)
            } else {
                SuFilePathHandler(
                    webRoot,
                    webUiRepository,
                    { webUIState.currentInsets },
                    { enable -> webUIState.isInsetsEnabled = enable },
                    colorsCssProvider,
                )
            }
            val webViewAssetLoader = WebViewAssetLoader.Builder()
                .setDomain(WEB_DOMAIN)
                .addPathHandler("/", pathHandler)
                .build()

            // WebViewClient
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url

                    BlobDownloadHandler.shouldInterceptRequest(request)?.let { return it }

                    if (url.scheme.equals(KSU_SCHEME, ignoreCase = true) && url.host.equals(ICON_HOST, ignoreCase = true)) {
                        val packageName = url.path?.substring(1)
                        if (!packageName.isNullOrEmpty()) {
                            val icon = appIconDataSource.loadSync(packageName, 512)
                            if (icon != null) {
                                val stream = ByteArrayOutputStream()
                                icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                return WebResourceResponse(
                                    "image/png",
                                    null,
                                    200,
                                    "OK",
                                    mapOf("Access-Control-Allow-Origin" to "*"),
                                    ByteArrayInputStream(stream.toByteArray()),
                                )
                            } else {
                                val errorMsg = "No such package"
                                val errorStream = ByteArrayInputStream(errorMsg.toByteArray(Charsets.UTF_8))
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    404,
                                    "Not Found",
                                    mapOf("Access-Control-Allow-Origin" to "*"),
                                    errorStream,
                                )
                            }
                        }
                    }
                    return webViewAssetLoader.shouldInterceptRequest(url)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    webUIState.webCanGoBack = view?.canGoBack() ?: false
                    if (webUIState.isInsetsEnabled) webUIState.webView?.evaluateJavascript(webUIState.currentInsets.js, null)
                    view?.evaluateJavascript(loadDownloadJs(activity), null)
                    super.doUpdateVisitedHistory(view, url, isReload)
                }
            }

            // WebChromeClient
            webView.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowAlert(message, result)
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowConfirm(message, result)
                    return true
                }

                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                    if (message == null || result == null || defaultValue == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowPrompt(message, defaultValue, result)
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    webUIState.filePathCallback?.onReceiveValue(null)
                    webUIState.filePathCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    webUIState.uiEvent = WebUIEvent.ShowFileChooser(intent)
                    return true
                }
            }

            // JS Interface
            val webviewInterface = WebViewInterface(webUIState, packageRepository, webUiRepository)
            val downloadInterface = WebUIDownloadInterface(webUIState)
            webUIState.webViewInterface = webviewInterface
            webUIState.downloadInterface = downloadInterface
            webUIState.webView = webView
            webView.addJavascriptInterface(webviewInterface, "ksu")
            webView.addJavascriptInterface(downloadInterface, "ksu_download")
            webView.setDownloadListener { url, _, _, _, _ ->
                if (Uri.parse(url).host != INTERNAL_BLOB_DOWNLOAD_HOST) {
                    downloadInterface.openExternal(url)
                }
            }
            webView.evaluateJavascript(loadDownloadJs(activity), null)
            webUIState.uiEvent = WebUIEvent.WebViewReady
        }
    }
}
