// Finds the gbot server (on this machine by default) and opens it.
const saved = localStorage.getItem("gbot.server");
const candidates = saved ? [saved] : ["http://127.0.0.1:8080", "http://localhost:8080"];
const $ = (id) => document.getElementById(id);

async function reachable(base) {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 1500);
    const res = await fetch(base.replace(/\/$/, "") + "/healthz", { signal: ctrl.signal });
    clearTimeout(t);
    return res.ok;
  } catch { return false; }
}

async function connect(list) {
  for (const base of list) {
    if (await reachable(base)) {
      localStorage.setItem("gbot.server", base);
      window.location.replace(base);
      return true;
    }
  }
  return false;
}

async function start() {
  if (await connect(candidates)) return;
  $("title").textContent = "gbot isn't running";
  $("hint").textContent = "Start the server on this machine, or enter its address.";
  $("url").value = candidates[0];
  $("form").hidden = false;
  $("cmd").hidden = false;
  // Keep trying quietly, so starting the server is all it takes.
  setInterval(() => connect([$("url").value]), 3000);
}

$("form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const v = $("url").value.trim();
  if (!/^https?:\/\//.test(v)) return;
  if (!(await connect([v]))) $("hint").textContent = "Couldn't reach " + v + ".";
});

start();
