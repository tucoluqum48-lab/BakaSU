package org.bakasu.bakasu.ui.screen.susfs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.bakasu.bakasu.domain.model.SuSFSConfig
import org.bakasu.bakasu.domain.model.SuSFSStatusInfo
import org.bakasu.bakasu.domain.model.UidScheme
import org.bakasu.bakasu.ui.component.settings.AppBackButton
import org.bakasu.bakasu.ui.navigation.LocalNavigator
import org.bakasu.bakasu.ui.util.LocalSnackbarHost
import org.bakasu.bakasu.ui.util.showReplacingSnackbar
import org.bakasu.bakasu.ui.viewmodel.SuSFSCommandReply
import org.bakasu.bakasu.ui.viewmodel.SuSFSUiAction
import org.bakasu.bakasu.ui.viewmodel.SusKstatOperation
import org.bakasu.bakasu.ui.viewmodel.SuSFSViewModel
import org.bakasu.bakasu.ui.viewmodel.awaitSuSFSBoolean
import org.bakasu.bakasu.ui.viewmodel.awaitSuSFSConfig
import org.bakasu.bakasu.ui.viewmodel.awaitSuSFSStatusInfo
import org.koin.compose.viewmodel.koinViewModel

private val ABI_ORDER = listOf("伪装", "路径", "重定向", "扩展")

/**
 * 参考 FinaSU 的 SUSFS 页面结构：顶部状态条 + 四个聚合 Tab。
 * 数据层仍走 BakaSU 自己的 SuSFSViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SusfsFinaScreen() {
    val vm = koinViewModel<SuSFSViewModel>()
    val navigator = LocalNavigator.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf<SuSFSConfig?>(null) }
    var status by remember { mutableStateOf<SuSFSStatusInfo?>(null) }
    var tab by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    fun notify(msg: String) = snackbar.showReplacingSnackbar(msg)

    fun reload() {
        scope.launch {
            busy = true
            config = awaitSuSFSConfig(vm) { r -> SuSFSUiAction.Refresh(r) }
            status = awaitSuSFSStatusInfo(vm, true)
            busy = false
        }
    }

    fun bool(fail: String, action: (SuSFSCommandReply) -> SuSFSUiAction) {
        scope.launch {
            busy = true
            if (!awaitSuSFSBoolean(vm, action)) notify(fail)
            config = awaitSuSFSConfig(vm) { r -> SuSFSUiAction.Refresh(r) }
            busy = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SUSFS 配置") },
                navigationIcon = { AppBackButton(onClick = { navigator.pop() }) },
                actions = {
                    TextButton(onClick = { reload() }) { Text("刷新") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            StatusBar(config = config, status = status, busy = busy)
            PrimaryTabRow(selectedTabIndex = tab) {
                ABI_ORDER.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title, fontSize = 13.sp) },
                    )
                }
            }
            when (tab) {
                0 -> SpoofTab(
                    config = config,
                    onToggle = { key, value ->
                        when (key) {
                            "enabled" -> bool("切换失败") { r -> SuSFSUiAction.SetEnabled(value, r) }
                            "logging" -> bool("设置失败") { r -> SuSFSUiAction.EnableLog(value, r) }
                            "avc" -> bool("设置失败") { r -> SuSFSUiAction.EnableAvcLogSpoofing(value, r) }
                            "mnts" -> bool("设置失败") { r -> SuSFSUiAction.HideSusMnts(value, r) }
                        }
                    },
                    onApplyUname = { release, version ->
                        scope.launch {
                            busy = true
                            val ok = awaitSuSFSBoolean(vm) { r ->
                                SuSFSUiAction.SetUname(release, version, r)
                            }
                            notify(if (ok) "已应用，当前生效值见顶部" else "应用失败")
                            config = awaitSuSFSConfig(vm) { r -> SuSFSUiAction.Refresh(r) }
                            status = awaitSuSFSStatusInfo(vm, true)
                            busy = false
                        }
                    },
                    onApplyCmdline = { path ->
                        bool("设置失败") { r -> SuSFSUiAction.SetCmdlineOrBootconfig(path, r) }
                    },
                )
                1 -> PathTab(
                    config = config,
                    onAdd = { path, loop ->
                        bool("添加失败") { r -> SuSFSUiAction.AddSusPath(path, loop, r) }
                    },
                    onAddBatch = { lines ->
                        scope.launch {
                            busy = true
                            var fail = 0
                            for (line in lines) {
                                val p = line.trim()
                                if (p.isEmpty()) continue
                                val ok = awaitSuSFSBoolean(vm) { r ->
                                    SuSFSUiAction.AddSusPath(p, false, r)
                                }
                                if (!ok) fail++
                            }
                            notify(if (fail == 0) "已添加 " + lines.size + " 条" else "有 " + fail + " 条失败")
                            config = awaitSuSFSConfig(vm) { r -> SuSFSUiAction.Refresh(r) }
                            busy = false
                        }
                    },
                    onRemove = { path ->
                        bool("删除失败") { r -> SuSFSUiAction.RemoveSusPath(path, r) }
                    },
                )
                2 -> RedirectTab(
                    config = config,
                    onAdd = { target, redirected, scheme ->
                        bool("添加失败") { r ->
                            SuSFSUiAction.AddOpenRedirect(target, redirected, scheme, r)
                        }
                    },
                    onRemove = { target ->
                        bool("删除失败") { r -> SuSFSUiAction.RemoveOpenRedirect(target, r) }
                    },
                )
                else -> ExtTab(
                    config = config,
                    onAddMap = { p -> bool("添加失败") { r -> SuSFSUiAction.AddSusMap(p, r) } },
                    onRemoveMap = { p -> bool("删除失败") { r -> SuSFSUiAction.RemoveSusMap(p, r) } },
                    onAddKstat = { p ->
                        bool("添加失败") { r ->
                            SuSFSUiAction.AddSusKstat(p, SusKstatOperation.Normal, null, r)
                        }
                    },
                    onRemoveKstat = { p -> bool("删除失败") { r -> SuSFSUiAction.RemoveSusKstat(p, r) } },
                )
            }
        }
    }
}

@Composable
private fun StatusBar(config: SuSFSConfig?, status: SuSFSStatusInfo?, busy: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (config?.enabled == true) "SUSFS 已启用" else "SUSFS 未启用",
                    fontSize = 15.sp,
                )
                Text(
                    text = status?.version?.ifBlank { "-" } ?: "-",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "能力: " + (status?.enabledFeatures?.ifBlank { "未知" } ?: "未知"),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "条目: 路径 " + (config?.sus_path?.size ?: 0) +
                    " / 映射 " + (config?.sus_map?.size ?: 0) +
                    " / kstat " + (config?.sus_kstat?.size ?: 0) +
                    " / 重定向 " + (config?.open_redirect?.size ?: 0),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Text("处理中…", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SpoofTab(
    config: SuSFSConfig?,
    onToggle: (String, Boolean) -> Unit,
    onApplyUname: (String, String) -> Unit,
    onApplyCmdline: (String) -> Unit,
) {
    var release by remember(config?.uname?.release) { mutableStateOf(config?.uname?.release ?: "default") }
    var version by remember(config?.uname?.version) { mutableStateOf(config?.uname?.version ?: "default") }
    var cmdline by remember(config?.cmdline_or_bootconfig) {
        mutableStateOf(config?.cmdline_or_bootconfig ?: "")
    }
    var confirmUname by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("开关") {
                SwitchRow("启用 SUSFS", config?.enabled == true) { onToggle("enabled", it) }
                SwitchRow("内核日志", config?.logging == true) { onToggle("logging", it) }
                SwitchRow("AVC 日志欺骗", config?.avc_log_spoofing == true) { onToggle("avc", it) }
                SwitchRow("对非 su 隐藏挂载", config?.hide_sus_mnts_for_non_su_procs == true) {
                    onToggle("mnts", it)
                }
            }
        }
        item {
            Section("内核版本伪装（uname）") {
                OutlinedTextField(
                    value = release,
                    onValueChange = { release = it },
                    label = { Text("release") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("version") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmUname = true }) { Text("应用") }
                    TextButton(onClick = {
                        release = "default"
                        version = "default"
                        onApplyUname("default", "default")
                    }) { Text("恢复默认") }
                }
                Text(
                    text = "当前生效: release=" + (config?.uname?.release ?: "-") +
                        " version=" + (config?.uname?.version ?: "-"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Section("cmdline / bootconfig") {
                OutlinedTextField(
                    value = cmdline,
                    onValueChange = { cmdline = it },
                    label = { Text("文件路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onApplyCmdline(cmdline) }) { Text("应用") }
            }
        }
    }

    if (confirmUname) {
        AlertDialog(
            onDismissRequest = { confirmUname = false },
            title = { Text("确认修改 uname？") },
            text = {
                Text(
                    "release = " + release + "
version = " + version +
                        "

修改后部分应用可能读取到伪造的内核版本，建议修改后重启一次。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUname = false
                    onApplyUname(release, version)
                }) { Text("确认应用") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUname = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PathTab(
    config: SuSFSConfig?,
    onAdd: (String, Boolean) -> Unit,
    onAddBatch: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var loop by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf("") }
    var showBatch by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("添加路径") {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("路径，如 /data/adb/modules") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow("循环挂载 (is_loop)", loop) { loop = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        if (path.isNotBlank()) {
                            onAdd(path.trim(), loop)
                            path = ""
                        }
                    }) { Text("添加") }
                    TextButton(onClick = { showBatch = !showBatch }) {
                        Text(if (showBatch) "收起批量" else "批量添加")
                    }
                }
                if (showBatch) {
                    OutlinedTextField(
                        value = batch,
                        onValueChange = { batch = it },
                        label = { Text("每行一条路径") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        val lines = batch.split("
").map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isNotEmpty()) {
                            onAddBatch(lines)
                            batch = ""
                        }
                    }) { Text("批量导入") }
                }
            }
        }
        item {
            Section("已隐藏路径（" + (config?.sus_path?.size ?: 0) + "）") {
                val items = config?.sus_path?.toList() ?: emptyList()
                if (items.isEmpty()) {
                    Text("暂无条目", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.path + (if (item.is_loop) "  [loop]" else ""),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(0.78f),
                            )
                            TextButton(onClick = { onRemove(item.path) }) { Text("删除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RedirectTab(
    config: SuSFSConfig?,
    onAdd: (String, String, UidScheme) -> Unit,
    onRemove: (String) -> Unit,
) {
    var target by remember { mutableStateOf("") }
    var redirected by remember { mutableStateOf("") }
    var scheme by remember { mutableStateOf(UidScheme.NonApp) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("添加重定向") {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("目标路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = redirected,
                    onValueChange = { redirected = it },
                    label = { Text("重定向到") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("UID 方案", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UidScheme.entries.forEach { s ->
                        TextButton(onClick = { scheme = s }) {
                            Text(
                                text = s.name,
                                fontSize = 11.sp,
                                color = if (s == scheme) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(onClick = {
                    if (target.isNotBlank() && redirected.isNotBlank()) {
                        onAdd(target.trim(), redirected.trim(), scheme)
                        target = ""
                        redirected = ""
                    }
                }) { Text("添加") }
            }
        }
        item {
            Section("已有重定向（" + (config?.open_redirect?.size ?: 0) + "）") {
                val items = config?.open_redirect?.toList() ?: emptyList()
                if (items.isEmpty()) {
                    Text("暂无条目", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.target_path + " -> " + item.redirected_path +
                                    "  (" + item.uid_scheme.name + ")",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(0.76f),
                            )
                            TextButton(onClick = { onRemove(item.target_path) }) { Text("删除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtTab(
    config: SuSFSConfig?,
    onAddMap: (String) -> Unit,
    onRemoveMap: (String) -> Unit,
    onAddKstat: (String) -> Unit,
    onRemoveKstat: (String) -> Unit,
) {
    var mapPath by remember { mutableStateOf("") }
    var kstatPath by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("映射隐藏 sus_map（" + (config?.sus_map?.size ?: 0) + "）") {
                OutlinedTextField(
                    value = mapPath,
                    onValueChange = { mapPath = it },
                    label = { Text("so 路径，如 /data/adb/modules/x/lib.so") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    if (mapPath.isNotBlank()) {
                        onAddMap(mapPath.trim())
                        mapPath = ""
                    }
                }) { Text("添加") }
                val items = config?.sus_map?.toList() ?: emptyList()
                if (items.isEmpty()) {
                    Text("暂无条目", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    items.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = p,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(0.78f),
                            )
                            TextButton(onClick = { onRemoveMap(p) }) { Text("删除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        item {
            Section("stat 伪装 sus_kstat（" + (config?.sus_kstat?.size ?: 0) + "）") {
                OutlinedTextField(
                    value = kstatPath,
                    onValueChange = { kstatPath = it },
                    label = { Text("路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    if (kstatPath.isNotBlank()) {
                        onAddKstat(kstatPath.trim())
                        kstatPath = ""
                    }
                }) { Text("添加") }
                val items = config?.sus_kstat?.toList() ?: emptyList()
                if (items.isEmpty()) {
                    Text("暂无条目", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.path,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(0.78f),
                            )
                            TextButton(onClick = { onRemoveKstat(item.path) }) { Text("删除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
