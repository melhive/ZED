package com.melhive.zed.models

/**
 * On-device models ZED can download. Every entry was checked against the
 * Hugging Face API (file name, exact byte size, SHA-256 of the LFS object and
 * license). Downloads are verified against [sha256] before use.
 *
 * All entries use the `.litertlm` format consumed by LiteRT-LM.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    /** One plain-language paragraph: what it is and what it's like to use. */
    val description: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    /** Device RAM we recommend (the model needs roughly its file size plus ~0.6 GB while running). */
    val minRamBytes: Long,
    val recommendedBackend: String,
    /** Maximum context the model supports (ZED uses a smaller window, see Model Manager). */
    val contextTokens: Int,
    /** What it's best at, shown as tags. */
    val bestFor: List<String> = emptyList(),
    /** 1 (basic) – 5 (best) for answers and following instructions. */
    val quality: Int = 3,
    /** 1 (slow) – 5 (instant) on a typical mid-range phone. */
    val speed: Int = 3,
    /** Tested end to end with ZED on a phone. */
    val verified: Boolean = false,
    /** Thinks step by step before answering (smarter on puzzles, slower to reply). */
    val reasoning: Boolean = false,
    /** Can look at photos on the phone ("what is this?"). */
    val vision: Boolean = false,
) {
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    val pageUrl: String get() = "https://huggingface.co/$repo"
    val isGpuBuild: Boolean get() = recommendedBackend == "GPU"
}

/** What the phone can handle, from its real specs. */
data class DeviceProfile(val totalRamBytes: Long, val freeStorageBytes: Long, val performanceCores: Int, val soc: String, val arm64: Boolean) {
    private val socLower = soc.lowercase()
    /** Phones whose GPUs LiteRT supports well (Adreno, Mali on MediaTek/Exynos/Tensor). */
    val gpuLikely: Boolean get() = listOf("qualcomm", "qcom", "sm", "mediatek", "mt", "exynos", "samsung", "google", "tensor").any { socLower.contains(it) }
    val ramGb: Double get() = totalRamBytes / (1024.0 * 1024 * 1024)
}

enum class Fit(val label: String, val rank: Int) {
    RECOMMENDED("Recommended for your phone", 0),
    GOOD("Runs well", 1),
    SLOW("Works, but slow on this phone", 2),
    TOO_BIG("Too big for this phone", 3),
    INCOMPATIBLE("Not supported on this phone", 4),
}

data class ModelAssessment(val fit: Fit, val reason: String)

object ModelCatalog {
    private const val GB = 1024L * 1024 * 1024
    private const val MB = 1024L * 1024

    val models = listOf(
        CatalogModel(
            id = "gemma-4-e2b-it-gpu", name = "Gemma 4 E2B · GPU",
            description = "Google's best small model, built for the phone's graphics chip. Smart, natural conversation, good at following instructions, and it can look at photos (“what is this?”) right on your phone. Replies start quickly on phones with a supported GPU. The best everyday choice for most mid-range and better phones.",
            repo = "litert-community/gemma-4-E2B-it-litert-lm", fileName = "gemma-4-E2B-it-gpu.litertlm",
            // Mobile GPU build with vision and audio encoders (checked in the file's section table). The
            // repo's earlier "-gpu" file (2,008,432,640 bytes) was the text-only web build, now named "-web".
            sizeBytes = 2_340_870_772, sha256 = "d3cfec6ae2e027a994e51366d44dbf522ec7531a4a35fbbfcd3e9cc9b927afc1",
            license = "Apache-2.0", minRamBytes = 4 * GB, recommendedBackend = "GPU", contextTokens = 32768,
            bestFor = listOf("Everyday chat", "Actions", "Multilingual"), quality = 4, speed = 4, verified = true, vision = true,
        ),
        CatalogModel(
            id = "gemma-4-e2b-it", name = "Gemma 4 E2B · CPU",
            description = "The same Gemma 4 E2B model packaged for the processor, including photo understanding. Use it when the GPU build doesn't start on your phone; same intelligence, a bit slower.",
            repo = "litert-community/gemma-4-E2B-it-litert-lm", fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712, sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            license = "Apache-2.0", minRamBytes = 4 * GB, recommendedBackend = "CPU", contextTokens = 32768,
            bestFor = listOf("Everyday chat", "Actions", "Any phone"), quality = 4, speed = 3, verified = true, vision = true,
        ),
        CatalogModel(
            id = "gemma-4-e4b-it-gpu", name = "Gemma 4 E4B · GPU",
            description = "Gemma 4's bigger sibling: noticeably better at explanations, writing, tricky questions and describing photos. Needs a high-end phone with 8 GB+ RAM; slower to reply than E2B.",
            repo = "litert-community/gemma-4-E4B-it-litert-lm", fileName = "gemma-4-E4B-it-gpu.litertlm",
            sizeBytes = 3_310_938_740, sha256 = "ea168f5a601b543e02712f9381f26b06ddc7d4cbe2406410387c82d69b995a60",
            license = "Apache-2.0", minRamBytes = 7 * GB, recommendedBackend = "GPU", contextTokens = 32768,
            bestFor = listOf("Best quality", "Writing", "Explanations"), quality = 5, speed = 2, vision = true,
        ),
        CatalogModel(
            id = "qwen3.5-4b", name = "Qwen 3.5 4B",
            description = "Alibaba's latest 4-billion-parameter model, compressed to 4-bit. Strong reasoning, maths and coding for its size, with good multilingual support. For phones with 8 GB+ RAM.",
            repo = "litert-community/Qwen3.5-4B", fileName = "Qwen3.5-4B_mixed_int4.litertlm",
            sizeBytes = 2_754_166_784, sha256 = "878e5d1926ecc4930e24c62de43a7038edeeb0c57446231086b139892984a5fd",
            license = "Apache-2.0", minRamBytes = 7 * GB, recommendedBackend = "CPU", contextTokens = 32768,
            bestFor = listOf("Reasoning", "Maths", "Coding"), quality = 5, speed = 2,
        ),
        CatalogModel(
            id = "qwen3-4b-2507", name = "Qwen 3 4B Instruct (2507)",
            description = "A 4B model tuned to follow instructions closely and answer directly without long thinking. Good for precise tasks and summaries. For phones with 8 GB+ RAM.",
            repo = "litert-community/Qwen3-4B-Instruct-2507", fileName = "qwen3_4b_instruct_2507_mixed_int4.litertlm",
            sizeBytes = 2_659_057_664, sha256 = "9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd",
            license = "Apache-2.0", minRamBytes = 7 * GB, recommendedBackend = "CPU", contextTokens = 32768,
            bestFor = listOf("Precise answers", "Summaries"), quality = 4, speed = 2,
        ),
        CatalogModel(
            id = "ministral-3-3b", name = "Ministral 3 3B",
            description = "Mistral AI's compact model: clear, well-structured answers and strong European languages (French, Spanish, German…). Fits phones with 6 GB+ RAM.",
            repo = "litert-community/Ministral-3-3B-Instruct-2512", fileName = "Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm",
            sizeBytes = 2_342_895_616, sha256 = "2e01bdbef438c1a57da21cfc9c040b271b563c0f77b8b7d1907a6154f962d0f1",
            license = "Apache-2.0", minRamBytes = 6 * GB, recommendedBackend = "CPU", contextTokens = 4096,
            bestFor = listOf("Clear answers", "European languages"), quality = 4, speed = 3,
        ),
        CatalogModel(
            id = "qwen3.5-2b", name = "Qwen 3.5 2B",
            description = "A balanced 2B model: quicker than the 4B ones, still capable for chat, short reasoning and translation. A good alternative to Gemma 4 E2B.",
            repo = "litert-community/Qwen3.5-2B", fileName = "Qwen3.5-2B_int8.litertlm",
            sizeBytes = 2_119_843_840, sha256 = "c2438cb9c050220e667366b4beeca4daac56e693b226420674f53fc40f007c49",
            license = "Apache-2.0", minRamBytes = 5 * GB, recommendedBackend = "CPU", contextTokens = 32768,
            bestFor = listOf("Balanced", "Translation"), quality = 3, speed = 3,
        ),
        CatalogModel(
            id = "phi-4-mini", name = "Phi-4 Mini",
            description = "Microsoft's 3.8B model, excellent at maths, logic and step-by-step explanations. Large download and memory-hungry: for 8 GB+ phones.",
            repo = "litert-community/Phi-4-mini-instruct", fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 3_910_090_752, sha256 = "7764d4deb53800578307be33039476b38a6c370fff71bedb3c0552563e23ab02",
            license = "MIT", minRamBytes = 8 * GB, recommendedBackend = "CPU", contextTokens = 4096,
            bestFor = listOf("Maths", "Logic"), quality = 4, speed = 1,
        ),
        CatalogModel(
            id = "deepseek-r1-1.5b", name = "DeepSeek R1 Distill 1.5B",
            description = "A reasoning model: it thinks out loud before answering, which helps with puzzles and maths but makes replies longer and slower. Not ideal for quick chat.",
            repo = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B", fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_833_451_520, sha256 = "69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295",
            license = "MIT", minRamBytes = 4 * GB, recommendedBackend = "CPU", contextTokens = 4096,
            bestFor = listOf("Puzzles", "Step-by-step"), quality = 3, speed = 2, reasoning = true,
        ),
        CatalogModel(
            id = "lfm2.5-1.2b", name = "LFM 2.5 1.2B",
            description = "Liquid AI's speed-focused model: very fast replies with surprisingly good chat quality for its size. Great for older or 4–6 GB phones.",
            repo = "litert-community/LFM2.5-1.2B-Instruct", fileName = "LFM2.5-1.2B-Instruct_int4.litertlm",
            sizeBytes = 736_015_744, sha256 = "e904729a8a8a0160658cb7cd22992e270a0547c05cc45b08c47ece5c0e29425b",
            license = "LFM Open License (free for personal use)", minRamBytes = 3 * GB, recommendedBackend = "CPU", contextTokens = 32768,
            bestFor = listOf("Fast replies", "Older phones"), quality = 3, speed = 5,
        ),
        CatalogModel(
            id = "qwen3.5-0.8b", name = "Qwen 3.5 0.8B",
            description = "Tiny and quick, newer than Qwen 3 0.6B and noticeably better at conversation. Good for simple questions on budget phones.",
            repo = "litert-community/Qwen3.5-0.8B", fileName = "Qwen3.5-0.8B_dynamic_wi8_afp32.litertlm",
            sizeBytes = 790_659_072, sha256 = "63f10ec01fcc6b8fbe36612658593ae99c2b369cada6558f6e18b28ca01824c4",
            license = "Apache-2.0", minRamBytes = 3 * GB, recommendedBackend = "CPU", contextTokens = 32768,
            bestFor = listOf("Fast replies", "Budget phones"), quality = 2, speed = 5,
        ),
        CatalogModel(
            id = "qwen3-0.6b", name = "Qwen 3 0.6B",
            description = "The smallest download (347 MB). Answers instantly but understands little; mainly for very old phones or testing on-device AI.",
            repo = "litert-community/Qwen3-0.6B-int4", fileName = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
            sizeBytes = 347_251_840, sha256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139",
            license = "Apache-2.0", minRamBytes = 2 * GB, recommendedBackend = "CPU", contextTokens = 1280,
            bestFor = listOf("Tiny", "Testing"), quality = 1, speed = 5,
        ),
        CatalogModel(
            id = "smollm2-360m", name = "SmolLM2 360M",
            description = "An ultra-light English model for phones with 2–3 GB RAM. Fine for greetings and simple facts; not for complex requests.",
            repo = "litert-community/SmolLM2-360M-Instruct", fileName = "SmolLM2_360M_instruct.litertlm",
            sizeBytes = 373_719_040, sha256 = "8e2834da211b439751af968ed650febdde5a8cb8d88bc6c1a3059f049caa5c2e",
            license = "Apache-2.0", minRamBytes = 2 * GB, recommendedBackend = "CPU", contextTokens = 2048,
            bestFor = listOf("Tiny", "English"), quality = 1, speed = 5,
        ),
    )

    fun find(id: String) = models.firstOrNull { it.id == id }

    /** Memory the model needs while running: its file plus runtime buffers. */
    fun runtimeRam(m: CatalogModel): Long = m.sizeBytes + 600 * MB

    /** How well [m] suits this phone, with a reason in plain words. */
    fun assess(m: CatalogModel, d: DeviceProfile): ModelAssessment {
        if (!d.arm64) return ModelAssessment(Fit.INCOMPATIBLE, "On-device models need a 64-bit ARM phone.")
        if (d.freeStorageBytes < m.sizeBytes + 300 * MB) return ModelAssessment(Fit.TOO_BIG, "Needs ${gb(m.sizeBytes)} of free storage.")
        val need = runtimeRam(m)
        val ram = d.totalRamBytes
        if (need > ram * 0.62) return ModelAssessment(Fit.TOO_BIG, "Needs about ${gb(need)} of memory; this phone has ${gb(ram)} in total.")
        if (m.isGpuBuild && !d.gpuLikely) return ModelAssessment(Fit.SLOW, "This GPU build may not start on your chip — the CPU build is safer.")
        val heavy = need > ram * 0.45 || (m.speed <= 2 && d.performanceCores < 4)
        return if (heavy) ModelAssessment(Fit.SLOW, "Fits, but it uses most of your memory (${gb(need)} of ${gb(ram)}); expect slower replies and close other apps.")
        else ModelAssessment(Fit.GOOD, "Fits comfortably: about ${gb(need)} of your ${gb(ram)} memory.")
    }

    /**
     * The model to suggest first: the highest quality that runs comfortably, preferring faster
     * models on mid-range phones, a GPU build when the chip supports it, and no reasoning models.
     */
    fun recommend(d: DeviceProfile): CatalogModel? {
        val candidates = models.filter { !it.reasoning }.map { it to assess(it, d) }.filter { it.second.fit == Fit.GOOD }
        // Mid-range phones: replies must stay quick, so speed counts a lot. Phones with plenty of memory: quality first.
        val flagship = d.ramGb >= 10
        val scored = candidates.map { (m, _) ->
            val gpuBonus = if (m.isGpuBuild && d.gpuLikely) 0.6 else 0.0
            val score = if (flagship) m.quality * 2.0 + m.speed * 0.3 else m.quality + m.speed * 0.7
            m to (score + gpuBonus + if (m.verified) 0.3 else 0.0)
        }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun gb(b: Long) = String.format("%.1f GB", b / GB.toDouble())
}
