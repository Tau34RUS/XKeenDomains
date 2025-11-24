package com.example.xkeendomains

import android.content.Context
import com.google.gson.GsonBuilder
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

// --- Data classes for JSON structure ---
data class XrayConfig(val routing: Routing)
data class Routing(var rules: MutableList<Rule> = mutableListOf())
data class Rule(
    var type: String = "",
    var outboundTag: String? = null,
    var inboundTag: List<String>? = null,
    var domain: MutableList<String>? = null,
    var network: String? = null,
    var port: String? = null,
    var ip: List<String>? = null
)

interface SshManager {
    suspend fun verifyConnectionAndConfig(): Result<Unit>
    suspend fun getDomains(): Result<List<String>>
    suspend fun addDomains(domains: List<String>): Result<Unit>
    suspend fun removeDomains(domains: List<String>): Result<Unit>
    suspend fun restartXkeen(logCallback: (String) -> Unit): Result<Unit>
}

class SshManagerImpl(context: Context) : SshManager {
    private val settingsManager = SettingsManager(context)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    override suspend fun verifyConnectionAndConfig(): Result<Unit> = runSshOperation { session ->
        val configPath = settingsManager.load().configPath
        // Use a simple, non-throwing command to check for file existence
        val checkCommand = "if [ -f \"$configPath\" ]; then echo 'exists'; else echo 'not_found'; fi"
        val result = executeLogCommand(session, checkCommand)
        if (!result.contains("exists")) {
            throw Exception("Config file not found at: $configPath")
        }
    }.map { Unit }

    override suspend fun getDomains(): Result<List<String>> = runSshOperation { session ->
        val configPath = settingsManager.load().configPath
        val fileContent = executeStrictCommand(session, "cat $configPath")
        val config = gson.fromJson(fileContent, XrayConfig::class.java)
        config.routing.rules.find { it.outboundTag == "vless-reality" }?.domain ?: emptyList()
    }

    override suspend fun addDomains(domains: List<String>): Result<Unit> = updateDomains { currentDomains ->
        val newDomains = domains.filter { d -> d.isNotBlank() && !currentDomains.contains(d) }
        currentDomains.addAll(newDomains)
    }

    override suspend fun removeDomains(domains: List<String>): Result<Unit> = updateDomains { currentDomains ->
        currentDomains.removeAll(domains.toSet())
    }

    override suspend fun restartXkeen(logCallback: (String) -> Unit): Result<Unit> = runSshOperation { session ->
        logCallback("Executing: xkeen -restart")
        executeLogCommand(session, "xkeen -restart") // Initial command
        
        var isRestarting = true
        val maxRetries = 15
        var retries = 0

        while (isRestarting && retries < maxRetries) {
            delay(1000)
            retries++
            logCallback("Checking status ($retries/$maxRetries)...")
            val psOutput = executeLogCommand(session, "ps | grep xkeen")
            
            if (psOutput.contains("xkeen -restart")) {
                logCallback("XKeen is still restarting...")
            } else {
                isRestarting = false
            }
        }

        if (isRestarting) throw Exception("Restart process timed out after $maxRetries seconds.")

        logCallback("Restart process finished. Checking final status...")
        val statusOutput = executeLogCommand(session, "xkeen -status")
        if (!statusOutput.contains("запущен")) {
            throw Exception("XKeen final status check failed. Full log:\n$statusOutput")
        }
        logCallback("Final status check successful.")
    }.map { Unit }

    private suspend fun updateDomains(modifier: (MutableList<String>) -> Unit): Result<Unit> = runSshOperation { session ->
        val configPath = settingsManager.load().configPath
        executeStrictCommand(session, "cp $configPath ${configPath}.bk")
        val fileContent = executeStrictCommand(session, "cat $configPath")
        val config = gson.fromJson(fileContent, XrayConfig::class.java)
        val ruleToUpdate = config.routing.rules.find { it.outboundTag == "vless-reality" } ?: throw Exception("Rule not found")
        if (ruleToUpdate.domain == null) ruleToUpdate.domain = mutableListOf()
        modifier(ruleToUpdate.domain!!)
        val updatedJson = gson.toJson(config)
        val writeCommand = "echo '" + updatedJson.replace("'", "'\\''") + "' > " + configPath
        executeStrictCommand(session, writeCommand)
    }.map { Unit }

    private suspend fun <T> runSshOperation(operation: suspend (Session) -> T): Result<T> = withContext(Dispatchers.IO) {
        val creds = settingsManager.load()
        if (!isSshReachable(creds.host, creds.port, 2000)) {
            return@withContext Result.failure(Exception("SSH server not reachable"))
        }

        var session: Session? = null
        try {
            val jsch = JSch()
            session = jsch.getSession(creds.user, creds.host, creds.port)
            session.setPassword(creds.pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(30000)
            Result.success(operation(session))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            session?.disconnect()
        }
    }

    private suspend fun isSshReachable(host: String, port: Int, timeout: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeout); true }
        } catch (e: Exception) { false }
    }
    
    private fun executeLogCommand(session: Session, command: String): String {
        var channel: com.jcraft.jsch.ChannelExec? = null
        try {
            channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(command)
            val outputStream = ByteArrayOutputStream()
            val errorStream = ByteArrayOutputStream()
            channel.setOutputStream(outputStream)
            channel.setErrStream(errorStream)
            channel.connect(15000)

            while (channel.isConnected) { Thread.sleep(100) }

            val output = outputStream.toString("UTF-8")
            val error = errorStream.toString("UTF-8")

            return buildString {
                if (output.isNotBlank()) append(output)
                if (error.isNotBlank()) {
                    if (isNotBlank()) append("\n\n")
                    append("--- STDERR ---\n$error")
                }
            }.ifEmpty { "Command executed, but produced no output." }
        } finally {
            channel?.disconnect()
        }
    }

    private fun executeStrictCommand(session: Session, command: String): String {
        var channel: com.jcraft.jsch.ChannelExec? = null
        try {
            channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand(command)
            val outputStream = ByteArrayOutputStream()
            val errorStream = ByteArrayOutputStream()
            channel.setOutputStream(outputStream)
            channel.setErrStream(errorStream)
            channel.connect(15000)

            while (channel.isConnected) { Thread.sleep(100) }

            val error = errorStream.toString("UTF-8")
            if (error.isNotEmpty()) {
                throw Exception("SSH command failed with error: $error")
            }
            return outputStream.toString("UTF-8")
        } finally {
            channel?.disconnect()
        }
    }
}