package com.example.xkeendomains

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.xkeendomains.ui.theme.LogError
import com.example.xkeendomains.ui.theme.LogInfo
import com.example.xkeendomains.ui.theme.LogSuccess
import com.example.xkeendomains.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogType { INFO, SUCCESS, ERROR }
data class LogEntry(val message: String, val type: LogType, val timestamp: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val settingsManager = remember { SettingsManager(context) }
            var isDarkTheme by remember { mutableStateOf(settingsManager.loadTheme()) }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                val navController = rememberNavController()
                val sshManager: SshManager = remember { SshManagerImpl(context) }

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            navController = navController, 
                            sshManager = sshManager,
                            isDarkTheme = isDarkTheme,
                            onThemeChange = { 
                                isDarkTheme = !isDarkTheme
                                settingsManager.saveTheme(isDarkTheme)
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(navController = navController, settingsManager = settingsManager)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    navController: NavController,
    isDarkTheme: Boolean,
    onThemeChange: () -> Unit,
    showSettingsButton: Boolean = false,
    showThemeChanger: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (navController.previousBackStackEntry != null) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (showThemeChanger) {
                        IconButton(onClick = onThemeChange) {
                            Icon(if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "Toggle Theme")
                        }
                    }
                    if (showSettingsButton) {
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        },
        content = content
    )
}

@Composable
fun MainScreen(navController: NavController, sshManager: SshManager, isDarkTheme: Boolean, onThemeChange: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var logEntries by remember { mutableStateOf(listOf<LogEntry>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showManageDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun log(message: String, type: LogType) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logEntries = logEntries + LogEntry(message, type, timestamp)
    }

    fun verifyConnection() {
        coroutineScope.launch {
            log("Verifying connection and config file...", LogType.INFO)
            sshManager.verifyConnectionAndConfig()
                .onSuccess { log("SSH host and config file are available.", LogType.SUCCESS) }
                .onFailure { log("Verification failed: ${it.message}", LogType.ERROR) }
        }
    }
    
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { verifyConnection() } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(logEntries) { listState.animateScrollToItem(if (logEntries.isNotEmpty()) logEntries.size - 1 else 0) }

    AppScaffold(title = "XKeen Domains", navController = navController, isDarkTheme = isDarkTheme, onThemeChange = onThemeChange, showSettingsButton = true, showThemeChanger = true) {
        Column(modifier = Modifier.padding(it).padding(16.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            
            ActionTile(icon = Icons.Default.Add, title = "Add Domains", subtitle = "Add new domains to the list", onClick = { showAddDialog = true })
            Spacer(modifier = Modifier.height(12.dp))
            ActionTile(icon = Icons.Default.List, title = "Manage Domains", subtitle = "View or delete existing domains", onClick = { showManageDialog = true })
            Spacer(modifier = Modifier.height(12.dp))
            ActionTile(icon = Icons.Default.Refresh, title = "Restart XKeen", subtitle = "Apply changes by restarting the service", onClick = {
                coroutineScope.launch {
                    log("Restarting XKeen...", LogType.INFO)
                    sshManager.restartXkeen { msg -> log(msg, LogType.INFO) }
                        .onSuccess { log("XKeen restarted successfully.", LogType.SUCCESS) }
                        .onFailure { log("Error restarting: ${it.message}", LogType.ERROR) }
                }
            })

            Spacer(modifier = Modifier.height(16.dp))

            // Log Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, Color.Gray, RoundedCornerShape(4.dp))) {
                LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                    items(logEntries) { entry ->
                        val color = when (entry.type) {
                            LogType.INFO -> LogInfo
                            LogType.SUCCESS -> LogSuccess
                            LogType.ERROR -> LogError
                        }
                        Text(text = "[${entry.timestamp}] ${entry.message}", color = color, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showAddDialog) { AddDomainsDialog(onDismiss = { showAddDialog = false }, onAdd = { domains -> coroutineScope.launch { log("Adding domains: ${domains.joinToString()}...", LogType.INFO); sshManager.addDomains(domains).onSuccess { log("Domains added successfully! Restart XKeen to apply.", LogType.SUCCESS) }.onFailure { log("Error adding domains: ${it.message}", LogType.ERROR) } }; showAddDialog = false }) }
    if (showManageDialog) { ManageDomainsDialog(sshManager = sshManager, onDismiss = { showManageDialog = false }, onDelete = { domains -> coroutineScope.launch { if (domains.isNotEmpty()) { log("Deleting domains: ${domains.joinToString()}...", LogType.INFO); sshManager.removeDomains(domains).onSuccess { log("Domains deleted successfully! Restart XKeen to apply.", LogType.SUCCESS) }.onFailure { log("Error deleting domains: ${it.message}", LogType.ERROR) } } }; showManageDialog = false }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = title, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
    }
}

@Composable
fun SettingsScreen(navController: NavController, settingsManager: SettingsManager) {
    var creds by remember { mutableStateOf(settingsManager.load()) }
    AppScaffold(title = "Settings", navController = navController, isDarkTheme = false, onThemeChange = {}) {
        Column(modifier = Modifier.padding(it).padding(16.dp)) {
            OutlinedTextField(value = creds.host, onValueChange = { creds = creds.copy(host = it) }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = creds.port.toString(), onValueChange = { creds = creds.copy(port = it.toIntOrNull() ?: 0) }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = creds.user, onValueChange = { creds = creds.copy(user = it) }, label = { Text("User") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = creds.pass, onValueChange = { creds = creds.copy(pass = it) }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = creds.configPath, onValueChange = { creds = creds.copy(configPath = it) }, label = { Text("Config File Path") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { settingsManager.save(creds); navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}

@Composable
fun AddDomainsDialog(onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var domainsText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Domains") },
        text = { TextField(value = domainsText, onValueChange = { domainsText = it }, label = { Text("Comma-separated domains") }) },
        confirmButton = { Button(onClick = { onAdd(domainsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }) }) { Text("Add") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ManageDomainsDialog(sshManager: SshManager, onDismiss: () -> Unit, onDelete: (List<String>) -> Unit) {
    var domainList by remember { mutableStateOf<List<String>?>(null) }
    var selectedDomains by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        sshManager.getDomains().onSuccess { domainList = it.sorted() }.onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Domains") },
        text = {
            Box(modifier = Modifier.fillMaxHeight(0.7f)) {
                when {
                    error != null -> Text("Error: $error")
                    domainList == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    domainList!!.isEmpty() -> Text("No domains found.")
                    else -> {
                        LazyColumn {
                            items(domainList!!) { domain ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                                    selectedDomains = if (selectedDomains.contains(domain)) selectedDomains - domain else selectedDomains + domain
                                }) {
                                    Checkbox(checked = selectedDomains.contains(domain), onCheckedChange = { isChecked ->
                                        selectedDomains = if (isChecked) selectedDomains + domain else selectedDomains - domain
                                    })
                                    Text(domain, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onDelete(selectedDomains.toList()) }, enabled = selectedDomains.isNotEmpty()) { Text("Delete Selected") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}
