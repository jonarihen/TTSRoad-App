package dk.perspektiva.ttsroad.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A release available on GitHub. */
data class ReleaseInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * In-app updater for the sideloaded build. Checks the GitHub Releases of [REPO] for a newer
 * version, downloads the attached APK, and hands it to the system package installer — so new
 * builds arrive without copying an APK around by hand.
 *
 * Requires `REQUEST_INSTALL_PACKAGES` + a FileProvider (see AndroidManifest). New releases must be
 * signed with the same key as the installed app (the debug key, for personal builds) to update
 * in place.
 */
class UpdateManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** [manual] checks surface "up to date"/errors; automatic checks fail silently back to Idle. */
    suspend fun check(currentVersionName: String, manual: Boolean = false) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        val release = try {
            fetchLatestRelease()
        } catch (e: Exception) {
            _state.value = if (manual) UpdateState.Failed(e.message ?: "Update check failed") else UpdateState.Idle
            return
        }
        _state.value = when {
            release == null -> if (manual) UpdateState.Failed("No releases found") else UpdateState.Idle
            isNewer(release.versionName, currentVersionName) -> UpdateState.Available(release)
            else -> UpdateState.UpToDate
        }
    }

    suspend fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        _state.value = UpdateState.Downloading(0)
        try {
            val apk = withContext(Dispatchers.IO) { download(context, release.apkUrl) }
            installApk(context, apk)
            _state.value = UpdateState.Idle
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Download failed")
        }
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    private suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null // no releases yet
            if (!response.isSuccessful) throw IOException("GitHub returned ${response.code}")
            val body = response.body?.string() ?: return@withContext null
            val release = moshi.adapter(GithubRelease::class.java).fromJson(body) ?: return@withContext null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@withContext null
            ReleaseInfo(
                versionName = release.tagName.removePrefix("v").trim(),
                notes = release.body?.trim().orEmpty(),
                apkUrl = apk.browserDownloadUrl,
            )
        }
    }

    private fun download(context: Context, url: String): File {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            val out = File(context.cacheDir, "update.apk")
            body.byteStream().use { input ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            _state.value = UpdateState.Downloading((downloaded * 100 / total).toInt())
                        }
                    }
                }
            }
            return out
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Semver-ish comparison: "0.4.0" vs "0.3.1" → true. */
    private fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
        val r = parts(remote)
        val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private data class GithubRelease(
        @param:Json(name = "tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        val assets: List<GithubAsset> = emptyList(),
    )

    private data class GithubAsset(
        val name: String = "",
        @param:Json(name = "browser_download_url") val browserDownloadUrl: String = "",
    )

    private companion object {
        const val REPO = "jonarihen/TTSRoad-App"
    }
}
