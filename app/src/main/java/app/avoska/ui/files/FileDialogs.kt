package app.avoska.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.avoska.R
import app.avoska.engine.EntryState
import app.avoska.ui.common.AppIcon
import app.avoska.ui.common.formatSize
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The row menu (ui.md): only the actions that apply to the file's state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileMenuSheet(row: FileRow, canDownload: Boolean, onAction: (MenuAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(bottom = 16.dp)) {
            MenuLine(
                icon = rowIcon(row.entry),
                title = row.entry.name,
                hint = "${formatSize(row.entry.sizeBytes)}${stateSuffix(row.entry.state)}",
                enabled = true,
                onClick = null,
            )
            row.menuActions.forEach { action ->
                when (action) {
                    MenuAction.OPEN -> MenuLine(R.drawable.ic_play, stringResource(R.string.action_open)) { onAction(action) }
                    MenuAction.DOWNLOAD_NOW -> MenuLine(
                        icon = R.drawable.ic_down,
                        title = stringResource(R.string.action_download_now),
                        hint = stringResource(
                            when {
                                !canDownload -> R.string.action_download_unavailable_hint
                                row.entry.state == EntryState.Outdated -> R.string.action_download_new_version_hint
                                else -> R.string.action_download_now_hint
                            },
                        ),
                        enabled = canDownload,
                    ) { onAction(action) }
                    MenuAction.REMOVE -> MenuLine(R.drawable.ic_trash, stringResource(R.string.action_remove)) { onAction(action) }
                    MenuAction.DETAILS -> MenuLine(R.drawable.ic_info, stringResource(R.string.action_details)) { onAction(action) }
                }
            }
        }
    }
}

@Composable
private fun stateSuffix(state: EntryState): String {
    val text = stateText(state) ?: return ""
    return stringResource(R.string.separator) + text
}

@Composable
private fun stateText(state: EntryState): String? = when (state) {
    EntryState.NotSelected -> null
    EntryState.SomeSelected -> stringResource(R.string.state_some_selected)
    is EntryState.Waiting -> stringResource(R.string.state_waiting_for_sync)
    is EntryState.Downloading -> stringResource(R.string.state_downloading)
    EntryState.OnDevice -> stringResource(R.string.state_on_device)
    EntryState.Outdated -> stringResource(R.string.state_outdated)
}

@Composable
private fun MenuLine(
    icon: Int,
    title: String,
    hint: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        AppIcon(
            icon,
            tint = if (onClick == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            },
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (hint != null) {
                Text(
                    hint,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
    }
}

/** Flow 4: deleting the local copy asks first and shows the size it frees. */
@Composable
fun RemoveDialog(row: FileRow, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val entry = row.entry
    val size = formatSize(entry.localBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_title)) },
        text = {
            Text(
                stringResource(if (entry.isFolder) R.string.remove_folder_text else R.string.remove_file_text, entry.name, size),
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove), fontSize = 15.sp) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), fontSize = 15.sp) } },
    )
}

@Composable
fun DetailsDialog(row: FileRow, onDismiss: () -> Unit) {
    val entry = row.entry
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(entry.modifiedMillis).atZone(ZoneId.systemDefault()))
    val folder = entry.ref.parent.ifEmpty { "/" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column {
                Text(stringResource(R.string.details_size, formatSize(entry.sizeBytes)))
                Text(stringResource(R.string.details_modified, date))
                Text(stringResource(R.string.details_location, folder))
                Text(stringResource(R.string.details_state, stateText(entry.state) ?: stringResource(R.string.state_not_selected)))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
