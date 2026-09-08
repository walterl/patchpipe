package dev.patchpipe.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFF9CD3E8),
    secondary = Color(0xFFB7C9A8),
    background = Color(0xFF101820),
    surface = Color(0xFF15202B),
)

@Composable
fun PatchpipeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
