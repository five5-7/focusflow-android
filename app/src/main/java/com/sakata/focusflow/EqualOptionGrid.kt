package com.sakata.focusflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Reserve enough width for labels after font scaling; keep four equal choices in two rows on phones. */
internal fun equalOptionColumns(widthDp: Float, fontScale: Float, count: Int): Int {
    if (count <= 1) return 1
    val minimumWidth = 136f * fontScale.coerceAtLeast(1f)
    return when {
        widthDp < minimumWidth * 2 + 8f -> 1
        count >= 4 && widthDp >= minimumWidth * 4 + 24f -> 4
        else -> 2
    }
}

@Composable
internal fun <T> EqualOptionGrid(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = equalOptionColumns(maxWidth.value, LocalDensity.current.fontScale, options.size)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.chunked(columns).forEach { rowOptions ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = selected == value,
                            onClick = { onSelect(value) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            label = { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                        )
                    }
                    repeat(columns - rowOptions.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
