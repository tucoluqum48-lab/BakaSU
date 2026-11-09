package org.bakasu.bakasu.ui.screen.susfs.subpages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.bakasu.bakasu.data.susfs.SuSFSConfig
import org.bakasu.bakasu.ui.screen.susfs.RegisterSuSFSRefresh
import org.bakasu.bakasu.ui.screen.susfs.SuSFSRefreshRegistrar
import org.bakasu.bakasu.ui.util.LocalSnackbarHost
import org.bakasu.bakasu.ui.util.showReplacingSnackbar
import org.bakasu.bakasu.ui.viewmodel.SuSFSCommandReply
import org.bakasu.bakasu.ui.viewmodel.SuSFSUiAction
import org.bakasu.bakasu.ui.viewmodel.SuSFSViewModel
import org.bakasu.bakasu.ui.viewmodel.awaitSuSFSBoolean
import org.koin.compose.viewmodel.koinViewModel

/**
 * 总览页：参考 FinaSU 的 SUSFS 交互，把高频操作集中在一屏。
 * 状态 + 快捷开关 + uname 伪装，其余细节仍走原有 Tab。
 */
@Composable
fun DashboardTab(
    nestedScrollConnection: NestedScrollConnection,
    innerPadding: PaddingValues,
    onRegisterRefresh: SuSFSRefreshRegistrar,
) {
    val vm = koinViewModel<SuSFSViewModel>()
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf<SuSFSConfig?>(null) }
    var unameRelease by remember { mutableStateOf("") }
    var unameVersion by remember { mutableStateOf("") }
    var unameLoaded by remember { mutableStateOf(false) }

    RegisterSuSFSRefresh(onRegisterRefresh) { cfg, _ ->
        config = cfg
        if (!unameLoaded) {
            unameRelease = cfg.uname.release
            unameVersion = cfg.uname.version
            unameLoaded = true
        }
    }

    fun runBool(fail: String, action: (SuSFSCommandReply) -> SuSFSUiAction) {
        scope.launch {
            if (!awaitSuSFSBoolean(vm, action)) {
                snackbar.showReplacingSnackbar(fail)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
    ) {
        item { Spacer(Modifier.height(innerPadding.calculateTopPadding())) }

        item {
            SectionCard(title = "运行状态") {
                InfoRow("SUSFS", if (config?.enabled == true) "已启用" else "未启用")
                InfoRow("配置版本", (config?.version ?: 0).toString())
                InfoRow("路径隐藏", (config?.sus_path?.size ?: 0).toString() + " 条")
                InfoRow("映射隐藏", (config?.sus_map?.size ?: 0).toString() + " 条")
                InfoRow("KSTAT", (config?.sus_kstat?.size ?: 0).toString() + " 条")
                InfoRow("重定向", (config?.open_redirect?.size ?: 0).toString() + " 条")
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        item {
            SectionCard(title = "快捷开关") {
                SwitchRow(
                    title = "启用 SUSFS",
                    checked = config?.enabled == true,
                ) { v -> runBool("切换失败") { r -> SuSFSUiAction.SetEnabled(v, r) } }

                SwitchRow(
                    title = "内核日志",
                    checked = config?.logging == true,
                ) { v -> runBool("设置失败") { r -> SuSFSUiAction.EnableLog(v, r) } }

                SwitchRow(
                    title = "AVC 日志欺骗",
                    checked = config?.avc_log_spoofing == true,
                ) { v -> runBool("设置失败") { r -> SuSFSUiAction.EnableAvcLogSpoofing(v, r) } }

                SwitchRow(
                    title = "对非 su 隐藏挂载",
                    checked = config?.hide_sus_mnts_for_non_su_procs == true,
                ) { v -> runBool("设置失败") { r -> SuSFSUiAction.HideSusMnts(v, r) } }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        item {
            SectionCard(title = "内核版本伪装（uname）") {
                OutlinedTextField(
                    value = unameRelease,
                    onValueChange = { unameRelease = it },
                    label = { Text("release") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = unameVersion,
                    onValueChange = { unameVersion = it },
                    label = { Text("version") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            val ok = awaitSuSFSBoolean(vm) { r ->
                                SuSFSUiAction.SetUname(unameRelease, unameVersion, r)
                            }
                            snackbar.showReplacingSnackbar(if (ok) "已应用" else "应用失败")
                        }
                    }) { Text("应用") }

                    TextButton(onClick = {
                        unameRelease = "default"
                        unameVersion = "default"
                        scope.launch {
                            val ok = awaitSuSFSBoolean(vm) { r ->
                                SuSFSUiAction.SetUname("default", "default", r)
                            }
                            snackbar.showReplacingSnackbar(if (ok) "已恢复默认" else "恢复失败")
                        }
                    }) { Text("恢复默认") }
                }
                Text(
                    text = "当前配置 release=" + (config?.uname?.release ?: "-") +
                        "  version=" + (config?.uname?.version ?: "-"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(innerPadding.calculateBottomPadding() + 16.dp)) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 13.sp)
        Text(text = value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider()
}
