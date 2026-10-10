package com.sakata.focusflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Shared hierarchy for content sections across the four primary pages. */
@Composable
internal fun FocusSectionHeader(
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stackAction = action != null &&
            (maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.3f)
        if (stackAction) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FocusSectionTitle(title, count)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    action.invoke()
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FocusSectionTitle(title, count, Modifier.weight(1f))
                if (action != null) {
                    Spacer(Modifier.width(8.dp))
                    action.invoke()
                }
            }
        }
    }
}

@Composable
private fun FocusSectionTitle(title: String, count: Int?, modifier: Modifier = Modifier) {
    Text(
        buildString {
            append(title)
            count?.let { append(" · ").append(it) }
        },
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}
