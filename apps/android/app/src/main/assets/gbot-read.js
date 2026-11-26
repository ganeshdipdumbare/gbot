// gbot-read: how the phone's bot reads a page. It gives the same picture the
// computer's browser gets from Playwright's accessibility snapshot: every
// element by its role and accessible name, in page order, nested under the
// headings, lists, cards and dialogs it sits in, with a number on what can be
// clicked or typed into. Open shadow roots and same-origin iframes are read
// too. Nothing here looks for particular words, so it works the same on any
// site and in any language.
//
// window.__gbotRead()      -> JSON {title, url, tree, cut, layers, scroll, labels}
// window.__gbotEl(id)      -> the element behind a number, or null
// window.__gbotCheck(id)   -> "" when the element is still what the read showed, else why not
// window.__gbotQuiet()     -> ms since the page last changed
// Numbers stay the same for an element while it is on the page.
(() => {
  if (window.__gbotRead) return;
  const CAP = 30000; // characters of tree, the same as the computer's
  const els = new Map(); // number -> {el, role, name}
  let next = 0;
  let lastChange = performance.now();
  try {
    new MutationObserver(() => { lastChange = performance.now(); })
      .observe(document, { subtree: true, childList: true, attributes: true, characterData: true });
  } catch (e) {}

  const ACTION = new Set(["button", "link", "textbox", "searchbox", "combobox", "checkbox", "radio", "switch", "tab",
    "menuitem", "menuitemcheckbox", "menuitemradio", "option", "slider", "spinbutton", "listbox", "treeitem"]);
  // Named by their own text, so their insides are not listed again.
  const FROM_TEXT = new Set(["button", "link", "heading", "tab", "menuitem", "menuitemcheckbox", "menuitemradio",
    "option", "checkbox", "radio", "switch", "treeitem", "clickable"]);
  // Kept as a level of the tree so their contents read as one group.
  const GROUP = new Set(["list", "listitem", "navigation", "main", "banner", "contentinfo", "form", "dialog",
    "alertdialog", "article", "region", "table", "row", "cell", "columnheader", "rowheader", "group", "menu",
    "menubar", "tablist", "tabpanel", "toolbar", "radiogroup", "paragraph", "iframe", "search", "complementary"]);
  const FIELDS = new Set(["textbox", "searchbox", "combobox", "spinbutton"]);
  const INTERESTING = "a[href],button,input,select,textarea,summary,[role],[onclick],[tabindex],h1,h2,h3,h4,h5,h6,img[alt],iframe,[contenteditable]";

  const clean = (s) => String(s == null ? "" : s).replace(/\s+/g, " ").trim();
  const style = (el) => (el.ownerDocument.defaultView || window).getComputedStyle(el);
  const q = (s) => JSON.stringify(s);

  function role(el) {
    const given = clean(el.getAttribute("role")).split(" ")[0];
    if (given && given !== "presentation" && given !== "none") return given;
    const tag = el.tagName.toLowerCase(), type = (el.getAttribute("type") || "").toLowerCase();
    switch (tag) {
      case "a": return el.hasAttribute("href") ? "link" : "";
      case "button": case "summary": return "button";
      case "input":
        if (type === "hidden") return "hidden";
        if (type === "checkbox") return "checkbox";
        if (type === "radio") return "radio";
        if (["button", "submit", "reset", "image"].includes(type)) return "button";
        if (type === "range") return "slider";
        if (type === "number") return "spinbutton";
        if (el.hasAttribute("list")) return "combobox";
        return type === "search" ? "searchbox" : "textbox";
      case "textarea": return "textbox";
      case "select": return el.multiple || el.size > 1 ? "listbox" : "combobox";
      case "option": return "option";
      case "h1": case "h2": case "h3": case "h4": case "h5": case "h6": return "heading";
      case "ul": case "ol": case "menu": return "list";
      case "li": return "listitem";
      case "nav": return "navigation";
      case "main": return "main";
      case "form": return "form";
      case "dialog": return "dialog";
      case "article": return "article";
      case "aside": return "complementary";
      case "section": return el.hasAttribute("aria-label") || el.hasAttribute("aria-labelledby") ? "region" : "";
      case "table": return "table";
      case "tr": return "row";
      case "td": return "cell";
      case "th": return "columnheader";
      case "img": return clean(el.getAttribute("alt")) ? "img" : "";
      case "iframe": return "iframe";
      case "p": return "paragraph";
      case "fieldset": case "details": return "group";
    }
    if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return "textbox";
    return "";
  }

  function byIds(el, attr) {
    const root = el.getRootNode ? el.getRootNode() : el.ownerDocument;
    return clean((el.getAttribute(attr) || "").split(/\s+/).map((id) => {
      const n = id && (root.getElementById ? root.getElementById(id) : el.ownerDocument.getElementById(id));
      return n ? n.innerText || n.textContent : "";
    }).join(" "));
  }

  function name(el, r) {
    let n = byIds(el, "aria-labelledby") || clean(el.getAttribute("aria-label"));
    if (n) return n.slice(0, 100);
    const tag = el.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") {
      if (el.labels && el.labels.length) {
        n = clean(Array.from(el.labels).map((l) => l.innerText).join(" "));
        if (n) return n.slice(0, 100);
      }
      const type = (el.getAttribute("type") || "").toLowerCase();
      if (tag === "INPUT" && ["button", "submit", "reset"].includes(type)) return clean(el.value) || (type === "submit" ? "Submit" : "");
      if (tag === "INPUT" && type === "image") return clean(el.alt);
      return clean(el.getAttribute("placeholder") || el.getAttribute("title")).slice(0, 100);
    }
    if (tag === "IMG") return clean(el.alt || el.title).slice(0, 100);
    if (FROM_TEXT.has(r)) {
      n = clean(el.innerText);
      if (!n) {
        const pic = el.querySelector("img[alt], svg title");
        n = pic ? clean(pic.alt || pic.textContent) : "";
      }
    }
    return clean(n || el.getAttribute("title")).slice(0, 100);
  }

  function hidden(el, s) {
    if (el.hidden || el.getAttribute("aria-hidden") === "true") return true;
    if (s.display === "none" || s.visibility === "hidden" || s.visibility === "collapse") return true;
    return s.display !== "contents" && el.getClientRects().length === 0;
  }

  // Something the page made clickable without saying what it is.
  function clickable(el, s) {
    if (el.hasAttribute("onclick")) return true;
    if (el.hasAttribute("tabindex") && el.tabIndex >= 0) return true;
    if (s.cursor !== "pointer") return false;
    const p = el.parentElement;
    return !p || style(p).cursor !== "pointer";
  }

  // Only plain text and choices are echoed. Anything else (passwords, cards,
  // codes, keys, text areas, which often hold them) shows whether it is filled.
  function value(el) {
    const tag = el.tagName.toLowerCase(), type = (el.getAttribute("type") || "").toLowerCase();
    if (tag === "select") {
      const o = el.selectedOptions && el.selectedOptions[0];
      return o ? clean(o.text) : "";
    }
    if (el.isContentEditable && tag !== "input" && tag !== "textarea") return clean(el.innerText) ? "(filled)" : "";
    const v = el.value || "";
    if (!v) return "";
    const labels = el.labels ? Array.from(el.labels).map((l) => l.innerText).join(" ") : "";
    const hint = [el.name, el.id, el.getAttribute("autocomplete"), el.getAttribute("inputmode"),
      el.getAttribute("placeholder"), el.getAttribute("aria-label"), labels].join(" ");
    const plain = tag === "input" && ["", "text", "search", "email", "url", "number", "date"].includes(type);
    const secretish = /(otp|one-time|code|token|secret|card|cc-|cvv|cvc|csc|ssn|pin|passcode|2fa|totp|password|key)/i.test(hint);
    return plain && !secretish ? clean(v).slice(0, 80) : "(filled)";
  }

  // Query strings often carry tokens; links are shown without them.
  function shownUrl(href) {
    const base = String(href || "").split("#")[0];
    const i = base.indexOf("?");
    return i < 0 ? base : base.slice(0, i) + "?(query hidden)";
  }

  function number(el, r, n) {
    let id = el.__gbotId;
    if (!id || !els.has(id) || els.get(id).el !== el) {
      id = ++next;
      try { el.__gbotId = id; } catch (e) {}
    }
    els.set(id, { el, role: r, name: n });
    return id;
  }

  function states(el, r) {
    const out = [];
    if (r === "heading") out.push(`[level=${el.getAttribute("aria-level") || el.tagName.slice(1) || 2}]`);
    if (["checkbox", "radio", "switch", "menuitemcheckbox", "menuitemradio"].includes(r)) {
      const c = el.getAttribute("aria-checked");
      if (el.checked || c === "true") out.push("[checked]");
    }
    if (el.getAttribute("aria-selected") === "true" || (r === "option" && el.selected)) out.push("[selected]");
    if (el.getAttribute("aria-expanded") === "true") out.push("[expanded]");
    if (el.disabled || el.getAttribute("aria-disabled") === "true") out.push("[disabled]");
    return out.length ? " " + out.join(" ") : "";
  }

  window.__gbotRead = function () {
    const lines = [], labels = {};
    let size = 0, cut = false, text = "";
    const emit = (depth, line) => {
      if (cut) return;
      const s = "  ".repeat(depth) + line;
      size += s.length + 1;
      if (size > CAP) { cut = true; return; }
      lines.push(s);
    };
    const flush = (depth) => {
      const t = clean(text);
      text = "";
      if (t) emit(depth, "- text: " + q(t.slice(0, 300)).slice(1, -1));
    };

    function kids(node) {
      if (node.shadowRoot) return node.shadowRoot.childNodes;
      if (node.tagName === "SLOT") return node.assignedNodes().length ? node.assignedNodes() : node.childNodes;
      return node.childNodes;
    }

    function walk(node, depth) {
      for (const child of kids(node)) {
        if (cut) return;
        if (child.nodeType === 3) { text += " " + child.textContent; continue; }
        if (child.nodeType !== 1) continue;
        const el = child, tag = el.tagName;
        if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT" || tag === "TEMPLATE" || el.hasAttribute("data-gbot-fx")) continue;
        let s;
        try { s = style(el); } catch (e) { continue; }
        if (hidden(el, s)) continue;
        let r = role(el);
        if (r === "hidden") continue;
        if (!r && clickable(el, s)) r = "clickable";
        if (!r) {
          // A plain wrapper: its contents read in place. Block boxes keep their text apart.
          const block = s.display !== "inline" && s.display !== "contents";
          if (block) flush(depth);
          walk(el, depth);
          if (block) flush(depth);
          continue;
        }
        flush(depth);
        if (r === "iframe") {
          let doc = null;
          try { doc = el.contentDocument; } catch (e) {}
          if (!doc || !doc.body) continue; // another site's frame can't be read from here
          emit(depth, "- iframe:");
          walk(doc.body, depth + 1);
          flush(depth + 1);
          continue;
        }
        const n = name(el, r);
        if (ACTION.has(r) || r === "clickable") {
          // A clickable box that holds its own buttons or links is a group, not one control.
          const holds = r === "clickable" && el.querySelector("a[href],button,input,select,textarea,[role=button],[role=link]");
          if (!holds) {
            const id = number(el, r, n);
            const shownRole = r === "clickable" ? "generic" : r;
            labels[id] = n || shownRole;
            let line = `- ${shownRole}${n ? " " + q(n) : ""} [${id}]${states(el, r)}`;
            if (r === "link") line += " -> " + shownUrl(el.href);
            if (FIELDS.has(r) || r === "listbox") {
              const v = value(el);
              if (v) line += ": " + v;
            }
            emit(depth, line);
            // Its own text is its name; anything else inside (a menu, a list) is read below it.
            if (!FROM_TEXT.has(r) && !FIELDS.has(r) && r !== "listbox") { walk(el, depth + 1); flush(depth + 1); }
            continue;
          }
          r = "group";
        }
        if (r === "heading" || r === "img") {
          emit(depth, `- ${r}${n ? " " + q(n) : ""}${states(el, r)}`);
          continue;
        }
        if (GROUP.has(r)) {
          // A group with nothing to act on inside reads as one line of text.
          if (!el.querySelector(INTERESTING) && !el.shadowRoot) {
            const t = clean(el.innerText);
            if (t) emit(depth, `- ${r}${n && n !== t ? " " + q(n) : ""}: ${q(t.slice(0, 300)).slice(1, -1)}`);
            continue;
          }
          const at = lines.length;
          emit(depth, `- ${r}${n ? " " + q(n) : ""}:`);
          walk(el, depth + 1);
          flush(depth + 1);
          if (lines.length === at + 1) lines.pop(); // an empty group says nothing
          continue;
        }
        // Any other role: read its contents in place.
        walk(el, depth);
        flush(depth);
      }
    }

    if (document.body) { walk(document.body, 0); flush(0); }
    const root = document.scrollingElement || document.documentElement;
    return JSON.stringify({
      title: document.title,
      url: location.href,
      tree: lines.join("\n"),
      cut,
      layers: layers(),
      scroll: root ? { y: Math.round(root.scrollTop), height: Math.round(root.scrollHeight), viewport: Math.round(root.clientHeight),
        atTop: root.scrollTop <= 1, atBottom: root.scrollTop + root.clientHeight >= root.scrollHeight - 2 } : null,
      labels,
    });
  };

  // What sits on top of the page: dialogs, and fixed layers that cover a good
  // part of it or run across its lower part (where cookie, app and newsletter
  // prompts live). Judged by position and size, never by wording, so it works
  // for any site and language. The same as the computer's LAYERS_JS.
  function layers() {
  const vw = innerWidth, vh = innerHeight, out = [], found = [];
  const modal = (n) => n.tagName === "DIALOG" || ["dialog", "alertdialog"].includes(n.getAttribute("role")) || n.getAttribute("aria-modal") === "true";
  const visit = (root) => {
    for (const n of root.querySelectorAll("*")) {
      if (n.hasAttribute("data-gbot-fx")) continue;
      if (n.shadowRoot) visit(n.shadowRoot);
      if (found.some((f) => f.contains(n))) continue;
      const s = getComputedStyle(n);
      if (s.position !== "fixed" && !modal(n)) continue;
      if (s.display === "none" || s.visibility === "hidden" || +s.opacity === 0) continue;
      found.push(n);
    }
  };
  visit(document);
  for (const n of found) {
    const r = n.getBoundingClientRect();
    const w = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0)), h = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
    if (w < 1 || h < 1) continue;
    const cover = (w * h) / (vw * vh);
    // A bar or card in the lower part of the screen, where cookie, app and newsletter prompts
    // sit: across most of it, or big enough to hold its own buttons. Sticky headers at the top
    // and small floating buttons (a chat bubble) are left out.
    const control = n.tagName === "IFRAME" || n.querySelector("button,a[href],input,select,[role=button],iframe");
    const lowBar = r.top > vh * 0.3 && (w >= vw * 0.5 || (control && cover >= 0.04));
    if (!(cover >= 0.25 || lowBar || (modal(n) && cover >= 0.02))) continue;
    let t = (n.innerText || "").replace(/\s+/g, " ").trim();
    if (!t && (n.tagName === "IFRAME" || n.querySelector("iframe"))) t = "(its content is in a frame)";
    if (t) out.push(t.slice(0, 180));
    if (out.length >= 3) break;
  }
  return out;
  }

  window.__gbotEl = (id) => {
    const e = els.get(+id);
    return e && e.el.isConnected ? e.el : null;
  };

  // The element must still be on the page with the role and name it had when
  // read, so a page that relabels or swaps an element can't redirect an action.
  window.__gbotCheck = (id) => {
    const e = els.get(+id);
    if (!e) return `No element [${id}] on this page. Numbers come from the latest page read: use one from it.`;
    if (!e.el.isConnected) return `[${id}] is no longer on the page. Read the page again and use a current number.`;
    let r = role(e.el);
    if (!r && e.role === "clickable") r = "clickable";
    if (r !== e.role || name(e.el, r) !== e.name) return `[${id}] changed since the page was read (it was ${e.role} "${e.name}"). Read the page again and use a current number.`;
    return "";
  };

  window.__gbotQuiet = () => Math.round(performance.now() - lastChange);
})();
