package com.melhive.zed

import com.melhive.zed.android.UpdateChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckerTest {
    @Test fun parsesReleaseTagAndApkAsset() {
        val json = Json.parseToJsonElement(
            """{"tag_name":"v0.1.12","html_url":"https://github.com/melhive/ZED/releases/tag/v0.1.12",
               "assets":[{"name":"notes.txt","browser_download_url":"x"},{"name":"zed.apk","browser_download_url":"https://github.com/melhive/ZED/releases/download/v0.1.12/zed.apk"}]}"""
        ).jsonObject
        val info = UpdateChecker.parse(json)!!
        assertEquals(12, info.versionCode)
        assertEquals("0.1.12", info.versionName)
        assertEquals("https://github.com/melhive/ZED/releases/download/v0.1.12/zed.apk", info.apkUrl)
    }

    @Test fun rejectsUnexpectedTags() {
        assertNull(UpdateChecker.parse(Json.parseToJsonElement("""{"tag_name":"latest"}""").jsonObject))
    }
}
