import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '@/lib/auth';
import { apiErrorMessage } from '@/lib/api';
import { Button, Field, Input } from '@/components/ui';
import { SsoButtons } from '@/components/SsoButtons';
import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { fadeRise, staggerContainer, DURATION, EASE } from '@/lib/motion';

const schema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
});
type FormValues = z.infer<typeof schema>;

const mfaSchema = z.object({
  code: z.string().regex(/^\d{6}$/, 'Enter the 6-digit code from your authenticator app'),
});
type MfaValues = z.infer<typeof mfaSchema>;

const HIGHLIGHTS = [
  'Issue offline-verified licenses in a click',
  'Govern customers, subscriptions & keys',
  'Audit every action with confidence',
];

export function LoginPage() {
  const { user, ready, login, completeMfa } = useAuth();
  const navigate = useNavigate();
  const loc = useLocation();
  const [searchParams] = useSearchParams();
  const [error, setError] = useState<string | null>(null);
  // When MFA is required after step 1 we hold the signed challenge and prompt for a TOTP code.
  const [challenge, setChallenge] = useState<string | null>(null);

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', password: '' },
  });
  const mfaForm = useForm<MfaValues>({
    resolver: zodResolver(mfaSchema),
    defaultValues: { code: '' },
  });

  // Surface an SSO failure redirected back from the IdP callback. On success the backend sets the
  // cp_session cookie and redirects to the app root, where the cookie-based /me bootstrap signs the
  // user in automatically (no token handling needed here).
  useEffect(() => {
    const ssoStatus = searchParams.get('sso');
    if (!ssoStatus || ssoStatus === 'success') return;
    if (ssoStatus === 'unverified') {
      setError('Your identity provider did not assert a verified email. Contact your administrator.');
    } else if (ssoStatus === 'domain') {
      setError('Your email domain is not allowed for SSO sign-in to this organization.');
    } else {
      setError('Single sign-on failed. Please try again or use your password.');
    }
  }, [searchParams]);

  if (ready && user) {
    const from = (loc.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const goAfterLogin = () => {
    const from = (loc.state as { from?: string } | null)?.from ?? '/';
    navigate(from, { replace: true });
  };

  const onSubmit = form.handleSubmit(async (values) => {
    setError(null);
    try {
      const res = await login(values.email, values.password);
      if (res.mfaRequired) {
        setChallenge(res.challenge);
        mfaForm.reset({ code: '' });
        return;
      }
      goAfterLogin();
    } catch (e) {
      setError(apiErrorMessage(e));
    }
  });

  const onSubmitMfa = mfaForm.handleSubmit(async (values) => {
    setError(null);
    if (!challenge) {
      setError('Your login session expired. Please sign in again.');
      return;
    }
    try {
      await completeMfa(challenge, values.code);
      goAfterLogin();
    } catch (e) {
      setError(apiErrorMessage(e));
    }
  });

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10">
      <motion.div
        variants={staggerContainer}
        initial="hidden"
        animate="show"
        className="grid w-full max-w-5xl overflow-hidden rounded-3xl border border-white/60 bg-white/60 shadow-glass-xl ring-1 ring-slate-900/5 backdrop-blur-glass backdrop-saturate-150 lg:grid-cols-2"
      >
        {/* Brand showcase pane — first-impression hero. Decorative; hidden on small screens. */}
        <motion.aside
          variants={fadeRise}
          className="relative hidden overflow-hidden bg-aurora-primary p-10 text-white lg:flex lg:flex-col lg:justify-between"
          aria-hidden="true"
        >
          {/* Soft luminous orbs layered over the gradient for depth. */}
          <div className="pointer-events-none absolute -right-16 -top-16 h-64 w-64 rounded-full bg-cyan-400/30 blur-3xl motion-safe:animate-float" />
          <div className="pointer-events-none absolute -bottom-20 -left-10 h-72 w-72 rounded-full bg-violet-500/40 blur-3xl" />
          <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top_left,rgba(255,255,255,0.18),transparent_55%)]" />

          <div className="relative">
            <div className="grid h-12 w-12 place-items-center rounded-2xl bg-white/15 text-lg font-bold ring-1 ring-white/30 backdrop-blur">
              KF
            </div>
            <h2 className="mt-8 font-display text-3xl font-semibold leading-tight tracking-tight">
              Keyforge
            </h2>
            <p className="mt-3 max-w-xs text-sm leading-relaxed text-white/80">
              The command center for your licensing platform.
            </p>
          </div>

          <ul className="relative mt-10 space-y-4">
            {HIGHLIGHTS.map((item) => (
              <li key={item} className="flex items-start gap-3 text-sm text-white/90">
                <span className="mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full bg-white/20 ring-1 ring-white/30">
                  <svg viewBox="0 0 20 20" className="h-3 w-3 fill-white" aria-hidden="true">
                    <path d="M7.6 13.2 4.4 10l-1.1 1.1 4.3 4.3 9-9L15.5 5.3z" />
                  </svg>
                </span>
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </motion.aside>

        {/* Auth form pane */}
        <motion.div variants={fadeRise} className="bg-white/80 p-8 backdrop-blur-glass sm:p-10">
          <div className="mx-auto w-full max-w-sm">
            <div className="mb-7 text-center lg:text-left">
              {/* Mobile-only brand chip (the showcase pane is hidden under lg). */}
              <div className="mx-auto mb-4 grid h-11 w-11 place-items-center rounded-xl bg-aurora-primary text-base font-bold text-white shadow-glow lg:hidden">
                KF
              </div>
              <h1 className="font-display text-2xl font-semibold tracking-tight text-ink">
                {challenge ? 'Two-factor authentication' : 'Welcome back'}
              </h1>
              <p className="mt-1.5 text-sm text-ink-muted">
                {challenge
                  ? 'Enter the 6-digit code from your authenticator app.'
                  : 'Sign in to administer customers, subscriptions, and licenses.'}
              </p>
            </div>

            {error ? (
              <motion.div
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: DURATION.fast, ease: EASE.out }}
                role="alert"
                className="mb-5 rounded-xl border border-danger-200 bg-danger-50/70 px-3.5 py-2.5 text-sm text-danger-700"
              >
                {error}
              </motion.div>
            ) : null}

            {challenge ? (
              <form onSubmit={onSubmitMfa} className="space-y-4">
                {/* Cascade the MFA field + actions in. Anchored to this branch's mount, so it
                    plays once when the two-factor step appears (not on every render). */}
                <motion.div
                  variants={staggerContainer}
                  initial="hidden"
                  animate="show"
                  className="space-y-4"
                >
                  <motion.div variants={fadeRise}>
                    <Field
                      label="Authentication code"
                      htmlFor="mfa-code"
                      required
                      error={mfaForm.formState.errors.code?.message}
                    >
                      <Input
                        id="mfa-code"
                        inputMode="numeric"
                        autoComplete="one-time-code"
                        maxLength={6}
                        placeholder="123456"
                        className="text-center text-lg tracking-[0.5em] tabular-nums"
                        invalid={!!mfaForm.formState.errors.code}
                        {...mfaForm.register('code')}
                      />
                    </Field>
                  </motion.div>
                  <motion.div variants={fadeRise}>
                    <Button type="submit" size="lg" className="w-full" loading={mfaForm.formState.isSubmitting}>
                      Verify and sign in
                    </Button>
                  </motion.div>
                  <motion.button
                    variants={fadeRise}
                    type="button"
                    className="block w-full rounded-md py-1 text-center text-xs font-medium text-ink-muted transition-colors hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
                    onClick={() => {
                      setChallenge(null);
                      setError(null);
                    }}
                  >
                    Back to sign in
                  </motion.button>
                </motion.div>
              </form>
            ) : (
              <form onSubmit={onSubmit} className="space-y-4">
                {/* Cascade the credential fields in. Anchored to mount so a re-render does not replay. */}
                <motion.div
                  variants={staggerContainer}
                  initial="hidden"
                  animate="show"
                  className="space-y-4"
                >
                  <motion.div variants={fadeRise}>
                    <Field
                      label="Email"
                      htmlFor="email"
                      required
                      error={form.formState.errors.email?.message}
                    >
                      <Input
                        id="email"
                        type="email"
                        autoComplete="email"
                        invalid={!!form.formState.errors.email}
                        {...form.register('email')}
                      />
                    </Field>
                  </motion.div>
                  <motion.div variants={fadeRise}>
                    <Field
                      label="Password"
                      htmlFor="password"
                      required
                      error={form.formState.errors.password?.message}
                      hint={
                        <Link
                          to="/password-reset/request"
                          className="font-medium text-indigo-600 transition-colors hover:text-indigo-700"
                        >
                          Forgot?
                        </Link>
                      }
                    >
                      <Input
                        id="password"
                        type="password"
                        autoComplete="current-password"
                        invalid={!!form.formState.errors.password}
                        {...form.register('password')}
                      />
                    </Field>
                  </motion.div>
                  <motion.div variants={fadeRise}>
                    <Button type="submit" size="lg" className="w-full" loading={form.formState.isSubmitting}>
                      Sign in
                    </Button>
                  </motion.div>
                </motion.div>
              </form>
            )}

            {!challenge ? (
              <>
                <p className="mt-6 text-center text-sm text-ink-muted">
                  New to Keyforge?{' '}
                  <Link
                    to="/signup"
                    className="font-medium text-indigo-600 transition-colors hover:text-indigo-700"
                  >
                    Create an account
                  </Link>
                </p>
                <SsoButtons />
              </>
            ) : null}
          </div>
        </motion.div>
      </motion.div>
    </div>
  );
}
