import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useState } from 'react';
import { auth } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api';
import { Button, Card, CardBody, Field, Input } from '@/components/ui';

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
  const { token } = useParams<{ token: string }>();
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
    <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <Card className="w-full max-w-md">
        <CardBody>
          <h1 className="mb-1 text-xl font-semibold text-slate-900">Choose a new password</h1>
          <p className="mb-5 text-sm text-slate-500">Set a strong password for your account.</p>
          <form onSubmit={onSubmit} className="space-y-4">
            {error ? (
              <div className="rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
                {error}
              </div>
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
            <Button type="submit" className="w-full" loading={form.formState.isSubmitting}>
              Set new password
            </Button>
          </form>
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
