import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      "/agui": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
