package com.jusiai.guidedog.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(vm: GuideViewModel) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val nav by vm.navStatus.collectAsStateWithLifecycle()

    // 单按钮需要的全部权限：相机(视觉) + 麦克风(语音设目的地) + 定位(导航)。
    val startPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (hasStartPerms(context)) vm.startGuided()
    }

    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导盲犬") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = preview
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "摄像头预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        if (status.running) "正在获取画面…" else "未开始",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = status.text.ifEmpty {
                        if (status.running) "观察中…" else "点击下方按钮开始"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                val dbg = buildString {
                    if (status.mad >= 0) append("mad=${status.mad}  ")
                    if (status.lastCycleMs > 0) append("cycle=${status.lastCycleMs}ms  ")
                    if (status.vlmMs >= 0) append("vlm=${status.vlmMs}ms")
                }
                if (dbg.isNotBlank()) {
                    Text(
                        dbg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                status.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            val running = status.running
            val busy = nav.busy

            // 导航中：显示目的地 / 剩余 / 下一段
            if (running && nav.navigating) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (nav.destName.isNotBlank()) {
                        Text("目的地：${nav.destName}", style = MaterialTheme.typography.titleMedium)
                    }
                    if (nav.remainingDist >= 0) {
                        Text(
                            "剩余 ${nav.remainingDist} 米，约 ${nav.remainingTime / 60} 分钟",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (nav.nextRoad.isNotBlank()) {
                        Text(
                            "下一段：${nav.nextRoad}" +
                                if (nav.nextTurnDist >= 0) "（${nav.nextTurnDist} 米）" else "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            // 语音设置阶段提示
            if (busy && nav.phase.isNotBlank()) {
                Text(nav.phase, style = MaterialTheme.typography.titleMedium)
            }
            nav.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }

            // 唯一大按钮：开始 / 取消 / 停止
            val label = when { running -> "停止"; busy -> "取消"; else -> "开始" }
            Button(
                onClick = {
                    when {
                        running -> vm.stop()
                        busy -> vm.cancelGuided()
                        hasStartPerms(context) -> vm.startGuided()
                        else -> startPermLauncher.launch(startPermissions())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .semantics {
                        contentDescription = when {
                            running -> "停止"
                            busy -> "取消"
                            else -> "开始，语音设置目的地并导航"
                        }
                    },
                colors = if (running || busy) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(label, style = MaterialTheme.typography.displaySmall)
            }
        }
    }

    if (showSettings) {
        SettingsSheet(vm, onDismiss = { showSettings = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(vm: GuideViewModel, onDismiss: () -> Unit) {
    val s = vm.settings
    var relayUrl by remember { mutableStateOf(s.relayUrl) }
    var token by remember { mutableStateOf(s.deviceToken) }
    var lang by remember { mutableStateOf(s.lang) }
    var audio by remember { mutableStateOf(s.wantAudio) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                label = { Text("中转地址 relay_url") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("设备 token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("语言")
                FilterChip(selected = lang == "zh", onClick = { lang = "zh" }, label = { Text("中文") })
                FilterChip(selected = lang == "en", onClick = { lang = "en" }, label = { Text("English") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语音播报", modifier = Modifier.weight(1f))
                Switch(checked = audio, onCheckedChange = { audio = it })
            }
            Button(
                onClick = {
                    s.relayUrl = relayUrl
                    s.deviceToken = token
                    s.lang = lang
                    s.wantAudio = audio
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
            Text(
                "提示：修改后请「停止」再「开始」以确保完全生效。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun hasPerm(c: Context, p: String): Boolean =
    ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

/** 单按钮全流程所需：相机(视觉) + 麦克风(语音) + 定位(导航)。 */
private fun hasStartPerms(c: Context): Boolean =
    hasPerm(c, Manifest.permission.CAMERA) &&
        hasPerm(c, Manifest.permission.RECORD_AUDIO) &&
        hasPerm(c, Manifest.permission.ACCESS_FINE_LOCATION)

private fun startPermissions(): Array<String> {
    val perms = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return perms.toTypedArray()
}
