package com.arcadesoftware.musix.components

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import com.arcadesoftware.musix.R
import kotlinx.coroutines.launch

data class WhatsNewFeature(
    val title: String,
    val description: String,
    val imageRes: Int
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhatsNewDialog(
    onDismiss: () -> Unit
) {
    val features = listOf(
        WhatsNewFeature(
            title = "New Musix Icons",
            description = "Select the icon that matches your aesthetic.\nSettings -> App Preference -> App Icon",
            imageRes = R.drawable.whatsnew_1
        ),
        WhatsNewFeature(
            title = "Control What Syncs to Cloud",
            description = "Manage the data leaving your device to the Musix cloud. If you have security concerns, you can delete your account completely.\nSettings -> Cloud Sync",
            imageRes = R.drawable.whatsnew_2
        ),
        WhatsNewFeature(
            title = "Download Center",
            description = "Monitor and manage your background downloads in real time within the Download Center.",
            imageRes = R.drawable.whatsnew_3
        )
    )

    val pagerState = rememberPagerState(pageCount = { features.size })
    val coroutineScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0C))
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header / Title
                Text(
                    text = "What's New in Musix",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                )

                val infiniteTransition = rememberInfiniteTransition(label = "whats_new_glow")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(4000, easing = androidx.compose.animation.core.LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                    ),
                    label = "border_rotation"
                )

                // Pager
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    val feature = features[page]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Image Container with rotating glowing border
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(262.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Rotating border
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(262.dp)
                                    .graphicsLayer { rotationZ = rotation }
                                    .border(
                                        2.5.dp,
                                        androidx.compose.ui.graphics.Brush.sweepGradient(
                                            listOf(
                                                Color(0xFF00FFFF),
                                                Color(0xFFFF00FF),
                                                Color(0xFFFFCC00),
                                                Color(0xFF00FFFF)
                                            )
                                        ),
                                        RoundedCornerShape(24.dp)
                                    )
                            )
                            // Static inner image
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF141416)),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = feature.imageRes),
                                    contentDescription = feature.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Text(
                            text = feature.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = feature.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            lineHeight = 22.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(features.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (isSelected) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Action Buttons
                val isLastPage = pagerState.currentPage == features.size - 1
                Button(
                    onClick = {
                        if (isLastPage) {
                            onDismiss()
                        } else {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isLastPage) "Get Started" else "Next",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

object WhatsNewChecker {
    /**
     * Checks if the app has been updated by comparing the current version code
     * with the stored version code in SharedPreferences.
     */
    fun shouldShowWhatsNew(context: Context): Boolean {
        // FOR TESTING: Returns true unconditionally so you can preview and test.
        // Set to false when moving to production/final.
        val testingMode = true
        if (testingMode) return true

        val sharedPrefs = context.getSharedPreferences("whats_new_prefs", Context.MODE_PRIVATE)
        val storedVersionCode = sharedPrefs.getInt("last_seen_version_code", -1)
        
        val currentVersionCode = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            0
        }

        return currentVersionCode > storedVersionCode
    }

    /**
     * Marks the current version code as seen so "What's New" is not shown again
     * until the next app update.
     */
    fun markWhatsNewAsSeen(context: Context) {
        val sharedPrefs = context.getSharedPreferences("whats_new_prefs", Context.MODE_PRIVATE)
        val currentVersionCode = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            0
        }
        sharedPrefs.edit().putInt("last_seen_version_code", currentVersionCode).apply()
    }
}
