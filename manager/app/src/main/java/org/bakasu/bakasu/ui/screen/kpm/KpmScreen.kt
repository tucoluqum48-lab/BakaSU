package org.bakasu.bakasu.ui.screen.kpm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons as M3Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.bakasu.bakasu.data.kpm.KpmModule
import org.bakasu.bakasu.data.kpm.KpmRepository
import org.bakasu.bakasu.ui.navigation.LocalNavigator

/**
 * KPM 模块管理页：调用 KPatch-Next 的 kpatch CLI，
 * 与内核是否实现 KernelSU 原生 KPM 超调用无关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KpmScreen() {
    val navigator = LocalNavigator.current

    val appContext = LocalContext.current.applicationContext
    val repo = remember { KpmRepository(appContext) }
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var available by remember { mutableStateOf(false) }
    var toolVer by remember { mutableStateOf("") }
    var kernelVer by remember { mutableStateOf("") }
    var rehook by remember { mutableStateOf("") }
    var modules by remember { mutableStateOf<List<KpmModule>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var ctlTarget by remember { mutableStateOf<KpmModule?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var persistentFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var ctlArgs by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            loading = true
            repo.bootstrap()
            available = repo.isAvailable()
            if (available) {
                toolVer = repo.toolVersion()
                kernelVer = repo.kernelVersion()
                rehook = repo.rehookStatus()
                modules = repo.listNames().mapNotNull { repo.info(it) }
            }
            persistentFiles = repo.persistentList()
            loading = false
        }
    }

    val context = LocalContext.current

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
            if (!copied) {
                message = "读取所选文件失败"
                loading = false
                refresh()
            } else {
                pendingFile = dest
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KPM 模块") },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(M3Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
    Column(Modifier.fillMaxSize().padding(16.dp).padding(innerPadding)) {
        Spacer(Modifier.height(6.dp))
        val subtitle = if (available) {
            "KPatch-Next " + toolVer + " · 内核 " + kernelVer + (if (rehook.isNotEmpty()) " · " + rehook else "")
        } else {
            "未检测到 KPatch-Next：需要内核包含 KPatch 补丁并安装 KPatch-Next 模块"
        }
        Text(
            subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已加载 " + modules.size + " 个", fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { refresh() }) { Text("刷新") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { pickKpm.launch(arrayOf("*/*")) }) { Text("加载 .kpm") }
        }

        if (message.isNotEmpty()) {
            Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
        }

        if (loading) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (!available) {
            Text(
                "本页只是一个命令行封装：模块由内核侧的 KPatch-Next 加载，不经过管理器。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (persistentFiles.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "开机自动加载（" + persistentFiles.size + "）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "来自 " + repo.persistentDir,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    persistentFiles.forEach { name ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    val r = repo.removePersistent(name)
                                    message = if (r.first) "已移除自启 " + name else "移除失败：" + r.second
                                    refresh()
                                }
                            }) { Text("移除") }
                        }
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(modules) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(m.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val meta = listOfNotNull(
                            if (m.version.isNotEmpty()) "版本 " + m.version else null,
                            if (m.author.isNotEmpty()) "作者 " + m.author else null,
                            if (m.args.isNotEmpty()) "参数 " + m.args else null,
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(meta, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (m.description.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(m.description, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                ctlTarget = m
                                ctlArgs = ""
                            }) { Text("控制") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val result = repo.unload(m.name)
                                    message = if (result.first) "已卸载 " + m.name else "卸载失败：" + result.second
                                    refresh()
                                }
                            }) { Text("卸载") }
                        }
                    }
                }
            }
        }
    }

    val pending = pendingFile
    if (pending != null) {
        AlertDialog(
            onDismissRequest = {
                pendingFile = null
                refresh()
            },
            title = { Text("加载方式") },
            text = {
                Column {
                    Text(pending.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("临时加载：仅本次开机有效，重启后消失", fontSize = 12.sp)
                    Text("开机自动加载：复制到 /data/adb/kp-next/kpm/，以后每次开机自动加载", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingFile = null
                    scope.launch {
                        loading = true
                        val r = repo.load(pending.absolutePath)
                        message = if (r.first) "已临时加载 " + pending.name else "加载失败：" + r.second
                        refresh()
                    }
                }) { Text("临时加载") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingFile = null
                    scope.launch {
                        loading = true
                        val r = repo.installPersistent(pending.absolutePath, pending.name)
                        message = if (r.first) "已加入开机自启 " + pending.name else "失败：" + r.second
                        refresh()
                    }
                }) { Text("开机自动加载") }
            },
        )
    }

    val target = ctlTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { ctlTarget = null },
            title = { Text("控制 " + target.name) },
            text = {
                Column {
                    Text("输入控制参数（等价于 kpatch kpm ctl0）", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ctlArgs,
                        onValueChange = { ctlArgs = it },
                        singleLine = true,
                        label = { Text("参数") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = target.name
                    val args = ctlArgs.trim()
                    ctlTarget = null
                    if (args.isEmpty()) {
                        message = "参数为空，已取消"
                    } else {
                        scope.launch {
                            val result = repo.ctl0(name, args)
                            message = if (result.first) {
                                name + " -> " + result.second.ifEmpty { "已发送" }
                            } else {
                                "控制失败：" + result.second
                            }
                            refresh()
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { ctlTarget = null }) { Text("取消") }
            },
        )
    }
    }
}