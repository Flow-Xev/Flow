package app.zaid.flow.ui.components.shorts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.ui.components.videoplayer.controls.PlayerPillIconButton
import app.zaid.flow.ui.theme.PlayerScrimContent

private val TopBarHorizontalPadding = 16.dp
private val BackButtonSize = 40.dp
private val BackIconSize = 24.dp
private const val TITLE_FADE_IN_MS = 220
private const val TITLE_FADE_OUT_MS = 90

/**
 * The strip over the top of the reel: a back pill when the queue was opened from somewhere, the
 * tab's own title when it is the Shorts tab, which [showTitle] keeps hidden while a reel plays
 * (it fades, so it never pops), and a search pill at the right edge. Deliberately not a [androidx.compose.material3.TopAppBar]:
 * it rests on video, so it takes the player's scrim tokens rather than a surface colour.
 */
@Composable
internal fun ShortsTopBar(
    visible: Boolean,
    showBackButton: Boolean,
    showTitle: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = TopBarHorizontalPadding)
                // Fixed height so the title never moves when other controls appear or vanish.
                .height(BackButtonSize),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (showBackButton) {
                PlayerPillIconButton(
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.btn_back),
                    buttonSize = BackButtonSize,
                    iconSize = BackIconSize,
                )
            } else {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTitle,
                    enter = fadeIn(tween(TITLE_FADE_IN_MS)),
                    exit = fadeOut(tween(TITLE_FADE_OUT_MS)),
                ) {
                    Text(
                        text = stringResource(R.string.shorts),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = PlayerScrimContent,
                    )
                }
            }
        }
        PlayerPillIconButton(
            onClick = onSearch,
            icon = Icons.Rounded.Search,
            contentDescription = stringResource(R.string.action_search),
            buttonSize = BackButtonSize,
            iconSize = BackIconSize,
        )
    }
}
