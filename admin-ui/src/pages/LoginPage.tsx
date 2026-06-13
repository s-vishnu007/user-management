import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '@/lib/auth';
import { apiErrorMessage } from '@/lib/api';
import { Button, Card, CardBody, Field, Input } from '@/components/ui';
import { useEffect, useState } from 'react';

const schema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
});
type FormValues = z.infer<typeof schema>;

const mfaSchema = z.object({
  code: z.string().regex(/^\d{6}$/, 'Enter the 6-digit code from your authenticator app'),
});
type MfaValues = z.infer<typeof mfaSchema>;

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
    <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <Card className="w-full max-w-md">
        <CardBody>
          <div className="mb-6 text-center">
            <div className="mx-auto mb-3 grid h-12 w-12 place-items-center rounded-lg bg-brand-600 text-lg font-bold text-white">
              CP
            </div>
            <h1 className="text-xl font-semibold text-slate-900">
              {challenge ? 'Two-factor authentication' : 'Sign in to Control Panel'}
            </h1>
            <p className="mt-1 text-sm text-slate-500">
              {challenge
                ? 'Enter the 6-digit code from your authenticator app.'
                : 'Administer customers, subscriptions, and licenses.'}
            </p>
          </div>
          {error ? (
            <div className="mb-4 rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
              {error}
            </div>
          ) : null}

          {challenge ? (
            <form onSubmit={onSubmitMfa} className="space-y-4">
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
                  invalid={!!mfaForm.formState.errors.code}
                  {...mfaForm.register('code')}
                />
              </Field>
              <Button type="submit" className="w-full" loading={mfaForm.formState.isSubmitting}>
                Verify and sign in
              </Button>
              <button
                type="button"
                className="w-full text-center text-xs text-slate-500 hover:text-slate-700"
                onClick={() => {
                  setChallenge(null);
                  setError(null);
                }}
              >
                Back to sign in
              </button>
            </form>
          ) : (
            <form onSubmit={onSubmit} className="space-y-4">
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
              <Field
                label="Password"
                htmlFor="password"
                required
                error={form.formState.errors.password?.message}
                hint={
                  <Link to="/password-reset/request" className="text-brand-600 hover:text-brand-700">
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
              <Button type="submit" className="w-full" loading={form.formState.isSubmitting}>
                Sign in
              </Button>
            </form>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
