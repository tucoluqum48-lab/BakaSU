package org.bakasu.bakasu.ui.screen.kpm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.data.kpm.KpmModule
import org.bakasu.bakasu.data.kpm.KpmRepository
import org.bakasu.bakasu.ui.component.settings.AppBackButton
import org.bakasu.bakasu.ui.component.settings.SegmentedColumn
import org.bakasu.bakasu.ui.component.settings.SettingsBaseWidget
import org.bakasu.bakasu.ui.component.settings.SettingsJumpPageWidget
import org.bakasu.bakasu.ui.navigation.LocalNavigator
import org.bakasu.bakasu.ui.util.adaptiveScaffoldWindowInsets

/**
 * KPM 模块管理页：驱动 KPatch-Next 的 kpatch CLI。
 * 模块由内核侧的 KPatch-Next 加载，不依赖管理器内置的 KPM 支持。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KpmScreen() {
    val repo = remember { KpmRepository() }
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var loading by remember { mutableStateOf(true) }
    var available by remember { mutableStateOf(false) }
    var toolVer by remember { mutableStateOf("") }
    var kernelVer by remember { mutableStateOf("") }
    var rehook by remember { mutableStateOf("") }
    var modules by remember { mutableStateOf<List<KpmModule>>(emptyList()) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            loading = true
            available = repo.isAvailable()
            if (available) {
                toolVer = repo.toolVersion()
                kernelVer = repo.kernelVersion()
                rehook = repo.rehookStatus()
                modules = repo.listNames().mapNotNull { repo.info(it) }
            } else {
                modules = emptyList()
            }
            loading = false
        }
    }

    val pickKpm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            val dest = File(context.cacheDir, "kpm_import.kpm")
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.length() > 0
                }.getOrDefault(false)
            }
            message = if (!copied) {
                "读取所选文件失败"
            } else {
                val result = repo.load(dest.absolutePath)
                if (result.first) "已加载 " + dest.name else "加载失败：" + result.second
            }
            refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        contentWindowInsets = adaptiveScaffoldWindowInsets(),
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(text = "KPM 模块") },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    AppBackButton(onClick = { navigator.pop() })
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 5.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                SegmentedColumn(title = "运行状态") {
                    item {
                        SettingsBaseWidget(
                            title = "KPatch-Next",
                            description = if (available) toolVer else "未检测到（内核未打 KPatch 补丁）",
                        )
                    }
                    if (available) {
                        item {
                            SettingsBaseWidget(title = "内核版本", description = kernelVer)
                        }
                        item {
                            SettingsBaseWidget(
                                title = "重挂模式",
                                description = rehook.ifEmpty { "未知" },
                            )
                        }
                    }
                }
            }

            item {
                SegmentedColumn(title = "操作") {
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.TwoTone.Refresh,
                            title = "刷新列表",
                            description = if (loading) "读取中…" else "已加载 " + modules.size + " 个模块",
                            onClick = { refresh() },
                        )
                    }
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.TwoTone.Add,
                            title = "加载 .kpm 文件",
                            description = if (available) "选择本地 .kpm 并加载到内核" else "需要 KPatch-Next",
                            enabled = available,
                            onClick = { pickKpm.launch(arrayOf("*/*")) },
                        )
                    }
                }
            }

            if (message.isNotEmpty()) {
                item {
                    SegmentedColumn(title = "上次操作") {
                        item {
                            SettingsBaseWidget(title = null, description = message)
                        }
                    }
                }
            }

            items(modules.size) { index ->
                val m = modules[index]
                SegmentedColumn(title = m.name) {
                    item {
                        SettingsBaseWidget(
                            title = "版本",
                            description = listOfNotNull(
                                m.version.ifEmpty { null },
                                m.license.ifEmpty { null },
                            ).joinToString(" · ").ifEmpty { "未知" },
                        )
                    }
                    item {
                        SettingsBaseWidget(title = "作者", description = m.author.ifEmpty { "未知" })
                    }
                    item {
                        SettingsBaseWidget(title = "参数", description = m.args.ifEmpty { "(null)" })
                    }
                    if (m.description.isNotEmpty()) {
                        item {
                            SettingsBaseWidget(title = "说明", description = m.description)
                        }
                    }
                    item {
                        SettingsJumpPageWidget(
                            icon = Icons.TwoTone.Delete,
                            title = "卸载",
                            description = "从内核中移除该模块",
                            isError = true,
                            onClick = {
                                scope.launch {
                                    val result = repo.unload(m.name)
                                    message = if (result.first) {
                                        "已卸载 " + m.name
                                    } else {
                                        "卸载失败：" + result.second
                                    }
                                    refresh()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
