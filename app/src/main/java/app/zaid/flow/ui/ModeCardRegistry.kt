package app.zaid.flow.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

enum class ModeSide { Video, Music }

/** A card currently on screen: its artwork, where it sits in the window, and its corner radius. */
class ModeCard(
    val key: String,
    val url: String,
    val rect: Rect,
    val cornerDp: Float,
)

/**
 * Cards that exist on screen right now, per mode. The mode switch reads this to animate the real
 * thumbnails of the screen being left into the positions of the cards on the screen being entered.
 * Only touched from the main thread (layout callbacks and the transition), so plain maps are enough.
 */
object ModeCardRegistry {
    private val cards = mapOf(ModeSide.Video to LinkedHashMap<String, ModeCard>(), ModeSide.Music to LinkedHashMap<String, ModeCard>())

    internal fun put(side: ModeSide, card: ModeCard) {
        cards.getValue(side)[card.key] = card
    }

    internal fun remove(side: ModeSide, key: String) {
        cards.getValue(side).remove(key)
    }

    /** Visible cards of [side] in reading order (top to bottom, then left to right). */
    fun visible(side: ModeSide, windowWidth: Float, windowHeight: Float, limit: Int): List<ModeCard> =
        cards
            .getValue(side)
            .values
            .filter { it.rect.width > 8f && it.rect.height > 8f && it.rect.right > 0f && it.rect.left < windowWidth && it.rect.bottom > 0f && it.rect.top < windowHeight }
            .sortedWith(compareBy({ (it.rect.top / 24f).toInt() }, { it.rect.left }))
            .take(limit)
}

/**
 * Registers this element as a card of [side] for the mode-switch animation. [aspect] > 0 limits the
 * registered rectangle to the top `width * aspect` (a video card's thumbnail, without its title).
 */
fun Modifier.modeCard(
    side: ModeSide,
    key: String,
    url: String,
    cornerDp: Float,
    aspect: Float = 0f,
): Modifier =
    composed {
        val id = remember(key) { "${side.name}:$key" }
        DisposableEffect(id) { onDispose { ModeCardRegistry.remove(side, id) } }
        onGloballyPositioned { coords ->
            if (!coords.isAttached || url.isBlank()) return@onGloballyPositioned
            val b = coords.boundsInRoot()
            val rect = if (aspect > 0f) Rect(b.left, b.top, b.right, b.top + b.width * aspect) else b
            ModeCardRegistry.put(side, ModeCard(id, url, rect, cornerDp))
        }
    }
