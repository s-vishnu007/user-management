import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { apiErrorMessage, sso } from '@/lib/api';
import { PageHeader } from '@/components/PageHeader';
import {
  Button,
  Card,
  CardBody,
  CardHeader,
  Field,
  Input,
  PageLoader,
  Select,
  Textarea,
} from '@/components/ui';
import { useToast } from '@/lib/toast';
import type { SsoProviderConfig, SsoProviderView, SsoType } from '@/lib/types';

const schema = z.object({
  enabled: z.boolean(),
  metadataUrl: z.string().url().optional().or(z.literal('')),
  metadataXml: z.string().optional(),
  issuer: z.string().optional(),
  clientId: z.string().optional(),
  clientSecret: z.string().optional(),
  discoveryUrl: z.string().url().optional().or(z.literal('')),
  allowedEmailDomains: z.string().optional(),
});
type Values = z.infer<typeof schema>;

const EMPTY: Values = {
  enabled: false,
  metadataUrl: '',
  metadataXml: '',
  issuer: '',
  clientId: '',
  clientSecret: '',
  discoveryUrl: '',
  allowedEmailDomains: '',
};

function configToForm(p: SsoProviderView | undefined): Values {
  if (!p) return EMPTY;
  const c = p.config;
  return {
    enabled: p.enabled,
    metadataUrl: (c.metadataUrl as string) ?? '',
    metadataXml: (c.metadataXml as string) ?? '',
    issuer: (c.issuer as string) ?? '',
    clientId: (c.clientId as string) ?? '',
    clientSecret: '', // never echo back a stored secret
    discoveryUrl: (c.discoveryUrl as string) ?? '',
    allowedEmailDomains: (c.allowedEmailDomains as string) ?? '',
  };
}

export function SsoConfigPage() {
  const { orgId = '' } = useParams<{ orgId: string }>();
  const qc = useQueryClient();
  const toast = useToast();
  const [protocol, setProtocol] = useState<SsoType>('SAML');

  const providersQ = useQuery({
    queryKey: ['org', orgId, 'sso'],
    queryFn: () => sso.list(orgId),
    enabled: !!orgId,
  });

  // The org may have one provider per protocol; pick the one matching the selected protocol.
  const current = useMemo(
    () => (providersQ.data ?? []).find((p) => p.type === protocol),
    [providersQ.data, protocol],
  );

  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: EMPTY });

  useEffect(() => {
    form.reset(configToForm(current));
  }, [current, form]);

  const saveMut = useMutation({
    mutationFn: (v: Values) => {
      // Preserve any unknown keys already on the stored config, then overlay the typed fields.
      const config: SsoProviderConfig = { ...(current?.config ?? {}) };
      config.enabled = v.enabled;
      if (protocol === 'SAML') {
        config.metadataUrl = v.metadataUrl || undefined;
        config.metadataXml = v.metadataXml || undefined;
        delete config.issuer;
        delete config.clientId;
        delete config.discoveryUrl;
      } else {
        config.issuer = v.issuer || undefined;
        config.discoveryUrl = v.discoveryUrl || undefined;
        config.clientId = v.clientId || undefined;
        // Only send a client secret when the admin entered a new one (blank = leave unchanged).
        if (v.clientSecret) config.clientSecret = v.clientSecret;
        delete config.metadataUrl;
        delete config.metadataXml;
      }
      config.allowedEmailDomains = v.allowedEmailDomains || undefined;
      return sso.create(orgId, { type: protocol, config });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['org', orgId, 'sso'] });
      toast.success('SSO configuration saved');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  const testMut = useMutation({
    mutationFn: () => {
      if (!current) throw new Error('Save the provider before testing.');
      return sso.test(orgId, current.id);
    },
    onSuccess: (r) => {
      if (r.ok === false) toast.error(r.message ?? 'SSO test failed');
      else toast.success(r.message ?? 'SSO test succeeded');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  if (providersQ.isLoading) return <PageLoader />;

  return (
    <div>
      <PageHeader
        title="Single sign-on"
        description="Configure SAML 2.0 or OIDC for this organization. JIT provisioning supported."
        breadcrumb={
          <Link to={`/orgs/${orgId}`} className="hover:text-brand-700">
            Organization
          </Link>
        }
      />

      <Card>
        <CardHeader title="Identity provider" />
        <CardBody>
          <form onSubmit={form.handleSubmit((v) => saveMut.mutate(v))} className="space-y-4">
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              <Field label="Protocol" htmlFor="protocol">
                <Select
                  id="protocol"
                  value={protocol}
                  onChange={(e) => setProtocol(e.target.value as SsoType)}
                >
                  <option value="SAML">SAML 2.0</option>
                  <option value="OIDC">OIDC</option>
                </Select>
              </Field>
              <Field label="Enabled" htmlFor="enabled">
                <div className="flex items-center gap-2 pt-2">
                  <input
                    id="enabled"
                    type="checkbox"
                    className="accent-brand-600"
                    {...form.register('enabled')}
                  />
                  <span className="text-sm text-slate-600">Allow users to sign in via SSO</span>
                </div>
              </Field>
            </div>

            {protocol === 'SAML' ? (
              <>
                <Field
                  label="IdP metadata URL"
                  htmlFor="metadataUrl"
                  hint="Or paste XML below"
                  error={form.formState.errors.metadataUrl?.message}
                >
                  <Input
                    id="metadataUrl"
                    placeholder="https://idp.example.com/metadata"
                    {...form.register('metadataUrl')}
                  />
                </Field>
                <Field label="IdP metadata XML" htmlFor="metadataXml">
                  <Textarea
                    id="metadataXml"
                    rows={8}
                    placeholder="<EntityDescriptor ..."
                    {...form.register('metadataXml')}
                  />
                </Field>
              </>
            ) : (
              <>
                <Field label="Issuer" htmlFor="issuer">
                  <Input
                    id="issuer"
                    placeholder="https://idp.example.com"
                    {...form.register('issuer')}
                  />
                </Field>
                <Field
                  label="Discovery URL"
                  htmlFor="discoveryUrl"
                  error={form.formState.errors.discoveryUrl?.message}
                >
                  <Input
                    id="discoveryUrl"
                    placeholder="https://idp.example.com/.well-known/openid-configuration"
                    {...form.register('discoveryUrl')}
                  />
                </Field>
                <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                  <Field label="Client ID" htmlFor="clientId">
                    <Input id="clientId" {...form.register('clientId')} />
                  </Field>
                  <Field
                    label="Client secret"
                    htmlFor="clientSecret"
                    hint={current ? 'Leave blank to keep current' : undefined}
                  >
                    <Input
                      id="clientSecret"
                      type="password"
                      autoComplete="new-password"
                      placeholder={current ? '••••••••' : ''}
                      {...form.register('clientSecret')}
                    />
                  </Field>
                </div>
              </>
            )}

            <Field
              label="Allowed email domains (comma-separated)"
              htmlFor="allowedEmailDomains"
              hint="Required for JIT provisioning; blank denies JIT"
            >
              <Input
                id="allowedEmailDomains"
                placeholder="example.com, corp.example.com"
                {...form.register('allowedEmailDomains')}
              />
            </Field>

            <div className="flex items-center gap-2">
              <Button type="submit" loading={saveMut.isPending}>
                Save configuration
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={!current}
                loading={testMut.isPending}
                onClick={() => testMut.mutate()}
              >
                Test connection
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
