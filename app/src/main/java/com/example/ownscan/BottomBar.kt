package com.example.ownscan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// -------------------------------------------------------------
// BOTTOM BAR: HOME, FILES, CAMERA, TOOLS, SETTINGS
// -------------------------------------------------------------

@Composable
fun MockupBottomBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onCenterScanClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        border = BorderStroke(1.dp, colors.divider)
    ) {
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
            .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectTab(0) }
            ) {
                Text(
                    text = "🏠",
                    fontSize = 20.sp,
                    color = if (selectedTab == 0) colors.accent else colors.textMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selectedTab == 0) colors.accent else Color.Transparent)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectTab(1) }
            ) {
                Text(
                    text = "📁",
                    fontSize = 20.sp,
                    color = if (selectedTab == 1) colors.accent else colors.textMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selectedTab == 1) colors.accent else Color.Transparent)
                )
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable { onCenterScanClick() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(28.dp)) {
                    val w = size.width
                    val h = size.height

                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(0f, h * 0.24f),
                        size = Size(w, h * 0.74f),
                        cornerRadius = CornerRadius(8f, 8f),
                        style = Stroke(width = 3.2f)
                    )

                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(w * 0.32f, h * 0.06f),
                        size = Size(w * 0.36f, h * 0.20f),
                        cornerRadius = CornerRadius(4f, 4f)
                    )

                    drawCircle(
                        color = Color.White,
                        radius = w * 0.23f,
                        center = Offset(w * 0.5f, h * 0.61f),
                        style = Stroke(width = 3.2f)
                    )

                    drawCircle(
                        color = Color.White,
                        radius = w * 0.08f,
                        center = Offset(w * 0.5f, h * 0.61f)
                    )

                    drawCircle(
                        color = Color.White,
                        radius = 2.5f,
                        center = Offset(w * 0.82f, h * 0.38f)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectTab(2) }
            ) {
                Text(
                    text = "🛠️",
                    fontSize = 20.sp,
                    color = if (selectedTab == 2) colors.accent else colors.textMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selectedTab == 2) colors.accent else Color.Transparent)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectTab(3) }
            ) {
                Text(
                    text = "⚙️",
                    fontSize = 20.sp,
                    color = if (selectedTab == 3) colors.accent else colors.textMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selectedTab == 3) colors.accent else Color.Transparent)
                )
            }
        }
    }
    }
}

