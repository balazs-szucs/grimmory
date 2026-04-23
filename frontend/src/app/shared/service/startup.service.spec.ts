import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {describe, expect, it, vi} from 'vitest';
import {QueryClient, queryOptions} from '@tanstack/angular-query-experimental';

import {AuthService} from './auth.service';
import {StartupService} from './startup.service';
import {BookService} from '../../features/book/service/book.service';

describe('StartupService', () => {
  it('does not preload LCP cover from startup flow', async () => {
    const prefetchQuery = vi.fn().mockResolvedValue(undefined);
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

    TestBed.configureTestingModule({
      providers: [
        StartupService,
        {
          provide: AuthService,
          useValue: {
            token: signal('token-abc'),
            getInternalAccessToken: () => 'token-abc'
          }
        },
        {
          provide: BookService,
          useValue: {
            bookContextQueryOptions: (bookId: number) => queryOptions({
              queryKey: ['book-context', bookId],
              queryFn: async () => ({})
            }),
          },
        },
        {provide: QueryClient, useValue: {prefetchQuery}},
      ]
    });

    const service = TestBed.inject(StartupService);
    await expect(service.load()).resolves.toBeUndefined();

    expect(appendSpy).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();

    localStorage.removeItem('lcp_book_candidate');
    window.history.pushState({}, '', originalUrl);
    appendSpy.mockRestore();
    fetchSpy.mockRestore();
  });

  it('prefetches book context when landing on a book route with a token', async () => {
    const prefetchQuery = vi.fn().mockResolvedValue(undefined);
    const originalUrl = window.location.href;
    window.history.pushState({}, '', '/book/123');

    TestBed.configureTestingModule({
      providers: [
        StartupService,
        {
          provide: AuthService,
          useValue: {
            token: signal('token'),
            getInternalAccessToken: () => 'token'
          }
        },
        {
          provide: BookService,
          useValue: {
            bookContextQueryOptions: (bookId: number) => queryOptions({
              queryKey: ['book-context', bookId],
              queryFn: async () => ({})
            }),
          },
        },
        {provide: QueryClient, useValue: {prefetchQuery}},
      ]
    });

    const service = TestBed.inject(StartupService);

    await expect(service.load()).resolves.toBeUndefined();
    expect(prefetchQuery).toHaveBeenCalledOnce();
    window.history.pushState({}, '', originalUrl);
  });

  it('resolves immediately when there is no token', async () => {
    const prefetchQuery = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        StartupService,
        {
          provide: AuthService,
          useValue: {
            token: signal(null),
            getInternalAccessToken: () => null
          }
        },
        {
          provide: BookService,
          useValue: {
            bookContextQueryOptions: (bookId: number) => queryOptions({
              queryKey: ['book-context', bookId],
              queryFn: async () => ({})
            }),
          },
        },
        {provide: QueryClient, useValue: {prefetchQuery}},
      ]
    });

    const service = TestBed.inject(StartupService);

    await expect(service.load()).resolves.toBeUndefined();
    expect(prefetchQuery).not.toHaveBeenCalled();
  });
});
