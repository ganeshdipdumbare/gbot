package dev.gbot.app.ui.chat

import android.util.Log
import androidx.a2ui.compose.runtime.A2uiComponentReference
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.runtime.LocalA2uiReadinessEvaluator
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.asReadinessEvaluator
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.AccessibilityAttributes
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1.CheckRule
import androidx.a2ui.model.catalog.basiccatalog.createBasicCatalogFunctions
import androidx.a2ui.model.catalog.functions.A2uiMessageFormatter
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import androidx.a2ui.model.processor.processInput
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gbot.app.GbotApp
import dev.gbot.app.data.keyOf
import dev.gbot.app.local.LocalBrain
import dev.gbot.app.local.LocalResults
import dev.gbot.app.local.Surfaces
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Message
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.Pictures
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.pressable
import dev.gbot.app.ui.shimmer
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * A2UI surfaces in the chat. The bot's show_ui call (checked by
 * local/Surfaces.kt) is drawn here by Google's A2UI Compose renderer from
 * our own catalog: the basic catalog's names and properties, a subset of its
 * parts, drawn in gbot's own style. Pictures come only from files kept on the
 * phone (or the server's blobs) that the surface lists; links open only from a
 * tap and only over https.
 */

/** Whether this surface's buttons still answer the bot. */
private val LocalLive = compositionLocalOf { false }

/** The kept pictures this surface may draw. */
private val LocalFiles = compositionLocalOf { emptySet<String>() }

/** The ink a Text inside a button takes. */
private val LocalInk = compositionLocalOf<Color?> { null }

/** The cache key prefix for this surface's pictures. */
private val LocalSurfaceKey = compositionLocalOf { "" }

/**
 * A row in the chat for a surface: shown by the bot (an event), waiting for
 * the person's tap (a card), or an older show_results list drawn the same way.
 */
@Composable
fun SurfaceRow(m: Message, author: Bot?) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    val spec = remember(m.id, m.data) { specOf(m) } ?: return
    val isCard = m.role == "card"
    val status = m.str("status").orEmpty()
    val live = isCard && status == "pending"
    var busy by remember(m.id) { mutableStateOf(false) }
    var error by remember(m.id) { mutableStateOf("") }

    fun answer(text: String?) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try {
                val upd = app.api.answer(m.id, "answer", text, null)
                app.store.patchMessage(keyOf(m), m.id) { upd }
                app.alerts.answered(m.id, keyOf(m), "")
            } catch (e: Exception) {
                error = e.message ?: "Couldn't answer"
            } finally { busy = false }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(40.dp)) { if (author != null) Gbot(author.look, author.id, 34) }
        Column(Modifier.widthIn(max = 340.dp)) {
            val title = m.str("title") ?: if (isCard) "" else m.content
            if (title.isNotEmpty()) Text(title, style = T.tiny, color = g.ink3, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
            SurfaceView(
                id = m.id, spec = spec, live = live && !busy,
                onEvent = { name, context ->
                    answer(buildJsonObject { put("event", name); put("context", context) }.toString())
                },
            )
            if (isCard) {
                when {
                    live -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        GButton("Skip", { answer(null) }, enabled = !busy, small = true, variant = Variant.Ghost)
                    }
                    else -> Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val chose = chosen(m.str("answer"))
                        Icon(if (chose != null) "check" else "close", size = 12.dp, color = if (chose != null) g.success else g.ink3)
                        Spacer(Modifier.width(4.dp))
                        Text(if (chose != null) "You chose “$chose”" else "Skipped", style = T.tiny, color = g.ink2)
                    }
                }
                if (error.isNotEmpty()) Text(error, style = T.tiny, color = g.danger, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
        }
    }
}

/** A surface's components and data: a show_ui surface, or an older results list as cards. */
internal fun specOf(m: Message): JsonObject? {
    (m.data?.get("surface") as? JsonObject)?.let { return it }
    val items = (m.data?.get("items") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
    return Surfaces.fromItems(items)
}

private fun chosen(answer: String?): String? {
    if (answer.isNullOrBlank()) return null
    val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(answer) as? JsonObject }.getOrNull() ?: return answer
    return (o["event"] as? JsonPrimitive)?.contentOrNull
}

/**
 * One A2UI surface, drawn by the A2UI renderer from [spec]. Events from
 * buttons go to [onEvent] while [live]; a link opens only if the surface
 * itself named it.
 */
@Composable
fun SurfaceView(id: String, spec: JsonObject, live: Boolean, onEvent: (String, JsonObject) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val uri = LocalUriHandler.current
    val openUri by rememberUpdatedState(uri)
    val event by rememberUpdatedState(onEvent)
    val links = remember(spec) { linksIn(spec) }
    val catalog = remember(spec) { phoneCatalog { url -> if (url in links && LocalResults.isHttps(url)) runCatching { openUri.openUri(url) } } }
    val processor = remember(id, spec) {
        A2uiMessageProcessor(listOf(catalog)).also { p ->
            val parser = A2uiMessageParser()
            Surfaces.messages(id, spec).forEach { p.processInput(parser, it) }
        }
    }
    LaunchedEffect(processor) { processor.collectMessages() }
    LaunchedEffect(processor) {
        processor.outboundEvents.collect { e ->
            when (e) {
                is A2uiClientEventMessage -> event(e.type, LocalBrain.jsonOf(e.context) as? JsonObject ?: JsonObject(emptyMap()))
                is A2uiClientErrorMessage -> Log.w("gbot-ui", "surface $id: ${e.code} ${e.message}")
                else -> Unit
            }
        }
    }
    val surfaces by processor.activeSurfaces.collectAsState()
    val surface = surfaces.firstOrNull()
    val readiness = remember(catalog) { catalog.asReadinessEvaluator() }
    if (surface == null) {
        Box(modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).shimmer())
        return
    }
    CompositionLocalProvider(
        LocalA2uiReadinessEvaluator provides readiness,
        LocalLive provides live,
        LocalFiles provides Surfaces.files(spec),
        LocalSurfaceKey provides id,
    ) {
        when (val st = observeA2uiComponentState(surface)) {
            is A2uiComponentState.Success -> A2uiComponent(st.component, modifier)
            is A2uiComponentState.Error -> Text("This part couldn't be drawn.", style = T.small, color = g.ink3, modifier = modifier.padding(4.dp))
            A2uiComponentState.Loading -> Box(modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).shimmer())
        }
    }
}

/** The https links a surface's buttons may open. */
private fun linksIn(spec: JsonObject): Set<String> =
    (spec["components"] as? JsonArray).orEmpty().mapNotNull { c ->
        val call = ((c as? JsonObject)?.get("action") as? JsonObject)?.get("functionCall") as? JsonObject ?: return@mapNotNull null
        ((call["args"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
    }.toSet()

/** Our catalog: the parts Surfaces lets through, and the basic functions with a guarded openUrl. */
private fun phoneCatalog(open: (String) -> Unit): A2uiCatalog = A2uiCatalog(
    catalogId = Surfaces.CATALOG_ID,
    components = listOf(PText, PImage, PRow, PColumn, PList, PCard, PDivider, PButton, PTextField, PCheckBox, PChoicePicker, PSlider),
    functions = createBasicCatalogFunctions(
        urlOpener = A2uiUrlOpener { open(it) },
        messageFormatter = A2uiMessageFormatter { pattern, locale, args -> android.icu.text.MessageFormat(pattern, locale).format(args) },
    ),
)

/** A child part by reference, with a weight when its parent is a Row or Column. */
@Composable
private fun A2uiComponentScope.Child(ref: A2uiComponentReference, modifier: Modifier = Modifier) {
    when (val st = observeA2uiComponentState(ref)) {
        is A2uiComponentState.Success -> A2uiComponent(st.component, modifier)
        is A2uiComponentState.Error -> Unit
        A2uiComponentState.Loading -> Unit
    }
}

@Composable
private fun A2uiComponentScope.weightOf(ref: A2uiComponentReference): Float? =
    (observeA2uiComponentState(ref) as? A2uiComponentState.Success)?.component?.properties
        ?.let { runCatching { it[A2uiBasicCatalogV1.WeightProperty] }.getOrNull() }?.toFloat()?.takeIf { it > 0f }

private fun Modifier.described(a: AccessibilityAttributes?): Modifier =
    a?.label?.let { l -> semantics { contentDescription = l } } ?: this

private object PText : A2uiBasicCatalogV1.Text {
    @Composable
    override fun A2uiComponentScope.TypedContent(text: String, variant: A2uiBasicCatalogV1.Text.Variant, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        val g = LocalG.current
        val ink = LocalInk.current
        val style = when (variant) {
            A2uiBasicCatalogV1.Text.Variant.H1 -> T.h2.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)
            A2uiBasicCatalogV1.Text.Variant.H2 -> T.h2.copy(fontSize = 19.sp)
            A2uiBasicCatalogV1.Text.Variant.H3 -> T.h2
            A2uiBasicCatalogV1.Text.Variant.H4 -> T.body.copy(fontWeight = FontWeight.SemiBold)
            A2uiBasicCatalogV1.Text.Variant.H5 -> T.body.copy(fontWeight = FontWeight.Medium)
            A2uiBasicCatalogV1.Text.Variant.Caption -> T.small
            else -> T.body
        }
        val color = ink ?: if (variant == A2uiBasicCatalogV1.Text.Variant.Caption) g.ink2 else g.ink
        Text(text, style = style, color = color, modifier = modifier.described(accessibility), maxLines = if (ink != null) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}

private object PImage : A2uiBasicCatalogV1.Image {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        url: String, description: String?, fit: A2uiBasicCatalogV1.Image.Fit, variant: A2uiBasicCatalogV1.Image.Variant,
        accessibility: AccessibilityAttributes?, modifier: Modifier,
    ) {
        val g = LocalG.current
        val files = LocalFiles.current
        // Only a picture kept for this surface, or a server blob; never a site.
        val file = url.removePrefix(Surfaces.FILE_SCHEME).takeIf { url.startsWith(Surfaces.FILE_SCHEME) && it in files }
        val blob = url.removePrefix(Surfaces.BLOB_SCHEME).takeIf { url.startsWith(Surfaces.BLOB_SCHEME) && it.isNotEmpty() }
        val key = LocalSurfaceKey.current + "#" + url
        var img by remember(key) { mutableStateOf<ImageBitmap?>(null) }
        var missing by remember(key) { mutableStateOf(false) }
        LaunchedEffect(key) {
            img = if (file != null || blob != null) Pictures.load(key, file, blob) else null
            missing = img == null
        }
        val box = when (variant) {
            A2uiBasicCatalogV1.Image.Variant.Icon -> Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
            A2uiBasicCatalogV1.Image.Variant.Avatar -> Modifier.size(40.dp).clip(CircleShape)
            A2uiBasicCatalogV1.Image.Variant.SmallFeature -> Modifier.size(84.dp).clip(RoundedCornerShape(10.dp))
            A2uiBasicCatalogV1.Image.Variant.LargeFeature -> Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp))
            A2uiBasicCatalogV1.Image.Variant.Header -> Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
            else -> Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
        }
        val scale = when (fit) {
            A2uiBasicCatalogV1.Image.Fit.Contain -> ContentScale.Fit
            A2uiBasicCatalogV1.Image.Fit.Fill -> ContentScale.FillBounds
            A2uiBasicCatalogV1.Image.Fit.None -> ContentScale.None
            A2uiBasicCatalogV1.Image.Fit.ScaleDown -> ContentScale.Inside
            else -> ContentScale.Crop
        }
        Box(modifier.then(box).background(g.surface2)) {
            val pic = img
            when {
                pic != null -> Image(pic, description ?: accessibility?.label, Modifier.fillMaxSize(), contentScale = scale)
                !missing -> Box(Modifier.fillMaxSize().shimmer())
            }
        }
    }
}

private fun hArrange(j: A2uiBasicCatalogV1.Row.Justify): Arrangement.Horizontal {
    return when (j) {
        A2uiBasicCatalogV1.Row.Justify.Center -> Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        A2uiBasicCatalogV1.Row.Justify.End -> Arrangement.spacedBy(8.dp, Alignment.End)
        A2uiBasicCatalogV1.Row.Justify.SpaceBetween -> Arrangement.SpaceBetween
        A2uiBasicCatalogV1.Row.Justify.SpaceAround -> Arrangement.SpaceAround
        A2uiBasicCatalogV1.Row.Justify.SpaceEvenly -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(8.dp)
    }
}

private object PRow : A2uiBasicCatalogV1.Row {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, justify: A2uiBasicCatalogV1.Row.Justify, align: A2uiBasicCatalogV1.Row.Align,
        accessibility: AccessibilityAttributes?, modifier: Modifier,
    ) {
        val v = when (align) { A2uiBasicCatalogV1.Row.Align.Start -> Alignment.Top; A2uiBasicCatalogV1.Row.Align.End -> Alignment.Bottom; A2uiBasicCatalogV1.Row.Align.Center -> Alignment.CenterVertically; else -> Alignment.Top }
        Row(modifier.fillMaxWidth().described(accessibility), horizontalArrangement = hArrange(justify), verticalAlignment = v) {
            for (c in children) {
                val w = weightOf(c)
                Child(c, if (w != null) Modifier.weight(w) else Modifier)
            }
        }
    }
}

private object PColumn : A2uiBasicCatalogV1.Column {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, justify: A2uiBasicCatalogV1.Column.Justify, align: A2uiBasicCatalogV1.Column.Align,
        accessibility: AccessibilityAttributes?, modifier: Modifier,
    ) {
        val h = when (align) { A2uiBasicCatalogV1.Column.Align.Center -> Alignment.CenterHorizontally; A2uiBasicCatalogV1.Column.Align.End -> Alignment.End; else -> Alignment.Start }
        val arr = when (justify) {
            A2uiBasicCatalogV1.Column.Justify.Center -> Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
            A2uiBasicCatalogV1.Column.Justify.End -> Arrangement.spacedBy(6.dp, Alignment.Bottom)
            A2uiBasicCatalogV1.Column.Justify.SpaceBetween -> Arrangement.SpaceBetween
            A2uiBasicCatalogV1.Column.Justify.SpaceAround -> Arrangement.SpaceAround
            A2uiBasicCatalogV1.Column.Justify.SpaceEvenly -> Arrangement.SpaceEvenly
            else -> Arrangement.spacedBy(6.dp)
        }
        Column(modifier.described(accessibility), verticalArrangement = arr, horizontalAlignment = h) {
            for (c in children) {
                val w = weightOf(c)
                Child(c, if (w != null) Modifier.weight(w, fill = false) else Modifier)
            }
        }
    }
}

private object PList : A2uiBasicCatalogV1.List {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>, direction: A2uiBasicCatalogV1.List.Direction, align: A2uiBasicCatalogV1.List.Align,
        accessibility: AccessibilityAttributes?, modifier: Modifier,
    ) {
        // Inside the chat's own scrolling list: plain layouts, not lazy ones.
        if (direction == A2uiBasicCatalogV1.List.Direction.Horizontal) {
            Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).described(accessibility), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in children) Child(c, Modifier.widthIn(max = 260.dp))
            }
        } else {
            Column(modifier.fillMaxWidth().described(accessibility), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in children) Child(c)
            }
        }
    }
}

private object PCard : A2uiBasicCatalogV1.Card {
    @Composable
    override fun A2uiComponentScope.TypedContent(childId: String, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        val g = LocalG.current
        val shape = RoundedCornerShape(16.dp)
        Box(modifier.fillMaxWidth().clip(shape).background(g.surface).border(1.dp, g.line, shape).padding(10.dp).described(accessibility)) {
            Child(A2uiComponentReference(childId))
        }
    }
}

private object PDivider : A2uiBasicCatalogV1.Divider {
    @Composable
    override fun A2uiComponentScope.TypedContent(axis: A2uiBasicCatalogV1.Divider.Axis, accessibility: AccessibilityAttributes?, modifier: Modifier) {
        val g = LocalG.current
        if (axis == A2uiBasicCatalogV1.Divider.Axis.Vertical) Box(modifier.width(1.dp).height(24.dp).background(g.line))
        else Box(modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(g.line))
    }
}

private object PButton : A2uiBasicCatalogV1.Button {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String, variant: A2uiBasicCatalogV1.Button.Variant, action: Map<String, Any?>,
        accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        val g = LocalG.current
        // A link works on any surface; an answer only while the card waits.
        val answers = action.containsKey("event")
        val enabled = checks.all { it.condition } && (!answers || LocalLive.current)
        // A link always looks like a link and names its site, so it can't
        // pass for a button that answers the bot.
        val linkHost = if (answers) null else
            (((action["functionCall"] as? Map<*, *>)?.get("args") as? Map<*, *>)?.get("url") as? String)
                ?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }.orEmpty()
        val variant = if (answers) variant else A2uiBasicCatalogV1.Button.Variant.Borderless
        val (bg, fg) = when (variant) {
            A2uiBasicCatalogV1.Button.Variant.Primary -> g.emphasis to g.onEmphasis
            A2uiBasicCatalogV1.Button.Variant.Borderless -> Color.Transparent to g.accent
            else -> g.surface to g.ink
        }
        val shape = RoundedCornerShape(50)
        Row(
            modifier.heightIn(min = 36.dp).clip(shape).background(bg)
                .then(if (variant == A2uiBasicCatalogV1.Button.Variant.Secondary) Modifier.border(1.dp, g.line, shape) else Modifier)
                .pressable(enabled) { dispatchAction(action) }
                .padding(horizontal = if (variant == A2uiBasicCatalogV1.Button.Variant.Borderless) 4.dp else 14.dp)
                .described(accessibility),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            CompositionLocalProvider(LocalInk provides if (enabled) fg else fg.copy(alpha = 0.4f)) {
                Child(A2uiComponentReference(childId))
            }
            if (linkHost != null) {
                Text(" ›", style = T.body, color = fg)
                if (linkHost.isNotEmpty()) Text("  $linkHost", style = T.tiny, color = g.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        checks.firstOrNull { !it.condition }?.let { Text(it.message, style = T.tiny, color = g.ink3) }
    }
}

private object PTextField : A2uiBasicCatalogV1.TextField {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String, value: String?, variant: A2uiBasicCatalogV1.TextField.Variant, validationRegexp: String?,
        onValueChange: (String) -> Unit, enabled: Boolean, accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        val g = LocalG.current
        val text = value.orEmpty()
        val bad = validationRegexp != null && text.isNotEmpty() && runCatching { !Regex(validationRegexp).matches(text) }.getOrDefault(false)
        Column(modifier.fillMaxWidth().described(accessibility)) {
            Text(label, style = T.label, color = g.ink, modifier = Modifier.padding(bottom = 4.dp))
            Field(
                text, onValueChange, enabled = enabled && LocalLive.current,
                singleLine = variant != A2uiBasicCatalogV1.TextField.Variant.LongText, minLines = if (variant == A2uiBasicCatalogV1.TextField.Variant.LongText) 3 else 1,
                password = variant == A2uiBasicCatalogV1.TextField.Variant.Obscured, keyboard = if (variant == A2uiBasicCatalogV1.TextField.Variant.Number) KeyboardType.Number else KeyboardType.Text,
            )
            val msg = checks.firstOrNull { !it.condition }?.message ?: if (bad) "That doesn't look right." else null
            if (msg != null && text.isNotEmpty()) Text(msg, style = T.tiny, color = g.danger, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Tick(on: Boolean, enabled: Boolean, onClick: () -> Unit, label: String, round: Boolean = false) {
    val g = LocalG.current
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).pressable(enabled, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).clip(shape).background(if (on) g.emphasis else Color.Transparent).border(1.5.dp, if (on) g.emphasis else g.ink3, shape),
            contentAlignment = Alignment.Center,
        ) { if (on) Icon("check", size = 13.dp, color = g.onEmphasis) }
        Spacer(Modifier.width(10.dp))
        Text(label, style = T.body, color = if (enabled) g.ink else g.ink2)
    }
}

private object PCheckBox : A2uiBasicCatalogV1.CheckBox {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String, value: Boolean, onValueChange: (Boolean) -> Unit, enabled: Boolean,
        accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        Box(modifier.described(accessibility)) { Tick(value, enabled && LocalLive.current, { onValueChange(!value) }, label) }
    }
}

private object PChoicePicker : A2uiBasicCatalogV1.ChoicePicker {
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String?, options: List<A2uiBasicCatalogV1.ChoicePicker.Option>, value: List<String>,
        variant: A2uiBasicCatalogV1.ChoicePicker.Variant, displayStyle: A2uiBasicCatalogV1.ChoicePicker.DisplayStyle, filterable: Boolean,
        onValueChange: (List<String>) -> Unit, enabled: Boolean, accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        val g = LocalG.current
        val can = enabled && LocalLive.current
        val one = variant == A2uiBasicCatalogV1.ChoicePicker.Variant.MutuallyExclusive
        fun pick(v: String) = onValueChange(if (one) listOf(v) else if (v in value) value - v else value + v)
        Column(modifier.fillMaxWidth().described(accessibility)) {
            if (!label.isNullOrEmpty()) Text(label, style = T.label, color = g.ink, modifier = Modifier.padding(bottom = 4.dp))
            if (displayStyle == A2uiBasicCatalogV1.ChoicePicker.DisplayStyle.Chips) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in options) {
                        val on = o.value in value
                        Box(
                            Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (on) g.emphasis else g.surface)
                                .border(1.dp, if (on) Color.Transparent else g.line, RoundedCornerShape(50))
                                .pressable(can) { pick(o.value) }.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(o.label, color = if (on) g.onEmphasis else g.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                    }
                }
            } else {
                for (o in options) Tick(o.value in value, can, { pick(o.value) }, o.label, round = one)
            }
        }
    }
}

private object PSlider : A2uiBasicCatalogV1.Slider {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String?, min: Float, max: Float, value: Float, onValueChange: (Float) -> Unit, enabled: Boolean,
        accessibility: AccessibilityAttributes?, checks: List<CheckRule>, modifier: Modifier,
    ) {
        val g = LocalG.current
        Column(modifier.fillMaxWidth().described(accessibility)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!label.isNullOrEmpty()) Text(label, style = T.label, color = g.ink, modifier = Modifier.weight(1f))
                Text(if (value == value.toLong().toFloat()) value.toLong().toString() else "%.1f".format(value), style = T.small, color = g.ink2)
            }
            Slider(
                value = value.coerceIn(min, maxOf(min, max)), onValueChange = onValueChange, valueRange = min..maxOf(min, max),
                enabled = enabled && LocalLive.current,
                colors = SliderDefaults.colors(thumbColor = g.emphasis, activeTrackColor = g.emphasis, inactiveTrackColor = g.line),
            )
        }
    }
}
