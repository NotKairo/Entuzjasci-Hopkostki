package app.flux.ui.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.flux.ui.design.FluxColors
import app.flux.ui.design.FluxIcon
import app.flux.ui.design.FluxIconView
import app.flux.ui.design.FluxType

/** A scrolling page with the standard margins. System-bar insets are handled by the Scaffold above it. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(title, style = FluxType.Title.copy(fontSize = 28.sp), color = FluxColors.OnSurface)
        if (subtitle != null) Text(subtitle, style = FluxType.Body, color = FluxColors.OnSurfaceMuted)
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = FluxColors.Surface),
        border = BorderStroke(1.dp, FluxColors.Stroke),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = FluxType.Body.copy(fontWeight = FontWeight.SemiBold), color = FluxColors.OnSurface)
            content()
        }
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = FluxType.Body, color = if (enabled) FluxColors.OnSurface else FluxColors.OnSurfaceFaint)
            if (subtitle != null) Text(subtitle, style = FluxType.Caption, color = FluxColors.OnSurfaceMuted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = FluxColors.Blue, checkedThumbColor = Color.White),
        )
    }
}

/**
 * Slider that reports live values while dragging ([onLive], used for the in-app preview) and commits
 * once on release ([onCommit]) so the settings file isn't rewritten on every pixel of movement.
 */
@Composable
fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onLive: (Float) -> Unit = {},
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) local else value
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = FluxType.Body, color = FluxColors.OnSurface)
            Text(valueLabel, style = FluxType.Body, color = FluxColors.OnSurfaceMuted)
        }
        Slider(
            value = shown,
            onValueChange = { dragging = true; local = it; onLive(it) },
            onValueChangeFinished = { dragging = false; onCommit(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = FluxColors.Blue, inactiveTrackColor = Color.White.copy(alpha = 0.16f)),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), style = FluxType.Caption) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = FluxColors.Blue.copy(alpha = 0.28f),
                    selectedLabelColor = FluxColors.OnSurface,
                    labelColor = FluxColors.OnSurfaceMuted,
                ),
            )
        }
    }
}

@Composable
fun StatusPill(text: String, good: Boolean) {
    val color = if (good) FluxColors.Charging else FluxColors.Warning
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = FluxType.Label, color = color)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(44.dp), shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = FluxColors.Blue, contentColor = Color(0xFF00102B)),
    ) { Text(text, style = FluxType.BodyCompact) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(44.dp), shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, FluxColors.StrokeStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = FluxColors.OnSurface),
    ) { Text(text, style = FluxType.BodyCompact) }
}

@Composable
fun Hint(text: String, icon: FluxIcon = FluxIcon.Info) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FluxIconView(icon, FluxColors.OnSurfaceFaint, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = FluxType.Caption, color = FluxColors.OnSurfaceMuted)
    }
}
