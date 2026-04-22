import {computed, effect, inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {lastValueFrom} from 'rxjs';
import {injectQuery, queryOptions, QueryClient} from '@tanstack/angular-query-experimental';

import {API_CONFIG} from '../../core/config/api-config';
import {AuthService} from './auth.service';

export interface MenuCountsResponse {
  libraryCounts: Record<number, number>;
  shelfCounts: Record<number, number>;
  magicShelfCounts: Record<number, number>;
  totalBookCount: number;
  unshelvedBookCount: number;
}

export const MENU_COUNTS_QUERY_KEY = ['menuCounts'] as const;

/**
 * Feeds sidebar book counts without materialising the full book list.
 *
 * The backend returns three small maps (library id -> count, shelf id -> count,
 * magic shelf id -> count) so the menu can render counts with a single cheap
 * aggregate query instead of waiting for `/api/v1/books?stripForListView=false`
 * (which is ~1s on prod and blocks first paint).
 */
@Injectable({providedIn: 'root'})
export class MenuCountsService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books/menu-counts`;

  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly queryClient = inject(QueryClient);
  private readonly token = this.authService.token;

  private readonly menuCountsQuery = injectQuery(() => ({
    ...this.getQueryOptions(),
    enabled: !!this.token(),
  }));

  readonly libraryCounts = computed(() => this.menuCountsQuery.data()?.libraryCounts ?? {});
  readonly shelfCounts = computed(() => this.menuCountsQuery.data()?.shelfCounts ?? {});
  readonly magicShelfCounts = computed(() => this.menuCountsQuery.data()?.magicShelfCounts ?? {});

  /**
   * Single source of truth for the "all books" menu item count — server-side
   * aggregate over the user-visible library set.
   */
  readonly totalBookCount = computed(() => this.menuCountsQuery.data()?.totalBookCount ?? 0);

  /** Count of books the current user owns that belong to no shelf. */
  readonly unshelvedBookCount = computed(() => this.menuCountsQuery.data()?.unshelvedBookCount ?? 0);

  readonly isLoading = computed(() => !!this.token() && this.menuCountsQuery.isPending());

  constructor() {
    effect(() => {
      if (this.token() === null) {
        this.queryClient.removeQueries({queryKey: MENU_COUNTS_QUERY_KEY});
      }
    });
  }

  /** Ask TanStack Query to refetch. Called from socket handlers after book mutations. */
  invalidate(): void {
    void this.queryClient.invalidateQueries({queryKey: MENU_COUNTS_QUERY_KEY, exact: true});
  }

  getLibraryCount(libraryId: number | null | undefined): number {
    if (libraryId == null) return 0;
    return this.libraryCounts()[libraryId] ?? 0;
  }

  getShelfCount(shelfId: number | null | undefined): number {
    if (shelfId == null) return 0;
    return this.shelfCounts()[shelfId] ?? 0;
  }

  getMagicShelfCount(shelfId: number | null | undefined): number {
    if (shelfId == null) return 0;
    return this.magicShelfCounts()[shelfId] ?? 0;
  }

  private getQueryOptions() {
    return queryOptions({
      queryKey: MENU_COUNTS_QUERY_KEY,
      queryFn: () => lastValueFrom(this.http.get<MenuCountsResponse>(this.url)),
      staleTime: 2 * 60_000,
    });
  }
}
