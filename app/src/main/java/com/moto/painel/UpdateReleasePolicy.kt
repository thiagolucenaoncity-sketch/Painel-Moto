package com.moto.painel

/**
 * Apenas versões de APKs oficiais publicados no repositório do RAVO.
 * O workflow usa GITHUB_RUN_NUMBER + 2 como versionCode do Android.
 */
internal object UpdateReleasePolicy {
    private const val OWNER_REPO = "thiagolucenaoncity-sketch/Ravo"
    const val API = "https://api.github.com/repos/$OWNER_REPO/releases/latest"

    fun runNumber(tag: String): Int? {
        val match = Regex("^v1\\.1\\.([1-9][0-9]*)$").matchEntire(tag) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    fun versionCode(tag: String): Int? =
        runNumber(tag)?.takeIf { it < Int.MAX_VALUE - 2 }?.plus(2)

    fun expectedAssetName(tag: String): String? =
        runNumber(tag)?.let { "RAVO_atualizavel_v1.1.$it.apk" }

    fun expectedDownloadUrl(tag: String): String? =
        expectedAssetName(tag)?.let {
            "https://github.com/$OWNER_REPO/releases/download/$tag/$it"
        }

    fun isValidAsset(tag: String, name: String, url: String, bytes: Long): Boolean =
        name == expectedAssetName(tag) &&
            url == expectedDownloadUrl(tag) &&
            bytes in 1_000_000L..150_000_000L
}
