import { defineConfig } from "vitest/config";
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
    // Vite's own default, set explicitly because the documentation cites this line
    // as the single source of truth for the port (ADR-009, EOP-106). It was 5371
    // between 2026-08-17 and 2026-08-20 for no reason anyone could reconstruct, and
    // four documentation sites had to explain the deviation. Changing it means
    // changing them too, so do not adjust this value without a ticket.
    port: 5173,
    proxy: {
      "/api": { target: API_TARGET, changeOrigin: true },
      "/health": { target: API_TARGET, changeOrigin: true },
    },
  },
  build: {
    outDir: "dist",
    // No source maps (EOP-107). ui/Dockerfile copies this whole directory to
    // Caddy's web root, so an emitted .map is a served file and anyone can
    // read the original TypeScript. There is no error-reporting service that
    // needs them, and `npm run dev` is unaffected — Vite serves maps from
    // memory in dev regardless of this setting. ui/Caddyfile refuses *.map at
    // the edge as well, so a regression here fails closed rather than quietly.
    sourcemap: false,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/setupTests.ts"],
    css: false,
    // Coverage exists to feed the front-end SonarQube project (ADR-063), not to
    // gate this build: there is no `thresholds` block here on purpose, because the
    // ratchet in tools/sonar/ is what holds quality and a second, differently
    // defined limit would be two numbers for one invariant.
    //
    // `lcov` is the reporter SonarQube reads (sonar.javascript.lcov.reportPaths);
    // `text` is for the human running `npm run coverage`. The report lands in
    // ui/coverage/, which is gitignored — it is a build output, and committing it
    // would make every test change a diff in a generated file.
    coverage: {
      provider: "v8",
      reporter: ["lcov", "text"],
      reportsDirectory: "./coverage",
      // `all` is what makes an untested file appear at 0% rather than not appear
      // at all. Without it SonarQube reads coverage over only the files a test
      // happened to import, so adding an unreferenced module would *raise* the
      // percentage — the opposite of what the number is for.
      all: true,
      include: ["src/**/*.{ts,tsx}"],
      // Excluded because they are not measurable product code: the test files
      // themselves, the Testing Library setup shim, and the ambient declaration
      // file (types only, zero emitted statements, so it would otherwise sit
      // permanently at 0% and drag the figure down for no reachable reason).
      exclude: [
        "src/**/*.test.{ts,tsx}",
        "src/setupTests.ts",
        "src/vite-env.d.ts",
      ],
    },
  },
});
