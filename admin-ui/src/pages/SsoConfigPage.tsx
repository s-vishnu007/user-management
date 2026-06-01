import { useEffect, useState } from 'react';
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
import type { SsoConfig } from '@/lib/types';

const schema = z.object({
  protocol: z.enum(['SAML', 'OIDC']),
  enabled: z.boolean(),
  metadataUrl: z.string().url().optional().or(z.literal('')),
  metadataXml: z.string().optional(),
  issuer: z.string().optional(),
  clientId: z.string().optional(),
  clientSecret: z.string().optional(),
  discoveryUrl: z.string().url().optional().or(z.literal('')),
});
type Values = z.infer<typeof schema>;

export function SsoConfigPage() {
  const { orgId = '' } = useParams<{ orgId: string }>();
  const qc = useQueryClient();
  const toast = useToast();
  const [protocol, setProtocol] = useState<'SAML' | 'OIDC'>('SAML');

  const cfgQ = useQuery({
    queryKey: ['org', orgId, 'sso'],
    queryFn: () => sso.get(orgId),
    enabled: !!orgId,
  });

  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      protocol: 'SAML',
      enabled: false,
      metadataUrl: '',
      metadataXml: '',
      issuer: '',
      clientId: '',
      clientSecret: '',
      discoveryUrl: '',
    },
  });

  useEffect(() => {
    if (cfgQ.data) {
      const d = cfgQ.data;
      setProtocol(d.protocol);
      form.reset({
        protocol: d.protocol,
        enabled: d.enabled,
        metadataUrl: d.metadataUrl ?? '',
        metadataXml: d.metadataXml ?? '',
        issuer: d.issuer ?? '',
        clientId: d.clientId ?? '',
        clientSecret: d.clientSecret ?? '',
        discoveryUrl: d.discoveryUrl ?? '',
      });
    }
  }, [cfgQ.data, form]);

  const saveMut = useMutation({
    mutationFn: (v: Values) =>
      sso.save(orgId, {
        orgId,
        protocol: v.protocol,
        enabled: v.enabled,
        metadataUrl: v.metadataUrl || undefined,
        metadataXml: v.metadataXml || undefined,
        issuer: v.issuer || undefined,
        clientId: v.clientId || undefined,
        clientSecret: v.clientSecret || undefined,
        discoveryUrl: v.discoveryUrl || undefined,
      } as SsoConfig),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['org', orgId, 'sso'] });
      toast.success('SSO configuration saved');
    },
    onError: (e) => toast.error(apiErrorMessage(e)),
  });

  if (cfgQ.isLoading) return <PageLoader />;

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
          <form
            onSubmit={form.handleSubmit((v) => saveMut.mutate({ ...v, protocol }))}
            className="space-y-4"
          >
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              <Field label="Protocol" htmlFor="protocol">
                <Select
                  id="protocol"
                  value={protocol}
                  onChange={(e) => {
                    const p = e.target.value as 'SAML' | 'OIDC';
                    setProtocol(p);
                    form.setValue('protocol', p);
                  }}
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
                  <Field label="Client secret" htmlFor="clientSecret">
                    <Input
                      id="clientSecret"
                      type="password"
                      {...form.register('clientSecret')}
                    />
                  </Field>
                </div>
              </>
            )}

            <Button type="submit" loading={saveMut.isPending}>
              Save configuration
            </Button>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}
