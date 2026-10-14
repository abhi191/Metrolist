package com.metrolist.music.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R

/**
 * FOSS builds have no Google Cast; this button picks a Sonos room on the local network instead.
 */
@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val handler = LocalPlayerConnection.current?.service?.castConnectionHandler ?: return
    val isCasting by handler.isCasting.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            handler.discover()
            showPicker = true
        },
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(if (isCasting) R.drawable.cast_connected else R.drawable.cast),
            contentDescription = stringResource(R.string.sonos_play_on),
            tint = if (isCasting) MaterialTheme.colorScheme.primary else tintColor,
        )
    }

    if (showPicker) {
        val rooms by handler.rooms.collectAsStateWithLifecycle()
        val isDiscovering by handler.isDiscovering.collectAsStateWithLifecycle()
        val connectedId by handler.connectedRoomId.collectAsStateWithLifecycle()

        AlertDialog(
            onDismissRequest = { showPicker = false },
            icon = { Icon(painterResource(R.drawable.cast), contentDescription = null) },
            title = { Text(stringResource(R.string.sonos_play_on)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutputRow(
                        name = stringResource(R.string.sonos_this_phone),
                        selected = connectedId == null,
                        onClick = {
                            handler.disconnect()
                            showPicker = false
                        },
                    )
                    rooms.forEach { room ->
                        OutputRow(
                            name = room.name,
                            selected = room.id == connectedId,
                            onClick = {
                                handler.connect(room)
                                showPicker = false
                            },
                        )
                    }
                    when {
                        isDiscovering ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(12.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(R.string.sonos_searching),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                        rooms.isEmpty() ->
                            Text(
                                stringResource(R.string.sonos_no_speakers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.close)) }
            },
            dismissButton = {
                TextButton(onClick = handler::discover, enabled = !isDiscovering) {
                    Text(stringResource(R.string.sonos_search_again))
                }
            },
        )
    }
}

@Composable
private fun OutputRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
