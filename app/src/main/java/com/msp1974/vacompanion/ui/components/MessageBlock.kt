package com.msp1974.vacompanion.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@SuppressLint("DefaultLocale")
@Composable
fun MessageBlock(
    message: String,
    icon: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .zIndex(2f)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) {
                // Prevent propagation of click
            }
    ) {
        Icon(
            imageVector = getIcon(icon),
            contentDescription = icon,
            tint = Color.White,
            modifier = Modifier.padding(end=20.dp).size(50.dp)
        )
        Text(
            text = message,
            color = Color.White,
            fontSize = 30.sp,
            textAlign = TextAlign.Center,
            lineHeight = 100.sp
        )
    }
}

private fun getIcon(name: String): ImageVector {
    return when (name) {
        "nowifi" -> Icons.Filled.WifiOff
        else -> Icons.AutoMirrored.Filled.Note
    }
}

@Preview(apiLevel = 35)
@Composable
fun MessageBlockPreview() {
    MessageBlock(
        modifier = Modifier.background(Color.White),
        message="Preview Test Message",
        icon="nowifi"
    )

}