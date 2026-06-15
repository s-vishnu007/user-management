import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { MotionConfig } from 'framer-motion';
// Self-hosted variable fonts (Aurora Glass). Presentation-only; no contract impact.
import '@fontsource-variable/inter';
import '@fontsource-variable/sora';
import { App } from './App';
import { queryClient } from './lib/queryClient';
import { AuthProvider } from './lib/auth';
import { ToastProvider } from './lib/toast';
import { ErrorBoundary } from './components/ErrorBoundary';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <QueryClientProvider client={queryClient}>
          <ToastProvider>
            <AuthProvider>
              {/* reducedMotion="user" → framer respects OS Reduced Motion globally. */}
              <MotionConfig reducedMotion="user">
                <App />
              </MotionConfig>
            </AuthProvider>
          </ToastProvider>
        </QueryClientProvider>
      </BrowserRouter>
    </ErrorBoundary>
  </React.StrictMode>,
);
