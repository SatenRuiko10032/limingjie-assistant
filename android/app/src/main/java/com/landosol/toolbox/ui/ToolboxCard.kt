package com.landosol.toolbox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

@Composable
internal fun ToolboxCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val compactHeight = LocalConfiguration.current.screenHeightDp < 480
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(if (compactHeight) 12.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
            content = content,
        )
    }
}
