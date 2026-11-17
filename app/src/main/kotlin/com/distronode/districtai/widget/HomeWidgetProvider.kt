package com.distronode.districtai.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.distronode.districtai.MainActivity
import com.distronode.districtai.R
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.resolvedLanguage

/**
 * The home-screen widget: the selected workspace's name, its unread messages and its calls in the
 * last 7 days (the iOS app's Home Screen widget). Tapping it opens the app.
 *
 * ⛔ IT MAKES NO REQUEST. Every draw reads [PreferencesHomeWidgetStore], which only the app writes
 * ([HomeWidgetPublisher]), and the app asks for each redraw. The system's own periodic update
 * (`updatePeriodMillis` in `home_widget_info.xml`) redraws the same snapshot, which is what turns a
 * call count older than a day into a hyphen.
 *
 * ⚠️ RemoteViews, NOT Glance: the app has no Glance dependency, and three text fields do not need one.
 */
class HomeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        draw(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        /** Draw the stored snapshot on every placed widget. Nothing to do when none is placed. */
        fun redraw(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HomeWidgetProvider::class.java))
            if (ids.isNotEmpty()) draw(context, manager, ids)
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val snapshot = PreferencesHomeWidgetStore(context).read()
            val face = HomeWidgetFace.of(snapshot, System.currentTimeMillis())
            manager.updateAppWidget(ids, homeWidgetViews(context, face))
        }
    }
}

/**
 * What the widget draws, decided apart from the drawing so the rules are plain to test.
 *
 * @param unread null draws a hyphen.
 * @param calls null draws a hyphen; also null when the count is a day old or more.
 */
internal data class HomeWidgetFace(val workspaceName: String, val unread: Int?, val calls: Int?) {
    companion object {
        /** A day: the 7-day window has moved enough that the old count is no longer that week's. */
        const val CALLS_VALID_MILLIS: Long = 24L * 60 * 60 * 1000

        /** Null is the signed-out face. */
        fun of(snapshot: HomeWidgetSnapshot?, nowMillis: Long): HomeWidgetFace? {
            if (snapshot == null) return null
            val readAt = snapshot.callsReadAtMillis
            // ⚠️ A read time in the future (the clock was moved back) is treated as stale too.
            val fresh = readAt != null && nowMillis - readAt in 0 until CALLS_VALID_MILLIS
            return HomeWidgetFace(
                workspaceName = snapshot.workspaceName,
                unread = snapshot.unread,
                calls = snapshot.callsLast7Days.takeIf { fresh },
            )
        }
    }
}

/** The widget's views for [face]; null is the signed-out face. */
internal fun homeWidgetViews(context: Context, face: HomeWidgetFace?): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.home_widget)
    views.setOnClickPendingIntent(R.id.home_widget_root, openAppIntent(context))
    if (face == null) {
        views.setViewVisibility(R.id.home_widget_signed_in, View.GONE)
        views.setViewVisibility(R.id.home_widget_signed_out, View.VISIBLE)
        return views
    }
    views.setViewVisibility(R.id.home_widget_signed_in, View.VISIBLE)
    views.setViewVisibility(R.id.home_widget_signed_out, View.GONE)
    views.setTextViewText(R.id.home_widget_name, face.workspaceName)
    views.setCount(context, R.id.home_widget_unread_value, face.unread)
    views.setCount(context, R.id.home_widget_calls_value, face.calls)
    return views
}

private fun RemoteViews.setCount(context: Context, viewId: Int, count: Int?) {
    if (count == null) {
        setTextViewText(viewId, UNKNOWN_COUNT)
        // ⚠️ TalkBack would read the hyphen as "hyphen"; say what it means instead.
        setContentDescription(viewId, context.getString(R.string.widget_count_unknown))
    } else {
        val text = countText(context, count)
        setTextViewText(viewId, text)
        setContentDescription(viewId, text)
    }
}

/** A count as the screens print it (`countText`): plain digits in English, grouped in French. */
private fun countText(context: Context, count: Int): String {
    val language = context.resolvedLanguage()
    return if (language == AppLanguage.EN) count.toString() else DistrictFormat.number(count.toDouble(), language)
}

/** The launcher's own intent, so a tap lands where tapping the app icon does. */
private fun openAppIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Not a number yet. A hyphen, never zero, as on iOS. */
private const val UNKNOWN_COUNT = "-"
