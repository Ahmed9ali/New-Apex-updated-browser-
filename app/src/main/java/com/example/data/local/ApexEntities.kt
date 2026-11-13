package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "privacy_stats")
data class PrivacyStatSummaryEntity(
    @PrimaryKey val id: Int = 1,
    val trackersBlocked: Int = 0,
    val adsBlocked: Int = 0,
    val httpsUpgrades: Int = 0,
    val bytesSaved: Long = 0L,
    val timeSavedMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "blocked_domain_logs")
data class BlockedDomainLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageHost: String,
    val blockedDomain: String,
    val category: String,
    val savedBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "extraction_rules")
data class ExtractionRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val promptDescription: String,
    val itemSelector: String,
    val titleSubSelector: String,
    val valueSubSelector: String,
    val linkSubSelector: String,
    val lastRunCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["url"], unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "search_history",
    indices = [Index(value = ["query"], unique = true)]
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val searchEngineName: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "top_visits",
    indices = [Index(value = ["url"], unique = true)]
)
data class TopVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "browsing_history",
    indices = [Index(value = ["visitedAt"]), Index(value = ["url"])]
)
data class BrowsingHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val host: String,
    val isHttps: Boolean = true,
    val trackersBlockedOnVisit: Int = 0,
    val visitCount: Int = 1,
    val visitedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "downloads",
    indices = [Index(value = ["createdAt"]), Index(value = ["status"])]
)
data class DownloadItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val url: String,
    val mimeType: String = "application/octet-stream",
    val category: String = "Other",
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val chunksCount: Int = 4,
    val status: String = "DOWNLOADING",
    val localFilePath: String = "",
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(
    tableName = "saved_passwords",
    indices = [
        Index(value = ["host"]),
        Index(value = ["updatedAt"]),
        Index(value = ["host", "username"], unique = true)
    ]
)
data class SavedPasswordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val siteName: String,
    val siteUrl: String,
    val host: String,
    val username: String,
    val encryptedPassword: String,
    val notes: String = "",
    val category: String = "Personal",
    val isFavorite: Boolean = false,
    val strengthScore: Int = 0,
    val passwordFingerprint: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null
)



