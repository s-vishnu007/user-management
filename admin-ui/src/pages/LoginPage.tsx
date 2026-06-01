import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth';
import { apiErrorMessage } from '@/lib/api';
import { Button, Card, CardBody, Field, Input } from '@/components/ui';
import { useState } from 'react';

const schema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
});
type FormValues = z.infer<typeof schema>;

export function LoginPage() {
  const { user, login, loading } = useAuth();
  const navigate = useNavigate();
  const loc = useLocation();
  const [error, setError] = useState<string | null>(null);
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', password: '' },
  });

  if (!loading && user) {
    const from = (loc.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const onSubmit = form.handleSubmit(async (values) => {
    setError(null);
    try {
      await login(values.email, values.password);
      const from = (loc.state as { from?: string } | null)?.from ?? '/';
      navigate(from, { replace: true });
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
            <h1 className="text-xl font-semibold text-slate-900">Sign in to Control Panel</h1>
            <p className="mt-1 text-sm text-slate-500">Administer customers, subscriptions, and licenses.</p>
          </div>
          {error ? (
            <div className="mb-4 rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
              {error}
            </div>
          ) : null}
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
        </CardBody>
      </Card>
    </div>
  );
}
