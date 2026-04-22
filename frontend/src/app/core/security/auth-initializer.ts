import {inject} from '@angular/core';
import {AuthService} from '../../shared/service/auth.service';
import {AppSettingsService} from '../../shared/service/app-settings.service';
import {AuthInitializationService} from './auth-initialization-service';
import {QueryClient} from '@tanstack/angular-query-experimental';

const SETTINGS_TIMEOUT_MS = 10000;

export function initializeAuthFactory() {
  return () => {
    const appSettingsService = inject(AppSettingsService);
    const authService = inject(AuthService);
    const authInitService = inject(AuthInitializationService);
    const queryClient = inject(QueryClient);

    const finalizeAuth = () => {
      if (authService.getInternalAccessToken()) {
        authService.initializeWebSocketConnection();
      }
      authInitService.markAsInitialized();
    };

    // If we already have an internal token, we don't want to block bootstrap on public-settings.
    // We can assume internal auth for now and let the settings fetch complete in the background.
    if (authService.getInternalAccessToken()) {
      finalizeAuth();

      // Still handle the settings result in the background to check for remote auth status
      queryClient.fetchQuery(appSettingsService.getPublicSettingsQueryOptions()).then(publicSettings => {
        if (publicSettings?.remoteAuthEnabled) {
          authService.remoteLogin().subscribe({
            error: err => console.error('[Remote Login] background refresh failed:', err)
          });
        }
      });

      return Promise.resolve();
    }

    const settingsPromise = queryClient.fetchQuery(appSettingsService.getPublicSettingsQueryOptions());

    const timeoutPromise = new Promise<null>(resolve =>
      setTimeout(() => resolve(null), SETTINGS_TIMEOUT_MS)
    );

    return Promise.race([settingsPromise, timeoutPromise]).then(publicSettings => {
      if (!publicSettings) {
        console.warn('[Auth] Public settings fetch timed out, falling back to local auth');
        finalizeAuth();
        return;
      }

      if (publicSettings.remoteAuthEnabled) {
        return new Promise<void>(resolve => {
          authService.remoteLogin().subscribe({
            next: () => {
              finalizeAuth();
              resolve();
            },
            error: err => {
              console.error('[Remote Login] failed:', err);
              finalizeAuth();
              resolve();
            }
          });
        });
      } else {
        finalizeAuth();
        return;
      }
    });
  };
}
