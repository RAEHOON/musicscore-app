package com.scorebook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import com.scorebook.core.input.ExternalIntentBus
import com.scorebook.core.input.InputHub
import com.scorebook.navigation.AppRoot
import com.scorebook.ui.theme.ScoreBookTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var inputHub: InputHub
    @Inject lateinit var externalIntents: ExternalIntentBus

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ScoreBookTheme {
                AppRoot()
            }
        }
        // 펜 필기용 저지연 오버레이를 Compose 위에 얹는다(터치는 받지 않음)
        inputHub.attach(findViewById<ViewGroup>(android.R.id.content))
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        inputHub.detach()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        when (inputHub.onTouchEvent(ev, window.decorView)) {
            InputHub.Decision.PASS -> super.dispatchTouchEvent(ev)
            InputHub.Decision.CONSUME -> true
            InputHub.Decision.CANCEL_THEN_CONSUME -> {
                cancelComposeGesture(ev)
                true
            }
        }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (inputHub.onGenericMotionEvent(ev)) cancelComposeGesture(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inputHub.onKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun cancelComposeGesture(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev.downTime, ev.eventTime, MotionEvent.ACTION_CANCEL, ev.x, ev.y, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val type = intent.type.orEmpty()
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        if (type.startsWith("image/")) {
            externalIntents.offerImages(uris)
        } else {
            externalIntents.offerPdfs(uris, open = intent.action == Intent.ACTION_VIEW && uris.size == 1)
        }
    }
}
