package tv.own.owntv.core.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.sync.SyncContentTypes

class CatalogSyncScheduler(private val context: Context) {

    fun enqueueSync(
        sourceId: Long,
        reason: String = "manual",
        contentTypes: SyncContentTypes = SyncContentTypes(live = true, movies = false, series = false),
        baseItemCount: Int = 0,
        completesInitialSync: Boolean = false,
        /**
         * User asked for a clean resync: bypasses the catalog-shrink prune guard for this run only,
         * so titles the provider no longer lists are removed. Never set for an automatic sync.
         */
        forcePrune: Boolean = false,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            .setInputData(workDataOf(
                CatalogSyncWorker.KEY_SOURCE_ID to sourceId,
                CatalogSyncWorker.KEY_REASON to reason,
                CatalogSyncWorker.KEY_BASE_ITEM_COUNT to baseItemCount,
                CatalogSyncWorker.KEY_LIVE to contentTypes.live,
                CatalogSyncWorker.KEY_MOVIES to contentTypes.movies,
                CatalogSyncWorker.KEY_SERIES to contentTypes.series,
                CatalogSyncWorker.KEY_COMPLETES_INITIAL_SYNC to completesInitialSync,
                CatalogSyncWorker.KEY_FORCE_PRUNE to forcePrune,
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_CATALOG_SYNC)
            .addTag("source-$sourceId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(sourceId), policy, request)
    }

    fun cancelSync(sourceId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(sourceId))
    }

    fun enqueueContentIndexBuild(reason: String = "fresh_sync") {
        val request = OneTimeWorkRequestBuilder<ContentIndexWorker>()
            .setInputData(workDataOf(ContentIndexWorker.KEY_REASON to reason))
            .addTag(ContentIndexWorker.TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ContentIndexWorker.workName(), ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueTrendingRefresh(sourceId: Long, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<TrendingRefreshWorker>()
            .setInputData(
                workDataOf(
                    TrendingRefreshWorker.KEY_SOURCE_ID to sourceId,
                    TrendingRefreshWorker.KEY_FORCE to force,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(TrendingRefreshWorker.WORK_TAG)
            .addTag("trending-source-$sourceId")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TrendingRefreshWorker.workName(sourceId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun observeSync(sourceId: Long): Flow<CatalogSyncState> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(workName(sourceId))
            .map { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                if (active == null) {
                    CatalogSyncState.Idle
                } else {
                    CatalogSyncState.Syncing(
                        baseItemCount = active.progress.getInt(CatalogSyncWorker.KEY_BASE_ITEM_COUNT, 0),
                        liveProcessed = active.progress.getInt(CatalogSyncWorker.KEY_PROGRESS_LIVE_PROCESSED, 0),
                        moviesProcessed = active.progress.getInt(CatalogSyncWorker.KEY_PROGRESS_MOVIES_PROCESSED, 0),
                        seriesProcessed = active.progress.getInt(CatalogSyncWorker.KEY_PROGRESS_SERIES_PROCESSED, 0),
                        liveActive = active.progress.getBoolean(CatalogSyncWorker.KEY_PROGRESS_LIVE_ACTIVE, false),
                        moviesActive = active.progress.getBoolean(CatalogSyncWorker.KEY_PROGRESS_MOVIES_ACTIVE, false),
                        seriesActive = active.progress.getBoolean(CatalogSyncWorker.KEY_PROGRESS_SERIES_ACTIVE, false),
                    )
                }
            }
            .distinctUntilChanged()

    companion object {
        const val TAG_CATALOG_SYNC = "catalog-sync"
        fun workName(sourceId: Long) = "catalog-sync-source-$sourceId"
    }
}

sealed interface CatalogSyncState {
    data object Idle : CatalogSyncState
    data class Syncing(
        val baseItemCount: Int,
        val liveProcessed: Int,
        val moviesProcessed: Int,
        val seriesProcessed: Int,
        val liveActive: Boolean,
        val moviesActive: Boolean,
        val seriesActive: Boolean,
    ) : CatalogSyncState {
        val totalProcessed: Int
            get() = liveProcessed + moviesProcessed + seriesProcessed
    }

    val isActive: Boolean
        get() = this is Syncing
}
