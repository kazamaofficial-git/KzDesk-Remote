package com.kazamatech.kzdesk.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.theme.KzPill
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Kontrol roda mouse yang ringkas: tap panah untuk satu takik, atau geser thumb
 * untuk mengirim satuan roda secara halus ke desktop remote.
 *
 * Thumb berperilaku seperti tuas pegas, bukan slider: posisinya dinyatakan
 * sebagai simpangan dari tengah (-1..1) dan **selalu kembali sendiri ke tengah**
 * begitu jari dilepas, sehingga gesture scroll berikutnya selalu mulai netral
 * dan tidak perlu menarik thumb balik manual.
 */
@Composable
internal fun SessionScrollPill(
    plate: HudPlate,
    scrollSpeed: Float,
    onScrollUnits: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = hudPalette(plate)
    val trackColor = if (plate == HudPlate.NONE) Color(0xA60B0D10) else palette.plate
    val edgeColor = if (plate == HudPlate.NONE) Color(0xBFFFFFFF) else palette.border
    val latestScroll = rememberUpdatedState(onScrollUnits)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Simpangan thumb dari tengah, -1 (paling atas) .. 1 (paling bawah).
    // Sengaja memakai state biasa, bukan Animatable: awaitEachGesture berjalan
    // di AwaitPointerEventScope yang melarang pemanggilan fungsi suspend
    // seperti Animatable.snapTo.
    var deflection by remember { mutableFloatStateOf(0f) }

    // Thumb sengaja berbentuk PIL, bukan lingkaran, dan lebih sempit dari
    // track. Versi lama memakai lingkaran 30dp di dalam track selebar 32dp
    // (44dp - padding 2x6dp) sehingga thumb nyaris menyumbat track dan terbaca
    // sebagai sumbat bulat, bukan tuas. Sekarang 16x36dp dengan sudut 50%,
    // menyisakan ruang di kiri-kanan supaya jelas bisa digeser.
    val trackHeight = 96.dp
    val thumbWidth = 16.dp
    val thumbHeight = 36.dp
    val thumbShape = RoundedCornerShape(percent = 50)
    val maxTravelPx = with(density) { (trackHeight - thumbHeight).toPx() / 2f }

    Column(
        modifier = modifier
            .width(40.dp)
            .clip(KzPill)
            .background(trackColor)
            .border(1.dp, edgeColor, KzPill)
            .padding(horizontal = 5.dp, vertical = 5.dp)
            .semantics {
                contentDescription =
                    kzNow("Kontrol scroll mouse atas dan bawah", "Mouse scroll up and down control")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable { latestScroll.value(120) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                KzIcons.ChevronUp,
                contentDescription = kzNow("Scroll mouse ke atas", "Scroll mouse up"),
                tint = palette.ink,
                modifier = Modifier.size(16.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .pointerInput(scrollSpeed) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val travel = maxTravelPx.coerceAtLeast(1f)
                        val accumulator = ScrollWheelAccumulator()
                        var lastY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val deltaY = change.position.y - lastY
                            if (deltaY != 0f) {
                                deflection = (deflection + deltaY / travel).coerceIn(-1f, 1f)
                                accumulator.consume(deltaY, scrollSpeed)
                                    .takeIf { it != 0 }
                                    ?.let(latestScroll.value)
                                lastY = change.position.y
                                change.consume()
                            }
                        }
                        // Balik sendiri ke tengah, dengan sedikit pantulan agar
                        // terasa seperti tuas fisik.
                        scope.launch {
                            animate(
                                initialValue = deflection,
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) { value, _ -> deflection = value }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .offset { IntOffset(0, (deflection * maxTravelPx).roundToInt()) }
                    .width(thumbWidth)
                    .height(thumbHeight)
                    .clip(thumbShape)
                    .background(palette.ink.copy(alpha = 0.76f))
                    .border(1.dp, edgeColor.copy(alpha = 0.72f), thumbShape),
            )
        }

        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable { latestScroll.value(-120) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                KzIcons.ChevronDown,
                contentDescription = kzNow("Scroll mouse ke bawah", "Scroll mouse down"),
                tint = palette.ink,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
