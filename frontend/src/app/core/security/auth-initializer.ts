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

const SETTINGS_TIMEOUT_MS = 10000;

export const BOOTSTRAP_QUERY_KEY = ['app-bootstrap'] as const;

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
      if (data) {
        // Seed the individual caches with the consolidated data
        queryClient.setQueryData(CURRENT_USER_QUERY_KEY, data.user);
        queryClient.setQueryData(PUBLIC_SETTINGS_QUERY_KEY, data.publicSettings);
        queryClient.setQueryData(MENU_COUNTS_QUERY_KEY, data.menuCounts);
      }

      if (authService.getInternalAccessToken()) {
        authService.initializeWebSocketConnection();
      }
      authInitService.markAsInitialized();
    };

    // If we already have an internal token, we don't want to block bootstrap.
    if (authService.getInternalAccessToken()) {
      finalizeAuth();

      // Fetch the bootstrap data in the background to refresh caches and check remote auth
      queryClient.fetchQuery(bootstrapOptions).then(data => {
        finalizeAuth(data);
        if (data?.publicSettings?.remoteAuthEnabled) {
          authService.remoteLogin().subscribe({
            error: err => console.error('[Remote Login] background refresh failed:', err)
          });
        }
      }).catch(err => {
        console.error('[Bootstrap] background refresh failed:', err);
      });

      return Promise.resolve();
    }

    const settingsPromise = queryClient.fetchQuery(bootstrapOptions);
    const timeoutPromise = new Promise<null>(resolve =>
      setTimeout(() => resolve(null), SETTINGS_TIMEOUT_MS)
    );

    return Promise.race([settingsPromise, timeoutPromise]).then(data => {
      if (!data) {
        console.warn('[Auth] Bootstrap fetch timed out, falling back to local auth');
        finalizeAuth();
        return;
      }

      if (data.publicSettings.remoteAuthEnabled) {
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
