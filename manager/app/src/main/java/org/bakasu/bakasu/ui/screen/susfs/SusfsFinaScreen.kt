package org.bakasu.bakasu.ui.screen.susfs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateMapOf
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
import org.bakasu.bakasu.domain.model.SusKstatStatically
import org.bakasu.bakasu.domain.model.UidScheme
import org.bakasu.bakasu.ui.component.settings.AppBackButton
import org.bakasu.bakasu.ui.navigation.LocalNavigator
import org.bakasu.bakasu.ui.util.LocalSnackbarHost
import org.bakasu.bakasu.ui.util.showReplacingSnackbar
import org.bakasu.bakasu.ui.viewmodel.SusKstatOperation
import org.bakasu.bakasu.ui.viewmodel.SuSFSCommandReply
import org.bakasu.bakasu.ui.viewmodel.SuSFSUiAction
import org.bakasu.bakasu.ui.viewmodel.SuSFSViewModel
import org.bakasu.bakasu.ui.viewmodel.awaitSuSFSBoolean
import org.bakasu.bakasu.ui.viewmodel.awaitSuSFSConfig
import org.bakasu.bakasu.ui.viewmodel.awaitSuSFSStatusInfo
import org.koin.compose.viewmodel.koinViewModel

private val TAB_TITLES = listOf("伪装", "路径", "重定向", "扩展")

private val STATIC_FIELDS = listOf(
    "ino" to "inode",
    "dev" to "设备号",
    "nlink" to "硬链接数",
    "size" to "大小",
    "blocks" to "块数",
    "blksize" to "块大小",
    "atime" to "访问时间",
    "atime_nsec" to "访问时间纳秒",
    "mtime" to "修改时间",
    "mtime_nsec" to "修改时间纳秒",
    "ctime" to "变更时间",
    "ctime_nsec" to "变更时间纳秒",
)

private fun parseCaps(raw: String?): Set<String> =
    (raw ?: "")
        .lines()
        .flatMap { it.split(' ') }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.removePrefix("CONFIG_KSU_SUSFS_") }
        .toSet()

/**
 * 参考 FinaSU 的 SUSFS 页面：顶部状态条（含能力探测）+ 四个聚合 Tab。
 * 不支持的能力会在对应区域提示并禁用操作。
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
    var capExpanded by remember { mutableStateOf(false) }
    val caps = remember(status) { parseCaps(status?.enabledFeatures) }

    fun notify(msg: String) {
        scope.launch { snackbar.showReplacingSnackbar(msg) }
    }

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
                actions = { TextButton(onClick = { reload() }) { Text("刷新") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            StatusBar(
                config = config,
                status = status,
                busy = busy,
                caps = caps,
                capExpanded = capExpanded,
                onToggleCaps = { capExpanded = !capExpanded },
            )
            PrimaryTabRow(selectedTabIndex = tab) {
                TAB_TITLES.forEachIndexed { index, title ->
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
                    caps = caps,
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
                            notify(if (ok) "已应用" else "应用失败")
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
                    supported = caps.contains("SUS_PATH"),
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
                    supported = caps.contains("OPEN_REDIRECT"),
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
                    mapSupported = caps.contains("SUS_MAP"),
                    kstatSupported = caps.contains("SUS_KSTAT"),
                    onAddMap = { p -> bool("添加失败") { r -> SuSFSUiAction.AddSusMap(p, r) } },
                    onRemoveMap = { p -> bool("删除失败") { r -> SuSFSUiAction.RemoveSusMap(p, r) } },
                    onAddKstat = { p, values ->
                        bool("添加失败") { r ->
                            SuSFSUiAction.AddSusKstat(p, SusKstatOperation.Statically, values, r)
                        }
                    },
                    onAddKstatClone = { p ->
                        bool("添加失败") { r ->
                            SuSFSUiAction.AddSusKstat(p, SusKstatOperation.FullClone, null, r)
                        }
                    },
                    onRemoveKstat = { p -> bool("删除失败") { r -> SuSFSUiAction.RemoveSusKstat(p, r) } },
                )
            }
        }
    }
}

@Composable
private fun StatusBar(
    config: SuSFSConfig?,
    status: SuSFSStatusInfo?,
    busy: Boolean,
    caps: Set<String>,
    capExpanded: Boolean,
    onToggleCaps: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "能力: " + caps.size + " 项",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onToggleCaps) {
                    Text(if (capExpanded) "收起" else "展开", fontSize = 11.sp)
                }
            }
            if (capExpanded) {
                caps.sorted().forEach { cap ->
                    Text(
                        text = cap,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
private fun UnsupportedHint(feature: String) {
    Text(
        text = "内核未启用 " + feature + "，此区域不可用",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SpoofTab(
    config: SuSFSConfig?,
    caps: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onApplyUname: (String, String) -> Unit,
    onApplyCmdline: (String) -> Unit,
) {
    val unameSupported = caps.contains("SPOOF_UNAME")
    val cmdlineSupported = caps.contains("SPOOF_CMDLINE_OR_BOOTCONFIG")
    val logSupported = caps.contains("ENABLE_LOG")
    val mntsSupported = caps.contains("SUS_MOUNT")

    var release by remember(config?.uname?.release) { mutableStateOf(config?.uname?.release ?: "default") }
    var version by remember(config?.uname?.version) { mutableStateOf(config?.uname?.version ?: "default") }
    var cmdline by remember(config?.cmdline_or_bootconfig) {
        mutableStateOf(config?.cmdline_or_bootconfig ?: "")
    }
    var confirmUname by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("开关") {
                SwitchRow("启用 SUSFS", config?.enabled == true, true) { onToggle("enabled", it) }
                SwitchRow("内核日志", config?.logging == true, logSupported) { onToggle("logging", it) }
                SwitchRow("AVC 日志欺骗", config?.avc_log_spoofing == true, true) { onToggle("avc", it) }
                SwitchRow("对非 su 隐藏挂载", config?.hide_sus_mnts_for_non_su_procs == true, mntsSupported) {
                    onToggle("mnts", it)
                }
            }
        }
        item {
            Section("内核版本伪装（uname）") {
                if (!unameSupported) {
                    UnsupportedHint("SPOOF_UNAME")
                } else {
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
        }
        item {
            Section("cmdline / bootconfig") {
                if (!cmdlineSupported) {
                    UnsupportedHint("SPOOF_CMDLINE_OR_BOOTCONFIG")
                } else {
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
    }

    if (confirmUname) {
        AlertDialog(
            onDismissRequest = { confirmUname = false },
            title = { Text("确认修改 uname？") },
            text = {
                Column {
                    Text("release = " + release, fontSize = 13.sp)
                    Text("version = " + version, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("修改后部分应用可能读取到伪造的内核版本，建议修改后重启一次。", fontSize = 13.sp)
                }
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
    supported: Boolean,
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
                if (!supported) {
                    UnsupportedHint("SUS_PATH")
                } else {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("路径，如 /data/adb/modules") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("循环挂载 (is_loop)", loop, true) { loop = it }
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
                            val lines = batch.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            if (lines.isNotEmpty()) {
                                onAddBatch(lines)
                                batch = ""
                            }
                        }) { Text("批量导入") }
                    }
                }
            }
        }
        item {
            Section("已隐藏路径（" + (config?.sus_path?.size ?: 0) + "）") {
                val items = config?.sus_path?.toList() ?: emptyList()
                if (items.isEmpty()) {
                    EmptyHint()
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
    supported: Boolean,
    onAdd: (String, String, UidScheme) -> Unit,
    onRemove: (String) -> Unit,
) {
    var target by remember { mutableStateOf("") }
    var redirected by remember { mutableStateOf("") }
    var scheme by remember { mutableStateOf(UidScheme.NonApp) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("添加重定向") {
                if (!supported) {
                    UnsupportedHint("OPEN_REDIRECT")
                } else {
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
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
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
        }
        item {
            Section("已有重定向（" + (config?.open_redirect?.size ?: 0) + "）") {
                val items = config?.open_redirect?.toList() ?: emptyList()
                if (items.isEmpty()) {
                    EmptyHint()
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
    mapSupported: Boolean,
    kstatSupported: Boolean,
    onAddMap: (String) -> Unit,
    onRemoveMap: (String) -> Unit,
    onAddKstat: (String, SusKstatStatically?) -> Unit,
    onAddKstatClone: (String) -> Unit,
    onRemoveKstat: (String) -> Unit,
) {
    var mapPath by remember { mutableStateOf("") }
    var kstatPath by remember { mutableStateOf("") }
    var showStatic by remember { mutableStateOf(false) }
    val staticValues = remember { mutableStateMapOf<String, String>() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("映射隐藏 sus_map（" + (config?.sus_map?.size ?: 0) + "）") {
                if (!mapSupported) {
                    UnsupportedHint("SUS_MAP")
                } else {
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
                        EmptyHint()
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
        }
        item {
            Section("stat 伪装 sus_kstat（" + (config?.sus_kstat?.size ?: 0) + "）") {
                if (!kstatSupported) {
                    UnsupportedHint("SUS_KSTAT")
                } else {
                    OutlinedTextField(
                        value = kstatPath,
                        onValueChange = { kstatPath = it },
                        label = { Text("路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            if (kstatPath.isNotBlank()) {
                                onAddKstatClone(kstatPath.trim())
                                kstatPath = ""
                            }
                        }) { Text("FullClone") }
                        TextButton(onClick = { showStatic = true }) { Text("静态字段…") }
                    }
                    val items = config?.sus_kstat?.toList() ?: emptyList()
                    if (items.isEmpty()) {
                        EmptyHint()
                    } else {
                        items.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.path + "  (" + item.spoof_type.name + ")",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth(0.72f),
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

    if (showStatic) {
        AlertDialog(
            onDismissRequest = { showStatic = false },
            title = { Text("静态 stat 字段") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    OutlinedTextField(
                        value = kstatPath,
                        onValueChange = { kstatPath = it },
                        label = { Text("要伪装的路径（必填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (kstatPath.isBlank()) "请先填写路径，其余字段留空表示不伪造"
                        else "路径 = " + kstatPath + "，字段留空表示不伪造",
                        fontSize = 12.sp,
                        color = if (kstatPath.isBlank()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    STATIC_FIELDS.forEach { (key, label) ->
                        OutlinedTextField(
                            value = staticValues[key] ?: "",
                            onValueChange = { staticValues[key] = it },
                            label = { Text(key + "  (" + label + ")") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = kstatPath.isNotBlank(),
                    onClick = {
                    val values = SusKstatStatically(
                        ino = staticValues["ino"]?.toLongOrNull(),
                        dev = staticValues["dev"]?.toLongOrNull(),
                        nlink = staticValues["nlink"]?.toLongOrNull(),
                        size = staticValues["size"]?.toLongOrNull(),
                        atime = staticValues["atime"]?.toLongOrNull(),
                        atime_nsec = staticValues["atime_nsec"]?.toLongOrNull(),
                        mtime = staticValues["mtime"]?.toLongOrNull(),
                        mtime_nsec = staticValues["mtime_nsec"]?.toLongOrNull(),
                        ctime = staticValues["ctime"]?.toLongOrNull(),
                        ctime_nsec = staticValues["ctime_nsec"]?.toLongOrNull(),
                        blocks = staticValues["blocks"]?.toLongOrNull(),
                        blksize = staticValues["blksize"]?.toLongOrNull(),
                    )
                    showStatic = false
                    onAddKstat(kstatPath.trim(), values)
                    staticValues.clear()
                    kstatPath = ""
                    },
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showStatic = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyHint() {
    Text("暂无条目", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
