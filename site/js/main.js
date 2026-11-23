// The page's own behavior: the picker, small niceties, and bringing the
// 3D crew in after the words are already on screen.

import { botSVG, eyeNames, hues, moodNames, shapeNames } from "./shapes.js";

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const still = matchMedia("(prefers-reduced-motion: reduce)").matches;

// ---------- nav hairline once the page moves ----------
const nav = $("[data-nav]");
const onScroll = () => nav.classList.toggle("is-stuck", scrollY > 8);
addEventListener("scroll", onScroll, { passive: true });
onScroll();

// ---------- sections ease in as they arrive ----------
const reveals = $$("[data-reveal]");
if ("IntersectionObserver" in window && !still) {
  const io = new IntersectionObserver((entries) => {
    let i = 0;
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      e.target.style.setProperty("--i", i++);
      e.target.classList.add("is-in");
      io.unobserve(e.target);
    }
  }, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });
  reveals.forEach((el) => io.observe(el));
} else reveals.forEach((el) => el.classList.add("is-in"));

// ---------- copy the commands ----------
for (const btn of $$("[data-copy]")) {
  btn.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(btn.dataset.copy);
      btn.textContent = "Copied";
    } catch {
      btn.textContent = "Select and copy";
    }
    // One timer per button: a second click restarts the label, not the old timer.
    clearTimeout(btn._copyTimer);
    btn._copyTimer = setTimeout(() => (btn.textContent = "Copy"), 1600);
  });
}

// ---------- the lineup above the last call ----------
const lineup = $("[data-lineup]");
const crew = [
  ["circle", "cobalt", "dot"], ["square", "mint", "pill"], ["triangle", "sky", "dot"], ["hexagon", "coral", "wide"],
  ["cloud", "bubblegum", "dot"], ["arch", "sun", "pill"], ["pill", "grape", "wide"], ["diamond", "cream", "dot"],
].map(([shape, hue, eyes]) => ({ shape, hue, eyes }));
if (lineup) lineup.innerHTML = crew.map((look) => botSVG(look)).join("");

// ---------- the studio picker ----------
const notes = {
  idle: ["Resting.", "A slow breath and the odd glance around."],
  thinking: ["Thinking.", "Eyes up and to the side, with a slow sway."],
  working: ["Working.", "Eyes narrowed, moving to a steady rhythm."],
  waiting: ["Needs you.", "It leans in, eyes wide. There is a card waiting for your OK."],
  blocked: ["Stuck.", "Brows down and a small shake of the head."],
  sleepy: ["Asleep.", "Its computer is frozen, windows and all."],
  done: ["Done.", "A hop and a smile."],
};
const form = $("[data-pickers]");
const note = $("[data-mood-note]");
const studioEl = $('[data-stage="studio"]');
const state = { shape: "arch", hue: "mint", eyes: "dot", mood: "idle" };
let studio = null;
let studioFlat = null;

function paintPicker() {
  for (const el of $$('[data-icon]', form)) {
    const input = el.previousElementSibling;
    const v = el.dataset.icon;
    if (input.name === "shape") el.innerHTML = botSVG({ ...state, mood: "idle", shape: v });
    else if (input.name === "eyes") el.innerHTML = botSVG({ ...state, mood: "idle", eyes: v });
    else el.style.setProperty("--c", hues[v]);
  }
}

function paintStudio(changed) {
  const [head, rest] = notes[state.mood];
  note.innerHTML = `<b>${head}</b> ${rest}`;
  if (changed !== "mood") paintPicker();
  if (studio) {
    if (changed === "mood") studio.setMood(state.mood);
    else studio.set(state);
  } else if (studioFlat) studioFlat.innerHTML = botSVG(state, { shadow: true });
}

if (form) {
  paintPicker();
  // Only known values reach the state, since the state is written into SVG.
  const allowed = { shape: shapeNames, hue: Object.keys(hues), eyes: eyeNames, mood: moodNames };
  form.addEventListener("change", (e) => {
    const { name, value } = e.target;
    if (!allowed[name] || !allowed[name].includes(value)) return;
    state[name] = value;
    paintStudio(name);
  });
  form.addEventListener("submit", (e) => e.preventDefault());
  $("[data-surprise]")?.addEventListener("click", () => {
    const pick = (list, not) => {
      let v;
      do v = list[Math.floor(Math.random() * list.length)]; while (v === not);
      return v;
    };
    state.shape = pick(shapeNames, state.shape);
    state.hue = pick(Object.keys(hues), state.hue);
    state.eyes = pick(eyeNames);
    for (const name of ["shape", "hue", "eyes"]) form.elements[name].value = state[name];
    paintStudio("look");
  });
}

// ---------- the 3D crew ----------
function flat(el, looks) {
  const box = document.createElement("div");
  box.className = "flat";
  box.innerHTML = looks.map((l) => botSVG(l, { shadow: true })).join("");
  el.append(box);
  return box;
}

const heroEl = $('[data-stage="hero"]');
let bots;
const load = () => (bots ||= import("./bots.js"));

async function startHero() {
  try {
    const { mountHero } = await load();
    mountHero(heroEl, { still });
    if (!still) $("[data-hint]").hidden = false;
  } catch (err) {
    console.warn("gbot: 3D crew unavailable, showing the flat one.", err);
    flat(heroEl, crew);
  }
}

async function startStudio() {
  try {
    const { mountStudio } = await load();
    studio = mountStudio(studioEl, { still });
    studio.set(state);
    if (state.mood !== "idle") studio.setMood(state.mood);
  } catch {
    // A half-built stage must not stay as the studio: tear it down and
    // show the flat picture instead.
    try { studio?.dispose(); } catch {}
    studio = null;
    studioFlat = flat(studioEl, [state]);
  }
}

if (heroEl) startHero();

// ---------- the vault scene ----------
const vaultEl = $("[data-vault]");
if (vaultEl) import("./vault.js").then(({ mountVault }) => mountVault(vaultEl, { still })).catch((err) => console.warn("gbot: vault scene unavailable", err));
if (studioEl) {
  // The second scene waits until it is nearly on screen. Browsers without
  // IntersectionObserver start it straight away, as the reveal code does.
  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver((entries) => {
      if (!entries.some((e) => e.isIntersecting)) return;
      io.disconnect();
      startStudio();
    }, { rootMargin: "600px 0px" });
    io.observe(studioEl);
  } else {
    startStudio();
  }
}

// A link to a question in the FAQ opens that answer instead of landing on a
// closed one.
function openFromHash() {
  const el = location.hash && document.getElementById(location.hash.slice(1));
  if (el instanceof HTMLDetailsElement) el.open = true;
}
addEventListener("hashchange", openFromHash);
openFromHash();
