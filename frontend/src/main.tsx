import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";
import { App } from "./App";

// HeroUI's dark theme keys off a .dark class; follow the system preference.
const scheme = window.matchMedia("(prefers-color-scheme: dark)");
const apply = () => document.documentElement.classList.toggle("dark", scheme.matches);
apply();
scheme.addEventListener?.("change", apply);

// Restore server state after back/forward cache navigation.
window.addEventListener("pageshow", (event) => {
  if (event.persisted) window.location.reload();
});

const container = document.getElementById("root");

if (container) {
  container.replaceChildren();
  createRoot(container).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
