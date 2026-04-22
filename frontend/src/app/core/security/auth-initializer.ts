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

const SETTINGS_TIMEOUT_MS = 10000;

export const BOOTSTRAP_QUERY_KEY = ['app-bootstrap'] as const;
const SHELVES_QUERY_KEY = ['shelves'] as const;

export function initializeAuthFactory() {
  return () => {
    const http = inject(HttpClient);
    const authService = inject(AuthService);
    const authInitService = inject(AuthInitializationService);
    const queryClient = inject(QueryClient);

    const bootstrapOptions = queryOptions({
      queryKey: BOOTSTRAP_QUERY_KEY,
      queryFn: () => lastValueFrom(http.get<AppBootstrapResponse>(`${API_CONFIG.BASE_URL}/api/v1/app/bootstrap`)),
      staleTime: 5 * 60_000
    });

    const finalizeAuth = (data?: AppBootstrapResponse) => {
      console.log('[Auth] Finalizing auth with data:', data ? 'present' : 'missing');
      if (data) {
        console.log('[Auth] Seeding caches: user=', data.user?.username, 'libraries=', data.libraries?.length, 'shelves=', data.shelves?.length);
        // Seed the individual caches with the consolidated data
        queryClient.setQueryData(CURRENT_USER_QUERY_KEY, data.user);
        queryClient.setQueryData(PUBLIC_SETTINGS_QUERY_KEY, data.publicSettings);
        queryClient.setQueryData(MENU_COUNTS_QUERY_KEY, data.menuCounts);
        queryClient.setQueryData(LIBRARIES_QUERY_KEY, [...(data.libraries || [])].sort((a, b) => a.name.localeCompare(b.name)));
        queryClient.setQueryData(SHELVES_QUERY_KEY, data.shelves || []);
      }

      if (authService.getInternalAccessToken()) {
        console.log('[Auth] Found internal access token, initializing WS');
        authService.initializeWebSocketConnection();
      }
      authInitService.markAsInitialized();
    };

    console.log('[Auth] Starting consolidated bootstrap fetch...');
    // We MUST fetch bootstrap data before proceeding to ensure guards and components have settings/session state.
    const bootstrapPromise = queryClient.fetchQuery(bootstrapOptions);
    const timeoutPromise = new Promise<null>(resolve =>
      setTimeout(() => {
        console.warn('[Auth] Bootstrap fetch reached 10s timeout');
        resolve(null);
      }, SETTINGS_TIMEOUT_MS)
    );

    return Promise.race([bootstrapPromise, timeoutPromise])
      .catch(err => {
        console.error('[Auth] Bootstrap fetch failed:', err);
        return null;
      })
      .then(data => {
        if (!data) {
          console.warn('[Auth] Proceeding with limited state due to bootstrap failure/timeout');
          finalizeAuth();
          return;
        }

        console.log('[Auth] Bootstrap data received successfully');

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
