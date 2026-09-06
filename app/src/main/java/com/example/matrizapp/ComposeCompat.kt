package com.example.matrizapp

import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compatibility wrapper for Material3 versions where HorizontalDivider is not available.
 * Keep the call site readable while using the Divider API already used by the project.
 */
@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    Divider(modifier = modifier)
}
