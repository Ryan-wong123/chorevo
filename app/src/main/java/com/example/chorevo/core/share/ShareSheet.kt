package com.example.chorevo.core.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri

/** What is being shared. */
data class ShareContent(
    val danceId: String,
    val title: String,
    val educatorName: String
) {
    val link: String get() = ShareLinks.danceLink(danceId)
    val message: String get() = "Check out \"$title\" by $educatorName on Chorevo!\n$link"
}

/**
 * One destination in the share sheet.
 *
 * [packageName] is the native app to target when installed. [webFallback]
 * builds a browser URL used when the app is missing; destinations without a
 * fallback are hidden if their app isn't installed.
 */
private data class ShareTarget(
    val key: String,
    val label: String,
    val brandColor: Color,
    val packageName: String? = null,
    val linkOnly: Boolean = false,
    val webFallback: ((ShareContent) -> String)? = null,
    val icon: ImageVector? = null,
    val action: ((Context, ShareContent) -> Unit)? = null
)

private val APP_TARGETS = listOf(
    ShareTarget(
        key = "whatsapp", label = "WhatsApp", brandColor = Color(0xFF25D366),
        packageName = "com.whatsapp",
        webFallback = { "https://wa.me/?text=${enc(it.message)}" }
    ),
    ShareTarget(
        key = "instagram", label = "Instagram", brandColor = Color(0xFFE1306C),
        packageName = "com.instagram.android"
    ),
    ShareTarget(
        key = "facebook", label = "Facebook", brandColor = Color(0xFF1877F2),
        packageName = "com.facebook.katana",
        // The Facebook app ignores free text and only accepts a URL.
        linkOnly = true,
        webFallback = { "https://www.facebook.com/sharer/sharer.php?u=${enc(it.link)}" }
    ),
    ShareTarget(
        key = "messenger", label = "Messenger", brandColor = Color(0xFF0084FF),
        packageName = "com.facebook.orca"
    ),
    ShareTarget(
        key = "telegram", label = "Telegram", brandColor = Color(0xFF229ED9),
        packageName = "org.telegram.messenger",
        webFallback = {
            "https://t.me/share/url?url=${enc(it.link)}&text=${enc("Check out \"${it.title}\" by ${it.educatorName} on Chorevo!")}"
        }
    ),
    ShareTarget(
        key = "x", label = "X", brandColor = Color(0xFF000000),
        packageName = "com.twitter.android",
        webFallback = {
            "https://twitter.com/intent/tweet?text=${enc("Check out \"${it.title}\" by ${it.educatorName} on Chorevo!")}&url=${enc(it.link)}"
        }
    ),
    ShareTarget(
        key = "snapchat", label = "Snapchat", brandColor = Color(0xFFFFFC00),
        packageName = "com.snapchat.android"
    ),
    ShareTarget(
        key = "line", label = "LINE", brandColor = Color(0xFF06C755),
        packageName = "jp.naver.line.android"
    )
)

private val UTILITY_TARGETS = listOf(
    ShareTarget(
        key = "copy", label = "Copy link", brandColor = Color(0xFF5F6368),
        icon = Icons.Outlined.ContentCopy,
        action = { ctx, content ->
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Chorevo link", content.link))
            Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
        }
    ),
    ShareTarget(
        key = "sms", label = "SMS", brandColor = Color(0xFF34A853),
        icon = Icons.Outlined.Sms,
        action = { ctx, content ->
            val intent = Intent(Intent.ACTION_SENDTO, "smsto:".toUri())
                .putExtra("sms_body", content.message)
            ctx.safeStart(intent)
        }
    ),
    ShareTarget(
        key = "email", label = "Email", brandColor = Color(0xFFEA4335),
        icon = Icons.Outlined.Email,
        action = { ctx, content ->
            val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
                .putExtra(Intent.EXTRA_SUBJECT, "${content.title} on Chorevo")
                .putExtra(Intent.EXTRA_TEXT, content.message)
            ctx.safeStart(intent)
        }
    ),
    ShareTarget(
        key = "more", label = "More", brandColor = Color(0xFF9AA0A6),
        icon = Icons.Outlined.MoreHoriz,
        action = { ctx, content -> ctx.openSystemChooser(content) }
    )
)

/** A resolved app destination: whether it's installed and its launcher icon. */
private data class ResolvedTarget(
    val target: ShareTarget,
    val installed: Boolean,
    val icon: ImageBitmap?
)

/**
 * TikTok/Instagram-style share sheet: a row of social apps followed by a row
 * of utility actions (copy link, SMS, email, system chooser).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareBottomSheet(
    content: ShareContent,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager

    // Resolve once per sheet: which apps are installed and their real icons.
    val appTargets = remember(content.danceId) {
        APP_TARGETS.mapNotNull { target ->
            val installed = target.packageName?.let { pm.isInstalled(it) } == true
            when {
                installed -> ResolvedTarget(target, true, pm.appIcon(target.packageName!!))
                target.webFallback != null -> ResolvedTarget(target, false, null)
                else -> null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.testTag("share_sheet")
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "Share to",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
            Text(
                text = content.link,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(appTargets, key = { it.target.key }) { resolved ->
                    ShareTargetItem(target = resolved.target, appIcon = resolved.icon) {
                        context.shareTo(resolved.target, content, appInstalled = resolved.installed)
                        onDismiss()
                    }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 16.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(UTILITY_TARGETS, key = { it.key }) { target ->
                    ShareTargetItem(target = target, appIcon = null) {
                        target.action?.invoke(context, content)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareTargetItem(
    target: ShareTarget,
    appIcon: ImageBitmap?,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("share_target_${target.key}")
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (appIcon != null) Color.Transparent else target.brandColor),
            contentAlignment = Alignment.Center
        ) {
            when {
                appIcon != null -> Image(
                    bitmap = appIcon,
                    contentDescription = target.label,
                    modifier = Modifier.fillMaxSize()
                )
                target.icon != null -> Icon(
                    imageVector = target.icon,
                    contentDescription = target.label,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
                else -> Text(
                    text = target.label.take(1),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = target.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

// ── Intent helpers ────────────────────────────────────────────────────────────

private fun Context.shareTo(target: ShareTarget, content: ShareContent, appInstalled: Boolean) {
    if (appInstalled && target.packageName != null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(target.packageName)
            putExtra(Intent.EXTRA_SUBJECT, content.title)
            putExtra(Intent.EXTRA_TEXT, if (target.linkOnly) content.link else content.message)
        }
        if (tryStart(intent)) return
    }
    val fallback = target.webFallback?.invoke(content)
    if (fallback == null || !tryStart(Intent(Intent.ACTION_VIEW, fallback.toUri()))) {
        openSystemChooser(content)
    }
}

private fun Context.openSystemChooser(content: ShareContent) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, content.title)
        putExtra(Intent.EXTRA_TEXT, content.message)
    }
    safeStart(Intent.createChooser(send, "Share via"))
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

private fun Context.safeStart(intent: Intent): Boolean =
    tryStart(intent).also { started ->
        if (!started) Toast.makeText(this, "No app available to share", Toast.LENGTH_SHORT).show()
    }

private fun PackageManager.isInstalled(packageName: String): Boolean = try {
    getApplicationInfo(packageName, 0).enabled
} catch (_: PackageManager.NameNotFoundException) {
    false
}

private fun PackageManager.appIcon(packageName: String): ImageBitmap? = try {
    getApplicationIcon(packageName).toBitmap(width = 144, height = 144).asImageBitmap()
} catch (_: Exception) {
    null
}

private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
