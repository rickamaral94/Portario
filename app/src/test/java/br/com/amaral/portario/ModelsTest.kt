package br.com.amaral.portario

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    private val arm = listOf("arm64-v8a", "armeabi-v7a")
    private fun apk(id: Long, name: String) = Asset(id, name, "https://example.invalid/$name", 400, null)
    private fun release(tag: String, time: Long, beta: Boolean = false, vararg assets: Asset) =
        Release(tag, beta, "", time, assets.toList())

    @Test fun githubLinksAreNormalizedIncludingTags() {
        val path = parseGithubRef("Confira: https://github.com/Mamcjr/TOTKDroid/releases/?x=1")
        assertEquals("mamcjr/totkdroid", path?.key)
        assertEquals(path?.key, parseGithubRef("https://github.com/mamcjr/totkdroid.git")?.key)
        assertEquals("android/v1", parseGithubRef("https://github.com/mamcjr/totkdroid/releases/tag/android%2Fv1")?.tag)
        assertNull(parseGithubRef("https://github.com.evil.invalid/Mamcjr/TOTKDroid"))
        assertNull(parseGithubRef("https://github.com/Mamcjr/TOTKDroid/blob/main/README.md"))
    }

    @Test fun arm64IsPreferredAndAmbiguousAlternativesRemainSelectable() {
        val a64 = apk(1, "game-arm64.apk")
        val a32 = apk(2, "game-armeabi-v7a.apk")
        val desktop = apk(3, "game-x86_64.apk")
        val debug = apk(4, "game-arm64-debug.apk")
        val regional = apk(5, "game-arm64-pal.apk")
        val standard = release("v2", 2, false, a32, desktop, a64, debug)
        assertEquals(listOf(a64, a32), standard.compatibleAssets(arm, ""))
        assertEquals(a64, standard.recommended(arm, ""))
        assertNull(standard.copy(assets = standard.assets + regional).recommended(arm, ""))
        assertEquals(regional, standard.copy(assets = standard.assets + regional).recommended(arm, "-pal"))
    }

    @Test fun releaseChannelsHandleStableBetaAndSourceOnly() {
        val stable = release("v1", 1, false, apk(1, "game.apk"))
        val beta = release("v2-beta", 2, true, apk(2, "game.apk"))
        val source = release("v3", 3)
        assertEquals(stable, pickRelease(listOf(beta, source, stable), Channel.STABLE, arm, ""))
        assertEquals(beta, pickRelease(listOf(beta), Channel.STABLE, arm, ""))
        assertEquals(beta, pickRelease(listOf(source, beta), Channel.STABLE, arm, ""))
        assertEquals(beta, pickRelease(listOf(stable, beta), Channel.ALL, arm, ""))
        assertEquals(source, pickRelease(listOf(source), Channel.STABLE, arm, ""))
    }

    @Test fun tagAndInstalledVersionAreDifferentSignals() {
        val track = Track("game", "owner/game", "STABLE", installedTag = "v1", installedPublishedAt = 10,
            installedVersionCode = 100, installedAssetId = 1)
        val installed = release("v1", 10, false, apk(1, "game.apk"))
        val changedAsset = release("v1", 10, false, apk(2, "game.apk"))
        assertEquals(Publication.INSTALLED, publication(installed, track, true, 100))
        assertEquals(Publication.NEW_RELEASE, publication(changedAsset, track, true, 100))
        assertEquals(Publication.CHECK_VERSION, publication(installed, track, true, 101))
        assertEquals(Publication.NEW_RELEASE, publication(release("v2", 11), track, true, 100))
        assertEquals(Publication.NO_STATUS, publication(installed, track, false, null))
    }

    @Test fun aliasSearchIgnoresAccentsAndMatchesAcrossFields() {
        val game = Game("totk", "Zelda: Lágrimas do Reino", listOf("TOTK"), "Aventura", "Switch",
            listOf(Variant.custom(GithubRef("mamcjr/TOTKDroid"))))
        assertTrue(game.matches("lagrimas"))
        assertTrue(game.matches("TOTK Zelda"))
        assertTrue(game.matches("mamcjr"))
        assertFalse(game.matches("PS2 TOTK"))
    }

    @Test fun betaIsRecognizedFromTagEvenWithoutGithubFlag() {
        val r = Release.from(JSONObject("""{"tag_name":"v1.2-rc2","prerelease":false,"assets":[]}"""))
        assertTrue(r.beta)
    }
}
