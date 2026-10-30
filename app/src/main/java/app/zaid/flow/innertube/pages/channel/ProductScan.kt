package app.zaid.flow.innertube.pages.channel

import app.zaid.flow.innertube.pages.objectOrNull
import app.zaid.flow.innertube.pages.renderer.forEachObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Diagnostics for "why does this video show no tagged products?". Nothing here changes what the
 * parser returns; it reports what a response actually contains so a missing format can be seen
 * instead of guessed.
 */
internal data class ProductKeyHit(
    val shape: String,
    val count: Int,
)

internal data class ProductCardTally(
    var found: Int = 0,
    var parsed: Int = 0,
    var noTitle: Int = 0,
    var noLink: Int = 0,
)

private val PRODUCT_KEY_REGEX = Regex("product|merchandise|shopping|shelf|storefront", RegexOption.IGNORE_CASE)
private const val MAX_SCAN_DEPTH = 80
private const val MAX_HITS = 200

/** Every key whose name looks product-related, as a path with array indices collapsed to `[]`, with how often it occurs. */
internal fun JsonElement.scanProductKeys(): List<ProductKeyHit> {
    val counts = linkedMapOf<String, Int>()

    fun walk(
        node: JsonElement,
        path: String,
        depth: Int,
    ) {
        if (depth > MAX_SCAN_DEPTH) return
        when (node) {
            is JsonObject -> {
                for ((key, value) in node) {
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    if (PRODUCT_KEY_REGEX.containsMatchIn(key) && (counts.size < MAX_HITS || counts.containsKey(childPath))) {
                        counts[childPath] = (counts[childPath] ?: 0) + 1
                    }
                    walk(value, childPath, depth + 1)
                }
            }

            is JsonArray -> {
                node.forEach { walk(it, "$path[]", depth + 1) }
            }

            else -> {
                Unit
            }
        }
    }
    runCatching { walk(this, "", 0) }
    return counts.map { ProductKeyHit(it.key, it.value) }
}

/** For each renderer name the parser knows: how many cards exist, how many parsed, and why the rest were dropped. */
internal fun JsonElement.tallyProductCards(): Map<String, ProductCardTally> {
    val tallies = linkedMapOf<String, ProductCardTally>()
    runCatching {
        forEachObject { node ->
            for ((key, value) in node) {
                if (key !in PRODUCT_RENDERERS) continue
                val tally = tallies.getOrPut(key) { ProductCardTally() }
                tally.found++
                val card = value.objectOrNull()
                when {
                    card == null || card.firstText(TITLE_KEYS) == null -> tally.noTitle++
                    card.productUrl() == null -> tally.noLink++
                    else -> tally.parsed++
                }
            }
        }
    }
    return tallies
}

private fun JsonElement.countRedirectLinks(): Int {
    var count = 0
    runCatching {
        forEachObject { node ->
            node.values.forEach { v ->
                if (v is JsonPrimitive && v.isString && v.content.contains("youtube.com/redirect")) count++
            }
        }
    }
    return count
}

/** A short plain-text report, written next to the raw JSON by the debug dump and safe to paste into an issue. */
internal fun JsonElement.describeProductScan(label: String): String =
    buildString {
        appendLine("Product scan: $label")
        val tallies = tallyProductCards()
        if (tallies.isEmpty()) {
            appendLine("Known product renderers found: none")
        } else {
            appendLine("Known product renderers found:")
            tallies.forEach { (name, t) ->
                appendLine("  $name found=${t.found} parsed=${t.parsed} droppedNoTitle=${t.noTitle} droppedNoLink=${t.noLink}")
            }
        }
        val hits = scanProductKeys()
        if (hits.isEmpty()) {
            appendLine("Keys matching product/merchandise/shopping/shelf/storefront: none")
        } else {
            appendLine("Keys matching product/merchandise/shopping/shelf/storefront:")
            hits.forEach { appendLine("  ${it.shape} x${it.count}") }
        }
        appendLine("youtube.com/redirect links in response: ${countRedirectLinks()}")
    }
