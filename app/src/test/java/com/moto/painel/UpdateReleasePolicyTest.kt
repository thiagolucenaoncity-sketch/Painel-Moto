package com.moto.painel

import org.junit.Assert.*
import org.junit.Test

class UpdateReleasePolicyTest {
    @Test fun releaseTagMatchesInstalledVersionCode() {
        assertEquals(302, UpdateReleasePolicy.versionCode("v1.1.300"))
        assertEquals("RAVO_atualizavel_v1.1.300.apk",
            UpdateReleasePolicy.expectedAssetName("v1.1.300"))
    }

    @Test fun onlyExactGithubSignedArtifactCanBeOffered() {
        val tag = "v1.1.300"
        val url = UpdateReleasePolicy.expectedDownloadUrl(tag)!!
        assertTrue(UpdateReleasePolicy.isValidAsset(
            tag, "RAVO_atualizavel_v1.1.300.apk", url, 27_000_000L))
        assertFalse(UpdateReleasePolicy.isValidAsset(
            tag, "RAVO_DEV_NAO_ATUALIZAVEL_v1.1.300.apk", url, 27_000_000L))
        assertFalse(UpdateReleasePolicy.isValidAsset(
            tag, "RAVO_atualizavel_v1.1.300.apk", "https://evil.example/update.apk", 27_000_000L))
        assertFalse(UpdateReleasePolicy.isValidAsset(
            tag, "RAVO_atualizavel_v1.1.300.apk", url, 1L))
    }

    @Test fun invalidTagsCannotOfferDownloads() {
        listOf("dev", "v1.1.a", "v1.2.100", "v1.1.0", "../v1.1.23", "v1.1.2147483647")
            .forEach { assertNull(UpdateReleasePolicy.versionCode(it)) }
    }
}
