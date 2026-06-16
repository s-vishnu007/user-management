import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { auth, apiErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { Button, Card, CardBody } from '@/components/ui';
import { motion } from 'framer-motion';
import { fadeRise, successPop, DURATION, EASE } from '@/lib/motion';

type Status = 'verifying' | 'success' | 'error' | 'missing';

/**
 * Lands the email-verification link (`/verify-email?token=...`). The token is kept in a query param
 * (out of the path/referrer) and the page POSTs it back — a mutation should never be a GET that an
 * email scanner could auto-consume. Verification is non-blocking, so this is purely confirmatory.
 */
export function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const { user, ready, refresh } = useAuth();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>(token ? 'verifying' : 'missing');
  const [error, setError] = useState<string | null>(null);
  const ran = useRef(false);

  useEffect(() => {
    if (!token || ran.current) return;
    ran.current = true; // guard React 18 StrictMode double-invoke so we POST once
    (async () => {
      try {
        await auth.verifyEmail(token);
        // If the user is already signed in (the common case after signup auto-login), refresh the
        // identity so the "verify your email" banner disappears immediately.
        if (user) await refresh();
        setStatus('success');
      } catch (e) {
        setError(apiErrorMessage(e));
        setStatus('error');
      }
    })();
    // user/refresh intentionally omitted: this must run exactly once for the token.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const continueTo = () => navigate(ready && user ? '/' : '/login', { replace: true });

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10">
      <motion.div variants={fadeRise} initial="hidden" animate="show" className="w-full max-w-md">
        <Card className="bg-white/80">
          <CardBody className="p-8 sm:p-10">
            <div className="mb-6 text-center">
              <div className="mx-auto mb-4 grid h-11 w-11 place-items-center rounded-xl bg-aurora-primary text-base font-bold text-white shadow-glow">
                KF
              </div>
              <h1 className="font-display text-2xl font-semibold tracking-tight text-ink">
                {status === 'success' ? 'Email verified' : 'Verify your email'}
              </h1>
            </div>

            {status === 'verifying' ? (
              <p className="text-center text-sm text-ink-muted">Confirming your email…</p>
            ) : null}

            {status === 'success' ? (
              <motion.div
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: DURATION.base, ease: EASE.out }}
                className="space-y-5"
              >
                <div
                  role="status"
                  className="flex items-start gap-3 rounded-xl border border-success-200 bg-success-50/70 px-3.5 py-3 text-sm text-success-700"
                >
                  {/* Celebrate the confirmation with a small bouncy pop on the check. */}
                  <motion.svg
                    variants={successPop}
                    initial="hidden"
                    animate="show"
                    viewBox="0 0 20 20"
                    className="mt-0.5 h-4 w-4 shrink-0 fill-success-600"
                    aria-hidden="true"
                  >
                    <path d="M10 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16Zm-1-5.6 5-5L12.6 6 9 9.6 7.4 8 6 9.4z" />
                  </motion.svg>
                  <span>Your email address has been confirmed. Thank you!</span>
                </div>
                <Button size="lg" className="w-full" onClick={continueTo}>
                  Continue
                </Button>
              </motion.div>
            ) : null}

            {status === 'error' || status === 'missing' ? (
              <div className="space-y-5">
                <div
                  role="alert"
                  className="rounded-xl border border-danger-200 bg-danger-50/70 px-3.5 py-2.5 text-sm text-danger-700"
                >
                  {status === 'missing'
                    ? 'This verification link is missing its token. Please use the link from your email.'
                    : (error ?? 'This verification link is invalid or has expired.')}
                </div>
                <div className="text-center text-xs text-ink-muted">
                  <Link
                    to={ready && user ? '/' : '/login'}
                    className="font-medium text-indigo-600 transition-colors hover:text-indigo-700"
                  >
                    {ready && user ? 'Back to dashboard' : 'Back to sign in'}
                  </Link>
                </div>
              </div>
            ) : null}
          </CardBody>
        </Card>
      </motion.div>
    </div>
  );
}
