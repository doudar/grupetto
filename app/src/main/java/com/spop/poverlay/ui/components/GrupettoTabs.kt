package com.spop.poverlay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ScrollableTabRow
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bounded folder-style tabs, with a stronger outline and fill for the selected page. */
@Composable
fun GrupettoTabs(selectedTabIndex: Int, labels: List<String>, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, fontSize: TextUnit = 14.sp) {
    require(labels.isNotEmpty() && selectedTabIndex in labels.indices)
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    val colors = MaterialTheme.colors
    BoxWithConstraints(modifier) {
        val tabs: @Composable () -> Unit = {
            labels.forEachIndexed { index, label ->
                val selected = selectedTabIndex == index
                Tab(selected = selected, onClick = { onSelect(index) },
                    selectedContentColor = colors.onSurface,
                    unselectedContentColor = colors.onSurface,
                    modifier = Modifier.padding(horizontal = 2.dp).heightIn(min = 48.dp)
                        .clip(shape)
                        .background(if (selected) lerp(colors.surface, colors.primary, .18f) else colors.surface)
                        .border(if (selected) 2.dp else 1.dp,
                            if (selected) colors.primary else colors.onSurface.copy(alpha = .5f), shape)) {
                    Text(label, Modifier.padding(horizontal = 8.dp, vertical = 12.dp), fontSize = fontSize,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        // Keep compact dialogs readable rather than squeezing labels or reducing touch targets.
        if (maxWidth < (labels.size * 88).dp) {
            ScrollableTabRow(selectedTabIndex, backgroundColor = Color.Transparent, edgePadding = 0.dp,
                indicator = {}, divider = {}, tabs = tabs)
        } else {
            TabRow(selectedTabIndex, backgroundColor = Color.Transparent, indicator = {}, divider = {}, tabs = tabs)
        }
    }
}
