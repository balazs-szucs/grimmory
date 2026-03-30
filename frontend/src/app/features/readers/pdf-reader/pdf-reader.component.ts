import { Component, HostListener, inject, OnDestroy, OnInit, AfterViewInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { NgxExtendedPdfViewerModule, NgxExtendedPdfViewerService, pdfDefaultOptions, ZoomType } from 'ngx-extended-pdf-viewer';
import { PageTitleService } from "../../../shared/service/page-title.service";
import { BookService } from '../../book/service/book.service';
import { forkJoin, from, Subject, Subscription } from 'rxjs';
import { debounceTime, map, switchMap } from 'rxjs/operators';
import { BookSetting } from '../../book/model/book.model';
import { UserService } from '../../settings/user-management/user.service';
import { AuthService } from '../../../shared/service/auth.service';
import { API_CONFIG } from '../../../core/config/api-config';
import { PdfAnnotationService } from '../../../shared/service/pdf-annotation.service';
import { ReaderIconComponent } from '../../readers/ebook-reader/shared/icon.component';

import { ProgressSpinner } from 'primeng/progressspinner';
import { MessageService } from 'primeng/api';
import { TranslocoService, TranslocoPipe } from '@jsverse/transloco';
import { ReadingSessionService } from '../../../shared/service/reading-session.service';
import { WakeLockService } from '../../../shared/service/wake-lock.service';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-pdf-reader',
  standalone: true,
  imports: [NgxExtendedPdfViewerModule, ProgressSpinner, TranslocoPipe, ReaderIconComponent, FormsModule],
  templateUrl: './pdf-reader.component.html',
  styleUrl: './pdf-reader.component.scss',
})
export class PdfReaderComponent implements OnInit, OnDestroy, AfterViewInit {
  constructor() {
    pdfDefaultOptions.rangeChunkSize = 512 * 1024;
    pdfDefaultOptions.disableAutoFetch = true;
  }

  isLoading = true;
  totalPages: number = 0;
  isDarkTheme = true;
  canPrint = false;

  rotation: 0 | 90 | 180 | 270 = 0;
  authorization = '';

  page!: number;
  spread!: 'off' | 'even' | 'odd';
  zoom!: ZoomType;

  bookData!: string;
  bookId!: number;
  bookFileId?: number;
  bookTitle = '';
  isFullscreen = false;
  viewerMode: 'book' | 'document' = 'book';
  private embedPdfViewerInstance: any;

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
  private appSettingsSubscription!: Subscription;
  private annotationSaveSubject = new Subject<void>();
  private annotationSaveSubscription!: Subscription;
  private annotationsLoaded = false;

  private bookService = inject(BookService);
  private userService = inject(UserService);
  private authService = inject(AuthService);
  private messageService = inject(MessageService);
  private route = inject(ActivatedRoute);
  private pageTitle = inject(PageTitleService);
  private readingSessionService = inject(ReadingSessionService);
  private location = inject(Location);
  private pdfViewerService = inject(NgxExtendedPdfViewerService);
  private pdfAnnotationService = inject(PdfAnnotationService);
  private readonly t = inject(TranslocoService);
  private wakeLockService = inject(WakeLockService);
  private annotationToolbarObserver?: MutationObserver;

  ngOnInit(): void {
    setTimeout(() => this.wakeLockService.enable(), 1000);
    this.startChromeAutoHide();
    document.addEventListener('fullscreenchange', this.onFullscreenChange);

    this.annotationSaveSubscription = this.annotationSaveSubject
      .pipe(debounceTime(1500))
      .subscribe(() => this.persistAnnotations());

    this.route.paramMap.pipe(
      switchMap((params) => {
        this.isLoading = true;
        this.bookId = +params.get('bookId')!;
        this.altBookType = this.route.snapshot.queryParamMap.get('bookType') ?? undefined;

        return from(this.bookService.ensureBookDetail(this.bookId, false)).pipe(
          switchMap((book) => {
            if (this.altBookType) {
              const altFile = book.alternativeFormats?.find(f => f.bookType === this.altBookType);
              this.bookFileId = altFile?.id;
            } else {
              this.bookFileId = book.primaryFile?.id;
            }

            return forkJoin([
              this.bookService.getBookSetting(this.bookId, this.bookFileId!),
              this.userService.getMyself()
            ]).pipe(map(([bookSetting, myself]) => ({ book, bookSetting, myself })));
          })
        );
      })
    ).subscribe({
      next: ({ book, bookSetting, myself }) => {
        const pdfMeta = book;
        const pdfPrefs = bookSetting;

        this.pageTitle.setBookPageTitle(pdfMeta);
        this.bookTitle = pdfMeta.metadata?.title || '';

        const globalOrIndividual = myself.userSettings.perBookSetting.pdf;
        if (globalOrIndividual === 'Global') {
          this.zoom = myself.userSettings.pdfReaderSetting.pageZoom || 'page-fit';
          this.spread = myself.userSettings.pdfReaderSetting.pageSpread || 'off';
        } else {
          this.zoom = pdfPrefs.pdfSettings?.zoom || myself.userSettings.pdfReaderSetting.pageZoom || 'page-fit';
          this.spread = pdfPrefs.pdfSettings?.spread || myself.userSettings.pdfReaderSetting.pageSpread || 'off';
          this.isDarkTheme = pdfPrefs.pdfSettings?.isDarkTheme ?? true;
        }
        this.canPrint = myself.permissions.canDownload || myself.permissions.admin;
        this.page = pdfMeta.pdfProgress?.page || 1;
        this.bookData = this.altBookType
          ? `${API_CONFIG.BASE_URL}/api/v1/books/${this.bookId}/content?bookType=${this.altBookType}`
          : `${API_CONFIG.BASE_URL}/api/v1/books/${this.bookId}/content`;
        const token = this.authService.getInternalAccessToken();
        this.authorization = token ? `Bearer ${token}` : '';
        this.isLoading = false;
      },
      error: () => {
        this.messageService.add({ severity: 'error', summary: this.t.translate('common.error'), detail: this.t.translate('readerPdf.toast.failedToLoadBook') });
        this.isLoading = false;
      }
    });
  }

  ngAfterViewInit(): void {
    this.setupAnnotationToolbarCloseObserver();
  }

  private setupAnnotationToolbarCloseObserver(): void {
    this.annotationToolbarObserver = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        mutation.addedNodes.forEach((node) => {
          if (node instanceof HTMLElement) {
            if (node.classList.contains('editorParamsToolbar')) {
              this.injectCloseButton(node);
            }
            const toolbars = node.querySelectorAll?.('.editorParamsToolbar');
            toolbars?.forEach(t => this.injectCloseButton(t as HTMLElement));
          }
        });
      });
    });

    this.annotationToolbarObserver.observe(document.body, { childList: true, subtree: true });

    // Also check immediately in case they are already in the DOM
    setTimeout(() => {
      document.querySelectorAll('.editorParamsToolbar').forEach(t => this.injectCloseButton(t as HTMLElement));
    }, 1000);
  }

  private injectCloseButton(toolbar: HTMLElement): void {
    if (toolbar.querySelector('.custom-close-btn-wrapper') || toolbar.querySelector('.custom-close-btn')) return;

    const wrapper = document.createElement('div');
    wrapper.className = 'custom-close-btn-wrapper';

    const btn = document.createElement('button');
    btn.className = 'custom-close-btn icon-btn';
    btn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`;

    // Attempt to translate, fallback to 'Close'
    try {
      btn.title = this.t.translate('common.close') || 'Close';
    } catch {
      btn.title = 'Close';
    }

    btn.onclick = () => {
      // Dispatching ESC doesn't always work natively for pdf.js. 
      // The most reliable way to close it is to click the tool that is currently active.
      const activeBtn = document.querySelector(`
        #editorHighlight.toggled, 
        #editorFreeText.toggled, 
        #editorInk.toggled,
        #editorStamp.toggled,
        .header-right button.toggled,
        .header-right button[aria-pressed="true"]
      `) as HTMLElement;

      if (activeBtn) {
        activeBtn.click();
      } else {
        // Fallback: dispatch a custom event or switch to hand tool if the button isn't found
        const editorNone = document.querySelector('#editorNone') as HTMLElement;
        if (editorNone) {
          editorNone.click();
        } else {
          // Last resort: click outside
          document.body.click();
        }
      }
    };

    wrapper.appendChild(btn);
    toolbar.prepend(wrapper);
  }

  async setViewerMode(mode: 'book' | 'document') {
    this.viewerMode = mode;
    if (mode === 'document') {
      setTimeout(() => this.initEmbedPdf(), 100);
    }
  }

  private async initEmbedPdf() {
    console.log('[EmbedPDF] initEmbedPdf() called. Instance exists?', !!this.embedPdfViewerInstance);
    if (this.embedPdfViewerInstance) return;

    try {
      // 1. Check Cross-Origin isolation (required for SharedArrayBuffer / pdfium WASM threads)
      const crossOriginIsolated = (self as any).crossOriginIsolated;
      console.log('[EmbedPDF] crossOriginIsolated:', crossOriginIsolated);
      if (!crossOriginIsolated) {
        console.warn(
          '[EmbedPDF] Page is NOT cross-origin isolated. ' +
          'pdfium WASM requires COOP/COEP headers:\n' +
          '  Cross-Origin-Opener-Policy: same-origin\n' +
          '  Cross-Origin-Embedder-Policy: require-corp\n' +
          'The viewer will stall during WASM instantiation without these.'
        );
      }

      // 2. Verify pdfium WASM is reachable
      const wasmUrl = '/assets/pdfium/pdfium.wasm';
      try {
        const wasmCheck = await fetch(wasmUrl, { method: 'HEAD' });
        console.log('[EmbedPDF] pdfium.wasm reachable:', wasmCheck.ok, `(${wasmCheck.status})`);
        if (!wasmCheck.ok) {
          console.error('[EmbedPDF] pdfium.wasm not found at', wasmUrl);
        }
      } catch (wasmErr) {
        console.error('[EmbedPDF] pdfium.wasm fetch failed:', wasmErr);
      }

      // 3. Fetch PDF content
      console.log(`[EmbedPDF] Fetching PDF from: ${this.bookData}`);
      const headers: Record<string, string> = {};
      if (this.authorization) {
        headers['Authorization'] = this.authorization;
      }

      const response = await fetch(this.bookData, {
        headers,
        credentials: 'include'
      });
      console.log(`[EmbedPDF] PDF fetch status: ${response.status}`);

      if (!response.ok) throw new Error(`PDF fetch failed: ${response.status}`);

      const buffer = await response.arrayBuffer();
      console.log(`[EmbedPDF] PDF buffer size: ${buffer.byteLength} bytes`);

      const blob = new Blob([buffer], { type: 'application/pdf' });
      const objectUrl = URL.createObjectURL(blob);

      // 4. Import EmbedPDF snippet
      console.log('[EmbedPDF] Importing @embedpdf/snippet...');
      // @ts-ignore
      const module = await import('@embedpdf/snippet');
      const EmbedPDF = module.default;
      console.log('[EmbedPDF] Module loaded. version:', EmbedPDF?.version, 'init:', typeof EmbedPDF?.init);

      const targetEl = document.getElementById('embedpdf-viewer');
      if (!targetEl) {
        throw new Error('#embedpdf-viewer element not found in DOM');
      }
      console.log('[EmbedPDF] Target element dimensions:', targetEl.clientWidth, 'x', targetEl.clientHeight);

      // 5. Initialize viewer
      console.log('[EmbedPDF] Calling EmbedPDF.init()');
      this.embedPdfViewerInstance = EmbedPDF.init({
        type: 'container',
        target: targetEl,
        src: objectUrl,
        wasmUrl,
        worker: false, // Disable worker - relative worker imports fail under Angular/Vite bundler
        theme: {
          preference: this.isDarkTheme ? 'dark' : 'light'
        }
      });
      console.log('[EmbedPDF] init() returned:', typeof this.embedPdfViewerInstance);

      // 6. Monitor rendering progress
      const checkRendering = (attempt: number) => {
        const container = targetEl.querySelector('embedpdf-container') as any;
        const shadowRoot = container?.shadowRoot;

        console.log(`[EmbedPDF] Render check #${attempt}:`);

        // Check engine and document-manager state via registry
        if (container?.registry) {
          container.registry.then((reg: any) => {
            const engine = reg.engine;
            const store = reg.store;
            const state = store?.getState?.();
            console.log('[EmbedPDF]   engine:', engine);
            console.log('[EmbedPDF]   engine.executor:', engine?.executor);
            console.log('[EmbedPDF]   store state core:', state?.core);
            console.log('[EmbedPDF]   store state core.documents:', state?.core?.documents);

            // Check document-manager plugin state
            const dmState = state?.plugins?.['document-manager'];
            console.log('[EmbedPDF]   document-manager plugin state:', dmState);

            // Check all plugin statuses
            const statuses: Record<string, string> = {};
            reg.status.forEach((v: string, k: string) => { statuses[k] = v; });
            console.log('[EmbedPDF]   plugin statuses:', statuses);

            // Try to get the document-manager plugin provides
            const dmPlugin = reg.plugins.get('document-manager');
            console.log('[EmbedPDF]   document-manager plugin:', dmPlugin);

            // Check for pending document loads
            const dmConfig = reg.configurations.get('document-manager');
            console.log('[EmbedPDF]   document-manager config:', dmConfig);
          }).catch((err: any) => {
            console.error('[EmbedPDF]   registry REJECTED:', err);
          });
        }

        // Check shadow DOM for loading/error indicators
        if (shadowRoot) {
          const allText = shadowRoot.textContent?.trim();
          const loadingEls = shadowRoot.querySelectorAll('[class*="loading"], [class*="spinner"], [class*="error"]');
          console.log(`[EmbedPDF]   shadow text (first 200): "${allText?.substring(0, 200)}"`);
          console.log(`[EmbedPDF]   loading/error elements: ${loadingEls.length}`);
          (Array.from(loadingEls) as Element[]).forEach((el, i) => {
            console.log(`[EmbedPDF]     [${i}] ${el.tagName}.${el.className}: "${el.textContent?.trim().substring(0, 100)}"`);
          });
        }
      };

      setTimeout(() => checkRendering(1), 3000);
      setTimeout(() => checkRendering(2), 8000);

    } catch (err) {
      console.error('[EmbedPDF] FATAL:', err);
      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('common.error'),
        detail: 'Failed to load Document Viewer. Check browser console for details.'
      });
    }
  }

  onPageChange(page: number): void {
    if (page !== this.page) {
      this.page = page;
      this.updateProgress();
      const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
      this.readingSessionService.updateProgress(this.page.toString(), percentage);
    }
  }

  onZoomChange(zoom: ZoomType): void {
    if (zoom !== this.zoom) {
      this.zoom = zoom;
      this.updateViewerSetting();
    }
  }

  onSpreadChange(spread: 'off' | 'even' | 'odd'): void {
    if (spread !== this.spread) {
      this.spread = spread;
      this.updateViewerSetting();
    }
  }

  toggleDarkTheme(): void {
    this.isDarkTheme = !this.isDarkTheme;
    this.updateViewerSetting();
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

  onPdfPagesLoaded(event: { pagesCount: number }): void {
    this.totalPages = event.pagesCount;
    const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
    this.readingSessionService.startSession(this.bookId, "PDF", this.page.toString(), percentage);
    this.readingSessionService.updateProgress(this.page.toString(), percentage);
    // Delay annotation loading to ensure annotation editor layers are initialized
    setTimeout(() => this.loadAnnotations(), 800);
  }

  onAnnotationEditorEvent(): void {
    if (this.annotationsLoaded) {
      this.annotationSaveSubject.next();
    }
  }

  ngOnDestroy(): void {
    this.wakeLockService.disable();
    if (this.chromeAutoHideTimer) clearTimeout(this.chromeAutoHideTimer);
    if (this.annotationToolbarObserver) this.annotationToolbarObserver.disconnect();
    if (this.readingSessionService.isSessionActive()) {
      const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
      this.readingSessionService.endSession(this.page.toString(), percentage);
    }

    this.annotationSaveSubscription?.unsubscribe();
    this.persistAnnotations();

    if (this.appSettingsSubscription) {
      this.appSettingsSubscription.unsubscribe();
    }
    this.updateProgress();
    document.removeEventListener('fullscreenchange', this.onFullscreenChange);
  }

  // --- Chrome auto-hide ---

  @HostListener('document:mousemove')
  onMouseMove(): void {
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

  // --- Fullscreen ---

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

  // --- Footer page navigation ---

  goToFirstPage(): void {
    this.page = 1;
    this.onPageChange(1);
  }

  goToPreviousPage(): void {
    if (this.page > 1) {
      const p = this.page - 1;
      this.page = p;
      this.onPageChange(p);
    }
  }

  goToNextPage(): void {
    if (this.page < this.totalPages) {
      const p = this.page + 1;
      this.page = p;
      this.onPageChange(p);
    }
  }

  goToLastPage(): void {
    this.page = this.totalPages;
    this.onPageChange(this.totalPages);
  }

  onSliderChange(event: Event): void {
    const value = +(event.target as HTMLInputElement).value;
    this.page = value;
    this.onPageChange(value);
  }

  onGoToPage(): void {
    if (this.goToPageInput && this.goToPageInput >= 1 && this.goToPageInput <= this.totalPages) {
      this.page = this.goToPageInput;
      this.onPageChange(this.goToPageInput);
      this.goToPageInput = null;
    }
  }

  // --- Rotation ---

  rotateClockwise(): void {
    this.rotation = ((this.rotation + 90) % 360) as 0 | 90 | 180 | 270;
  }

  closeReader = (): void => {
    // Save annotations while the PDF viewer is still alive (ngOnDestroy is too late)
    this.persistAnnotations();
    if (this.readingSessionService.isSessionActive()) {
      const percentage = this.totalPages > 0 ? Math.round((this.page / this.totalPages) * 1000) / 10 : 0;
      this.readingSessionService.endSession(this.page.toString(), percentage);
    }
    this.location.back();
  }

  private loadAnnotations(): void {
    this.pdfAnnotationService.getAnnotations(this.bookId).subscribe({
      next: async (response) => {
        if (response?.data) {
          try {
            const annotations = JSON.parse(response.data);
            if (Array.isArray(annotations)) {
              for (const annotation of annotations) {
                await this.pdfViewerService.addEditorAnnotation(annotation);
              }
            }
          } catch (e) {
            console.error('[PDF Annotations] Failed to load annotations:', e);
          }
        }
        this.annotationsLoaded = true;
      },
      error: () => {
        this.annotationsLoaded = true;
      }
    });
  }

  private persistAnnotations(): void {
    if (!this.annotationsLoaded || !this.bookId) {
      return;
    }
    try {
      const serialized = this.pdfViewerService.getSerializedAnnotations();
      if (serialized && serialized.length > 0) {
        const data = JSON.stringify(serialized);
        this.pdfAnnotationService.saveAnnotations(this.bookId, data).subscribe();
      }
    } catch (e) {
      console.error('[PDF Annotations] Failed to save annotations:', e);
    }
  }
}
