package app.avoska.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.avoska.R
import app.avoska.SyncNetwork
import app.avoska.ui.common.AppTopBar

/** Flow 9: where the Sync button may download. */
@Composable
fun SyncNetworkScreen(viewModel: SyncNetworkViewModel, onBack: () -> Unit) {
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.sync_network), onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .selectableGroup(),
        ) {
            Text(
                stringResource(R.string.sync_network_intro),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            )
            SyncNetwork.entries.forEach { network ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .selectable(selected = network == selected, role = Role.RadioButton) { viewModel.select(network) }
                        .padding(horizontal = 4.dp),
                ) {
                    RadioButton(selected = network == selected, onClick = null, modifier = Modifier.padding(12.dp))
                    Column {
                        Text(stringResource(networkTitle(network)), fontSize = 16.sp, lineHeight = 22.sp)
                        Text(
                            stringResource(networkHint(network)),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun networkHint(network: SyncNetwork): Int = when (network) {
    SyncNetwork.OFF -> R.string.network_off_hint
    SyncNetwork.WIFI -> R.string.network_wifi_hint
    SyncNetwork.WIFI_AND_MOBILE -> R.string.network_wifi_mobile_hint
    SyncNetwork.ANY -> R.string.network_any_hint
}
