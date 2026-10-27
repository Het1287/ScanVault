package com.example.ownscan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.ownscan.ui.icons.AppIcons

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

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
            color = colors.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 3.dp,
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectTab(0) }
                    ) {
                        Icon(
                            painter = if (selectedTab == 0) AppIcons.NavHomeFilled else AppIcons.NavHomeOutline,
                            contentDescription = "Home",
                            modifier = Modifier.size(24.dp),
                            tint = if (selectedTab == 0) colors.accent else colors.textMuted
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
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
                        Icon(
                            painter = if (selectedTab == 1) AppIcons.NavFilesFilled else AppIcons.NavFilesOutline,
                            contentDescription = "Files",
                            modifier = Modifier.size(24.dp),
                            tint = if (selectedTab == 1) colors.accent else colors.textMuted
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(if (selectedTab == 1) colors.accent else Color.Transparent)
                        )
                    }

                    // Empty center gap equal to the camera button width so tabs do not overlap
                    Spacer(modifier = Modifier.width(56.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectTab(2) }
                    ) {
                        Icon(
                            painter = if (selectedTab == 2) AppIcons.NavToolsFilled else AppIcons.NavToolsOutline,
                            contentDescription = "Tools",
                            modifier = Modifier.size(24.dp),
                            tint = if (selectedTab == 2) colors.accent else colors.textMuted
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
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
                        Icon(
                            painter = if (selectedTab == 3) AppIcons.NavSettingsFilled else AppIcons.NavSettingsOutline,
                            contentDescription = "Settings",
                            modifier = Modifier.size(24.dp),
                            tint = if (selectedTab == 3) colors.accent else colors.textMuted
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(if (selectedTab == 3) colors.accent else Color.Transparent)
                        )
                    }
                }
            }
        }

        // CamScanner-style raised circular camera button (rises ~40% above the bar)
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable { onCenterScanClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_nav_camera),
                contentDescription = "Scan document",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

