package com.example.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicReference

class NavDataServer(hostname: String = "127.0.0.1", port: Int = 3000) : NanoHTTPD(hostname, port) {

    data class NavData(
        val distance: String?,
        val street: String?,
        val baseStructure: String?,
        val action: String?,
        val icon: String?,
        val phrase: String?,
        val arrivalTime: String?
    )

    companion object {
        private val latestNavData = AtomicReference<NavData?>(null)
        @Volatile
        private var isNotificationDismissed = false

        fun updateNavData(
            distance: String?,
            street: String?,
            baseStructure: String?,
            action: String?,
            icon: String?,
            phrase: String?,
            arrivalTime: String?
        ) {
            isNotificationDismissed = false
            latestNavData.set(NavData(
                distance, street, baseStructure, action, icon, phrase, arrivalTime
            ))
            Log.d("NavDataServer", "In-memory navigation data updated: distance='$distance', street='$street', phrase='$phrase', ETA='$arrivalTime'")
        }

        fun clearNavData() {
            isNotificationDismissed = true
            latestNavData.set(null)
            Log.d("NavDataServer", "In-memory navigation data explicitly cleared/dismissed")
        }
    }

    override fun serve(session: IHTTPSession?): Response {
        val uri = session?.uri ?: "/"
        val method = session?.method

        if (method == Method.GET && uri == "/getlatestmaps") {
            val json = when {
                isNotificationDismissed || latestNavData.get() == null -> {
                    """
                    {
                      "distance": null,
                      "street": null,
                      "baseStructure": null,
                      "action": null,
                      "icon": null,
                      "phrase": null,
                      "arrivalTime": null
                    }
                    """.trimIndent()
                }
                else -> {
                    val data = latestNavData.get()!!
                    """
                    {
                      "distance": ${formatJsonValue(data.distance)},
                      "street": ${formatJsonValue(data.street)},
                      "baseStructure": ${formatJsonValue(data.baseStructure)},
                      "action": ${formatJsonValue(data.action)},
                      "icon": ${formatJsonValue(data.icon)},
                      "phrase": ${formatJsonValue(data.phrase)},
                      "arrivalTime": ${formatJsonValue(data.arrivalTime)}
                    }
                    """.trimIndent()
                }
            }

            val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json)
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        val errorResponse = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        errorResponse.addHeader("Access-Control-Allow-Origin", "*")
        return errorResponse
    }

    private fun formatJsonValue(value: String?): String {
        if (value == null) return "null"
        return "\"${escapeJson(value)}\""
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
