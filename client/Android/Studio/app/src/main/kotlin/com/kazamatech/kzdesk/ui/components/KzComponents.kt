package com.kazamatech.kzdesk.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import com.kazamatech.kzdesk.ui.theme.KzDisplay
import com.kazamatech.kzdesk.ui.theme.KzPill

/**
 * Primitif KzDesk: permukaan rounded dengan garis lembut untuk mengelompokkan
 * konten. Outline yang lebih tegas tetap dipakai pada input dan kontrol aktif.
 */

@Composable
fun KzCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f), shape)
            .padding(padding),
        content = content,
    )
}

/** Tombol aksi rounded; `primary` = isi penuh; `false` = outline saja. */
@Composable
fun KzPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        primary -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .clip(KzPill)
            .background(container)
            .then(
                if (primary && enabled) Modifier
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, KzPill)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (compact) 16.dp else 20.dp,
                vertical = if (compact) 9.dp else 13.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Tombol ikon; bisa berupa lingkaran berisi (HUD) atau aksi datar tanpa chip. */
@Composable
fun KzIconPill(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    active: Boolean = false,
    flat: Boolean = false,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = when {
        flat && active -> MaterialTheme.colorScheme.primary
        flat -> MaterialTheme.colorScheme.onSurfaceVariant
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(
                if (flat) Modifier
                else Modifier
                    .background(bg)
                    .border(
                        1.dp,
                        if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                        shape,
                    ),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun KzSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = KzDisplay,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.4.sp,
    )
}

/** Baris daftar: judul + subjudul opsional + trailing. */
@Composable
fun KzRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    leadingTint: Color? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = leadingTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Toggle custom: track pill + knob, tanpa komponen bawaan OS. */
@Composable
fun KzSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val trackColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "track",
    )
    val knobColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "knob",
    )
    val knobOffset by animateDpAsState(if (checked) 23.dp else 3.dp, label = "knobOffset")
    // Area sentuh diperbesar (52x38) tanpa mengubah visual track 46x28:
    // pemilik mengeluh toggle kurang presisi saat ditekan.
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 38.dp),
        contentAlignment = Alignment.Center,
    ) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(KzPill)
            .background(trackColor)
            .border(
                1.dp,
                if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.outlineVariant,
                KzPill,
            )
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobOffset, top = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(knobColor),
        )
    }
    }
}

@Composable
fun KzToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        KzSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Segmented pill: pilihan tunggal dari beberapa opsi. */
@Composable
fun KzSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Input teks: label kecil, permukaan jelas, outline membesar dan beraksen saat fokus. */
@Composable
fun KzField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Toggle lihat/sembunyikan password hidup otomatis di sini supaya SEMUA
    // field password di app (perangkat, gateway, NLA) punya perilaku yang
    // sama — dulu password cuma bisa diketik buta.
    var pwVisible by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fieldOutline = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val fieldOutlineWidth = if (focused) 1.5.dp else 1.dp
    Column(modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = KzDisplay,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            interactionSource = interactionSource,
            visualTransformation = if (isPassword && !pwVisible) PasswordVisualTransformation()
            else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(KzPill)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(fieldOutlineWidth, fieldOutline, KzPill)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && hint != null) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                    if (isPassword) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .clickable { pwVisible = !pwVisible }
                                .padding(6.dp),
                        ) {
                            Icon(
                                if (pwVisible) KzIcons.EyeOff else KzIcons.Eye,
                                contentDescription = if (pwVisible) {
                                    "Sembunyikan password (hide password)"
                                } else {
                                    "Lihat password (show password)"
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    trailing?.invoke()
                }
            },
        )
    }
}

/** Bar header minimal: tombol kembali + judul + aksi kanan. */
@Composable
fun KzTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onBack != null) {
            KzIconPill(
                icon = KzIcons.ChevronLeft,
                onClick = onBack,
                size = 44.dp,
                flat = true,
                contentDescription = "Kembali",
            )
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 4.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions?.invoke()
    }
}

@Composable
fun KzStatusDot(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun KzWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
) {
    Text(
        "KzDesk",
        modifier = modifier,
        fontFamily = KzDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = fontSize,
        letterSpacing = (-0.4).sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * Mark KzDesk dalam Compose (geometri sama dengan ikon launcher):
 * layar + kursor di dalamnya. Satu warna, jadi bisa di-tint sesuai tema.
 */
@Composable
fun KzLogo(
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.minDimension / 48f
        fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * s, y * s)

        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.7f * s)
        drawRoundRect(
            color = tint,
            topLeft = point(6.8f, 13.4f),
            size = androidx.compose.ui.geometry.Size(34.4f * s, 20.6f * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * s),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = point(19.5f, 40.2f),
            end = point(28.5f, 40.2f),
            strokeWidth = 2.7f * s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = point(24f, 34.2f),
            end = point(24f, 40.2f),
            strokeWidth = 2.7f * s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        val cursor = androidx.compose.ui.graphics.Path().apply {
            val xs = floatArrayOf(18.6f, 18.6f, 21.7f, 23.9f, 26.2f, 24f, 28.1f)
            val ys = floatArrayOf(17.6f, 28.4f, 25.3f, 30.1f, 29f, 24.2f, 23.8f)
            val first = point(xs[0], ys[0])
            moveTo(first.x, first.y)
            for (i in 1 until xs.size) {
                val q = point(xs[i], ys[i])
                lineTo(q.x, q.y)
            }
            close()
        }
        drawPath(cursor, tint)
    }
}

/**
 * Shell dialog milik KzDesk dengan bidang datar dan tanpa border dekoratif.
 * Scrim bisa ditutup untuk prompt kredensial/sertifikat yang tidak boleh ditutup begitu saja.
 */
@Composable
fun KzOverlay(
    title: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    maxWidth: Dp = 440.dp,
    origin: Alignment = Alignment.Center,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Muncul & pergi halus: kartu membesar dari titik tombol pemicu (origin)
    // seperti keluar dari tombolnya; scrim ikut menggelap dan menerang.
    var shown by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(leaving) {
        if (leaving) {
            shown = false
            delay(210)
            onDismiss?.invoke()
        }
    }
    val cardScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.88f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(170),
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (shown) 0.26f else 0f,
        animationSpec = tween(240),
    )
    val popOrigin = when (origin) {
        Alignment.TopStart -> TransformOrigin(0f, 0f)
        Alignment.TopCenter -> TransformOrigin(0.5f, 0f)
        Alignment.TopEnd -> TransformOrigin(1f, 0f)
        Alignment.BottomStart -> TransformOrigin(0f, 1f)
        Alignment.BottomCenter -> TransformOrigin(0.5f, 1f)
        Alignment.BottomEnd -> TransformOrigin(1f, 1f)
        else -> TransformOrigin(0.5f, 0.5f)
    }
    fun requestDismiss() {
        if (!leaving && onDismiss != null) leaving = true
    }
    Box(
        Modifier.fillMaxSize().zIndex(50f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .pointerInput(onDismiss) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var moved = false
                        val slop = viewConfiguration.touchSlop
                        var total = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                total += (change.position - change.previousPosition).getDistance()
                                if (total > slop) moved = true
                                change.consume()
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (!moved) requestDismiss()
                    }
                },
        )
        Column(
            modifier
                .padding(24.dp)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                    alpha = cardAlpha
                    transformOrigin = popOrigin
                }
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title.uppercase(),
                fontFamily = KzDisplay,
                fontSize = 11.sp,
                letterSpacing = 1.3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

/** Dialog teks sederhana: judul + isi (+ opsional tombol kedua). */
@Composable
fun KzDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    mono: Boolean = false,
) {
    KzOverlay(title = title, onDismiss = onDismiss) {
        if (mono) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                body.lineSequence().forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dismissLabel != null && onDismiss != null) {
                KzPillButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            KzPillButton(
                text = confirmLabel,
                onClick = onConfirm,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
