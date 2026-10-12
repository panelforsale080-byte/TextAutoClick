package com.example.textautoclick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var a11yGranted by mutableStateOf(false)

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_DATA, result.data)
            }
            startForegroundService(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(
                    overlayGranted = overlayGranted,
                    a11yGranted = a11yGranted,
                    onGrantOverlay = {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    },
                    onGrantA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onStart = { startFlow() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
        a11yGranted = AutoClickAccessibilityService.isRunning()
    }

    private fun startFlow() {
        when {
            !overlayGranted -> {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
            !a11yGranted -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else -> {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                requestProjection.launch(mpm.createScreenCaptureIntent())
            }
        }
    }
}

@Composable
fun MainScreen(
    overlayGranted: Boolean,
    a11yGranted: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantA11y: () -> Unit,
    onStart: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = GreyBg) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Text AutoClick", fontSize = 28.sp, color = DarkText)
            Spacer(Modifier.height(8.dp))
            Text(
                "Detects on-screen text via OCR and taps it automatically.",
                fontSize = 13.sp, color = GreyDark
            )
            Spacer(Modifier.height(28.dp))

            Text("Overlay permission: ${if (overlayGranted) "✅" else "❌"}", color = DarkText)
            TextButton(onClick = onGrantOverlay) { Text("Grant overlay", color = GreyDark) }

            Text("Accessibility service: ${if (a11yGranted) "✅" else "❌"}", color = DarkText)
            TextButton(onClick = onGrantA11y) { Text("Open accessibility settings", color = GreyDark) }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = GreyDark)
            ) { Text("Start (grants screen capture)", color = WhiteSurface) }
        }
    }
}
