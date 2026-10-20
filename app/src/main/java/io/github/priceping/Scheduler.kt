package io.github.priceping

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/** Keeps Android's built-in job scheduler running background checks at the chosen interval. */
object Scheduler {
    private const val JOB_ID = 4201

    fun apply(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val s = Store.read(ctx).getJSONObject("settings")
        val hours = s.optInt("intervalHours", 6)
        val wifiOnly = s.optBoolean("wifiOnly", false)
        val prefs = ctx.getSharedPreferences("scheduler", Context.MODE_PRIVATE)

        if (hours <= 0) {
            js.cancel(JOB_ID)
            prefs.edit().putString("sig", "off").apply()
            return
        }
        val sig = "$hours|$wifiOnly"
        if (js.getPendingJob(JOB_ID) != null && prefs.getString("sig", "") == sig) return

        val period = hours * 3_600_000L
        val flex = maxOf(5 * 60_000L, minOf(period / 4, 30 * 60_000L))
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, CheckJobService::class.java))
            .setPeriodic(period, flex)
            .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        js.schedule(info)
        prefs.edit().putString("sig", sig).apply()
    }
}
