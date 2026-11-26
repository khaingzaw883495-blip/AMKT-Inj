package com.example.ui.redeem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.membership.RemotePriceItem
import com.example.redeem.RedeemOutcome
import com.example.ui.components.ExpiryBadge
import com.example.ui.components.RemotePricesCard
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedeemCodeScreen(
  deviceId: String,
  isRedeeming: Boolean,
  lastOutcome: RedeemOutcome?,
  onRedeemSubmit: (String) -> Unit,
  onClearOutcome: () -> Unit,
  onNavigateBack: () -> Unit,
  remotePrices: List<RemotePriceItem> = emptyList(),
  modifier: Modifier = Modifier,
) {
  var codeInput by rememberSaveable { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = stringResource(R.string.redeem_screen_title),
            fontFamily = DisplayFontFamily,
            fontWeight = FontWeight.Bold,
          )
        },
        navigationIcon = {
          IconButton(
            onClick = {
              onClearOutcome()
              onNavigateBack()
            },
            modifier = Modifier.testTag("redeem_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
      )
    },
    containerColor = MaterialTheme.colorScheme.background,
    modifier = modifier.fillMaxSize(),
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (remotePrices.isNotEmpty()) {
          item {
            RemotePricesCard(
              prices = remotePrices,
              cardTestTag = "redeem_prices_card",
            )
          }
        }
        item {
          Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(18.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                  Icon(
                    imageVector = Icons.Default.ConfirmationNumber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp).size(24.dp),
                  )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                  Text(
                    text = stringResource(R.string.redeem_card_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(
                    text = stringResource(R.string.redeem_card_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              OutlinedTextField(
                value = codeInput,
                onValueChange = {
                  codeInput = it
                  if (lastOutcome != null) onClearOutcome()
                },
                enabled = !isRedeeming,
                singleLine = true,
                label = { Text(stringResource(R.string.redeem_code_label)) },
                placeholder = {
                  Text(
                    text = stringResource(R.string.redeem_code_placeholder),
                    fontFamily = MonoFontFamily,
                  )
                },
                keyboardOptions =
                  KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                  ),
                keyboardActions =
                  KeyboardActions(
                    onDone = {
                      if (!isRedeeming && codeInput.isNotBlank()) {
                        onRedeemSubmit(codeInput)
                      }
                    }
                  ),
                modifier = Modifier.fillMaxWidth().testTag("redeem_code_input"),
              )

              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                  )
                  Text(
                    text = "${stringResource(R.string.pass_device_id_label)}: $deviceId",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              Text(
                text = stringResource(R.string.redeem_one_device_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              Button(
                onClick = { onRedeemSubmit(codeInput) },
                enabled = !isRedeeming && codeInput.trim().isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("redeem_submit_button"),
              ) {
                if (isRedeeming) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                  )
                  Spacer(Modifier.width(10.dp))
                  Text(stringResource(R.string.redeem_submitting_label))
                } else {
                  Text(
                    text = stringResource(R.string.redeem_submit_button),
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }
            }
          }
        }

        // Result feedback card
        lastOutcome?.let { outcome ->
          item {
            when (outcome) {
              is RedeemOutcome.Success -> {
                val srv = outcome.server
                Card(
                  shape = RoundedCornerShape(20.dp),
                  colors =
                    CardDefaults.cardColors(
                      containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                  modifier = Modifier.fillMaxWidth().testTag("redeem_success_card"),
                ) {
                  Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                      )
                      Spacer(Modifier.width(8.dp))
                      Text(
                        text =
                          if (outcome.wasUpdated) stringResource(R.string.redeem_success_updated_title)
                          else stringResource(R.string.redeem_success_added_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                      )
                    }

                    Text(
                      text = outcome.message,
                      style = MaterialTheme.typography.bodyMedium,
                    )

                    Surface(
                      shape = RoundedCornerShape(12.dp),
                      color = MaterialTheme.colorScheme.surface,
                      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                      modifier = Modifier.fillMaxWidth(),
                    ) {
                      Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                      ) {
                        Text(
                          text = srv.name,
                          style = MaterialTheme.typography.titleSmall,
                          fontWeight = FontWeight.Bold,
                        )
                        if (srv.expiryDate.isNotBlank()) {
                          ExpiryBadge(expiryDate = srv.expiryDate)
                        }
                      }
                    }

                    Text(
                      text = stringResource(R.string.redeem_next_step_hint),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FilledTonalButton(
                      onClick = {
                        onClearOutcome()
                        onNavigateBack()
                      },
                      shape = RoundedCornerShape(12.dp),
                      modifier = Modifier.fillMaxWidth().testTag("redeem_go_home_button"),
                    ) {
                      Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                      )
                      Spacer(Modifier.width(8.dp))
                      Text(stringResource(R.string.redeem_return_home_button))
                    }
                  }
                }
              }

              is RedeemOutcome.Invalid -> {
                RedeemErrorBanner(
                  title = stringResource(R.string.redeem_invalid_title),
                  message = outcome.message,
                )
              }

              is RedeemOutcome.Error -> {
                RedeemErrorBanner(
                  title = stringResource(R.string.redeem_error_title),
                  message = outcome.message,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RedeemErrorBanner(
  title: String,
  message: String,
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
      ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    modifier = Modifier.fillMaxWidth().testTag("redeem_error_card"),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = Icons.Default.ErrorOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
      )
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
          text = message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    }
  }
}
