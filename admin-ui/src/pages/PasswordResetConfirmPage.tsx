import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useState } from 'react';
import { auth } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api';
import { Button, Card, CardBody, Field, Input } from '@/components/ui';
import { motion } from 'framer-motion';
import { fadeRise, DURATION, EASE } from '@/lib/motion';

const schema = z
  .object({
    newPassword: z.string().min(10, 'Password must be at least 10 characters'),
    confirm: z.string(),
  })
  .refine((d) => d.newPassword === d.confirm, {
    path: ['confirm'],
    message: 'Passwords do not match',
  });
type Values = z.infer<typeof schema>;

export function PasswordResetConfirmPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? undefined;
  const navigate = useNavigate();
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { newPassword: '', confirm: '' } });
  const [error, setError] = useState<string | null>(null);

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    if (!token) {
      setError('Missing reset token');
      return;
    }
    try {
      await auth.confirmPasswordReset(token, v.newPassword);
      navigate('/login', { replace: true });
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
                CP
              </div>
              <h1 className="font-display text-2xl font-semibold tracking-tight text-ink">
                Choose a new password
              </h1>
              <p className="mt-1.5 text-sm text-ink-muted">Set a strong password for your account.</p>
            </div>
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
              <Field
                label="New password"
                htmlFor="newPassword"
                required
                error={form.formState.errors.newPassword?.message}
              >
                <Input
                  id="newPassword"
                  type="password"
                  invalid={!!form.formState.errors.newPassword}
                  {...form.register('newPassword')}
                />
              </Field>
              <Field
                label="Confirm password"
                htmlFor="confirm"
                required
                error={form.formState.errors.confirm?.message}
              >
                <Input
                  id="confirm"
                  type="password"
                  invalid={!!form.formState.errors.confirm}
                  {...form.register('confirm')}
                />
              </Field>
              <Button type="submit" size="lg" className="w-full" loading={form.formState.isSubmitting}>
                Set new password
              </Button>
            </form>
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
