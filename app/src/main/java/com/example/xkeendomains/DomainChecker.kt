package com.example.xkeendomains

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

interface DomainChecker {
    suspend fun checkDomains(domains: List<String>): Map<String, Boolean>
}

class DomainCheckerImpl : DomainChecker {
    override suspend fun checkDomains(domains: List<String>): Map<String, Boolean> {
        return withContext(Dispatchers.IO) {
            domains.associateWith { domain ->
                try {
                    // 1. DNS resolution check
                    InetAddress.getByName(domain)

                    // 2. HTTP(S) endpoint testing
                    isReachable(domain)
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun isReachable(domain: String): Boolean {
        // Try HTTPS first
        if (checkUrl("https://$domain")) {
            return true
        }
        // Fallback to HTTP
        return checkUrl("http://$domain")
    }

    private fun checkUrl(urlString: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000 // 5 seconds
            connection.readTimeout = 5000
            connection.connect()

            val code = connection.responseCode
            // We consider any valid HTTP response code as success
            code in 200..399 
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}