package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.MapSnarkApplication
import com.example.data.MapNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

class MapNotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var navDataServer: com.example.server.NavDataServer? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        try {
            synchronized(this) {
                val wl = wakeLock
                if (wl != null && !wl.isHeld) {
                    wl.acquire()
                    Log.d("MapNotificationService", "WakeLock acquired: Navigation is actively running")
                }
            }
        } catch (e: Exception) {
            Log.e("MapNotificationService", "Error acquiring WakeLock: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            synchronized(this) {
                val wl = wakeLock
                if (wl != null && wl.isHeld) {
                    wl.release()
                    Log.d("MapNotificationService", "WakeLock released: Navigation is inactive or ended")
                }
            }
        } catch (e: Exception) {
            Log.e("MapNotificationService", "Error releasing WakeLock: ${e.message}", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MapNotificationService", "onCreate: Initializing offline loopback NavDataServer and starting foreground")
        startForegroundServiceWithNotification()

        // Initialize partial wake lock for battery efficiency, do not acquire yet
        try {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "MapNotificationService::WakeLock"
            ).apply {
                setReferenceCounted(false)
            }
            Log.d("MapNotificationService", "WakeLock initialized in onCreate (not yet acquired)")
        } catch (e: Exception) {
            Log.e("MapNotificationService", "Failed to initialize WakeLock: ${e.message}", e)
        }

        navDataServer = com.example.server.NavDataServer("127.0.0.1", 3000)
        serviceScope.launch(Dispatchers.IO) {
            try {
                navDataServer?.start()
                Log.d("MapNotificationService", "Embedded offline loopback NavDataServer started on 127.0.0.1:3000")
            } catch (e: Exception) {
                Log.e("MapNotificationService", "Failed to start NavDataServer: ${e.message}", e)
            }
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceWithNotification()
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "map_navigation_service_channel"
        val channelName = "Map Navigation Listener"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps navigation listener and loopback server running backgrounded"
            }
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }.apply {
            setContentTitle("Maps Companion Active")
            setContentText("Listening to navigation updates offline")
            setSmallIcon(android.R.drawable.ic_menu_compass)
            setOngoing(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setCategory(android.app.Notification.CATEGORY_SERVICE)
            }
        }.build()

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    1001,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(1001, notification)
            }
            Log.d("MapNotificationService", "startForeground called with DATA_SYNC type successfully")
        } catch (e: Exception) {
            Log.e("MapNotificationService", "Failed to call startForeground: ${e.message}", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName == "com.google.android.apps.maps") {
            Log.d("MapSnarkLog", "=== Intercepted com.google.android.apps.maps notification ===")
            
            val isOngoing = sbn.isOngoing
            val category = sbn.notification.category
            Log.d("MapSnarkLog", "SBN Meta - ID: ${sbn.id}, Tag: ${sbn.tag}, Ongoing: $isOngoing, Category: $category")

            val extras = sbn.notification.extras
            if (extras == null) {
                Log.d("MapSnarkLog", "Extras bundle is null! Cannot parse.")
                return
            }

            // Dump all keys and values for debugging
            Log.d("MapSnarkLog", "--- Notification Extras Dump Start ---")
            for (key in extras.keySet()) {
                try {
                    val value = extras.get(key)
                    Log.d("MapSnarkLog", "Extra Key: '$key' | Value: '$value' | Type: ${value?.javaClass?.name ?: "null"}")
                } catch (e: Exception) {
                    Log.e("MapSnarkLog", "Error reading extra key '$key': ${e.message}")
                }
            }
            Log.d("MapSnarkLog", "--- Notification Extras Dump End ---")

            // Retrieve all potentially relevant textual fields
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

            Log.d("MapSnarkLog", "Extracted Fields - Title: '$title' | Text: '$text' | SubText: '$subText' | BigText: '$bigText'")

            // Combined check for active navigation
            val combinedText = "$title $text $bigText".lowercase(Locale.getDefault())
            
            // Refine the filter: navigation is ongoing OR has navigation-specific keywords
            val hasNavKeywords = combinedText.contains("toward") || 
                                 combinedText.contains("onto") || 
                                 combinedText.contains("turn") || 
                                 combinedText.contains("head") || 
                                 combinedText.contains("keep") || 
                                 combinedText.contains("exit") || 
                                 combinedText.contains("merge") ||
                                 combinedText.contains("split") ||
                                 combinedText.contains("flyover") ||
                                 combinedText.contains("roundabout")
                                 
            // Filter out system or background alerts
            val isLocationSharing = combinedText.contains("sharing your location") || combinedText.contains("sharing location")
            val isOfflineMaps = combinedText.contains("offline maps") || combinedText.contains("offline map")
            val isBackgroundAlert = combinedText.contains("running in the background") || combinedText.contains("gps signal lost")

            if (!isOngoing && !hasNavKeywords) {
                Log.d("MapSnarkLog", "Skipping notification: Not marked ongoing and contains no navigation keywords.")
                releaseWakeLock()
                return
            }
            if (isLocationSharing || isOfflineMaps || isBackgroundAlert) {
                Log.d("MapSnarkLog", "Skipping notification: Identified as location-sharing, offline-map, or background alert.")
                releaseWakeLock()
                return
            }

            Log.d("MapSnarkLog", "Notification validated as active Google Maps turn-by-turn navigation update!")
            acquireWakeLock()

            // Parse navigation telemetry
            val parsedData = parseTelemetry(title, text, bigText)
            Log.d("MapSnarkLog", "PARSED DATA SUCCESS -> Distance: '${parsedData.distance}' | Street: '${parsedData.street}' | Structure: '${parsedData.baseStructure}' | Action: '${parsedData.action}' | Icon: '${parsedData.icon}'")

            // Prepare display string for list logs (representing what was intercepted)
            val logText = if (title.isNotBlank() && text.isNotBlank()) {
                "$title: $text"
            } else if (text.isNotBlank()) {
                text
            } else {
                title
            }

            // Save to thread-safe memory cache for local HTTP server
            com.example.data.NavigationStateCache.update(
                com.example.data.NavigationStateCache.NavigationState(
                    distance = parsedData.distance,
                    street = parsedData.street,
                    baseStructure = parsedData.baseStructure,
                    action = parsedData.action,
                    icon = parsedData.icon,
                    text = logText,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Persist the intercepted text and parsed fields into local Room database
            val app = application as? MapSnarkApplication
            if (app != null) {
                serviceScope.launch {
                    app.repository.insert(
                        MapNotification(
                            text = logText,
                            packageName = packageName,
                            distance = parsedData.distance,
                            street = parsedData.street,
                            baseStructure = parsedData.baseStructure,
                            action = parsedData.action,
                            icon = parsedData.icon
                        )
                    )
                }
            }

            val (_, _, arrivalTime) = parseSubText(subText)

            // Construct full human-readable phrase based on mapped actions/icons and street
            val phrase = constructPhrase(parsedData.action, parsedData.icon, parsedData.street)

            // Directly update the offline loopback NavDataServer thread-safe variable
            com.example.server.NavDataServer.updateNavData(
                distance = parsedData.distance,
                street = parsedData.street,
                baseStructure = parsedData.baseStructure,
                action = parsedData.action,
                icon = parsedData.icon,
                phrase = phrase,
                arrivalTime = arrivalTime
            )
        }
    }

    /**
     * Parses the turn-by-turn instruction fields into structured telemetry
     */
    private fun parseTelemetry(title: String, text: String, bigText: String): ParsedTelemetry {
        // 1. Extract Distance
        val distance = extractDistance(title, text, bigText)

        // 2. Extract Street
        val street = extractStreet(title, text, bigText, distance)

        // 3. Extract Base Structure: "flyover", "intersection", "fork", or "straight"
        val combinedLower = "$title $text $bigText".lowercase(Locale.getDefault())
        val baseStructure = when {
            combinedLower.contains("flyover") || combinedLower.contains("overpass") || combinedLower.contains("bridge") -> "flyover"
            combinedLower.contains("intersection") || combinedLower.contains("roundabout") || combinedLower.contains("circle") || combinedLower.contains("junction") -> "intersection"
            combinedLower.contains("fork") || combinedLower.contains("exit") || combinedLower.contains("merge") || combinedLower.contains("split") -> "fork"
            else -> "straight"
        }

        // 4. Extract Action: "left_turn", "right_turn", "keep_left", "keep_right", "u_turn", or "straight"
        val action = when {
            combinedLower.contains("keep left") || combinedLower.contains("bear left") -> "keep_left"
            combinedLower.contains("keep right") || combinedLower.contains("bear right") -> "keep_right"
            combinedLower.contains("u-turn") || combinedLower.contains("uturn") || combinedLower.contains("u turn") -> "u_turn"
            combinedLower.contains("left") -> "left_turn"
            combinedLower.contains("right") -> "right_turn"
            else -> "straight"
        }

        // 5. Map exact visual directions to one of the 16 explicit string tokens
        val icon = mapIcon(title, text, bigText)

        return ParsedTelemetry(distance, street, baseStructure, action, icon)
    }

    private fun mapIcon(title: String, text: String, bigText: String): String {
        val combinedLower = "$title $text $bigText".lowercase(Locale.getDefault())

        return when {
            // Arriving at destination / end of route
            combinedLower.contains("arrived") || 
            combinedLower.contains("arriving") || 
            combinedLower.contains("destination") || 
            combinedLower.contains("reached") -> "destination"

            // U-turns
            combinedLower.contains("u-turn") || 
            combinedLower.contains("uturn") || 
            combinedLower.contains("u turn") -> {
                if (combinedLower.contains("right")) "u-turn-right" else "u-turn-left"
            }

            // Roundabouts (Specific exits / straight / turns)
            combinedLower.contains("roundabout") || 
            combinedLower.contains("circle") || 
            combinedLower.contains("junction") -> {
                when {
                    combinedLower.contains("exit") && combinedLower.contains("left") -> "roundabout-exit-left"
                    combinedLower.contains("exit") && combinedLower.contains("right") -> "roundabout-exit-right"
                    combinedLower.contains("exit") && (combinedLower.contains("1st") || combinedLower.contains("first")) -> "roundabout-exit-right"
                    combinedLower.contains("exit") && (combinedLower.contains("3rd") || combinedLower.contains("third")) -> "roundabout-exit-left"
                    combinedLower.contains("straight") || combinedLower.contains("2nd exit") || combinedLower.contains("second exit") -> "roundabout-straight"
                    combinedLower.contains("left") -> "roundabout-left"
                    combinedLower.contains("right") -> "roundabout-right"
                    else -> "roundabout-straight"
                }
            }

            // Merge lanes
            combinedLower.contains("merge") || combinedLower.contains("join") -> "merge"

            // Take the left or right fork
            combinedLower.contains("fork") -> {
                if (combinedLower.contains("left")) "fork-left" else "fork-right"
            }

            // Slight left / Slight right
            combinedLower.contains("slight left") -> "slight-left"
            combinedLower.contains("slight right") -> "slight-right"
            
            // Bear left / keep left
            combinedLower.contains("bear left") || combinedLower.contains("keep left") -> "slight-left"
            
            // Bear right / keep right
            combinedLower.contains("bear right") || combinedLower.contains("keep right") -> "slight-right"

            // Standard left / right turns
            combinedLower.contains("turn left") || 
            combinedLower.contains("left turn") || 
            combinedLower.contains("take a left") || 
            combinedLower.contains("then left") -> "turn-left"

            combinedLower.contains("turn right") || 
            combinedLower.contains("right turn") || 
            combinedLower.contains("take a right") || 
            combinedLower.contains("then right") -> "turn-right"

            // Simple directional fallbacks
            combinedLower.contains("left") -> "turn-left"
            combinedLower.contains("right") -> "turn-right"

            // Continue straight, keep straight, stay on road
            combinedLower.contains("straight") || 
            combinedLower.contains("keep straight") || 
            combinedLower.contains("continue") || 
            combinedLower.contains("head") || 
            combinedLower.contains("stay on") || 
            combinedLower.contains("stay") -> "straight"

            // Default fallback
            else -> "straight"
        }
    }

    private fun extractDistance(title: String, text: String, bigText: String): String {
        val distanceRegex = """(?i)\b(\d+(?:[.,]\d+)?\s*(?:m|km|ft|mi|miles|meters|kilometers|yards|yd))\b""".toRegex()
        
        // Search in title, then text, then bigText
        for (field in listOf(title, text, bigText)) {
            val match = distanceRegex.find(field)
            if (match != null) {
                return match.value.trim()
            }
        }
        
        // Fallback for "now" or relative maneuvers
        val combinedLower = "$title $text $bigText".lowercase(Locale.getDefault())
        if (combinedLower.contains("now") || combinedLower.contains("immediately")) {
            return "Now"
        }
        
        return "0 m"
    }

    private fun extractStreet(title: String, text: String, bigText: String, distance: String): String {
        // Look for bullet points "•" first, very common in "Distance • Street" titles
        for (field in listOf(title, text, bigText)) {
            if (field.contains("•")) {
                val parts = field.split("•")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed != distance && trimmed.isNotBlank() && !trimmed.contains("maps", ignoreCase = true)) {
                        val cleaned = cleanStreetString(trimmed)
                        if (cleaned.isNotEmpty()) return cleaned
                    }
                }
            }
        }

        // Look for typical keyword boundaries
        val candidates = listOf(bigText, text, title).filter { it.isNotBlank() }
        for (candidate in candidates) {
            val lowercase = candidate.lowercase(Locale.getDefault())
            var extracted: String? = null
            when {
                lowercase.contains("toward") -> {
                    extracted = candidate.substringAfterLast("toward", "").trim()
                }
                lowercase.contains("onto") -> {
                    extracted = candidate.substringAfterLast("onto", "").trim()
                }
                lowercase.contains("to stay on") -> {
                    extracted = candidate.substringAfterLast("to stay on", "").trim()
                }
                lowercase.contains("on") -> {
                    val onIndex = lowercase.lastIndexOf(" on ")
                    if (onIndex != -1) {
                        extracted = candidate.substring(onIndex + 4).trim()
                    }
                }
            }

            if (extracted != null && extracted.isNotBlank()) {
                val cleaned = cleanStreetString(extracted)
                if (cleaned.isNotEmpty() && cleaned != "left" && cleaned != "right") {
                    return cleaned
                }
            }
        }

        // If no keyword matches, check for comma-split fallbacks
        for (candidate in candidates) {
            if (candidate.contains(",")) {
                val parts = candidate.split(",")
                val lastPart = parts.last().trim()
                val cleaned = cleanStreetString(lastPart)
                if (cleaned.isNotEmpty() && cleaned != distance) {
                    return cleaned
                }
            }
        }

        // Final fallback: return whichever field isn't the distance or blank
        for (candidate in candidates) {
            val cleaned = cleanStreetString(candidate)
            if (cleaned != distance && cleaned.isNotEmpty() && !cleaned.contains("maps", ignoreCase = true)) {
                return cleaned
            }
        }

        return "Main Route"
    }

    private fun cleanStreetString(input: String): String {
        var str = input.trim()
        
        // Remove surrounding quotes/apostrophes
        str = str.removeSurrounding("\"").removeSurrounding("'").trim()
        
        // Remove trailing punctuation
        while (str.endsWith(".") || str.endsWith(",")) {
            if (str.length <= 1) return ""
            str = str.substring(0, str.length - 1).trim()
        }
        
        // Strip out the distance if it was included in this portion
        val distanceRegex = """(?i)\b(\d+(?:[.,]\d+)?\s*(?:m|km|ft|mi|miles|meters|kilometers|yards|yd))\b""".toRegex()
        str = distanceRegex.replace(str, "").trim()
        
        // Strip leading/trailing separators
        while (str.startsWith(",") || str.startsWith("•") || str.startsWith("-") || str.startsWith("/")) {
            if (str.length <= 1) return ""
            str = str.substring(1).trim()
        }
        while (str.endsWith(",") || str.endsWith("•") || str.endsWith("-") || str.endsWith("/")) {
            if (str.length <= 1) return ""
            str = str.substring(0, str.length - 1).trim()
        }
        
        return str.trim()
    }

    override fun onDestroy() {
        Log.d("MapNotificationService", "onDestroy: Cleaning up service")
        releaseWakeLock()
        try {
            navDataServer?.stop()
            Log.d("MapNotificationService", "NavDataServer stopped successfully in onDestroy")
        } catch (e: Exception) {
            Log.e("MapNotificationService", "Error stopping NavDataServer in onDestroy: ${e.message}")
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun parseSubText(subText: String): Triple<String, String, String> {
        if (subText.isBlank()) return Triple("--", "--", "--")

        var timeRem = "--"
        var distRem = "--"
        var arrTime = "--"

        // Time remaining: e.g. "12 min" or "1 hr 12 min" or "1 h 12 m"
        val timeRegex = """(?i)\b\d+\s*(?:hr|h|hour|hours)?\s*\d+\s*(?:min|m|minute|minutes)\b""".toRegex()
        val timeMatch = timeRegex.find(subText)
        if (timeMatch != null) {
            timeRem = timeMatch.value.trim()
        }

        // Distance remaining: e.g. "3.4 km", "2.1 mi"
        val distRegex = """(?i)\b\d+(?:[.,]\d+)?\s*(?:km|mi|miles|kilometers)\b""".toRegex()
        val distMatch = distRegex.find(subText)
        if (distMatch != null) {
            distRem = distMatch.value.trim()
        } else {
            // Fallback for smaller units like m or ft
            val fallbackDistRegex = """(?i)\b\d+(?:[.,]\d+)?\s*(?:m|ft|yards|yd)\b""".toRegex()
            val fallbackMatch = fallbackDistRegex.find(subText)
            if (fallbackMatch != null && fallbackMatch.value.trim() != timeRem) {
                distRem = fallbackMatch.value.trim()
            }
        }

        // Arrival time clock time: e.g. "9:45 PM" or "21:45"
        val arrivalRegex = """(?i)\b\d{1,2}:\d{2}\s*(?:AM|PM|am|pm)?\b""".toRegex()
        val arrivalMatch = arrivalRegex.find(subText)
        if (arrivalMatch != null) {
            arrTime = formatTo24Hour(arrivalMatch.value.trim())
        }

        // Fallback for arrivalTime if we have timeRemaining but no explicit clock time
        if (arrTime == "--" && timeRem != "--") {
            try {
                val hrRegex = """(?i)(\d+)\s*(?:hr|h)""".toRegex()
                val minRegex = """(?i)(\d+)\s*(?:min|m)""".toRegex()
                val hrs = hrRegex.find(timeRem)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val mins = minRegex.find(timeRem)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val totalMinutes = hrs * 60 + mins
                if (totalMinutes > 0) {
                    val totalSeconds = totalMinutes * 60L
                    val calendar = java.util.Calendar.getInstance()
                    calendar.add(java.util.Calendar.SECOND, totalSeconds.toInt())
                    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    arrTime = sdf.format(calendar.time)
                }
            } catch (e: Exception) {
                Log.e("MapSnarkLog", "Error estimating arrivalTime: ${e.message}")
            }
        }

        return Triple(timeRem, distRem, arrTime)
    }

    private fun formatTo24Hour(timeStr: String): String {
        if (timeStr == "--" || timeStr.isBlank()) return "--"
        val trimmed = timeStr.trim()
        val formats = listOf(
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.US),
            java.text.SimpleDateFormat("h:mma", java.util.Locale.US),
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US),
            java.text.SimpleDateFormat("H:mm", java.util.Locale.US)
        )
        for (format in formats) {
            try {
                format.isLenient = true
                val date = format.parse(trimmed)
                if (date != null) {
                    val outFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    return outFormat.format(date)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return "--"
    }

    private fun constructPhrase(action: String, icon: String, street: String): String {
        val trimmedStreet = street.trim()
        
        // Compass direction handling: "Head [compass direction]"
        val compassRegex = """(?i)^Head\s+(north|northeast|east|southeast|south|southwest|west|northwest)$""".toRegex()
        val matchResult = compassRegex.matchEntire(trimmedStreet)
        if (matchResult != null) {
            val direction = matchResult.groupValues[1]
            val capitalizedDirection = direction.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
            return "Head $capitalizedDirection"
        }

        val cleanStreet = stripHtml(trimmedStreet).ifBlank { "the road" }

        val normalizedKey = when (action.lowercase().trim()) {
            "straight", "continue" -> "straight"
            "turn-left", "turn_left", "left_turn" -> "turn-left"
            "turn-right", "turn_right", "right_turn" -> "turn-right"
            "slight-left", "slight_left", "keep_left" -> "slight-left"
            "slight-right", "slight_right", "keep_right" -> "slight-right"
            "fork-left", "fork_left" -> "fork-left"
            "fork-right", "fork_right" -> "fork-right"
            "u-turn-left", "u-turn-right", "u_turn" -> "u-turn"
            "roundabout-right", "roundabout-left" -> "roundabout"
            "destination", "arrive" -> "destination"
            "merge" -> "merge"
            else -> {
                when (icon.lowercase().trim()) {
                    "straight", "continue" -> "straight"
                    "turn-left", "turn_left" -> "turn-left"
                    "turn-right", "turn_right" -> "turn-right"
                    "slight-left", "slight_left" -> "slight-left"
                    "slight-right", "slight_right" -> "slight-right"
                    "fork-left", "fork_left" -> "fork-left"
                    "fork-right", "fork_right" -> "fork-right"
                    "u-turn-left", "u-turn-right" -> "u-turn"
                    "roundabout-right", "roundabout-left" -> "roundabout"
                    "destination", "arrive" -> "destination"
                    "merge" -> "merge"
                    else -> "fallback"
                }
            }
        }

        return when (normalizedKey) {
            "straight" -> "Continue on $cleanStreet"
            "turn-left" -> "Turn left onto $cleanStreet"
            "turn-right" -> "Turn right onto $cleanStreet"
            "slight-left" -> "Bear left onto $cleanStreet"
            "slight-right" -> "Bear right onto $cleanStreet"
            "fork-left" -> "Keep left toward $cleanStreet"
            "fork-right" -> "Keep right toward $cleanStreet"
            "u-turn" -> "Make a U-turn"
            "roundabout" -> "Enter the roundabout"
            "destination" -> "You have arrived at your destination"
            "merge" -> "Merge onto $cleanStreet"
            else -> "Head toward $cleanStreet"
        }
    }

    private fun stripHtml(html: String): String {
        return html.replace("""<[^>]*>""".toRegex(), "").trim()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName == "com.google.android.apps.maps") {
            Log.d("MapSnarkLog", "Google Maps notification dismissed/removed. Resetting navigation variables to return empty JSON object {}")
            com.example.data.NavigationStateCache.clear()
            com.example.server.NavDataServer.clearNavData()
            releaseWakeLock()
        }
    }

    private data class ParsedTelemetry(
        val distance: String,
        val street: String,
        val baseStructure: String,
        val action: String,
        val icon: String
    )
}
