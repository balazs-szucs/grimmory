import {computed, effect, inject, Injectable} from '@angular/core';
import {BookService} from '../../book/service/book.service';
import {Book} from '../../book/model/book.model';
import {DashboardConfigService} from './dashboard-config.service';
import {HttpClient} from '@angular/common/http';
import {API_CONFIG} from '../../../core/config/api-config';
import {injectQuery} from '@tanstack/angular-query-experimental';
import {lastValueFrom} from 'rxjs';
import {mapAppBookToBook} from '../../book/model/app-book.model';
import {AppDashboardResponse} from '../models/app-dashboard.model';
import {AuthService} from '../../../shared/service/auth.service';
import {LocalStorageService} from '../../../shared/service/local-storage.service';

@Injectable({
  providedIn: 'root'
})
export class DashboardBookService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly bookService = inject(BookService);
  private readonly configService = inject(DashboardConfigService);
  private readonly localStorageService = inject(LocalStorageService);

  private readonly dashboardUrl = `${API_CONFIG.BASE_URL}/api/v1/app/dashboard`;

  readonly dashboardQuery = injectQuery(() => ({
    queryKey: ['app-dashboard', this.configService.config()] as const,
    queryFn: () => lastValueFrom(this.http.get<AppDashboardResponse>(this.dashboardUrl)),
    enabled: !!this.authService.token(),
    staleTime: 2 * 60_000,
  }));

  readonly isLoading = computed(() => this.dashboardQuery.isPending());

  /**
   * Computed map of scroller ID to its book list, fetched from the consolidated backend endpoint.
   */
  readonly scrollerBooksMap = computed(() => {
    const data = this.dashboardQuery.data();
    const scrollerMap = new Map<string, Book[]>();
    if (!data) return scrollerMap;

    for (const [id, summaries] of Object.entries(data.scrollers)) {
      scrollerMap.set(id, summaries.map(mapAppBookToBook));
    }

    return scrollerMap;
  });

  constructor() {
    // Track the LCP candidate (first book in the first scroller) to preload it next session.
    effect(() => {
      const map = this.scrollerBooksMap();
      const config = this.configService.config();
      if (map.size === 0) return;

      const firstEnabledScroller = config.scrollers.find(s => s.enabled);
      if (!firstEnabledScroller) return;

      const firstBook = map.get(firstEnabledScroller.id)?.[0];
      if (firstBook) {
        const candidate = {
          id: firstBook.id,
          updatedOn: firstBook.metadata?.coverUpdatedOn,
          audioUpdatedOn: firstBook.metadata?.audiobookCoverUpdatedOn,
          isAudio: firstBook.primaryFile?.bookType === 'AUDIOBOOK'
        };
        this.localStorageService.set('lcp_book_candidate', candidate);
      }
    });
  }
}

