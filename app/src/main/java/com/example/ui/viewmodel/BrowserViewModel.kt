package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.automation.ExtractedRecord
import com.example.automation.NanoChatMessage
import com.example.automation.NanoMultiAgentEngine
import com.example.automation.RetrieverEngine
import com.example.automation.TabSnapshotTarget
import com.example.data.local.ApexDatabase
import com.example.data.local.BlockedDomainLogEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.BrowsingHistoryEntity
import com.example.data.local.DownloadItemEntity
import com.example.data.local.ExtractionRuleEntity
import com.example.data.local.PrivacyStatSummaryEntity
import com.example.data.local.SavedPasswordEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.TopVisitEntity
import com.example.data.preferences.BrowserPreferences
import com.example.data.preferences.SearchEngineOption
import com.example.data.preferences.ThemeModeOption
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BrowserRepository
import com.example.engine.FastDownloadEngine
import com.example.engine.LanguageCatalog
import com.example.engine.LanguageTranslationEngine
import com.example.engine.PasswordVaultUtil
import com.example.engine.PerformanceOptimizer
import com.example.engine.PreloadCandidate
import com.example.engine.PrivacyManager
import com.example.engine.SearchSuggestionEngine
import com.example.engine.SearchSuggestionItem
import org.json.JSONArray
import org.json.JSONObject
import com.example.ui.theme.SeedPalettePreset
import com.example.ui.theme.TabGroupColorOption
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TabGroupInfo(
    val id: String = "grp_${UUID.randomUUID()}",
    val title: String,
    val colorOption: TabGroupColorOption = TabGroupColorOption.COBALT
)

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Home",
    val url: String = "apex://home",
    val requestedUrl: String = url,
    val loadRequestId: Long = 1L,
    val isPrivate: Boolean = false,
    val groupId: String? = null,
    val tabColor: TabGroupColorOption? = null,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val blockedCountOnPage: Int = 0,
    val isHttps: Boolean = true,
    val wasUpgradedToHttps: Boolean = false,
    val previewThumbnail: Bitmap? = null,
    val lastActiveTimeMillis: Long = System.currentTimeMillis()
) {
    val isHomePage: Boolean
        get() = url.isBlank() || url.equals("apex://home", ignoreCase = true)
}

sealed interface TabGridEntry {
    val gridKey: String

    data class SingleTab(val tab: BrowserTab) : TabGridEntry {
        override val gridKey: String = "single_${tab.id}"
    }

    data class GroupTab(
        val group: TabGroupInfo,
        val tabs: List<BrowserTab>
    ) : TabGridEntry {
        override val gridKey: String = "group_${group.id}"
    }
}

enum class ExtensionModule {
    NANO,
    RETRIEVER
}

enum class RetrieverDisplayMode {
    TABLE,
    CARDS
}

data class PendingCredentialPrompt(
    val siteName: String,
    val siteUrl: String,
    val username: String,
    val password: String,
    val existingId: Long? = null
)

data class PendingClosedTabUndo(
    val token: Long,
    val tabTitle: String
)

data class TabTranslationState(
    val tabId: String,
    val pageUrl: String = "",
    val detectedSourceCode: String? = null,
    val targetCode: String = "en",
    val isTranslated: Boolean = false,
    val isTranslating: Boolean = false,
    val isBannerVisible: Boolean = false,
    val bannerDismissed: Boolean = false,
    val errorMessage: String? = null
)

data class BrowserUiState(
    // Fresh launch: strictly 1 clean Home tab — no pre-opened tabs
    val tabs: List<BrowserTab> = listOf(
        BrowserTab(id = "tab_home", title = "Home", url = "apex://home")
    ),
    val tabGroups: List<TabGroupInfo> = emptyList(),
    val expandedGroupId: String? = null,
    val editingGroupId: String? = null,
    val activeTabId: String = "tab_home",
    val previousTabIndex: Int = 0,
    val isToolbarVisible: Boolean = true,
    val isSearchScreenOpen: Boolean = false,
    val searchQuery: String = "",
    val searchSuggestions: List<SearchSuggestionItem> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val isTabSwitcherOpen: Boolean = false,
    val isBookmarksScreenOpen: Boolean = false,
    val isHistoryScreenOpen: Boolean = false,
    val isDownloadsScreenOpen: Boolean = false,
    val isPasswordManagerOpen: Boolean = false,
    val isSettingsScreenOpen: Boolean = false,
    val isMenuSheetOpen: Boolean = false,
    val isIncognitoUnlocked: Boolean = false,
    val isAutomationOpen: Boolean = false,
    val pendingCredentialPrompt: PendingCredentialPrompt? = null,
    val matchedCredentialsForActiveTab: List<SavedPasswordEntity> = emptyList(),
    val activeExtension: ExtensionModule = ExtensionModule.NANO,
    val snackbarNotice: String? = null,
    val pendingClosedTabUndo: PendingClosedTabUndo? = null,
    val preloadCandidates: List<PreloadCandidate> = emptyList(),
    val nanoMessages: List<NanoChatMessage> = listOf(
        NanoChatMessage(
            id = "welcome_nano",
            isUser = false,
            text = "Nano Multi-Agent ready. PlannerAgent, NavigatorAgent, and ValidatorAgent run locally over the active WebView DOM."
        )
    ),
    val isNanoRunning: Boolean = false,
    val selectedRuleId: Long? = null,
    val retrieverPromptInput: String = "",
    val extractedRecords: List<ExtractedRecord> = emptyList(),
    val isRetrieverRunning: Boolean = false,
    val retrieverStatusBanner: String = "Ready for single-page or multi-tab parallel capture",
    val retrieverDisplayMode: RetrieverDisplayMode = RetrieverDisplayMode.TABLE
) {
    val activeTab: BrowserTab
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    val activeTabIndex: Int
        get() = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)

    val activeTabGroup: TabGroupInfo?
        get() {
            val gId = activeTab.groupId ?: return null
            return tabGroups.firstOrNull { it.id == gId }
        }

    val activeGroupTabs: List<BrowserTab>
        get() {
            val gId = activeTabGroup?.id ?: return emptyList()
            return tabs.filter { it.groupId == gId }
        }

    val editingGroup: TabGroupInfo?
        get() {
            val editId = editingGroupId ?: return null
            return tabGroups.firstOrNull { it.id == editId }
        }

    val incognitoTabCount: Int
        get() = tabs.count { it.isPrivate }

    val gridEntries: List<TabGridEntry>
        get() {
            val emittedGroups = mutableSetOf<String>()
            val result = mutableListOf<TabGridEntry>()
            val groupLookup = tabGroups.associateBy { it.id }

            for (tab in tabs) {
                val gId = tab.groupId
                if (gId == null) {
                    result.add(TabGridEntry.SingleTab(tab))
                } else if (emittedGroups.add(gId)) {
                    val memberTabs = tabs.filter { it.groupId == gId }
                    val info = groupLookup[gId]
                    if (info != null && memberTabs.isNotEmpty()) {
                        result.add(TabGridEntry.GroupTab(group = info, tabs = memberTabs))
                    } else if (memberTabs.size >= 2) {
                        result.add(
                            TabGridEntry.GroupTab(
                                group = TabGroupInfo(id = gId, title = "Tab Group"),
                                tabs = memberTabs
                            )
                        )
                    } else {
                        result.add(TabGridEntry.SingleTab(tab))
                    }
                }
            }
            return result
        }
}

class BrowserViewModel(
    private val repository: BrowserRepository,
    val privacyManager: PrivacyManager = PrivacyManager(),
    val performanceOptimizer: PerformanceOptimizer = PerformanceOptimizer(),
    private val suggestionEngine: SearchSuggestionEngine = SearchSuggestionEngine(),
    private val nanoEngine: NanoMultiAgentEngine = NanoMultiAgentEngine(),
    private val retrieverEngine: RetrieverEngine = RetrieverEngine(),
    private val fastDownloadEngine: FastDownloadEngine? = null,
    private val translationEngine: LanguageTranslationEngine = LanguageTranslationEngine()
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _downloadedLanguageCodes = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLanguageCodes: StateFlow<Set<String>> = _downloadedLanguageCodes.asStateFlow()

    private val _downloadingLanguageCodes = MutableStateFlow<Set<String>>(emptySet())
    val downloadingLanguageCodes: StateFlow<Set<String>> = _downloadingLanguageCodes.asStateFlow()

    private val _failedLanguageDownloads = MutableStateFlow<Set<String>>(emptySet())
    val failedLanguageDownloads: StateFlow<Set<String>> = _failedLanguageDownloads.asStateFlow()

    private val _tabTranslationStates = MutableStateFlow<Map<String, TabTranslationState>>(emptyMap())
    val tabTranslationStates: StateFlow<Map<String, TabTranslationState>> = _tabTranslationStates.asStateFlow()

    val preferencesState: StateFlow<BrowserPreferences> = repository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BrowserPreferences()
        )

    val privacyStatsState: StateFlow<PrivacyStatSummaryEntity> = repository.privacyStatsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PrivacyStatSummaryEntity()
        )

    val blockedLogsState: StateFlow<List<BlockedDomainLogEntity>> = repository.recentBlockedLogsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val extractionRulesState: StateFlow<List<ExtractionRuleEntity>> = repository.extractionRulesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val bookmarksState: StateFlow<List<BookmarkEntity>> = repository.bookmarksFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val searchHistoryState: StateFlow<List<SearchHistoryEntity>> = repository.searchHistoryFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val topVisitsState: StateFlow<List<TopVisitEntity>> = repository.topVisitsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val browsingHistoryState: StateFlow<List<BrowsingHistoryEntity>> = repository.browsingHistoryFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val browsingHistoryCountState: StateFlow<Int> = repository.browsingHistoryCountFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val downloadsState: StateFlow<List<DownloadItemEntity>> = repository.downloadsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val downloadsCountState: StateFlow<Int> = repository.downloadsCountFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val savedPasswordsState: StateFlow<List<SavedPasswordEntity>> = repository.savedPasswordsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val savedPasswordsCountState: StateFlow<Int> = repository.savedPasswordsCountFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    private data class RecordedTabVisit(
        val loadRequestId: Long,
        val canonicalUrl: String,
        val host: String,
        val recordedAtMillis: Long
    )

    private data class ClosedTabSnapshot(
        val token: Long,
        val tab: BrowserTab,
        val originalIndex: Int,
        val previousActiveTabId: String,
        val previousExpandedGroupId: String?,
        val groupInfo: TabGroupInfo?,
        val autoCreatedReplacementTabId: String?
    )

    private val webViewRegistry = mutableMapOf<String, WebView>()
    private val lastRecordedVisitByTab = mutableMapOf<String, RecordedTabVisit>()
    private val closedTabHistory = mutableListOf<ClosedTabSnapshot>()
    private var nextClosedTabUndoToken = 1L
    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            repository.ensureFreshInitialized()
        }
        viewModelScope.launch {
            refreshDownloadedLanguageModels()
        }
        viewModelScope.launch {
            repository.savedPasswordsFlow.collect {
                refreshMatchedCredentialsForActiveTab()
            }
        }
    }

    fun registerWebView(tabId: String, webView: WebView) {
        webViewRegistry[tabId] = webView
    }

    fun getWebView(tabId: String): WebView? = webViewRegistry[tabId]

    fun consumeSnackbarNotice() {
        _uiState.update { it.copy(snackbarNotice = null) }
    }

    fun consumeClosedTabUndo(token: Long) {
        _uiState.update { state ->
            if (state.pendingClosedTabUndo?.token == token) {
                state.copy(pendingClosedTabUndo = null)
            } else {
                state
            }
        }
    }

    // --- Dedicated Search Screen & Search-Engine Suggestions ---

    fun openSearchScreen(prefillCurrentUrl: Boolean = false) {
        _uiState.update {
            it.copy(
                isSearchScreenOpen = true,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isPasswordManagerOpen = false,
                isSettingsScreenOpen = false,
                isMenuSheetOpen = false,
                isTabSwitcherOpen = false,
                isAutomationOpen = false,
                isToolbarVisible = true,
                searchQuery = "",
                searchSuggestions = emptyList(),
                isLoadingSuggestions = false
            )
        }
    }

    fun closeSearchScreen() {
        suggestionJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchScreenOpen = false,
                isLoadingSuggestions = false
            )
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        _uiState.update {
            it.copy(
                searchQuery = newQuery,
                searchSuggestions = if (newQuery.isBlank()) emptyList() else it.searchSuggestions,
                isLoadingSuggestions = newQuery.isNotBlank()
            )
        }
        triggerSuggestionsFetch(newQuery, preferencesState.value.searchEngine)
    }

    fun selectSearchEngine(engine: SearchEngineOption) {
        viewModelScope.launch {
            repository.setSearchEngine(engine)
            val currentQ = _uiState.value.searchQuery
            if (currentQ.isNotBlank()) {
                triggerSuggestionsFetch(currentQ, engine)
            }
        }
    }

    private fun triggerSuggestionsFetch(query: String, engine: SearchEngineOption) {
        suggestionJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(searchSuggestions = emptyList(), isLoadingSuggestions = false)
            }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(160)
            val results = suggestionEngine.fetchSuggestions(trimmed, engine)
            _uiState.update { state ->
                if (state.searchQuery.trim() == trimmed) {
                    state.copy(
                        searchSuggestions = results,
                        isLoadingSuggestions = false
                    )
                } else {
                    state
                }
            }
        }
    }

    fun deleteSearchHistoryItem(id: Long) {
        viewModelScope.launch { repository.deleteSearchHistoryItem(id) }
    }

    fun clearAllSearchHistory() {
        viewModelScope.launch { repository.clearAllSearchHistory() }
    }

    fun submitNavigation(rawInput: String, targetTabId: String = _uiState.value.activeTabId) {
        val trimmedInput = rawInput.trim()
        if (trimmedInput.isEmpty()) return

        val prefs = preferencesState.value
        val normalized = privacyManager.normalizeAndUpgradeUrl(
            rawInput = trimmedInput,
            httpsUpgradeEnabled = prefs.httpsUpgradeEnabled,
            searchEngineTemplate = prefs.searchEngineTemplate
        )

        val targetTab = _uiState.value.tabs.firstOrNull { it.id == targetTabId }
        if (!normalized.isBuiltInScheme && targetTab?.isPrivate != true) {
            viewModelScope.launch {
                repository.recordSearchQuery(
                    query = trimmedInput,
                    engineName = prefs.searchEngine.displayName
                )
            }
        }

        if (normalized.wasUpgradedToHttps) {
            viewModelScope.launch { repository.recordHttpsUpgrade() }
        }

        suggestionJob?.cancel()
        lastRecordedVisitByTab.remove(targetTabId)
        _tabTranslationStates.update { it - targetTabId }

        val isHomeDest = normalized.finalUrl.equals("apex://home", ignoreCase = true)
        _uiState.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == targetTabId) {
                    tab.copy(
                        url = normalized.finalUrl,
                        requestedUrl = normalized.finalUrl,
                        loadRequestId = tab.loadRequestId + 1L,
                        title = if (isHomeDest) {
                            "Home"
                        } else {
                            deriveShortTitle(normalized.finalUrl)
                        },
                        blockedCountOnPage = 0,
                        isHttps = normalized.finalUrl.startsWith("https://") || normalized.isBuiltInScheme,
                        wasUpgradedToHttps = normalized.wasUpgradedToHttps,
                        isLoading = !isHomeDest,
                        progress = if (isHomeDest) 0f else 0.06f
                    )
                } else {
                    tab
                }
            }
            state.copy(
                tabs = updatedTabs,
                isSearchScreenOpen = false,
                isLoadingSuggestions = false,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isSettingsScreenOpen = false,
                isTabSwitcherOpen = false,
                isToolbarVisible = true
            )
        }
    }

    fun recordHttpsUpgradeStat() {
        viewModelScope.launch { repository.recordHttpsUpgrade() }
    }

    fun openLinkInNewTab(
        rawUrl: String,
        inGroup: Boolean = false,
        isPrivate: Boolean = false,
        switchToTab: Boolean = true
    ) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return

        val prefs = preferencesState.value
        val normalized = privacyManager.normalizeAndUpgradeUrl(
            rawInput = trimmed,
            httpsUpgradeEnabled = prefs.httpsUpgradeEnabled,
            searchEngineTemplate = prefs.searchEngineTemplate
        )

        _uiState.update { state ->
            val active = state.activeTab
            var updatedGroups = state.tabGroups
            var targetGroupId: String? = null
            var chosenColor: TabGroupColorOption? = null

            if (inGroup) {
                if (active.groupId != null) {
                    targetGroupId = active.groupId
                    chosenColor = state.tabGroups.firstOrNull { it.id == targetGroupId }?.colorOption ?: active.tabColor
                } else {
                    val newGrpId = "grp_${UUID.randomUUID()}"
                    val grpNumber = state.tabGroups.size + 1
                    chosenColor = active.tabColor ?: TabGroupColorOption.entries[
                        state.tabGroups.size % TabGroupColorOption.entries.size
                    ]
                    val newGrp = TabGroupInfo(
                        id = newGrpId,
                        title = "Group $grpNumber",
                        colorOption = chosenColor
                    )
                    updatedGroups = state.tabGroups + newGrp
                    targetGroupId = newGrpId
                }
            }

            val newTab = BrowserTab(
                title = deriveShortTitle(normalized.finalUrl),
                url = normalized.finalUrl,
                requestedUrl = normalized.finalUrl,
                loadRequestId = 1L,
                isPrivate = isPrivate,
                groupId = targetGroupId,
                tabColor = chosenColor,
                isLoading = true,
                progress = 0.06f,
                isHttps = normalized.finalUrl.startsWith("https://") || normalized.isBuiltInScheme,
                wasUpgradedToHttps = normalized.wasUpgradedToHttps
            )

            val baseTabs = if (inGroup && active.groupId == null && targetGroupId != null) {
                state.tabs.map { tab ->
                    if (tab.id == active.id) {
                        tab.copy(groupId = targetGroupId, tabColor = tab.tabColor ?: chosenColor)
                    } else {
                        tab
                    }
                }
            } else {
                state.tabs
            }

            val noticeText = when {
                isPrivate -> "Opened in Private tab"
                inGroup -> "Opened in new tab in group"
                else -> "Opened in new tab"
            }

            state.copy(
                previousTabIndex = state.activeTabIndex,
                tabs = baseTabs + newTab,
                tabGroups = updatedGroups,
                activeTabId = if (switchToTab) newTab.id else state.activeTabId,
                isHistoryScreenOpen = if (switchToTab) false else state.isHistoryScreenOpen,
                isIncognitoUnlocked = state.isIncognitoUnlocked || isPrivate,
                isToolbarVisible = true,
                snackbarNotice = noticeText
            )
        }
    }

    fun addLinkToReadingList(title: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val cleanTitle = title.trim().ifEmpty { deriveShortTitle(cleanUrl) }
        viewModelScope.launch {
            repository.saveBookmark(url = cleanUrl, title = cleanTitle)
            _uiState.update {
                it.copy(snackbarNotice = "Added '$cleanTitle' to reading list")
            }
        }
    }

    fun onWebViewPageStarted(tabId: String, url: String) {
        if (url.startsWith("data:") || url.isBlank()) return
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        val effectiveUrl = mapBaseUrlBackToScheme(url, tab.url)
                        tab.copy(
                            url = effectiveUrl,
                            isLoading = true,
                            progress = tab.progress.coerceAtLeast(0.08f),
                            isHttps = effectiveUrl.startsWith("https://") || effectiveUrl.startsWith("apex://")
                        )
                    } else {
                        tab
                    }
                }
            )
        }
    }

    fun onWebViewUrlCommitted(tabId: String, url: String) {
        if (url.startsWith("data:") || url.isBlank()) return
        val currentTab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        val effectiveUrl = mapBaseUrlBackToScheme(url, currentTab.url)
        val isHttps = effectiveUrl.startsWith("https://") || effectiveUrl.startsWith("apex://")
        if (currentTab.url == effectiveUrl && currentTab.isHttps == isHttps) return
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(
                            url = effectiveUrl,
                            isHttps = isHttps
                        )
                    } else {
                        tab
                    }
                }
            )
        }
    }

    fun onWebViewProgress(tabId: String, progress: Int) {
        val wv = webViewRegistry[tabId]
        val existingTab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        val isDone = progress >= 100
        val targetProgress = (progress / 100f).coerceIn(0f, 1f)
        val liveUrl = wv?.url?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { mapBaseUrlBackToScheme(it, existingTab.url) }
            ?: existingTab.url
        val liveTitle = wv?.title?.takeIf { it.isNotBlank() } ?: existingTab.title
        val canGoBack = wv?.canGoBack() ?: existingTab.canGoBack
        val canGoForward = wv?.canGoForward() ?: existingTab.canGoForward

        if (!isDone &&
            existingTab.isLoading == !isDone &&
            kotlin.math.abs(existingTab.progress - targetProgress) < 0.05f &&
            existingTab.url == liveUrl &&
            existingTab.canGoBack == canGoBack &&
            existingTab.canGoForward == canGoForward
        ) {
            return
        }

        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(
                            url = liveUrl,
                            title = if (isDone) liveTitle else tab.title,
                            progress = targetProgress,
                            isLoading = !isDone,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward
                        )
                    } else {
                        tab
                    }
                }
            )
        }
    }

    fun onWebViewPageFinished(
        tabId: String,
        title: String?,
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        var finishedUrl = ""
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        val effectiveUrl = if (!url.isNullOrBlank()) {
                            mapBaseUrlBackToScheme(url, tab.url)
                        } else {
                            tab.url
                        }
                        finishedUrl = effectiveUrl
                        tab.copy(
                            title = title?.takeIf { it.isNotBlank() } ?: deriveShortTitle(effectiveUrl),
                            url = effectiveUrl,
                            isLoading = false,
                            progress = 1f,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            lastActiveTimeMillis = System.currentTimeMillis()
                        )
                    } else {
                        tab
                    }
                }
            )
        }

        val finishedTab = _uiState.value.tabs.firstOrNull { it.id == tabId }
        if (tabId == _uiState.value.activeTabId) {
            refreshMatchedCredentialsForActiveTab()
        }
        val wv = webViewRegistry[tabId]
        if (wv != null && tabId == _uiState.value.activeTabId && finishedTab?.isPrivate != true) {
            performanceOptimizer.preloadLikelyNextPages(
                webView = wv,
                enabled = preferencesState.value.aggressivePreloadEnabled
            ) { candidates ->
                _uiState.update { it.copy(preloadCandidates = candidates) }
            }
        }

        // Record browsing history for non-private tabs (deduplicated across multiple WebView onPageFinished callbacks)
        if (finishedTab != null &&
            !finishedTab.isPrivate &&
            !finishedTab.isHomePage &&
            finishedUrl.isNotBlank() &&
            !finishedUrl.equals("about:blank", ignoreCase = true) &&
            !finishedUrl.startsWith("data:", ignoreCase = true)
        ) {
            val canonicalUrl = repository.normalizeHistoryUrl(finishedUrl)
            if (canonicalUrl.isNotBlank()) {
                val now = System.currentTimeMillis()
                val host = canonicalUrl.substringAfter("://", canonicalUrl)
                    .substringBefore('/')
                    .removePrefix("www.")
                    .lowercase()
                val prev = lastRecordedVisitByTab[tabId]

                val isSameNavigationVisit = prev != null && (
                    prev.canonicalUrl.equals(canonicalUrl, ignoreCase = true) ||
                        (prev.loadRequestId == finishedTab.loadRequestId &&
                            (now - prev.recordedAtMillis) < 12_000L) ||
                        ((now - prev.recordedAtMillis) < 4_500L)
                )

                val previousUrlInSameLoad = if (isSameNavigationVisit) prev?.canonicalUrl else null
                lastRecordedVisitByTab[tabId] = RecordedTabVisit(
                    loadRequestId = finishedTab.loadRequestId,
                    canonicalUrl = canonicalUrl,
                    host = host,
                    recordedAtMillis = if (isSameNavigationVisit && prev != null) prev.recordedAtMillis else now
                )

                val shouldIncrementVisit = !isSameNavigationVisit
                val pageTitle = finishedTab.title
                val pageIsHttps = finishedTab.isHttps
                val pageBlockedCount = finishedTab.blockedCountOnPage

                viewModelScope.launch {
                    repository.recordPageVisit(
                        url = canonicalUrl,
                        title = pageTitle,
                        isHttps = pageIsHttps,
                        trackersBlockedOnVisit = pageBlockedCount,
                        incrementVisitCount = shouldIncrementVisit,
                        previousUrlInSameLoad = previousUrlInSameLoad
                    )
                }
            }
        }
    }

    fun onSubresourceBlocked(
        tabId: String,
        pageHost: String,
        blockedDomain: String,
        category: String,
        bytesSaved: Long,
        timeSavedMs: Long
    ) {
        incrementTabBlockedCount(tabId, 1)
        viewModelScope.launch {
            repository.recordBlockedItems(
                pageHost = pageHost,
                trackersDelta = if (category == "Tracker") 1 else 0,
                adsDelta = if (category == "Ad Network") 1 else 0,
                bytesDelta = bytesSaved,
                timeMsDelta = timeSavedMs,
                sampleDomain = blockedDomain,
                sampleCategory = category
            )
        }
    }

    private fun incrementTabBlockedCount(tabId: String, delta: Int) {
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(blockedCountOnPage = tab.blockedCountOnPage + delta)
                    } else {
                        tab
                    }
                }
            )
        }
    }

    fun onWebViewScrollDelta(dy: Int) {
        // Keep the top toolbar stably visible during WebView scrolling so Scaffold
        // never animates innerPadding.top and resizes the WebView mid-gesture.
        if (!_uiState.value.isToolbarVisible) {
            _uiState.update { it.copy(isToolbarVisible = true) }
        }
    }

    // --- Tab & Draggable Tab Group Management ---

    fun selectTab(tabId: String) {
        _uiState.update { state ->
            val prevIdx = state.activeTabIndex
            val targetTab = state.tabs.firstOrNull { it.id == tabId } ?: return@update state
            state.copy(
                previousTabIndex = prevIdx,
                activeTabId = tabId,
                expandedGroupId = null,
                isTabSwitcherOpen = false,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isPasswordManagerOpen = false,
                isSearchScreenOpen = false,
                isToolbarVisible = true
            )
        }
        refreshMatchedCredentialsForActiveTab()
    }

    fun selectNextTab() {
        val state = _uiState.value
        val nextIndex = state.activeTabIndex + 1
        if (nextIndex in state.tabs.indices) {
            selectTab(state.tabs[nextIndex].id)
        }
    }

    fun selectPreviousTab() {
        val state = _uiState.value
        val prevIndex = state.activeTabIndex - 1
        if (prevIndex in state.tabs.indices) {
            selectTab(state.tabs[prevIndex].id)
        }
    }

    fun createNewTab(
        initialUrl: String = "apex://home",
        groupId: String? = null,
        stayInSwitcher: Boolean = groupId != null
    ) {
        val inheritedColor = groupId?.let { gId ->
            _uiState.value.tabGroups.firstOrNull { it.id == gId }?.colorOption
        }
        val newTab = BrowserTab(
            title = if (initialUrl == "apex://home") "Home" else deriveShortTitle(initialUrl),
            url = initialUrl,
            groupId = groupId,
            tabColor = inheritedColor
        )
        _uiState.update { state ->
            state.copy(
                previousTabIndex = state.activeTabIndex,
                tabs = state.tabs + newTab,
                activeTabId = if (!stayInSwitcher) newTab.id else state.activeTabId,
                isTabSwitcherOpen = stayInSwitcher,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isSettingsScreenOpen = false,
                isSearchScreenOpen = false,
                isToolbarVisible = true
            )
        }
        if (initialUrl != "apex://home") {
            submitNavigation(initialUrl, newTab.id)
        }
    }

    fun createNewIncognitoTab(
        initialUrl: String = "apex://home",
        stayInSwitcher: Boolean = false
    ) {
        val newTab = BrowserTab(
            title = if (initialUrl == "apex://home") "Incognito" else deriveShortTitle(initialUrl),
            url = initialUrl,
            isPrivate = true
        )
        _uiState.update { state ->
            state.copy(
                previousTabIndex = state.activeTabIndex,
                tabs = state.tabs + newTab,
                activeTabId = if (!stayInSwitcher) newTab.id else state.activeTabId,
                isTabSwitcherOpen = stayInSwitcher,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isSettingsScreenOpen = false,
                isMenuSheetOpen = false,
                isSearchScreenOpen = false,
                isIncognitoUnlocked = true,
                isToolbarVisible = true,
                snackbarNotice = "Incognito mode active • History, cookies & site data will not be saved"
            )
        }
        if (initialUrl != "apex://home") {
            submitNavigation(initialUrl, newTab.id)
        }
    }

    fun closeAllIncognitoTabs() {
        val currentState = _uiState.value
        val incognitoIds = currentState.tabs.filter { it.isPrivate }.map { it.id }.toSet()
        if (incognitoIds.isEmpty()) {
            _uiState.update { it.copy(isIncognitoUnlocked = false) }
            return
        }

        incognitoIds.forEach { id ->
            lastRecordedVisitByTab.remove(id)
            webViewRegistry.remove(id)?.let { wv ->
                destroyWebViewSafely(wv, isIncognito = true)
            }
        }
        performanceOptimizer.wipeIncognitoSessionStorage()

        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.isPrivate }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(title = "Home", url = "apex://home")
                state.copy(
                    tabs = listOf(fresh),
                    tabGroups = emptyList(),
                    expandedGroupId = null,
                    editingGroupId = null,
                    activeTabId = fresh.id,
                    isIncognitoUnlocked = false,
                    snackbarNotice = "Incognito session ended • Cookies & site data wiped"
                )
            } else {
                val (cleanTabs, cleanGroups) = normalizeGroups(remaining, state.tabGroups)
                val nextActiveId = if (state.activeTabId in incognitoIds) {
                    cleanTabs.last().id
                } else {
                    state.activeTabId
                }
                state.copy(
                    tabs = cleanTabs,
                    tabGroups = cleanGroups,
                    activeTabId = nextActiveId,
                    isIncognitoUnlocked = false,
                    snackbarNotice = "Incognito session ended • Cookies & site data wiped"
                )
            }
        }
    }

    fun closeSelectedTabs(tabIds: Set<String>) {
        if (tabIds.isEmpty()) return
        val currentTabs = _uiState.value.tabs
        val closingTabs = currentTabs.filter { it.id in tabIds }
        val closedAnyIncognito = closingTabs.any { it.isPrivate }

        closingTabs.forEach { tab ->
            lastRecordedVisitByTab.remove(tab.id)
            webViewRegistry.remove(tab.id)?.let { wv ->
                destroyWebViewSafely(wv, isIncognito = tab.isPrivate)
            }
        }

        val remainingAfterClose = currentTabs.filterNot { it.id in tabIds }
        val anyIncognitoLeft = remainingAfterClose.any { it.isPrivate }
        if (closedAnyIncognito && !anyIncognitoLeft) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }

        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id in tabIds }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(title = "Home", url = "apex://home")
                state.copy(
                    tabs = listOf(fresh),
                    tabGroups = emptyList(),
                    expandedGroupId = null,
                    editingGroupId = null,
                    activeTabId = fresh.id,
                    isIncognitoUnlocked = false,
                    snackbarNotice = "Closed ${closingTabs.size} tab${if (closingTabs.size == 1) "" else "s"}"
                )
            } else {
                val (cleanTabs, cleanGroups) = normalizeGroups(remaining, state.tabGroups)
                val nextActiveId = if (state.activeTabId in tabIds) {
                    cleanTabs.last().id
                } else {
                    state.activeTabId
                }
                state.copy(
                    tabs = cleanTabs,
                    tabGroups = cleanGroups,
                    activeTabId = nextActiveId,
                    isIncognitoUnlocked = anyIncognitoLeft,
                    snackbarNotice = "Closed ${closingTabs.size} tab${if (closingTabs.size == 1) "" else "s"}"
                )
            }
        }
    }

    fun groupSelectedTabs(tabIds: Set<String>) {
        if (tabIds.isEmpty()) return
        _uiState.update { state ->
            val newGroupId = "grp_${UUID.randomUUID()}"
            val groupNumber = state.tabGroups.size + 1
            val defaultColor = TabGroupColorOption.entries[
                state.tabGroups.size % TabGroupColorOption.entries.size
            ]
            val newGroup = TabGroupInfo(
                id = newGroupId,
                title = "Group $groupNumber",
                colorOption = defaultColor
            )
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id in tabIds) {
                    tab.copy(groupId = newGroupId, tabColor = tab.tabColor ?: defaultColor)
                } else {
                    tab
                }
            }
            val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups + newGroup)
            state.copy(
                tabs = cleanTabs,
                tabGroups = cleanGroups,
                snackbarNotice = "Grouped ${tabIds.size} tab${if (tabIds.size == 1) "" else "s"} into ${newGroup.title}"
            )
        }
    }

    fun createNewTabGroupFromSwitcher() {
        _uiState.update { state ->
            val newGroupId = "grp_${UUID.randomUUID()}"
            val groupNumber = state.tabGroups.size + 1
            val defaultColor = TabGroupColorOption.entries[
                state.tabGroups.size % TabGroupColorOption.entries.size
            ]
            val newGroup = TabGroupInfo(
                id = newGroupId,
                title = "Group $groupNumber",
                colorOption = defaultColor
            )

            val ungroupedTabs = state.tabs.filter { it.groupId == null }
            val updatedTabs = if (ungroupedTabs.size >= 2) {
                val idsToGroup = ungroupedTabs.takeLast(2).map { it.id }.toSet()
                state.tabs.map { tab ->
                    if (tab.id in idsToGroup) {
                        tab.copy(groupId = newGroupId, tabColor = tab.tabColor ?: defaultColor)
                    } else {
                        tab
                    }
                }
            } else if (ungroupedTabs.size == 1) {
                val existingUngroupedId = ungroupedTabs.single().id
                val companionTab = BrowserTab(
                    title = "Home",
                    url = "apex://home",
                    groupId = newGroupId,
                    tabColor = defaultColor
                )
                state.tabs.map { tab ->
                    if (tab.id == existingUngroupedId) {
                        tab.copy(groupId = newGroupId, tabColor = tab.tabColor ?: defaultColor)
                    } else {
                        tab
                    }
                } + companionTab
            } else {
                val t1 = BrowserTab(title = "Home", url = "apex://home", groupId = newGroupId, tabColor = defaultColor)
                val t2 = BrowserTab(title = "Home", url = "apex://home", groupId = newGroupId, tabColor = defaultColor)
                state.tabs + t1 + t2
            }

            val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups + newGroup)
            state.copy(
                tabs = cleanTabs,
                tabGroups = cleanGroups,
                editingGroupId = newGroupId,
                isTabSwitcherOpen = true
            )
        }
    }

    fun mergeGridEntriesIntoGroup(draggedKey: String, targetKey: String) {
        if (draggedKey == targetKey) return

        _uiState.update { state ->
            val draggedIsGroup = draggedKey.startsWith("group_")
            val targetIsGroup = targetKey.startsWith("group_")

            val draggedId = draggedKey.removePrefix("single_").removePrefix("group_")
            val targetId = targetKey.removePrefix("single_").removePrefix("group_")

            when {
                !draggedIsGroup && !targetIsGroup -> {
                    val newGroupId = "grp_${UUID.randomUUID()}"
                    val groupNumber = state.tabGroups.size + 1
                    val targetTab = state.tabs.firstOrNull { it.id == targetId }
                    val draggedTab = state.tabs.firstOrNull { it.id == draggedId }
                    val chosenColor = targetTab?.tabColor
                        ?: draggedTab?.tabColor
                        ?: TabGroupColorOption.entries[
                            state.tabGroups.size % TabGroupColorOption.entries.size
                        ]
                    val newGroup = TabGroupInfo(
                        id = newGroupId,
                        title = "Group $groupNumber",
                        colorOption = chosenColor
                    )

                    val updatedTabs = state.tabs.map { tab ->
                        if (tab.id == targetId || tab.id == draggedId) {
                            tab.copy(groupId = newGroupId, tabColor = tab.tabColor ?: chosenColor)
                        } else {
                            tab
                        }
                    }
                    normalizeGroups(
                        tabs = updatedTabs,
                        groups = state.tabGroups + newGroup
                    ).let { (cleanTabs, cleanGroups) ->
                        state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
                    }
                }

                !draggedIsGroup && targetIsGroup -> {
                    val targetGroupColor = state.tabGroups.firstOrNull { it.id == targetId }?.colorOption
                    val updatedTabs = state.tabs.map { tab ->
                        if (tab.id == draggedId) {
                            tab.copy(groupId = targetId, tabColor = tab.tabColor ?: targetGroupColor)
                        } else {
                            tab
                        }
                    }
                    normalizeGroups(updatedTabs, state.tabGroups).let { (cleanTabs, cleanGroups) ->
                        state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
                    }
                }

                draggedIsGroup && !targetIsGroup -> {
                    val draggedGroupColor = state.tabGroups.firstOrNull { it.id == draggedId }?.colorOption
                    val updatedTabs = state.tabs.map { tab ->
                        if (tab.id == targetId) {
                            tab.copy(groupId = draggedId, tabColor = tab.tabColor ?: draggedGroupColor)
                        } else {
                            tab
                        }
                    }
                    normalizeGroups(updatedTabs, state.tabGroups).let { (cleanTabs, cleanGroups) ->
                        state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
                    }
                }

                else -> {
                    val targetGroupColor = state.tabGroups.firstOrNull { it.id == targetId }?.colorOption
                    val updatedTabs = state.tabs.map { tab ->
                        if (tab.groupId == draggedId) {
                            tab.copy(groupId = targetId, tabColor = targetGroupColor ?: tab.tabColor)
                        } else {
                            tab
                        }
                    }
                    normalizeGroups(updatedTabs, state.tabGroups).let { (cleanTabs, cleanGroups) ->
                        state.copy(
                            tabs = cleanTabs,
                            tabGroups = cleanGroups,
                            expandedGroupId = if (state.expandedGroupId == draggedId) targetId else state.expandedGroupId
                        )
                    }
                }
            }
        }
    }

    fun moveGroupedTabToGridTarget(tabId: String, targetGridKey: String?) {
        if (targetGridKey == null) {
            ungroupTab(tabId)
            return
        }
        val currentTab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        val sourceGroupKey = currentTab.groupId?.let { "group_$it" }
        if (targetGridKey == sourceGroupKey || targetGridKey == "single_$tabId") {
            return
        }

        _uiState.update { state ->
            if (targetGridKey.startsWith("group_")) {
                val destGroupId = targetGridKey.removePrefix("group_")
                val destColor = state.tabGroups.firstOrNull { it.id == destGroupId }?.colorOption
                val updatedTabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(groupId = destGroupId, tabColor = destColor ?: tab.tabColor)
                    } else {
                        tab
                    }
                }
                val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups)
                state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
            } else {
                val otherTabId = targetGridKey.removePrefix("single_")
                val newGroupId = "grp_${UUID.randomUUID()}"
                val groupNumber = state.tabGroups.size + 1
                val chosenColor = TabGroupColorOption.entries[
                    state.tabGroups.size % TabGroupColorOption.entries.size
                ]
                val newGroup = TabGroupInfo(
                    id = newGroupId,
                    title = "Group $groupNumber",
                    colorOption = chosenColor
                )
                val updatedTabs = state.tabs.map { tab ->
                    if (tab.id == tabId || tab.id == otherTabId) {
                        tab.copy(groupId = newGroupId, tabColor = chosenColor)
                    } else {
                        tab
                    }
                }
                val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups + newGroup)
                state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
            }
        }
    }

    fun openGroupEditor(groupId: String) {
        _uiState.update { it.copy(editingGroupId = groupId) }
    }

    fun dismissGroupEditor() {
        _uiState.update { it.copy(editingGroupId = null) }
    }

    fun updateGroupTitleAndColor(
        groupId: String,
        newTitle: String,
        colorOption: TabGroupColorOption
    ) {
        _uiState.update { state ->
            val trimmed = newTitle.trim()
            val updatedGroups = state.tabGroups.map { grp ->
                if (grp.id == groupId) {
                    grp.copy(
                        title = trimmed.ifEmpty { grp.title },
                        colorOption = colorOption
                    )
                } else {
                    grp
                }
            }
            val updatedTabs = state.tabs.map { tab ->
                if (tab.groupId == groupId) {
                    tab.copy(tabColor = colorOption)
                } else {
                    tab
                }
            }
            state.copy(
                tabs = updatedTabs,
                tabGroups = updatedGroups,
                editingGroupId = null
            )
        }
    }

    fun cycleTabColor(tabId: String) {
        _uiState.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == tabId) {
                    val nextColor = tab.tabColor?.next() ?: TabGroupColorOption.COBALT
                    tab.copy(tabColor = nextColor)
                } else {
                    tab
                }
            }
            state.copy(tabs = updatedTabs)
        }
    }

    fun ungroupTab(tabId: String) {
        _uiState.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == tabId) tab.copy(groupId = null) else tab
            }
            val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups)
            val validExpanded = state.expandedGroupId?.takeIf { expId ->
                cleanGroups.any { it.id == expId }
            }
            val validEditing = state.editingGroupId?.takeIf { editId ->
                cleanGroups.any { it.id == editId }
            }
            state.copy(
                tabs = cleanTabs,
                tabGroups = cleanGroups,
                expandedGroupId = validExpanded,
                editingGroupId = validEditing
            )
        }
    }

    fun ungroupAllInGroup(groupId: String) {
        _uiState.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.groupId == groupId) tab.copy(groupId = null) else tab
            }
            val remainingGroups = state.tabGroups.filterNot { it.id == groupId }
            state.copy(
                tabs = updatedTabs,
                tabGroups = remainingGroups,
                expandedGroupId = if (state.expandedGroupId == groupId) null else state.expandedGroupId,
                editingGroupId = if (state.editingGroupId == groupId) null else state.editingGroupId
            )
        }
    }

    fun setExpandedGroup(groupId: String?) {
        _uiState.update { it.copy(expandedGroupId = groupId) }
    }

    fun closeGroup(groupId: String) {
        val currentTabs = _uiState.value.tabs
        val groupTabs = currentTabs.filter { it.groupId == groupId }
        val groupTabIds = groupTabs.map { it.id }.toSet()
        val closedAnyIncognito = groupTabs.any { it.isPrivate }

        groupTabs.forEach { tab ->
            lastRecordedVisitByTab.remove(tab.id)
            webViewRegistry.remove(tab.id)?.let { wv ->
                destroyWebViewSafely(wv, isIncognito = tab.isPrivate)
            }
        }

        val remainingTabsAfterClose = currentTabs.filterNot { it.id in groupTabIds }
        val incognitoSessionEnded = closedAnyIncognito && remainingTabsAfterClose.none { it.isPrivate }
        if (incognitoSessionEnded) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }

        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id in groupTabIds }
            val sessionNotice = if (incognitoSessionEnded) {
                "Incognito session ended • Cookies & site data wiped"
            } else {
                state.snackbarNotice
            }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(title = "Home", url = "apex://home")
                state.copy(
                    tabs = listOf(fresh),
                    tabGroups = emptyList(),
                    expandedGroupId = null,
                    editingGroupId = null,
                    activeTabId = fresh.id,
                    snackbarNotice = sessionNotice
                )
            } else {
                val (cleanTabs, cleanGroups) = normalizeGroups(remaining, state.tabGroups)
                val nextActive = if (state.activeTabId in groupTabIds) {
                    cleanTabs.last().id
                } else {
                    state.activeTabId
                }
                state.copy(
                    tabs = cleanTabs,
                    tabGroups = cleanGroups,
                    expandedGroupId = if (state.expandedGroupId == groupId) null else state.expandedGroupId,
                    editingGroupId = if (state.editingGroupId == groupId) null else state.editingGroupId,
                    activeTabId = nextActive,
                    snackbarNotice = sessionNotice
                )
            }
        }
    }

    fun closeTab(tabId: String) {
        val snapshotBeforeClose = _uiState.value
        val closingIndex = snapshotBeforeClose.tabs.indexOfFirst { it.id == tabId }
        val closingTab = if (closingIndex >= 0) snapshotBeforeClose.tabs[closingIndex] else null
        if (closingTab == null) return

        val closingGroupInfo = closingTab.groupId?.let { gId ->
            snapshotBeforeClose.tabGroups.firstOrNull { it.id == gId }
        }
        val undoToken = nextClosedTabUndoToken++

        lastRecordedVisitByTab.remove(tabId)
        webViewRegistry.remove(tabId)?.let { wv ->
            destroyWebViewSafely(wv, isIncognito = closingTab.isPrivate)
        }

        val remainingAfterClose = snapshotBeforeClose.tabs.filterNot { it.id == tabId }
        val incognitoSessionEnded = closingTab.isPrivate && remainingAfterClose.none { it.isPrivate }
        if (incognitoSessionEnded) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }

        var autoReplacementTabId: String? = null
        _uiState.update { state ->
            val closedTab = state.tabs.firstOrNull { it.id == tabId }
            val remaining = state.tabs.filterNot { it.id == tabId }
            val sessionNotice = if (incognitoSessionEnded) {
                "Incognito session ended • Cookies & site data wiped"
            } else {
                state.snackbarNotice
            }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(title = "Home", url = "apex://home")
                autoReplacementTabId = fresh.id
                state.copy(
                    tabs = listOf(fresh),
                    tabGroups = emptyList(),
                    expandedGroupId = null,
                    editingGroupId = null,
                    activeTabId = fresh.id,
                    isIncognitoUnlocked = false,
                    snackbarNotice = sessionNotice,
                    pendingClosedTabUndo = PendingClosedTabUndo(
                        token = undoToken,
                        tabTitle = closedTab?.title ?: closingTab.title
                    )
                )
            } else {
                val (cleanTabs, cleanGroups) = normalizeGroups(remaining, state.tabGroups)
                val nextActiveId = if (state.activeTabId == tabId) {
                    val closedGroupId = closedTab?.groupId
                    val siblingInGroup = closedGroupId?.let { gId ->
                        cleanTabs.lastOrNull { it.groupId == gId }
                    }
                    siblingInGroup?.id ?: cleanTabs.last().id
                } else {
                    state.activeTabId
                }
                val validExpanded = state.expandedGroupId?.takeIf { expId ->
                    cleanGroups.any { it.id == expId }
                }
                val validEditing = state.editingGroupId?.takeIf { editId ->
                    cleanGroups.any { it.id == editId }
                }
                state.copy(
                    tabs = cleanTabs,
                    tabGroups = cleanGroups,
                    expandedGroupId = validExpanded,
                    editingGroupId = validEditing,
                    activeTabId = nextActiveId,
                    isIncognitoUnlocked = cleanTabs.any { it.isPrivate },
                    snackbarNotice = sessionNotice,
                    pendingClosedTabUndo = PendingClosedTabUndo(
                        token = undoToken,
                        tabTitle = closedTab?.title ?: closingTab.title
                    )
                )
            }
        }

        closedTabHistory.add(
            ClosedTabSnapshot(
                token = undoToken,
                tab = closingTab,
                originalIndex = closingIndex,
                previousActiveTabId = snapshotBeforeClose.activeTabId,
                previousExpandedGroupId = snapshotBeforeClose.expandedGroupId,
                groupInfo = closingGroupInfo,
                autoCreatedReplacementTabId = autoReplacementTabId
            )
        )
        if (closedTabHistory.size > 10) {
            closedTabHistory.removeAt(0)
        }
    }

    fun undoLastClosedTab() {
        val snapshot = closedTabHistory.removeLastOrNull() ?: return
        _uiState.update { state ->
            val baseTabs = if (
                snapshot.autoCreatedReplacementTabId != null &&
                state.tabs.size == 1 &&
                state.tabs.first().id == snapshot.autoCreatedReplacementTabId
            ) {
                emptyList()
            } else {
                state.tabs
            }

            if (baseTabs.any { it.id == snapshot.tab.id }) {
                return@update state.copy(pendingClosedTabUndo = null)
            }

            val restoredTabs = baseTabs.toMutableList().apply {
                add(snapshot.originalIndex.coerceIn(0, size), snapshot.tab)
            }
            val restoredGroups = if (
                snapshot.groupInfo != null &&
                state.tabGroups.none { it.id == snapshot.groupInfo.id }
            ) {
                state.tabGroups + snapshot.groupInfo
            } else {
                state.tabGroups
            }
            val nextActiveId = when {
                snapshot.autoCreatedReplacementTabId != null -> snapshot.tab.id
                snapshot.previousActiveTabId == snapshot.tab.id -> snapshot.tab.id
                restoredTabs.any { it.id == state.activeTabId } -> state.activeTabId
                else -> snapshot.tab.id
            }
            val nextExpandedGroupId = when {
                state.expandedGroupId != null && restoredGroups.any { it.id == state.expandedGroupId } -> {
                    state.expandedGroupId
                }
                snapshot.previousExpandedGroupId != null &&
                    restoredGroups.any { it.id == snapshot.previousExpandedGroupId } -> {
                    snapshot.previousExpandedGroupId
                }
                else -> null
            }

            state.copy(
                tabs = restoredTabs,
                tabGroups = restoredGroups,
                activeTabId = nextActiveId,
                expandedGroupId = nextExpandedGroupId,
                isIncognitoUnlocked = restoredTabs.any { it.isPrivate },
                pendingClosedTabUndo = null
            )
        }
    }

    private fun normalizeGroups(
        tabs: List<BrowserTab>,
        groups: List<TabGroupInfo>
    ): Pair<List<BrowserTab>, List<TabGroupInfo>> {
        val countsByGroup = tabs.mapNotNull { it.groupId }.groupingBy { it }.eachCount()
        val knownGroupIds = groups.map { it.id }.toSet()
        val validGroupIds = countsByGroup.filter { (gId, count) ->
            if (gId in knownGroupIds) count >= 1 else count >= 2
        }.keys
        val cleanedTabs = tabs.map { tab ->
            if (tab.groupId != null && tab.groupId !in validGroupIds) {
                tab.copy(groupId = null)
            } else {
                tab
            }
        }
        val cleanedGroups = groups.filter { it.id in validGroupIds }
        return cleanedTabs to cleanedGroups
    }

    fun resetAllTabs() {
        val currentTabs = _uiState.value.tabs
        val hadIncognito = currentTabs.any { it.isPrivate }
        currentTabs.forEach { tab ->
            val wv = webViewRegistry.remove(tab.id)
            if (wv != null) {
                destroyWebViewSafely(wv, isIncognito = tab.isPrivate)
            }
        }
        webViewRegistry.values.forEach { destroyWebViewSafely(it, isIncognito = false) }
        webViewRegistry.clear()
        lastRecordedVisitByTab.clear()
        closedTabHistory.clear()
        _tabTranslationStates.value = emptyMap()
        if (hadIncognito) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }
        val fresh = BrowserTab(title = "Home", url = "apex://home")
        _uiState.update {
            it.copy(
                tabs = listOf(fresh),
                tabGroups = emptyList(),
                expandedGroupId = null,
                editingGroupId = null,
                activeTabId = fresh.id,
                isIncognitoUnlocked = false,
                isTabSwitcherOpen = false,
                pendingClosedTabUndo = null,
                snackbarNotice = if (hadIncognito) {
                    "All tabs closed • Incognito session data wiped"
                } else {
                    it.snackbarNotice
                }
            )
        }
    }

    // --- Context-Aware Bottom Bar Controls (Home, Bookmarks, Search, Tabs) ---

    fun navigateBack() {
        val active = _uiState.value.activeTab
        val wv = webViewRegistry[active.id]
        if (!active.isHomePage && wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (!active.isHomePage) {
            wv?.setTag(-19048102, null)
            lastRecordedVisitByTab.remove(active.id)
            _tabTranslationStates.update { it - active.id }
            _uiState.update { state ->
                state.copy(
                    tabs = state.tabs.map { tab ->
                        if (tab.id == active.id) {
                            tab.copy(url = "apex://home", title = "Home", isLoading = false, blockedCountOnPage = 0)
                        } else {
                            tab
                        }
                    },
                    isToolbarVisible = true
                )
            }
        }
    }

    fun onBottomHomePressed() {
        val currentState = _uiState.value
        webViewRegistry[currentState.activeTabId]?.setTag(-19048102, null)
        lastRecordedVisitByTab.remove(currentState.activeTabId)
        _tabTranslationStates.update { it - currentState.activeTabId }
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == currentState.activeTabId && !tab.isHomePage) {
                        tab.copy(
                            url = "apex://home",
                            title = "Home",
                            isLoading = false,
                            blockedCountOnPage = 0
                        )
                    } else {
                        tab
                    }
                },
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isPasswordManagerOpen = false,
                isSettingsScreenOpen = false,
                isMenuSheetOpen = false,
                isSearchScreenOpen = false,
                isTabSwitcherOpen = false,
                isAutomationOpen = false,
                isToolbarVisible = true,
                matchedCredentialsForActiveTab = emptyList(),
                pendingCredentialPrompt = null
            )
        }
    }

    fun onBottomBookmarksPressed() {
        val state = _uiState.value
        if (state.isBookmarksScreenOpen) {
            _uiState.update { it.copy(isBookmarksScreenOpen = false, isMenuSheetOpen = false) }
            return
        }
        if (state.activeTab.isHomePage ||
            state.isSettingsScreenOpen ||
            state.isHistoryScreenOpen ||
            state.isDownloadsScreenOpen ||
            state.isPasswordManagerOpen
        ) {
            _uiState.update {
                it.copy(
                    isBookmarksScreenOpen = true,
                    isHistoryScreenOpen = false,
                    isDownloadsScreenOpen = false,
                    isPasswordManagerOpen = false,
                    isSettingsScreenOpen = false,
                    isMenuSheetOpen = false,
                    isSearchScreenOpen = false,
                    isTabSwitcherOpen = false,
                    isAutomationOpen = false,
                    isToolbarVisible = true
                )
            }
        } else {
            val active = state.activeTab
            viewModelScope.launch {
                val existing = repository.getBookmarkByUrl(active.url)
                if (existing != null) {
                    repository.deleteBookmark(existing.id)
                    _uiState.update {
                        it.copy(
                            isTabSwitcherOpen = false,
                            isHistoryScreenOpen = false,
                            isDownloadsScreenOpen = false,
                            isSettingsScreenOpen = false,
                            isMenuSheetOpen = false,
                            isSearchScreenOpen = false,
                            snackbarNotice = "Removed bookmark '${active.title}'"
                        )
                    }
                } else {
                    repository.saveBookmark(url = active.url, title = active.title)
                    _uiState.update {
                        it.copy(
                            isTabSwitcherOpen = false,
                            isHistoryScreenOpen = false,
                            isDownloadsScreenOpen = false,
                            isSettingsScreenOpen = false,
                            isMenuSheetOpen = false,
                            isSearchScreenOpen = false,
                            snackbarNotice = "Bookmarked '${active.title}'"
                        )
                    }
                }
            }
        }
    }

    fun setBookmarksScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isBookmarksScreenOpen = open,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isPasswordManagerOpen = if (open) false else it.isPasswordManagerOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setHistoryScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isHistoryScreenOpen = open,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isPasswordManagerOpen = if (open) false else it.isPasswordManagerOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isAutomationOpen = if (open) false else it.isAutomationOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setDownloadsScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isDownloadsScreenOpen = open,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isPasswordManagerOpen = if (open) false else it.isPasswordManagerOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isAutomationOpen = if (open) false else it.isAutomationOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setPasswordManagerOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isPasswordManagerOpen = open,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isAutomationOpen = if (open) false else it.isAutomationOpen,
                isToolbarVisible = true
            )
        }
    }

    fun decryptPassword(entity: SavedPasswordEntity): String {
        return repository.decryptPassword(entity)
    }

    fun saveOrUpdatePassword(
        id: Long = 0L,
        siteName: String,
        siteUrl: String,
        username: String,
        plainPassword: String,
        notes: String = "",
        category: String = "Personal",
        isFavorite: Boolean = false
    ) {
        if (username.isBlank() || plainPassword.isEmpty()) return
        viewModelScope.launch {
            repository.saveOrUpdatePassword(
                id = id,
                siteName = siteName,
                siteUrl = siteUrl,
                username = username,
                plainPassword = plainPassword,
                notes = notes,
                category = category,
                isFavorite = isFavorite
            )
            refreshMatchedCredentialsForActiveTab()
            _uiState.update {
                it.copy(
                    snackbarNotice = if (id > 0L) "Password updated in vault" else "Password saved to encrypted vault"
                )
            }
        }
    }

    fun togglePasswordFavorite(id: Long) {
        viewModelScope.launch { repository.togglePasswordFavorite(id) }
    }

    fun deleteSavedPassword(id: Long) {
        viewModelScope.launch {
            repository.deleteSavedPassword(id)
            refreshMatchedCredentialsForActiveTab()
            _uiState.update { it.copy(snackbarNotice = "Password removed from vault") }
        }
    }

    fun clearAllSavedPasswords() {
        viewModelScope.launch {
            repository.clearAllSavedPasswords()
            _uiState.update {
                it.copy(
                    matchedCredentialsForActiveTab = emptyList(),
                    snackbarNotice = "Password vault cleared"
                )
            }
        }
    }

    fun importPasswordsFromCsv(rawCsv: String) {
        viewModelScope.launch {
            val count = repository.importPasswordsFromCsv(rawCsv)
            refreshMatchedCredentialsForActiveTab()
            _uiState.update {
                it.copy(
                    snackbarNotice = if (count > 0) {
                        "Imported $count password${if (count == 1) "" else "s"}"
                    } else {
                        "No valid credentials found in CSV"
                    }
                )
            }
        }
    }

    fun exportPasswordsToCsv(onExportReady: (String) -> Unit) {
        viewModelScope.launch {
            val csv = repository.exportPasswordsToCsv()
            onExportReady(csv)
        }
    }

    fun togglePasswordAutofill(enabled: Boolean) {
        viewModelScope.launch {
            repository.setPasswordAutofillEnabled(enabled)
            refreshMatchedCredentialsForActiveTab()
        }
    }

    fun refreshMatchedCredentialsForActiveTab() {
        val state = _uiState.value
        val active = state.activeTab
        if (active.isHomePage || !preferencesState.value.passwordAutofillEnabled) {
            _uiState.update { it.copy(matchedCredentialsForActiveTab = emptyList()) }
            return
        }
        viewModelScope.launch {
            val matches = repository.getPasswordsForUrl(active.url)
            if (_uiState.value.activeTabId == active.id) {
                _uiState.update { it.copy(matchedCredentialsForActiveTab = matches) }
            }
        }
    }

    fun onWebCredentialSubmitted(
        pageUrl: String,
        pageTitle: String,
        username: String,
        password: String
    ) {
        val state = _uiState.value
        val active = state.activeTab
        if (active.isPrivate || !preferencesState.value.passwordAutofillEnabled) return
        val cleanUser = username.trim()
        if (cleanUser.isBlank() || password.isBlank()) return
        val host = PasswordVaultUtil.extractHost(pageUrl)
        if (host.isBlank()) return

        viewModelScope.launch {
            val existing = repository.getPasswordsForUrl(pageUrl)
                .firstOrNull { it.username.equals(cleanUser, ignoreCase = true) }
            if (existing != null && repository.decryptPassword(existing) == password) {
                return@launch
            }
            val siteName = PasswordVaultUtil.deriveSiteName(pageUrl, pageTitle)
            _uiState.update {
                it.copy(
                    pendingCredentialPrompt = PendingCredentialPrompt(
                        siteName = siteName,
                        siteUrl = pageUrl,
                        username = cleanUser,
                        password = password,
                        existingId = existing?.id
                    )
                )
            }
        }
    }

    fun confirmSavePendingCredential() {
        val pending = _uiState.value.pendingCredentialPrompt ?: return
        _uiState.update { it.copy(pendingCredentialPrompt = null) }
        saveOrUpdatePassword(
            id = pending.existingId ?: 0L,
            siteName = pending.siteName,
            siteUrl = pending.siteUrl,
            username = pending.username,
            plainPassword = pending.password
        )
    }

    fun dismissPendingCredentialPrompt() {
        _uiState.update { it.copy(pendingCredentialPrompt = null) }
    }

    fun fillCredentialIntoActiveWebView(entity: SavedPasswordEntity) {
        val active = _uiState.value.activeTab
        val wv = webViewRegistry[active.id] ?: return
        val plainPassword = repository.decryptPassword(entity)
        val userLiteral = JSONObject.quote(entity.username)
        val passLiteral = JSONObject.quote(plainPassword)
        val js = """
            (function() {
                var userVal = $userLiteral;
                var passVal = $passLiteral;
                function setNativeValue(el, value) {
                    if (!el) return;
                    var proto = Object.getPrototypeOf(el);
                    var desc = Object.getOwnPropertyDescriptor(proto, 'value') ||
                        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                    if (desc && desc.set) {
                        desc.set.call(el, value);
                    } else {
                        el.value = value;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                var passInput = document.querySelector('input[type="password"]:not([disabled])');
                if (passInput) {
                    setNativeValue(passInput, passVal);
                    var form = passInput.form || document;
                    var candidates = Array.prototype.slice.call(
                        form.querySelectorAll('input[type="email"], input[type="text"], input[name*="user" i], input[name*="mail" i], input[name*="login" i], input:not([type])')
                    );
                    var userInput = candidates.find(function(inp) {
                        return inp !== passInput && !inp.disabled && inp.type !== 'hidden' && inp.offsetParent !== null;
                    }) || candidates[0];
                    if (userInput) {
                        setNativeValue(userInput, userVal);
                    }
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
        viewModelScope.launch { repository.markPasswordUsed(entity.id) }
        _uiState.update {
            it.copy(snackbarNotice = "Filled credentials for ${entity.siteName}")
        }
    }

    fun startFastDownload(
        url: String,
        suggestedFileName: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        contentLength: Long = -1L,
        userAgent: String? = null
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val engine = fastDownloadEngine
        if (engine != null) {
            engine.enqueueDownload(
                url = cleanUrl,
                suggestedFileName = suggestedFileName,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
                userAgent = userAgent
            ) { entity ->
                _uiState.update {
                    it.copy(snackbarNotice = "Downloading ${entity.fileName} (4× Turbo)…")
                }
            }
        } else {
            val rawName = suggestedFileName?.takeIf { it.isNotBlank() }
                ?: cleanUrl.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
            val resolvedName = FastDownloadEngine.sanitizeFileName(rawName)
            val resolvedMime = FastDownloadEngine.resolveMimeType(cleanUrl, mimeType)
            val category = FastDownloadEngine.inferCategory(resolvedName, resolvedMime)
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                repository.upsertDownload(
                    DownloadItemEntity(
                        fileName = resolvedName,
                        url = cleanUrl,
                        mimeType = resolvedMime,
                        category = category,
                        totalBytes = if (contentLength > 0) contentLength else 128_000L,
                        downloadedBytes = if (contentLength > 0) contentLength else 128_000L,
                        speedBytesPerSec = 4_200_000L,
                        chunksCount = 4,
                        status = FastDownloadEngine.STATUS_COMPLETED,
                        createdAt = now,
                        completedAt = now
                    )
                )
                _uiState.update {
                    it.copy(snackbarNotice = "Downloaded $resolvedName")
                }
            }
        }
    }

    fun pauseDownload(id: Long) {
        fastDownloadEngine?.pauseDownload(id)
    }

    fun resumeDownload(id: Long) {
        fastDownloadEngine?.resumeDownload(id)
    }

    fun retryDownload(id: Long) {
        fastDownloadEngine?.retryDownload(id)
    }

    fun cancelDownload(id: Long) {
        fastDownloadEngine?.cancelDownload(id)
    }

    fun deleteDownload(id: Long) {
        val engine = fastDownloadEngine
        if (engine != null) {
            engine.deleteDownload(id)
        } else {
            viewModelScope.launch { repository.deleteDownloadById(id) }
        }
        _uiState.update { it.copy(snackbarNotice = "Removed download item") }
    }

    fun clearCompletedDownloads() {
        viewModelScope.launch {
            repository.clearCompletedDownloads()
            _uiState.update { it.copy(snackbarNotice = "Cleared completed downloads") }
        }
    }

    fun clearAllDownloads() {
        val engine = fastDownloadEngine
        if (engine != null) {
            engine.clearAllDownloads()
            _uiState.update { it.copy(snackbarNotice = "Cleared all downloads") }
        } else {
            viewModelScope.launch {
                repository.clearAllDownloads()
                _uiState.update { it.copy(snackbarNotice = "Cleared all downloads") }
            }
        }
    }

    fun deleteBrowsingHistoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteBrowsingHistoryItem(id)
            _uiState.update { it.copy(snackbarNotice = "Removed visit from history") }
        }
    }

    fun clearBrowsingHistoryRange(sinceMillis: Long?) {
        viewModelScope.launch {
            if (sinceMillis == null) {
                repository.clearAllBrowsingHistory()
                _uiState.update { it.copy(snackbarNotice = "Cleared all browsing history") }
            } else {
                repository.clearBrowsingHistorySince(sinceMillis)
                _uiState.update { it.copy(snackbarNotice = "Cleared selected browsing history range") }
            }
        }
    }

    fun setSettingsScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isSettingsScreenOpen = open,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isPasswordManagerOpen = if (open) false else it.isPasswordManagerOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isAutomationOpen = if (open) false else it.isAutomationOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setMenuSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isMenuSheetOpen = open) }
    }

    fun toggleMenuSheet() {
        _uiState.update { it.copy(isMenuSheetOpen = !it.isMenuSheetOpen) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repository.deleteBookmark(id) }
    }

    fun importBookmarksFromText(rawContent: String) {
        viewModelScope.launch {
            val importedCount = repository.importBookmarksFromRawContent(rawContent)
            val message = if (importedCount > 0) {
                "Imported $importedCount bookmark${if (importedCount == 1) "" else "s"}"
            } else {
                "No valid bookmarks found to import"
            }
            _uiState.update { it.copy(snackbarNotice = message) }
        }
    }

    fun notifyBookmarksExported(count: Int, formatLabel: String) {
        _uiState.update {
            it.copy(
                snackbarNotice = "Exported $count bookmark${if (count == 1) "" else "s"} ($formatLabel)"
            )
        }
    }

    fun addTopVisit(title: String, url: String) {
        viewModelScope.launch {
            repository.addTopVisit(title, url)
            _uiState.update { it.copy(snackbarNotice = "Added to Top Visits") }
        }
    }

    fun deleteTopVisit(id: Long) {
        viewModelScope.launch {
            repository.deleteTopVisit(id)
            _uiState.update { it.copy(snackbarNotice = "Removed from Top Visits") }
        }
    }

    fun setShowTopVisitsOnHome(enabled: Boolean) {
        viewModelScope.launch {
            repository.setShowTopVisitsOnHome(enabled)
        }
    }

    fun reloadActiveTab() {
        val active = _uiState.value.activeTab
        if (active.isHomePage) return
        val builtIn = com.example.engine.BuiltInShowcasePages.resolveHtml(active.url)
        val wv = webViewRegistry[active.id]
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == active.id) {
                        tab.copy(
                            isLoading = true,
                            progress = 0.08f,
                            loadRequestId = if (wv == null) tab.loadRequestId + 1L else tab.loadRequestId
                        )
                    } else {
                        tab
                    }
                }
            )
        }
        if (wv != null) {
            if (builtIn != null) {
                wv.loadDataWithBaseURL(builtIn.first, builtIn.second, "text/html", "UTF-8", null)
            } else {
                wv.reload()
            }
        }
    }

    fun captureTabThumbnail(tabId: String): Bitmap? {
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId }
        if (tab?.isPrivate == true) return null
        val wv = webViewRegistry[tabId] ?: return null
        return runCatching {
            if (wv.width > 0 && wv.height > 0 && wv.isAttachedToWindow && wv.windowToken != null) {
                val thumbWidth = 320
                val thumbHeight = (wv.height.toFloat() / wv.width.toFloat() * thumbWidth).toInt().coerceIn(200, 480)
                val thumbBitmap = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(thumbBitmap)
                val scale = thumbWidth.toFloat() / wv.width.toFloat()
                canvas.scale(scale, scale)
                wv.draw(canvas)
                thumbBitmap
            } else null
        }.getOrNull()
    }

    fun setTabSwitcherOpen(open: Boolean) {
        if (open) {
            val currentTabId = _uiState.value.activeTabId
            val thumb = captureTabThumbnail(currentTabId)
            if (thumb != null) {
                _uiState.update { state ->
                    state.copy(
                        tabs = state.tabs.map { tab ->
                            if (tab.id == currentTabId) {
                                tab.copy(
                                    previewThumbnail = thumb,
                                    lastActiveTimeMillis = System.currentTimeMillis()
                                )
                            } else tab
                        }
                    )
                }
            }
        }
        _uiState.update {
            it.copy(
                isTabSwitcherOpen = open,
                expandedGroupId = if (!open) null else it.expandedGroupId,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isPasswordManagerOpen = if (open) false else it.isPasswordManagerOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setAutomationOpen(open: Boolean, module: ExtensionModule = _uiState.value.activeExtension) {
        _uiState.update {
            it.copy(
                isAutomationOpen = open,
                activeExtension = module,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isToolbarVisible = true
            )
        }
    }

    fun selectExtensionModule(module: ExtensionModule) {
        _uiState.update { it.copy(activeExtension = module) }
    }

    // --- Preferences & Settings Controls ---

    fun toggleShields(enabled: Boolean) {
        viewModelScope.launch { repository.setShieldsEnabled(enabled) }
    }

    fun toggleHttpsUpgrade(enabled: Boolean) {
        viewModelScope.launch { repository.setHttpsUpgradeEnabled(enabled) }
    }

    fun toggleAggressivePreload(enabled: Boolean) {
        viewModelScope.launch { repository.setAggressivePreloadEnabled(enabled) }
    }

    fun toggleScriptGuard(enabled: Boolean) {
        viewModelScope.launch { repository.setScriptGuardEnabled(enabled) }
    }

    fun toggleDesktopViewport(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDesktopViewportEnabled(enabled)
            val currentPrefs = preferencesState.value
            val tabsById = _uiState.value.tabs.associateBy { it.id }
            webViewRegistry.forEach { (tabId, wv) ->
                val isPrivate = tabsById[tabId]?.isPrivate == true
                performanceOptimizer.configureFastWebView(
                    webView = wv,
                    scriptGuardEnabled = currentPrefs.scriptGuardEnabled,
                    desktopMode = enabled,
                    aggressiveCaching = currentPrefs.aggressivePreloadEnabled,
                    isIncognito = isPrivate
                )
                performanceOptimizer.applyDesktopViewportMode(wv, enabled)
            }
            _uiState.update {
                it.copy(
                    snackbarNotice = if (enabled) "Desktop site enabled" else "Mobile site enabled"
                )
            }
        }
    }

    fun toggleDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColorEnabled(enabled) }
    }

    fun selectSeedPalette(preset: SeedPalettePreset) {
        viewModelScope.launch { repository.setSeedPaletteOrdinal(preset.ordinal) }
    }

    fun selectThemeMode(mode: ThemeModeOption) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun cycleSeedTheme() {
        viewModelScope.launch { repository.cycleSeedPalette() }
    }

    fun setDefaultZoomPercent(percent: Int) {
        val clamped = BrowserPreferences.clampZoomPercent(percent)
        viewModelScope.launch {
            repository.setDefaultZoomPercent(clamped)
            syncWebViewAccessibilitySettings()
        }
    }

    fun setSiteZoom(hostOrUrl: String, percent: Int) {
        val clamped = BrowserPreferences.clampZoomPercent(percent)
        viewModelScope.launch {
            repository.setSiteZoom(hostOrUrl, clamped)
            syncWebViewAccessibilitySettings()
        }
    }

    fun removeSiteZoom(hostOrUrl: String) {
        viewModelScope.launch {
            repository.removeSiteZoom(hostOrUrl)
            syncWebViewAccessibilitySettings()
        }
    }

    fun clearSavedSiteZooms() {
        viewModelScope.launch {
            repository.clearSavedSiteZooms()
            syncWebViewAccessibilitySettings()
        }
    }

    fun adjustActiveZoomPercent(newPercent: Int) {
        val clamped = BrowserPreferences.clampZoomPercent(newPercent)
        val active = _uiState.value.activeTab
        val host = if (active.isHomePage) "" else BrowserPreferences.normalizeZoomHost(active.url)
        if (host.isNotEmpty()) {
            setSiteZoom(host, clamped)
        } else {
            setDefaultZoomPercent(clamped)
        }
    }

    fun resetActiveSiteZoom() {
        val active = _uiState.value.activeTab
        val host = if (active.isHomePage) "" else BrowserPreferences.normalizeZoomHost(active.url)
        if (host.isNotEmpty()) {
            removeSiteZoom(host)
        } else {
            setDefaultZoomPercent(100)
        }
    }

    fun toggleShowZoomInMainMenu(enabled: Boolean) {
        viewModelScope.launch { repository.setShowZoomInMainMenu(enabled) }
    }

    fun toggleForceEnableZoom(enabled: Boolean) {
        viewModelScope.launch {
            repository.setForceEnableZoom(enabled)
            webViewRegistry.values.forEach { wv ->
                performanceOptimizer.applyForceEnableZoom(wv, enabled)
            }
        }
    }

    fun toggleSimplifiedView(enabled: Boolean) {
        viewModelScope.launch { repository.setSimplifiedViewEnabled(enabled) }
    }

    fun toggleCaretBrowsing(enabled: Boolean, announceShortcut: Boolean = false) {
        viewModelScope.launch {
            repository.setCaretBrowsingEnabled(enabled)
            webViewRegistry.values.forEach { wv ->
                performanceOptimizer.applyCaretBrowsing(wv, enabled)
            }
            if (announceShortcut) {
                _uiState.update {
                    it.copy(
                        snackbarNotice = if (enabled) {
                            "Caret browsing enabled (F7)"
                        } else {
                            "Caret browsing disabled (F7)"
                        }
                    )
                }
            }
        }
    }

    fun toggleTouchpadSwipeNavigation(enabled: Boolean) {
        viewModelScope.launch { repository.setTouchpadSwipeNavigationEnabled(enabled) }
    }

    private fun syncWebViewAccessibilitySettings() {
        val prefs = preferencesState.value
        val tabsById = _uiState.value.tabs.associateBy { it.id }
        webViewRegistry.forEach { (tabId, wv) ->
            val tabUrl = tabsById[tabId]?.url
            wv.settings.textZoom = prefs.effectiveZoomForUrl(tabUrl)
        }
    }

    // --- Languages & Offline Translation Models (ML Kit On-Device) ---

    fun refreshDownloadedLanguageModels() {
        viewModelScope.launch {
            val models = translationEngine.refreshDownloadedModels()
            _downloadedLanguageCodes.value = models
        }
    }

    fun downloadTranslationModel(mlKitCode: String) {
        val normalized = LanguageCatalog.normalizeMlKitCode(mlKitCode) ?: return
        if (normalized in _downloadingLanguageCodes.value) return
        _failedLanguageDownloads.update { it - normalized }
        _downloadingLanguageCodes.update { it + normalized }
        val info = LanguageCatalog.describeLanguage(normalized)
        viewModelScope.launch {
            val result = translationEngine.downloadLanguageModel(
                mlKitCode = normalized,
                requireWifi = preferencesState.value.wifiOnlyTranslationDownloads
            )
            _downloadingLanguageCodes.update { it - normalized }
            if (result.isSuccess) {
                val refreshed = translationEngine.refreshDownloadedModels()
                _downloadedLanguageCodes.value = refreshed + normalized
                _uiState.update {
                    it.copy(snackbarNotice = "${info.displayName} offline translation model ready")
                }
            } else {
                _failedLanguageDownloads.update { it + normalized }
                _uiState.update {
                    it.copy(snackbarNotice = "Failed to download ${info.displayName} model")
                }
            }
        }
    }

    fun deleteTranslationModel(mlKitCode: String) {
        val normalized = LanguageCatalog.normalizeMlKitCode(mlKitCode) ?: return
        val info = LanguageCatalog.describeLanguage(normalized)
        viewModelScope.launch {
            val result = translationEngine.deleteLanguageModel(normalized)
            if (result.isSuccess) {
                val refreshed = translationEngine.refreshDownloadedModels()
                _downloadedLanguageCodes.value = refreshed - normalized
                _uiState.update {
                    it.copy(snackbarNotice = "Removed ${info.displayName} offline model")
                }
            } else {
                _uiState.update {
                    it.copy(snackbarNotice = "Unable to remove ${info.displayName} model")
                }
            }
        }
    }

    fun setAppLanguageTag(tag: String) {
        viewModelScope.launch { repository.setAppLanguageTag(tag) }
    }

    fun addPreferredLanguage(tag: String, alsoDownloadModel: Boolean = false) {
        val clean = tag.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val current = preferencesState.value.preferredLanguageTags
            if (current.none { it.equals(clean, ignoreCase = true) }) {
                repository.setPreferredLanguageTags(current + clean)
            }
            if (alsoDownloadModel) {
                LanguageCatalog.normalizeMlKitCode(clean)?.let { code ->
                    if (code !in _downloadedLanguageCodes.value) {
                        downloadTranslationModel(code)
                    }
                }
            }
        }
    }

    fun removePreferredLanguage(tag: String) {
        viewModelScope.launch {
            val current = preferencesState.value.preferredLanguageTags
            val updated = current.filterNot { it.equals(tag, ignoreCase = true) }
            if (updated.isNotEmpty()) {
                repository.setPreferredLanguageTags(updated)
            }
        }
    }

    fun movePreferredLanguageUp(tag: String) {
        viewModelScope.launch {
            val current = preferencesState.value.preferredLanguageTags.toMutableList()
            val idx = current.indexOfFirst { it.equals(tag, ignoreCase = true) }
            if (idx > 0) {
                val item = current.removeAt(idx)
                current.add(idx - 1, item)
                repository.setPreferredLanguageTags(current)
            }
        }
    }

    fun movePreferredLanguageDown(tag: String) {
        viewModelScope.launch {
            val current = preferencesState.value.preferredLanguageTags.toMutableList()
            val idx = current.indexOfFirst { it.equals(tag, ignoreCase = true) }
            if (idx in 0 until current.lastIndex) {
                val item = current.removeAt(idx)
                current.add(idx + 1, item)
                repository.setPreferredLanguageTags(current)
            }
        }
    }

    fun movePreferredLanguageToTop(tag: String) {
        viewModelScope.launch {
            val current = preferencesState.value.preferredLanguageTags.toMutableList()
            val idx = current.indexOfFirst { it.equals(tag, ignoreCase = true) }
            if (idx > 0) {
                val item = current.removeAt(idx)
                current.add(0, item)
                repository.setPreferredLanguageTags(current)
            }
        }
    }

    fun toggleOfferToTranslate(enabled: Boolean) {
        viewModelScope.launch { repository.setOfferToTranslateEnabled(enabled) }
    }

    fun setTranslateTargetLanguage(code: String) {
        val normalized = LanguageCatalog.normalizeMlKitCode(code) ?: return
        viewModelScope.launch { repository.setTranslateTargetLanguageCode(normalized) }
    }

    fun toggleAutoTranslateLanguage(code: String) {
        val normalized = LanguageCatalog.normalizeMlKitCode(code) ?: return
        viewModelScope.launch {
            val current = preferencesState.value.autoTranslateLanguageCodes
            val updated = if (normalized in current) current - normalized else current + normalized
            repository.setAutoTranslateLanguageCodes(updated)
            if (normalized in updated) {
                val never = preferencesState.value.neverTranslateLanguageCodes - normalized
                repository.setNeverTranslateLanguageCodes(never)
            }
        }
    }

    fun toggleNeverTranslateLanguage(code: String) {
        val normalized = LanguageCatalog.normalizeMlKitCode(code) ?: return
        viewModelScope.launch {
            val current = preferencesState.value.neverTranslateLanguageCodes
            val updated = if (normalized in current) current - normalized else current + normalized
            repository.setNeverTranslateLanguageCodes(updated)
            if (normalized in updated) {
                val auto = preferencesState.value.autoTranslateLanguageCodes - normalized
                repository.setAutoTranslateLanguageCodes(auto)
            }
        }
    }

    fun toggleWifiOnlyTranslationDownloads(enabled: Boolean) {
        viewModelScope.launch { repository.setWifiOnlyTranslationDownloads(enabled) }
    }

    fun detectPageLanguageAndMaybeAutoTranslate(
        tabId: String,
        pageUrl: String,
        htmlLangHint: String?,
        textSample: String
    ) {
        val cleanUrl = pageUrl.trim()
        if (cleanUrl.isEmpty() || cleanUrl.equals("apex://home", ignoreCase = true)) return

        viewModelScope.launch {
            val prefs = preferencesState.value
            val targetCode = LanguageCatalog.normalizeMlKitCode(prefs.translateTargetLanguageCode) ?: "en"
            val detected = translationEngine.detectLanguage(textSample)
                ?: LanguageCatalog.normalizeMlKitCode(htmlLangHint)
                ?: return@launch

            val currentState = _tabTranslationStates.value[tabId]
            if (currentState != null &&
                currentState.pageUrl == cleanUrl &&
                (currentState.isTranslated || currentState.bannerDismissed)
            ) {
                return@launch
            }

            if (detected == targetCode) {
                _tabTranslationStates.update {
                    it + (
                        tabId to TabTranslationState(
                            tabId = tabId,
                            pageUrl = cleanUrl,
                            detectedSourceCode = detected,
                            targetCode = targetCode,
                            isBannerVisible = false
                        )
                    )
                }
                return@launch
            }

            val shouldAutoTranslate = detected in prefs.autoTranslateLanguageCodes
            val isNeverTranslate = detected in prefs.neverTranslateLanguageCodes
            val showBanner = prefs.offerToTranslateEnabled && !isNeverTranslate

            _tabTranslationStates.update {
                it + (
                    tabId to TabTranslationState(
                        tabId = tabId,
                        pageUrl = cleanUrl,
                        detectedSourceCode = detected,
                        targetCode = targetCode,
                        isBannerVisible = showBanner || shouldAutoTranslate
                    )
                )
            }

            if (shouldAutoTranslate) {
                translateWebPage(tabId = tabId, targetCodeOverride = targetCode)
            }
        }
    }

    fun translateActiveWebPage(targetCodeOverride: String? = null) {
        val active = _uiState.value.activeTab
        if (active.isHomePage) return
        translateWebPage(tabId = active.id, targetCodeOverride = targetCodeOverride)
    }

    fun translateWebPage(tabId: String, targetCodeOverride: String? = null) {
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.isHomePage) return
        val wv = webViewRegistry[tabId] ?: return

        val prefs = preferencesState.value
        val targetCode = LanguageCatalog.normalizeMlKitCode(targetCodeOverride)
            ?: LanguageCatalog.normalizeMlKitCode(prefs.translateTargetLanguageCode)
            ?: "en"
        val existingState = _tabTranslationStates.value[tabId]

        _tabTranslationStates.update { map ->
            map + (
                tabId to (existingState ?: TabTranslationState(tabId = tabId, pageUrl = tab.url)).copy(
                    pageUrl = tab.url,
                    targetCode = targetCode,
                    isTranslating = true,
                    isBannerVisible = true,
                    bannerDismissed = false,
                    errorMessage = null
                )
            )
        }

        val collectNodesJs = """
            (function() {
                try {
                    if (!window.__apexOrigTextNodes) {
                        window.__apexOrigTextNodes = [];
                        var skipTags = { SCRIPT:1, STYLE:1, NOSCRIPT:1, CODE:1, PRE:1, SVG:1, TEXTAREA:1 };
                        var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, null, false);
                        var node;
                        while ((node = walker.nextNode())) {
                            var parent = node.parentElement;
                            if (!parent || skipTags[parent.tagName]) continue;
                            var val = node.nodeValue || "";
                            if (val.trim().length >= 2) {
                                window.__apexOrigTextNodes.push({ node: node, orig: val });
                            }
                        }
                    }
                    var items = window.__apexOrigTextNodes.slice(0, 120).map(function(entry) { return entry.orig; });
                    var sample = items.slice(0, 16).join(" ").substring(0, 1200);
                    return JSON.stringify({
                        htmlLang: (document.documentElement.getAttribute("lang") || "").trim(),
                        sample: sample,
                        items: items
                    });
                } catch (e) {
                    return JSON.stringify({ htmlLang: "", sample: "", items: [] });
                }
            })();
        """.trimIndent()

        wv.evaluateJavascript(collectNodesJs) { rawResult ->
            viewModelScope.launch {
                val parsed = parseJsStringResult(rawResult)?.let { jsonStr ->
                    runCatching { JSONObject(jsonStr) }.getOrNull()
                }
                val htmlLang = parsed?.optString("htmlLang", "").orEmpty()
                val sample = parsed?.optString("sample", "").orEmpty()
                val itemsArr = parsed?.optJSONArray("items")
                val segments = buildList {
                    if (itemsArr != null) {
                        for (i in 0 until itemsArr.length()) {
                            add(itemsArr.optString(i))
                        }
                    }
                }

                if (segments.isEmpty()) {
                    _tabTranslationStates.update { map ->
                        map + (
                            tabId to (map[tabId] ?: TabTranslationState(tabId = tabId)).copy(
                                isTranslating = false,
                                errorMessage = "No translatable text found"
                            )
                        )
                    }
                    return@launch
                }

                val sourceCode = existingState?.detectedSourceCode
                    ?: translationEngine.detectLanguage(sample)
                    ?: LanguageCatalog.normalizeMlKitCode(htmlLang)
                    ?: "es"

                if (sourceCode == targetCode) {
                    val targetName = LanguageCatalog.describeLanguage(targetCode).displayName
                    _tabTranslationStates.update { map ->
                        map + (
                            tabId to (map[tabId] ?: TabTranslationState(tabId = tabId)).copy(
                                detectedSourceCode = sourceCode,
                                targetCode = targetCode,
                                isTranslating = false,
                                isTranslated = false,
                                isBannerVisible = false
                            )
                        )
                    }
                    _uiState.update {
                        it.copy(snackbarNotice = "Page is already in $targetName")
                    }
                    return@launch
                }

                val translatedResult = translationEngine.translateSegments(
                    segments = segments,
                    sourceCode = sourceCode,
                    targetCode = targetCode,
                    requireWifiForDownload = preferencesState.value.wifiOnlyTranslationDownloads
                )

                translatedResult.onSuccess { translated ->
                    val refreshed = translationEngine.refreshDownloadedModels()
                    if (refreshed.isNotEmpty()) {
                        _downloadedLanguageCodes.value = refreshed + sourceCode + targetCode
                    }
                    val payloadArray = JSONArray()
                    translated.forEach { payloadArray.put(it) }
                    val applyJs = """
                        (function(list) {
                            try {
                                if (!window.__apexOrigTextNodes) return;
                                for (var i = 0; i < list.length && i < window.__apexOrigTextNodes.length; i++) {
                                    var orig = window.__apexOrigTextNodes[i].orig;
                                    var lead = orig.match(/^\s*/)[0] || "";
                                    var trail = orig.match(/\s*$/)[0] || "";
                                    window.__apexOrigTextNodes[i].node.nodeValue = lead + list[i] + trail;
                                }
                            } catch (e) {}
                        })(${payloadArray.toString()});
                    """.trimIndent()
                    webViewRegistry[tabId]?.evaluateJavascript(applyJs, null)

                    _tabTranslationStates.update { map ->
                        map + (
                            tabId to TabTranslationState(
                                tabId = tabId,
                                pageUrl = tab.url,
                                detectedSourceCode = sourceCode,
                                targetCode = targetCode,
                                isTranslated = true,
                                isTranslating = false,
                                isBannerVisible = true,
                                bannerDismissed = false
                            )
                        )
                    }
                }.onFailure { err ->
                    _tabTranslationStates.update { map ->
                        map + (
                            tabId to (map[tabId] ?: TabTranslationState(tabId = tabId, pageUrl = tab.url)).copy(
                                detectedSourceCode = sourceCode,
                                targetCode = targetCode,
                                isTranslating = false,
                                isBannerVisible = true,
                                errorMessage = err.message ?: "Translation failed"
                            )
                        )
                    }
                    _uiState.update {
                        it.copy(snackbarNotice = "Translation model download or translation failed")
                    }
                }
            }
        }
    }

    fun restoreOriginalWebPage(tabId: String) {
        val wv = webViewRegistry[tabId]
        val restoreJs = """
            (function() {
                try {
                    if (!window.__apexOrigTextNodes) return;
                    for (var i = 0; i < window.__apexOrigTextNodes.length; i++) {
                        window.__apexOrigTextNodes[i].node.nodeValue = window.__apexOrigTextNodes[i].orig;
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        wv?.evaluateJavascript(restoreJs, null)
        _tabTranslationStates.update { map ->
            val current = map[tabId] ?: return@update map
            map + (
                tabId to current.copy(
                    isTranslated = false,
                    isTranslating = false,
                    isBannerVisible = true
                )
            )
        }
    }

    fun dismissTranslationBanner(tabId: String) {
        _tabTranslationStates.update { map ->
            val current = map[tabId] ?: return@update map
            map + (
                tabId to current.copy(
                    isBannerVisible = false,
                    bannerDismissed = true
                )
            )
        }
    }

    private fun parseJsStringResult(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                org.json.JSONTokener(raw).nextValue().toString()
            } else {
                raw
            }
        }.getOrNull()
    }

    fun resetPrivacyStats() {
        viewModelScope.launch {
            repository.resetTelemetryStats()
            performanceOptimizer.wipeIncognitoSessionStorage()
            _uiState.update {
                it.copy(snackbarNotice = "Search history and browsing data cleared")
            }
        }
    }

    // --- Extension 1: Nano Multi-Agent Automation ---

    fun runNanoTask(userPrompt: String) {
        val cleanPrompt = userPrompt.trim()
        if (cleanPrompt.isEmpty() || _uiState.value.isNanoRunning) return

        val userMsg = NanoChatMessage(
            id = "u_${System.currentTimeMillis()}",
            isUser = true,
            text = cleanPrompt
        )
        val agentMsgId = "a_${System.currentTimeMillis()}"
        val placeholderAgentMsg = NanoChatMessage(
            id = agentMsgId,
            isUser = false,
            text = "Executing multi-agent DOM workflow…",
            steps = emptyList(),
            isRunning = true
        )

        _uiState.update {
            it.copy(
                nanoMessages = it.nanoMessages + userMsg + placeholderAgentMsg,
                isNanoRunning = true
            )
        }

        viewModelScope.launch {
            val startTabUrl = _uiState.value.activeTab.url
            val outcome = nanoEngine.runTask(
                prompt = cleanPrompt,
                currentUrl = startTabUrl,
                webViewProvider = { webViewRegistry[_uiState.value.activeTabId] },
                onNavigateUrl = { destUrl ->
                    submitNavigation(destUrl, _uiState.value.activeTabId)
                },
                onStepUpdate = { liveSteps ->
                    _uiState.update { state ->
                        state.copy(
                            nanoMessages = state.nanoMessages.map { msg ->
                                if (msg.id == agentMsgId) msg.copy(steps = liveSteps) else msg
                            }
                        )
                    }
                }
            )

            _uiState.update { state ->
                state.copy(
                    isNanoRunning = false,
                    nanoMessages = state.nanoMessages.map { msg ->
                        if (msg.id == agentMsgId) {
                            msg.copy(
                                text = outcome.finalSummary,
                                isRunning = false
                            )
                        } else {
                            msg
                        }
                    }
                )
            }
        }
    }

    // --- Extension 2: Retriever Local-First Web Scraper ---

    fun onRetrieverPromptChanged(value: String) {
        _uiState.update { it.copy(retrieverPromptInput = value) }
    }

    fun selectExtractionRule(ruleId: Long) {
        val rule = extractionRulesState.value.firstOrNull { it.id == ruleId }
        _uiState.update {
            it.copy(
                selectedRuleId = ruleId,
                retrieverPromptInput = rule?.promptDescription ?: it.retrieverPromptInput
            )
        }
    }

    fun setRetrieverDisplayMode(mode: RetrieverDisplayMode) {
        _uiState.update { it.copy(retrieverDisplayMode = mode) }
    }

    fun saveCurrentRetrieverRule() {
        val input = _uiState.value.retrieverPromptInput.trim()
        if (input.isEmpty()) return
        viewModelScope.launch {
            val built = retrieverEngine.buildRuleFromInput(input)
            val newId = repository.saveExtractionRule(built)
            _uiState.update {
                it.copy(
                    selectedRuleId = newId,
                    retrieverStatusBanner = "Saved rule '${built.name}' locally"
                )
            }
        }
    }

    fun deleteRetrieverRule(ruleId: Long) {
        viewModelScope.launch {
            repository.deleteExtractionRule(ruleId)
            if (_uiState.value.selectedRuleId == ruleId) {
                _uiState.update { it.copy(selectedRuleId = null) }
            }
        }
    }

    fun captureCurrentPageWithRetriever() {
        val state = _uiState.value
        if (state.isRetrieverRunning) return
        val rule = resolveActiveRule(state)
        val activeTab = state.activeTab

        _uiState.update {
            it.copy(
                isRetrieverRunning = true,
                retrieverStatusBanner = "Capturing DOM from '${activeTab.title}'…"
            )
        }

        viewModelScope.launch {
            val target = TabSnapshotTarget(
                tabId = activeTab.id,
                title = activeTab.title,
                url = activeTab.url
            )
            val records = retrieverEngine.extractFromSingleTab(
                target = target,
                webView = webViewRegistry[activeTab.id],
                rule = rule
            )
            if (rule.id != 0L) {
                repository.updateRuleRunCount(rule.id, records.size)
            }
            _uiState.update {
                it.copy(
                    isRetrieverRunning = false,
                    extractedRecords = records,
                    retrieverStatusBanner = "Captured ${records.size} structured rows from '${activeTab.title}'"
                )
            }
        }
    }

    fun runParallelMultiTabExtraction() {
        val state = _uiState.value
        if (state.isRetrieverRunning) return
        val rule = resolveActiveRule(state)
        val targets = state.tabs.map {
            TabSnapshotTarget(tabId = it.id, title = it.title, url = it.url)
        }

        _uiState.update {
            it.copy(
                isRetrieverRunning = true,
                retrieverStatusBanner = "Running parallel DOM extraction across ${targets.size} open tabs…"
            )
        }

        viewModelScope.launch {
            val records = retrieverEngine.extractAcrossTabsInParallel(
                tabs = targets,
                webViewLookup = { id -> webViewRegistry[id] },
                rule = rule
            )
            if (rule.id != 0L) {
                repository.updateRuleRunCount(rule.id, records.size)
            }
            _uiState.update {
                it.copy(
                    isRetrieverRunning = false,
                    extractedRecords = records,
                    retrieverStatusBanner = "Parallel run complete: ${records.size} rows across ${targets.size} tabs"
                )
            }
        }
    }

    private fun resolveActiveRule(state: BrowserUiState): ExtractionRuleEntity {
        val rules = extractionRulesState.value
        val byId = state.selectedRuleId?.let { id -> rules.firstOrNull { it.id == id } }
        if (byId != null && (state.retrieverPromptInput.isBlank() || state.retrieverPromptInput == byId.promptDescription)) {
            return byId
        }
        if (state.retrieverPromptInput.isNotBlank()) {
            return retrieverEngine.buildRuleFromInput(state.retrieverPromptInput)
        }
        return rules.firstOrNull() ?: retrieverEngine.buildRuleFromInput("Extract headlines, prices, and links")
    }

    private fun mapBaseUrlBackToScheme(loadedUrl: String, currentTabUrl: String): String {
        return when {
            loadedUrl.startsWith("https://news.apex.browser") -> "apex://news"
            loadedUrl.startsWith("https://store.apex.browser") -> "apex://store"
            currentTabUrl.startsWith("apex://") && loadedUrl == "about:blank" -> currentTabUrl
            else -> loadedUrl
        }
    }

    private fun deriveShortTitle(url: String): String {
        return when (url.lowercase()) {
            "apex://home" -> "Home"
            "apex://news" -> "AI News Pulse"
            "apex://store" -> "Apex Gear Store"
            else -> url.removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .substringBefore('/')
                .ifBlank { "Web Tab" }
        }
    }

    private fun destroyWebViewSafely(wv: WebView, isIncognito: Boolean) {
        runCatching {
            if (isIncognito) {
                performanceOptimizer.wipeIncognitoWebView(wv)
            } else {
                wv.stopLoading()
            }
            wv.onPause()
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
    }

    override fun onCleared() {
        super.onCleared()
        suggestionJob?.cancel()
        val hadIncognito = _uiState.value.tabs.any { it.isPrivate }
        _uiState.value.tabs.forEach { tab ->
            val wv = webViewRegistry.remove(tab.id)
            if (wv != null) {
                destroyWebViewSafely(wv, isIncognito = tab.isPrivate)
            }
        }
        webViewRegistry.values.forEach { destroyWebViewSafely(it, isIncognito = false) }
        webViewRegistry.clear()
        if (hadIncognito) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val database = ApexDatabase.getInstance(appContext)
                    val prefsRepo = UserPreferencesRepository(appContext)
                    val sharedPrefs = appContext.getSharedPreferences(
                        "apex_browser_storage",
                        Context.MODE_PRIVATE
                    )
                    val repo = BrowserRepository(database.apexDao(), prefsRepo, sharedPrefs)
                    val downloadEngine = FastDownloadEngine(appContext, repo)
                    return BrowserViewModel(
                        repository = repo,
                        fastDownloadEngine = downloadEngine
                    ) as T
                }
            }
        }
    }
}
