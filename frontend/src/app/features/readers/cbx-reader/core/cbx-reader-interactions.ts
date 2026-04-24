import { CbxReadingDirection, CbxScrollMode } from '../../../settings/user-management/user.service';

export interface CbxNavigationAction {
  action: 'next' | 'previous' | 'menu' | 'scrollUp' | 'scrollDown' | 'none';
}

export function getSwipeNavigationAction(
  scrollMode: CbxScrollMode,
  readingDirection: CbxReadingDirection,
  deltaX: number,
  threshold: number,
  deltaY = 0,
  maxCrossAxisDelta = Number.POSITIVE_INFINITY
): CbxNavigationAction {
  if (scrollMode !== CbxScrollMode.PAGINATED) {
    return { action: 'none' };
  }
  if (Math.abs(deltaY) > maxCrossAxisDelta) {
    return { action: 'none' };
  }
  if (Math.abs(deltaX) < threshold) {
    return { action: 'none' };
  }

  const shouldGoNext = readingDirection === CbxReadingDirection.RTL ? deltaX > 0 : deltaX < 0;
  return { action: shouldGoNext ? 'next' : 'previous' };
}

export function getClickZoneNavigationAction(
  zone: 'prev' | 'next' | 'menu',
  scrollMode: CbxScrollMode
): CbxNavigationAction {
  if (zone === 'menu') {
    return { action: 'menu' };
  }
  if (scrollMode === CbxScrollMode.INFINITE || scrollMode === CbxScrollMode.LONG_STRIP) {
    return { action: zone === 'prev' ? 'scrollUp' : 'scrollDown' };
  }
  return { action: zone === 'prev' ? 'previous' : 'next' };
}

