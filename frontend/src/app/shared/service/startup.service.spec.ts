import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {describe, expect, it, vi} from 'vitest';
import {QueryClient, queryOptions} from '@tanstack/angular-query-experimental';

import {AuthService} from './auth.service';
import {StartupService} from './startup.service';
import {BookService} from '../../features/book/service/book.service';

describe('StartupService', () => {
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
