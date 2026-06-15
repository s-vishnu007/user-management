import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { useAuth } from '@/lib/auth';
import { cn } from '@/lib/cn';
import { pageTransition } from '@/styles/motion';
import { Button } from './ui/Button';

interface NavItem {
  to: string;
  label: string;
  permission?: string;
  icon: string;
}

const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: 'D' },
  { to: '/orgs', label: 'Organizations', icon: 'O', permission: 'org.read' },
  { to: '/plans', label: 'Plans', icon: 'P', permission: 'plan.read' },
  { to: '/licenses', label: 'Licenses', icon: 'L', permission: 'license.read' },
  { to: '/keys', label: 'Signing keys', icon: 'K', permission: 'key.read' },
  { to: '/audit', label: 'Audit log', icon: 'A', permission: 'audit.read' },
];

/**
 * Decorative line-icons keyed off the existing NAV `icon` letter so the NAV
 * contract (to/label/permission/icon) is untouched. Falls back to the letter
 * glyph for any unmapped key. aria-hidden — the label carries the meaning.
 */
const NAV_ICONS: Record<string, ReactNode> = {
  D: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <rect x="3" y="3" width="7" height="9" rx="1.5" />
      <rect x="14" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="12" width="7" height="9" rx="1.5" />
      <rect x="3" y="16" width="7" height="5" rx="1.5" />
    </svg>
  ),
  O: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <path d="M3 21h18" />
      <path d="M5 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16" />
      <path d="M15 9h2a2 2 0 0 1 2 2v10" />
      <path d="M8 7h2M8 11h2M8 15h2" strokeLinecap="round" />
    </svg>
  ),
  P: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <path d="M4 5a2 2 0 0 1 2-2h8l6 6v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z" />
      <path d="M14 3v6h6" />
      <path d="M9 13h6M9 17h4" strokeLinecap="round" />
    </svg>
  ),
  L: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <path d="M9 12.5 11 14.5 15.5 10" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12 3 4 6v6c0 4.5 3.4 7.6 8 9 4.6-1.4 8-4.5 8-9V6z" strokeLinejoin="round" />
    </svg>
  ),
  K: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <circle cx="8" cy="8" r="4" />
      <path d="m11 11 9 9" strokeLinecap="round" />
      <path d="m16 16 2-2M19 19l2-2" strokeLinecap="round" />
    </svg>
  ),
  A: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} aria-hidden="true">
      <path d="M4 4h12l4 4v12a0 0 0 0 1 0 0H4z" strokeLinejoin="round" />
      <path d="M16 4v4h4" strokeLinejoin="round" />
      <path d="M8 12h8M8 16h5" strokeLinecap="round" />
    </svg>
  ),
};

function NavIcon({ item }: { item: NavItem }) {
  return (
    <span
      className="grid h-5 w-5 shrink-0 place-items-center text-current [&>svg]:h-[18px] [&>svg]:w-[18px]"
      aria-hidden="true"
    >
      {NAV_ICONS[item.icon] ?? (
        <span className="text-[10px] font-bold">{item.icon}</span>
      )}
    </span>
  );
}

export function AppShell() {
  const { user, logout, hasPermission } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const visibleNav = NAV.filter((n) => !n.permission || hasPermission(n.permission));

  // Presentation-only mobile nav disclosure. Does not touch NAV / visibleNav /
  // NavLink targets / onLogout — it only reveals the same links below `md`.
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  // Close the drawer whenever the route changes (e.g. a NavLink was followed).
  useEffect(() => {
    setMobileNavOpen(false);
  }, [location.pathname]);

  // Escape closes the drawer.
  useEffect(() => {
    if (!mobileNavOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMobileNavOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [mobileNavOpen]);

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  const userLabel = user?.fullName ?? user?.email;
  const initial = (user?.fullName ?? user?.email ?? '?').trim().charAt(0).toUpperCase() || '?';

  return (
    <div className="flex min-h-screen">
      <aside className="hidden md:flex md:w-64 md:flex-col glass-nav border-r border-white/60">
        <div className="flex h-14 items-center gap-2.5 border-b border-white/50 px-5">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-aurora-primary text-sm font-bold text-white shadow-glow">
            CP
          </div>
          <span className="font-display font-semibold tracking-tight text-ink">Control Panel</span>
        </div>
        <nav className="flex-1 space-y-1 p-3" aria-label="Primary">
          {visibleNav.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end={n.to === '/'}
              className={({ isActive }) =>
                cn(
                  'group relative flex items-center gap-3 rounded-xl px-3 py-2 text-sm font-medium transition-all duration-fast',
                  isActive
                    ? 'bg-gradient-to-r from-indigo-50 to-violet-50 text-indigo-700 shadow-glass-sm ring-1 ring-indigo-100/70'
                    : 'text-ink-muted hover:bg-white/70 hover:text-ink',
                )
              }
            >
              {({ isActive }) => (
                <>
                  <span
                    aria-hidden="true"
                    className={cn(
                      'absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-aurora-primary transition-all duration-base ease-out-quint',
                      isActive ? 'opacity-100' : 'opacity-0',
                    )}
                  />
                  <NavIcon item={n} />
                  <span className="truncate">{n.label}</span>
                </>
              )}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-white/50 p-3">
          <div className="mb-2 flex items-center gap-2.5 rounded-xl px-2 py-1.5">
            <div className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-aurora-chip text-xs font-semibold text-indigo-700 ring-1 ring-indigo-100">
              {initial}
            </div>
            <div className="min-w-0">
              {user?.fullName ? (
                <div className="truncate text-sm font-medium text-ink">{user.fullName}</div>
              ) : null}
              <div className="truncate text-xs text-ink-muted">{user?.email}</div>
            </div>
          </div>
          <Button variant="outline" size="sm" className="w-full" onClick={onLogout}>
            Sign out
          </Button>
        </div>
      </aside>
      <div className="flex flex-1 flex-col">
        <header className="sticky top-0 z-20 flex h-14 items-center justify-between glass-sticky border-b border-white/60 px-5 md:px-8">
          <div className="flex items-center gap-2.5 md:hidden">
            <button
              type="button"
              onClick={() => setMobileNavOpen((o) => !o)}
              aria-label={mobileNavOpen ? 'Close navigation menu' : 'Open navigation menu'}
              aria-expanded={mobileNavOpen}
              aria-controls="mobile-nav"
              className="grid h-9 w-9 place-items-center rounded-lg text-ink-muted transition-colors hover:bg-white/70 hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
            >
              <svg
                viewBox="0 0 24 24"
                className="h-5 w-5"
                fill="none"
                stroke="currentColor"
                strokeWidth={1.75}
                strokeLinecap="round"
                aria-hidden="true"
              >
                <path d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>
            <div className="grid h-7 w-7 place-items-center rounded-lg bg-aurora-primary text-xs font-bold text-white shadow-glow">
              CP
            </div>
            <span className="font-display font-semibold tracking-tight text-ink">Control Panel</span>
          </div>
          <div className="ml-auto flex items-center gap-3">
            <span className="hidden items-center gap-2 text-sm text-ink-muted md:inline-flex">
              <span
                aria-hidden="true"
                className="grid h-7 w-7 place-items-center rounded-full bg-aurora-chip text-xs font-semibold text-indigo-700 ring-1 ring-indigo-100"
              >
                {initial}
              </span>
              <span className="max-w-[14rem] truncate">{userLabel}</span>
            </span>
            <Button variant="ghost" size="sm" className="md:hidden" onClick={onLogout}>
              Sign out
            </Button>
          </div>
        </header>

        {/* Mobile navigation drawer — reveals the SAME visibleNav links below `md`.
            Hidden on desktop; the existing aside handles >=md. */}
        <AnimatePresence>
          {mobileNavOpen ? (
            <div className="md:hidden">
              <motion.div
                className="fixed inset-0 z-30 bg-slate-900/40"
                onClick={() => setMobileNavOpen(false)}
                aria-hidden="true"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.15 }}
              />
              <motion.div
                id="mobile-nav"
                className="fixed inset-y-0 left-0 z-40 flex w-72 max-w-[85vw] flex-col glass-nav border-r border-white/60 shadow-glass-xl"
                role="dialog"
                aria-modal="true"
                aria-label="Navigation"
                initial={{ x: '-100%' }}
                animate={{ x: 0 }}
                exit={{ x: '-100%' }}
                transition={{ type: 'spring', stiffness: 400, damping: 38 }}
              >
                <div className="flex h-14 items-center justify-between border-b border-white/50 px-4">
                  <div className="flex items-center gap-2.5">
                    <div className="grid h-8 w-8 place-items-center rounded-lg bg-aurora-primary text-sm font-bold text-white shadow-glow">
                      CP
                    </div>
                    <span className="font-display font-semibold tracking-tight text-ink">
                      Control Panel
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => setMobileNavOpen(false)}
                    aria-label="Close navigation menu"
                    className="grid h-9 w-9 place-items-center rounded-lg text-ink-muted transition-colors hover:bg-white/70 hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-white"
                  >
                    <svg
                      viewBox="0 0 24 24"
                      className="h-5 w-5"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={1.75}
                      strokeLinecap="round"
                      aria-hidden="true"
                    >
                      <path d="m6 6 12 12M18 6 6 18" />
                    </svg>
                  </button>
                </div>
                <nav className="flex-1 space-y-1 overflow-y-auto p-3" aria-label="Primary">
                  {visibleNav.map((n) => (
                    <NavLink
                      key={n.to}
                      to={n.to}
                      end={n.to === '/'}
                      onClick={() => setMobileNavOpen(false)}
                      className={({ isActive }) =>
                        cn(
                          'group relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-fast',
                          isActive
                            ? 'bg-gradient-to-r from-indigo-50 to-violet-50 text-indigo-700 shadow-glass-sm ring-1 ring-indigo-100/70'
                            : 'text-ink-muted hover:bg-white/70 hover:text-ink',
                        )
                      }
                    >
                      {({ isActive }) => (
                        <>
                          <span
                            aria-hidden="true"
                            className={cn(
                              'absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-aurora-primary transition-opacity duration-base ease-out-quint',
                              isActive ? 'opacity-100' : 'opacity-0',
                            )}
                          />
                          <NavIcon item={n} />
                          <span className="truncate">{n.label}</span>
                        </>
                      )}
                    </NavLink>
                  ))}
                </nav>
                <div className="border-t border-white/50 p-3">
                  <div className="mb-2 flex items-center gap-2.5 rounded-xl px-2 py-1.5">
                    <div className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-aurora-chip text-xs font-semibold text-indigo-700 ring-1 ring-indigo-100">
                      {initial}
                    </div>
                    <div className="min-w-0">
                      {user?.fullName ? (
                        <div className="truncate text-sm font-medium text-ink">{user.fullName}</div>
                      ) : null}
                      <div className="truncate text-xs text-ink-muted">{user?.email}</div>
                    </div>
                  </div>
                  <Button variant="outline" size="sm" className="w-full" onClick={onLogout}>
                    Sign out
                  </Button>
                </div>
              </motion.div>
            </div>
          ) : null}
        </AnimatePresence>

        <main className="flex-1 px-5 py-6 md:px-8 md:py-8">
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={location.pathname}
              initial={pageTransition.initial}
              animate={pageTransition.animate}
              exit={pageTransition.exit}
              transition={pageTransition.transition}
            >
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </main>
      </div>
    </div>
  );
}
