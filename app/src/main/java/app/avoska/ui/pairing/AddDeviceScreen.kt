package app.avoska.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.avoska.R
import app.avoska.ui.common.AppIcon
import app.avoska.ui.common.AccentOutlinedButton
import app.avoska.ui.common.AppTopBar
import kotlinx.coroutines.launch

/** Flow 1, step 1. On first launch there is no back arrow. */
@Composable
fun AddDeviceScreen(
    viewModel: AddDeviceViewModel,
    firstLaunch: Boolean,
    onBack: () -> Unit,
    onAdded: (deviceId: String) -> Unit,
    onShowDeviceId: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scanMessage = stringResource(R.string.scan_demo)
    LaunchedEffect(state.scannedDemo) {
        if (state.scannedDemo) {
            viewModel.scanMessageShown()
            scope.launch { snackbar.showSnackbar(scanMessage) }
        }
    }
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.add_a_device), onBack = onBack.takeUnless { firstLaunch }) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.add_device_intro),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                Button(onClick = viewModel::scan, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    AppIcon(R.drawable.ic_qr, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_qr_code), fontSize = 16.sp)
                }
                Text(
                    stringResource(R.string.or),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                )
                Text(
                    stringResource(R.string.device_id),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::onInput,
                        placeholder = { Text(stringResource(R.string.device_id_placeholder)) },
                        isError = state.invalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                                if (text != null) viewModel.onInput(text.toString())
                            }
                        },
                    ) { Text(stringResource(R.string.paste), fontSize = 16.sp) }
                }
                if (state.invalid) {
                    Text(
                        stringResource(R.string.invalid_device_id),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                AccentOutlinedButton(
                    onClick = { viewModel.add()?.let(onAdded) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.add_device), fontSize = 16.sp) }
            }
            TextButton(
                onClick = onShowDeviceId,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp),
            ) { Text(stringResource(R.string.show_my_device_id), fontSize = 16.sp) }
        }
    }
}
