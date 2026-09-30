package com.l1ratch.wsbridge

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.l1ratch.wsbridge.tunnel.TunnelService
import kotlinx.coroutines.delay

/// Главный экран: молния-кнопка (порт ContentView.swift) + меню.
class MainActivity : ComponentActivity() {

    private val startTunnel = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Consent получен (или уже был) — запускаем сервис.
        launchTunnel()
    }
    private fun launchTunnel() {
        val intent = Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopTunnel() {
        startService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelManager.init(this)
        setContent { App(onToggle = ::toggle) }
    }

    private fun toggle() {
        if (TunnelManager.running) {
            stopTunnel()
            return
        }
        // VpnService.prepare: null = согласие уже есть, иначе системный диалог.
        val consent = VpnService.prepare(this)
        if (consent == null) launchTunnel() else startTunnel.launch(consent)
    }
}

private val Green = Color(0xFF17A05E)
private val DarkBg = Color(0xFF0D1526)
private val LightBg = Color(0xFFE8EDF5)

@Composable
fun App(onToggle: () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(background = DarkBg, surface = DarkBg)
        else lightColorScheme(background = LightBg, surface = LightBg)
    ) {
        var screen by remember { mutableStateOf<Any>("main") }
        when (screen) {
            "main" -> MainScreen(onToggle = onToggle, navigate = { screen = it })
            "dnsManage" -> DnsManageScreen(navigate = { screen = it })
            "worker" -> WorkerScreen(navigate = { screen = it })
            "journal" -> JournalScreen(navigate = { screen = it })
            "about" -> AboutScreen(navigate = { screen = it })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onToggle: () -> Unit, navigate: (String) -> Unit) {
    val running = TunnelManager.running
    var showMenu by remember { mutableStateOf(false) }
    var showDns by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth()) {
                // Меню якоряется на коробке вокруг самой кнопки, а не на всём topBar:
                // иначе popup считает якорём полноширинный Box и открывается слева.
                Box(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Журнал туннеля") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { showMenu = false; navigate("journal") },
                        )
                        DropdownMenuItem(
                            text = { Text("DNS-серверы") },
                            leadingIcon = { Icon(Icons.Default.NetworkWifi, null) },
                            onClick = { showMenu = false; navigate("dnsManage") },
                        )
                        DropdownMenuItem(
                            text = { Text("CF Worker") },
                            leadingIcon = { Icon(Icons.Default.Cloud, null) },
                            onClick = { showMenu = false; navigate("worker") },
                        )
                        DropdownMenuItem(
                            text = { Text("О программе") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { showMenu = false; navigate("about") },
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Молния — и индикатор, и кнопка: тап включает/выключает.
            Box(
                modifier = Modifier
                    .size(220.dp)
                    // Фон ДО тени: иначе shadow() рисует ореол вокруг пустой
                    // коробки — читается как «непонятная обводка», а не как кнопка.
                    .background(
                        if (running) Green.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        CircleShape
                    )
                    .shadow(if (running) 40.dp else 8.dp, CircleShape,
                        ambientColor = if (running) Green else Color.Black,
                        spotColor = if (running) Green else Color.Black)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = if (running) "Выключить туннель" else "Включить туннель",
                    tint = if (running) Green else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(140.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            // DNS-строка: название выбранного DNS + точка-индикатор.
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable { showDns = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.NetworkWifi, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(TunnelManager.selectedDNS.name, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (TunnelManager.selectedDNS.servers.isEmpty()) Color.Gray else Green,
                            CircleShape
                        )
                )
            }

            Spacer(Modifier.height(48.dp))

            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · © 2026 l1ratch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }

    if (showDns) DnsSheet(onDismiss = { showDns = false }, navigate = navigate)
}

/// Быстрый выбор DNS (лист поверх главного экрана).
@Composable
private fun DnsSheet(onDismiss: () -> Unit, navigate: (String) -> Unit) {
    var selected by remember { mutableStateOf(TunnelManager.selectedDNSId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("DNS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Изменения применятся при следующем включении туннеля.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (config in TunnelManager.allDNS) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = config.id; TunnelManager.selectedDNSId = config.id }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(config.name)
                            Text(
                                config.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        if (selected == config.id) {
                            Icon(Icons.Default.CheckCircle, null, tint = Green)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { onDismiss(); navigate("dnsManage") }) {
                    Text("Настройки DNS")
                }
            }
        },
    )
}

/// Управление DNS: пресеты (только просмотр) + свои (добавить/удалить).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsManageScreen(navigate: (String) -> Unit) {
    var editing by remember { mutableStateOf<TunnelManager.DNSConfig?>(null) }
    var custom by remember { mutableStateOf(TunnelManager.customDNS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки DNS") },
                navigationIcon = {
                    IconButton(onClick = { navigate("main") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Пресеты", style = MaterialTheme.typography.titleMedium)
            for (config in TunnelManager.dnsPresets) {
                DnsRow(config, onClick = null)
            }
            Spacer(Modifier.height(16.dp))
            Text("Свои серверы", style = MaterialTheme.typography.titleMedium)
            for (config in custom) {
                DnsRow(config, onClick = { editing = config })
                TextButton(onClick = {
                    custom = custom.filter { it.id != config.id }
                    TunnelManager.customDNS = custom
                    if (TunnelManager.selectedDNSId == config.id) TunnelManager.selectedDNSId = "system"
                }) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(4.dp))
                    Text("Удалить ${config.name}", color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedButton(onClick = { editing = TunnelManager.DNSConfig("", "", "", emptyList(), false) }) {
                Text("Добавить DNS")
            }
        }
    }

    editing?.let { config ->
        DnsEditorDialog(
            initial = config.takeIf { it.id.isNotEmpty() },
            onDismiss = { editing = null },
            onSave = { name, desc, servers ->
                val entry = TunnelManager.DNSConfig(
                    id = if (config.id.isNotEmpty()) config.id else java.util.UUID.randomUUID().toString(),
                    name = name, description = desc, servers = servers, isPreset = false,
                )
                custom = if (config.id.isNotEmpty()) custom.map { if (it.id == entry.id) entry else it }
                else custom + entry
                TunnelManager.customDNS = custom
                TunnelManager.selectedDNSId = entry.id
                editing = null
            },
        )
    }
}

@Composable
private fun DnsRow(config: TunnelManager.DNSConfig, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(config.name)
            Text(
                if (config.servers.isEmpty()) config.description
                else config.description + " · " + config.servers.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        if (TunnelManager.selectedDNSId == config.id) {
            Icon(Icons.Default.CheckCircle, null, tint = Green)
        } else if (config.isPreset) {
            // выбор пресета прямо из списка управления — одно касание
            TextButton(onClick = { TunnelManager.selectedDNSId = config.id }) { Text("Выбрать") }
        }
    }
}

@Composable
private fun DnsEditorDialog(
    initial: TunnelManager.DNSConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, servers: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var servers by remember { mutableStateOf(initial?.servers?.toMutableList() ?: mutableListOf("")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name.trim(), description.trim(), servers.map { it.trim() }.filter { it.isNotEmpty() })
                },
                enabled = name.isNotBlank() && servers.any { it.isNotBlank() },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text(if (initial == null) "Новый DNS" else "Настройки DNS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Описание") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Серверы (IP)", style = MaterialTheme.typography.labelLarge)
                servers.forEachIndexed { i, server ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            server,
                            { newValue ->
                                servers = servers.toMutableList().also { it[i] = newValue }
                            },
                            label = { Text("IP-адрес") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        if (servers.size > 1) {
                            IconButton(onClick = {
                                servers = servers.filterIndexed { idx, _ -> idx != i }.toMutableList()
                            }) {
                                Icon(Icons.Default.Delete, "Удалить сервер")
                            }
                        }
                    }
                }
                TextButton(onClick = { servers = (servers + "").toMutableList() }) { Text("Добавить сервер") }
                Text(
                    "Запросы идут plain UDP:53 из туннеля. DoH/DoT на Android не поддерживаются — при необходимости используйте системный private DNS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
    )
}

/// CF Worker: настройка собственного worker'а или реле (порт WorkerView).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkerScreen(navigate: (String) -> Unit) {
    var worker by remember { mutableStateOf(TunnelManager.workerDomain) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CF Worker") },
                navigationIcon = {
                    IconButton(onClick = { navigate("main") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            OutlinedTextField(
                worker,
                { worker = it },
                label = { Text("worker.example.com или ip:port") },
                singleLine = true,
                enabled = !TunnelManager.running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                TunnelManager.workerDomain = worker.trim()
                navigate("main")
            }) { Text("Сохранить") }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    worker.isBlank() -> "Пусто = kws-фронты Cloudflare (основной режим)."
                    worker.contains(":") -> "Прямой режим: сырой TCP на реле host:port, без CF."
                    else -> "Pipe-режим через свой CF worker. Перезапусти туннель, чтобы применилось."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/// Журнал: моноширинный текст + обновить/скопировать (порт JournalView).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalScreen(navigate: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        while (true) {
            text = TunnelManager.journal()
            loading = false
            delay(2000) // живой журнал: туннель в том же процессе, обновляем на месте
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал") },
                navigationIcon = {
                    IconButton(onClick = { navigate("main") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { loading = true; text = TunnelManager.journal(); loading = false }) {
                        Icon(Icons.Default.Refresh, "Обновить")
                    }
                    IconButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    }) {
                        Icon(Icons.Default.ContentCopy, "Скопировать")
                    }
                },
            )
        }
    ) { padding ->
        if (loading && text.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

/// О программе (порт AboutView).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(navigate: (String) -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("О программе") },
                navigationIcon = {
                    IconButton(onClick = { navigate("main") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Bolt, null, tint = Green, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(8.dp))
            Text("WSBridge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(24.dp))
            Text("Что это", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "WSBridge — VPN-туннель, работающий локально на вашем устройстве. Он перехватывает трафик Telegram и перенаправляет его через WebSocket-соединение к серверам Telegram в обход блокировок. Настройка в самом Telegram не требуется.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Text("Разработчик", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LinkRow("Автор: l1ratch", null)
            LinkRow("Исходный код WSBridge-Android") {
                openUrl(context, "https://github.com/l1ratch/WSBridge-Android")
            }
            LinkRow("Оригинал: tg-ws-proxy (Flowseal)") {
                openUrl(context, "https://github.com/Flowseal/tg-ws-proxy")
            }
            LinkRow("Версия для iOS") {
                openUrl(context, "https://github.com/l1ratch/WSBridge-iOS")
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, onClick: (() -> Unit)?) {
    Text(
        title,
        color = if (onClick != null) Green else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}
