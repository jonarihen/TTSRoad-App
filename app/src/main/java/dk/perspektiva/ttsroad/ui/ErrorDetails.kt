package dk.perspektiva.ttsroad.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The raw text behind a plain-language error, folded away until asked for (#360).
 *
 * The sentence above it is for the person holding the phone; this is for whoever they ask for
 * help, and for the self-hoster reading their own proxy logs. Renders nothing when [details] is null.
 */
@Composable
fun ErrorDetails(
    details: String?,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    if (details.isNullOrBlank()) return
    var expanded by rememberSaveable(details) { mutableStateOf(false) }
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag(ErrorDetailsToggleTag),
        ) {
            MetaText(text = if (expanded) "Hide details" else "Details", color = AarisColor.Accent)
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    text = details,
                    color = AarisColor.Muted,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

const val ErrorDetailsToggleTag = "error-details-toggle"
