import {
  Component,
  ElementRef,
  HostListener,
  inject,
  OnDestroy,
  OnInit,
  NgZone,
  ViewChild,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { PageTitleService } from "../../../shared/service/page-title.service";
import { BookService } from '../../book/service/book.service';
import { forkJoin, from, Observable, of } from "rxjs";
import { map, switchMap, take, tap } from 'rxjs/operators';
import { BookSetting, PdfZoomPreference } from '../../book/model/book.model';
import { UserService } from '../../settings/user-management/user.service';
import { AuthService } from '../../../shared/service/auth.service';
import { API_CONFIG } from '../../../core/config/api-config';
import { ReaderIconComponent } from '../../readers/ebook-reader/shared/icon.component';

import { ProgressSpinner } from 'primeng/progressspinner';
import { MessageService } from 'primeng/api';
import { TranslocoService, TranslocoPipe } from '@jsverse/transloco';
import { CacheStorageService } from '../../../shared/service/cache-storage.service'
import { LocalSettingsService } from '../../../shared/service/local-settings.service';
import { ReadingSessionService } from '../../../shared/service/reading-session.service';
import { WakeLockService } from '../../../shared/service/wake-lock.service';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PdfAnnotationService } from '../../../shared/service/pdf-annotation.service';
import {
  parseStoredPdfAnnotationsForEmbed,
  serializeAnnotationTransferItemsForIframe,
} from './pdf-annotation-transfer.util';

type EmbedPdfMessage =
  | { type: 'ready' }
  | { type: 'log'; args: string[] }
  | { type: 'documentOpened'; pageCount?: number }
  | { type: 'documentError'; error: string }
  | { type: 'saved'; buffer?: ArrayBuffer }
  | { type: 'saveError'; error: string }
  | { type: 'pageChanged'; page: number; totalPages: number }
  | { type: 'annotationsExported'; json: string }
  | { type: 'annotationFlushAck' }
  | { type: 'annotationsImported'; count?: number }
  | { type: 'zoomChanged'; level: unknown; scale?: number }
  | { type: 'userActivity' };

@Component({
  selector: 'app-pdf-reader',
  standalone: true,
  imports: [ProgressSpinner, TranslocoPipe, ReaderIconComponent, FormsModule],
  templateUrl: './pdf-reader.component.html',
  styleUrl: './pdf-reader.component.scss',
})
export class PdfReaderComponent implements OnInit, OnDestroy {
  @ViewChild('embedPdfHost', { read: ElementRef }) embedPdfHost?: ElementRef<HTMLDivElement>;

  isLoading = true;
  /** 0–100 while streaming PDF bytes when Content-Length is present */
  pdfFetchProgressPercent: number | null = null;
  totalPages: number = 0;
  isDarkTheme = true;
  canPrint = false;

  rotation: 0 | 90 | 180 | 270 = 0;
  authorization = '';

  page!: number;
  spread!: 'off' | 'even' | 'odd';
  zoom!: PdfZoomPreference;

  bookData!: string;
  bookId!: number;
  bookFileId?: number;
  bookTitle = '';
  isFullscreen = false;
  viewerMode: 'book' | 'document' = 'book';
  private embedPdfIframe: HTMLIFrameElement | null = null;
  private embedPdfMessageHandler?: (e: MessageEvent) => void;
  private embedPdfSaveResolve?: (buffer: ArrayBuffer | null) => void;
  private embedPdfSaveTimer?: ReturnType<typeof setTimeout>;
  private loadComplete = false;
  private embedLoadToken = 0;
  private embedPdfInitInFlight = false;
  private pdfFetchAbort?: AbortController;
  private annotationFlushTimer?: ReturnType<typeof setTimeout>;
  private annotationFlushResolve?: () => void;
  private lastAnnotationPayloadSaved = '';

  // Auto-hide chrome
  headerVisible = true;
  footerVisible = true;
  private chromeAutoHideTimer?: ReturnType<typeof setTimeout>;
  private readonly CHROME_HIDE_DELAY = 3000;

  // Footer page navigation
  goToPageInput: number | null = null;
  get sliderTicks(): number[] {
    if (this.totalPages <= 1) return [];
    const step = Math.max(1, Math.floor(this.totalPages / 10));
    const ticks: number[] = [];
    for (let i = 1; i <= this.totalPages; i += step) ticks.push(i);
    if (ticks[ticks.length - 1] !== this.totalPages) ticks.push(this.totalPages);
    return ticks;
  }

  private altBookType?: string;

  private bookService = inject(BookService);
  private userService = inject(UserService);
  private authService = inject(AuthService);
  private messageService = inject(MessageService);
  private route = inject(ActivatedRoute);
  private pageTitle = inject(PageTitleService);
  private readingSessionService = inject(ReadingSessionService);
  private location = inject(Location);
  private cacheStorageService = inject(CacheStorageService);
  private localSettingsService = inject(LocalSettingsService);
  private readonly t = inject(TranslocoService);
  private wakeLockService = inject(WakeLockService);
  private ngZone = inject(NgZone);
  private pdfAnnotationService = inject(PdfAnnotationService);

  ngOnInit(): void {
    setTimeout(() => this.wakeLockService.enable(), 1000);
    this.startChromeAutoHide();
    document.addEventListener('fullscreenchange', this.onFullscreenChange);

    this.route.paramMap.pipe(
      switchMap((params) => {
        const nextBookId = +params.get('bookId')!;
        const nextAlt = this.route.snapshot.queryParamMap.get('bookType') ?? undefined;
        return from(this.destroyEmbedPdf()).pipe(
          tap(() => {
            this.isLoading = true;
            this.lastAnnotationPayloadSaved = '';
            this.bookId = nextBookId;
            this.altBookType = nextAlt;
          }),
          switchMap(() =>
            from(this.bookService.ensureBookDetail(this.bookId, false)).pipe(
              switchMap((book) => {
                if (this.altBookType) {
                  const altFile = book.alternativeFormats?.find((f) => f.bookType === this.altBookType);
                  this.bookFileId = altFile?.id;
                } else {
                  this.bookFileId = book.primaryFile?.id;
                }

                return forkJoin([
                  this.bookService.getBookSetting(this.bookId, this.bookFileId!),
                  this.userService.getMyself(),
                ]).pipe(map(([bookSetting, myself]) => ({ book, bookSetting, myself })));
              })
            )
          )
        );
      }),
      switchMap(({ book, bookSetting, myself }) => {
        return this.getBookData(this.bookId.toString(), this.altBookType).pipe(
          map((bookData) => ({ book, bookSetting, myself, bookData })),
        );
      }),
    ).subscribe({
      next: ({ book, bookSetting, myself, bookData }) => {
        const pdfMeta = book;
        const pdfPrefs = bookSetting;

        this.pageTitle.setBookPageTitle(pdfMeta);
        this.bookTitle = pdfMeta.metadata?.title || '';

        const globalOrIndividual = myself.userSettings.perBookSetting.pdf;
        if (globalOrIndividual === 'Global') {
          this.zoom = String(myself.userSettings.pdfReaderSetting.pageZoom || 'page-fit');
          this.spread = myself.userSettings.pdfReaderSetting.pageSpread || 'off';
        } else {
          this.zoom = String(
            pdfPrefs.pdfSettings?.zoom ?? myself.userSettings.pdfReaderSetting.pageZoom ?? 'page-fit'
          );
          this.spread = pdfPrefs.pdfSettings?.spread || myself.userSettings.pdfReaderSetting.pageSpread || 'off';
          this.isDarkTheme = pdfPrefs.pdfSettings?.isDarkTheme ?? true;
        }
        this.canPrint = myself.permissions.canDownload || myself.permissions.admin;
        this.page = pdfMeta.pdfProgress?.page || 1;
        this.bookData = bookData;
        const token = this.authService.getInternalAccessToken();
        this.authorization = token ? `Bearer ${token}` : '';
        this.isLoading = false;
        this.loadComplete = true;
        this.scheduleEmbedInit();
      },
      error: () => {
        this.messageService.add({ severity: 'error', summary: this.t.translate('common.error'), detail: this.t.translate('readerPdf.toast.failedToLoadBook') });
        this.isLoading = false;
      }
    });
  }

  private scheduleEmbedInit(): void {
    if (!this.loadComplete || !this.bookData || this.isLoading) return;
    setTimeout(() => void this.initEmbedPdf(), 100);
  }

  async setViewerMode(mode: 'book' | 'document') {
    if (mode === this.viewerMode) {
      return;
    }
    if (this.viewerMode === 'document' && mode !== 'document') {
      await this.saveEmbedPdfDocument();
    }
    await this.destroyEmbedPdf();
    this.viewerMode = mode;
    setTimeout(() => void this.initEmbedPdf(), 100);
  }

  private async initEmbedPdf() {
    if (!this.bookData || this.embedPdfIframe || this.embedPdfInitInFlight) {
      return;
    }
    this.embedPdfInitInFlight = true;
    const myToken = this.embedLoadToken;

    try {
      const headers: Record<string, string> = {};
      if (this.authorization) {
        headers['Authorization'] = this.authorization;
      }

      this.pdfFetchAbort?.abort();
      this.pdfFetchAbort = new AbortController();
      const signal = this.pdfFetchAbort.signal;

      const response = await fetch(this.bookData, {
        headers,
        credentials: 'include',
        signal,
      });
      if (myToken !== this.embedLoadToken) {
        return;
      }
      if (!response.ok) {
        throw new Error(`PDF fetch failed: ${response.status}`);
      }

      const pdfBuffer = await this.readPdfResponseAsArrayBuffer(response, myToken, signal);
      if (myToken !== this.embedLoadToken) {
        return;
      }

      this.ngZone.run(() => {
        this.pdfFetchProgressPercent = null;
      });

      const targetEl = this.embedPdfHost?.nativeElement;
      if (!targetEl) {
        throw new Error('embedPdfHost not found');
      }

      const iframe = document.createElement('iframe');
      iframe.src = '/assets/embedpdf-frame.html';
      iframe.style.cssText = 'width:100%;height:100%;border:none;';
      iframe.setAttribute('allow', 'fullscreen');
      iframe.tabIndex = 0;

      this.embedPdfMessageHandler = (e: MessageEvent) => {
        if (e.origin !== location.origin) {
          return;
        }
        if (e.source !== iframe.contentWindow) {
          return;
        }
        this.handleEmbedPdfMessage(e.data as EmbedPdfMessage);
      };
      window.addEventListener('message', this.embedPdfMessageHandler);

      await new Promise<void>((resolve) => {
        iframe.onload = () => resolve();
        targetEl.appendChild(iframe);
      });

      if (myToken !== this.embedLoadToken) {
        iframe.remove();
        return;
      }

      this.embedPdfIframe = iframe;

      iframe.contentWindow!.postMessage(
        {
          type: 'init',
          buffer: pdfBuffer,
          wasmUrl: '/assets/pdfium/pdfium.wasm',
          theme: this.isDarkTheme ? 'dark' : 'light',
          mode: this.viewerMode,
          allowPrint: this.canPrint,
          initialPage: this.page,
          spread: this.spread,
          zoom: this.zoom,
          rotation: this.rotation,
        },
        location.origin,
        [pdfBuffer],
      );
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        return;
      }
      console.error('[EmbedPDF] FATAL:', err);
      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('common.error'),
        detail: 'Failed to load PDF viewer. Check browser console for details.',
      });
    } finally {
      this.embedPdfInitInFlight = false;
      this.ngZone.run(() => {
        if (this.pdfFetchProgressPercent !== null) {
          this.pdfFetchProgressPercent = null;
        }
      });
    }
  }

  private async readPdfResponseAsArrayBuffer(
    response: Response,
    token: number,
    signal: AbortSignal,
  ): Promise<ArrayBuffer> {
    const lenHeader = response.headers.get('content-length');
    const total = lenHeader ? parseInt(lenHeader, 10) : NaN;

    if (!response.body || Number.isNaN(total) || total <= 0) {
      return response.arrayBuffer();
    }

    this.ngZone.run(() => {
      this.pdfFetchProgressPercent = 0;
    });

    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let loaded = 0;

    while (true) {
      if (signal.aborted) {
        await reader.cancel().catch(() => undefined);
        throw new DOMException('aborted', 'AbortError');
      }
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      chunks.push(value);
      loaded += value.length;
      if (token !== this.embedLoadToken) {
        await reader.cancel().catch(() => undefined);
        throw new DOMException('aborted', 'AbortError');
      }
      const pct = Math.min(100, Math.round((loaded / total) * 100));
      this.ngZone.run(() => {
        this.pdfFetchProgressPercent = pct;
      });
    }

    const out = new Uint8Array(loaded);
    let offset = 0;
    for (const c of chunks) {
      out.set(c, offset);
      offset += c.length;
    }
    return out.buffer;
  }

  private mapEmbedZoomLevelToPreference(level: unknown): PdfZoomPreference {
    if (level === 'automatic') {
      return 'auto';
    }
    if (level === 'fit-page') {
      return 'page-fit';
    }
    if (level === 'fit-width') {
      return 'page-width';
    }
    if (typeof level === 'number' && !Number.isNaN(level)) {
      return `${Math.round(level * 100)}%`;
    }
    return String(level);
  }

  private resolveAnnotationFlushWait(): void {
    this.annotationFlushResolve?.();
    this.annotationFlushResolve = undefined;
    if (this.annotationFlushTimer) {
      clearTimeout(this.annotationFlushTimer);
      this.annotationFlushTimer = undefined;
    }
  }

  private handleEmbedPdfMessage(raw: unknown): void {
    if (!raw || typeof raw !== 'object' || !('type' in raw)) {
      return;
    }
    const msg = raw as EmbedPdfMessage;
    switch (msg.type) {
      case 'ready':
        break;
      case 'log':
        break;
      case 'documentOpened':
        this.ngZone.run(() => {
          if (msg.pageCount != null) {
            this.totalPages = msg.pageCount;
          }
          const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
          this.readingSessionService.startSession(this.bookId, "PDF", this.page.toString(), percentage);
          this.readingSessionService.updateProgress(this.page.toString(), percentage);
        });
        if (this.viewerMode === 'book') {
          this.injectStoredPdfAnnotations();
        }
        setTimeout(() => {
          this.embedPdfIframe?.focus();
          this.embedPdfIframe?.contentWindow?.focus();
        }, 0);
        break;
      case 'annotationFlushAck':
        this.resolveAnnotationFlushWait();
        break;
      case 'annotationsImported':
        break;
      case 'zoomChanged':
        this.ngZone.run(() => {
          this.zoom = this.mapEmbedZoomLevelToPreference(msg.level);
        });
        break;
      case 'userActivity':
        this.ngZone.run(() => {
          this.showChrome();
          this.startChromeAutoHide();
        });
        break;
      case 'annotationsExported':
        this.resolveAnnotationFlushWait();
        if (this.viewerMode === 'book' && msg.json !== this.lastAnnotationPayloadSaved) {
          const payload = msg.json;
          this.pdfAnnotationService.saveAnnotations(this.bookId, payload).subscribe({
            next: () => {
              this.lastAnnotationPayloadSaved = payload;
            },
            error: (e) => console.error('[PDF overlay] save failed', e),
          });
        }
        break;
      case 'documentError':
        console.error('[EmbedPDF] Document error:', msg.error);
        break;
      case 'saved':
        this.embedPdfSaveResolve?.(msg.buffer ?? null);
        this.embedPdfSaveResolve = undefined;
        break;
      case 'saveError':
        console.error('[EmbedPDF] Save error:', msg.error);
        this.embedPdfSaveResolve?.(null);
        this.embedPdfSaveResolve = undefined;
        break;
      case 'pageChanged':
        this.ngZone.run(() => {
          if (msg.totalPages > 0) {
            this.totalPages = msg.totalPages;
          }
          if (msg.page === this.page) {
            return;
          }
          this.page = msg.page;
          this.updateProgress();
          const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
          this.readingSessionService.updateProgress(this.page.toString(), percentage);
        });
        break;
    }
  }

  private postEmbedControl(payload: Record<string, unknown>): void {
    if (!this.embedPdfIframe?.contentWindow) return;
    this.embedPdfIframe.contentWindow.postMessage({ type: 'control', ...payload }, location.origin);
  }

  private async saveEmbedPdfDocument(): Promise<void> {
    if (!this.embedPdfIframe?.contentWindow) {
      return;
    }

    try {
      const buffer: ArrayBuffer | null = await new Promise((resolve) => {
        this.embedPdfSaveResolve = resolve;
        this.embedPdfIframe!.contentWindow!.postMessage({ type: 'save' }, location.origin);
        const timer = setTimeout(() => {
          if (this.embedPdfSaveResolve === resolve) {
            resolve(null);
            this.embedPdfSaveResolve = undefined;
          }
        }, 30000);
        this.embedPdfSaveTimer = timer;
      });

      if (this.embedPdfSaveTimer) {
        clearTimeout(this.embedPdfSaveTimer);
        this.embedPdfSaveTimer = undefined;
      }

      if (!buffer) {
        return;
      }

      const headers: Record<string, string> = { 'Content-Type': 'application/pdf' };
      if (this.authorization) {
        headers['Authorization'] = this.authorization;
      }

      const url = this.altBookType
        ? `${API_CONFIG.BASE_URL}/api/v1/books/${this.bookId}/content?bookType=${this.altBookType}`
        : `${API_CONFIG.BASE_URL}/api/v1/books/${this.bookId}/content`;

      const uploadResponse = await fetch(url, {
        method: 'PUT',
        headers,
        credentials: 'include',
        body: buffer
      });
      if (!uploadResponse.ok) {
        console.error('[EmbedPDF] Upload failed:', uploadResponse.status);
      }
    } catch (err) {
      console.error('[EmbedPDF] Failed to save document:', err);
    }
  }

  private navigateToPage(page: number, smooth = false): void {
    if (page < 1 || (this.totalPages > 0 && page > this.totalPages)) {
      return;
    }
    if (page !== this.page) {
      this.page = page;
      this.updateProgress();
      const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
      this.readingSessionService.updateProgress(this.page.toString(), percentage);
    }
    this.postEmbedControl({ action: 'goToPage', page, smooth });
  }

  onZoomPreferenceChange(zoom: PdfZoomPreference): void {
    const prev = this.zoom;
    this.zoom = zoom;
    if (zoom !== prev) {
      this.updateViewerSetting();
    }
    this.postEmbedControl({ action: 'setZoom', zoom });
  }

  onSpreadChange(spread: 'off' | 'even' | 'odd'): void {
    const prev = this.spread;
    this.spread = spread;
    if (spread !== prev) {
      this.updateViewerSetting();
    }
    this.postEmbedControl({ action: 'setSpread', spread });
  }

  toggleDarkTheme(): void {
    this.isDarkTheme = !this.isDarkTheme;
    this.updateViewerSetting();
    if (this.embedPdfIframe?.contentWindow) {
      this.embedPdfIframe.contentWindow.postMessage(
        { type: 'setTheme', theme: this.isDarkTheme ? 'dark' : 'light' },
        location.origin
      );
    }
  }

  private updateViewerSetting(): void {
    const bookSetting: BookSetting = {
      pdfSettings: {
        spread: this.spread,
        zoom: this.zoom,
        isDarkTheme: this.isDarkTheme,
      }
    }
    this.bookService.updateViewerSetting(bookSetting, this.bookId).subscribe();
  }

  updateProgress(): void {
    const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
    this.bookService.savePdfProgress(this.bookId, this.page, percentage, this.bookFileId).subscribe();
  }

  embedToggleSidebar(): void {
    this.postEmbedControl({ action: 'runCommand', commandId: 'sidebar-button' });
  }

  embedOpenSearch(): void {
    this.postEmbedControl({ action: 'runCommand', commandId: 'search-button' });
  }

  embedOpenMoreMenu(): void {
    this.postEmbedControl({ action: 'runCommand', commandId: 'overflow-left-action-menu-button' });
  }

  embedZoomIn(): void {
    this.postEmbedControl({ action: 'zoomIn' });
  }

  embedZoomOut(): void {
    this.postEmbedControl({ action: 'zoomOut' });
  }

  embedTogglePan(): void {
    this.postEmbedControl({ action: 'togglePan' });
  }

  embedToggleOutlineSidebar(): void {
    this.postEmbedControl({ action: 'runCommand', commandId: 'outline-sidebar' });
  }

  /** EmbedPDF default tool ids: highlight, ink, freeText */
  embedSetAnnotationTool(toolId: string | null): void {
    this.postEmbedControl({ action: 'setAnnotationTool', toolId });
  }

  embedPrint(): void {
    this.postEmbedControl({ action: 'runCommand', commandId: 'print' });
  }

  ngOnDestroy(): void {
    this.wakeLockService.disable();
    if (this.chromeAutoHideTimer) clearTimeout(this.chromeAutoHideTimer);
    if (this.readingSessionService.isSessionActive()) {
      const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
      this.readingSessionService.endSession(this.page.toString(), percentage);
    }

    void this.destroyEmbedPdf();

    this.updateProgress();
    document.removeEventListener('fullscreenchange', this.onFullscreenChange);

    if (this.bookData?.startsWith('blob:')) {
      URL.revokeObjectURL(this.bookData);
    }
  }

  @HostListener('document:mousemove')
  onMouseMove(): void {
    this.showChrome();
    this.startChromeAutoHide();
  }

  @HostListener('document:touchstart', ['$event'])
  onDocumentTouchStart(ev: TouchEvent): void {
    if (ev.touches.length > 0) {
      this.showChrome();
      this.startChromeAutoHide();
    }
  }

  @HostListener('document:click')
  onDocumentClick(): void {
    this.showChrome();
    this.startChromeAutoHide();
  }

  showChrome(): void {
    this.headerVisible = true;
    this.footerVisible = true;
  }

  hideChrome(): void {
    this.headerVisible = false;
    this.footerVisible = false;
  }

  private startChromeAutoHide(): void {
    if (this.chromeAutoHideTimer) clearTimeout(this.chromeAutoHideTimer);
    this.chromeAutoHideTimer = setTimeout(() => this.hideChrome(), this.CHROME_HIDE_DELAY);
  }

  onHeaderTriggerZoneEnter(): void {
    this.headerVisible = true;
    this.startChromeAutoHide();
  }

  onFooterTriggerZoneEnter(): void {
    this.footerVisible = true;
    this.startChromeAutoHide();
  }

  toggleFullscreen(): void {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen?.();
    } else {
      document.exitFullscreen?.();
    }
  }

  private onFullscreenChange = (): void => {
    this.isFullscreen = !!document.fullscreenElement;
  };

  goToFirstPage(): void {
    this.navigateToPage(1, false);
  }

  goToPreviousPage(): void {
    if (this.page > 1) {
      this.navigateToPage(this.page - 1, false);
    }
  }

  goToNextPage(): void {
    if (this.page < this.totalPages) {
      this.navigateToPage(this.page + 1, false);
    }
  }

  goToLastPage(): void {
    this.navigateToPage(this.totalPages, false);
  }

  onSliderChange(event: Event): void {
    const value = +(event.target as HTMLInputElement).value;
    this.navigateToPage(value, true);
  }

  onGoToPage(): void {
    if (this.goToPageInput && this.goToPageInput >= 1 && this.goToPageInput <= this.totalPages) {
      this.navigateToPage(this.goToPageInput, false);
      this.goToPageInput = null;
    }
  }

  rotateClockwise(): void {
    this.rotation = ((this.rotation + 90) % 360) as 0 | 90 | 180 | 270;
    this.postEmbedControl({ action: 'rotateForward' });
  }

  closeReader = async (): Promise<void> => {
    if (this.viewerMode === 'document') {
      await this.saveEmbedPdfDocument();
    }
    await this.destroyEmbedPdf();
    if (this.readingSessionService.isSessionActive()) {
      const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
      this.readingSessionService.endSession(this.page.toString(), percentage);
    }
    this.location.back();
  }

  private getBookData(
    bookId: string,
    fileType: string | undefined,
  ): Observable<string> {
    const uri = fileType
      ? `${API_CONFIG.BASE_URL}/api/v1/books/${bookId}/content?bookType=${fileType}`
      : `${API_CONFIG.BASE_URL}/api/v1/books/${bookId}/content`;
    if (!this.localSettingsService.get().cacheStorageEnabled) return of(uri);
    return from(this.cacheStorageService.getCache(uri)).pipe(
      switchMap(res => res.blob()),
      map(blob => URL.createObjectURL(blob))
    )
  }

  private injectStoredPdfAnnotations(): void {
    this.pdfAnnotationService.getAnnotations(this.bookId).pipe(take(1)).subscribe({
      next: (res) => {
        if (!res?.data?.trim()) {
          return;
        }
        const items = parseStoredPdfAnnotationsForEmbed(res.data);
        if (items.length === 0) {
          return;
        }
        const json = serializeAnnotationTransferItemsForIframe(items);
        if (!this.embedPdfIframe?.contentWindow || this.viewerMode !== 'book') {
          return;
        }
        this.embedPdfIframe.contentWindow.postMessage({ type: 'importAnnotations', json }, location.origin);
      },
      error: () => {},
    });
  }

  private async waitForAnnotationFlush(): Promise<void> {
    if (this.viewerMode !== 'book' || !this.embedPdfIframe?.contentWindow) {
      return;
    }
    await new Promise<void>((resolve) => {
      this.annotationFlushTimer = setTimeout(() => {
        this.annotationFlushTimer = undefined;
        this.annotationFlushResolve = undefined;
        resolve();
      }, 2500);
      this.annotationFlushResolve = () => {
        if (this.annotationFlushTimer) {
          clearTimeout(this.annotationFlushTimer);
        }
        this.annotationFlushTimer = undefined;
        this.annotationFlushResolve = undefined;
        resolve();
      };
      this.embedPdfIframe!.contentWindow!.postMessage({ type: 'flushAnnotations' }, location.origin);
    });
  }

  private async destroyEmbedPdf(): Promise<void> {
    this.pdfFetchAbort?.abort();
    this.pdfFetchAbort = undefined;

    await this.waitForAnnotationFlush();

    this.embedLoadToken++;
    if (this.embedPdfMessageHandler) {
      window.removeEventListener('message', this.embedPdfMessageHandler);
      this.embedPdfMessageHandler = undefined;
    }

    if (this.embedPdfSaveTimer) {
      clearTimeout(this.embedPdfSaveTimer);
      this.embedPdfSaveTimer = undefined;
    }
    this.embedPdfSaveResolve?.(null);
    this.embedPdfSaveResolve = undefined;

    if (this.embedPdfIframe) {
      this.embedPdfIframe.remove();
      this.embedPdfIframe = null;
    }
  }
}
