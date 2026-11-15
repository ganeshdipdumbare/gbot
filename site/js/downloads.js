// Point every download link at the artifact for the visitor's platform.
// Android gets the APK, Mac gets the app image, and anything else falls back
// to the latest release page (set in the HTML, so it works without JS too).
// Both files live on the repo's latest release; CI keeps their names fixed.

const RELEASE = "https://github.com/ganeshdipdumbare/gbot/releases/latest/download";
const ua = navigator.userAgent;

let target = null;
if (/Android/i.test(ua)) {
  target = { href: `${RELEASE}/gbot.apk`, label: null };
} else if (/Macintosh|Mac OS X/i.test(ua) && !/iPhone|iPad|iPod/i.test(ua)) {
  target = { href: `${RELEASE}/gbot-mac.dmg`, label: "Download for Mac" };
}

if (target) {
  for (const a of document.querySelectorAll("a[data-download]")) {
    a.href = target.href;
    if (target.label && a.textContent.trim().startsWith("Download")) a.textContent = target.label;
  }
}
