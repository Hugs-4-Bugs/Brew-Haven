// Neutral PostCSS configuration for the CafeMS frontend.
//
// Purpose: pin vite's PostCSS config resolution to THIS directory. Without it,
// vite searches parent directories for a postcss config, and in this shared
// workspace unrelated configs exist above the project root, which crashed the
// dev server ("Invalid PostCSS Plugin found"). The frontend uses plain CSS
// (src/styles.css) — no PostCSS plugins are needed; this empty config stops
// the upward search permanently.
export default { plugins: [] };
