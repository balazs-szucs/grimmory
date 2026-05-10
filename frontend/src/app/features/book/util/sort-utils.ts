import { Book, ReadStatus } from '../model/book.model';
import { SortDirection, SortOption } from '../model/sort.model';

const READ_STATUS_RANK: Record<string, number> = {
  [ReadStatus.UNSET]: 0,
  [ReadStatus.UNREAD]: 1,
  [ReadStatus.READING]: 2,
  [ReadStatus.RE_READING]: 3,
  [ReadStatus.PARTIALLY_READ]: 4,
  [ReadStatus.PAUSED]: 5,
  [ReadStatus.READ]: 6,
  [ReadStatus.ABANDONED]: 7,
  [ReadStatus.WONT_READ]: 8,
};

const fieldExtractors: Record<string, (book: Book) => unknown> = {
  title: (book) => book.metadata?.title?.toLowerCase() || null,
  author: (book) => book.metadata?.authors?.map(a => a.toLowerCase()).join(", ") || null,
  authorSurnameVorname: (book) => book.metadata?.authors?.map(a => {
    const parts = a.trim().split(/\s+/);
    if (parts.length < 2) return a.toLowerCase();
    const surname = parts.pop();
    const firstname = parts.join(" ");
    return `${surname}, ${firstname}`.toLowerCase();
  }).join(", ") || null,
  publishedDate: (book) => {
    const date = book.metadata?.publishedDate;
    return date === null || date === undefined ? null : new Date(date).getTime();
  },
  publisher: (book) => book.metadata?.publisher || null,
  pageCount: (book) => book.metadata?.pageCount || null,
  rating: (book) => book.metadata?.rating || null,
  personalRating: (book) => book.personalRating || null,
  reviewCount: (book) => book.metadata?.reviewCount || null,
  amazonRating: (book) => book.metadata?.amazonRating || null,
  amazonReviewCount: (book) => book.metadata?.amazonReviewCount || null,
  goodreadsRating: (book) => book.metadata?.goodreadsRating || null,
  goodreadsReviewCount: (book) => book.metadata?.goodreadsReviewCount || null,
  hardcoverRating: (book) => book.metadata?.hardcoverRating || null,
  hardcoverReviewCount: (book) => book.metadata?.hardcoverReviewCount || null,
  ranobedbRating: (book) => book.metadata?.ranobedbRating || null,
  locked: (book) => book.metadata?.allMetadataLocked ?? false,
  lastReadTime: (book) => book.lastReadTime ? new Date(book.lastReadTime).getTime() : null,
  addedOn: (book) => book.addedOn ? new Date(book.addedOn).getTime() : null,
  fileSizeKb: (book) => book.fileSizeKb ?? book.primaryFile?.fileSizeKb ?? null,
  fileName: (book) => book.fileName ?? book.primaryFile?.fileName ?? null,
  filePath: (book) => book.filePath ?? book.primaryFile?.filePath ?? null,
  random: () => Math.random(),
  seriesName: (book) => book.metadata?.seriesName?.toLowerCase() || null,
  seriesNumber: (book) => book.metadata?.seriesNumber ?? null,
  readStatus: (book) => book.readStatus ? (READ_STATUS_RANK[book.readStatus] ?? null) : null,
  dateFinished: (book) => book.dateFinished ? new Date(book.dateFinished).getTime() : null,
  readingProgress: (book) =>
    book.epubProgress?.percentage
    ?? book.pdfProgress?.percentage
    ?? book.cbxProgress?.percentage
    ?? book.audiobookProgress?.percentage
    ?? book.koreaderProgress?.percentage
    ?? book.koboProgress?.percentage
    ?? null,
  bookType: (book) => book.primaryFile?.bookType || null,
  narrator: (book) => book.metadata?.narrator?.toLowerCase() || null,
};

function naturalCompare(a: string, b: string): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;

  const aStr = a.toString();
  const bStr = b.toString();

  const chunkRegex = /(\d+|\D+)/g;
  const aChunks = aStr.match(chunkRegex) || [aStr];
  const bChunks = bStr.match(chunkRegex) || [bStr];

  const maxLength = Math.max(aChunks.length, bChunks.length);

  for (let i = 0; i < maxLength; i++) {
    const aChunk = aChunks[i] || '';
    const bChunk = bChunks[i] || '';

    if (aChunk === '' && bChunk === '') continue;

    const aIsNumeric = /^\d+$/.test(aChunk);
    const bIsNumeric = /^\d+$/.test(bChunk);

    if (aIsNumeric && bIsNumeric) {
      const aNum = parseInt(aChunk, 10);
      const bNum = parseInt(bChunk, 10);
      if (aNum !== bNum) return aNum - bNum;
    } else {
      const comparison = aChunk.localeCompare(bChunk);
      if (comparison !== 0) return comparison;
    }
  }

  return aChunks.length - bChunks.length;
}

function compareValues(aValue: unknown, bValue: unknown): number {
  if (Array.isArray(aValue) && Array.isArray(bValue)) {
    for (let i = 0; i < aValue.length; i++) {
      const valA = aValue[i];
      const valB = bValue[i];
      if (typeof valA === 'string' && typeof valB === 'string') {
        const res = naturalCompare(valA, valB);
        if (res !== 0) return res;
      } else if (typeof valA === 'number' && typeof valB === 'number') {
        const res = valA - valB;
        if (res !== 0) return res;
      } else {
        if (valA == null && valB != null) return 1;
        if (valA != null && valB == null) return -1;
      }
    }
    return 0;
  } else if (typeof aValue === 'string' && typeof bValue === 'string') {
    return naturalCompare(aValue, bValue);
  } else if (typeof aValue === 'number' && typeof bValue === 'number') {
    return aValue - bValue;
  } else {
    if (aValue == null && bValue != null) return 1;
    if (aValue != null && bValue == null) return -1;
    return 0;
  }
}

function compareByCriterion(a: Book, b: Book, criterion: SortOption): number {
  const extractor = fieldExtractors[criterion.field];
  if (!extractor) return 0;

  const aValue = extractor(a);
  const bValue = extractor(b);
  const result = compareValues(aValue, bValue);

  return criterion.direction === SortDirection.ASCENDING ? result : -result;
}

export function multiSort(books: Book[], sortCriteria: SortOption[]): Book[] {
  if (!sortCriteria || sortCriteria.length === 0) return books;

  return books.slice().sort((a, b) => {
    for (const criterion of sortCriteria) {
      const result = compareByCriterion(a, b, criterion);
      if (result !== 0) return result;
    }
    return 0;
  });
}
