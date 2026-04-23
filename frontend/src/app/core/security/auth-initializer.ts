import {inject} from '@angular/core';
import {AuthService} from '../../shared/service/auth.service';
import {AuthInitializationService} from './auth-initialization-service';
import {QueryClient, queryOptions} from '@tanstack/angular-query-experimental';
import {HttpClient} from '@angular/common/http';
import {API_CONFIG} from '../config/api-config';
import {lastValueFrom} from 'rxjs';
import {AppBootstrapResponse} from '../../shared/models/app-bootstrap.model';
import {CURRENT_USER_QUERY_KEY} from '../../features/settings/user-management/user-query-keys';
import {PUBLIC_SETTINGS_QUERY_KEY} from '../../shared/service/app-settings-query-keys';
import {MENU_COUNTS_QUERY_KEY} from '../../shared/service/menu-counts.service';
import {LIBRARIES_QUERY_KEY} from '../../features/book/service/library-query-keys';
import {SHELVES_QUERY_KEY} from '../../features/book/service/shelf.service';

const SETTINGS_TIMEOUT_MS = 5000;

export const BOOTSTRAP_QUERY_KEY = ['app-bootstrap'] as const;

function shouldSkipBootstrapForRoute(): boolean {
  if (typeof globalThis.window === 'undefined') {
    return false;
  }

  const path = globalThis.window.location.pathname;
  return path === '/login' || path.startsWith('/login/') || path === '/setup' || path.startsWith('/setup/');
}

function warmDashboardLcpCandidate(token: string | null): void {
  if (!token || typeof globalThis.window === 'undefined') {
    return;
  }

  const path = globalThis.window.location.pathname;
  if (path !== '/dashboard' && !path.startsWith('/dashboard/')) {
    return;
  }

  const raw = globalThis.window.localStorage.getItem('lcp_book_candidate');
  if (!raw) {
    return;
  }

  try {
    const candidate = JSON.parse(raw) as {
      id?: number;
      updatedOn?: string | null;
      audioUpdatedOn?: string | null;
      isAudio?: boolean;
    };

    if (!candidate?.id) {
      return;
    }

    const updatedOn = candidate.isAudio ? candidate.audioUpdatedOn : candidate.updatedOn;
    if (!updatedOn) {
      return;
    }

    const endpoint = candidate.isAudio ? 'audiobook-thumbnail' : 'thumbnail';
    const url = `${API_CONFIG.BASE_URL}/api/v1/media/book/${candidate.id}/${endpoint}?${updatedOn}&w=240&token=${token}`;

    const link = globalThis.window.document.createElement('link');
    link.rel = 'preload';
    link.as = 'image';
    link.href = url;
    link.fetchPriority = 'high';
    globalThis.window.document.head.appendChild(link);

    void fetch(url, {
      credentials: 'include',
      cache: 'force-cache',
    }).catch(() => {});
  } catch {
    // ignore malformed candidate data
  }
}

export function initializeAuthFactory() {
  return () => {
    const http = inject(HttpClient);
    const authService = inject(AuthService);
    const authInitService = inject(AuthInitializationService);
    const queryClient = inject(QueryClient);

    if (shouldSkipBootstrapForRoute()) {
      authInitService.markAsInitialized();
      return Promise.resolve();
    }

    const bootstrapOptions = queryOptions({
      queryKey: BOOTSTRAP_QUERY_KEY,
      queryFn: () => lastValueFrom(http.get<AppBootstrapResponse>(`${API_CONFIG.BASE_URL}/api/v1/app/bootstrap`)),
      staleTime: 5 * 60_000
    });

    const seedBootstrapCaches = (data: AppBootstrapResponse) => {
      queryClient.setQueryData(CURRENT_USER_QUERY_KEY, data.user);
      queryClient.setQueryData(PUBLIC_SETTINGS_QUERY_KEY, data.publicSettings);
      queryClient.setQueryData(MENU_COUNTS_QUERY_KEY, data.menuCounts);
      queryClient.setQueryData(LIBRARIES_QUERY_KEY, [...(data.libraries || [])].sort((a, b) => a.name.localeCompare(b.name)));
      queryClient.setQueryData(SHELVES_QUERY_KEY, data.shelves || []);
    };

    const finalizeAuth = (data?: AppBootstrapResponse) => {
      if (data) {
        seedBootstrapCaches(data);
      }

      const accessToken = authService.getInternalAccessToken();
      warmDashboardLcpCandidate(accessToken);

      if (accessToken) {
        authService.initializeWebSocketConnection();
      }
      authInitService.markAsInitialized();
    };

    const existingToken = authService.getInternalAccessToken();
    if (existingToken) {
      // Do not block first paint for authenticated users; hydrate bootstrap data asynchronously.
      warmDashboardLcpCandidate(existingToken);
      authService.initializeWebSocketConnection();
      authInitService.markAsInitialized();

      const bootstrapPromise = queryClient.fetchQuery(bootstrapOptions);
      const timeoutPromise = new Promise<null>(resolve =>
        setTimeout(() => {
          console.warn('[Auth] Bootstrap fetch reached 5s timeout');
          resolve(null);
        }, SETTINGS_TIMEOUT_MS)
      );

      void Promise.race([bootstrapPromise, timeoutPromise]).then(data => {
        if (!data) {
          console.warn('[Auth] Proceeding with limited state due to bootstrap failure/timeout');
          return;
        }
        seedBootstrapCaches(data);
      });

      return Promise.resolve();
    }

    // We MUST fetch bootstrap data before proceeding to ensure guards and components have settings/session state.
    const bootstrapPromise = queryClient.fetchQuery(bootstrapOptions);
    const timeoutPromise = new Promise<null>(resolve =>
      setTimeout(() => {
        console.warn('[Auth] Bootstrap fetch reached 5s timeout');
        resolve(null);
      }, SETTINGS_TIMEOUT_MS)
    );

    return Promise.race([bootstrapPromise, timeoutPromise])
      .then(data => {
        if (!data) {
          console.warn('[Auth] Proceeding with limited state due to bootstrap failure/timeout');
          finalizeAuth();
          return;
        }

        // If we have a potential remote auth but no local token, try to login automatically
        if (data.publicSettings?.remoteAuthEnabled && !authService.getInternalAccessToken()) {
          return new Promise<void>(resolve => {
            authService.remoteLogin().subscribe({
              next: () => {
                finalizeAuth(data);
                resolve();
              },
              error: err => {
                console.error('[Remote Login] failed:', err);
                finalizeAuth(data);
                resolve();
              }
            });
          });
        } else {
          finalizeAuth(data);
          return;
        }
      });
  };
}
