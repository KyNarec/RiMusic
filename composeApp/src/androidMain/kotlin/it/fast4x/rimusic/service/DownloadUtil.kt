package it.fast4x.rimusic.service


import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
//import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Requirements
import it.fast4x.innertube.Innertube
import it.fast4x.innertube.models.bodies.PlayerBody
import it.fast4x.innertube.requests.player
import it.fast4x.innertube.utils.ProxyPreferences
import it.fast4x.rimusic.Database
import it.fast4x.rimusic.enums.AudioQualityFormat
import it.fast4x.rimusic.models.Format
import it.fast4x.rimusic.query
import it.fast4x.rimusic.utils.RingBufferPrevious
import it.fast4x.rimusic.utils.audioQualityFormatKey
import it.fast4x.rimusic.utils.defaultDataSourceFactory
import it.fast4x.rimusic.utils.getEnum
import it.fast4x.rimusic.utils.getPipedSession
import it.fast4x.rimusic.utils.preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.Executors

@UnstableApi
object DownloadUtil {
    const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_channel"

    //private const val TAG = "DownloadUtil"
    private const val DOWNLOAD_CONTENT_DIRECTORY = "downloads"

    private lateinit var databaseProvider: DatabaseProvider
    private lateinit var downloadCache: Cache

    //private lateinit var dataSourceFactory: DataSource.Factory
    //private lateinit var httpDataSourceFactory: HttpDataSource.Factory
    //private lateinit var ResolvingDataSourceFactory: ResolvingDataSource.Factory
    private lateinit var downloadNotificationHelper: DownloadNotificationHelper
    private lateinit var downloadDirectory: File
    private lateinit var downloadManager: DownloadManager
    private lateinit var audioQualityFormat: AudioQualityFormat
    private lateinit var connectivityManager: ConnectivityManager


    var downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    fun getDownload(songId: String): Flow<Download?> {
        return downloads.map { it[songId] }

    }

    @SuppressLint("LongLogTag")
    @Synchronized
    fun getDownloads() {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result

    }


    @SuppressLint("SuspiciousIndentation")
    @Synchronized
    fun getResolvingDataSourceFactory(context: Context): ResolvingDataSource.Factory {
        audioQualityFormat =
            context.preferences.getEnum(audioQualityFormatKey, AudioQualityFormat.Auto)

        //connectivityManager =
        //    getSystemService(context, ConnectivityManager::class.java) as ConnectivityManager

        val dataSourceFactory =
            ResolvingDataSource.Factory(createCacheDataSource(context)) { dataSpec ->
                val videoId = dataSpec.key?.removePrefix("https://youtube.com/watch?v=")
                    ?: error("A key must be set")

                //val chunkLength = 512 * 1024L
                //val chunkLength = 1024 * 1024L
                //val chunkLength = 10000 * 1024L
                //val chunkLength = 30000 * 1024L
                val chunkLength = 180000 * 1024L
                val ringBuffer = RingBufferPrevious<Pair<String, Uri>?>(2) { null }

                if (
                    dataSpec.isLocal ||
                    downloadCache.isCached(videoId, dataSpec.position, chunkLength)
                ) {
                    dataSpec
                } else {
                    when (videoId) {
                        ringBuffer.getOrNull(0)?.first -> dataSpec.withUri(ringBuffer.getOrNull(0)!!.second)
                        ringBuffer.getOrNull(1)?.first -> dataSpec.withUri(ringBuffer.getOrNull(1)!!.second)
                        "initVideoId" -> dataSpec
                        else -> run {
                            val body = runBlocking(Dispatchers.IO) {
                                Innertube.player(
                                    body = PlayerBody(videoId = videoId),
                                    pipedSession = getPipedSession().toApiSession()
                                )
                            }?.getOrElse { throwable ->
                                when (throwable) {
                                    is ConnectException, is UnknownHostException -> throw NoInternetException()
                                    is SocketTimeoutException -> throw TimeoutException()
                                    else -> throw UnknownException()
                                }
                            }
                            println("DownloadUtil createDataSourceResolverFactory body playabilityStatus ${body?.playabilityStatus?.status}")

                            val format = when (audioQualityFormat) {
                                AudioQualityFormat.Auto -> body?.streamingData?.highestQualityFormat
                                AudioQualityFormat.High -> body?.streamingData?.highestQualityFormat
                                AudioQualityFormat.Medium -> body?.streamingData?.mediumQualityFormat
                                AudioQualityFormat.Low -> body?.streamingData?.lowestQualityFormat
                            } ?: throw PlayableFormatNotFoundException()

                            println("DownloadUtil createDataSourceResolverFactory adaptiveFormats available bitrate ${body?.streamingData?.adaptiveFormats?.map { it.mimeType }}")
                            println("DownloadUtil createDataSourceResolverFactory adaptiveFormats selected $format")

                            val url = when (body?.playabilityStatus?.status) {
                                    "OK" -> {
                                        format.let { formatIn ->
                                            query {
                                                if (Database.songExist(videoId) == 1) {
                                                    Database.upsert(
                                                        Format(
                                                            songId = videoId,
                                                            itag = formatIn.itag,
                                                            mimeType = formatIn.mimeType,
                                                            bitrate = formatIn.bitrate,
                                                            loudnessDb = body.playerConfig?.audioConfig?.normalizedLoudnessDb,
                                                            contentLength = formatIn.contentLength,
                                                            lastModified = formatIn.lastModified
                                                        )
                                                    )
                                                }
                                            }

                                            formatIn.url
                                        } ?: throw PlayableFormatNotFoundException()
                                    }

                                    "UNPLAYABLE" -> throw UnplayableException()
                                    "LOGIN_REQUIRED" -> throw LoginRequiredException()
                                    else -> throw UnknownException()
                                }


                            val uri = url.toUri()
                            ringBuffer.append(videoId to uri)

                            dataSpec
                                .withUri(uri)

                        }
                    }
                }


            }
        return dataSourceFactory
    }

    private fun okHttpClient(): OkHttpClient {
        ProxyPreferences.preference?.let {
            return OkHttpClient.Builder()
                .proxy(
                    Proxy(
                        it.proxyMode,
                        InetSocketAddress(it.proxyHost, it.proxyPort)
                    )
                )
                .connectTimeout(Duration.ofSeconds(16))
                .readTimeout(Duration.ofSeconds(8))
                .build()
        }
        return OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(16))
            .readTimeout(Duration.ofSeconds(8))
            .build()
    }

    private fun createCacheDataSource(context: Context): DataSource.Factory {
        return CacheDataSource.Factory().setCache(getDownloadCache(context)).apply {
            setUpstreamDataSourceFactory(
                context.defaultDataSourceFactory
                //OkHttpDataSource.Factory(okHttpClient())
                //    .setUserAgent("Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/65.0.3325.181 Mobile Safari/537.36")
                /*
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(16000)
                    .setReadTimeoutMs(8000)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0")

                 */
            )
            setCacheWriteDataSinkFactory(null)
        }
    }


    @Synchronized
    fun getDownloadNotificationHelper(context: Context?): DownloadNotificationHelper {
        if (!DownloadUtil::downloadNotificationHelper.isInitialized) {
            downloadNotificationHelper =
                DownloadNotificationHelper(context!!, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        }
        return downloadNotificationHelper
    }

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        ensureDownloadManagerInitialized(context)
        return downloadManager
    }

    /*
        @Synchronized
        fun getDownloadTracker(context: Context): DownloadTracker {
            ensureDownloadManagerInitialized(context)
            return downloadTracker
        }

     */


    /*
    fun getDownloadString(context: Context, @Download.State downloadState: Int): String {
        return when (downloadState) {
            /*
            Download.STATE_COMPLETED -> context.resources.getString(R.string.exo_download_completed)
            Download.STATE_DOWNLOADING -> context.resources.getString(R.string.exo_download_downloading)
            Download.STATE_FAILED -> context.resources.getString(R.string.exo_download_failed)
            Download.STATE_QUEUED -> context.resources.getString(R.string.exo_download_queued)
            Download.STATE_REMOVING -> context.resources.getString(R.string.exo_download_removing)
            Download.STATE_RESTARTING -> context.resources.getString(R.string.exo_download_restarting)
            Download.STATE_STOPPED -> context.resources.getString(R.string.exo_download_stopped)
            else -> throw IllegalArgumentException()
             */
            Download.STATE_COMPLETED -> "Completed"
            Download.STATE_DOWNLOADING -> "Downloading"
            Download.STATE_FAILED -> "Failed"
            Download.STATE_QUEUED -> "Queued"
            Download.STATE_REMOVING -> "Removing"
            Download.STATE_RESTARTING -> "Restarting"
            Download.STATE_STOPPED -> "Stopped"
            else -> throw IllegalArgumentException()

        }
    }
    */

    @Synchronized
    private fun getDownloadCache(context: Context): Cache {
        if (!DownloadUtil::downloadCache.isInitialized) {
            val downloadContentDirectory =
                File(getDownloadDirectory(context), DOWNLOAD_CONTENT_DIRECTORY)
            downloadCache = SimpleCache(
                downloadContentDirectory,
                NoOpCacheEvictor(),
                getDatabaseProvider(context)
            )
        }
        return downloadCache
    }

    @Synchronized
    fun getDownloadSimpleCache(context: Context): Cache {
        if (!DownloadUtil::downloadCache.isInitialized) {
            val downloadContentDirectory =
                File(getDownloadDirectory(context), DOWNLOAD_CONTENT_DIRECTORY)
            downloadCache = SimpleCache(
                downloadContentDirectory,
                NoOpCacheEvictor(),
                getDatabaseProvider(context)
            )
        }
        return downloadCache
    }

    @Synchronized
    private fun ensureDownloadManagerInitialized(context: Context) {
        if (!DownloadUtil::downloadManager.isInitialized) {
            downloadManager = DownloadManager(
                context,
                getDatabaseProvider(context),
                getDownloadCache(context),
                //getHttpDataSourceFactory(context),
                //getReadOnlyDataSourceFactory(context),
                getResolvingDataSourceFactory(context),
                Executors.newFixedThreadPool(6)
            ).apply {
                maxParallelDownloads = 3
                minRetryCount = 1
                requirements = Requirements(Requirements.NETWORK)
            }

            //downloadTracker =
            //    DownloadTracker(context, getHttpDataSourceFactory(context), downloadManager)
        }
    }

    @Synchronized
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        if (!DownloadUtil::databaseProvider.isInitialized) databaseProvider =
            StandaloneDatabaseProvider(context)
        return databaseProvider
    }

    @Synchronized
    fun getDownloadDirectory(context: Context): File {
        if (!DownloadUtil::downloadDirectory.isInitialized) {
            downloadDirectory = context.getExternalFilesDir(null) ?: context.filesDir
            downloadDirectory.resolve(DOWNLOAD_CONTENT_DIRECTORY).also { directory ->
                if (directory.exists()) return@also
                directory.mkdir()
            }
            //Log.d("downloadMedia", downloadDirectory.path)
        }
        return downloadDirectory
    }


}
