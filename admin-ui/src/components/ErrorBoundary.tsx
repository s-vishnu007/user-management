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
        <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
          <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-6 text-center shadow-sm">
            <div className="mx-auto mb-3 grid h-10 w-10 place-items-center rounded-full bg-rose-100 text-rose-600">
              !
            </div>
            <h1 className="text-lg font-semibold text-slate-900">Something went wrong</h1>
            <p className="mt-1 text-sm text-slate-500">
              The page hit an unexpected error. Try again, or reload the app.
            </p>
            {error.message ? (
              <pre className="mt-3 overflow-auto rounded-md border border-slate-200 bg-slate-50 p-2 text-left text-xs text-slate-600">
                {error.message}
              </pre>
            ) : null}
            <div className="mt-4 flex justify-center gap-2">
              <button
                type="button"
                className="rounded-md border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
                onClick={this.reset}
              >
                Try again
              </button>
              <button
                type="button"
                className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700"
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
