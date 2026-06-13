import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth';
import { cn } from '@/lib/cn';
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

export function AppShell() {
  const { user, logout, hasPermission } = useAuth();
  const navigate = useNavigate();

  const visibleNav = NAV.filter((n) => !n.permission || hasPermission(n.permission));

  const onLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="flex min-h-screen bg-slate-50">
      <aside className="hidden md:flex md:w-60 md:flex-col border-r border-slate-200 bg-white">
        <div className="flex h-14 items-center gap-2 border-b border-slate-100 px-5">
          <div className="grid h-8 w-8 place-items-center rounded-md bg-brand-600 text-sm font-bold text-white">
            CP
          </div>
          <span className="font-semibold text-slate-800">Control Panel</span>
        </div>
        <nav className="flex-1 space-y-0.5 p-3">
          {visibleNav.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end={n.to === '/'}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium',
                  isActive
                    ? 'bg-brand-50 text-brand-700'
                    : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
                )
              }
            >
              <span
                className="grid h-5 w-5 place-items-center rounded bg-slate-100 text-[10px] font-bold text-slate-600"
                aria-hidden="true"
              >
                {n.icon}
              </span>
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-slate-100 p-3">
          <div className="mb-2 truncate text-xs text-slate-500">{user?.email}</div>
          <Button variant="outline" size="sm" className="w-full" onClick={onLogout}>
            Sign out
          </Button>
        </div>
      </aside>
      <div className="flex flex-1 flex-col">
        <header className="flex h-14 items-center justify-between border-b border-slate-200 bg-white px-5 md:px-8">
          <div className="md:hidden font-semibold text-slate-800">Control Panel</div>
          <div className="ml-auto flex items-center gap-3">
            <span className="hidden text-sm text-slate-500 md:inline">
              {user?.fullName ?? user?.email}
            </span>
            <Button variant="ghost" size="sm" className="md:hidden" onClick={onLogout}>
              Sign out
            </Button>
          </div>
        </header>
        <main className="flex-1 px-5 py-6 md:px-8 md:py-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
