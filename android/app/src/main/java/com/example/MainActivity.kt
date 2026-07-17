package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.MapNotification
import com.example.data.NavigationStateCache
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as MapSnarkApplication).repository)
    }

    private val isPermissionGrantedState = mutableStateOf(false)
    private val isBatteryOptimizationIgnoredState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) { // Enforce dark theme as requested
                Scaffold(
                     modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold"),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    containerColor = Color(0xFF1C1B1F) // Deep M3 dark color
                ) { innerPadding ->
                    MapSnarkScreen(
                        viewModel = viewModel,
                        isPermissionGranted = isPermissionGrantedState.value,
                        onGrantPermissionClick = { launchNotificationAccessSettings() },
                        isBatteryOptimizationIgnored = isBatteryOptimizationIgnoredState.value,
                        onGrantBatteryPermissionClick = { requestIgnoreBatteryOptimizations() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the permission status when user returns to the app
        val enabled = isNotificationServiceEnabled(this)
        isPermissionGrantedState.value = enabled
        isBatteryOptimizationIgnoredState.value = isBatteryOptimizationIgnored(this)
        if (enabled) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(this, com.example.service.MapNotificationService::class.java)
                )
                Log.d("MapSnarkLog", "Requested rebind for MapNotificationService to ensure background stability")
            } catch (e: Exception) {
                Log.e("MapSnarkLog", "Failed to request rebind: ${e.message}")
            }
        }
    }

    private fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MapSnarkLog", "Failed to launch direct battery opt request: ${e.message}", e)
            try {
                // Fallback to settings screen
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (ex: Exception) {
                Log.e("MapSnarkLog", "Failed to launch battery settings fallback: ${ex.message}", ex)
            }
        }
    }

    private fun launchNotificationAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to general settings if listener settings fail to open
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun isNotificationServiceEnabled(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.packageName)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}

@Composable
fun MapSnarkScreen(
    viewModel: MainViewModel,
    isPermissionGranted: Boolean,
    onGrantPermissionClick: () -> Unit,
    isBatteryOptimizationIgnored: Boolean,
    onGrantBatteryPermissionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val latestNotification = notifications.firstOrNull()
    val latestState by NavigationStateCache.latestState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C1B1F))
    ) {
        // Material 3 Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2B2930))
                    .clickable { onGrantPermissionClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Map,
                    contentDescription = "Nark Icon",
                    tint = Color(0xFFD0BCFF),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Nark Map Controller",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    fontSize = 22.sp
                ),
                color = Color(0xFFE6E1E5)
            )
        }

        // Main layout container: Scrollable list of cards and live feed
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Permission Status Card
            item {
                PermissionStatusCard(
                    isGranted = isPermissionGranted,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Prominent Action Button (Only show if permission not granted)
            if (!isPermissionGranted) {
                item {
                    Button(
                        onClick = onGrantPermissionClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD0BCFF),
                            contentColor = Color(0xFF381E72)
                        ),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("grant_access_button"),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Grant Notification Access",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "Right Arrow",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Battery Optimization Status Card
            item {
                BatteryOptimizationStatusCard(
                    isIgnored = isBatteryOptimizationIgnored,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Prominent Action Button for Battery Optimization (Only show if not ignoring battery optimization)
            if (!isBatteryOptimizationIgnored) {
                item {
                    Button(
                        onClick = onGrantBatteryPermissionClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD0BCFF),
                            contentColor = Color(0xFF381E72)
                        ),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("grant_battery_button"),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Grant Unrestricted Battery Access",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "Right Arrow",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Server Status Card
            item {
                ServerStatusCard(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Live debug layout displaying the hosted raw JSON payload
            item {
                JsonDebugCard(
                    latestState = latestState,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Live Parsed Telemetry Card
            if (latestNotification != null) {
                item {
                    TelemetryCard(
                        notification = latestNotification,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Live Feed Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "LIVE FEED (MAPSNARKLOG)",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                fontSize = 11.sp
                            ),
                            color = Color(0xFFCAC4D0)
                        )

                        // Pulsating Live indicator
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulse_alpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFB4AB).copy(alpha = alpha))
                        )
                    }

                    if (notifications.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearNotifications() },
                            modifier = Modifier.testTag("clear_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Clear logs",
                                tint = Color(0xFFF9DEDC),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Scrollable container for live feed logs
            if (notifications.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0xFF2B2930))
                            .padding(24.dp)
                    ) {
                        EmptyFeedState()
                    }
                }
            } else {
                items(
                    items = notifications,
                    key = { it.id }
                ) { item ->
                    NotificationItemRow(item = item)
                }
            }
        }

        // M3 Bottom Indicator Bar
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(128.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            )
        }
    }
}

@Composable
fun ServerStatusCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("server_status_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1B2330)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = Color(0xFF64B5F6).copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "server_pulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "server_scale"
                )
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF81C784).copy(alpha = scale))
                )

                Text(
                    text = "Server Status: Running",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = Color(0xFFE2E8F0)
                )
            }
            Text(
                text = "Hosting latest GPS navigation variables on Port 3000. Endpoint at /getlatestmaps is active and ready for Zepp OS companion app requests.",
                style = MaterialTheme.typography.bodySmall.copy(
                    lineHeight = 16.sp
                ),
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
fun JsonDebugCard(
    latestState: NavigationStateCache.NavigationState?,
    modifier: Modifier = Modifier
) {
    val jsonString = if (latestState != null) {
        """
        {
          "distance": "${latestState.distance}",
          "street": "${latestState.street}",
          "baseStructure": "${latestState.baseStructure}",
          "action": "${latestState.action}",
          "icon": "${latestState.icon}"
        }
        """.trimIndent()
    } else {
        """
        {
          "distance": "",
          "street": "",
          "baseStructure": "",
          "action": "",
          "icon": ""
        }
        """.trimIndent()
    }

    Card(
        modifier = modifier.testTag("json_debug_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF121214)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = Color(0xFFD0BCFF).copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "HOSTED API PAYLOAD (/getlatestmaps)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 11.sp
                    ),
                    color = Color(0xFFD0BCFF)
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF381E72))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "GET - JSON",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE2D6FF)
                        )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E24))
                    .padding(12.dp)
            ) {
                Text(
                    text = jsonString,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    ),
                    color = Color(0xFF81C784)
                )
            }
        }
    }
}


@Composable
fun PermissionStatusCard(
    isGranted: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("status_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFF142B1F) else Color(0xFF31111D)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isGranted) Color(0xFF10B981) else Color(0xFF8C1D18)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isGranted) Color(0xFFD1FAE5) else Color(0xFFF9DEDC)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isGranted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        contentDescription = if (isGranted) "Permission Active" else "Permission Inactive",
                        tint = if (isGranted) Color(0xFF065F46) else Color(0xFF410002),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isGranted) "Access Granted" else "Access Required",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = if (isGranted) Color(0xFFD1FAE5) else Color(0xFFF9DEDC)
                )
            }

            Text(
                text = if (isGranted) {
                    "The system notification listener is currently active and bound. Map Snark is fully ready to capture and log live Google Maps turn-by-turn navigation."
                } else {
                    "The system notification listener is currently disabled. To log navigation snarks, the app needs permission to read Maps alerts."
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 20.sp,
                    fontSize = 14.sp
                ),
                color = (if (isGranted) Color(0xFFD1FAE5) else Color(0xFFF9DEDC)).copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
fun BatteryOptimizationStatusCard(
    isIgnored: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("battery_status_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isIgnored) Color(0xFF142B1F) else Color(0xFF31111D)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isIgnored) Color(0xFF10B981) else Color(0xFF8C1D18)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isIgnored) Color(0xFFD1FAE5) else Color(0xFFF9DEDC)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isIgnored) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        contentDescription = if (isIgnored) "Battery Active" else "Battery Restricted",
                        tint = if (isIgnored) Color(0xFF065F46) else Color(0xFF410002),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isIgnored) "Unrestricted Battery" else "Battery Restricted",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = if (isIgnored) Color(0xFFD1FAE5) else Color(0xFFF9DEDC)
                )
            }

            Text(
                text = if (isIgnored) {
                    "Unrestricted battery usage is enabled. The system will allow Nark to maintain connections and update navigation data in the background even with the screen off."
                } else {
                    "Battery saver/optimization is active. To prevent the system from terminating Nark when the screen turns off, please grant unrestricted battery permission."
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 20.sp,
                    fontSize = 14.sp
                ),
                color = (if (isIgnored) Color(0xFFD1FAE5) else Color(0xFFF9DEDC)).copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
fun TelemetryCard(
    notification: MapNotification,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("telemetry_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2B2930)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = Color(0xFFD0BCFF).copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Navigation,
                        contentDescription = "Active parsed values",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Active Navigation Telemetry",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFE6E1E5)
                    )
                }

                // Badge showing Synced state
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFD1FAE5))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Z_OS SYNCED",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF065F46)
                        )
                    )
                }
            }

            // Clean grid layout of parsed parameters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryItem(
                    label = "DISTANCE",
                    value = notification.distance.ifEmpty { "0 m" },
                    modifier = Modifier.weight(1.1f)
                )
                TelemetryItem(
                    label = "ACTION",
                    value = notification.action.replace("_", " ").uppercase(Locale.getDefault()).ifEmpty { "STRAIGHT" },
                    modifier = Modifier.weight(0.9f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryItem(
                    label = "STRUCTURE",
                    value = notification.baseStructure.uppercase(Locale.getDefault()).ifEmpty { "STRAIGHT" },
                    modifier = Modifier.weight(0.9f)
                )
                TelemetryItem(
                    label = "STREET",
                    value = notification.street.ifEmpty { "Main Route" },
                    modifier = Modifier.weight(1.1f)
                )
            }
        }
    }
}

@Composable
fun TelemetryItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1B1F))
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = Color(0xFF938F99)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = Color(0xFFE6E1E5),
                maxLines = 1
            )
        }
    }
}

@Composable
fun NotificationItemRow(
    item: MapNotification,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1B1F))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left indicator line matching the M3 Bold style
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFD0BCFF))
            )

            Text(
                text = formatTimestamp(item.timestamp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = Color(0xFF938F99),
                modifier = Modifier.padding(top = 2.dp)
            )

            Text(
                text = "\"${item.text}\"",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = Color(0xFFE6E1E5),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun EmptyFeedState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Notifications,
            contentDescription = "No notifications icon",
            tint = Color(0xFFCAC4D0).copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Maps notifications intercepted yet",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFFCAC4D0),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Make sure notification permission is granted, then start turn-by-turn navigation in Google Maps to capture live data feed.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCAC4D0).copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return format.format(date)
}
