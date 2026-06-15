import type { Config } from 'tailwindcss';
import defaultTheme from 'tailwindcss/defaultTheme';

/**
 * AURORA GLASS — design tokens.
 *
 * Light-mode only. No `dark:` variants, no theme toggle.
 * This config is the single source of truth for colors, elevation,
 * blur, radii, fonts, easing and keyframes. Feature components apply
 * these via Tailwind utilities + the `.glass-*` helpers in index.css.
 *
 * The `brand` ramp is preserved (now mapped onto the indigo accent)
 * so any existing `brand-*` reference keeps working.
 */
const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        /**
         * brand — preserved alias. Re-pointed at the aurora indigo accent
         * so legacy `brand-600` etc. now read as premium indigo, not the old blue.
         */
        brand: {
          50: '#eef2ff',
          100: '#e0e7ff',
          200: '#c7d2fe',
          300: '#a5b4fc',
          400: '#818cf8',
          500: '#6366f1',
          600: '#4f46e5',
          700: '#4338ca',
          800: '#3730a3',
          900: '#312e81',
        },

        /** aurora — the signature accent ramps (indigo → violet → cyan). */
        aurora: {
          indigo: '#6366f1',
          violet: '#a855f7',
          fuchsia: '#d946ef',
          cyan: '#22d3ee',
          sky: '#38bdf8',
        },

        /** indigo primary-action ramp (vivid, AA-on-white at 600+). */
        indigo: {
          50: '#eef2ff',
          100: '#e0e7ff',
          200: '#c7d2fe',
          300: '#a5b4fc',
          400: '#818cf8',
          500: '#6366f1',
          600: '#4f46e5',
          700: '#4338ca',
          800: '#3730a3',
          900: '#312e81',
        },

        /** violet companion accent. */
        violet: {
          50: '#f5f3ff',
          100: '#ede9fe',
          200: '#ddd6fe',
          300: '#c4b5fd',
          400: '#a78bfa',
          500: '#8b5cf6',
          600: '#7c3aed',
          700: '#6d28d9',
          800: '#5b21b6',
          900: '#4c1d95',
        },

        /** cyan companion accent. */
        cyan: {
          50: '#ecfeff',
          100: '#cffafe',
          200: '#a5f3fc',
          300: '#67e8f9',
          400: '#22d3ee',
          500: '#06b6d4',
          600: '#0891b2',
          700: '#0e7490',
        },

        /**
         * ink — text scale. Use these (or slate equivalents) for all copy.
         * ink (900) for headings/strong body, ink-soft (700) for body,
         * ink-muted (600) for secondary text — all AA on >=0.55 white glass.
         * ink-faint (500) only for large/decorative text or icons.
         */
        ink: {
          DEFAULT: '#0f172a', // slate-900 — headings / primary
          soft: '#334155', // slate-700 — body
          muted: '#475569', // slate-600 — secondary (AA on glass)
          faint: '#64748b', // slate-500 — large/muted only
          ghost: '#94a3b8', // slate-400 — DECORATIVE ONLY, never meaningful text
        },

        /**
         * surface — light canvas + glass base tints.
         * `canvas` is the page base behind the aurora mesh.
         */
        surface: {
          canvas: '#f6f7fb',
          base: '#ffffff',
          subtle: '#f1f5f9', // slate-100 — solid fills, dividers backdrop
          line: '#e2e8f0', // slate-200 — solid hairline
        },

        /** semantic — light-toned status colors (pair with icon/label, never color-alone). */
        success: {
          50: '#ecfdf5',
          100: '#d1fae5',
          200: '#a7f3d0',
          500: '#10b981',
          600: '#059669',
          700: '#047857',
        },
        warn: {
          50: '#fffbeb',
          100: '#fef3c7',
          200: '#fde68a',
          500: '#f59e0b',
          600: '#d97706',
          700: '#b45309',
        },
        danger: {
          50: '#fff1f2',
          100: '#ffe4e6',
          200: '#fecdd3',
          500: '#f43f5e',
          600: '#e11d48',
          700: '#be123c',
        },
        info: {
          50: '#eff6ff',
          100: '#dbeafe',
          200: '#bfdbfe',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8',
        },
      },

      fontFamily: {
        // UI / body — Inter (variable). Falls back to system sans.
        sans: ['"Inter Variable"', 'Inter', ...defaultTheme.fontFamily.sans],
        // Display — Sora (variable) for h1/h2, page titles, big KPI numbers.
        display: ['"Sora Variable"', 'Sora', ...defaultTheme.fontFamily.sans],
        // Mono — preserved for keys / IDs / fingerprints.
        mono: ['"JetBrains Mono"', ...defaultTheme.fontFamily.mono],
      },

      fontSize: {
        // tabular metric scale used by KPI values (line-heights tuned for numerals)
        'metric-sm': ['1.5rem', { lineHeight: '1.75rem', fontWeight: '600' }],
        metric: ['1.875rem', { lineHeight: '2.25rem', fontWeight: '600' }],
        'metric-lg': ['2.25rem', { lineHeight: '2.5rem', fontWeight: '700' }],
      },

      borderRadius: {
        // inputs/buttons use md(0.5rem)/lg(0.75rem); cards use 2xl(1rem); pills use full
        xl: '0.875rem',
        '2xl': '1rem',
        '3xl': '1.5rem',
      },

      /**
       * blur — backdrop-filter blur scale. Sweet spot for glass is 12–18px.
       * Use `backdrop-blur-glass` (14px) on cards, `backdrop-blur-nav` (18px) on chrome.
       */
      blur: {
        glass: '14px',
        nav: '18px',
        blob: '60px', // for the optional extra aurora blob layer
      },
      backdropBlur: {
        glass: '14px',
        nav: '18px',
      },

      /**
       * boxShadow — layered Stripe/Linear-style soft elevation. Stack of
       * low-opacity shadows for premium depth, never a single dark shadow.
       * `glass-inset` adds the 1px "rim of light" on glass surfaces.
       */
      boxShadow: {
        'glass-sm': '0 1px 2px rgba(16,24,40,0.05), 0 1px 1px rgba(16,24,40,0.04)',
        glass:
          '0 1px 1px rgba(16,24,40,0.04), 0 4px 8px rgba(16,24,40,0.05), 0 12px 24px rgba(16,24,40,0.06)',
        'glass-lg':
          '0 2px 4px rgba(16,24,40,0.05), 0 12px 24px rgba(16,24,40,0.07), 0 24px 48px -12px rgba(16,24,40,0.12)',
        'glass-xl':
          '0 8px 16px -4px rgba(16,24,40,0.08), 0 32px 64px -16px rgba(16,24,40,0.18)',
        'glass-inset': 'inset 0 1px 0 rgba(255,255,255,0.7)',
        // colored glow for the primary gradient button
        glow: '0 4px 14px -4px rgba(99,102,241,0.5)',
        'glow-lg': '0 6px 20px -4px rgba(99,102,241,0.6)',
        // focus ring fallback (prefer focus-visible:ring utilities)
        focus: '0 0 0 2px #ffffff, 0 0 0 4px #6366f1',
      },

      backgroundImage: {
        // primary action gradient
        // Start at indigo-600 (#4f46e5) so white text clears WCAG AA (5.6:1) along
        // the lightest stop — indigo-500 (#6366f1) was only 4.47:1 for sm/md buttons.
        'aurora-primary': 'linear-gradient(135deg, #4f46e5 0%, #7c3aed 100%)',
        'aurora-primary-hover': 'linear-gradient(135deg, #4f46e5 0%, #6d28d9 100%)',
        // soft accent chip backgrounds
        'aurora-chip': 'linear-gradient(135deg, rgba(99,102,241,0.15) 0%, rgba(34,211,238,0.15) 100%)',
        // subtle title/value text gradient
        'aurora-text': 'linear-gradient(135deg, #4f46e5 0%, #7c3aed 50%, #0891b2 100%)',
      },

      transitionTimingFunction: {
        // token-driven easing — see DESIGN_SYSTEM.md motion section
        'ease-out-quint': 'cubic-bezier(0.22, 1, 0.36, 1)', // standard entrances
        'ease-out-expo': 'cubic-bezier(0.16, 1, 0.3, 1)', // emphasized (dialogs/route)
        'ease-in-quint': 'cubic-bezier(0.64, 0, 0.78, 0)', // exits / accelerate
        'ease-standard': 'cubic-bezier(0.4, 0, 0.2, 1)', // Material standard inOut
      },

      transitionDuration: {
        instant: '100ms',
        fast: '150ms',
        base: '220ms',
        moderate: '320ms',
        slow: '500ms',
      },

      keyframes: {
        // entrance: opacity + small upward translate
        'fade-up': {
          '0%': { opacity: '0', transform: 'translateY(12px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'fade-in': {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        // dialog / popover entrance
        'scale-in': {
          '0%': { opacity: '0', transform: 'translateY(8px) scale(0.96)' },
          '100%': { opacity: '1', transform: 'translateY(0) scale(1)' },
        },
        // skeleton loading sweep
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        // slow ambient drift of the aurora mesh backdrop (gated behind motion-safe)
        'aurora-drift': {
          '0%': { transform: 'translate3d(0, 0, 0) scale(1)' },
          '50%': { transform: 'translate3d(-2%, 1.5%, 0) scale(1.05)' },
          '100%': { transform: 'translate3d(1.5%, -1%, 0) scale(1.02)' },
        },
        // gentle float for accent chips / decorative elements
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-4px)' },
        },
        // subtle pulse for live/status dots — compositor-only (transform + opacity).
        // Drives a separate ring element (see `.pulse-ring` in index.css) instead of
        // animating box-shadow spread, which is a per-frame paint operation.
        'pulse-ring': {
          '0%': { transform: 'translate(-50%, -50%) scale(1)', opacity: '0.5' },
          '70%': { transform: 'translate(-50%, -50%) scale(3)', opacity: '0' },
          '100%': { transform: 'translate(-50%, -50%) scale(3)', opacity: '0' },
        },
      },

      animation: {
        'fade-up': 'fade-up 0.32s cubic-bezier(0.22, 1, 0.36, 1) both',
        'fade-in': 'fade-in 0.22s cubic-bezier(0.22, 1, 0.36, 1) both',
        'scale-in': 'scale-in 0.28s cubic-bezier(0.16, 1, 0.3, 1) both',
        shimmer: 'shimmer 1.6s linear infinite',
        'aurora-drift': 'aurora-drift 28s ease-in-out infinite alternate',
        float: 'float 6s ease-in-out infinite',
        'pulse-ring': 'pulse-ring 2s cubic-bezier(0.4, 0, 0.6, 1) infinite',
      },
    },
  },
  plugins: [],
};
export default config;
