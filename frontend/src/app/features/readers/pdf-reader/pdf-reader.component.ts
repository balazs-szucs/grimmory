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
    if (this.embedPdfViewerInstance) return;

    try {
      if (!(self as any).crossOriginIsolated) {
        console.warn('[EmbedPDF] Page is NOT cross-origin isolated. pdfium WASM needs COOP/COEP headers.');
      }

      const wasmUrl = '/assets/pdfium/pdfium.wasm';
      const headers: Record<string, string> = {};
      if (this.authorization) {
        headers['Authorization'] = this.authorization;
      }

      const response = await fetch(this.bookData, { headers, credentials: 'include' });
      if (!response.ok) throw new Error(`PDF fetch failed: ${response.status}`);

      const buffer = await response.arrayBuffer();
      const blob = new Blob([buffer], { type: 'application/pdf' });
      const objectUrl = URL.createObjectURL(blob);

      // @ts-ignore
      const module = await import('@embedpdf/snippet');
      const EmbedPDF = module.default;

      const targetEl = document.getElementById('embedpdf-viewer');
      if (!targetEl) throw new Error('#embedpdf-viewer not found');

      this.embedPdfViewerInstance = EmbedPDF.init({
        type: 'container',
        target: targetEl,
        src: objectUrl,
        wasmUrl,
        worker: false,
        theme: {
          preference: this.isDarkTheme ? 'dark' : 'light'
        }
      });

      // Restyle EmbedPDF chrome to match book viewer colour scheme
      this.styleEmbedPdfChrome(targetEl);

    } catch (err) {
      console.error('[EmbedPDF] FATAL:', err);
      this.messageService.add({
        severity: 'error',
        summary: this.t.translate('common.error'),
        detail: 'Failed to load Document Viewer. Check browser console for details.'
      });
    }
  }

  private styleEmbedPdfChrome(targetEl: HTMLElement): void {
    const inject = () => {
      const container = targetEl.querySelector('embedpdf-container') as HTMLElement;
      const shadowRoot = container?.shadowRoot;
      if (!shadowRoot) {
        setTimeout(inject, 200);
        return;
      }

      // Already injected?
      if (shadowRoot.querySelector('style[data-grimmory]')) return;

      const style = document.createElement('style');
      style.setAttribute('data-grimmory', '');
      style.textContent = `
        /* ── Grimmory theme overrides ── */

        /* Match the book viewer background */
        :host {
          --ep-background-app: #1a1a1a;
          --ep-background-surface: #2d2d2d;
          --ep-border-default: #404040;
          --ep-border-subtle: #333333;
          --ep-foreground-primary: rgba(255,255,255,0.95);
          --ep-foreground-secondary: rgba(255,255,255,0.60);
          --ep-accent-primary: #4a90e2;
        }

        :host([data-color-scheme="light"]) {
          --ep-background-app: #f5f5f5;
          --ep-background-surface: #ffffff;
          --ep-border-default: #d0d0d0;
          --ep-border-subtle: #e0e0e0;
          --ep-foreground-primary: rgba(0,0,0,0.87);
          --ep-foreground-secondary: rgba(0,0,0,0.54);
        }

        /* Restyle the top toolbar to match book viewer header */
        [class*="border-b"][class*="bg-bg-surface"][class*="px-4"][class*="py-2"] {
          background: linear-gradient(135deg, #2d2d2d 0%, #1f1f1f 100%) !important;
          border-bottom-color: #404040 !important;
          box-shadow: 0 2px 8px rgba(0,0,0,0.30) !important;
        }

        :host([data-color-scheme="light"]) [class*="border-b"][class*="bg-bg-surface"][class*="px-4"][class*="py-2"] {
          background: linear-gradient(135deg, #f0f0f0 0%, #e8e8e8 100%) !important;
          border-bottom-color: #d0d0d0 !important;
          box-shadow: 0 2px 8px rgba(0,0,0,0.10) !important;
        }

        /* Hide EmbedPDF bottom bar (page controls) - Grimmory footer handles navigation */
        [class*="border-t"][class*="bg-bg-surface"][class*="px-4"][class*="py-1"] {
          display: none !important;
        }
      `;
      shadowRoot.appendChild(style);
    };
    setTimeout(inject, 300);
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
    // Sync EmbedPDF theme if active
    if (this.embedPdfViewerInstance) {
      this.embedPdfViewerInstance.setTheme?.(this.isDarkTheme ? 'dark' : 'light');
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
