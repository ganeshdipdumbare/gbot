// The vault, acted out: a bot asks to use a secret, you approve, the vault
// types it in while the model sees dots, and a lookalike site is refused.
// The scene runs only while it is on screen, and holds one calm frame
// when motion is reduced.

import { botSVG } from "./shapes.js";

const look = { shape: "arch", hue: "mint", eyes: "dot" };
const DOTS = 12;

export function mountVault(root, { still = false } = {}) {
  const $ = (s) => root.querySelector(s);
  const el = {
    bot: $("[data-vault-bot]"), ask: $("[data-ask]"), askText: $("[data-ask-text]"), approve: $("[data-approve]"),
    safe: $("[data-safe]"), lock: $("[data-safe-lock]"), note: $("[data-safe-note]"), row: $('[data-secret="linkedin"]'),
    site: $("[data-site]"), url: $("[data-url]"), urlDot: $("[data-url-dot]"), pw: $("[data-pw]"), dots: $("[data-dots]"),
    signin: $("[data-signin]"), stamp: $("[data-stamp]"), model: $("[data-model]"), modelText: $("[data-model-text]"), key: $("[data-key]"),
  };
  const mood = (m) => { el.bot.innerHTML = botSVG({ ...look, mood: m }, { shadow: true }); el.bot.dataset.mood = m; };

  function reset() {
    root.classList.remove("is-open", "is-refused", "is-done");
    el.ask.classList.remove("is-shown", "is-pressed");
    el.row.classList.remove("is-picked", "is-no");
    el.pw.classList.remove("is-typing");
    el.signin.classList.remove("is-pressed");
    el.stamp.className = "stamp"; el.stamp.textContent = "";
    el.model.classList.remove("is-shown");
    el.url.textContent = "linkedin.com/login";
    el.urlDot.className = "";
    el.dots.textContent = "";
    el.note.textContent = "Bots see names, never values.";
    el.askText.innerHTML = "Type your <b>LinkedIn</b> secret into “Password”?";
    el.modelText.textContent = 'vault_type(name: "LinkedIn")';
    el.key.style.opacity = "0";
    mood("working");
  }

  // Where an element sits inside the stage, for the flying key.
  const at = (node) => {
    const r = node.getBoundingClientRect(), s = root.getBoundingClientRect();
    return { x: r.left - s.left + r.width / 2, y: r.top - s.top + r.height / 2 };
  };

  if (still) {
    reset();
    root.classList.add("is-done");
    el.dots.textContent = "•".repeat(DOTS);
    el.row.classList.add("is-picked");
    el.model.classList.add("is-shown");
    el.modelText.textContent = 'vault_type(name: "LinkedIn") → typed ' + "•".repeat(8);
    el.stamp.className = "stamp is-ok"; el.stamp.textContent = "Signed in";
    mood("done");
    return { dispose() {} };
  }

  // A sleep that also waits while the scene is off screen or the tab is hidden.
  let visible = false, gone = false, wake = null;
  const io = new IntersectionObserver(([e]) => { visible = e.isIntersecting; if (visible && wake) { wake(); wake = null; } }, { threshold: 0.25 });
  io.observe(root);
  const onVis = () => { if (!document.hidden && visible && wake) { wake(); wake = null; } };
  document.addEventListener("visibilitychange", onVis);
  const ready = () => (visible && !document.hidden) ? Promise.resolve() : new Promise((r) => (wake = r));
  const sleep = async (ms) => {
    let left = ms;
    while (left > 0 && !gone) {
      await ready();
      const step = Math.min(left, 100);
      await new Promise((r) => setTimeout(r, step));
      left -= step;
    }
  };

  async function flyKey() {
    const from = at(el.row.querySelector("code")), to = at(el.pw);
    el.key.style.opacity = "1";
    const mid = { x: (from.x + to.x) / 2, y: Math.min(from.y, to.y) - 70 };
    const tf = (p, s = 1, r = 0) => `translate(${p.x}px, ${p.y}px) translate(-50%, -50%) scale(${s}) rotate(${r}deg)`;
    const a = el.key.animate(
      [
        { transform: tf(from, 0.6, -20), opacity: 0 },
        { transform: tf(from, 1, -20), opacity: 1, offset: 0.15 },
        { transform: tf(mid, 1.15, 10), offset: 0.55 },
        { transform: tf(to, 0.9, 0), opacity: 1, offset: 0.9 },
        { transform: tf(to, 0.4, 0), opacity: 0 },
      ],
      { duration: 1100, easing: "cubic-bezier(.45,.05,.3,1)", fill: "forwards" },
    );
    await a.finished.catch(() => {});
  }

  async function type(n) {
    el.pw.classList.add("is-typing");
    for (let i = 1; i <= n && !gone; i++) { el.dots.textContent = "•".repeat(i); await sleep(55 + (i % 3) * 20); }
    el.pw.classList.remove("is-typing");
  }

  async function loop() {
    while (!gone) {
      reset();
      await sleep(1100);
      // 1. The bot needs a password and asks.
      mood("waiting");
      el.ask.classList.add("is-shown");
      await sleep(1700);
      el.ask.classList.add("is-pressed");
      await sleep(350);
      el.ask.classList.remove("is-shown");
      // 2. The vault checks the site, opens, and types; the model sees dots.
      mood("working");
      el.row.classList.add("is-picked");
      el.note.textContent = "linkedin.com is on this secret's list ✓";
      root.classList.add("is-open");
      await sleep(600);
      el.model.classList.add("is-shown");
      await flyKey();
      await type(DOTS);
      el.modelText.textContent = 'vault_type(name: "LinkedIn") → typed ' + "•".repeat(8);
      await sleep(400);
      el.signin.classList.add("is-pressed");
      await sleep(350);
      root.classList.remove("is-open");
      root.classList.add("is-done");
      el.stamp.className = "stamp is-ok"; el.stamp.textContent = "Signed in";
      mood("done");
      await sleep(2600);
      // 3. A lookalike page asks for the same password, and is refused.
      reset();
      el.url.textContent = "linkedin-security.help/verify";
      el.urlDot.className = "is-bad";
      el.model.classList.add("is-shown");
      el.modelText.textContent = 'vault_type(name: "LinkedIn")';
      mood("working");
      await sleep(1300);
      el.row.classList.add("is-no");
      root.classList.add("is-refused");
      el.note.textContent = "Not one of LinkedIn's sites. Nothing typed.";
      el.modelText.textContent = 'vault_type(name: "LinkedIn") → refused';
      el.stamp.className = "stamp is-no"; el.stamp.textContent = "Refused";
      mood("blocked");
      await sleep(3200);
    }
  }

  loop();
  return {
    dispose() { gone = true; io.disconnect(); document.removeEventListener("visibilitychange", onVis); wake?.(); },
  };
}
