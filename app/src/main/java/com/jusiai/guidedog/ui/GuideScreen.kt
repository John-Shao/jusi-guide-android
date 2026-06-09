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

    var cameraGranted by remember { mutableStateOf(hasCamera(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: hasCamera(context)
        if (cameraGranted) vm.start()
    }

    val navPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val ok = (result[Manifest.permission.RECORD_AUDIO] ?: hasPerm(context, Manifest.permission.RECORD_AUDIO)) &&
            (result[Manifest.permission.ACCESS_FINE_LOCATION] ?: hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION))
        if (ok) vm.setDestinationByVoice()
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

            // ---- 步行导航：建议先用语音设好目的地（此时无视觉播报/开麦干扰），再点「开始」----
            if (nav.navigating) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(nav.phase.ifEmpty { "导航中" }, style = MaterialTheme.typography.titleMedium)
                    if (nav.destName.isNotBlank()) {
                        Text("目的地：${nav.destName}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (nav.remainingDist >= 0) {
                        Text(
                            "剩余 ${nav.remainingDist} 米，约 ${nav.remainingTime / 60} 分钟",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (nav.nextRoad.isNotBlank()) {
                        Text(
                            "下一段：${nav.nextRoad}" +
                                if (nav.nextTurnDist >= 0) "（${nav.nextTurnDist} 米）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    nav.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(
                    onClick = { vm.stopNav() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .semantics { contentDescription = "结束导航" },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("结束导航", style = MaterialTheme.typography.titleLarge) }
            } else {
                Button(
                    onClick = {
                        if (hasNavPerms(context)) vm.setDestinationByVoice()
                        else navPermLauncher.launch(navPermissions())
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .semantics { contentDescription = "语音设置目的地" },
                ) { Text("语音设置目的地", style = MaterialTheme.typography.titleLarge) }
                if (nav.phase.isNotBlank()) {
                    Text(
                        if (nav.phase == "已设目的地" && nav.destName.isNotBlank()) {
                            "已设目的地：${nav.destName}（点击开始导航）"
                        } else {
                            nav.phase
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                nav.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = {
                    when {
                        running -> vm.stop()
                        cameraGranted -> vm.start()
                        else -> permLauncher.launch(neededPermissions())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .semantics { contentDescription = if (running) "停止导盲犬" else "开始导盲犬" },
                colors = if (running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (running) "停止" else "开始", style = MaterialTheme.typography.headlineMedium)
            }
            if (!cameraGranted) {
                Text(
                    "需要相机权限才能开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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

private fun hasCamera(c: Context): Boolean = hasPerm(c, Manifest.permission.CAMERA)

private fun hasNavPerms(c: Context): Boolean =
    hasPerm(c, Manifest.permission.RECORD_AUDIO) && hasPerm(c, Manifest.permission.ACCESS_FINE_LOCATION)

private fun neededPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.CAMERA)
    }

private fun navPermissions(): Array<String> = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)
