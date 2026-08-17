/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dev server proxies the API to the application so that a developer running
// `npm run dev` sees the same single-origin behaviour that Caddy provides in the
// container stack (ADR-017). Without this, local development would need CORS and
// the deployed stack would not - two different shapes for the same application.
//
// The target is the application's container port. `npm run dev` therefore expects
// the app to be reachable on 8080, which means either the container stack with a
// temporary port mapping, or `./mvnw spring-boot:run`.
//
// Deliberately a constant rather than an environment variable: reading `process.env`
// here would pull Node's type definitions into a browser project to configure a
// developer convenience that is not part of any deployed path.
const API_TARGET = "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5371,
    proxy: {
      "/api": { target: API_TARGET, changeOrigin: true },
      "/health": { target: API_TARGET, changeOrigin: true },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: true,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/setupTests.ts"],
    css: false,
  },
});
