use tauri::{WebviewUrl, WebviewWindowBuilder};

/// The desktop app is a thin native shell: it opens the gbot server
/// running on this machine and adds native notifications.
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("gbot")
                .inner_size(1240.0, 820.0)
                .min_inner_size(420.0, 560.0)
                // Lets the web app adapt to the desktop shell.
                .initialization_script("document.documentElement.dataset.shell = 'desktop';")
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running gbot");
}
