package com.apvcode.devx.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apvcode.devx.data.parseBlocks
import com.apvcode.devx.model.Block
import com.apvcode.devx.remote.RemoteMedia
import com.apvcode.devx.remote.RemoteSession
import com.apvcode.devx.ui.chat.BuildingCard
import com.apvcode.devx.ui.chat.ChartBlock
import com.apvcode.devx.ui.chat.CodeView
import com.apvcode.devx.ui.chat.GenerativeCard
import com.apvcode.devx.ui.chat.ImageBlock
import com.apvcode.devx.ui.chat.LocalImageLoader
import com.apvcode.devx.ui.chat.MapBlock
import com.apvcode.devx.ui.chat.RepliesBlock
import com.apvcode.devx.ui.chat.RichBlock
import com.apvcode.devx.ui.chat.SvgView
import com.apvcode.devx.ui.markdown.MarkdownText
import kotlinx.coroutines.launch

/**
 * An answer of the agent on the computer, with what the chat of this phone can draw: maps and routes, pictures (of the
 * computer: "pc:/path"), charts, cards, SVG and quick answers, each where the agent wrote it; the rest is text.
 */
@Composable
fun RemoteAssistantText(text: String, animate: Boolean, session: RemoteSession) {
    val blocks = remember(text) { parseBlocks(text) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader: suspend (String) -> Any? = { src ->
        val path = when {
            src.startsWith("pc:") -> src.removePrefix("pc:")
            src.startsWith("file:") -> src.removePrefix("file:")
            else -> null
        }
        if (path != null) RemoteMedia.fetchPath(context, session, path.trim()) else null
    }
    val send: (String) -> Unit = { words -> scope.launch { runCatching { session.send(words) } } }
    CompositionLocalProvider(LocalImageLoader provides loader) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is Block.Text -> SelectionContainer { MarkdownText(block.markdown, animate = animate) }
                    is Block.Code -> CodeView(block.language, block.code)
                    is Block.Svg -> SvgView(block.caption, block.svg)
                    is Block.Ui -> GenerativeCard(block.json, send)
                    is Block.Map -> MapBlock(block.json)
                    is Block.Image -> ImageBlock(block.json)
                    is Block.Chart -> ChartBlock(block.json)
                    is Block.Replies -> RepliesBlock(block.json, send)
                    is Block.Rich -> RichBlock(block)
                    Block.Building -> BuildingCard()
                    // Files and approvals of the chat on the phone do not come from a computer.
                    is Block.File, is Block.Approval -> Unit
                }
            }
        }
    }
}
