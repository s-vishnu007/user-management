import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link } from 'react-router-dom';
import { useState } from 'react';
import { auth } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api';
import { Button, Card, CardBody, Field, Input } from '@/components/ui';
import { motion } from 'framer-motion';
import { fadeRise, successPop, DURATION, EASE } from '@/lib/motion';

const schema = z.object({ email: z.string().email() });
type Values = z.infer<typeof schema>;

export function PasswordResetRequestPage() {
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { email: '' } });
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    try {
      await auth.requestPasswordReset(v.email);
      setDone(true);
    } catch (e) {
      setError(apiErrorMessage(e));
    }
  });

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10">
      <motion.div
        variants={fadeRise}
        initial="hidden"
        animate="show"
        className="w-full max-w-md"
      >
        <Card className="bg-white/80">
          <CardBody className="p-8 sm:p-10">
            <div className="mb-6 text-center">
              <div className="mx-auto mb-4 grid h-11 w-11 place-items-center rounded-xl bg-aurora-primary text-base font-bold text-white shadow-glow">
                KF
              </div>
              <h1 className="font-display text-2xl font-semibold tracking-tight text-ink">
                Reset your password
              </h1>
              <p className="mt-1.5 text-sm text-ink-muted">
                Enter your email and we'll send you a reset link.
              </p>
            </div>
            {done ? (
              <motion.div
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: DURATION.base, ease: EASE.out }}
                role="status"
                className="flex items-start gap-3 rounded-xl border border-success-200 bg-success-50/70 px-3.5 py-3 text-sm text-success-700"
              >
                {/* Small bouncy accent on the confirmation check. */}
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
                <span>If an account exists for that email, a reset link has been sent.</span>
              </motion.div>
            ) : (
              <form onSubmit={onSubmit} className="space-y-4">
                {error ? (
                  <motion.div
                    initial={{ opacity: 0, y: -4 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ duration: DURATION.fast, ease: EASE.out }}
                    role="alert"
                    className="rounded-xl border border-danger-200 bg-danger-50/70 px-3.5 py-2.5 text-sm text-danger-700"
                  >
                    {error}
                  </motion.div>
                ) : null}
                <Field label="Email" htmlFor="email" required error={form.formState.errors.email?.message}>
                  <Input
                    id="email"
                    type="email"
                    invalid={!!form.formState.errors.email}
                    {...form.register('email')}
                  />
                </Field>
                <Button type="submit" size="lg" className="w-full" loading={form.formState.isSubmitting}>
                  Send reset link
                </Button>
              </form>
            )}
            <div className="mt-6 text-center text-xs text-ink-muted">
              <Link
                to="/login"
                className="font-medium text-indigo-600 transition-colors hover:text-indigo-700"
              >
                Back to sign in
              </Link>
            </div>
          </CardBody>
        </Card>
      </motion.div>
    </div>
  );
}
