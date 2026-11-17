package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.SetupStepItem
import com.example.ui.TermuxCastUiState
import com.example.ui.components.CommandSnippetBox
import com.example.ui.components.copyToClipboard

@Composable
fun TermuxSetupScreen(
  state: TermuxCastUiState,
  onToggleStep: (String) -> Unit,
  onShowMessage: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val completedCount = state.completedStepIds.size
  val totalCount = state.setupSteps.size.coerceAtLeast(1)
  val progress = completedCount.toFloat() / totalCount.toFloat()

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Progress + 1-paste installer banner
    item {
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = "Termux Local Setup",
                style = MaterialTheme.typography.titleMedium,
              )
            }
            Text(
              text = "$completedCount / $totalCount ready",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }

          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
          )

          Text(
            text =
              "Run this entire Flask + SocketIO server directly inside Termux on your phone. Other devices on your Wi-Fi open ${state.browserStreamUrl}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Button(
            onClick = {
              val script =
                state.serverFiles.firstOrNull { it.id == "installer" }?.content.orEmpty()
              copyToClipboard(context, "TermuxCast 1-Paste Installer", script)
              onShowMessage("Copied 1-paste installer script to clipboard")
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("copy_installer_button"),
          ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Copy 1-Paste Termux File Installer")
          }
        }
      }
    }

    items(state.setupSteps, key = { it.id }) { step ->
      val isDone = step.id in state.completedStepIds
      SetupStepCard(
        step = step,
        isDone = isDone,
        onToggle = { onToggleStep(step.id) },
        onCopyCommand = { cmd ->
          copyToClipboard(context, "Termux Command", cmd)
          onShowMessage("Copied Step ${step.stepNumber} command")
        },
      )
    }
  }
}

@Composable
private fun SetupStepCard(
  step: SetupStepItem,
  isDone: Boolean,
  onToggle: () -> Unit,
  onCopyCommand: (String) -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (isDone) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
          else MaterialTheme.colorScheme.outline,
      ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(
          onClick = onToggle,
          modifier = Modifier.size(48.dp).testTag("toggle_${step.id}"),
        ) {
          Icon(
            imageVector =
              if (isDone) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (isDone) "Mark incomplete" else "Mark step complete",
            tint =
              if (isDone) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "STEP ${step.stepNumber}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
          )
          Text(
            text = step.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }

      Text(
        text = step.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      step.command?.let { cmd ->
        CommandSnippetBox(
          command = cmd,
          onCopy = { onCopyCommand(cmd) },
          testTag = "copy_${step.id}",
        )
      }

      step.tip?.let { tip ->
        Text(
          text = "Tip: $tip",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.tertiary,
        )
      }
    }
  }
}
