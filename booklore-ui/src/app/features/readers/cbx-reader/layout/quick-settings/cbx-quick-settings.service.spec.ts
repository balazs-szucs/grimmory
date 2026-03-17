import {beforeEach, describe, expect, it} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {CbxQuickSettingsService} from './cbx-quick-settings.service';
import {
  CbxFitMode,
  CbxScrollMode,
  CbxPageViewMode,
  CbxPageSpread,
  CbxBackgroundColor,
  CbxReadingDirection,
  CbxSlideshowInterval,
  CbxMagnifierZoom,
  CbxMagnifierLensSize
} from '../../../../settings/user-management/user.service';
import {firstValueFrom} from 'rxjs';

describe('CbxQuickSettingsService', () => {
  let service: CbxQuickSettingsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [CbxQuickSettingsService],
    });
    service = TestBed.inject(CbxQuickSettingsService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('initial state', () => {
    it('should have correct default values', () => {
      const state = service.state;
      expect(state.fitMode).toBe(CbxFitMode.FIT_PAGE);
      expect(state.scrollMode).toBe(CbxScrollMode.PAGINATED);
      expect(state.pageViewMode).toBe(CbxPageViewMode.SINGLE_PAGE);
      expect(state.pageSpread).toBe(CbxPageSpread.ODD);
      expect(state.backgroundColor).toBe(CbxBackgroundColor.GRAY);
      expect(state.readingDirection).toBe(CbxReadingDirection.LTR);
      expect(state.slideshowInterval).toBe(CbxSlideshowInterval.FIVE_SECONDS);
      expect(state.magnifierZoom).toBe(CbxMagnifierZoom.ZOOM_3X);
      expect(state.magnifierLensSize).toBe(CbxMagnifierLensSize.MEDIUM);
    });

    it('should not be visible initially', () => {
      expect(service.isVisible).toBe(false);
    });
  });

  describe('visibility', () => {
    it('should become visible after show()', () => {
      service.show();
      expect(service.isVisible).toBe(true);
    });

    it('should hide after close()', () => {
      service.show();
      service.close();
      expect(service.isVisible).toBe(false);
    });

    it('should emit visibility changes via observable', async () => {
      const values: boolean[] = [];
      const sub = service.visible$.subscribe(v => values.push(v));
      service.show();
      service.close();
      sub.unsubscribe();
      expect(values).toEqual([false, true, false]);
    });
  });

  describe('updateState', () => {
    it('should merge partial state', () => {
      service.updateState({fitMode: CbxFitMode.FIT_WIDTH});
      expect(service.state.fitMode).toBe(CbxFitMode.FIT_WIDTH);
      expect(service.state.scrollMode).toBe(CbxScrollMode.PAGINATED);
    });

    it('should emit new state via observable', async () => {
      service.updateState({backgroundColor: CbxBackgroundColor.BLACK});
      const state = await firstValueFrom(service.state$);
      expect(state.backgroundColor).toBe(CbxBackgroundColor.BLACK);
    });
  });

  describe('individual setters', () => {
    it('setFitMode should update fitMode', () => {
      service.setFitMode(CbxFitMode.ACTUAL_SIZE);
      expect(service.state.fitMode).toBe(CbxFitMode.ACTUAL_SIZE);
    });

    it('setScrollMode should update scrollMode', () => {
      service.setScrollMode(CbxScrollMode.INFINITE);
      expect(service.state.scrollMode).toBe(CbxScrollMode.INFINITE);
    });

    it('setPageViewMode should update pageViewMode', () => {
      service.setPageViewMode(CbxPageViewMode.TWO_PAGE);
      expect(service.state.pageViewMode).toBe(CbxPageViewMode.TWO_PAGE);
    });

    it('setPageSpread should update pageSpread', () => {
      service.setPageSpread(CbxPageSpread.EVEN);
      expect(service.state.pageSpread).toBe(CbxPageSpread.EVEN);
    });

    it('setBackgroundColor should update backgroundColor', () => {
      service.setBackgroundColor(CbxBackgroundColor.WHITE);
      expect(service.state.backgroundColor).toBe(CbxBackgroundColor.WHITE);
    });

    it('setReadingDirection should update readingDirection', () => {
      service.setReadingDirection(CbxReadingDirection.RTL);
      expect(service.state.readingDirection).toBe(CbxReadingDirection.RTL);
    });

    it('setSlideshowInterval should update slideshowInterval', () => {
      service.setSlideshowInterval(CbxSlideshowInterval.TEN_SECONDS);
      expect(service.state.slideshowInterval).toBe(CbxSlideshowInterval.TEN_SECONDS);
    });

    it('setMagnifierZoom should update magnifierZoom', () => {
      service.setMagnifierZoom(CbxMagnifierZoom.ZOOM_4X);
      expect(service.state.magnifierZoom).toBe(CbxMagnifierZoom.ZOOM_4X);
    });

    it('setMagnifierLensSize should update magnifierLensSize', () => {
      service.setMagnifierLensSize(CbxMagnifierLensSize.LARGE);
      expect(service.state.magnifierLensSize).toBe(CbxMagnifierLensSize.LARGE);
    });
  });

  describe('emit change subjects', () => {
    it('emitFitModeChange should emit on fitModeChange$', () => {
      let emitted: CbxFitMode | undefined;
      const sub = service.fitModeChange$.subscribe(v => emitted = v);
      service.emitFitModeChange(CbxFitMode.AUTO);
      sub.unsubscribe();
      expect(emitted).toBe(CbxFitMode.AUTO);
    });

    it('emitScrollModeChange should emit on scrollModeChange$', () => {
      let emitted: CbxScrollMode | undefined;
      const sub = service.scrollModeChange$.subscribe(v => emitted = v);
      service.emitScrollModeChange(CbxScrollMode.LONG_STRIP);
      sub.unsubscribe();
      expect(emitted).toBe(CbxScrollMode.LONG_STRIP);
    });

    it('emitBackgroundColorChange should emit on backgroundColorChange$', () => {
      let emitted: CbxBackgroundColor | undefined;
      const sub = service.backgroundColorChange$.subscribe(v => emitted = v);
      service.emitBackgroundColorChange(CbxBackgroundColor.BLACK);
      sub.unsubscribe();
      expect(emitted).toBe(CbxBackgroundColor.BLACK);
    });

    it('emitReadingDirectionChange should emit on readingDirectionChange$', () => {
      let emitted: CbxReadingDirection | undefined;
      const sub = service.readingDirectionChange$.subscribe(v => emitted = v);
      service.emitReadingDirectionChange(CbxReadingDirection.RTL);
      sub.unsubscribe();
      expect(emitted).toBe(CbxReadingDirection.RTL);
    });
  });

  describe('reset', () => {
    it('should restore all defaults', () => {
      service.setFitMode(CbxFitMode.ACTUAL_SIZE);
      service.setScrollMode(CbxScrollMode.INFINITE);
      service.setBackgroundColor(CbxBackgroundColor.BLACK);
      service.show();

      service.reset();

      expect(service.state.fitMode).toBe(CbxFitMode.FIT_PAGE);
      expect(service.state.scrollMode).toBe(CbxScrollMode.PAGINATED);
      expect(service.state.backgroundColor).toBe(CbxBackgroundColor.GRAY);
      expect(service.isVisible).toBe(false);
    });
  });
});
