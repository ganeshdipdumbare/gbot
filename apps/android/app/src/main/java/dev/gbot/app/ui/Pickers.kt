package dev.gbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gbot.app.net.Look
import dev.gbot.app.net.Model

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
private val eyeLabels = mapOf("dot" to "Dots", "pill" to "Tall", "wide" to "Wide")

/** Shape, color and eyes, as the web's LookPicker. */
@Composable
fun LookPicker(look: Look, seed: String, onChange: (Look) -> Unit) {
    val g = LocalG.current
    val t = resolveLook(look, seed)
    fun set(shape: String = t.shape, hue: String = t.hue, eyes: String = t.eyes) = onChange(Look(shape, hue, eyes, look.seed ?: seed))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Shape", style = T.tiny, color = g.ink3)
        PickRow(shapes.map { o -> { ChoiceBody(t.shape == o, { set(shape = o) }) { Gbot(Look(o, t.hue, t.eyes), seed, 36) } } })
        Text("Color", style = T.tiny, color = g.ink3)
        PickRow(hueNames.map { o -> {
            Box(Modifier.fillMaxWidth().widthIn(max = 48.dp).aspectRatio(1f).clip(CircleShape).background(if (t.hue == o) g.selected else Color.Transparent).border(if (t.hue == o) 1.dp else 0.dp, if (t.hue == o) g.ink3 else Color.Transparent, CircleShape).pressable { set(hue = o) }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize(.64f).clip(CircleShape).background(Color(hues.getValue(o).body)))
            }
        } })
        Text("Eyes", style = T.tiny, color = g.ink3)
        PickRow(eyeStyles.map { o -> { ChoiceBody(t.eyes == o, { set(eyes = o) }) { Gbot(Look(t.shape, t.hue, o), seed, 36) } } })
    }
}

/** Eight equal columns for every row, so shape, color and eyes share one left edge and pitch. */
@Composable
private fun PickRow(cells: List<@Composable () -> Unit>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(8) { i -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { cells.getOrNull(i)?.invoke() } }
    }
}

@Composable
private fun ChoiceBody(on: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val g = LocalG.current
    Box(
        Modifier.fillMaxWidth().widthIn(max = 48.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(if (on) g.selected else Color.Transparent)
            .border(1.dp, if (on) g.ink3 else Color.Transparent, RoundedCornerShape(12.dp)).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A random look: what "Surprise me" picks. */
fun surpriseLook(): Look {
    val s = randomSeed()
    val t = traitsFromSeed(s)
    return Look(t.shape, t.hue, t.eyes, s)
}

// ---- Models ----------------------------------------------------------

data class ModelItem(val id: String, val label: String, val sub: String?, val caps: List<String>, val local: Boolean?)

private data class Filter(val id: String, val label: String, val test: (ModelItem) -> Boolean)
private val filters = listOf(
    Filter("tools", "Tool calling") { "tools" in it.caps },
    Filter("vision", "Vision") { "vision" in it.caps },
    Filter("reasoning", "Reasoning") { "reasoning" in it.caps },
    Filter("local", "Local") { it.local == true },
    Filter("cloud", "Cloud") { it.local == false },
)

@Composable
fun CapTags(caps: List<String>) {
    val g = LocalG.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if ("tools" in caps) Text("TOOLS", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = g.live)
        if ("vision" in caps) Text("VISION", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = g.ink2)
        if ("reasoning" in caps) Text("REASONING", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = g.ink2)
    }
}

/** Search, capability filters that combine, and a scrollable list. */
@Composable
fun ModelBrowser(items: List<ModelItem>, selected: String? = null, onSelect: ((String) -> Unit)? = null, onRemove: ((String) -> Unit)? = null) {
    val g = LocalG.current
    var q by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(setOf<String>()) }
    val avail = filters.filter { f -> items.any(f.test) }
    val needle = q.trim().lowercase()
    val shown = items.filter { i -> (needle.isEmpty() || "${i.label} ${i.sub.orEmpty()}".lowercase().contains(needle)) && filters.filter { it.id in on }.all { it.test(i) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(q, { q = it }, placeholder = "Search ${items.size} models")
        if (avail.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            avail.forEach { f -> Chip(f.label, f.id in on, { on = if (f.id in on) on - f.id else on + f.id }) }
            if (on.isNotEmpty()) Chip("Clear", false, { on = emptySet() })
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, g.line, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState())) {
            if (shown.isEmpty()) Text(if (items.isEmpty()) "No models yet." else "No models match.", style = T.small, color = g.ink2, modifier = Modifier.padding(12.dp))
            shown.forEachIndexed { idx, i ->
                val sel = selected == i.id
                Row(
                    Modifier.fillMaxWidth().background(if (sel) g.surface2 else Color.Transparent)
                        .then(if (onSelect != null) Modifier.tap { onSelect(i.id) } else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onSelect != null) { Text(if (sel) "✓" else "", color = g.live, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(i.label, style = T.small.copy(fontSize = 13.5.sp, fontWeight = if (sel) FontWeight.SemiBold else null, fontFamily = if (onSelect == null) FontFamily.Monospace else FontFamily.Default), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!i.sub.isNullOrEmpty()) Text(i.sub, style = T.tiny, color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CapTags(i.caps)
                    if (onRemove != null) { Spacer(Modifier.width(8.dp)); Text("×", color = g.ink2, fontSize = 20.sp, modifier = Modifier.tap { onRemove(i.id) }.padding(horizontal = 4.dp)) }
                }
                if (idx < shown.lastIndex) Box(Modifier.fillMaxWidth().padding(top = 0.dp).background(g.line).size(width = 1.dp, height = 1.dp))
            }
        }
    }
}

/** A provider's models with manual add and remove. */
@Composable
fun ModelList(models: List<String>, caps: Map<String, List<String>?>?, onChange: ((List<String>) -> Unit)? = null) {
    var draft by remember { mutableStateOf("") }
    val items = models.map { ModelItem(it, it, null, caps?.get(it).orEmpty(), null) }
    fun add() { val m = draft.trim(); if (m.isNotEmpty() && m !in models) onChange?.invoke(models + m); draft = "" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelBrowser(items, onRemove = onChange?.let { cb -> { id: String -> cb(models - id) } })
        if (onChange != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Field(draft, { draft = it }, Modifier.weight(1f), placeholder = "Add a model by name", mono = true, onDone = ::add)
            Spacer(Modifier.width(8.dp))
            GButton("Add", ::add, variant = Variant.Soft, small = true)
        }
    }
}

/**
 * Picks one model. Closed it shows the choice (name, provider, abilities);
 * the default row says which model it stands for right now.
 */
@Composable
fun ModelPicker(models: List<Model>, value: String, onChange: (String) -> Unit, defaultLabel: String, fallback: String? = null, enabled: Boolean = true) {
    val g = LocalG.current
    var open by remember { mutableStateOf(false) }
    val now = models.find { it.id == fallback } ?: models.find { it.best }
    val items = listOf(ModelItem("", defaultLabel, now?.let { "Now: ${it.label} · ${it.provider}" } ?: "No model connected yet", now?.caps.orEmpty(), null)) +
        models.map { ModelItem(it.id, it.label, it.provider, it.caps ?: if (it.tools) listOf("tools") else emptyList(), it.local) }
    val cur = items.find { it.id == value }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(g.surface).border(1.5.dp, if (cur != null) g.live else g.line, RoundedCornerShape(14.dp))
                .tap(enabled) { open = !open }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(cur?.label ?: value.removePrefix("local:"), style = T.body.copy(fontWeight = FontWeight.Bold), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A model that left the catalog is shown by name only; it
                // already falls back, no warning needed.
                if (cur != null && !cur.sub.isNullOrEmpty()) Text(cur.sub, style = T.tiny, color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (cur != null) CapTags(cur.caps)
            Spacer(Modifier.width(8.dp))
            Text(if (open) "▲" else "▼", style = T.tiny, color = g.ink2)
        }
        if (open) ModelBrowser(items, value, onSelect = { onChange(it); open = false })
    }
}
