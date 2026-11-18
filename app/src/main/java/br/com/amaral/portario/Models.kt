package br.com.amaral.portario

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

private val slug = Regex("[A-Za-z0-9_.-]+")
private val betaTag = Regex("(^|[._-])(alpha|beta|rc|preview|experimental|nightly|test)([._-]|[0-9]|$)", RegexOption.IGNORE_CASE)
private val archive = Regex("(?:^|[._-])(debug|testonly|unsigned|unaligned|split|config|base)(?:[._-]|$)", RegexOption.IGNORE_CASE)

data class GithubRef(val repository: String, val tag: String? = null) {
    val key get() = repository.lowercase(Locale.ROOT)
    val url get() = "https://github.com/$repository"
}

fun parseGithubRef(input: String): GithubRef? {
    val text = input.trim()
    val candidate = Regex("https?://[^\\s<>]+").find(text)?.value?.trimEnd(')', ']', ',', '.')
        ?: "https://github.com/${text.trim('/')}"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (uri.scheme !in listOf("https", "http") || !uri.host.equals("github.com", true) ||
        uri.userInfo != null || uri.port !in listOf(-1, 443)
    ) return null
    val parts = uri.path.trim('/').split('/')
    if (parts.size < 2 || !slug.matches(parts[0])) return null
    val repo = parts[1].removeSuffix(".git")
    if (!slug.matches(repo) || listOf(parts[0], repo).any { it == "." || it == ".." }) return null
    val tag = when {
        parts.size == 2 -> null
        parts.size == 3 && parts[2] == "releases" -> null
        parts.size >= 5 && parts.subList(2, 4) == listOf("releases", "tag") ->
            parts.drop(4).joinToString("/").takeIf { it.isNotBlank() } ?: return null
        else -> return null
    }
    return GithubRef("${parts[0]}/$repo", tag)
}

enum class Channel { STABLE, ALL, BETA }
enum class Setup { DIRECT, EXTRA, MANUAL }
enum class Kind { RECOMP, DECOMP, SOURCE, ENGINE }

data class Asset(val id: Long, val name: String, val url: String, val bytes: Long, val digest: String?) {
    val abi: String? get() {
        val normalized = name.lowercase(Locale.ROOT)
        return when {
            listOf("arm64", "aarch64", "armv8").any(normalized::contains) -> "arm64-v8a"
            listOf("armeabi", "armv7", "aarch32").any(normalized::contains) -> "armeabi-v7a"
            listOf("x86_64", "x86-64", "amd64").any(normalized::contains) -> "x86_64"
            "x86" in normalized -> "x86"
            else -> null
        }
    }

    companion object {
        fun from(json: JSONObject) = Asset(
            json.getLong("id"), json.getString("name"), json.getString("browser_download_url"),
            json.getLong("size"), json.optString("digest").takeIf { it.startsWith("sha256:") }
        )
    }
}

data class Release(val tag: String, val beta: Boolean, val url: String, val publishedAt: Long, val assets: List<Asset>) {
    companion object {
        fun from(json: JSONObject): Release {
            val tag = json.getString("tag_name")
            val apkAssets = json.optJSONArray("assets")?.objects().orEmpty().map(Asset::from)
            return Release(tag, json.optBoolean("prerelease") || betaTag.containsMatchIn(tag),
                json.optString("html_url"), runCatching { Instant.parse(json.optString("published_at")).toEpochMilli() }.getOrDefault(0),
                apkAssets)
        }
    }
}

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()

fun Release.compatibleAssets(abis: List<String>, filter: String): List<Asset> = assets
    .filter { it.name.endsWith(".apk", true) && (filter.isNotBlank() || !archive.containsMatchIn(it.name)) }
    .filter { filter.isBlank() || it.name.contains(filter.trim(), true) }
    .filter { it.abi == null || it.abi in abis }
    .sortedWith(compareBy<Asset> { abis.indexOf(it.abi).takeIf { rank -> rank >= 0 } ?: abis.size }.thenBy { it.name })

fun Release.recommended(abis: List<String>, filter: String): Asset? {
    val compatible = compatibleAssets(abis, filter)
    val first = compatible.firstOrNull() ?: return null
    return first.takeIf { compatible.count { apk -> apk.abi == first.abi } == 1 }
}

fun pickRelease(releases: List<Release>, channel: Channel, abis: List<String>, filter: String): Release? {
    val sorted = releases.sortedByDescending { it.publishedAt }
    val byChannel = when (channel) {
        Channel.STABLE -> sorted.filterNot { it.beta }
        Channel.ALL -> sorted
        Channel.BETA -> sorted.filter { it.beta }
    }
    // Use the latest release that publishes the requested Android variant.
    // Keep the latest publication visible when a repository has only source releases.
    return byChannel.firstOrNull { it.compatibleAssets(abis, filter).isNotEmpty() }
        ?: sorted.firstOrNull { channel == Channel.STABLE && it.compatibleAssets(abis, filter).isNotEmpty() }
        ?: byChannel.firstOrNull() ?: sorted.firstOrNull().takeIf { channel == Channel.STABLE }
}

private fun assetFlavor(name: String) = name.lowercase(Locale.ROOT)
    .replace(Regex("v?[0-9]+(?:[._-][0-9]+)+"), "#")

fun preferredAsset(options: List<Asset>, previous: String): Asset? =
    options.firstOrNull { it.name == previous } ?: options.singleOrNull { assetFlavor(it.name) == assetFlavor(previous) }

fun Release.fingerprint() = "$tag:" + assets.filter { it.name.endsWith(".apk", true) }.maxOfOrNull { it.id }

data class Variant(
    val id: String, val gameId: String, val repository: String, val name: String,
    val kind: Kind, val stage: String, val setup: Setup, val channel: Channel,
    val guide: String, val requirements: String, val gameFiles: String, val controller: String,
    val evidence: List<String>, val checked: String, val note: String, val upstream: String,
    val filter: String, val verifiedTag: String, val verifiedAssets: List<String>, val packageId: String,
    val knownAbis: List<String>
) {
    val ref get() = GithubRef(repository)
    companion object {
        fun from(gameId: String, json: JSONObject) = with(json) {
            Variant(getString("id"), gameId, getString("repo"), getString("name"), Kind.valueOf(getString("kind")),
                getString("stage"), Setup.valueOf(getString("setup")),
                Channel.valueOf(optString("channel", "STABLE")), optString("guide"), optString("requirements"),
                optString("gameFiles"), optString("controller"), strings("evidence"), optString("checked"),
                optString("note"), optString("upstream"), optString("filter"), optString("verifiedTag"),
                strings("verifiedAssets"), optString("packageId"), strings("abis"))
        }

        fun custom(ref: GithubRef) = Variant(ref.key, ref.key, ref.repository, ref.repository.substringAfter('/'),
            Kind.SOURCE, "", Setup.EXTRA, Channel.STABLE, ref.url, "", "", "", emptyList(), "", "", "", "", "",
            emptyList(), "", emptyList())
    }
}

data class Game(val id: String, val title: String, val aliases: List<String>, val genre: String,
                val platform: String, val variants: List<Variant>) {
    fun matches(query: String): Boolean {
        val haystack = normalized((listOf(title, platform, genre) + aliases +
            variants.flatMap { listOf(it.name, it.repository) }).joinToString(" "))
        return normalized(query).split(' ').filter(String::isNotBlank).all(haystack::contains)
    }

    companion object {
        fun from(json: JSONObject): Game {
            val id = json.getString("id")
            return Game(id, json.getString("title"), json.strings("aliases"), json.getString("genre"),
                json.getString("platform"), json.getJSONArray("variants").objects().map { Variant.from(id, it) })
        }
    }
}

fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

enum class Publication { NEW_RELEASE, CHECK_VERSION, INSTALLED, NO_STATUS }

fun publication(release: Release?, track: Track?, packageVisible: Boolean, installedCode: Long?): Publication = when {
    track == null -> Publication.NO_STATUS
    release == null -> if (packageVisible) Publication.INSTALLED else Publication.NO_STATUS
    track.installedTag.isNotBlank() && release.tag != track.installedTag &&
        release.publishedAt >= track.installedPublishedAt -> Publication.NEW_RELEASE
    track.installedTag == release.tag && track.installedAssetId > 0 &&
        release.assets.any { it.name.endsWith(".apk", true) } &&
        release.assets.none { it.id == track.installedAssetId } -> Publication.NEW_RELEASE
    track.installedTag.isBlank() && track.followedRelease.isNotBlank() &&
        release.fingerprint() != track.followedRelease -> Publication.NEW_RELEASE
    packageVisible && installedCode != null && track.installedVersionCode != installedCode -> Publication.CHECK_VERSION
    packageVisible && track.installedTag.isBlank() -> Publication.CHECK_VERSION
    packageVisible -> Publication.INSTALLED
    else -> Publication.NO_STATUS
}
