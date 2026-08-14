package com.muse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.design.LocalPalette
import com.muse.design.LocalMuseStyle
import com.muse.llm.DEFAULT_BASE_URL
import com.muse.llm.MODEL_FLASH
import com.muse.llm.MODEL_PRO

@Composable
fun OnboardingScreen(onContinue: (apiKey: String, model: String, baseUrl: String) -> Unit) {
    val palette = LocalPalette.current
    val style = LocalMuseStyle.current
    var apiKey by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(DEFAULT_BASE_URL) }
    var model by rememberSaveable { mutableStateOf(MODEL_FLASH) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.crust)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Muse",
            color = palette.mauve,
            fontSize = if (style.isClaude) 40.sp else 36.sp,
            fontFamily = style.brandSerif,
            fontWeight = if (style.isClaude) FontWeight.Medium else FontWeight.SemiBold,
            letterSpacing = if (style.isClaude) 0.6.sp else 0.sp,
        )
        Text("暖色、慢慢来。", color = palette.subtext0, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "跑在 Android 上的个人 Agent。默认 Model 是 DeepSeek V4 Flash，需要时再切 Pro。",
            color = palette.subtext0,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(28.dp))
        SectionLabel("API Key")
        MuseField(
            value = apiKey,
            onValueChange = { apiKey = it; error = null },
            placeholder = "sk-…",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        SectionLabel("Model")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MODEL_FLASH to "Flash", MODEL_PRO to "Pro").forEach { (id, label) ->
                FilterChip(
                    selected = model == id,
                    onClick = { model = id },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.mauve,
                        selectedLabelColor = palette.base,
                        containerColor = palette.base,
                        labelColor = palette.text,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("Base URL")
        MuseField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = palette.red, fontSize = 14.sp)
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (apiKey.isBlank()) {
                    error = "API Key 不能为空。"
                } else {
                    onContinue(apiKey.trim(), model, baseUrl.trim())
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.mauve,
                contentColor = palette.base,
            ),
        ) {
            Text("开始使用", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
