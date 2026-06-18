package com.arcadesoftware.musix.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.math.sin
import kotlin.random.Random

object HeartAnimManager {
    val triggerEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    
    fun trigger() {
        triggerEvents.tryEmit(Unit)
    }
}

data class FloatingHeart(
    val id: Long,
    val startXFraction: Float, // 0.15f to 0.85f
    val scale: Float,
    val duration: Int, // Represents speed (float duration)
    val driftAmountPx: Float,
    val emoji: String,
    val rotation: Float // Random rotation tilt option
)

private val loveEmojis = listOf(

    // Hearts

    "🩷", "❤️", "🧡", "💛", "💚", "🩵", "💙", "💜",

    "🤍", "🤎", "❤️🔥", "❤️🩹",

    "💖", "💗", "💓", "💞", "💕", "💝", "💘", "❣️",


    "🥰", "😍", "😘", "🤩","🥳","🤭","💪",



    // Cute affection

    "💋", "💐", "💌", "🎀","🎉"

)

@Composable
fun FloatingHeartsContainer(modifier: Modifier = Modifier) {
    val hearts = remember { mutableStateListOf<FloatingHeart>() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) {
        HeartAnimManager.triggerEvents.collect {
            if (containerSize.width > 0 && containerSize.height > 0) {
                // Spawn a large group of 22 to 32 hearts (more than 20)
                val spawnCount = Random.nextInt(22, 33)
                val now = System.currentTimeMillis()
                for (i in 0 until spawnCount) {
                    hearts.add(
                        FloatingHeart(
                            id = now + i + Random.nextLong(100000),
                            startXFraction = Random.nextFloat() * 0.7f + 0.15f, // center 70% of screen width
                            scale = Random.nextFloat() * 0.8f + 0.6f, // random scale size option (0.6x to 1.4x)
                            duration = Random.nextInt(2400, 3500), // Speed: highly dynamic float duration (1.2s to 2.4s)
                            driftAmountPx = (Random.nextFloat() * 80f + 40f) * (if (Random.nextBoolean()) 1f else -1f),
                            emoji = loveEmojis.random(),
                            rotation = Random.nextFloat() * 50f - 25f // random rotation option (-25 to 25 degrees)
                        )
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        hearts.forEach { heart ->
            key(heart.id) {
                HeartParticle(
                    heart = heart,
                    containerSize = containerSize,
                    onAnimationFinished = { hearts.remove(heart) }
                )
            }
        }
    }
}

@Composable
fun HeartParticle(
    heart: FloatingHeart,
    containerSize: IntSize,
    onAnimationFinished: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = heart.duration, easing = LinearEasing)
        )
        onAnimationFinished()
    }

    val y = containerSize.height * (1f - progress.value)
    // Create a wavy side-to-side drift movement using a sine wave
    val drift = sin(progress.value * Math.PI.toFloat() * 2.8f) * heart.driftAmountPx
    val x = (containerSize.width * heart.startXFraction) + drift
    
    // Scale up at the start, fade out near the top
    val scale = heart.scale * if (progress.value < 0.15f) progress.value / 0.15f else 1f
    val alpha = if (progress.value > 0.75f) (1f - progress.value) / 0.25f else 1f

    Text(
        text = heart.emoji,
        fontSize = 28.sp,
        modifier = Modifier
            .offset { IntOffset(x.toInt(), y.toInt()) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = heart.rotation
                this.alpha = alpha
            }
    )
}
