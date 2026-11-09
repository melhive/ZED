package com.melhive.zenith.android

import com.melhive.zenith.BuildConfig
import com.melhive.zenith.intelligence.providers.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases for a newer signed APK. Only the public release
 * metadata is requested (no personal data is sent). Installing is always the
 * user's choice: the app only opens the download link.
 */
class UpdateChecker(private val repo: String = REPO) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        val result = Http.getJson("https://api.github.com/repos/$repo/releases/latest", mapOf("Accept" to "application/vnd.github+json")).fold(
            onSuccess = { root -> parse(root)?.let { if (it.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(it) else UpdateState.UpToDate } ?: UpdateState.UpToDate },
            onFailure = { UpdateState.Failed(it.message ?: "Could not reach GitHub") },
        )
        _state.value = result
        result
    }

    companion object {
        const val REPO = "melhive/ZENITH"
        const val LATEST_APK = "https://github.com/$REPO/releases/latest/download/zenith.apk"

        /** Release tags are "v0.1.<versionCode>". */
        fun parse(root: JsonObject): UpdateInfo? {
            val tag = (root["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return null
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val apk = (root["assets"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == "zenith.apk" }
                ?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull } ?: LATEST_APK
            val page = (root["html_url"] as? JsonPrimitive)?.contentOrNull ?: "https://github.com/$REPO/releases/latest"
            return UpdateInfo(tag.removePrefix("v"), code, apk, page)
        }

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }
}
