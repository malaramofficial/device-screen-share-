package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.TermuxCastUiState
import com.example.ui.components.FileActionBar
import com.example.ui.components.copyToClipboard
import com.example.ui.components.shareTextContent

@Composable
fun ServerFilesScreen(
  state: TermuxCastUiState,
  onSelectFile: (String) -> Unit,
  onShowMessage: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val selected = state.selectedFile

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
      items(state.serverFiles, key = { it.id }) { file ->
        val isSelected = file.id == selected?.id
        FilterChip(
          selected = isSelected,
          onClick = { onSelectFile(file.id) },
          label = { Text(file.path, style = MaterialTheme.typography.labelMedium) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          modifier = Modifier.testTag("file_chip_${file.id}"),
        )
      }
    }

    if (selected != null) {
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          FileActionBar(
            title = selected.path,
            subtitle = "${selected.language} • ${selected.lineCount} lines",
            onCopy = {
              copyToClipboard(context, selected.path, selected.content)
              onShowMessage("Copied ${selected.path}")
            },
            onShare = {
              shareTextContent(context, "TermuxCast: ${selected.path}", selected.content)
            },
          )
          Text(
            text = selected.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.weight(1f).fillMaxWidth(),
      ) {
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        SelectionContainer {
          Row(
            modifier =
              Modifier.fillMaxSize()
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll)
                .padding(14.dp)
          ) {
            Text(
              text = selected.content,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onBackground,
            )
          }
        }
      }
    }
  }
}
