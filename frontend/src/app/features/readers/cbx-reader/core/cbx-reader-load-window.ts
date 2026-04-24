export interface LoadWindowStats {
  loaded: number;
  evicted: number;
}

export function buildCenteredWindow(currentPage: number, totalPages: number, radius: number): number[] {
  if (totalPages <= 0) {
    return [];
  }
  const start = Math.max(0, currentPage - radius);
  const end = Math.min(totalPages - 1, currentPage + radius);
  const pages: number[] = [];
  for (let p = start; p <= end; p++) {
    pages.push(p);
  }
  return pages;
}

export function extendTailWindow(
  existing: number[],
  totalPages: number,
  chunkSize: number,
  maxDomPages: number
): { pages: number[]; stats: LoadWindowStats } {
  if (existing.length === 0) {
    return { pages: [], stats: { loaded: 0, evicted: 0 } };
  }

  const last = existing[existing.length - 1];
  const next: number[] = [];
  for (let p = last + 1; p <= Math.min(totalPages - 1, last + chunkSize); p++) {
    next.push(p);
  }
  const combined = [...existing, ...next];
  if (combined.length <= maxDomPages) {
    return { pages: combined, stats: { loaded: next.length, evicted: 0 } };
  }
  const evicted = combined.length - maxDomPages;
  return { pages: combined.slice(-maxDomPages), stats: { loaded: next.length, evicted } };
}

export function extendHeadWindow(
  existing: number[],
  chunkSize: number,
  maxDomPages: number
): { pages: number[]; stats: LoadWindowStats } {
  if (existing.length === 0) {
    return { pages: [], stats: { loaded: 0, evicted: 0 } };
  }

  const first = existing[0];
  const newFirst = Math.max(0, first - chunkSize);
  const prepended: number[] = [];
  for (let p = newFirst; p < first; p++) {
    prepended.push(p);
  }
  const combined = [...prepended, ...existing];
  if (combined.length <= maxDomPages) {
    return { pages: combined, stats: { loaded: prepended.length, evicted: 0 } };
  }
  const evicted = combined.length - maxDomPages;
  return { pages: combined.slice(0, maxDomPages), stats: { loaded: prepended.length, evicted } };
}

