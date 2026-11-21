package com.example.connwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Site(val name: String, val url: String)

// Edit this list to change what gets checked. Rebuild after changes.
private val SITES = listOf(
    Site("Irancell portal", "https://my.irancell.ir"),
    Site("MCI portal", "https://my.mci.ir"),
    Site("leader.ir", "https://leader.ir"),
    Site("example.com", "https://example.com"),
    Site("Google", "https://www.google.com"),
    Site("YouTube", "https://www.youtube.com"),
    Site("Telegram", "https://web.telegram.org"),
)

class ConnWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context, null, "Tap to check")) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CHECK) return

        val pending = goAsync()
        Thread {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, ConnWidget::class.java))
                ids.forEach { manager.updateAppWidget(it, buildViews(context, null, "Checking…")) }

                val results = arrayOfNulls<Long>(SITES.size)
                val threads = SITES.indices.map { i ->
                    Thread { results[i] = check(SITES[i].url) }.apply { start() }
                }

                // Overall deadline so the receiver finishes well inside Android's limit
                val deadline = System.currentTimeMillis() + 6000
                threads.forEach { t ->
                    val left = deadline - System.currentTimeMillis()
                    if (left > 0) t.join(left)
                }

                val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val snapshot = results.toList()
                ids.forEach { manager.updateAppWidget(it, buildViews(context, snapshot, "Updated $stamp")) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun buildViews(context: Context, times: List<Long?>?, status: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        views.setTextViewText(R.id.status, status)
        views.removeAllViews(R.id.rows)

        SITES.forEachIndexed { i, site ->
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            val ms = times?.get(i)
            val (color, label) = when {
                times == null -> GRAY to "–"
                ms == null -> RED to "No reply"
                ms < 2000 -> GREEN to seconds(ms)
                ms <= 5000 -> YELLOW to seconds(ms)
                else -> RED to seconds(ms)
            }
            row.setTextViewText(R.id.name, site.name)
            row.setTextColor(R.id.dot, color)
            row.setTextViewText(R.id.time, label)
            views.addView(R.id.rows, row)
        }

        views.setOnClickPendingIntent(R.id.root, tapIntent(context))
        return views
    }

    // Any HTTP reply counts as reachable, including redirects and errors.
    // Returns elapsed milliseconds, or null if there was no reply.
    private fun check(url: String): Long? = try {
        val start = System.nanoTime()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.responseCode
        val ms = (System.nanoTime() - start) / 1_000_000
        conn.disconnect()
        ms
    } catch (e: Exception) {
        null
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1fs", ms / 1000.0)

    private fun tapIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConnWidget::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_CHECK = "com.example.connwidget.CHECK"
        private val GREEN = 0xFF639922.toInt()
        private val YELLOW = 0xFFEF9F27.toInt()
        private val RED = 0xFFE24B4A.toInt()
        private val GRAY = 0xFF888780.toInt()
    }
}
