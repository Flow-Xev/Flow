package app.zaid.flow.innertube.pages.channel

import app.zaid.flow.innertube.pages.arrayOrNull
import app.zaid.flow.innertube.pages.objectOrNull
import app.zaid.flow.innertube.pages.renderer.forEachObject
import app.zaid.flow.innertube.pages.renderer.largestImageUrl
import app.zaid.flow.innertube.pages.stringOrNull
import app.zaid.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URLDecoder

/** One item from a channel's Store tab. [url] is an absolute link (https, http or another safe scheme), opened via the system. */
data class ChannelProduct(
    val title: String,
    val url: String,
    val price: String? = null,
    val imageUrl: String? = null,
    val merchant: String? = null,
)

/** One page of products plus the token for the next page, if the tab has one. */
data class ChannelStorePage(
    val products: List<ChannelProduct>,
    val continuation: String? = null,
)

/** The renderers YouTube uses for a product card, depending on the surface that carries it. */
internal val PRODUCT_RENDERERS =
    setOf(
        "verticalProductCardRenderer",
        "productListItemRenderer",
        "horizontalProductCardRenderer",
        "merchandiseItemRenderer",
        "productCardRenderer",
        "shoppingProductRenderer",
    )

internal val TITLE_KEYS = listOf("title", "productTitle", "name", "headline", "primaryText")
private val PRICE_KEYS = listOf("price", "priceText", "formattedPrice", "displayPrice", "priceRange", "salePrice", "secondaryText")
private val MERCHANT_KEYS = listOf("merchantName", "vendorName", "sellerName", "merchant", "brandName", "subtitle")
private val IMAGE_KEYS = listOf("thumbnail", "image", "productImage", "thumbnailImage", "imageSource")
private val LINK_ROOT_KEYS =
    listOf(
        "onTap",
        "buttonCommand",
        "buttonNavigationEndpoint",
        "onClickCommand",
        "navigationEndpoint",
        "endpoint",
        "command",
        "productUrl",
        "url",
    )

/**
 * Every product in a Store tab response, in page order and without duplicates.
 *
 * Never throws: a malformed card is skipped, so one odd item cannot cost the user the whole list.
 * Items whose link cannot be resolved to a safe link are dropped — a product that opens
 * nothing is noise in a list people tap.
 */
internal fun JsonElement.toChannelStorePage(): ChannelStorePage {
    val products = mutableListOf<ChannelProduct>()
    var continuation: String? = null
    runCatching {
        forEachObject { node ->
            for ((key, value) in node) {
                if (key in PRODUCT_RENDERERS) {
                    runCatching { value.objectOrNull()?.toChannelProduct() }.getOrNull()?.let(products::add)
                }
            }
            if (continuation == null) {
                continuation =
                    node["continuationItemRenderer"]
                        .objectOrNull()
                        ?.get("continuationEndpoint")
                        .objectOrNull()
                        ?.get("continuationCommand")
                        .objectOrNull()
                        ?.get("token")
                        .stringOrNull()
                        ?.takeIf(String::isNotBlank)
            }
        }
    }
    return ChannelStorePage(products.distinctBy { it.url }, continuation)
}

internal fun JsonElement.toChannelProducts(): List<ChannelProduct> = toChannelStorePage().products

internal fun JsonObject.firstText(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key -> this[key].youtubeText()?.trim()?.takeIf(String::isNotEmpty) }

private fun JsonObject.toChannelProduct(): ChannelProduct? {
    val title = firstText(TITLE_KEYS) ?: return null
    val url = productUrl() ?: return null
    return ChannelProduct(
        title = title,
        url = url,
        price = firstText(PRICE_KEYS)?.takeIf { it != title },
        imageUrl = IMAGE_KEYS.firstNotNullOfOrNull { this[it].largestImageUrl() }?.let(::absoluteImageUrl),
        merchant = firstText(MERCHANT_KEYS)?.takeIf { it != title },
    )
}

private fun absoluteImageUrl(url: String): String? =
    when {
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("//") -> "https:$url"
        else -> null
    }

/** The first outbound link under a tap or navigation endpoint, unwrapped from YouTube's redirector. */
internal fun JsonObject.productUrl(): String? {
    for (key in LINK_ROOT_KEYS) {
        val root = this[key] ?: continue
        val candidates = mutableListOf<String>()
        root.stringOrNull()?.let(candidates::add)
        root.forEachObject { node ->
            node["urlEndpoint"]
                .objectOrNull()
                ?.get("url")
                .stringOrNull()
                ?.let(candidates::add)
            node["url"].stringOrNull()?.let(candidates::add)
        }
        candidates.firstNotNullOfOrNull(::safeLink)?.let { return it }
    }
    return null
}

/** Schemes that can run code, read local data or launch arbitrary apps; never opened from a product link. */
private val BLOCKED_SCHEMES =
    setOf("javascript", "vbscript", "data", "blob", "file", "content", "intent", "android-app", "about", "view-source", "filesystem")

private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
private val BARE_HOST_REGEX = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:\\d+)?([/?#].*)?$")

/**
 * Resolves [raw] to a usable link, unwrapping `youtube.com/redirect`; null when unusable.
 *
 * HTTPS is no longer required: http, mailto, tel, ftp, market and other app or custom schemes are all
 * kept so more items can be shown. Scheme-less input (`//host/p`, `/path`, `shop.example/p`) becomes
 * https. Only schemes in [BLOCKED_SCHEMES] (code execution, local files, raw intents) are refused.
 */
internal fun safeLink(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
    val absolute =
        when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://www.youtube.com$trimmed"
            !SCHEME_REGEX.containsMatchIn(trimmed) && BARE_HOST_REGEX.matches(trimmed) -> "https://$trimmed"
            else -> trimmed
        }
    val unwrapped =
        if (absolute.contains("youtube.com/redirect")) {
            val target =
                absolute
                    .substringAfter("?", "")
                    .split('&')
                    .firstOrNull { it.startsWith("q=") }
                    ?.removePrefix("q=")
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            // The redirect target may itself be scheme-less or protocol-relative.
            target?.takeIf { it.isNotBlank() }?.let { t ->
                if (SCHEME_REGEX.containsMatchIn(t)) {
                    t
                } else if (t.startsWith("//")) {
                    "https:$t"
                } else {
                    "https://$t"
                }
            } ?: return null
        } else {
            absolute
        }
    val uri = runCatching { URI(unwrapped) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme in BLOCKED_SCHEMES) return null
    return if (scheme == "http" || scheme == "https") {
        unwrapped.takeIf { !uri.host.isNullOrBlank() }
    } else {
        // mailto:, tel:, market://, custom app schemes: need something after the scheme.
        unwrapped.takeIf { !uri.schemeSpecificPart.isNullOrBlank() }
    }
}

/** Short label for a link: the host for web links, otherwise the scheme (e.g. "mailto"). */
internal fun linkLabel(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.removePrefix("www.")
    return when {
        !host.isNullOrBlank() -> if (scheme == "http") "$host · http" else host
        scheme.isNotEmpty() -> scheme
        else -> url
    }
}
