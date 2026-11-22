package io.github.jellyforgedev.widgora.widget.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Surface
import io.github.jellyforgedev.widgora.WidgoraApplication
import io.github.jellyforgedev.widgora.core.data.StorageWriteResult
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.core.data.WidgetSettingsRepository
import io.github.jellyforgedev.widgora.design.WidgoraTheme
import io.github.jellyforgedev.widgora.widget.WidgetUpdater
import kotlinx.coroutines.launch

/**
 * The configuration step a host runs when one of WIDGORA's widgets is placed.
 *
 * ### The result contract, which is the part a host depends on
 *
 * `RESULT_CANCELED` is set **first**, before anything else happens, and it carries the widget's
 * identifier. That ordering is the whole contract: every way out of this screen that is not a
 * successful save — the back key, a refused request, the system reclaiming the activity — leaves
 * that result standing, and a host reading it removes the widget it had provisionally placed.
 * `RESULT_OK` is sent on one path only, and only after a write that reported success, so a host is
 * never told a widget is configured while nothing reached the file.
 *
 * ### Why it is exported, and what that means
 *
 * It has to be: the party that starts it is a launcher in another process. So the identifier it is
 * handed is checked against the widget manager rather than believed — see [WidgetConfigRequests]
 * for what that defends against and what it does not need to.
 *
 * ### One repository, not a second one
 *
 * The settings store is taken from the application. A second live store over the same file raises,
 * loudly and at runtime, and this screen is exactly where that temptation arises: it is the first
 * thing other than the widget update that writes to that file.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Before anything that could fail, return early or be killed.
        setResult(RESULT_CANCELED, resultIntent(appWidgetId))

        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
        val request = WidgetConfigRequests.of(
            appWidgetId = appWidgetId,
            providerPackageName = info?.provider?.packageName,
            providerClassName = info?.provider?.className,
            ownPackageName = packageName,
        )

        // Nothing is said about why. A caller that had no business asking learns nothing from a
        // message, and a user never reaches this path: a host only starts this screen for a widget
        // it has just bound.
        if (request !is WidgetConfigRequest.Configure) {
            finish()
            return
        }

        val widgora = application as WidgoraApplication
        val holder = WidgetConfigStateHolder(
            appWidgetId = request.appWidgetId,
            kind = request.kind,
            instances = widgora.instanceStore.instances,
            settings = RepositoryWidgetSettingsStore(widgora.widgetSettings),
        )

        setContent {
            WidgoraTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    WidgetConfigScreen(
                        holder = holder,
                        onSaved = { finishConfigured(request.appWidgetId) },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    /**
     * Reports success, draws the widget once, and closes.
     *
     * The push happens here rather than being left to the host, because what a host does after a
     * configuration step is not documented either way. Without it a freshly configured widget
     * would keep whatever picture it had until the next retrieval, which for a widget just placed
     * is the line saying nothing has loaded.
     *
     * The identifier is handed to the push as one to keep. The sweep that removes settings for
     * widgets nobody placed runs inside it, and this is the one moment where a stored entry can
     * legitimately be newer than what the widget manager reports — so the entry just written is
     * named as an exception rather than left to a race.
     */
    private fun finishConfigured(appWidgetId: Int) {
        setResult(RESULT_OK, resultIntent(appWidgetId))
        lifecycleScope.launch {
            WidgetUpdater.pushFromStorage(this@WidgetConfigActivity, justConfigured = appWidgetId)
        }
        finish()
    }

    /**
     * The identifier goes back with both results.
     *
     * The platform requires it with `RESULT_OK`. It is sent with the cancellation too, which is
     * what the platform's own guidance for this step does, so that a host can tell which of
     * possibly several pending placements was abandoned.
     */
    private fun resultIntent(appWidgetId: Int): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

/** Passes everything through to the repository; it adds no behaviour of its own. */
internal class RepositoryWidgetSettingsStore(
    private val repository: WidgetSettingsRepository,
) : WidgetSettingsStore {

    override suspend fun settingsFor(appWidgetId: Int): WidgetSettings? =
        repository.settingsFor(appWidgetId)

    override suspend fun save(
        appWidgetId: Int,
        settings: WidgetSettings,
    ): StorageWriteResult = repository.save(appWidgetId, settings)
}
