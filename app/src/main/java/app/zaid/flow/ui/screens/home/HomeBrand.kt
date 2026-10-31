package app.zaid.flow.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zaid.flow.R

private val LogoSize = 28.dp

/**
 * Home's left side, the way YouTube's is: the app mark followed by a tight, heavy wordmark in
 * ordinary case. Long-pressing either one switches Deep Flow.
 */
@Composable
internal fun HomeBrand(
    showLogo: Boolean,
    isDeepFlowActive: Boolean,
    iconSuffix: String?,
    deepFlowLabel: String,
    onToggleDeepFlow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .deepFlowLongPress(deepFlowLabel, onToggleDeepFlow)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showLogo) {
            FlowHeaderLogoIcon(
                isDeepFlowActive = isDeepFlowActive,
                iconSuffix = iconSuffix,
                size = LogoSize,
            )
        }
        Text(
            text = stringResource(R.string.app_name),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                ),
            maxLines = 1,
        )
    }
}
