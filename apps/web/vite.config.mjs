import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  build: {
    outDir: "dist/client",
  },
  optimizeDeps: {
    include: ["react", "react-dom/client"],
  },
  server: {
    host: "0.0.0.0",
    allowedHosts: ["terminal.local"],
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
        configure(proxy) {
          proxy.on("error", (_error, _request, response) => {
            if (response.headersSent) return;
            response.writeHead(503, {
              "Content-Type": "application/json; charset=utf-8",
              "Cache-Control": "no-store",
            });
            response.end(JSON.stringify({
              error: {
                code: "API_UNAVAILABLE",
                message: "Skill Center API is temporarily unavailable",
                details: [],
              },
              requestId: "proxy",
            }));
          });
        },
      },
    },
    warmup: {
      clientFiles: ["./src/main.jsx"],
    },
  },
  plugins: [react()],
});
