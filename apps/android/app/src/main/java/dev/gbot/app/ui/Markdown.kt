package dev.gbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gbot.app.GbotApp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import dev.gbot.app.local.Links
import org.commonmark.node.Text as MdText

private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()

private fun Node.children(): List<Node> {
    val out = ArrayList<Node>()
    var c = firstChild
    while (c != null) { out += c; c = c.next }
    return out
}

/**
 * Bot output as Markdown, rendered natively. Like the web, it's treated as
 * untrusted: no HTML, no images, and links open in your browser.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = LocalG.current.ink, style: TextStyle = T.body, caret: Boolean = false) {
    val doc = remember(text) { parser.parse(text) }
    val blocks = doc.children()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { i, b -> Block(b, color, style, caret && i == blocks.lastIndex) }
        if (blocks.isEmpty() && caret) Text("▍", style = style, color = color)
    }
}

@Composable
private fun Block(n: Node, color: Color, style: TextStyle, caret: Boolean) {
    val g = LocalG.current
    when (n) {
        is Paragraph -> Inline(n, color, style, caret)
        is Heading -> Inline(n, color, style.copy(fontWeight = FontWeight.SemiBold, fontSize = when (n.level) { 1 -> 20.sp; 2 -> 18.sp; else -> 16.sp }, lineHeight = 26.sp), caret)
        is BulletList, is OrderedList -> {
            val ordered = n is OrderedList
            var num = (n as? OrderedList)?.markerStartNumber ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (item in n.children()) if (item is ListItem) {
                    val marker = if (ordered) "${num++}." else "•"
                    Row {
                        Text(marker, style = style, color = g.ink2, modifier = Modifier.width(if (ordered) 24.dp else 16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in item.children()) Block(c, color, style, false)
                        }
                    }
                }
            }
        }
        is BlockQuote -> {
            val line = g.line
            Column(
                Modifier.drawBehind { drawRect(line, Offset.Zero, androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { for (c in n.children()) Block(c, g.ink2, style, false) }
        }
        is FencedCodeBlock -> CodeBlock(n.literal.trimEnd('\n'))
        is IndentedCodeBlock -> CodeBlock(n.literal.trimEnd('\n'))
        is ThematicBreak -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(g.line).padding(top = 1.dp))
        is TableBlock -> Table(n, color, style)
        is HtmlBlock -> Text(n.literal.trim(), style = style, color = color)
        else -> Inline(n, color, style, caret)
    }
}

@Composable
private fun CodeBlock(code: String) {
    val g = LocalG.current
    val clip = LocalClipboardManager.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(g.bg).border(1.dp, g.line, RoundedCornerShape(10.dp))) {
        Text(
            code, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp), color = g.ink,
            softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 52.dp, top = 8.dp, bottom = 8.dp),
        )
        Text(
            "Copy", style = T.tiny, color = g.ink2,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(g.surface2)
                .tap { clip.setText(AnnotatedString(code)); GbotApp.instance.toast("Copied code") }.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun Table(n: TableBlock, color: Color, style: TextStyle) {
    val g = LocalG.current
    val rows = n.children().flatMap { section -> section.children() }.filterIsInstance<TableRow>()
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, g.line, RoundedCornerShape(8.dp))) {
        rows.forEachIndexed { ri, row ->
            Row(if (ri == 0) Modifier.background(g.surface) else Modifier) {
                for (cell in row.children().filterIsInstance<TableCell>()) {
                    Box(Modifier.widthIn(min = 64.dp, max = 220.dp).padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Inline(cell, color, style.copy(fontSize = 13.5.sp, fontWeight = if (cell.isHeader) FontWeight.SemiBold else null), false)
                    }
                }
            }
        }
    }
}

@Composable
private fun Inline(n: Node, color: Color, style: TextStyle, caret: Boolean) {
    val g = LocalG.current
    val s = remember(n, g, caret) {
        buildAnnotatedString {
            val linkStyle = TextLinkStyles(SpanStyle(color = g.accent, textDecoration = TextDecoration.Underline))
            var inLink = false
            fun walk(node: Node) {
                when (node) {
                    // A bare address in a sentence opens too, without the
                    // full stop or bracket written after it.
                    is MdText -> if (inLink) append(node.literal) else {
                        val text = node.literal
                        var at = 0
                        for (f in Links.bare(text)) {
                            append(text.substring(at, f.range.first))
                            withLink(LinkAnnotation.Url(f.url, linkStyle)) { append(f.label) }
                            at = f.range.last + 1
                        }
                        append(text.substring(at))
                    }
                    is SoftLineBreak, is HardLineBreak -> append("\n")
                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, background = g.surface2)) { append(" ${node.literal} ") }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { node.children().forEach(::walk) }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { node.children().forEach(::walk) }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { node.children().forEach(::walk) }
                    is Link -> {
                        // example.com or www.… without a scheme still opens.
                        val url = Links.normalize(node.destination)
                        inLink = true
                        if (url != null) withLink(LinkAnnotation.Url(url, linkStyle)) { node.children().forEach(::walk) }
                        else node.children().forEach(::walk)
                        inLink = false
                    }
                    is Image -> node.children().forEach(::walk)
                    is HtmlInline -> append(node.literal)
                    else -> node.children().forEach(::walk)
                }
            }
            n.children().forEach(::walk)
            if (caret) withStyle(SpanStyle(color = g.ink3)) { append(" ▍") }
        }
    }
    Text(s, style = style, color = color)
}
