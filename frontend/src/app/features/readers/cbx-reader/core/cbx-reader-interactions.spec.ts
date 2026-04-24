import { describe, expect, it } from 'vitest';

import { CbxReadingDirection, CbxScrollMode } from '../../../settings/user-management/user.service';
import { getClickZoneNavigationAction, getSwipeNavigationAction } from './cbx-reader-interactions';

describe('cbx-reader-interactions', () => {
  describe('getSwipeNavigationAction', () => {
    it('ignores swipe in non paginated modes', () => {
      expect(getSwipeNavigationAction(CbxScrollMode.INFINITE, CbxReadingDirection.LTR, -120, 50).action).toBe('none');
    });

    it('maps swipe to next/previous in LTR', () => {
      expect(getSwipeNavigationAction(CbxScrollMode.PAGINATED, CbxReadingDirection.LTR, -120, 50).action).toBe('next');
      expect(getSwipeNavigationAction(CbxScrollMode.PAGINATED, CbxReadingDirection.LTR, 120, 50).action).toBe('previous');
    });

    it('maps swipe to next/previous in RTL', () => {
      expect(getSwipeNavigationAction(CbxScrollMode.PAGINATED, CbxReadingDirection.RTL, 120, 50).action).toBe('next');
      expect(getSwipeNavigationAction(CbxScrollMode.PAGINATED, CbxReadingDirection.RTL, -120, 50).action).toBe('previous');
    });

    it('ignores diagonal swipe when cross-axis exceeds guard', () => {
      expect(getSwipeNavigationAction(CbxScrollMode.PAGINATED, CbxReadingDirection.LTR, -120, 50, 80, 40).action).toBe('none');
    });
  });

  describe('getClickZoneNavigationAction', () => {
    it('returns menu action for center zone', () => {
      expect(getClickZoneNavigationAction('menu', CbxScrollMode.PAGINATED).action).toBe('menu');
    });

    it('returns page navigation in paginated mode', () => {
      expect(getClickZoneNavigationAction('prev', CbxScrollMode.PAGINATED).action).toBe('previous');
      expect(getClickZoneNavigationAction('next', CbxScrollMode.PAGINATED).action).toBe('next');
    });

    it('returns scroll actions in scroll modes', () => {
      expect(getClickZoneNavigationAction('prev', CbxScrollMode.INFINITE).action).toBe('scrollUp');
      expect(getClickZoneNavigationAction('next', CbxScrollMode.LONG_STRIP).action).toBe('scrollDown');
    });
  });
});

