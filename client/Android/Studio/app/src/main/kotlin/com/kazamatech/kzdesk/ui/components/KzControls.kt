package com.kazamatech.kzdesk.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.material3.Text

/**
 * Kontrol milik KzDesk sendiri — bukan Slider/ProgressIndicator bawaan Material.
 *
 * Alasan: bahasa visual app ini hairlines + bentuk pil, sementara kontrol
 * bawaan Material punya gaya sendiri (warna, ketebalan, animasi) yang selalu
 * kelihatan "asing" di dalam panel kami.
 */

internal fun sliderValueAtFraction(
    fraction: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val start = valueRange.start
    val end = valueRange.endInclusive
    if (!start.isFinite() || !end.isFinite() || end <= start) return start

    val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val raw = start + safeFraction * (end - start)
    if (steps <= 0) return raw

    val stepSize = (end - start) / (steps + 1)
    val index = ((raw - start) / stepSize).roundToInt().coerceIn(0, steps + 1)
    return (start + index * stepSize).coerceIn(start, end)
}

/** Slider: track tipis ber-border, isi aksen, pegangan bulat dan dapat diakses. */
@Composable
fun KzSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Jumlah langkah antara (0 = bebas). Nilai dibulatkan ke langkah terdekat. */
    steps: Int = 0,
) {
    val latest = rememberUpdatedState(onValueChange)
    val range = valueRange.endInclusive - valueRange.start

    Box(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(valueRange, enabled, steps) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun apply(x: Float) {
                        val fraction = if (size.width > 0) x / size.width.toFloat() else 0f
                        latest.value(sliderValueAtFraction(fraction, valueRange, steps))
                    }
                    apply(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        apply(change.position.x)
                        change.consume()
                    }
                }
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    value.coerceIn(valueRange.start, valueRange.endInclusive),
                    valueRange,
                    steps.coerceAtLeast(0),
                )
                setProgress { target ->
                    if (!enabled || range <= 0f) {
                        false
                    } else {
                        val fraction = (target - valueRange.start) / range
                        latest.value(sliderValueAtFraction(fraction, valueRange, steps))
                        true
                    }
                }
            },
    ) {
        val track = MaterialTheme.colorScheme.outline
        val fill = MaterialTheme.colorScheme.primary
        val knob = MaterialTheme.colorScheme.surface
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val h = size.height
            val w = size.width
            val trackH = 6.dp.toPx()
            val radius = CornerRadius(trackH / 2f, trackH / 2f)
            val cy = h / 2f
            drawRoundRect(
                color = track,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(w, trackH),
                cornerRadius = radius,
                style = Stroke(width = 1.2f),
            )
            val frac = if (range <= 0f) 0f
            else ((value - valueRange.start) / range).coerceIn(0f, 1f)
            if (frac > 0f) {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(0f, cy - trackH / 2f),
                    size = Size(w * frac, trackH),
                    cornerRadius = radius,
                )
            }
            val knobR = 8.dp.toPx()
            val kx = (w * frac).coerceIn(knobR, (w - knobR).coerceAtLeast(knobR))
            drawCircle(color = fill, radius = knobR, center = Offset(kx, cy))
            drawCircle(
                color = knob,
                radius = knobR - 2.5f,
                center = Offset(kx, cy),
            )
        }
    }
}

/**
 * Spinner: satu busur yang berputar. Pengganti `CircularProgressIndicator`
 * bawaan supaya tidak ada dua bahasa animasi di satu layar.
 */
@Composable
fun KzSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp,
) {
    val transition = rememberInfiniteTransition(label = "kz-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "kz-spinner-angle",
    )
    Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 260f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** Garis pemisah tipis milik app (bukan HorizontalDivider). */
@Composable
fun KzDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/** Titik kecil sebagai pengganti indikator akhir daftar. */
@Composable
fun KzDot(color: Color, sizeDp: Dp = 6.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(sizeDp)
            .clip(CircleShape)
            .border(1.dp, color, CircleShape),
    )
}

/** Teks kecil ber-huruf renggang untuk label bagian. */
@Composable
fun KzTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
        ),
        maxLines = 1,
    )
}

/** Latar panel kecil dengan radius konsisten (dipakai di beberapa layar). */
@Composable
fun KzInset(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
    ) {
        content()
    }
}
