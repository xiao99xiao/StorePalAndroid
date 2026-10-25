package app.storepal.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/** Internal, non-exported presenter. Initialize the SDK in Application.onCreate for process recreation. */
class StorePalActivity : ComponentActivity() {
    private var runtime: StorePalRuntime? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = StorePalFeedback.runtime
        if (runtime == null) { finish(); return }
        enableEdgeToEdge()
        setContent {
            StorePalTheme {
                CompositionLocalProvider(LocalStorePalLanguageTags provides if (Build.VERSION.SDK_INT < 33) intent.getStringExtra("language") else null) {
                if (intent.getStringExtra("feature") == "release") {
                    StorePalWhatsNewScreen(onClose = ::finish, version = intent.getStringExtra("version") ?: runtime!!.appVersion)
                } else StorePalFeedbackScreen(onClose = ::finish)
                }
            }
        }
    }
    override fun onDestroy() {
        if (!isChangingConfigurations) runtime?.presenting?.set(false)
        super.onDestroy()
    }
}

/** The standalone presenter uses system dynamic colors. Embedded screens inherit your own theme. */
@Composable
fun StorePalTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
