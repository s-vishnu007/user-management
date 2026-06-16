import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth';
import { apiErrorMessage } from '@/lib/api';
import { Button, Field, Input } from '@/components/ui';
import { SsoButtons } from '@/components/SsoButtons';
import { useState } from 'react';
import { motion } from 'framer-motion';
import { fadeRise, staggerContainer, DURATION, EASE } from '@/lib/motion';

// Password rules mirror the backend PasswordPolicy (min 12 + one of each class). The backend remains
// authoritative; this only gives fast, friendly client-side feedback.
const password = z
  .string()
  .min(12, 'Use at least 12 characters')
  .max(72, 'Use at most 72 characters')
  .regex(/[a-z]/, 'Add a lowercase letter')
  .regex(/[A-Z]/, 'Add an uppercase letter')
  .regex(/[0-9]/, 'Add a number')
  .regex(/[^A-Za-z0-9]/, 'Add a symbol');

const schema = z.object({
  fullName: z.string().trim().min(1, 'Your name is required').max(255),
  email: z.string().trim().email('Enter a valid email'),
  orgName: z.string().trim().min(1, 'Organization name is required').max(255),
  password,
});
type FormValues = z.infer<typeof schema>;

const HIGHLIGHTS = [
  'Spin up your organization in seconds',
  'Invite your team and assign roles',
  'Issue offline-verified licenses to your apps',
];

export function SignupPage() {
  const { user, ready, register } = useAuth();
  const navigate = useNavigate();
  const loc = useLocation();
  const [error, setError] = useState<string | null>(null);

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { fullName: '', email: '', orgName: '', password: '' },
  });

  // Already authenticated (e.g. navigated here with a live session) → bounce to the app.
  if (ready && user) {
    const from = (loc.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const onSubmit = form.handleSubmit(async (values) => {
    setError(null);
    try {
      // On success the backend sets the cp_session cookie and register() bootstraps identity; the
      // dashboard then shows a non-blocking "verify your email" banner.
      await register(values);
      navigate('/', { replace: true });
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
        {/* Brand showcase pane — decorative; hidden on small screens. */}
        <motion.aside
          variants={fadeRise}
          className="relative hidden overflow-hidden bg-aurora-primary p-10 text-white lg:flex lg:flex-col lg:justify-between"
          aria-hidden="true"
        >
          <div className="pointer-events-none absolute -right-16 -top-16 h-64 w-64 rounded-full bg-cyan-400/30 blur-3xl motion-safe:animate-float" />
          <div className="pointer-events-none absolute -bottom-20 -left-10 h-72 w-72 rounded-full bg-violet-500/40 blur-3xl" />
          <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top_left,rgba(255,255,255,0.18),transparent_55%)]" />

          <div className="relative">
            <div className="grid h-12 w-12 place-items-center rounded-2xl bg-white/15 text-lg font-bold ring-1 ring-white/30 backdrop-blur">
              KF
            </div>
            <h2 className="mt-8 font-display text-3xl font-semibold leading-tight tracking-tight">
              Create your Keyforge account
            </h2>
            <p className="mt-3 max-w-xs text-sm leading-relaxed text-white/80">
              Your organization, your licenses — set up in under a minute.
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

        {/* Signup form pane */}
        <motion.div variants={fadeRise} className="bg-white/80 p-8 backdrop-blur-glass sm:p-10">
          <div className="mx-auto w-full max-w-sm">
            <div className="mb-7 text-center lg:text-left">
              <div className="mx-auto mb-4 grid h-11 w-11 place-items-center rounded-xl bg-aurora-primary text-base font-bold text-white shadow-glow lg:hidden">
                KF
              </div>
              <h1 className="font-display text-2xl font-semibold tracking-tight text-ink">
                Create your account
              </h1>
              <p className="mt-1.5 text-sm text-ink-muted">
                You'll become the owner of a new organization.
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

            <form onSubmit={onSubmit} className="space-y-4">
              {/* Cascade the sign-up fields in. Anchored to mount so a re-render does not replay. */}
              <motion.div
                variants={staggerContainer}
                initial="hidden"
                animate="show"
                className="space-y-4"
              >
                <motion.div variants={fadeRise}>
                  <Field
                    label="Full name"
                    htmlFor="fullName"
                    required
                    error={form.formState.errors.fullName?.message}
                  >
                    <Input
                      id="fullName"
                      autoComplete="name"
                      invalid={!!form.formState.errors.fullName}
                      {...form.register('fullName')}
                    />
                  </Field>
                </motion.div>
                <motion.div variants={fadeRise}>
                  <Field
                    label="Work email"
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
                    label="Organization name"
                    htmlFor="orgName"
                    required
                    error={form.formState.errors.orgName?.message}
                  >
                    <Input
                      id="orgName"
                      autoComplete="organization"
                      placeholder="Your company"
                      invalid={!!form.formState.errors.orgName}
                      {...form.register('orgName')}
                    />
                  </Field>
                </motion.div>
                <motion.div variants={fadeRise}>
                  <Field
                    label="Password"
                    htmlFor="password"
                    required
                    error={form.formState.errors.password?.message}
                  >
                    <Input
                      id="password"
                      type="password"
                      autoComplete="new-password"
                      invalid={!!form.formState.errors.password}
                      {...form.register('password')}
                    />
                    <p className="mt-1.5 text-xs text-ink-muted">
                      At least 12 characters with an uppercase, lowercase, number, and symbol.
                    </p>
                  </Field>
                </motion.div>
                <motion.div variants={fadeRise}>
                  <Button type="submit" size="lg" className="w-full" loading={form.formState.isSubmitting}>
                    Create account
                  </Button>
                </motion.div>
              </motion.div>
            </form>

            <p className="mt-6 text-center text-sm text-ink-muted">
              Already have an account?{' '}
              <Link
                to="/login"
                className="font-medium text-indigo-600 transition-colors hover:text-indigo-700"
              >
                Sign in
              </Link>
            </p>

            <SsoButtons />
          </div>
        </motion.div>
      </motion.div>
    </div>
  );
}
