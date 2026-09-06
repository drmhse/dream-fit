package com.drmhse.dream.fit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText

private val Pulse = Color(0xFFFF453A)
private val Steps = Color(0xFF30D158)
private val Ember = Color(0xFFFF9F0A)
private val Muted = Color(0xFF8E8E93)

const val STEP_GOAL = 10_000

@Composable
fun DreamFitScreen() {
    MaterialTheme {
        AppScaffold {
            ScreenScaffold(timeText = { TimeText() }) {
                val s by WatchState.state.collectAsState()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Step goal rides the bezel: the one number worth a glance
                    // without reading anything.
                    CircularProgressIndicator(
                        progress = { (s.steps.toFloat() / STEP_GOAL).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        startAngle = 291f,
                        endAngle = 249f,
                        strokeWidth = 8.dp,
                        colors = ProgressIndicatorDefaults.colors(
                            indicatorColor = Steps,
                            trackColor = Steps.copy(alpha = 0.18f),
                        ),
                    )
                    Readout(s)
                }
            }
        }
    }
}

@Composable
private fun Readout(s: WatchState.Snapshot) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 46.dp),
    ) {
        Text(
            text = if (s.bpm > 0) "${s.bpm}" else "—",
            color = if (s.bpm > 0) Color.White else Muted,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("BPM", color = Pulse, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(10.dp))

        Text(
            text = "%,d".format(s.steps),
            color = Steps,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text("steps", color = Muted, fontSize = 10.sp)

        if (s.exMin > 0) {
            Spacer(Modifier.height(4.dp))
            Text("${s.exMin} min active", color = Ember, fontSize = 10.sp)
        }

        Spacer(Modifier.height(10.dp))
        LinkPill(s)
    }
}

@Composable
private fun LinkPill(s: WatchState.Snapshot) {
    val (tint, label) = when {
        s.linked -> Steps to "iPhone"
        s.running -> Ember to "searching"
        else -> Muted to "off"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}
