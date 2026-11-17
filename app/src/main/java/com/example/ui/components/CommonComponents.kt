package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

fun copyToClipboard(context: Context, label: String, text: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun shareTextContent(context: Context, subject: String, body: String) {
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, subject)
      putExtra(Intent.EXTRA_TEXT, body)
    }
  context.startActivity(Intent.createChooser(intent, subject))
}

@Composable
fun StatusPill(
  label: String,
  dotColor: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surfaceVariant,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun CommandSnippetBox(
  command: String,
  onCopy: () -> Unit,
  modifier: Modifier = Modifier,
  testTag: String = "copy_command_button",
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = command,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
      )
      Spacer(modifier = Modifier.width(4.dp))
      IconButton(
        onClick = onCopy,
        modifier = Modifier.size(48.dp).testTag(testTag),
      ) {
        Icon(
          imageVector = Icons.Rounded.ContentCopy,
          contentDescription = "Copy command",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
fun FileActionBar(
  title: String,
  subtitle: String,
  onCopy: () -> Unit,
  onShare: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(
      onClick = onCopy,
      modifier = Modifier.size(48.dp).testTag("copy_file_button"),
    ) {
      Icon(
        imageVector = Icons.Rounded.ContentCopy,
        contentDescription = "Copy file contents",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
    IconButton(
      onClick = onShare,
      modifier = Modifier.size(48.dp).testTag("share_file_button"),
    ) {
      Icon(
        imageVector = Icons.Rounded.Share,
        contentDescription = "Share file contents",
        tint = MaterialTheme.colorScheme.secondary,
      )
    }
  }
}
