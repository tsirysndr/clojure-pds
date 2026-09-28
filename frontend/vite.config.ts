import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// The PDS serves exactly /account/app.js and /account/style.css from
// resources/security under a same-origin CSP, so the build emits fixed names.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: "../resources/security",
    emptyOutDir: false,
    manifest: false,
    sourcemap: false,
    rollupOptions: {
      input: "src/main.tsx",
      output: {
        // The server maps exactly these two files; keep everything in them.
        inlineDynamicImports: true,
        entryFileNames: "app.js",
        assetFileNames: (asset) =>
          asset.names?.[0]?.endsWith(".css") ? "style.css" : "app-[name][extname]",
      },
    },
  },
  server: {
    // `npm run dev` proxies API calls to a locally running PDS. The backend
    // requires a matching Origin header on JSON posts, so it is rewritten.
    proxy: Object.fromEntries(
      ["/account/session", "/account/action", "/oauth", "/xrpc"].map((path) => [
        path,
        {
          target: process.env.PDS_DEV_ORIGIN ?? "http://127.0.0.1:3000",
          changeOrigin: true,
          headers: { origin: process.env.PDS_DEV_ORIGIN ?? "http://127.0.0.1:3000" },
        },
      ]),
    ),
  },
  test: {
    globals: true,
    environment: "happy-dom",
    setupFiles: ["src/test/setup.ts"],
    css: false,
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
