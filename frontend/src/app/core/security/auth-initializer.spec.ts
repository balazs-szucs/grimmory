import {Injector, runInInjectionContext} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {of, throwError} from 'rxjs';
import {QueryClient} from '@tanstack/angular-query-experimental';

import {AuthInitializationService} from './auth-initialization-service';
import {AuthService} from '../../shared/service/auth.service';
import {initializeAuthFactory} from './auth-initializer';

describe('initializeAuthFactory', () => {
  const authInitService = {
    markAsInitialized: vi.fn(),
  };

  const authService = {
    getInternalAccessToken: vi.fn<() => string | null>(),
    remoteLogin: vi.fn(),
    initializeWebSocketConnection: vi.fn(),
  };

  const queryClient = {
    fetchQuery: vi.fn(),
    setQueryData: vi.fn(),
  };
  const bootstrapBase = {
    user: {username: 'admin'},
    publicSettings: {
      oidcEnabled: true,
      remoteAuthEnabled: false,
      oidcProviderDetails: null,
      oidcForceOnlyMode: false,
    },
    version: {current: 'v1.0.0', latest: 'v1.0.0'},
    menuCounts: {totalBooks: 0, byLibrary: {}, byShelf: {}, unshelvedBooks: 0, byMagicShelf: {}},
    libraries: [],
    shelves: []
  };

  beforeEach(() => {
    vi.restoreAllMocks();
    authInitService.markAsInitialized.mockReset();
    authService.getInternalAccessToken.mockReset();
    authService.remoteLogin.mockReset();
    authService.initializeWebSocketConnection.mockReset();
    queryClient.fetchQuery.mockReset();
    queryClient.setQueryData.mockReset();
  });

  it('marks auth initialized when public settings are unavailable', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    queryClient.fetchQuery.mockResolvedValue(null);

    const injector = Injector.create({
      providers: [
        {provide: AuthInitializationService, useValue: authInitService},
        {provide: AuthService, useValue: authService},
        {provide: HttpClient, useValue: {get: vi.fn()}},
        {provide: QueryClient, useValue: queryClient},
      ]
    });

    await runInInjectionContext(injector, () => initializeAuthFactory()());

    expect(authInitService.markAsInitialized).toHaveBeenCalledOnce();
    expect(warnSpy).toHaveBeenCalled();
  });

  it('skips bootstrap fetch on login route', async () => {
    const originalUrl = window.location.href;
    window.history.pushState({}, '', '/login');

    const injector = Injector.create({
      providers: [
        {provide: AuthInitializationService, useValue: authInitService},
        {provide: AuthService, useValue: authService},
        {provide: HttpClient, useValue: {get: vi.fn()}},
        {provide: QueryClient, useValue: queryClient},
      ]
    });

    await runInInjectionContext(injector, () => initializeAuthFactory()());

    expect(queryClient.fetchQuery).not.toHaveBeenCalled();
    expect(authInitService.markAsInitialized).toHaveBeenCalledOnce();

    window.history.pushState({}, '', originalUrl);
  });

  it('initializes websocket auth when local auth is active and a token exists', async () => {
    queryClient.fetchQuery.mockResolvedValue({...bootstrapBase, publicSettings: {...bootstrapBase.publicSettings, remoteAuthEnabled: false}});
    authService.getInternalAccessToken.mockReturnValue('access-token');

    const injector = Injector.create({
      providers: [
        {provide: AuthInitializationService, useValue: authInitService},
        {provide: AuthService, useValue: authService},
        {provide: HttpClient, useValue: {get: vi.fn()}},
        {provide: QueryClient, useValue: queryClient},
      ]
    });

    await runInInjectionContext(injector, () => initializeAuthFactory()());

    expect(authService.initializeWebSocketConnection).toHaveBeenCalledOnce();
    expect(authInitService.markAsInitialized).toHaveBeenCalledOnce();
  });

  it('skips websocket initialization when local auth is active and no token exists', async () => {
    queryClient.fetchQuery.mockResolvedValue({...bootstrapBase, publicSettings: {...bootstrapBase.publicSettings, remoteAuthEnabled: false}});
    authService.getInternalAccessToken.mockReturnValue(null);

    const injector = Injector.create({
      providers: [
        {provide: AuthInitializationService, useValue: authInitService},
        {provide: AuthService, useValue: authService},
        {provide: HttpClient, useValue: {get: vi.fn()}},
        {provide: QueryClient, useValue: queryClient},
      ]
    });

    await runInInjectionContext(injector, () => initializeAuthFactory()());

    expect(authService.initializeWebSocketConnection).not.toHaveBeenCalled();
    expect(authInitService.markAsInitialized).toHaveBeenCalledOnce();
  });

  it('performs remote login when remote auth is enabled', async () => {
    queryClient.fetchQuery.mockResolvedValue({...bootstrapBase, publicSettings: {...bootstrapBase.publicSettings, remoteAuthEnabled: true}});
    authService.remoteLogin.mockReturnValue(of({
      accessToken: 'access',
      refreshToken: 'refresh',
      isDefaultPassword: 'false',
    }));

    const injector = Injector.create({
      providers: [
        {provide: AuthInitializationService, useValue: authInitService},
        {provide: AuthService, useValue: authService},
        {provide: HttpClient, useValue: {get: vi.fn()}},
        {provide: QueryClient, useValue: queryClient},
      ]
    });

    await runInInjectionContext(injector, () => initializeAuthFactory()());

    expect(authService.remoteLogin).toHaveBeenCalledOnce();
    expect(authInitService.markAsInitialized).toHaveBeenCalledOnce();
  });

  it('marks auth initialized even when remote login fails', async () => {
    const error = new Error('remote login failed');
    queryClient.fetchQuery.mockResolvedValue({...bootstrapBase, publicSettings: {...bootstrapBase.publicSettings, remoteAuthEnabled: true}});
    authService.remoteLogin.mockReturnValue(throwError(() => error));
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    const injector = Injector.create({
      providers: [
        {provide: AuthInitializationService, useValue: authInitService},
        {provide: AuthService, useValue: authService},
        {provide: HttpClient, useValue: {get: vi.fn()}},
        {provide: QueryClient, useValue: queryClient},
      ]
    });

    await runInInjectionContext(injector, () => initializeAuthFactory()());

    expect(authService.remoteLogin).toHaveBeenCalledOnce();
    expect(authInitService.markAsInitialized).toHaveBeenCalledOnce();
    expect(errorSpy).toHaveBeenCalledWith('[Remote Login] failed:', error);
  });

  it('warms dashboard LCP candidate when access token exists', async () => {
    queryClient.fetchQuery.mockResolvedValue({...bootstrapBase, publicSettings: {...bootstrapBase.publicSettings, remoteAuthEnabled: false}});
    authService.getInternalAccessToken.mockReturnValue('access-token');

    const originalUrl = window.location.href;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null));
    const appendSpy = vi.spyOn(document.head, 'appendChild');
    window.history.pushState({}, '', '/dashboard');
    localStorage.setItem('lcp_book_candidate', JSON.stringify({
      id: 383,
      updatedOn: '2026-03-28T21:58:43Z',
      audioUpdatedOn: null,
      isAudio: false
    }));

    const injector = Injector.create({
      providers: [
        {provide: AuthInitializationService, useValue: authInitService},
        {provide: AuthService, useValue: authService},
        {provide: HttpClient, useValue: {get: vi.fn()}},
        {provide: QueryClient, useValue: queryClient},
      ]
    });

    await runInInjectionContext(injector, () => initializeAuthFactory()());

    expect(appendSpy).toHaveBeenCalled();
    expect(fetchSpy).toHaveBeenCalledOnce();

    localStorage.removeItem('lcp_book_candidate');
    window.history.pushState({}, '', originalUrl);
    appendSpy.mockRestore();
    fetchSpy.mockRestore();
  });
});
