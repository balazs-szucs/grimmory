import {Injectable, inject} from '@angular/core';
import {AuthService} from './auth.service';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {BookService} from '../../features/book/service/book.service';

@Injectable({providedIn: 'root'})
export class StartupService {
  private authService = inject(AuthService);
  private bookService = inject(BookService);
  private queryClient = inject(QueryClient);

  load(): Promise<void> {
    const token = this.authService.getInternalAccessToken();
    if (token) {
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
}
