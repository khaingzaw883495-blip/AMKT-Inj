package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ConnectionMode
import com.example.data.PayloadMode
import com.example.data.ProfileExpiryChecker
import com.example.data.ServerProfileEntity
import com.example.ui.theme.MonoFontFamily

@Composable
fun ModeBadge(
  mode: ConnectionMode,
  modifier: Modifier = Modifier,
) {
  val (container, content) =
    when (mode) {
      ConnectionMode.SSH_DIRECT,
      ConnectionMode.SSH_WEBSOCKET ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
      ConnectionMode.SSH_PROXY,
      ConnectionMode.DNSTT ->
        MaterialTheme.colorScheme.secondaryContainer to
          MaterialTheme.colorScheme.onSecondaryContainer
      ConnectionMode.SSH_SSL,
      ConnectionMode.VLESS_WS ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = container,
  ) {
    Text(
      text = mode.displayLabel,
      style = MaterialTheme.typography.labelSmall,
      fontFamily = MonoFontFamily,
      fontWeight = FontWeight.Bold,
      color = content,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
fun PayloadModeBadge(
  payloadMode: PayloadMode,
  modifier: Modifier = Modifier,
) {
  val (container, content) =
    when (payloadMode) {
      PayloadMode.DIRECT ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
      PayloadMode.HTTP_PAYLOAD ->
        MaterialTheme.colorScheme.secondaryContainer to
          MaterialTheme.colorScheme.onSecondaryContainer
      PayloadMode.WEBSOCKET ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
      PayloadMode.SSL_TLS_SNI ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = container,
  ) {
    Text(
      text = "Payload: ${payloadMode.displayLabel}",
      style = MaterialTheme.typography.labelSmall,
      fontFamily = MonoFontFamily,
      fontWeight = FontWeight.SemiBold,
      color = content,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
fun StatusPill(
  text: String,
  containerColor: Color,
  contentColor: Color,
  icon: ImageVector? = null,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = containerColor,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(13.dp),
        )
      }
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
      )
    }
  }
}

@Composable
fun LockedBadge(modifier: Modifier = Modifier) {
  StatusPill(
    text = stringResource(R.string.badge_locked),
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    icon = Icons.Default.Lock,
    modifier = modifier,
  )
}

fun isDateExpired(expiryDate: String): Boolean = ProfileExpiryChecker.isExpired(expiryDate)

@Composable
fun ExpiryBadge(expiryDate: String, modifier: Modifier = Modifier) {
  if (expiryDate.isBlank()) return
  val expired = remember(expiryDate) { isDateExpired(expiryDate) }
  StatusPill(
    text =
      if (expired) "${stringResource(R.string.label_expired)} ($expiryDate)"
      else "${stringResource(R.string.label_expiry)}: $expiryDate",
    containerColor =
      if (expired) MaterialTheme.colorScheme.errorContainer
      else MaterialTheme.colorScheme.surfaceVariant,
    contentColor =
      if (expired) MaterialTheme.colorScheme.onErrorContainer
      else MaterialTheme.colorScheme.onSurfaceVariant,
    icon = Icons.Default.Schedule,
    modifier = modifier,
  )
}

@Composable
fun ImportOptionsDialog(
  onDismiss: () -> Unit,
  onPickMyVpnFile: () -> Unit,
  onPickTxtFile: () -> Unit,
  onImportLinkText: (String) -> Unit,
) {
  var showPasteField by remember { mutableStateOf(false) }
  var pastedText by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.import_dialog_title),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!showPasteField) {
          ImportOptionRow(
            icon = Icons.Default.EnhancedEncryption,
            title = stringResource(R.string.import_myvpn_option),
            subtitle = stringResource(R.string.import_myvpn_desc),
            testTag = "import_option_myvpn",
            onClick = {
              onDismiss()
              onPickMyVpnFile()
            },
          )
          ImportOptionRow(
            icon = Icons.Default.Description,
            title = stringResource(R.string.import_txt_option),
            subtitle = stringResource(R.string.import_txt_desc),
            testTag = "import_option_txt",
            onClick = {
              onDismiss()
              onPickTxtFile()
            },
          )
          ImportOptionRow(
            icon = Icons.Default.ContentPaste,
            title = stringResource(R.string.import_paste_option),
            subtitle = stringResource(R.string.import_paste_desc),
            testTag = "import_option_paste",
            onClick = { showPasteField = true },
          )
        } else {
          OutlinedTextField(
            value = pastedText,
            onValueChange = { pastedText = it },
            label = { Text(stringResource(R.string.import_paste_option)) },
            placeholder = {
              Text(
                text = stringResource(R.string.paste_link_hint),
                fontFamily = MonoFontFamily,
              )
            },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("paste_link_input"),
          )
        }
      }
    },
    confirmButton = {
      if (showPasteField) {
        Button(
          onClick = {
            if (pastedText.isNotBlank()) {
              onImportLinkText(pastedText)
              onDismiss()
            }
          },
          modifier = Modifier.testTag("confirm_paste_import_button"),
        ) {
          Text(stringResource(R.string.action_import_config))
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
private fun ImportOptionRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  testTag: String,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(testTag),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
      Spacer(Modifier.width(12.dp))
      Column {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
fun ExportMyVpnDialog(
  profile: ServerProfileEntity,
  onDismiss: () -> Unit,
  onConfirmExport: (locked: Boolean, expiryDate: String, notes: String) -> Unit,
) {
  var locked by remember(profile.id) { mutableStateOf(profile.locked) }
  var expiryDate by remember(profile.id) { mutableStateOf(profile.expiryDate) }
  var notes by remember(profile.id) { mutableStateOf(profile.notes) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.export_dialog_title),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = "${profile.name} (${profile.connectionMode.displayLabel})",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = stringResource(R.string.export_lock_label),
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = stringResource(R.string.export_lock_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = locked,
            onCheckedChange = { locked = it },
            modifier = Modifier.testTag("export_lock_switch"),
          )
        }
        OutlinedTextField(
          value = expiryDate,
          onValueChange = { expiryDate = it },
          label = { Text(stringResource(R.string.export_expiry_label)) },
          placeholder = { Text("2026-12-31", fontFamily = MonoFontFamily) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("export_expiry_input"),
        )
        OutlinedTextField(
          value = notes,
          onValueChange = { notes = it },
          label = { Text(stringResource(R.string.export_notes_label)) },
          minLines = 2,
          maxLines = 4,
          modifier = Modifier.fillMaxWidth().testTag("export_notes_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirmExport(locked, expiryDate, notes) },
        modifier = Modifier.testTag("export_confirm_button"),
      ) {
        Text(stringResource(R.string.export_button_confirm))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}

enum class SplitExportScope {
  COMBINED,
  NETWORK_ONLY,
  SERVER_ONLY,
}

@Composable
fun ExportSplitMyVpnDialog(
  network: com.example.data.NetworkConfigEntity?,
  server: com.example.data.ServerAccountEntity?,
  initialScope: SplitExportScope =
    when {
      network != null && server != null -> SplitExportScope.COMBINED
      network != null -> SplitExportScope.NETWORK_ONLY
      else -> SplitExportScope.SERVER_ONLY
    },
  onDismiss: () -> Unit,
  onConfirmExport: (
    scope: SplitExportScope,
    locked: Boolean,
    expiryDate: String,
    notes: String,
  ) -> Unit,
) {
  var scope by remember(network?.id, server?.id, initialScope) { mutableStateOf(initialScope) }
  var locked by remember(network?.id, server?.id) {
    mutableStateOf((network?.locked == true) || (server?.locked == true))
  }
  var expiryDate by remember(server?.id) { mutableStateOf(server?.expiryDate.orEmpty()) }
  var notes by remember(server?.id) { mutableStateOf(server?.notes.orEmpty()) }

  val canCombined = network != null && server != null

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.export_dialog_title),
        style = MaterialTheme.typography.titleLarge,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (canCombined) {
          Text(
            text = stringResource(R.string.export_mode_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportScopeOption(
              label = stringResource(R.string.export_mode_combined),
              detail = "${network?.name.orEmpty()} + ${server?.name.orEmpty()}",
              selected = scope == SplitExportScope.COMBINED,
              testTag = "export_scope_combined",
              onClick = { scope = SplitExportScope.COMBINED },
            )
            ExportScopeOption(
              label = stringResource(R.string.export_mode_network_only),
              detail = network?.name.orEmpty(),
              selected = scope == SplitExportScope.NETWORK_ONLY,
              testTag = "export_scope_network",
              onClick = { scope = SplitExportScope.NETWORK_ONLY },
            )
            ExportScopeOption(
              label = stringResource(R.string.export_mode_server_only),
              detail = server?.name.orEmpty(),
              selected = scope == SplitExportScope.SERVER_ONLY,
              testTag = "export_scope_server",
              onClick = { scope = SplitExportScope.SERVER_ONLY },
            )
          }
        } else {
          val targetTitle =
            when {
              network != null -> "${stringResource(R.string.export_mode_network_only)}: ${network.name}"
              server != null -> "${stringResource(R.string.export_mode_server_only)}: ${server.name}"
              else -> ""
            }
          Text(
            text = targetTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = stringResource(R.string.export_lock_label),
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = stringResource(R.string.export_lock_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = locked,
            onCheckedChange = { locked = it },
            modifier = Modifier.testTag("export_lock_switch"),
          )
        }

        if (scope != SplitExportScope.NETWORK_ONLY && server != null) {
          OutlinedTextField(
            value = expiryDate,
            onValueChange = { expiryDate = it },
            label = { Text(stringResource(R.string.export_expiry_label)) },
            placeholder = { Text("2026-12-31", fontFamily = MonoFontFamily) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("export_expiry_input"),
          )
          OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(stringResource(R.string.export_notes_label)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("export_notes_input"),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirmExport(scope, locked, expiryDate, notes) },
        modifier = Modifier.testTag("export_confirm_button"),
      ) {
        Text(stringResource(R.string.export_button_confirm))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
private fun ExportScopeOption(
  label: String,
  detail: String,
  selected: Boolean,
  testTag: String,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color =
      if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
      else MaterialTheme.colorScheme.surfaceVariant,
    border =
      BorderStroke(
        width = if (selected) 1.5.dp else 1.dp,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
      ),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(testTag),
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color =
          if (selected) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurface,
      )
      if (detail.isNotBlank()) {
        Text(
          text = detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

