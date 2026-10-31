import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath } from "node:url";

const api = process.env.GBOT_API ?? "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: fileURLToPath(new URL("../../server/internal/web/dist", import.meta.url)),
    emptyOutDir: true,
    target: "es2022",
    cssCodeSplit: true,
  },
  server: {
    port: 5173,
    proxy: { "/api": { target: api, changeOrigin: false } },
  },
});
