// Package web serves the built single-page app embedded in the binary.
// Hashed assets are immutable; brotli/gzip variants are served when the
// build produced them and the client accepts them.
package web

import (
	"embed"
	"io/fs"
	"net/http"
	"path"
	"strings"
)

//go:embed all:dist
var dist embed.FS

func Handler() http.Handler {
	root, _ := fs.Sub(dist, "dist")
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if p == "" || !exists(root, p) {
			p = "index.html" // SPA route
		}
		if strings.HasPrefix(p, "assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		if ct := contentType(p); ct != "" {
			w.Header().Set("Content-Type", ct)
		}
		w.Header().Add("Vary", "Accept-Encoding")
		ae := r.Header.Get("Accept-Encoding")
		for _, enc := range []struct{ name, ext string }{{"br", ".br"}, {"gzip", ".gz"}} {
			if strings.Contains(ae, enc.name) && exists(root, p+enc.ext) {
				w.Header().Set("Content-Encoding", enc.name)
				http.ServeFileFS(w, r, root, p+enc.ext)
				return
			}
		}
		if !exists(root, p) {
			http.Error(w, "gbot web app is not built. Run: pnpm build:web", http.StatusNotFound)
			return
		}
		http.ServeFileFS(w, r, root, p)
	})
}

func exists(root fs.FS, p string) bool {
	st, err := fs.Stat(root, p)
	return err == nil && !st.IsDir()
}

func contentType(p string) string {
	switch path.Ext(p) {
	case ".js":
		return "text/javascript; charset=utf-8"
	case ".css":
		return "text/css; charset=utf-8"
	case ".html":
		return "text/html; charset=utf-8"
	case ".svg":
		return "image/svg+xml"
	case ".woff2":
		return "font/woff2"
	}
	return ""
}
