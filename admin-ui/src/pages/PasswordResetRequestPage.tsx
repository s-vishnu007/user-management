import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link } from 'react-router-dom';
import { useState } from 'react';
import { auth } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api';
import { Button, Card, CardBody, Field, Input } from '@/components/ui';

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
    <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <Card className="w-full max-w-md">
        <CardBody>
          <h1 className="mb-1 text-xl font-semibold text-slate-900">Reset your password</h1>
          <p className="mb-5 text-sm text-slate-500">
            Enter your email and we'll send you a reset link.
          </p>
          {done ? (
            <div className="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
              If an account exists for that email, a reset link has been sent.
            </div>
          ) : (
            <form onSubmit={onSubmit} className="space-y-4">
              {error ? (
                <div className="rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
                  {error}
                </div>
              ) : null}
              <Field label="Email" htmlFor="email" required error={form.formState.errors.email?.message}>
                <Input
                  id="email"
                  type="email"
                  invalid={!!form.formState.errors.email}
                  {...form.register('email')}
                />
              </Field>
              <Button type="submit" className="w-full" loading={form.formState.isSubmitting}>
                Send reset link
              </Button>
            </form>
          )}
          <div className="mt-5 text-center text-xs text-slate-500">
            <Link to="/login" className="text-brand-600 hover:text-brand-700">
              Back to sign in
            </Link>
          </div>
        </CardBody>
      </Card>
    </div>
  );
}
