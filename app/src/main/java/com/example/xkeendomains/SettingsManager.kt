package com.example.xkeendomains

import android.content.Context
import android.content.SharedPreferences

data class SshCredentials(val host: String, val port: Int, val user: String, val pass: String, val configPath: String)

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)

    fun save(credentials: SshCredentials) {
        prefs.edit().apply {
            putString("host", credentials.host)
            putInt("port", credentials.port)
            putString("user", credentials.user)
            putString("pass", credentials.pass) // Note: In a real app, encrypt this!
            putString("configPath", credentials.configPath)
            apply()
        }
    }

    fun load(): SshCredentials {
        return SshCredentials(
            host = prefs.getString("host", "192.168.1.1")!!,
            port = prefs.getInt("port", 222),
            user = prefs.getString("user", "root")!!,
            pass = prefs.getString("pass", "")!!,
            configPath = prefs.getString("configPath", "/opt/etc/xray/configs/05_routing.json")!!
        )
    }

    fun saveTheme(isDark: Boolean) {
        prefs.edit().putBoolean("is_dark_theme", isDark).apply()
    }

    fun loadTheme(): Boolean {
        return prefs.getBoolean("is_dark_theme", true) // Default to dark theme
    }
}