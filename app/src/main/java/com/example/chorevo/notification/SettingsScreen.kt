package com.example.chorevo.notification

import com.example.chorevo.ui.theme.PillShape
import com.example.chorevo.ui.theme.ChorevoTheme
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.outlined.DarkMode
import com.example.chorevo.core.ViewModel.ThemeMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.chorevo.core.ViewModel.AppViewModel
import com.example.chorevo.firebase.UserProfile
import java.util.Calendar
import java.util.Locale

/** Updates tings screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    profile: UserProfile,
    onBack: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by appViewModel.uiState.collectAsStateWithLifecycle()

    val brandTeal = ChorevoTheme.colors.accent
    val screenBg = MaterialTheme.colorScheme.surface
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    val displayName =
        uiState.profile?.displayName?.ifBlank { "User" }
            ?: profile.displayName?.ifBlank { "User" }
            ?: "User"

    val email =
        uiState.profile?.email?.ifBlank { "-" }
            ?: profile.email?.ifBlank { "-" }
            ?: "-"

    val savedPhotoUrl = uiState.profile?.photoUrl ?: profile.photoUrl

    val pushEnabled = uiState.pushEnabled
    val selectedHour = uiState.notificationHour
    val selectedMinute = uiState.notificationMinute

    var showTimePicker by remember { mutableStateOf(false) }
    var localPreviewUri by remember { mutableStateOf<Uri?>(null) }

    DisposableEffect(context, pushEnabled) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val systemNotificationsEnabled =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()

                if (!systemNotificationsEnabled && pushEnabled) {
                    appViewModel.setPushEnabled(false)
                }
            }
        }

        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.profile?.photoUrl) {
        if (!uiState.profile?.photoUrl.isNullOrBlank()) {
            localPreviewUri = null
        }
    }

    LaunchedEffect(uiState.profilePhotoUploadError) {
        uiState.profilePhotoUploadError?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            appViewModel.clearProfilePhotoUploadError()
        }
    }

    val profileGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            localPreviewUri = uri
            appViewModel.uploadProfilePhoto(uri)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            appViewModel.setPushEnabled(true)
            Toast.makeText(context, "Notifications enabled", Toast.LENGTH_SHORT).show()
        } else {
            appViewModel.setPushEnabled(false)
            openAppNotificationSettings(context)
        }
    }

    Scaffold(
        containerColor = screenBg,
        topBar = {
            SettingsTopBar(
                onBack = onBack,
                brandTeal = brandTeal
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            ProfileHeader(
                displayName = displayName,
                email = email,
                profileImageModel = localPreviewUri ?: savedPhotoUrl,
                isUploading = uiState.isUploadingProfilePhoto,
                onEditClick = { profileGalleryLauncher.launch("image/*") },
                brandTeal = brandTeal
            )

            Spacer(modifier = Modifier.height(24.dp))

            AppearanceCard(
                selected = uiState.themeMode,
                onSelect = appViewModel::setThemeMode,
                cardColor = cardBg
            )

            Spacer(modifier = Modifier.height(16.dp))

            NotificationCard(
                pushEnabled = pushEnabled,
                reminderTime = formatTime(selectedHour, selectedMinute),
                onToggleNotifications = { enabled ->
                    handleNotificationToggle(
                        context = context,
                        enabled = enabled,
                        currentPushEnabled = pushEnabled,
                        setPushEnabled = appViewModel::setPushEnabled,
                        permissionLauncher = {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                },
                onTimeClick = {
                    if (pushEnabled) {
                        showTimePicker = true
                    }
                },
                brandTeal = brandTeal,
                cardColor = cardBg
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = brandTeal,
                    contentColor = ChorevoTheme.colors.onAccent
                ),
                shape = PillShape
            ) {
                Text(
                    text = "Sign Out",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (showTimePicker) {
        NotificationTimePickerDialog(
            initialHour = selectedHour,
            initialMinute = selectedMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                appViewModel.setNotificationTime(hour, minute)
                showTimePicker = false
            }
        )
    }
}

/** Dark / Light / System choice for the app's colours. */
@Composable
private fun AppearanceCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    cardColor: Color
) {
    val colors = ChorevoTheme.colors
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("appearanceCard"),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.DarkMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Appearance",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PillShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(4.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    val isSelected = mode == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(PillShape)
                            .background(if (isSelected) colors.accent else Color.Transparent)
                            .clickable { onSelect(mode) }
                            .padding(vertical = 10.dp)
                            .testTag("themeMode_${mode.key}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) colors.onAccent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Updates tings top bar. */
@Composable
private fun SettingsTopBar(
    onBack: () -> Unit,
    brandTeal: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = "Settings",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/** Handles profile header. */
@Composable
private fun ProfileHeader(
    displayName: String,
    email: String,
    profileImageModel: Any?,
    isUploading: Boolean,
    onEditClick: () -> Unit,
    brandTeal: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            if (profileImageModel != null) {
                AsyncImage(
                    model = profileImageModel,
                    contentDescription = "Profile picture",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(brandTeal),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayName.take(2).uppercase(Locale.getDefault()),
                        color = ChorevoTheme.colors.onAccent,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (isUploading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit profile picture",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .clickable(onClick = onEditClick)
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
    }
}

/** Handles notification card. */
@Composable
private fun NotificationCard(
    pushEnabled: Boolean,
    reminderTime: String,
    onToggleNotifications: (Boolean) -> Unit,
    onTimeClick: () -> Unit,
    brandTeal: Color,
    cardColor: Color
) {
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Notifications",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = "Get notified about reminders and updates",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            SettingToggle(
                label = "Push Notifications",
                checked = pushEnabled,
                onCheckedChange = onToggleNotifications,
                brandTeal = brandTeal
            )

            Spacer(modifier = Modifier.height(12.dp))

            NotificationTimeRow(
                enabled = pushEnabled,
                timeText = reminderTime,
                onClick = onTimeClick
            )
        }
    }
}

/** Handles notification time row. */
@Composable
private fun NotificationTimeRow(
    enabled: Boolean,
    timeText: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = "Reminder Time",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (enabled) timeText else "Enable notifications to choose a time",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

/** Handles notification time picker dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            TimePicker(state = state)
        }
    )
}

/** Displays a labeled switch row for a settings option. */
@Composable
private fun SettingToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    brandTeal: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ChorevoTheme.colors.onAccent,
                checkedTrackColor = brandTeal
            )
        )
    }
}

/** Applies notification toggle changes and requests permission when needed. */
private fun handleNotificationToggle(
    context: Context,
    enabled: Boolean,
    currentPushEnabled: Boolean,
    setPushEnabled: (Boolean) -> Unit,
    permissionLauncher: () -> Unit
) {
    if (!enabled) {
        setPushEnabled(false)
        return
    }

    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        openAppNotificationSettings(context)
        return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        requestExactAlarmPermission(context)
        setPushEnabled(true)
        return
    }

    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    if (granted) {
        requestExactAlarmPermission(context)
        if (!currentPushEnabled) {
            setPushEnabled(true)
        }
    } else {
        permissionLauncher()
    }
}

/** Opens app notification settings. */
private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

/** Handles request exact alarm permission. */
private fun requestExactAlarmPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

        if (!alarmManager.canScheduleExactAlarms()) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}

/** Formats time for display. */
private fun formatTime(hour: Int, minute: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }

    val hour12 = when (val h = calendar.get(Calendar.HOUR)) {
        0 -> 12
        else -> h
    }
    val minuteText = calendar.get(Calendar.MINUTE).toString().padStart(2, '0')
    val amPm = if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"

    return "$hour12:$minuteText $amPm"
}
