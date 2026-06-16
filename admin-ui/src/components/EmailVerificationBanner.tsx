import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '@/lib/auth';
import { auth, apiErrorMessage } from '@/lib/api';
import { Button } from './ui/Button';

/**
 * Non-blocking nudge shown to a signed-in user whose email is not yet verified. Lets them resend the
 * verification email; in dev the backend returns the raw token, so we surface a one-click "Verify
 * now" link for convenience. Dismissible for the session.
 */
export function EmailVerificationBanner() {
  const { user, refresh } = useAuth();
  const [dismissed, setDismissed] = useState(false);
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle');
  const [error, setError] = useState<string | null>(null);
  const [devToken, setDevToken] = useState<string | null>(null);

  // Only when we positively know the email is unverified (older identities omit the flag → treated
  // as verified, so existing users are never nagged).
  if (!user || user.emailVerified !== false || dismissed) return null;

  const resend = async () => {
    setState('sending');
    setError(null);
    setDevToken(null);
    try {
      const res = await auth.resendVerification();
      if (res.alreadyVerified) {
        // Reset state before the early-return; the banner usually unmounts once /me reflects the
        // verified flag, but if that read lags we must not leave the button stuck 'sending'.
        setState('idle');
        await refresh();
        return;
      }
      setDevToken(res.verification_token ?? null);
      setState('sent');
    } catch (e) {
      setError(apiErrorMessage(e));
      setState('error');
    }
  };

  return (
    <div
      role="status"
      className="mb-6 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border border-amber-200 bg-amber-50/80 px-4 py-3 text-sm text-amber-800"
    >
      <svg viewBox="0 0 20 20" className="h-4 w-4 shrink-0 fill-amber-500" aria-hidden="true">
        <path d="M10 2a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm0 4a1 1 0 0 1 1 1v4a1 1 0 1 1-2 0V7a1 1 0 0 1 1-1Zm0 9a1.1 1.1 0 1 1 0-2.2 1.1 1.1 0 0 1 0 2.2Z" />
      </svg>
      <span className="min-w-[12rem] flex-1">
        {state === 'sent' ? (
          'Verification email sent — check your inbox.'
        ) : (
          <>
            Please verify <span className="font-medium">{user.email}</span> to secure your account.
          </>
        )}
        {error ? <span className="text-danger-700"> {error}</span> : null}
      </span>
      {devToken ? (
        <Link
          to={`/verify-email?token=${encodeURIComponent(devToken)}`}
          className="font-medium text-indigo-600 transition-colors hover:text-indigo-700"
        >
          Verify now (dev)
        </Link>
      ) : null}
      <Button variant="outline" size="sm" onClick={resend} loading={state === 'sending'}>
        {state === 'sent' ? 'Resend again' : 'Resend email'}
      </Button>
      <button
        type="button"
        onClick={() => setDismissed(true)}
        aria-label="Dismiss"
        className="grid h-7 w-7 place-items-center rounded-lg text-amber-700/70 transition-colors hover:bg-amber-100 hover:text-amber-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber-500"
      >
        <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" aria-hidden="true">
          <path d="m6 6 12 12M18 6 6 18" />
        </svg>
      </button>
    </div>
  );
}
