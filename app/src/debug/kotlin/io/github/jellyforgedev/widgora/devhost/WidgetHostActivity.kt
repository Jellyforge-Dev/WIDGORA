package io.github.jellyforgedev.widgora.devhost

import android.app.Activity
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

    private val provider: ComponentName by lazy {
        ComponentName(this, CountsWidgetProvider::class.java)
    }

    private val store by lazy { getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private lateinit var sizeButton: Button

    private var hostView: AppWidgetHostView? = null
    private var sizes: List<HostSize> = emptyList()
    private var sizeIndex = 0

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
        if (requestCode != REQUEST_BIND) return
        if (resultCode == RESULT_OK) {
            attach(unboundAppWidgetId)
        } else {
            releaseUnbound()
            showGrantHint()
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
        val stored = store.getInt(KEY_APP_WIDGET_ID, HostWidgetIds.NO_APP_WIDGET_ID)
            .takeIf { it != HostWidgetIds.NO_APP_WIDGET_ID }
        val boundTo = stored?.let { widgetManager.getAppWidgetInfo(it)?.provider?.className }

        when (val decision = HostWidgetIds.decide(stored, boundTo, provider.className)) {
            is HostWidgetDecision.Reuse -> attach(decision.appWidgetId)

            is HostWidgetDecision.ReleaseAndAllocate -> {
                host.deleteAppWidgetId(decision.staleAppWidgetId)
                store.edit().remove(KEY_APP_WIDGET_ID).apply()
                allocateAndBind()
            }

            HostWidgetDecision.Allocate -> allocateAndBind()
        }
    }

    private fun allocateAndBind() {
        val appWidgetId = host.allocateAppWidgetId()
        unboundAppWidgetId = appWidgetId

        if (widgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)) {
            attach(appWidgetId)
            return
        }

        // Binding is a privileged act, and an ordinary application is refused it until the user
        // or the shell says otherwise. The command goes on the screen because that is where
        // somebody standing in front of a television will look for it.
        showGrantHint()

        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
        }
        runCatching { startActivityForResult(intent, REQUEST_BIND) }
    }

    private fun attach(appWidgetId: Int) {
        val info = widgetManager.getAppWidgetInfo(appWidgetId)
        if (info == null) {
            status.text = getString(R.string.debug_host_no_info)
            return
        }

        unboundAppWidgetId = HostWidgetIds.NO_APP_WIDGET_ID
        store.edit().putInt(KEY_APP_WIDGET_ID, appWidgetId).apply()

        val view = host.createView(this, appWidgetId, info)
        hostView = view
        frame.removeAllViews()
        frame.addView(view)

        sizes = sizesFor(info)
        sizeIndex = 0
        applySize()

        sizeButton.visibility = if (sizes.size > 1) View.VISIBLE else View.GONE

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

    private fun navigate(keyCode: Int): Boolean {
        if (sizeButton.visibility != View.VISIBLE) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> focusIsInWidget() && sizeButton.requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> currentFocus === sizeButton && focusWidget()
            else -> false
        }
    }

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
            declaredWidthDp = info.minWidth.toDp(),
            declaredHeightDp = info.minHeight.toDp(),
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

        status.text = getString(R.string.debug_host_size, size.widthDp, size.heightDp)
        sizeButton.text = getString(
            if (sizeIndex == 0) {
                R.string.debug_host_size_larger
            } else {
                R.string.debug_host_size_smaller
            },
        )
    }

    private fun toggleSize() {
        if (sizes.size < 2) return
        sizeIndex = (sizeIndex + 1) % sizes.size
        applySize()
    }

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
        sizeButton = Button(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
            background = focusableBackground()
            // Setting a background clears the padding the platform's button style supplied, and
            // nothing says so: the text then sits flush against the border and looks as though
            // it does not fit its button, which is what a television showed. The padding is put
            // back explicitly, and the log prints it.
            setPadding(
                WidgoraSpacing.Large.value.toInt().toPx(),
                WidgoraSpacing.Small.value.toInt().toPx(),
                WidgoraSpacing.Large.value.toInt().toPx(),
                WidgoraSpacing.Small.value.toInt().toPx(),
            )
            // Capitals are wider for no gain, and the application sets none anywhere else.
            isAllCaps = false
            maxLines = 1
            setTextColor(WidgoraColors.OnSurface.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, WidgoraText.Label.size.value)
            setOnClickListener { toggleSize() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = WidgoraSpacing.Small.value.toInt().toPx() }
        }
        root.addView(sizeButton)

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

    private companion object {

        /** Unique within this package and fixed, so the system's record survives a restart. */
        const val HOST_ID = 1

        const val PREFERENCES = "widgora-debug-widget-host"
        const val KEY_APP_WIDGET_ID = "app_widget_id"

        /** The log tag to filter on; see the pull request for the command. */
        const val TAG = "WidgoraHost"

        const val REQUEST_BIND = 1
    }
}
