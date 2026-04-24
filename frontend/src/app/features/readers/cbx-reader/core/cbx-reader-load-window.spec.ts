import { describe, expect, it } from 'vitest';

import { buildCenteredWindow, extendHeadWindow, extendTailWindow } from './cbx-reader-load-window';

describe('cbx-reader-load-window', () => {
  it('builds centered window around current page', () => {
    expect(buildCenteredWindow(10, 100, 2)).toEqual([8, 9, 10, 11, 12]);
    expect(buildCenteredWindow(0, 5, 3)).toEqual([0, 1, 2, 3]);
  });

  it('extends tail and trims head when over max', () => {
    const result = extendTailWindow([1, 2, 3, 4], 20, 3, 5);
    expect(result.pages).toEqual([3, 4, 5, 6, 7]);
    expect(result.stats).toEqual({ loaded: 3, evicted: 2 });
  });

  it('extends head and trims tail when over max', () => {
    const result = extendHeadWindow([5, 6, 7, 8], 3, 5);
    expect(result.pages).toEqual([2, 3, 4, 5, 6]);
    expect(result.stats).toEqual({ loaded: 3, evicted: 2 });
  });
});

