import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MotionConfig } from "motion/react";
import "./styles.css";
import { App } from "./App";
import { applyTheme, watchSystemTheme } from "./lib/theme";

// Before the first paint, so a dark choice never flashes white.
applyTheme();
watchSystemTheme();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <MotionConfig reducedMotion="user">
      <App />
    </MotionConfig>
  </StrictMode>,
);
