package app.zaid.flow.ui.components.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import app.zaid.flow.R

private val MenuWidth = 236.dp
private val MenuLabelHorizontalPadding = 16.dp
private val MenuLabelVerticalPadding = 8.dp

/**
 * How much room the control takes.
 *
 * [Compact] is the extra-small Material 3 size, for a row that already carries an avatar, a channel
 * name and a subscriber count beside it.
 */
enum class FlowSubscribeButtonSize {
    Default,
    Compact,

    /**
     * Stretches the control, subscribed or not, to the width it is given, for a row that shares its
     * space with another action. It is also the tallest size, for the channel page.
     */
    Wide,

    /** A slim 32dp pill, for rows where the button should never outweigh the channel name. */
    Mini,

    /** Smaller than YouTube's own Shorts subscribe pill (30dp tall, 12sp label), for the reel overlay. */
    Shorts,
}

/**
 * The one subscribe control, shared by the channel page, the player, the subscription manager and
 * search.
 *
 * Tapping while subscribed opens the menu rather than unsubscribing outright, so the destructive
 * action always needs a second, deliberate tap. A control that stands for several channels passes
 * [onSubscribedClick] instead: it then reads "Subscribed" with no bell or menu, since neither can
 * say which channel they act on.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowSubscribeButton(
    isSubscribed: Boolean,
    onSubscribeClick: () -> Unit,
    modifier: Modifier = Modifier,
    isNotificationsEnabled: Boolean = false,
    onUnsubscribeClick: () -> Unit = {},
    onNotificationChange: ((Boolean) -> Unit)? = null,
    areShortsExcluded: Boolean? = null,
    onShortsExcludeChange: (Boolean) -> Unit = {},
    onManageGroups: (() -> Unit)? = null,
    onAddNote: (() -> Unit)? = null,
    size: FlowSubscribeButtonSize = FlowSubscribeButtonSize.Default,
    tint: MediaArtworkTint? = null,
    onSubscribedClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val scaleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val metrics =
        when (size) {
            FlowSubscribeButtonSize.Compact -> PillMetrics(height = 34.dp, horizontalPadding = 16.dp, icon = 18.dp)
            FlowSubscribeButtonSize.Mini -> PillMetrics(height = 32.dp, horizontalPadding = 14.dp, icon = 16.dp)
            FlowSubscribeButtonSize.Shorts -> PillMetrics(height = 24.dp, horizontalPadding = 10.dp, icon = 14.dp, textSize = 11.sp, tight = true, doneHeight = 24.dp, doneTextSize = 11.sp)
            FlowSubscribeButtonSize.Wide -> PillMetrics(height = 46.dp, horizontalPadding = 24.dp, icon = 20.dp)
            FlowSubscribeButtonSize.Default -> PillMetrics(height = 42.dp, horizontalPadding = 20.dp, icon = 20.dp)
        }
    val fill = size == FlowSubscribeButtonSize.Wide

    // On an artwork-tinted card the theme's own primary lands as an unrelated colour, and its
    // contrast is against the theme surface rather than against the tint the card actually drew.
    // Filling with the ink the tint already clamped to 4.5:1 stays readable whatever the avatar is.
    val subscribeContainer = tint?.onContainer ?: MaterialTheme.colorScheme.onSurface
    val subscribeContent = tint?.container ?: MaterialTheme.colorScheme.surface
    val subscribedContainer = tint?.raised ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val subscribedContent = tint?.onContainer ?: MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier) {
        // Subscribing morphs the control into its subscribed form rather than swapping it: the
        // labels cross-fade and scale, so the change reads as an outcome of the tap and not a flicker.
        AnimatedContent(
            targetState = isSubscribed,
            transitionSpec = {
                (fadeIn(fadeSpec) + scaleIn(scaleSpec, initialScale = 0.92f)) togetherWith
                    (fadeOut(fadeSpec) + scaleOut(scaleSpec, targetScale = 0.92f))
            },
            contentAlignment = Alignment.CenterStart,
            label = "subscribeState",
        ) { subscribed ->
            if (!subscribed) {
                SubscribePill(
                    metrics = metrics,
                    fill = fill,
                    container = subscribeContainer,
                    content = subscribeContent,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSubscribeClick()
                    },
                )
            } else {
                SubscribedPill(
                    metrics = metrics,
                    fill = fill,
                    container = subscribedContainer,
                    content = subscribedContent,
                    // A control that stands for several channels has no bell or menu to offer.
                    showControls = onSubscribedClick == null,
                    isNotificationsEnabled = isNotificationsEnabled,
                    onBellClick = {
                        haptics.performHapticFeedback(
                            if (isNotificationsEnabled) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        onNotificationChange?.invoke(!isNotificationsEnabled)
                    },
                    onLabelClick = { onSubscribedClick?.invoke() ?: run { menuExpanded = true } },
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.width(MenuWidth),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (onNotificationChange != null) {
                    Text(
                        text = stringResource(R.string.notifications),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MenuTogglePill(
                            label = stringResource(R.string.on),
                            icon = Icons.Rounded.NotificationsActive,
                            selected = isNotificationsEnabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            onNotificationChange(true)
                            menuExpanded = false
                        }
                        MenuTogglePill(
                            label = stringResource(R.string.off),
                            icon = Icons.Rounded.NotificationsOff,
                            selected = !isNotificationsEnabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onNotificationChange(false)
                            menuExpanded = false
                        }
                    }
                }
                if (areShortsExcluded != null) {
                    MenuPill(
                        text = stringResource(if (areShortsExcluded) R.string.show_channel_shorts else R.string.hide_channel_shorts),
                        icon = if (areShortsExcluded) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    ) {
                        onShortsExcludeChange(!areShortsExcluded)
                        menuExpanded = false
                    }
                }
                if (onAddNote != null) {
                    MenuPill(stringResource(R.string.note_add), Icons.Outlined.StickyNote2) {
                        onAddNote()
                        menuExpanded = false
                    }
                }
                if (onManageGroups != null) {
                    MenuPill(stringResource(R.string.channel_add_to_group), Icons.Rounded.Folder) {
                        onManageGroups()
                        menuExpanded = false
                    }
                }
                // The one destructive entry reads as such, so it is never the one tapped by habit.
                MenuPill(
                    text = stringResource(R.string.unsubscribe),
                    icon = Icons.Rounded.PersonRemove,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                    onUnsubscribeClick()
                    menuExpanded = false
                }
            }
        }
    }
}

private data class PillMetrics(
    val height: Dp,
    val horizontalPadding: Dp,
    val icon: Dp,
    val textSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    /** Shorts overlay: the subscribed pill drops the chevron and tightens its padding. */
    val tight: Boolean = false,
    /** Shorts: the settled pill is thinner than the call to action and says just "Subscribed". */
    val doneHeight: Dp? = null,
    val doneTextSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
)

/** The call to action: a solid, high-contrast pill in the inverse of the page, with no icon to dilute it. */
@Composable
private fun SubscribePill(
    metrics: PillMetrics,
    fill: Boolean,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            (if (fill) Modifier.fillMaxWidth() else Modifier)
                .height(metrics.height)
                .pressScale(interaction, pressedScale = 0.96f)
                .clip(CircleShape)
                .background(container)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .padding(horizontal = metrics.horizontalPadding),
    ) {
        Text(
            text = stringResource(R.string.subscribe),
            color = content,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, fontSize = metrics.textSize),
            maxLines = 1,
        )
    }
}

/**
 * The settled state: one quiet pill in two tap zones. The bell toggles notifications in place; the
 * label and chevron open the menu, so the destructive action still needs a second, deliberate tap.
 */
@Composable
private fun SubscribedPill(
    metrics: PillMetrics,
    fill: Boolean,
    container: Color,
    content: Color,
    showControls: Boolean,
    isNotificationsEnabled: Boolean,
    onBellClick: () -> Unit,
    onLabelClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            (if (fill) Modifier.fillMaxWidth() else Modifier)
                .height(metrics.doneHeight ?: metrics.height)
                .clip(CircleShape)
                .background(container),
    ) {
        if (showControls && !metrics.tight) {
            val bellDescription = stringResource(if (isNotificationsEnabled) R.string.off else R.string.on)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .clickable(role = Role.Button, onClickLabel = bellDescription, onClick = onBellClick)
                        .padding(start = if (metrics.tight) 10.dp else metrics.horizontalPadding - 2.dp, end = if (metrics.tight) 7.dp else 12.dp),
            ) {
                Icon(
                    imageVector =
                        if (isNotificationsEnabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                    contentDescription = null,
                    tint = content,
                    // The bell rings when notifications switch on.
                    modifier = Modifier.likePop(isNotificationsEnabled, tiltDegrees = 24f).size(metrics.icon),
                )
            }
            Box(
                modifier =
                    Modifier
                        .width(1.dp)
                        .height(metrics.height * 0.42f)
                        .background(content.copy(alpha = 0.16f)),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            modifier =
                (if (fill) Modifier.weight(1f) else Modifier)
                    .fillMaxHeight()
                    .clickable(role = Role.Button, onClick = onLabelClick)
                    .padding(
                        start = if (metrics.tight) 10.dp else if (showControls) 12.dp else metrics.horizontalPadding,
                        end = if (metrics.tight) 10.dp else if (showControls) 14.dp else metrics.horizontalPadding,
                    ),
        ) {
            Text(
                text = stringResource(R.string.subscribed),
                color = content,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = metrics.doneTextSize),
                maxLines = 1,
            )
            if (showControls && !metrics.tight) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(metrics.icon),
                )
            }
        }
    }
}

@Composable
private fun MenuPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = CircleShape, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun MenuTogglePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "pillContainer",
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
