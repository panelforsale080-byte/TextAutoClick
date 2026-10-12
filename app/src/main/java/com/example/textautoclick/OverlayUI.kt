package com.example.textautoclick

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val GreyBg = Color(0xFFE0E0E0)
val GreyDark = Color(0xFF9E9E9E)
val WhiteSurface = Color.White
val DarkText = Color(0xFF333333)

/** Root overlay: expanded panel OR minimized logo bubble. No close action by design. */
@Composable
fun OverlayUI(
    minimized: Boolean,
    detecting: Boolean,
    keyword: String,
    lastStatus: String,
    onKeywordChange: (String) -> Unit,
    onToggleDetection: () -> Unit,
    onMinimize: () -> Unit,
    onExpand: () -> Unit,
    onDragBy: (Int, Int) -> Unit,
    onFieldFocus: (Boolean) -> Unit
) {
    if (minimized) {
        MinimizedLogo(onExpand = onExpand, onDragBy = onDragBy)
    } else {
        ExpandedPanel(
            detecting, keyword, lastStatus,
            onKeywordChange, onToggleDetection, onMinimize, onDragBy, onFieldFocus
        )
    }
}

@Composable
private fun ExpandedPanel(
    detecting: Boolean,
    keyword: String,
    lastStatus: String,
    onKeywordChange: (String) -> Unit,
    onToggleDetection: () -> Unit,
    onMinimize: () -> Unit,
    onDragBy: (Int, Int) -> Unit,
    onFieldFocus: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = WhiteSurface,
        shadowElevation = 8.dp,
        modifier = Modifier.width(220.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Logo + drag handle row (drag anywhere on the header)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onDragBy(drag.x.toInt(), drag.y.toInt())
                        }
                    }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .background(GreyBg, CircleShape)
                ) { Text("AC", color = DarkText, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(8.dp))
                Text("Text AutoClick", color = DarkText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                label = { Text("text to tap", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFieldFocus(it.isFocused) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onToggleDetection,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (detecting) GreyDark else GreyBg
                    )
                ) {
                    Text(if (detecting) "Stop" else "Start", color = DarkText, fontSize = 12.sp)
                }
                Button(
                    onClick = onMinimize,
                    colors = ButtonDefaults.buttonColors(containerColor = GreyBg)
                ) { Text("Minimize", color = DarkText, fontSize = 12.sp) }
            }

            Text(lastStatus, color = GreyDark, fontSize = 11.sp)
            if (detecting) {
                Text(
                    "tap bubble to reopen · stop from here",
                    color = GreyDark, fontSize = 10.sp
                )
            }
        }
    }
}

/** Small floating logo — the only "dismissed" state. Tap = expand. Draggable.
 *  Manual tap-vs-drag: detectDragGestures eats the tap, so we split them by
 *  movement threshold in a single gesture handler. */
@Composable
private fun MinimizedLogo(onExpand: () -> Unit, onDragBy: (Int, Int) -> Unit) {
    Surface(
        shape = CircleShape,
        color = WhiteSurface,
        shadowElevation = 8.dp,
        modifier = Modifier
            .size(56.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.changedToUp()) {
                            if (!dragged) onExpand()
                            break
                        }
                        val drag = ch.positionChange()
                        if (dragged || drag.getDistance() > 16f) {
                            dragged = true
                            ch.consume()
                            onDragBy(drag.x.toInt(), drag.y.toInt())
                        }
                    }
                }
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .background(GreyBg, CircleShape)
        ) {
            Text("AC", color = DarkText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
