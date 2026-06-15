import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  /** Optional custom fallback renderer. */
  fallback?: (error: Error, reset: () => void) => ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Top-level React error boundary. Without this, a render-time exception in any page (e.g. a
 * contract mismatch like calling `.map` on a non-array) unmounts the entire app and white-screens
 * the SPA with no recovery path (finding P1-17). This catches the error, shows a recoverable
 * fallback, and lets the user retry or reload.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Log so the error is observable in the console / any error-reporting hook.
    // eslint-disable-next-line no-console
    console.error('Unhandled UI error:', error, info.componentStack);
  }

  reset = () => this.setState({ error: null });

  render() {
    const { error } = this.state;
    if (error) {
      if (this.props.fallback) return this.props.fallback(error, this.reset);
      return (
        <div className="flex min-h-screen items-center justify-center px-4">
          <div className="glass-card w-full max-w-md p-7 text-center shadow-glass-xl motion-safe:animate-scale-in">
            <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-danger-100 text-danger-600 ring-1 ring-danger-200">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={2}
                className="h-6 w-6"
                aria-hidden="true"
              >
                <path d="M12 9v4" strokeLinecap="round" />
                <path d="M12 17h.01" strokeLinecap="round" />
                <path
                  d="M10.3 3.9 2.4 17.5A2 2 0 0 0 4.1 20.5h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"
                  strokeLinejoin="round"
                />
              </svg>
            </div>
            <h1 className="font-display text-lg font-semibold text-ink">Something went wrong</h1>
            <p className="mt-1.5 text-sm text-ink-muted">
              The page hit an unexpected error. Try again, or reload the app.
            </p>
            {error.message ? (
              <pre className="mt-4 overflow-auto rounded-lg border border-slate-200/80 bg-slate-50/80 p-3 text-left font-mono text-xs text-ink-soft">
                {error.message}
              </pre>
            ) : null}
            <div className="mt-5 flex justify-center gap-2">
              <button
                type="button"
                className="rounded-lg border border-white/70 bg-white/60 px-4 py-2 text-sm font-medium text-ink-soft shadow-glass-sm ring-1 ring-slate-900/5 transition-all duration-fast hover:bg-white/80 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
                onClick={this.reset}
              >
                Try again
              </button>
              <button
                type="button"
                className="rounded-lg bg-aurora-primary px-4 py-2 text-sm font-medium text-white shadow-glow transition-all duration-fast hover:bg-aurora-primary-hover hover:shadow-glow-lg hover:-translate-y-px active:translate-y-0 focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
                onClick={() => window.location.assign('/')}
              >
                Reload app
              </button>
            </div>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
