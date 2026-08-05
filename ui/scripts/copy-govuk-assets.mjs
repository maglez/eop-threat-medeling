/**
 * Copy the GOV.UK Design System fonts and images into `public/` so Vite emits them.
 *
 * The compiled Design System stylesheet references `/assets/fonts/...` and
 * `/assets/images/...` at absolute paths. Nothing in a default Vite build puts
 * them there, so without this step the page renders in fallback system fonts and
 * the crest 404s - a failure that looks like a styling opinion rather than a
 * missing file, which is why the build warning that revealed it is worth heeding.
 *
 * `public/` is generated and gitignored: copying vendored assets into version
 * control would duplicate a dependency we already declare.
 */
import { cp, mkdir, rm } from "node:fs/promises";

const SOURCE = "node_modules/govuk-frontend/dist/govuk/assets";
const TARGET = "public/assets";

await rm(TARGET, { recursive: true, force: true });
await mkdir("public", { recursive: true });
await cp(SOURCE, TARGET, { recursive: true });

console.log(`Copied Design System assets: ${SOURCE} -> ${TARGET}`);
