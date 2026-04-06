import {Component, ElementRef, EventEmitter, Input, OnDestroy, Output, ViewChild} from '@angular/core';
import {CommonModule} from '@angular/common';
import {TranslocoDirective} from '@jsverse/transloco';
import {ReaderIconComponent} from './icon.component';

export type AnnotationStyle = 'highlight' | 'underline' | 'strikethrough' | 'squiggly';

export interface TextSelectionAction {
  type: 'select' | 'annotate' | 'delete' | 'dismiss' | 'preview' | 'search' | 'note';
  color?: string;
  style?: AnnotationStyle;
  annotationId?: number;
  searchText?: string;
}

/** Responsive popup dimensions based on viewport width */
function getPopupDimensions(viewportWidth: number): {width: number; height: number; margin: number} {
  // On very small screens the popup naturally shrinks due to fewer visible buttons;
  // these estimates are conservative so the clamping logic errs on the side of safety.
  if (viewportWidth < 375) {
    return {width: 156, height: 44, margin: 16};
  }
  if (viewportWidth < 768) {
    return {width: 180, height: 44, margin: 12};
  }
  return {width: 220, height: 44, margin: 8};
}

/** Read CSS safe-area insets (env() values) with pixel fallbacks */
function getSafeAreaInsets(): {top: number; right: number; bottom: number; left: number} {
  const root = getComputedStyle(document.documentElement);
  const parse = (name: string) => {
    const val = root.getPropertyValue(name).trim();
    // env() values look like "env(safe-area-inset-top)" cannot be parsed as px
    // If the browser has already resolved them to pixels, parse; otherwise 0.
    if (val.endsWith('px')) return parseFloat(val) || 0;
    return 0;
  };
  return {top: parse('--sat'), right: parse('--sar'), bottom: parse('--sab'), left: parse('--sal')};
}

@Component({
  selector: 'app-text-selection-popup',
  standalone: true,
  imports: [CommonModule, TranslocoDirective, ReaderIconComponent],
  templateUrl: './selection-popup.component.html',
  styleUrls: ['./selection-popup.component.scss']
})
export class TextSelectionPopupComponent implements OnDestroy {
  @ViewChild('annotationOptionsEl', {read: ElementRef}) annotationOptionsEl!: ElementRef<HTMLElement>;

  private pendingAnimationFrame = 0;

  @Input() set visible(value: boolean) {
    this._visible = value;
    if (value) {
      this.showAnnotationOptions = false;
      this.hasPreview = false;
      this.annotationOptionsLeft = 50;
    } else {
      this.cancelPendingAnimationFrame();
    }
  }
  get visible(): boolean {
    return this._visible;
  }
  private _visible = false;

  @Input() position = {x: 0, y: 0};
  @Input() showBelow = false;
  @Input() overlappingAnnotationId: number | null = null;
  @Input() selectedText = '';
  @Output() action = new EventEmitter<TextSelectionAction>();

  showAnnotationOptions = false;
  selectedColor = '#FACC15';
  selectedStyle: AnnotationStyle = 'highlight';
  private hasPreview = false;
  annotationOptionsLeft = 50;
  annotationOptionsTop: number | null = null;

  highlightColors = [
    {value: '#FACC15', label: 'Yellow'},
    {value: '#4ADE80', label: 'Green'},
    {value: '#38BDF8', label: 'Blue'},
    {value: '#F472B6', label: 'Pink'},
    {value: '#FB923C', label: 'Orange'}
  ];

  lineColors = [
    {value: '#B8860B', label: 'Dark Gold'},
    {value: '#228B22', label: 'Forest Green'},
    {value: '#1E90FF', label: 'Dodger Blue'},
    {value: '#DC143C', label: 'Crimson'},
    {value: '#FF8C00', label: 'Dark Orange'}
  ];

  get colors() {
    return this.selectedStyle === 'highlight' ? this.highlightColors : this.lineColors;
  }

  styles: { value: AnnotationStyle; label: string; icon: string }[] = [
    {value: 'highlight', label: 'Highlight', icon: 'H'},
    {value: 'underline', label: 'Underline', icon: 'U'},
    {value: 'squiggly', label: 'Squiggly', icon: '~'},
    {value: 'strikethrough', label: 'Strikethrough', icon: 'S'}
  ];

  ngOnDestroy(): void {
    this.cancelPendingAnimationFrame();
  }

  onSelect(): void {
    this.action.emit({type: 'select'});
    this.showAnnotationOptions = false;
    this.hasPreview = false;
  }

  toggleAnnotationOptions(): void {
    this.showAnnotationOptions = !this.showAnnotationOptions;
    if (this.showAnnotationOptions) {
      this.emitPreview();
      // Defer clamping until after the browser has painted the new element
      this.clampAnnotationOptionsAfterPaint();
    } else {
      this.cancelPendingAnimationFrame();
      this.annotationOptionsLeft = 50;
      this.annotationOptionsTop = null;
    }
  }

  /**
   * Uses requestAnimationFrame to ensure the element has been painted
   * before reading its dimensions. Cancels any pending previous frame.
   */
  private clampAnnotationOptionsAfterPaint(): void {
    this.cancelPendingAnimationFrame();
    this.pendingAnimationFrame = requestAnimationFrame(() => {
      this.pendingAnimationFrame = 0;
      this.doClampAnnotationOptions();
    });
  }

  private cancelPendingAnimationFrame(): void {
    if (this.pendingAnimationFrame) {
      cancelAnimationFrame(this.pendingAnimationFrame);
      this.pendingAnimationFrame = 0;
    }
  }

  private doClampAnnotationOptions(): void {
    const el = this.annotationOptionsEl?.nativeElement;
    if (!el) return;

    const rect = el.getBoundingClientRect();
    const popupWidth = rect.width;
    const popupHeight = rect.height;
    if (!popupWidth || !popupHeight) return;

    const parentRect = el.parentElement?.getBoundingClientRect();
    if (!parentRect) return;

    const dims = getPopupDimensions(window.innerWidth);
    const safeArea = getSafeAreaInsets();
    const margin = dims.margin;

    // --- Horizontal Clamping ---
    const safeLeftMargin = margin + safeArea.left;
    const idealCenterX = parentRect.left + parentRect.width / 2;
    const idealLeft = idealCenterX - popupWidth / 2;

    const clampedLeft = Math.max(
      safeLeftMargin,
      Math.min(idealLeft, window.innerWidth - popupWidth - margin - safeArea.right)
    );

    this.annotationOptionsLeft = ((clampedLeft - parentRect.left) / parentRect.width) * 100;

    // --- Vertical Clamping ---
    const gap = 8;
    const safeTopMargin = margin + safeArea.top;
    const safeBottomMargin = margin + safeArea.bottom;

    // Ideal vertical position based on above/below decision
    let idealTop: number;
    if (this.showBelow) {
      idealTop = parentRect.bottom + gap;
    } else {
      idealTop = parentRect.top - popupHeight - gap;
    }

    const clampedTop = Math.max(
      safeTopMargin,
      Math.min(idealTop, window.innerHeight - popupHeight - safeBottomMargin)
    );

    this.annotationOptionsTop = ((clampedTop - parentRect.top) / parentRect.height) * 100;
  }

  selectColor(color: string): void {
    this.selectedColor = color;
    this.emitPreview();
  }

  selectStyle(style: AnnotationStyle): void {
    const wasHighlight = this.selectedStyle === 'highlight';
    const isHighlight = style === 'highlight';

    this.selectedStyle = style;

    if (wasHighlight !== isHighlight) {
      this.selectedColor = isHighlight ? this.highlightColors[0].value : this.lineColors[0].value;
    }
    this.emitPreview();
  }

  private emitPreview(): void {
    this.hasPreview = true;
    this.action.emit({
      type: 'preview',
      color: this.selectedColor,
      style: this.selectedStyle
    });
  }

  onDelete(): void {
    if (this.overlappingAnnotationId) {
      this.action.emit({type: 'delete', annotationId: this.overlappingAnnotationId});
    }
  }

  onSearch(): void {
    this.action.emit({type: 'search', searchText: this.selectedText});
    this.showAnnotationOptions = false;
    this.hasPreview = false;
  }

  onNote(): void {
    this.action.emit({type: 'note'});
    this.showAnnotationOptions = false;
    this.hasPreview = false;
  }

  onDismiss(event: Event): void {
    event.stopPropagation();
    event.preventDefault();

    if (this.hasPreview) {
      this.action.emit({
        type: 'annotate',
        color: this.selectedColor,
        style: this.selectedStyle
      });
    } else {
      this.action.emit({type: 'dismiss'});
    }

    this.showAnnotationOptions = false;
    this.hasPreview = false;
  }
}
