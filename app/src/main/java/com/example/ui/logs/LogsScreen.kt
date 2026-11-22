package com.example.ui.logs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.LogEntry
import com.example.data.LogLevel
import com.example.ui.theme.MonoFontFamily

@Composable
fun LogsScreen(
  logs: List<LogEntry>,
  onCopyLogs: () -> Unit,
  onClearLogs: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).fillMaxSize()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.logs_title),
            style = MaterialTheme.typography.headlineSmall,
          )
          Text(
            text = stringResource(R.string.logs_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row {
          IconButton(
            onClick = onCopyLogs,
            modifier = Modifier.testTag("logs_copy_button"),
          ) {
            Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = stringResource(R.string.action_copy_logs),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
          IconButton(
            onClick = onClearLogs,
            modifier = Modifier.testTag("logs_clear_button"),
          ) {
            Icon(
              imageVector = Icons.Default.DeleteSweep,
              contentDescription = stringResource(R.string.action_clear_logs),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        if (logs.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = stringResource(R.string.logs_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(logs.reversed(), key = { it.id }) { entry ->
              val tagColor =
                when (entry.level) {
                  LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                  LogLevel.WARNING -> MaterialTheme.colorScheme.secondary
                  LogLevel.ERROR -> MaterialTheme.colorScheme.error
                  LogLevel.INFO -> MaterialTheme.colorScheme.tertiary
                }
              Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                  text = entry.timestamp,
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = MonoFontFamily,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                  text = "[${entry.tag}]",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = MonoFontFamily,
                  fontWeight = FontWeight.Bold,
                  color = tagColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                  text = entry.message,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }
    }
  }
}
