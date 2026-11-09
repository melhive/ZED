package com.melhive.zenith.models

/**
 * On-device models ZENITH can download. Every entry was checked against the
 * Hugging Face API (file name, exact byte size, SHA-256 of the LFS object and
 * license). Downloads are verified against [sha256] before use.
 *
 * All entries use the `.litertlm` format consumed by LiteRT-LM.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val description: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    /** Device RAM we recommend (runtime uses ~1.4–1.7 GB for Gemma 4 E2B per Google's model card); a heuristic, not a guarantee. */
    val minRamBytes: Long,
    val recommendedBackend: String,
    /** Maximum context the model supports (ZENITH uses a smaller window, see Model Manager). */
    val contextTokens: Int,
) {
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    val pageUrl: String get() = "https://huggingface.co/$repo"
}

object ModelCatalog {
    private const val GB = 1024L * 1024 * 1024

    val models = listOf(
        CatalogModel(
            id = "gemma-4-e2b-it",
            name = "Gemma 4 E2B Instruct",
            description = "Google's efficient on-device model (≈2B effective parameters). Best balance of quality and speed for phones.",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            license = "Apache-2.0",
            minRamBytes = 4 * GB,
            recommendedBackend = "CPU",
            contextTokens = 32768,
        ),
        CatalogModel(
            id = "gemma-4-e2b-it-gpu",
            name = "Gemma 4 E2B Instruct (GPU build)",
            description = "Same model packaged for GPU acceleration. Smaller download; requires a device GPU supported by LiteRT.",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it-gpu.litertlm",
            sizeBytes = 2_008_432_640,
            sha256 = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
            license = "Apache-2.0",
            minRamBytes = 4 * GB,
            recommendedBackend = "GPU",
            contextTokens = 32768,
        ),
        CatalogModel(
            id = "gemma-4-e4b-it-gpu",
            name = "Gemma 4 E4B Instruct (GPU build)",
            description = "Larger, more capable Gemma 4 variant. Needs a high-end phone.",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it-gpu.litertlm",
            sizeBytes = 2_969_059_328,
            sha256 = "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff",
            license = "Apache-2.0",
            minRamBytes = 6 * GB,
            recommendedBackend = "GPU",
            contextTokens = 32768,
        ),
        CatalogModel(
            id = "qwen3-0.6b",
            name = "Qwen3 0.6B (int4)",
            description = "Very small, fast model for testing the local runtime on modest devices. Limited reasoning.",
            repo = "litert-community/Qwen3-0.6B-int4",
            fileName = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
            sizeBytes = 347_251_840,
            sha256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139",
            license = "Apache-2.0",
            minRamBytes = 2 * GB,
            recommendedBackend = "CPU",
            contextTokens = 1280,
        ),
    )

    fun find(id: String) = models.firstOrNull { it.id == id }
}
