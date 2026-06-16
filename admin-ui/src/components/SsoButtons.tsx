import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { auth } from '@/lib/api';
import { Button, Field, Input } from '@/components/ui';
import { DURATION, EASE } from '@/lib/motion';

/**
 * Single sign-on entry points for the auth screens (login + signup).
 *
 * Two flows are surfaced:
 *  - A global "Continue with Google" button, shown only when discovery reports
 *    `googleEnabled` (a global OIDC option independent of any org).
 *  - A "Sign in with SSO" button that reveals a work-email / org-slug field;
 *    on submit we resolve the org via discovery and, if it has at least one
 *    enabled provider, navigate the browser to the per-org SSO start URL.
 *
 * SSO start/Google URLs are plain browser navigations (302 → IdP), so we set
 * `window.location.href` rather than issuing an axios call. Discovery never
 * throws (the api helper swallows errors and returns null), and we additionally
 * guard every branch so this component can never crash the auth page.
 */
export function SsoButtons() {
  // Whether the global Google option is configured (learned on mount).
  const [googleEnabled, setGoogleEnabled] = useState(false);
  // Toggles the inline org/email field for the per-org SSO flow.
  const [showOrgField, setShowOrgField] = useState(false);
  const [orgQuery, setOrgQuery] = useState('');
  const [resolving, setResolving] = useState(false);
  // Inline, non-throwing feedback for the per-org lookup.
  const [message, setMessage] = useState<string | null>(null);

  // Learn the global Google flag once on mount. Pass '' to get the global-only
  // view; discovery returns null on error/unknown org, which we treat as "off".
  useEffect(() => {
    let active = true;
    auth
      .ssoDiscovery('')
      .then((res) => {
        if (active) setGoogleEnabled(!!res?.googleEnabled);
      })
      .catch(() => {
        // Defensive only — ssoDiscovery already swallows errors to null.
        if (active) setGoogleEnabled(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const onGoogle = () => {
    window.location.href = auth.ssoGoogleStartUrl();
  };

  const onRevealOrgField = () => {
    setShowOrgField(true);
    setMessage(null);
  };

  const resolveAndStart = async () => {
    const q = orgQuery.trim();
    if (!q) {
      setMessage('Enter your work email or organization to continue.');
      return;
    }
    setResolving(true);
    setMessage(null);
    try {
      const result = await auth.ssoDiscovery(q);
      if (result && result.orgSlug && result.providers.length > 0) {
        // Kick off the per-org flow with the first available provider.
        window.location.href = auth.ssoStartUrl(result.orgSlug, result.providers[0].id);
        return;
      }
      setMessage('No SSO is configured for that organization.');
    } catch {
      // Belt-and-suspenders: discovery returns null rather than throwing, but we
      // never want a rejected promise to surface as an unhandled crash.
      setMessage('We could not check SSO right now. Please try again.');
    } finally {
      setResolving(false);
    }
  };

  const onOrgKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      void resolveAndStart();
    }
  };

  return (
    <div className="mt-6">
      {/* Subtle "or" divider separating password auth from SSO. */}
      <div className="relative flex items-center" aria-hidden="true">
        <span className="h-px flex-1 bg-slate-200/80" />
        <span className="px-3 text-xs font-medium uppercase tracking-wide text-ink-faint">or</span>
        <span className="h-px flex-1 bg-slate-200/80" />
      </div>

      <div className="mt-5 space-y-2.5">
        {googleEnabled ? (
          <Button type="button" variant="outline" size="lg" className="w-full" onClick={onGoogle}>
            <GoogleGlyph />
            Continue with Google
          </Button>
        ) : null}

        <Button
          type="button"
          variant="outline"
          size="lg"
          className="w-full"
          onClick={onRevealOrgField}
          aria-expanded={showOrgField}
          aria-controls="sso-org-field"
        >
          Sign in with SSO
        </Button>
      </div>

      {showOrgField ? (
        <motion.div
          id="sso-org-field"
          initial={{ opacity: 0, y: -4 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: DURATION.fast, ease: EASE.out }}
          className="mt-3"
        >
          {/* Backend SSO discovery resolves by org slug only (no email->org mapping), so the field
              advertises the organization, not a work email, to match what actually works. */}
          <Field label="Organization" htmlFor="sso-org">
            <Input
              id="sso-org"
              autoFocus
              autoComplete="organization"
              placeholder="your-org"
              value={orgQuery}
              onChange={(e) => setOrgQuery(e.target.value)}
              onKeyDown={onOrgKeyDown}
              aria-describedby={message ? 'sso-message' : undefined}
            />
          </Field>
          <Button
            type="button"
            variant="primary"
            size="md"
            className="mt-2 w-full"
            loading={resolving}
            aria-busy={resolving}
            onClick={() => void resolveAndStart()}
          >
            Continue
          </Button>
          {message ? (
            <p id="sso-message" role="status" className="mt-2 text-center text-xs text-ink-muted">
              {message}
            </p>
          ) : null}
        </motion.div>
      ) : null}
    </div>
  );
}

/** Small inline Google "G" mark for the Continue with Google button. */
function GoogleGlyph() {
  return (
    <svg viewBox="0 0 18 18" className="h-4 w-4" aria-hidden="true" focusable="false">
      <path
        fill="#4285F4"
        d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62z"
      />
      <path
        fill="#34A853"
        d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.81.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z"
      />
      <path
        fill="#FBBC05"
        d="M3.97 10.72A5.41 5.41 0 0 1 3.68 9c0-.6.1-1.18.29-1.72V4.95H.96A9 9 0 0 0 0 9c0 1.45.35 2.82.96 4.05l3.01-2.33z"
      />
      <path
        fill="#EA4335"
        d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.47.89 11.43 0 9 0A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58z"
      />
    </svg>
  );
}
