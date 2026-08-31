import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import './i18n';
import './styles.css';
import { App } from './App';
import { AuthProvider } from './auth/AuthContext';
import { ApiError } from './api/client';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Stock figures go stale the moment anyone else moves anything, and PWA guidance in
      // architecture 6.3 is explicit that stock levels must never be served from a cache.
      // Refetching on focus is the cheap version of that rule.
      refetchOnWindowFocus: true,
      staleTime: 10_000,
      retry: (failureCount, error) => {
        // Retrying a 401, 403 or 409 just repeats a decision the server already made.
        if (error instanceof ApiError && error.status < 500) return false;
        return failureCount < 2;
      },
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
