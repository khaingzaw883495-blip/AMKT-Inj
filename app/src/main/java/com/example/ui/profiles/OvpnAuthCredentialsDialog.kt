package com.example.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * One-time credential prompt shown ONLY during `.ovpn` file import when the config specifies
 * `auth-user-pass` without inline credentials. Credentials are encrypted at rest and never
 * displayed again anywhere in the app.
 */
@Composable
fun OvpnAuthCredentialsDialog(
  profileName: String,
  onConfirm: (username: String, password: String) -> Unit,
  onDismiss: () -> Unit,
) {
  var username by remember(profileName) { mutableStateOf("") }
  var password by remember(profileName) { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("ovpn_auth_dialog"),
    title = {
      Text(
        text = stringResource(R.string.ovpn_auth_dialog_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = profileName,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = stringResource(R.string.ovpn_auth_dialog_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text(stringResource(R.string.ovpn_auth_username_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("ovpn_auth_username_input"),
        )
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text(stringResource(R.string.ovpn_auth_password_label)) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth().testTag("ovpn_auth_password_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(username.trim(), password) },
        enabled = username.trim().isNotBlank(),
        modifier = Modifier.testTag("ovpn_auth_save_button"),
      ) {
        Text(stringResource(R.string.ovpn_auth_save_button))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("ovpn_auth_cancel_button"),
      ) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}
