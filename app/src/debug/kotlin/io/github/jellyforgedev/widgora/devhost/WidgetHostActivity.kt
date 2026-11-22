package io.github.jellyforgedev.widgora.devhost

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.jellyforgedev.widgora.R
import io.github.jellyforgedev.widgora.design.WidgoraColors
import io.github.jellyforgedev.widgora.design.WidgoraFocus
import io.github.jellyforgedev.widgora.design.WidgoraOverscan
import io.github.jellyforgedev.widgora.design.WidgoraShapes
import io.github.jellyforgedev.widgora.design.WidgoraSpacing
import io.github.jellyforgedev.widgora.design.WidgoraText
import io.github.jellyforgedev.widgora.widget.CountsWidgetProvider
import io.github.jellyforgedev.widgora.widget.WidgetSizes

/**
 * A widget host, for looking at WIDGORA's widget on a television that has none.
 *
 * ### Why this exists
 *
 * A widget is only ever seen inside a host, and the launcher on the intended television does not
 * host widgets. Without this, the widget could be built, registered and tested in every way
 * except the one that matters — somebody looking at it on a television and pressing a button on
 * a remote.
 *
 * ### Why it is only in the debug build
 *
 * Hosting widgets needs `BIND_APPWIDGET`, which is not a permission a dashboard application has
 * any business holding. The permission and this activity are declared in the debug source set
 * alone, so neither reaches a release build, and `SECURITY.md`'s statement that WIDGORA asks for
 * `INTERNET` and `ACCESS_NETWORK_STATE` and nothing else stays true of what is shipped.
 *
 * ### What it deliberately does not do
 *
 * It does not style the widget, size it to taste, or draw anything around it beyond what is
 * needed to operate it. A harness that improves on the thing it is testing reports on the
 * harness. Both sizes come from the provider's own metadata; see [HostSizes].
 */
class WidgetHostActivity : Activity() {

    private val widgetManager: AppWidgetManager by lazy { AppWidgetManager.getInstance(this) }

    /**
     * The host, with an identifier fixed in code.
     *
     * It has to be stable across starts: the system files this host's widgets under it, and a
     * changing value would orphan the record from the run before.
     */
    private val host: AppWidgetHost by lazy { AppWidgetHost(this, HOST_ID) }

    /**
     * Every provider of this package, read from the package manager rather than named in code.
     *
     * The previous version named one provider as a constant, which was correct while there was
     * one. Reading the installed list means the second and third widget types appear here the day
     * they are declared, without this file knowing anything about them — which is the only way a
     * harness can be useful for a type that does not exist yet.
     *
     * Sorted by class name so the order is the same on every start: an index is stored across
     * runs, and an index into a list the platform orders at its own discretion would point at a
     * different type each time.
     */
    private val providers: List<AppWidgetProviderInfo> by lazy {
        widgetManager.getInstalledProvidersForPackage(packageName, null)
            .sortedBy { info -> info.provider.className }
    }

    private val store by lazy { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private lateinit var buttonRow: LinearLayout
    private lateinit var typeButton: Button
    private lateinit var sizeButton: Button
    private lateinit var configureButton: Button
    private lateinit var removeButton: Button
    private lateinit var placeButton: Button

    private var hostView: AppWidgetHostView? = null
    private var sizes: List<HostSize> = emptyList()
    private var sizeIndex = 0

    /** Which of [providers] the next placement uses. Stored, so a chosen type survives a start. */
    private var providerIndex = 0

    /** The widget currently attached, or the platform's "no widget" marker. */
    private var attachedAppWidgetId = HostWidgetIds.NO_APP_WIDGET_ID

    /**
     * An identifier that has been allocated and not yet bound to anything.
     *
     * Held so that it can be given back. An identifier left allocated is a widget the system
     * believes exists somewhere, and nothing on any screen would ever say so.
     */
    private var unboundAppWidgetId = HostWidgetIds.NO_APP_WIDGET_ID

    /**
     * The result of the system's own permission dialog, which is offered as a fallback.
     *
     * A fallback rather than the route, because it is a touch dialog and this runs on a
     * television. The shell command named on screen is the reliable way in; this is here in case
     * the dialog turns out to be operable.
     *
     * The older result callback is used rather than the Activity Result API, because this screen
     * is a plain activity. It is a plain activity because it has to override `dispatchKeyEvent`
     * to move the focus, and on `ComponentActivity` that method is restricted to AndroidX's own
     * libraries — a restriction lint enforces, and rightly. A debug tool with no Compose in it
     * has no other use for that base class.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_BIND -> if (resultCode == RESULT_OK) {
                configureThenAttach(unboundAppWidgetId)
            } else {
                releaseUnbound()
                showGrantHint()
            }

            // The configuration step of a freshly bound widget. A real launcher attaches only on
            // RESULT_OK and removes the placement otherwise, and this host does the same — that
            // asymmetry is the part of the contract worth exercising, and a harness that attached
            // regardless would never catch a configuration step that reports failure.
            REQUEST_CONFIGURE -> if (resultCode == RESULT_OK) {
                attach(unboundAppWidgetId)
            } else {
                releaseUnbound()
                status.text = getString(R.string.debug_host_configuration_cancelled)
                showControls()
            }

            // A reconfiguration of a widget that is already attached. Nothing is released either
            // way: the widget exists and keeps existing. The application pushes its own update
            // after a successful save, so there is nothing to redraw from here.
            REQUEST_RECONFIGURE -> status.text = getString(
                if (resultCode == RESULT_OK) {
                    R.string.debug_host_reconfigured
                } else {
                    R.string.debug_host_configuration_cancelled
                },
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        prepareWidget()
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        // Only an identifier that never became a widget is given back. One that did belongs to
        // the next run of this host, which is the whole point of storing it.
        releaseUnbound()
        super.onDestroy()
    }

    // --- Obtaining a widget --------------------------------------------------------------------

    private fun prepareWidget() {
        providerIndex = store.getInt(KEY_PROVIDER_INDEX, 0).coerceIn(0, lastProviderIndex())

        if (providers.isEmpty()) {
            status.text = getString(R.string.debug_host_no_provider)
            showControls()
            return
        }

        val stored = store.getInt(KEY_APP_WIDGET_ID, HostWidgetIds.NO_APP_WIDGET_ID)
            .takeIf { it != HostWidgetIds.NO_APP_WIDGET_ID }
        val boundTo = stored?.let { widgetManager.getAppWidgetInfo(it)?.provider?.className }

        when (val decision = HostWidgetIds.decide(stored, boundTo, ourProviderClassNames())) {
            is HostWidgetDecision.Reuse -> {
                // The stored widget decides which type is on screen, not the stored index: the
                // widget is the thing that exists, and an index that disagreed with it would make
                // the type label describe something other than what is being shown.
                providers.indexOfFirst { it.provider.className == boundTo }
                    .takeIf { it >= 0 }
                    ?.let { index -> providerIndex = index }
                attach(decision.appWidgetId)
            }

            is HostWidgetDecision.ReleaseAndAllocate -> {
                host.deleteAppWidgetId(decision.staleAppWidgetId)
                store.edit().remove(KEY_APP_WIDGET_ID).apply()
                allocateAndBind()
            }

            HostWidgetDecision.Allocate -> allocateAndBind()
        }
    }

    private fun allocateAndBind() {
        val provider = providers.getOrNull(providerIndex)?.provider ?: return
        val appWidgetId = host.allocateAppWidgetId()
        unboundAppWidgetId = appWidgetId

        if (widgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)) {
            configureThenAttach(appWidgetId)
            return
        }

        // Binding is a privileged act, and an ordinary application is refused it until the user
        // or the shell says otherwise. The command goes on the screen because that is where
        // somebody standing in front of a television will look for it, and the controls are shown
        // so that the screen is still operable once they have run it.
        showGrantHint()
        showControls()

        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
        }
        runCatching { startActivityForResult(intent, REQUEST_BIND) }
    }

    /**
     * Runs the provider's configuration step, if it declares one, and attaches only on success.
     *
     * This is the sequence a real launcher follows and the reason this host is worth having: bind,
     * configure, attach — and on a cancelled configuration give the identifier back rather than
     * attach a widget the user declined to set up. A provider that declares no configuration
     * activity is attached straight away, which is what the platform does too.
     */
    private fun configureThenAttach(appWidgetId: Int) {
        val configure = widgetManager.getAppWidgetInfo(appWidgetId)?.configure
        if (configure == null) {
            attach(appWidgetId)
            return
        }

        status.text = getString(R.string.debug_host_configuring)
        val started = runCatching {
            host.startAppWidgetConfigureActivityForResult(
                this,
                appWidgetId,
                0,
                REQUEST_CONFIGURE,
                configureOptions(),
            )
        }.isSuccess

        // A declared configuration activity that cannot be started is a defect in the
        // application, not a reason to keep a widget nobody configured.
        //
        // Worth knowing about this check, from the television: it did **not** catch the blocked
        // start. The call returned normally and the refusal arrived later as `RESULT_CANCELED`,
        // which is indistinguishable from a user who pressed back — so the screen said the step
        // had been cancelled, which was true of the result and false about what happened. Nothing
        // here can tell the two apart; what closes that gap is the test that insists the step
        // actually appears. See [configureOptions].
        if (!started) {
            releaseUnbound()
            status.text = getString(R.string.debug_host_configuration_unavailable)
            showControls()
        }
    }

    private fun attach(appWidgetId: Int) {
        val info = widgetManager.getAppWidgetInfo(appWidgetId)
        if (info == null) {
            status.text = getString(R.string.debug_host_no_info)
            showControls()
            return
        }

        unboundAppWidgetId = HostWidgetIds.NO_APP_WIDGET_ID
        attachedAppWidgetId = appWidgetId
        store.edit()
            .putInt(KEY_APP_WIDGET_ID, appWidgetId)
            .putInt(KEY_PROVIDER_INDEX, providerIndex)
            .apply()

        val view = host.createView(this, appWidgetId, info)
        hostView = view
        frame.removeAllViews()
        frame.addView(view)

        sizes = sizesFor(info)
        sizeIndex = 0
        applySize()

        showControls()

        // Every layout of the hosted widget is reported, because that is the moment at which the
        // numbers that matter exist. See [report].
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            report(view)
            // A re-applied widget is a new view tree, and the focus does not follow it. Asking
            // again is what keeps the screen operable after an update or a size change; the
            // guard is there so this never takes the focus off the button.
            if (currentFocus == null) focusWidget()
        }

        view.isFocusable = false
        view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // Posted because a view has no size until it has been laid out, and an unlaid view
        // cannot take the focus.
        view.post { focusWidget() }
    }

    // --- Navigation ----------------------------------------------------------------------------

    /**
     * The host moves the focus itself, rather than asking the views to remember how.
     *
     * The previous version set `nextFocusUpId` on the view inside the hosted widget. It worked
     * once and then stopped — on a television the button was reachable the first time the screen
     * opened and never again. Applying `RemoteViews` builds a new view tree: the object that
     * carried the wiring is replaced, and the wiring goes with it. Every update push and every
     * size change does that, so the arrangement could only ever survive until the first one.
     *
     * Deciding it here instead costs two key codes and cannot be undone by anything the widget
     * does, however often it is rebuilt. The diagnostic log prints `nextFocusUpId` after each
     * layout, which is where that explanation can be checked rather than believed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && navigate(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Up leaves the widget for the controls, down returns.
     *
     * Only those two directions are taken over. Left and right inside the row are left to the
     * platform's own focus search, which handles a horizontal row of siblings correctly; taking
     * them as well would mean reimplementing something that already works, and getting the ends
     * of the row wrong.
     */
    private fun navigate(keyCode: Int): Boolean {
        val firstControl = visibleControls().firstOrNull() ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> focusIsInWidget() && firstControl.requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> focusIsInControls() && focusWidget()
            else -> false
        }
    }

    private fun visibleControls(): List<Button> =
        listOf(typeButton, sizeButton, configureButton, removeButton, placeButton)
            .filter { button -> button.visibility == View.VISIBLE }

    private fun focusIsInControls(): Boolean = currentFocus?.let { focused ->
        visibleControls().any { button -> button === focused }
    } ?: false

    private fun focusIsInWidget(): Boolean {
        val hosted = hostView ?: return false
        var node: View? = currentFocus
        while (node != null) {
            if (node === hosted) return true
            node = node.parent as? View
        }
        return false
    }

    /**
     * Puts the focus on the widget's own root, not on the box around it.
     *
     * The click belongs to that root — it is the view the pending intent was set on — so if the
     * container held the focus instead, the centre key would have nothing to press.
     *
     * Reaching inside the hosted view is possible only because this host and the widget are the
     * same application in the same process. A real host could not do it, which is worth knowing:
     * if the widget ever needs this, that is a finding about the widget and not about this
     * screen.
     */
    private fun focusWidget(): Boolean {
        val hosted = hostView ?: return false
        val target = hosted.findViewById<View>(R.id.widget_root) ?: hosted
        return target.requestFocus()
    }

    // --- Diagnosis -----------------------------------------------------------------------------

    /**
     * What the widget was actually given, and what it actually needed, after every layout.
     *
     * This exists because reasoning failed twice. The widget was cut off on a television while a
     * measurement test on an emulator passed, and no amount of argument settled why; the numbers
     * did, in one run. The host's own padding is the first line printed for that reason — it is
     * the figure that turned out to be missing from every calculation.
     *
     * Nothing from the widget's content is logged. Lines are named by their resource identifier
     * and described by their measurements, so the log carries no instance name, no monitor name
     * and no value.
     */
    private fun report(hosted: AppWidgetHostView) {
        val density = resources.displayMetrics.density
        fun dp(px: Int): String = "%.1f".format(px / density)

        Log.i(TAG, "--- layout ---")
        Log.i(
            TAG,
            "fontScale=${resources.configuration.fontScale} density=$density " +
                "screen=${resources.displayMetrics.widthPixels}x" +
                "${resources.displayMetrics.heightPixels}px",
        )
        Log.i(
            TAG,
            "hostView ${hosted.width}x${hosted.height}px " +
                "(${dp(hosted.width)}x${dp(hosted.height)}dp) padding=" +
                "${hosted.paddingLeft}/${hosted.paddingTop}/${hosted.paddingRight}/" +
                "${hosted.paddingBottom}px",
        )

        val root = hosted.findViewById<View>(R.id.widget_root)
        if (root == null) {
            Log.w(TAG, "widget_root is not in the hosted view")
            return
        }
        Log.i(
            TAG,
            "widget_root ${root.width}x${root.height}px " +
                "(${dp(root.width)}x${dp(root.height)}dp) padding=" +
                "${root.paddingLeft}/${root.paddingTop}/${root.paddingRight}/" +
                "${root.paddingBottom}px focusable=${root.isFocusable} " +
                "hasFocus=${root.hasFocus()} nextFocusUpId=${root.nextFocusUpId}",
        )

        for (line in visibleTextViews(root)) {
            val needed = line.layout?.height ?: 0
            val metrics = line.paint.fontMetricsInt
            Log.i(
                TAG,
                "${entryName(line)} assigned=${line.height}px needed=${needed}px " +
                    "fits=${line.height >= needed} textSize=${line.textSize.toInt()}px " +
                    "ascent=${metrics.ascent} descent=${metrics.descent} " +
                    "lineHeight=${line.lineHeight} ellipsised=" +
                    "${line.layout?.getEllipsisCount(0) ?: 0}",
            )
        }
        Log.i(
            TAG,
            "button ${sizeButton.width}x${sizeButton.height}px needed=" +
                "${sizeButton.layout?.width ?: 0}x${sizeButton.layout?.height ?: 0}px " +
                "padding=${sizeButton.paddingLeft}/${sizeButton.paddingTop}/" +
                "${sizeButton.paddingRight}/${sizeButton.paddingBottom}px " +
                "ellipsised=${sizeButton.layout?.getEllipsisCount(0) ?: 0}",
        )
    }

    private fun entryName(view: View): String = runCatching {
        resources.getResourceEntryName(view.id)
    }.getOrNull() ?: "unnamed"

    private fun visibleTextViews(view: View): List<TextView> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is TextView -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { index ->
            visibleTextViews(view.getChildAt(index))
        }

        else -> emptyList()
    }

    private fun releaseUnbound() {
        if (unboundAppWidgetId == HostWidgetIds.NO_APP_WIDGET_ID) return
        host.deleteAppWidgetId(unboundAppWidgetId)
        unboundAppWidgetId = HostWidgetIds.NO_APP_WIDGET_ID
    }

    private fun showGrantHint() {
        status.text = getString(R.string.debug_host_bind_refused, grantCommand())
    }

    /**
     * The command that grants this package the right to bind widgets, with its own name in it.
     *
     * No `--user` argument. The shell tool defaults to the system user, which is the one that
     * matters here; `--user current` names a pseudo user the grant does not resolve, and it
     * silently sets nothing at all. Checked on two devices running Android 14: without the
     * argument the grant appears in `dumpsys appwidget`, with `--user current` the grant list
     * stays empty.
     */
    private fun grantCommand(): String =
        "adb shell appwidget grantbind --package $packageName"

    // --- Size ------------------------------------------------------------------------------------

    /**
     * The two sizes, read from the provider.
     *
     * `AppWidgetProviderInfo` reports its sizes in pixels — the declaration is written in dp and
     * resolved against the device's density when the package is parsed — so they are converted
     * back here. The size in use is printed on screen, which is what makes that claim checkable
     * by looking rather than by argument.
     */
    private fun sizesFor(info: AppWidgetProviderInfo): List<HostSize> {
        val metrics = resources.displayMetrics
        val horizontalMargin = WidgoraOverscan.Horizontal.value.toInt()
        val verticalMargin = WidgoraOverscan.Vertical.value.toInt()

        val maxWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.maxResizeWidth else 0
        val maxHeight =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.maxResizeHeight else 0

        return HostSizes.offered(
            // The resize floor rather than the size a host would place the widget at, and that is
            // a correction rather than a preference. The three kinds are placed at three different
            // boxes — the counts widget at its smallest, the selection widget at its largest — so
            // taking the placement size as the first step would offer that kind one box and hide
            // the two smaller layouts it is equally built for. The floor is the same figure for
            // all three kinds, so the steps come out as the same three boxes whatever is placed,
            // which is what a harness for looking at layouts should show.
            declaredWidthDp = info.minResizeWidth.toDp().takeIf { width -> width > 0 }
                ?: info.minWidth.toDp(),
            declaredHeightDp = info.minResizeHeight.toDp().takeIf { height -> height > 0 }
                ?: info.minHeight.toDp(),
            // The one figure that cannot be read from the metadata: where the widget changes
            // from one layout to the other. This host exists to look at WIDGORA's own widget, so
            // it reads that from WIDGORA's own constants rather than guessing at a midpoint.
            breakpointWidthDp = WidgetSizes.MIN_WIDTH_DP,
            breakpointHeightDp = WidgetSizes.FULL_HEIGHT_DP,
            maxWidthDp = maxWidth.toDp(),
            maxHeightDp = maxHeight.toDp(),
            availableWidthDp = (metrics.widthPixels.toDp() - 2 * horizontalMargin)
                .coerceAtLeast(1),
            availableHeightDp = (metrics.heightPixels.toDp() - 2 * verticalMargin)
                .coerceAtLeast(1),
        )
    }

    private fun applySize() {
        val view = hostView ?: return
        val size = sizes.getOrNull(sizeIndex) ?: return

        view.layoutParams = FrameLayout.LayoutParams(
            size.widthDp.toPx(),
            size.heightDp.toPx(),
            Gravity.CENTER,
        )

        // Telling the widget its own size is what a real host does, and it is what makes a
        // responsive layout respond. Both bounds are the same because this host shows one size
        // at a time rather than a range.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(
                Bundle.EMPTY,
                listOf(SizeF(size.widthDp.toFloat(), size.heightDp.toFloat())),
            )
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(
                Bundle.EMPTY,
                size.widthDp,
                size.heightDp,
                size.widthDp,
                size.heightDp,
            )
        }

        // The type is named beside the size, because with more than one provider the box alone no
        // longer says what is on screen. One resource with five placeholders rather than two
        // texts joined in code: the join is what lint objects to, and rightly — a separator put
        // between two texts here is a decision no translation can reach.
        status.text = getString(
            R.string.debug_host_size,
            sizeIndex + 1,
            sizes.size,
            size.widthDp,
            size.heightDp,
            typeLabel(),
        )
    }

    /** The type on screen, by its provider's simple class name: short, and never a user value. */
    private fun typeLabel(): String = providers.getOrNull(providerIndex)
        ?.provider
        ?.className
        ?.substringAfterLast('.')
        .orEmpty()

    private fun toggleSize() {
        if (sizes.size < 2) return
        sizeIndex = (sizeIndex + 1) % sizes.size
        applySize()
    }

    // --- Type, reconfiguration and removal -----------------------------------------------------

    /**
     * Moves to the next widget type, which means a different widget.
     *
     * A widget belongs to its provider, so there is no way to change the type of one: the current
     * placement is removed and a new one is bound, configured and attached. That is exactly what a
     * launcher does when a user replaces one widget with another, and it also means the removal
     * path is exercised every time the type is changed.
     */
    private fun nextType() {
        if (providers.size < 2) return
        removeWidget()
        providerIndex = (providerIndex + 1) % providers.size
        store.edit().putInt(KEY_PROVIDER_INDEX, providerIndex).apply()
        allocateAndBind()
    }

    /** Opens the configuration step again for the widget on screen, as a host's menu would. */
    private fun reconfigure() {
        val appWidgetId = attachedAppWidgetId
        if (appWidgetId == HostWidgetIds.NO_APP_WIDGET_ID) return
        if (widgetManager.getAppWidgetInfo(appWidgetId)?.configure == null) {
            status.text = getString(R.string.debug_host_configuration_unavailable)
            return
        }

        val started = runCatching {
            host.startAppWidgetConfigureActivityForResult(
                this,
                appWidgetId,
                0,
                REQUEST_RECONFIGURE,
                configureOptions(),
            )
        }.isSuccess
        if (!started) status.text = getString(R.string.debug_host_configuration_unavailable)
    }

    /**
     * The permission a host has to hand over for the configuration step to start at all.
     *
     * ### The finding
     *
     * On a television running Android 14 the step **never started** `[V]`. The widget was bound,
     * and then:
     *
     * ```
     * ActivityTaskManager: Background activity launch blocked … callingUid: 1000
     *   realCallingPackage: io.github.jellyforgedev.widgora; realCallingUidProcState: TOP
     *   originatingPendingIntent: PendingIntentRecord{… android startActivity}
     *   intent: … act=android.appwidget.action.APPWIDGET_CONFIGURE … (BAL_BLOCK)
     * ```
     *
     * The host was in the foreground — the log says `TOP` itself — so this is not a background
     * application being stopped from stealing the screen. The reason is who created the intent:
     * [AppWidgetHost.startAppWidgetConfigureActivityForResult] does not start the activity, it
     * sends a `PendingIntent` the **system** created (`callingUid: 1000`, originating package
     * `android`). From Android 14 the privilege to start an activity from a pending intent is no
     * longer inherited from whoever happens to be visible: the sender has to grant it explicitly,
     * in the options it passes to the send. A host that passes nothing is refused, however
     * foreground it is.
     *
     * ### The versions, read out of the SDK rather than remembered
     *
     * From `platforms/android-37.0/data/api-versions.xml` `[V]`:
     *
     * ```
     * setPendingIntentBackgroundActivityStartMode(I)  since="34"
     * MODE_BACKGROUND_ACTIVITY_START_ALLOWED          since="34" deprecated="36"
     * MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS     since="36"
     * MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE since="36"
     * ```
     *
     * So there are three cases rather than two. Below 34 there is nothing to pass and nothing to
     * grant, and the options stay absent as they were. On 34 and 35 the one constant that exists
     * is the one that is abandoned later. From 36 that constant is deprecated in favour of two
     * narrower ones, and the narrower of the two is the right one here: this host is visible at
     * the moment it starts the step — the block itself recorded it as `TOP` — so it asks for the
     * privilege while visible rather than for the unconditional one. Asking for more than the
     * case needs would be the opposite of how every other permission in this project is chosen.
     *
     * The 36 branch is reasoned from the SDK and not yet exercised on a device `[U]`; the target
     * television is on 34.
     */
    private fun configureOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null

        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE,
            )
        } else {
            @Suppress("DEPRECATION")
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
        return options.toBundle()
    }

    /**
     * Gives the widget back, which is what makes `onDeleted` and `onDisabled` observable.
     *
     * Both callbacks are the application's own cleanup — the stored settings of that placement,
     * and the periodic refresh when the last widget goes — and neither could be seen on a
     * television before, because nothing here ever removed a widget. The stored identifier goes
     * too: a record pointing at a widget that no longer exists would be released and replaced on
     * the next start, which is work for nothing.
     */
    private fun removeWidget() {
        val appWidgetId = attachedAppWidgetId
        if (appWidgetId != HostWidgetIds.NO_APP_WIDGET_ID) host.deleteAppWidgetId(appWidgetId)

        attachedAppWidgetId = HostWidgetIds.NO_APP_WIDGET_ID
        hostView = null
        sizes = emptyList()
        frame.removeAllViews()
        store.edit().remove(KEY_APP_WIDGET_ID).apply()

        status.text = getString(R.string.debug_host_removed)
        showControls()
    }

    /**
     * Which controls apply to the situation on screen.
     *
     * A control that does nothing is worse than one that is absent: on a remote it is a stop on
     * the way to the next one, and it invites a press that appears to be ignored.
     */
    private fun showControls() {
        val attached = attachedAppWidgetId != HostWidgetIds.NO_APP_WIDGET_ID
        typeButton.visibility = if (providers.size > 1) View.VISIBLE else View.GONE
        sizeButton.visibility = if (attached && sizes.size > 1) View.VISIBLE else View.GONE
        configureButton.visibility = if (attached) View.VISIBLE else View.GONE
        removeButton.visibility = if (attached) View.VISIBLE else View.GONE
        placeButton.visibility = if (!attached && providers.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Without a widget on screen there is nothing else the focus can be on, and a screen
        // whose only controls cannot be reached is a dead end on a television. Posted because a
        // view that has just become visible has not been laid out yet, and an unlaid view cannot
        // take the focus.
        if (!attached) {
            buttonRow.post { visibleControls().firstOrNull()?.requestFocus() }
        }
    }

    private fun ourProviderClassNames(): List<String> =
        providers.map { info -> info.provider.className }

    private fun lastProviderIndex(): Int = (providers.size - 1).coerceAtLeast(0)

    // --- The screen around the widget ------------------------------------------------------------

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WidgoraColors.Backdrop.toArgb())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                WidgoraOverscan.Horizontal.value.toInt().toPx(),
                WidgoraOverscan.Vertical.value.toInt().toPx(),
                WidgoraOverscan.Horizontal.value.toInt().toPx(),
                WidgoraOverscan.Vertical.value.toInt().toPx(),
            )
        }

        root.addView(label(getString(R.string.debug_host_title), WidgoraText.ScreenTitle.size.value))

        status = label("", WidgoraText.Caption.size.value, WidgoraColors.OnSurfaceMuted)
        root.addView(status)

        // Above the widget rather than below it. A control at the bottom edge of a television
        // sits in the part of the picture a set is most likely to crop, and the widget is what
        // should have the room: it is the thing being looked at.
        //
        // One row, so that left and right move between the controls and only up and down have to
        // be taken over; see navigate.
        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = WidgoraSpacing.Small.value.toInt().toPx() }
        }

        typeButton = actionButton(R.string.debug_host_type_next) { nextType() }
        sizeButton = actionButton(R.string.debug_host_size_next) { toggleSize() }
        configureButton = actionButton(R.string.debug_host_reconfigure) { reconfigure() }
        removeButton = actionButton(R.string.debug_host_remove) { removeWidget() }
        placeButton = actionButton(R.string.debug_host_place) { allocateAndBind() }
        for (button in listOf(
            typeButton,
            sizeButton,
            configureButton,
            removeButton,
            placeButton,
        )) {
            buttonRow.addView(button)
        }
        root.addView(buttonRow)

        frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
                weight = 1f
                topMargin = WidgoraSpacing.Medium.value.toInt().toPx()
            }
        }
        root.addView(frame)

        return root
    }

    /**
     * One control of the row, carrying the project's focus mark.
     *
     * Written once rather than five times. The padding is put back explicitly because setting a
     * background clears the padding the platform's button style supplied, and nothing says so:
     * the text then sits flush against the border and looks as though it does not fit its button,
     * which is what a television showed.
     */
    private fun actionButton(textRes: Int, onClick: () -> Unit): Button = Button(this).apply {
        id = View.generateViewId()
        visibility = View.GONE
        background = focusableBackground()
        setPadding(
            WidgoraSpacing.Large.value.toInt().toPx(),
            WidgoraSpacing.Small.value.toInt().toPx(),
            WidgoraSpacing.Large.value.toInt().toPx(),
            WidgoraSpacing.Small.value.toInt().toPx(),
        )
        // Capitals are wider for no gain, and the application sets none anywhere else.
        isAllCaps = false
        maxLines = 1
        text = getString(textRes)
        setTextColor(WidgoraColors.OnSurface.toArgb())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, WidgoraText.Label.size.value)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = WidgoraSpacing.Small.value.toInt().toPx() }
    }

    /**
     * The project's own focus mark, on a plain platform button.
     *
     * The theme's own focus treatment is barely visible across a room, and this screen is
     * operated from across a room. Same white border at the same width as everywhere else, so
     * the host does not teach a different habit than the application it is testing.
     */
    private fun focusableBackground(): StateListDrawable {
        fun face(strokeColor: Int, strokeWidthDp: Float) = GradientDrawable().apply {
            setColor(WidgoraColors.Surface.toArgb())
            cornerRadius = WidgoraShapes.TileCornerRadius.value.toInt().toPx().toFloat()
            setStroke(strokeWidthDp.toInt().toPx(), strokeColor)
        }

        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                face(WidgoraColors.Focus.toArgb(), WidgoraFocus.BorderWidth.value),
            )
            addState(
                intArrayOf(),
                face(WidgoraColors.Outline.toArgb(), WidgoraFocus.RestingBorderWidth.value),
            )
        }
    }

    private fun label(
        text: String,
        sizeSp: Float,
        color: Color = WidgoraColors.OnSurface,
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color.toArgb())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun Int.toDp(): Int = (this / resources.displayMetrics.density).toInt()

    /**
     * Visible within the module rather than private, for one reason: the instrumented test that
     * drives this screen has to start it from a known state and clean up after itself, and both
     * need the same host identifier and the same preference file this screen uses. A second copy
     * of those names in the test would be two things to keep in step.
     */
    internal companion object {

        /** Unique within this package and fixed, so the system's record survives a restart. */
        const val HOST_ID = 1

        const val PREFERENCES = "widgora-debug-widget-host"
        const val KEY_APP_WIDGET_ID = "app_widget_id"

        /** Which widget type to place next. Stored so a chosen type survives a restart. */
        const val KEY_PROVIDER_INDEX = "provider_index"

        /** The log tag to filter on; see the pull request for the command. */
        const val TAG = "WidgoraHost"

        const val REQUEST_BIND = 1

        /** The configuration step of a widget just bound; nothing is attached before it returns. */
        const val REQUEST_CONFIGURE = 2

        /** The same step for a widget already on screen, which is attached either way. */
        const val REQUEST_RECONFIGURE = 3
    }
}
