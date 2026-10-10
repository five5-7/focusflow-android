package com.sakata.focusflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared page heading for the four primary surfaces.
 *
 * The side accent makes the hierarchy visible at a glance while the supporting
 * line gives the page a clear purpose. A trailing help action stays in this
 * same row so it remains discoverable and never consumes a separate line.
 */
@Composable
internal fun FocusPageHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FocusPageHeaderAccent()
        FocusPageHeaderText(title, subtitle)
        action?.invoke()
    }
}

@Composable
private fun RowScope.FocusPageHeaderText(title: String, subtitle: String?) {
    Column(
        Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FocusPageHeaderAccent() {
    Box(
        Modifier
            .width(4.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary)
    )
}
