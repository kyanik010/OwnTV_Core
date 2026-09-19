package tv.own.owntv.core.nav

import androidx.annotation.StringRes
import tv.own.owntv.core.R

/**
 * The top-level destinations both apps navigate between.
 *
 * The TV app draws them as a sidebar rail, the mobile app as a bottom bar or navigation rail, but
 * *which* of them a given playlist shows is one rule, decided here — see [NavVisibility]. The names
 * are persisted (`navMenuHidden` stores `MainSection.name` values), so renaming a constant would
 * silently unhide a section the user hid.
 */
enum class MainSection(@param:StringRes val labelRes: Int) {
    SEARCH(R.string.common_nav_search),
    HOME(R.string.common_nav_home),
    LIVE_TV(R.string.common_nav_live_tv),
    MOVIES(R.string.common_nav_movies),
    SERIES(R.string.common_nav_series),
    DOWNLOADS(R.string.common_nav_downloads),
    EPG(R.string.common_nav_guide),
    SETTINGS(R.string.common_nav_settings), // pinned, not part of the browse set

    /**
     * The hub that everything which is neither content nor a preference lives behind — Favourites,
     * History, Backup, Local sync, the error log, About, and Settings itself.
     *
     * Pinned like [SETTINGS] and deliberately outside [browseOrder], which is what makes it
     * un-hidable for free: the Nav menu settings page only ever offers the browse items, so nobody
     * can hide their way out of their own settings.
     */
    MORE(R.string.common_nav_more);

    /** Browse items are the ones the visibility rule applies to. Search lives in a top bar, not the nav. */
    val isBrowse: Boolean get() = this != SETTINGS && this != SEARCH && this != MORE

    companion object {
        /** Fixed order of the browse items (Settings is pinned separately). */
        val browseOrder: List<MainSection> = listOf(LIVE_TV)

        /** All six browse items — the default value, so a cold start shows a full nav rather than
         *  flickering through an empty one before the first real emission lands. */
        val allBrowse: Set<MainSection> = browseOrder.toSet()

        /**
         * DYNAMIC-mode rule: which browse items show, given the active playlist's content caps.
         * Home always; Live and Guide when there are channels; Movies/Series when their tables have
         * rows; Downloads when Movies OR Series exist, because Live has no download.
         */
        fun dynamicVisible(hasLive: Boolean, hasMovies: Boolean, hasSeries: Boolean): Set<MainSection> =
            if (hasLive) setOf(LIVE_TV) else emptySet()
    }
}
