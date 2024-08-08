package org.wikipedia.recommendedcontent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.wikipedia.Constants
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.database.AppDatabase
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.page.NearbyPage
import org.wikipedia.dataclient.page.PageSummary
import org.wikipedia.feed.aggregated.AggregatedFeedContent
import org.wikipedia.feed.topread.TopRead
import org.wikipedia.page.PageTitle
import org.wikipedia.places.PlacesFragment
import org.wikipedia.settings.Prefs
import org.wikipedia.util.DateUtil
import org.wikipedia.util.ImageUrlUtil
import org.wikipedia.util.StringUtil

object RecommendedContentHelper {

    // TODO: introduce cache for these methods
    // TODO: think about all PageSummary vs PageTitle.

    suspend fun loadHistoryItems(): List<PageTitle> {
        return withContext(Dispatchers.IO) {
            AppDatabase.instance.historyEntryWithImageDao().filterHistoryItemsWithoutTime("").map {
                it.title
            }
        }
    }

    suspend fun loadRecentSearches(): List<PageTitle> {
        return withContext(Dispatchers.IO) {
            AppDatabase.instance.recentSearchDao().getRecentSearches().map {
                PageTitle(it.text, WikipediaApp.instance.wikiSite)
            }
        }
    }

    suspend fun loadFeed(): AggregatedFeedContent {
        return withContext(Dispatchers.IO) {
            val wikiSite = WikipediaApp.instance.wikiSite
            val hasParentLanguageCode = !WikipediaApp.instance.languageState.getDefaultLanguageCode(wikiSite.languageCode).isNullOrEmpty()
            val date = DateUtil.getUtcRequestDateFor(0)
            var feedContentResponse = ServiceFactory.getRest(wikiSite).getFeedFeatured(date.year, date.month, date.day)
            if (hasParentLanguageCode) {
                feedContentResponse.topRead?.let {
                    val topReadResponse = getPagesForLanguageVariant(it.articles, wikiSite)
                    feedContentResponse = AggregatedFeedContent(
                        tfa = feedContentResponse.tfa,
                        news = feedContentResponse.news,
                        topRead = TopRead(it.date, topReadResponse),
                        potd = feedContentResponse.potd,
                        onthisday = feedContentResponse.onthisday
                    )
                }
            }
            feedContentResponse
        }
    }

    suspend fun loadTopRead(): List<PageSummary> {
        return loadFeed().topRead?.articles ?: emptyList()
    }

    suspend fun loadExplore(searchTerm: String): List<PageSummary> {
        // TODO: single search term vs multiple search terms.
        return withContext(Dispatchers.IO) {
            val wikiSite = WikipediaApp.instance.wikiSite
            val moreLikeResponse = ServiceFactory.get(wikiSite).searchMoreLike("morelike:$searchTerm", Constants.SUGGESTION_REQUEST_ITEMS, Constants.SUGGESTION_REQUEST_ITEMS)
            val hasParentLanguageCode = !WikipediaApp.instance.languageState.getDefaultLanguageCode(wikiSite.languageCode).isNullOrEmpty()

            val list = moreLikeResponse.query?.pages?.map {
                PageSummary(it.displayTitle(wikiSite.languageCode), it.title, it.description, it.extract, it.thumbUrl(), wikiSite.languageCode)
            } ?: emptyList()

            if (hasParentLanguageCode) {
                getPagesForLanguageVariant(list, wikiSite)
            } else {
                list
            }
        }
    }

    suspend fun loadBecauseYouRead(age: Int): List<PageSummary> {
        return withContext(Dispatchers.IO) {
            val entry = AppDatabase.instance.historyEntryWithImageDao().findEntryForReadMore(age, WikipediaApp.instance.resources.getInteger(
                R.integer.article_engagement_threshold_sec)).last()
            loadExplore(entry.title.prefixedText)
        }
    }

    suspend fun loadContinueReading(): List<PageTitle> {
        return withContext(Dispatchers.IO) {
            WikipediaApp.instance.tabList.mapNotNull { it.backStackPositionTitle }
        }
    }

    suspend fun loadPlaces(): List<NearbyPage> {
        val wikiSite = WikipediaApp.instance.wikiSite
        return withContext(Dispatchers.IO) {
            Prefs.placesLastLocationAndZoomLevel?.let { pair ->
                val location = pair.first
                val response = ServiceFactory.get(wikiSite).getGeoSearch("${location.latitude}|${location.longitude}", 10000, 10, 10)
                val nearbyPages = response.query?.pages.orEmpty()
                    .filter { it.coordinates != null }
                    .map {
                        NearbyPage(it.pageId, PageTitle(it.title, wikiSite,
                            if (it.thumbUrl().isNullOrEmpty()) null else ImageUrlUtil.getUrlForPreferredSize(it.thumbUrl()!!, PlacesFragment.THUMB_SIZE),
                            it.description, it.displayTitle(wikiSite.languageCode)), it.coordinates!![0].lat, it.coordinates[0].lon)
                    }
                nearbyPages
            } ?: emptyList()
        }
    }

    suspend fun loadRandomArticles(): List<PageSummary> {
        // TODO: define how many random articles to load.
        return withContext(Dispatchers.IO) {
            val wikiSite = WikipediaApp.instance.wikiSite
            val list = mutableListOf<PageSummary>()
            repeat(5) {
                val response = ServiceFactory.getRest(wikiSite).getRandomSummary()
                list.add(response)
            }
            // make sure the list is distinct
            list.distinct()
        }
    }

    // TODO: borrowed from FeedClient. Refactor this method to be more generic.
    private suspend fun getPagesForLanguageVariant(list: List<PageSummary>, wikiSite: WikiSite): List<PageSummary> {
        val newList = mutableListOf<PageSummary>()
        withContext(Dispatchers.IO) {
            val titles = list.joinToString(separator = "|") { it.apiTitle }
            // First, get the correct description from Wikidata directly.
            val wikiDataResponse = async {
                ServiceFactory.get(Constants.wikidataWikiSite)
                    .getWikidataDescription(titles = titles, sites = wikiSite.dbName(), langCode = wikiSite.languageCode)
            }
            // Second, fetch varianttitles from prop=info endpoint.
            val mwQueryResponse = async {
                ServiceFactory.get(wikiSite).getVariantTitlesByTitles(titles)
            }

            list.forEach { pageSummary ->
                // Find the correct display title from the varianttitles map, and insert the new page summary to the list.
                val displayTitle = mwQueryResponse.await().query?.pages?.find { StringUtil.addUnderscores(it.title) == pageSummary.apiTitle }?.varianttitles?.get(wikiSite.languageCode)
                val newPageSummary = pageSummary.apply {
                    val newDisplayTitle = displayTitle ?: pageSummary.displayTitle
                    this.titles = PageSummary.Titles(
                        canonical = pageSummary.apiTitle,
                        display = newDisplayTitle
                    )
                    this.description = wikiDataResponse.await().entities.values.firstOrNull {
                        it.labels[wikiSite.languageCode]?.value == newDisplayTitle
                    }?.descriptions?.get(wikiSite.languageCode)?.value ?: pageSummary.description
                }
                newList.add(newPageSummary)
            }
        }
        return newList
    }
}
