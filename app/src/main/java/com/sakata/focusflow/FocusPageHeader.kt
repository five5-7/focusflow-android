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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Shared page heading for the four primary surfaces.
 *
 * The side accent makes the hierarchy visible at a glance while the supporting
 * line gives the page a clear purpose. The title keeps a reserved action slot
 * in its row while the subtitle uses the full line below it.
 */
@Composable
internal fun FocusPageHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.displaySmall
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FocusPageHeaderAccent()
            Text(
                title,
                Modifier.weight(1f),
                style = titleStyle,
                fontWeight = FontWeight.Bold
            )
            if (action != null) {
                Box(
                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    action()
                }
            }
        }
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                Modifier.fillMaxWidth().padding(start = 16.dp),
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
