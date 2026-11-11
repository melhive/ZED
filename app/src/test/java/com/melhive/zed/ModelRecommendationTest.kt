package com.melhive.zed

import com.melhive.zed.models.DeviceProfile
import com.melhive.zed.models.Fit
import com.melhive.zed.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommendationTest {
    private val GB = 1024L * 1024 * 1024

    @Test fun catalogEntriesAreWellFormed() {
        assertEquals(ModelCatalog.models.size, ModelCatalog.models.map { it.id }.toSet().size)
        for (m in ModelCatalog.models) {
            assertEquals(m.id, 64, m.sha256.length)
            assertTrue(m.id, m.fileName.endsWith(".litertlm"))
            assertTrue(m.id, m.description.length > 60)
            assertTrue(m.id, m.quality in 1..5 && m.speed in 1..5)
        }
    }

    @Test fun midRangePhoneGetsGemmaGpu() {
        // MediaTek Dimensity 7300, 7.3 GB RAM, 4 fast cores (the developer's phone).
        val d = DeviceProfile((7.3 * GB).toLong(), 40 * GB, 4, "MediaTek MT6878", arm64 = true)
        assertEquals("gemma-4-e2b-it-gpu", ModelCatalog.recommend(d)?.id)
        assertEquals(Fit.SLOW, ModelCatalog.assess(ModelCatalog.find("gemma-4-e4b-it-gpu")!!, d).fit)
        assertEquals(Fit.GOOD, ModelCatalog.assess(ModelCatalog.find("lfm2.5-1.2b")!!, d).fit)
    }

    @Test fun smallPhonesGetSmallModels() {
        val d = DeviceProfile(3L * GB, 20 * GB, 4, "Unisoc T606", arm64 = true)
        val pick = ModelCatalog.recommend(d)!!
        assertTrue(pick.id, pick.sizeBytes < 1_000_000_000)
        assertEquals(Fit.TOO_BIG, ModelCatalog.assess(ModelCatalog.find("gemma-4-e2b-it")!!, d).fit)
    }

    @Test fun flagshipsGetTheBestQuality() {
        val d = DeviceProfile(16L * GB, 200 * GB, 4, "Qualcomm SM8750", arm64 = true)
        assertEquals(5, ModelCatalog.recommend(d)!!.quality)
    }

    @Test fun lowStorageAndX86AreHandled() {
        assertEquals(Fit.TOO_BIG, ModelCatalog.assess(ModelCatalog.find("gemma-4-e2b-it")!!, DeviceProfile(8L * GB, 1L * GB, 4, "MT6878", true)).fit)
        assertEquals(Fit.INCOMPATIBLE, ModelCatalog.assess(ModelCatalog.find("qwen3-0.6b")!!, DeviceProfile(8L * GB, 50 * GB, 4, "x86", false)).fit)
    }
}
