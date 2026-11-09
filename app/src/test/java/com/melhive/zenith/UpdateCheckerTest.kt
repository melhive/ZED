package com.melhive.zenith

import com.melhive.zenith.android.UpdateChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckerTest {
    @Test fun parsesReleaseTagAndApkAsset() {
        val json = Json.parseToJsonElement(
            """{"tag_name":"v0.1.12","html_url":"https://github.com/melhive/ZENITH/releases/tag/v0.1.12",
               "assets":[{"name":"notes.txt","browser_download_url":"x"},{"name":"zenith.apk","browser_download_url":"https://github.com/melhive/ZENITH/releases/download/v0.1.12/zenith.apk"}]}"""
        ).jsonObject
        val info = UpdateChecker.parse(json)!!
        assertEquals(12, info.versionCode)
        assertEquals("0.1.12", info.versionName)
        assertEquals("https://github.com/melhive/ZENITH/releases/download/v0.1.12/zenith.apk", info.apkUrl)
    }

    @Test fun rejectsUnexpectedTags() {
        assertNull(UpdateChecker.parse(Json.parseToJsonElement("""{"tag_name":"latest"}""").jsonObject))
    }
}
