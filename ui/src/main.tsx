import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";

// The compiled Design System stylesheet, not the Sass sources. Importing the
// compiled CSS keeps Sass out of the build entirely - one fewer toolchain to
// maintain for a project that uses the defaults and overrides nothing.
import "govuk-frontend/dist/govuk/govuk-frontend.min.css";

const container = document.getElementById("root");
if (!container) {
  throw new Error("Missing #root element - index.html and main.tsx disagree.");
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
