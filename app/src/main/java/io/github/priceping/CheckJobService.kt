package io.github.priceping

import android.app.job.JobParameters
import android.app.job.JobService

/** Android wakes this up on schedule; it checks every tracked link on a background thread. */
class CheckJobService : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val t = Thread {
            try {
                Checker.runAll(applicationContext, null)
            } catch (e: Throwable) {
                // Never crash the app from a background check.
            }
            jobFinished(params, false)
        }
        worker = t
        t.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}
