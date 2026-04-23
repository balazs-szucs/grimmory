import {Injectable, inject} from '@angular/core';
import {AuthService} from './auth.service';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {BookService} from '../../features/book/service/book.service';
import {API_CONFIG} from '../../core/config/api-config';

@Injectable({providedIn: 'root'})
export class StartupService {
  private authService = inject(AuthService);
  private bookService = inject(BookService);
  private queryClient = inject(QueryClient);

  load(): Promise<void> {
    const token = this.authService.getInternalAccessToken();
    if (token) {
      if (this.shouldWarmLikelyLcp(window.location.pathname)) {
        this.predictivePreloadLcp(token);
      }

      // If we are landing on a book-specific route, prefetch that specific book context
      const bookId = this.detectBookIdFromUrl();
      if (bookId) {
        void this.queryClient.prefetchQuery(this.bookService.bookContextQueryOptions(bookId));
      }
    }

    return Promise.resolve();
  }

  private detectBookIdFromUrl(): number | null {
    const path = window.location.pathname;
    // Matches /book/123, /pdf-reader/book/123, etc.
    const match = path.match(/\/book\/(\d+)/);
    return match ? parseInt(match[1], 10) : null;
  }

  private shouldWarmLikelyLcp(path: string): boolean {
    return path === '/dashboard' || path.startsWith('/dashboard/');
  }

  private predictivePreloadLcp(token: string): void {
    const raw = localStorage.getItem('lcp_book_candidate');
    if (!raw) return;

    try {
      const candidate = JSON.parse(raw);
      const updatedOn = candidate.isAudio ? candidate.audioUpdatedOn : candidate.updatedOn;
      if (!updatedOn) return;

      const endpoint = candidate.isAudio ? 'audiobook-thumbnail' : 'thumbnail';
      // Book cards render at ~232x325 CSS pixels; request a 240px-wide variant.
      const url = `${API_CONFIG.BASE_URL}/api/v1/media/book/${candidate.id}/${endpoint}?${updatedOn}&w=240&token=${token}`;

      const link = document.createElement('link');
      link.rel = 'preload';
      link.as = 'image';
      link.href = url;
      link.fetchPriority = 'high';
      document.head.appendChild(link);

      // Warm the request pipeline immediately so the first dashboard cover can
      // hit memory/disk cache by the time the scroller renders.
      void fetch(url, {
        credentials: 'include',
        cache: 'force-cache',
      }).catch(() => {});
    } catch (e) {
      console.warn('[Startup] Failed to parse LCP candidate', e);
    }
  }
}
