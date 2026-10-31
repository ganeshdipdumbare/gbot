// Tells the person when a bot finishes or needs them while gbot is in
// the background: native notifications in the desktop app, the browser's
// otherwise.

type TauriNotify = {
  isPermissionGranted(): Promise<boolean>;
  requestPermission(): Promise<string>;
  sendNotification(o: { title: string; body?: string }): void;
};
const tauri = () => (window as unknown as { __TAURI__?: { notification?: TauriNotify } }).__TAURI__?.notification;

export async function askToNotify() {
  try {
    const t = tauri();
    if (t) {
      if (!(await t.isPermissionGranted())) await t.requestPermission();
    } else if ("Notification" in window && Notification.permission === "default") {
      await Notification.requestPermission();
    }
  } catch { /* not supported */ }
}

export async function notify(title: string, body: string, onClick?: () => void) {
  if (document.visibilityState === "visible" && document.hasFocus()) return;
  try {
    const t = tauri();
    if (t) {
      if (await t.isPermissionGranted()) t.sendNotification({ title, body });
      return;
    }
    if ("Notification" in window && Notification.permission === "granted") {
      const n = new Notification(title, { body, icon: "/favicon.svg", tag: title });
      n.onclick = () => { window.focus(); onClick?.(); n.close(); };
    }
  } catch { /* not supported */ }
}
