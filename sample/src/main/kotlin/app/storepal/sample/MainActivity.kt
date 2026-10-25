package app.storepal.sample

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.storepal.android.*

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.STOREPAL_API_KEY.isBlank()) {
            StorePalFeedback.initialize(this, DemoService(this), "offline-example", "alex@example.com", "Alex")
            StorePalFeedback.rememberConversation("00000000-0000-4000-8000-000000000001")
        } else StorePalFeedback.configure(this, BuildConfig.STOREPAL_API_KEY)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StorePalTheme {
                StorePalWhatsNewOnUpdate()
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.sample_app_name), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(if (BuildConfig.STOREPAL_API_KEY.isBlank()) R.string.sample_offline else R.string.sample_connected),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        FeedbackButton()
                        OutlinedButton(onClick = { StorePalWhatsNew.show(this@MainActivity) }) { Text(stringResource(app.storepal.android.R.string.sp_whats_new)) }
                    }
                }
            }
        }
    }
}
