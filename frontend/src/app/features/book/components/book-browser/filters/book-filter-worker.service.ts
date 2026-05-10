import { Injectable, OnDestroy } from '@angular/core';
import { Book } from '../../../model/book.model';
import { BookFilterMode } from '../../../model/filter.model';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class BookFilterWorkerService implements OnDestroy {
  private worker: Worker | undefined;
  private requestId = 0;
  private pendingRequests = new Map<number, (books: Book[]) => void>();
  private lastSentBooks: Book[] | null = null;

  constructor() {
    if (typeof Worker !== 'undefined') {
      this.worker = new Worker(new URL('./book-filter.worker', import.meta.url));
      this.worker.onmessage = ({ data }) => {
        const { filteredBooks, requestId } = data;
        const resolve = this.pendingRequests.get(requestId);
        if (resolve) {
          resolve(filteredBooks);
          this.pendingRequests.delete(requestId);
        }
      };
    }
  }

  filterBooks(
    books: Book[],
    searchTerm: string,
    activeFilters: Record<string, unknown[]> | null,
    mode: BookFilterMode,
    excludeFilterType?: string
  ): Promise<Book[]> {
    if (!this.worker) {
      // Fallback to main thread if workers are not supported
      return import('./HeaderFilter').then(mh => {
        return import('./sidebar-filter').then(ms => {
          let result = mh.filterBooksBySearchTerm(books, searchTerm);
          return ms.filterBooksByFilters(result, activeFilters, mode, excludeFilterType);
        });
      });
    }

    const booksChanged = books !== this.lastSentBooks;
    if (booksChanged) {
      this.lastSentBooks = books;
    }

    const id = ++this.requestId;
    return new Promise<Book[]>((resolve) => {
      this.pendingRequests.set(id, resolve);
      this.worker?.postMessage({
        type: 'filter',
        books: booksChanged ? books : null,
        searchTerm,
        activeFilters,
        mode,
        excludeFilterType,
        requestId: id
      });
    });
  }

  ngOnDestroy(): void {
    this.pendingRequests.forEach(resolve => resolve([]));
    this.pendingRequests.clear();
    this.worker?.terminate();
  }
}
