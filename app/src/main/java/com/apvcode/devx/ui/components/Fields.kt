package com.apvcode.devx.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType

/** A labelled single-line field in the app's style. */
@Composable
fun DevxTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = DevxType.Section, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = DevxType.Body.copy(color = DevxColors.TextPrimary),
            cursorBrush = SolidColor(DevxColors.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clip(shape)
                .border(1.dp, DevxColors.Outline, shape),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = DevxType.Body, color = DevxColors.TextDisabled)
                    }
                    inner()
                }
            },
        )
    }
}
