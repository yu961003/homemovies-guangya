package com.example.localmovielibrarz.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query("SELECT COUNT(*) FROM movies")
    fun observeMovieListInvalidation(): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE scrapeTaskStatus IN (:statuses)")
    fun observeScrapeTaskCount(statuses: List<String>): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE nfoUri IS NULL OR scrapeTaskStatus IN (:statuses)")
    fun observeScrapeIssueCount(statuses: List<String>): Flow<Int>

    @Query("SELECT COUNT(*) FROM movies WHERE scrapeTaskStatus = :status")
    suspend fun countScrapeTasks(status: String): Int

    @Query("SELECT COUNT(*) FROM movies WHERE scrapeTaskStatus IN (:statuses)")
    suspend fun countScrapeTasks(statuses: List<String>): Int

    @Query("SELECT COUNT(*) FROM movies WHERE nfoUri IS NULL AND scrapeTaskStatus NOT IN (:trackedStatuses)")
    suspend fun countUntrackedMoviesWithoutNfo(trackedStatuses: List<String>): Int

    @Query("SELECT * FROM movies WHERE scrapeTaskStatus IN (:statuses) ORDER BY updatedAt ASC, scannedAtMillis ASC")
    suspend fun getScrapeTaskMovies(statuses: List<String>): List<MovieEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE nfoUri IS NULL OR scrapeTaskStatus IN (:statuses)
        ORDER BY
            CASE scrapeTaskStatus
                WHEN 'Failed' THEN 0
                WHEN 'Running' THEN 1
                WHEN 'Pending' THEN 2
                ELSE 3
            END,
            updatedAt DESC,
            scannedAtMillis DESC
        """
    )
    suspend fun getScrapeIssueMovies(statuses: List<String>): List<MovieEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE nfoUri IS NULL OR scrapeTaskStatus IN (:statuses)
        ORDER BY
            CASE scrapeTaskStatus
                WHEN 'Failed' THEN 0
                WHEN 'Running' THEN 1
                WHEN 'Pending' THEN 2
                ELSE 3
            END,
            updatedAt DESC,
            scannedAtMillis DESC
        """
    )
    fun observeScrapeIssueMovies(statuses: List<String>): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT 
            id, videoUri, videoName, sortTitle, title, originalTitle,
            year, premiered, runtimeMinutes, mpaa, rating,
            genres, tags, posterUri, fanartUri, thumbUri,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun getAllMovieListItems(): List<MovieListItem>

    @Query("SELECT COUNT(*) FROM movies WHERE isFavorite = 1")
    fun observeFavoriteMovieListInvalidation(): Flow<Int>

    @Query(
        """
        SELECT 
            id, videoUri, videoName, sortTitle, title, originalTitle,
            year, premiered, runtimeMinutes, mpaa, rating,
            genres, tags, posterUri, fanartUri, thumbUri,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE isFavorite = 1
        ORDER BY updatedAt DESC, scannedAtMillis DESC
        """
    )
    suspend fun getAllFavoriteMovieListItems(): List<MovieListItem>

    @Query("SELECT COUNT(*) FROM movies")
    fun observeMoviePlaybackKeyInvalidation(): Flow<Int>

    @Query("SELECT id, videoUri FROM movies ORDER BY id")
    suspend fun getAllMoviePlaybackKeyItems(): List<MoviePlaybackKeyItem>

    @Query("SELECT actors AS items FROM movies WHERE actors != ''")
    suspend fun getActorMetadataLists(): List<MovieMetadataList>

    @Query("SELECT tags AS items FROM movies WHERE tags != ''")
    suspend fun getTagMetadataLists(): List<MovieMetadataList>

    @Query("SELECT genres AS items FROM movies WHERE genres != ''")
    suspend fun getGenreMetadataLists(): List<MovieMetadataList>

    @Query("SELECT studios AS items FROM movies WHERE studios != ''")
    suspend fun getStudioMetadataLists(): List<MovieMetadataList>

    @Query("SELECT series AS value FROM movies WHERE series IS NOT NULL AND series != ''")
    suspend fun getSeriesMetadataTexts(): List<MovieMetadataText>

    @Query("SELECT * FROM movies WHERE id = :id")
    fun observeMovie(id: Long): Flow<MovieEntity?>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE id = :id
        """
    )
    suspend fun getMovieLite(id: Long): MovieEntity?

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE videoUri = :videoUri
        LIMIT 1
        """
    )
    suspend fun getMovieByVideoUriLite(videoUri: String): MovieEntity?

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE videoUri IN (:videoUris)
        """
    )
    suspend fun getMoviesByVideoUrisLite(videoUris: List<String>): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE libraryRootUri = :rootUri
        """
    )
    suspend fun getMoviesByLibraryRootLite(rootUri: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE libraryRootUri = :rootUri
            AND (
                videoName LIKE :pattern OR
                title LIKE :pattern OR
                originalTitle LIKE :pattern
            )
        """
    )
    suspend fun getMovieNumberCandidatesByLibraryRootLite(rootUri: String, pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            studios, series, directors, actors, genres, tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        """
    )
    suspend fun getMoviesForMetadataLookupLite(): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE actors LIKE :pattern
        """
    )
    suspend fun getMoviesForActorLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE tags LIKE :pattern
        """
    )
    suspend fun getMoviesForTagLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE genres LIKE :pattern
        """
    )
    suspend fun getMoviesForGenreLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE studios LIKE :pattern
        """
    )
    suspend fun getMoviesForStudioLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE series LIKE :pattern
        """
    )
    suspend fun getMoviesForCollectionLookupLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE actors LIKE :pattern
        """
    )
    suspend fun getSimilarCandidatesByActorLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE videoName LIKE :pattern OR title LIKE :pattern OR originalTitle LIKE :pattern
        """
    )
    suspend fun getSimilarCandidatesByCodeLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        """
    )
    suspend fun getMoviesSnapshotLite(): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE 
            title LIKE :pattern OR originalTitle LIKE :pattern OR videoName LIKE :pattern OR
            CAST(year AS TEXT) LIKE :pattern OR actors LIKE :pattern OR genres LIKE :pattern OR tags LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE title LIKE :pattern OR originalTitle LIKE :pattern OR videoName LIKE :pattern OR CAST(year AS TEXT) LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByTitleLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE actors LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByActorLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE videoName LIKE :pattern OR title LIKE :pattern OR originalTitle LIKE :pattern OR uniqueIds LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByNumberLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE tags LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByTagLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE genres LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByGenreLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE studios LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByStudioLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE series LIKE :pattern
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByCollectionLite(pattern: String): List<MovieEntity>

    @Query(
        """
        SELECT 
            id, libraryRootUri, videoUri, videoName, sortTitle, title, originalTitle,
            NULL AS plot, NULL AS outline, year, premiered, runtimeMinutes, mpaa,
            '' AS studios, series, '' AS directors, '' AS actors, '' AS genres, '' AS tags, rating,
            '' AS uniqueIds, posterUri, fanartUri, thumbUri, nfoUri, scrapeFailureReason, scrapeTaskStatus,
            scannedAtMillis, isFavorite, isWatched, updatedAt
        FROM movies
        WHERE CAST(year AS TEXT) = :year
        ORDER BY sortTitle COLLATE NOCASE
        """
    )
    suspend fun searchMoviesByYearLite(year: String): List<MovieEntity>

    @Query("SELECT DISTINCT libraryRootUri FROM movies WHERE libraryRootUri != ''")
    suspend fun getLibraryRootUris(): List<String>

    /**
     * 元数据补全的候选集：**只挑「文本字段有空位」的影片**。
     *
     * 为什么是这几个字段：title 之外的 `actors` / `studios` / `genres` / `plot` 是详情页与筛选页
     * 真正会展示的东西，空着用户一眼能看见；`posterUri` 刻意**不在判据里**——它指向本地已下载的
     * 图片文件，补全只回填文本，不下载图片（下载属于重新刮削的职责），把它算进来只会让
     * 每一部「有元数据但没封面」的影片被反复排队又补不出东西。
     * `nfoUri IS NOT NULL` 限定「已经整理过」：未整理的影片该走影片刮削，不该被补全抢先占用。
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE nfoUri IS NOT NULL
          AND (
            actors = '' OR studios = '' OR genres = ''
            OR plot IS NULL OR plot = ''
          )
        ORDER BY updatedAt ASC, scannedAtMillis ASC
        """
    )
    suspend fun getMetadataGapMovies(): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(movies: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(movie: MovieEntity)

    @Transaction
    suspend fun synchronizeLibraryMovies(removedIds: List<Long>, movies: List<MovieEntity>) {
        if (removedIds.isNotEmpty()) {
            deleteByIds(removedIds)
        }
        if (movies.isNotEmpty()) {
            upsertAll(movies)
        }
    }

    @Query("UPDATE movies SET isFavorite = :isFavorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean, updatedAt: Long)

    @Query("UPDATE movies SET isWatched = :isWatched, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setWatched(id: Long, isWatched: Boolean, updatedAt: Long)

    @Query(
        """
        UPDATE movies
        SET videoUri = :newVideoUri, videoName = :newVideoName, updatedAt = :updatedAt
        WHERE id = :id AND videoUri = :oldVideoUri
        """
    )
    suspend fun updateStoredMediaLocation(
        id: Long,
        oldVideoUri: String,
        newVideoUri: String,
        newVideoName: String,
        updatedAt: Long
    ): Int

    @Query("SELECT tags FROM movies WHERE id = :id")
    suspend fun getTags(id: Long): List<String>?

    @Query("UPDATE movies SET tags = :tags, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTags(id: Long, tags: List<String>, updatedAt: Long)

    @Query("UPDATE movies SET scrapeFailureReason = :reason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setScrapeFailureReason(id: Long, reason: String?, updatedAt: Long)

    @Query("UPDATE movies SET scrapeTaskStatus = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setScrapeTaskStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE movies SET scrapeTaskStatus = :status, scrapeFailureReason = :reason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setScrapeTaskStatusAndFailureReason(id: Long, status: String, reason: String?, updatedAt: Long)

    @Query("UPDATE movies SET scrapeTaskStatus = :toStatus, updatedAt = :updatedAt WHERE scrapeTaskStatus IN (:fromStatuses)")
    suspend fun updateScrapeTaskStatuses(fromStatuses: List<String>, toStatus: String, updatedAt: Long): Int

    @Query("UPDATE movies SET scrapeTaskStatus = :toStatus, updatedAt = :updatedAt WHERE scrapeTaskStatus IN (:fromStatuses) AND id NOT IN (:excludedMovieIds)")
    suspend fun updateScrapeTaskStatusesExcluding(
        fromStatuses: List<String>,
        excludedMovieIds: List<Long>,
        toStatus: String,
        updatedAt: Long
    ): Int

    @Query("UPDATE movies SET scrapeTaskStatus = :toStatus, scrapeFailureReason = NULL, updatedAt = :updatedAt WHERE scrapeTaskStatus IN (:fromStatuses)")
    suspend fun updateScrapeTaskStatusesAndClearFailureReason(fromStatuses: List<String>, toStatus: String, updatedAt: Long): Int

    @Query("DELETE FROM movies WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM movies WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM movies WHERE libraryRootUri = :rootUri")
    suspend fun deleteByLibraryRoot(rootUri: String)

    /**
     * 扫描同步时查询"其他库根"的影片精简行（F3 优化：替代全库快照）。
     * 只取 stale 检测所需的字段，避免将 4 万+ 影片的全字段 MovieEntity 载入内存。
     */
    @Query(
        """
        SELECT id, libraryRootUri, videoUri, videoName, title, originalTitle, uniqueIds
        FROM movies
        WHERE libraryRootUri != :rootUri
        """
    )
    suspend fun getOtherRootMoviesForStaleCheck(rootUri: String): List<MovieStaleCheckRow>

    /**
     * 批量取刮削状态（云盘浏览页判定「已入库 / 待刮削 / 刮削失败」三态用）。
     * 只投影两列，一屏几百个 pickcode 也只读一个小结果集。
     */
    @Query("SELECT id, scrapeTaskStatus FROM movies WHERE id IN (:ids)")
    suspend fun getScrapeStatuses(ids: List<Long>): List<MovieScrapeStatusRow>
}

/** 影片刮削状态精简投影，用于云盘条目三态判定。 */
data class MovieScrapeStatusRow(
    val id: Long,
    val scrapeTaskStatus: String
)

/** 其他库根影片的精简投影，用于扫描同步的 stale duplicate 检测，避免全字段载入。 */
data class MovieStaleCheckRow(
    val id: Long,
    val libraryRootUri: String,
    val videoUri: String,
    val videoName: String,
    val title: String,
    val originalTitle: String?,
    val uniqueIds: List<String>
)
