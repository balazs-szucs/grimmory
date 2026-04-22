import {computed, effect, inject, Injectable, signal} from '@angular/core';
import {BookService} from '../../book/service/book.service';
import {Book, BookType, ReadStatus} from '../../book/model/book.model';
import {DashboardConfigService} from './dashboard-config.service';
import {HttpClient} from '@angular/common/http';
import {API_CONFIG} from '../../../core/config/api-config';
import {injectQuery, queryOptions} from '@tanstack/angular-query-experimental';
import {lastValueFrom} from 'rxjs';
import {AppBookSummary} from '../../book/model/app-book.model';
import {AppDashboardResponse} from '../models/app-dashboard.model';
import {AuthService} from '../../../shared/service/auth.service';

@Injectable({
  providedIn: 'root'
})
export class DashboardBookService {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly bookService = inject(BookService);
  private readonly configService = inject(DashboardConfigService);

  private readonly dashboardUrl = `${API_CONFIG.BASE_URL}/api/v1/app/dashboard`;

  readonly dashboardQuery = injectQuery(() => ({
    queryKey: ['app-dashboard', this.configService.config()] as const,
    queryFn: () => lastValueFrom(this.http.get<AppDashboardResponse>(this.dashboardUrl)),
    enabled: !!this.authService.token(),
    staleTime: 2 * 60_000,
  }));

  /**
   * Computed map of scroller ID to its book list, fetched from the consolidated backend endpoint.
   */
  readonly scrollerBooksMap = computed(() => {
    const data = this.dashboardQuery.data();
    const scrollerMap = new Map<string, Book[]>();
    if (!data) return scrollerMap;

    for (const [id, summaries] of Object.entries(data.scrollers)) {
      scrollerMap.set(id, summaries.map(summaryToBook));
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
        localStorage.setItem('lcp_book_candidate', JSON.stringify(candidate));
      }
    });
  }
}

/**
 * Maps a server-side AppBookSummary to a Book-shaped object
 * compatible with BookCardComponent's @Input() book property.
 * This is a duplicated utility from AppBooksApiService to avoid circular dependencies.
 */
function summaryToBook(summary: AppBookSummary): Book {
  return {
    id: summary.id,
    libraryId: summary.libraryId,
    readStatus: (summary.readStatus as ReadStatus) ?? ReadStatus.UNSET,
    personalRating: summary.personalRating ?? 0,
    addedOn: summary.addedOn,
    lastReadTime: summary.lastReadTime,
    isPhysical: summary.isPhysical ?? false,
    fileSizeKb: summary.fileSizeKb ?? undefined,
    metadataMatchScore: summary.metadataMatchScore,
    metadata: {
      bookId: summary.id,
      title: summary.title,
      authors: summary.authors ?? [],
      seriesName: summary.seriesName,
      seriesNumber: summary.seriesNumber,
      coverUpdatedOn: summary.coverUpdatedOn,
      audiobookCoverUpdatedOn: summary.audiobookCoverUpdatedOn,
      publishedDate: summary.publishedDate ?? undefined,
      pageCount: summary.pageCount,
      ageRating: summary.ageRating,
      contentRating: summary.contentRating,
    },
    primaryFile: summary.primaryFileType
      ? {bookType: summary.primaryFileType as BookType, extension: summary.primaryFileType.toLowerCase()}
      : null,
    pdfProgress: summary.readProgress != null
      ? {page: 0, percentage: summary.readProgress}
      : null,
    epubProgress: null,
    cbxProgress: null,
    shelves: [],
  } as unknown as Book;
}

