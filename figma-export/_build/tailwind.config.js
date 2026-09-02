// fragments/layout.html 의 인라인 tailwind.config 를 그대로 옮긴 것.
// Play CDN 이 런타임에 만들던 CSS 를 파일로 뽑기 위한 설정.
const path = require("node:path");
const ROOT = path.resolve(__dirname, "../../src/main/resources");

module.exports = {
    darkMode: "class",
    content: [ROOT + "/templates/**/*.html", ROOT + "/static/js/**/*.js"],
    theme: {
        extend: {
            colors: {
                "background": "var(--bg)",
                "surface": "var(--surface)",
                "surface-alt": "var(--surface-alt)",
                "surface-container": "var(--surface-alt)",
                "surface-container-low": "var(--surface-alt)",
                "surface-container-high": "var(--surface-alt)",
                "surface-container-highest": "var(--surface-alt)",
                "surface-container-lowest": "var(--surface)",
                "surface-variant": "var(--surface-alt)",
                "surface-bright": "var(--surface)",
                "surface-dim": "var(--surface-alt)",
                "border": "var(--border)",
                "outline": "var(--text-muted)",
                "outline-variant": "var(--border)",
                "primary": "var(--accent)",
                "primary-container": "var(--accent)",
                "primary-fixed-dim": "var(--accent)",
                "inverse-primary": "var(--accent)",
                "accent-hover": "var(--accent-hover)",
                "accent-soft": "var(--accent-soft)",
                "text-primary": "var(--text)",
                "text-muted": "var(--text-muted)",
                "on-surface": "var(--text)",
                "on-surface-variant": "var(--text)",
                "on-background": "var(--text)",
                "on-primary": "#ffffff",
                "on-primary-container": "#ffffff",
                "on-secondary": "#ffffff",
                "sage-bg": "var(--sage-bg)",
                "sage": "var(--sage)",
                "secondary": "var(--sage)",
                "on-secondary-fixed-variant": "var(--sage)",
                "error": "var(--error)"
            },
            fontFamily: {
                "body-main": "var(--font-sans)",
                "section-title": "var(--font-sans)",
                "card-title": "var(--font-sans)",
                "caption": "var(--font-sans)",
                "display-title": "var(--font-serif)",
                "display-title-mobile": "var(--font-serif)",
                "brand-logo": "var(--font-serif)"
            },
            fontSize: {
                "display-title": ["36px", { lineHeight: "1.3", letterSpacing: "-0.01em", fontWeight: "600" }],
                "display-title-mobile": ["28px", { lineHeight: "1.3", letterSpacing: "-0.01em", fontWeight: "600" }],
                "section-title": ["22px", { lineHeight: "1.4", letterSpacing: "-0.01em", fontWeight: "600" }],
                "card-title": ["17px", { lineHeight: "1.4", letterSpacing: "-0.01em", fontWeight: "600" }],
                "body-main": ["15px", { lineHeight: "1.7", letterSpacing: "-0.01em", fontWeight: "400" }],
                "caption": ["13px", { lineHeight: "1.5", letterSpacing: "-0.01em", fontWeight: "400" }]
            },
            spacing: {
                "container-max": "var(--max-w)",
                "card-padding": "var(--space-3)",
                "section-gap": "var(--space-4)",
                "grid-unit": "var(--space-1)"
            },
            borderRadius: {
                "DEFAULT": "0.25rem",
                "lg": "var(--radius-btn)",
                "xl": "var(--radius-card)",
                "full": "var(--radius-chip)",
                "input": "var(--radius-btn)",
                "login-card": "var(--radius-card)"
            }
        }
    },
    plugins: [require("@tailwindcss/forms"), require("@tailwindcss/container-queries")]
};
