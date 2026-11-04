package app.zaid.flow.ui.components.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.zaid.flow.ai.MdBlock
import app.zaid.flow.ai.MdSpan
import app.zaid.flow.ai.parseAiMarkdown

private class AiMarkdownColors(
    val codeBackground: Color,
    val codeContent: Color,
    val link: Color,
    val timeBackground: Color,
    val timeContent: Color,
)

/**
 * An assistant answer with its markdown shown: bold, italics, lists, headings, code, links, and
 * timestamps that jump the player ([onSeek], null when nothing is playing). The text is selectable,
 * so any part of an answer can be highlighted and copied. Colours come from the theme only.
 */
@Composable
internal fun AiMarkdownText(
    text: String,
    onSeek: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseAiMarkdown(text) }
    val scheme = MaterialTheme.colorScheme
    val colors =
        AiMarkdownColors(
            codeBackground = scheme.surfaceContainerHighest,
            codeContent = scheme.onSurface,
            link = scheme.primary,
            timeBackground = scheme.primaryContainer,
            timeContent = scheme.onPrimaryContainer,
        )
    val typography = MaterialTheme.typography
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Paragraph -> {
                        Text(block.spans.toAnnotated(colors, onSeek), style = typography.bodyLarge)
                    }

                    is MdBlock.Heading -> {
                        Text(
                            block.spans.toAnnotated(colors, onSeek),
                            style =
                                (
                                    if (block.level <=
                                        2
                                    ) {
                                        typography.titleMedium
                                    } else {
                                        typography.titleSmall
                                    }
                                ).copy(fontWeight = FontWeight.Bold),
                        )
                    }

                    is MdBlock.Bullet -> {
                        ListRow(marker = "\u2022", indent = block.indent) {
                            Text(block.spans.toAnnotated(colors, onSeek), style = typography.bodyLarge)
                        }
                    }

                    is MdBlock.Numbered -> {
                        ListRow(marker = "${block.number}.", indent = block.indent) {
                            Text(block.spans.toAnnotated(colors, onSeek), style = typography.bodyLarge)
                        }
                    }

                    is MdBlock.Code -> {
                        Surface(shape = MaterialTheme.shapes.small, color = colors.codeBackground, contentColor = colors.codeContent) {
                            Text(
                                text = block.text,
                                style = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(
    marker: String,
    indent: Int,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.padding(start = (indent.coerceIn(0, 4) * 16).dp)) {
        Text(text = marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(22.dp))
        content()
    }
}

private fun List<MdSpan>.toAnnotated(
    colors: AiMarkdownColors,
    onSeek: ((Long) -> Unit)?,
): AnnotatedString =
    buildAnnotatedString {
        for (span in this@toAnnotated) {
            val link = span.link
            val moment = span.timestampMs
            when {
                span.code -> {
                    withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground, color = colors.codeContent),
                    ) {
                        append(span.text)
                    }
                }

                link != null -> {
                    val styles = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
                    withLink(LinkAnnotation.Url(link, styles)) { append(span.text) }
                }

                moment != null && onSeek != null -> {
                    val styles =
                        TextLinkStyles(
                            SpanStyle(color = colors.timeContent, background = colors.timeBackground, fontWeight = FontWeight.SemiBold),
                        )
                    withLink(LinkAnnotation.Clickable("ai-moment", styles) { onSeek(moment) }) { append(span.text) }
                }

                else -> {
                    withStyle(
                        SpanStyle(
                            fontWeight = if (span.bold) FontWeight.Bold else null,
                            fontStyle = if (span.italic) FontStyle.Italic else null,
                        ),
                    ) { append(span.text) }
                }
            }
        }
    }
