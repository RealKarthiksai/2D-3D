package dev.capturekit.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background upload under WorkManager: survives process death and reboot, runs only with a network,
 * backs off exponentially. No upload without a recorded consent flag; local files are deleted only
 * after the server reports COMPLETE and only if the user's setting allows (Part 1 stub of the full flow).
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val dir = File(inputData.getString(KEY_DIR) ?: return Result.failure())
        if (!inputData.getBoolean(KEY_CONSENT, false)) return Result.failure(workDataOf(KEY_ERROR to "no upload consent recorded"))
        if (!File(dir, UploadPlan.MANIFEST).exists()) return Result.failure(workDataOf(KEY_ERROR to "bundle not finalized"))
        val up = TusUploader(applicationContext, inputData.getString(KEY_TUS) ?: return Result.failure(), inputData.getString(KEY_VERIFIER))
        return when (val o = up.uploadBundle(dir)) {
            is TusUploader.Outcome.Complete -> {
                if (inputData.getBoolean(KEY_DELETE, false)) dir.deleteRecursively()
                Result.success()
            }
            is TusUploader.Outcome.Retry -> Result.retry()
            is TusUploader.Outcome.Failed -> Result.failure(workDataOf(KEY_ERROR to o.reason))
        }
    }

    companion object {
        const val KEY_DIR = "dir"; const val KEY_TUS = "tus"; const val KEY_VERIFIER = "verifier"
        const val KEY_CONSENT = "consent"; const val KEY_DELETE = "delete_after_verify"; const val KEY_ERROR = "error"

        /** One unique job per bundle: enqueueing again while pending is a no-op (KEEP). */
        fun enqueue(ctx: Context, bundle: File, tusUrl: String, verifierUrl: String?, consent: Boolean, deleteAfterVerify: Boolean = false, unmeteredOnly: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_DIR to bundle.absolutePath, KEY_TUS to tusUrl, KEY_VERIFIER to verifierUrl,
                    KEY_CONSENT to consent, KEY_DELETE to deleteAfterVerify))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload-${bundle.name}", ExistingWorkPolicy.KEEP, req)
        }
    }
}
