import { useRef } from 'react';
import { motion } from 'framer-motion';
import { SPRING } from '@/styles/motion';
import { cn } from '@/lib/cn';

export interface TabDef {
  id: string;
  label: string;
}

export function Tabs({
  tabs,
  active,
  onChange,
}: {
  tabs: TabDef[];
  active: string;
  onChange: (id: string) => void;
}) {
  // Refs to the tab buttons so arrow-key navigation can move DOM focus along
  // with the active selection (WAI-ARIA tabs roving-focus pattern).
  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({});

  const focusTab = (id: string) => {
    onChange(id);
    // Move focus to the newly-activated tab on the next frame so the button
    // (which becomes tabIndex 0) receives focus.
    requestAnimationFrame(() => tabRefs.current[id]?.focus());
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLButtonElement>, index: number) => {
    let nextIndex: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
      nextIndex = (index + 1) % tabs.length;
    } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
      nextIndex = (index - 1 + tabs.length) % tabs.length;
    } else if (e.key === 'Home') {
      nextIndex = 0;
    } else if (e.key === 'End') {
      nextIndex = tabs.length - 1;
    }
    if (nextIndex !== null) {
      e.preventDefault();
      focusTab(tabs[nextIndex].id);
    }
  };

  return (
    <div className="border-b border-slate-900/10">
      <nav className="-mb-px flex gap-4 overflow-x-auto" aria-label="Tabs" role="tablist">
        {tabs.map((t, index) => {
          const isActive = t.id === active;
          return (
            <button
              key={t.id}
              ref={(el) => {
                tabRefs.current[t.id] = el;
              }}
              type="button"
              role="tab"
              id={`tab-${t.id}`}
              aria-selected={isActive}
              aria-controls={`tabpanel-${t.id}`}
              // Roving tabindex: only the active tab is in the Tab order; the rest
              // are reached via arrow keys.
              tabIndex={isActive ? 0 : -1}
              onClick={() => onChange(t.id)}
              onKeyDown={(e) => onKeyDown(e, index)}
              aria-current={isActive ? 'page' : undefined}
              className={cn(
                'relative whitespace-nowrap px-1 py-3 text-sm font-medium transition-colors',
                isActive ? 'text-indigo-700' : 'text-ink-muted hover:text-ink',
              )}
            >
              {t.label}
              {isActive ? (
                <motion.span
                  layoutId="tabs-active-indicator"
                  className="absolute inset-x-0 -bottom-px h-0.5 rounded-full bg-aurora-primary"
                  transition={SPRING.gentle}
                />
              ) : (
                <span
                  aria-hidden="true"
                  className="absolute inset-x-0 -bottom-px h-0.5 rounded-full bg-transparent"
                />
              )}
            </button>
          );
        })}
      </nav>
    </div>
  );
}
