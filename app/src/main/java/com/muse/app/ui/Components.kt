package com.muse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.design.LocalPalette
import com.muse.design.MuseRadius

@Composable
fun MuseField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    val palette = LocalPalette.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = TextStyle(
            color = palette.text,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        cursorBrush = SolidColor(palette.mauve),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MuseRadius))
                    .background(palette.base)
                    .border(1.dp, palette.surface1, RoundedCornerShape(MuseRadius))
                    .heightIn(min = 48.dp, max = 140.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = palette.overlay0, fontSize = 16.sp)
                }
                inner()
            }
        },
    )
}

@Composable
fun SectionLabel(text: String) {
    val palette = LocalPalette.current
    Text(
        text = text,
        color = palette.subtext0,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
fun EmptyHint(title: String, body: String) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = palette.subtext0, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

fun modelLabel(model: String): String = when {
    model.contains("pro", ignoreCase = true) -> "Pro"
    else -> "Flash"
}
