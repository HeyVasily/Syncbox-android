package app.avoska.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.avoska.R
import app.avoska.ui.common.AppIcon
import app.avoska.ui.common.AppTopBar
import app.avoska.ui.theme.LocalStatusColors

/**
 * Flow 1, steps 2 and 3. On first launch it waits for the device and then shows its folder offers;
 * from Settings it only waits for the device (steps 1 and 2) and then finishes.
 */
@Composable
fun ConnectFolderScreen(
    viewModel: ConnectFolderViewModel,
    firstLaunch: Boolean,
    onBack: () -> Unit,
    onAccepted: (folderId: String) -> Unit,
    onDone: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val device = state.device
    val connected = device?.connected == true
    Scaffold(
        topBar = {
            AppTopBar(
                stringResource(if (firstLaunch) R.string.connect_a_folder else R.string.add_a_device),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (device != null) {
                    DeviceLine(
                        name = device.name ?: device.id.substringBefore('-'),
                        status = stringResource(if (connected) R.string.connected else R.string.waiting_for_device),
                        connected = connected,
                    )
                }
                when {
                    !connected -> Hint(stringResource(R.string.waiting_hint))
                    firstLaunch -> {
                        state.offers.forEach { offer ->
                            OfferCard(
                                folder = offer.label,
                                deviceName = device?.name.orEmpty(),
                                onAccept = {
                                    viewModel.accept(offer)
                                    onAccepted(offer.folderId)
                                },
                                onIgnore = { viewModel.ignore(offer) },
                            )
                        }
                        Hint(stringResource(R.string.no_offer_hint))
                    }
                    else -> Hint(stringResource(R.string.device_connected_hint))
                }
            }
            if (firstLaunch) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp),
                ) { Text(stringResource(R.string.add_another_device), fontSize = 16.sp) }
            } else {
                Button(
                    onClick = onDone,
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.done), fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun DeviceLine(name: String, status: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
        AppIcon(R.drawable.ic_pc)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, lineHeight = 22.sp)
            Text(
                status,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (connected) LocalStatusColors.current.ok else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!connected) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun OfferCard(folder: String, deviceName: String, onAccept: () -> Unit, onIgnore: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_folder, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(folder, fontSize = 16.sp, lineHeight = 22.sp)
                    Text(
                        stringResource(R.string.folder_offer, deviceName),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.folder_offer_hint),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onIgnore) { Text(stringResource(R.string.ignore), fontSize = 16.sp) }
                Button(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.accept), fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}
