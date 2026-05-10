import {ageRatingRanges, fileSizeRanges, matchScoreRanges, pageCountRanges, ratingRanges} from '../book-filter/book-filter.config';
import {Book, ReadStatus} from '../../../model/book.model';
import {BookFilterMode} from '../../../model/filter.model';
import {InvertedIndex} from './inverted-index';

export function isRatingInRange(rating: number | undefined | null, rangeId: string | number): boolean {
  if (rating == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = ratingRanges.find(r => r.id === numericId);
  if (!range) return false;
  return rating >= range.min && rating < range.max;
}

export function isRatingInRange10(rating: number | undefined | null, rangeId: string | number): boolean {
  if (rating == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  return Math.round(rating) === numericId;
}

export function isFileSizeInRange(fileSizeKb: number | undefined, rangeId: string | number): boolean {
  if (fileSizeKb == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = fileSizeRanges.find(r => r.id === numericId);
  if (!range) return false;
  return fileSizeKb >= range.min && fileSizeKb < range.max;
}

export function isPageCountInRange(pageCount: number | undefined, rangeId: string | number): boolean {
  if (pageCount == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = pageCountRanges.find(r => r.id === numericId);
  if (!range) return false;
  return pageCount >= range.min && pageCount < range.max;
}

export function isMatchScoreInRange(score: number | undefined | null, rangeId: string | number): boolean {
  if (score == null) return false;
  const normalizedScore = score > 1 ? score / 100 : score;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = matchScoreRanges.find(r => r.id === numericId);
  if (!range) return false;
  return normalizedScore >= range.min && normalizedScore < range.max;
}

export function isAgeRatingInRange(ageRating: number | undefined | null, rangeId: string | number): boolean {
  if (ageRating == null) return false;
  const numericId = typeof rangeId === 'string' ? Number(rangeId) : rangeId;
  const range = ageRatingRanges.find(r => r.id === numericId);
  if (!range) return false;
  return ageRating >= range.min && ageRating < range.max;
}

export function doesBookMatchReadStatus(book: Book, selected: unknown[]): boolean {
  const status = book.readStatus ?? ReadStatus.UNSET;
  return selected.includes(status);
}

export function doesBookMatchFilter(
  book: Book,
  filterType: string,
  filterValues: unknown[],
  mode: BookFilterMode
): boolean {
  if (!Array.isArray(filterValues) || filterValues.length === 0) {
    return mode === 'or';
  }

  const effectiveMode = mode === 'not' ? 'or' : mode;

  switch (filterType) {
    case 'author':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.authors?.includes(val as string))
        : filterValues.every(val => book.metadata?.authors?.includes(val as string));
    case 'category':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.categories?.includes(val as string))
        : filterValues.every(val => book.metadata?.categories?.includes(val as string));
    case 'series':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.seriesName?.trim() === val)
        : filterValues.every(val => book.metadata?.seriesName?.trim() === val);
    case 'bookType':
      return book.isPhysical ? filterValues.includes('PHYSICAL') : filterValues.includes(book.primaryFile?.bookType);
    case 'readStatus':
      return doesBookMatchReadStatus(book, filterValues);
    case 'personalRating':
      return filterValues.some(range => isRatingInRange10(book.personalRating, range as string | number));
    case 'publisher':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.publisher === val)
        : filterValues.every(val => book.metadata?.publisher === val);
    case 'matchScore':
      return filterValues.some(range => isMatchScoreInRange(book.metadataMatchScore, range as string | number));
    case 'library':
      return effectiveMode === 'or'
        ? filterValues.some(val => val == book.libraryId)
        : filterValues.every(val => val == book.libraryId);
    case 'shelf':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.shelves?.some(s => s.id == val))
        : filterValues.every(val => book.shelves?.some(s => s.id == val));
    case 'shelfStatus': {
      const shelved = book.shelves && book.shelves.length > 0 ? 'shelved' : 'unshelved';
      return filterValues.includes(shelved);
    }
    case 'tag':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.tags?.includes(val as string))
        : filterValues.every(val => book.metadata?.tags?.includes(val as string));
    case 'publishedDate': {
      const bookYear = book.metadata?.publishedDate
        ? new Date(book.metadata.publishedDate).getFullYear()
        : null;
      return bookYear ? filterValues.some(val => val == bookYear || val == bookYear.toString()) : false;
    }
    case 'fileSize':
      return filterValues.some(range => isFileSizeInRange(book.fileSizeKb, range as string | number));
    case 'amazonRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.amazonRating, range as string | number));
    case 'goodreadsRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.goodreadsRating, range as string | number));
    case 'hardcoverRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.hardcoverRating, range as string | number));
    case 'lubimyczytacRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.lubimyczytacRating, range as string | number));
    case 'ranobedbRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.ranobedbRating, range as string | number));
    case 'audibleRating':
      return filterValues.some(range => isRatingInRange(book.metadata?.audibleRating, range as string | number));
    case 'language':
      return filterValues.includes(book.metadata?.language);
    case 'pageCount':
      return filterValues.some(range => isPageCountInRange(book.metadata?.pageCount ?? undefined, range as string | number));
    case 'mood':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.moods?.includes(val as string))
        : filterValues.every(val => book.metadata?.moods?.includes(val as string));
    case 'ageRating':
      return filterValues.some(range => isAgeRatingInRange(book.metadata?.ageRating, range as string | number));
    case 'contentRating':
      return filterValues.includes(book.metadata?.contentRating);
    case 'narrator':
      return filterValues.includes(book.metadata?.narrator);
    case 'comicCharacter':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.comicMetadata?.characters?.includes(val as string))
        : filterValues.every(val => book.metadata?.comicMetadata?.characters?.includes(val as string));
    case 'comicTeam':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.comicMetadata?.teams?.includes(val as string))
        : filterValues.every(val => book.metadata?.comicMetadata?.teams?.includes(val as string));
    case 'comicLocation':
      return effectiveMode === 'or'
        ? filterValues.some(val => book.metadata?.comicMetadata?.locations?.includes(val as string))
        : filterValues.every(val => book.metadata?.comicMetadata?.locations?.includes(val as string));
    case 'comicCreator': {
      const comic = book.metadata?.comicMetadata;
      if (!comic) return false;
      const allCreators: string[] = [];
      const roles: [string[] | undefined, string][] = [
        [comic.pencillers, 'penciller'],
        [comic.inkers, 'inker'],
        [comic.colorists, 'colorist'],
        [comic.letterers, 'letterer'],
        [comic.coverArtists, 'coverArtist'],
        [comic.editors, 'editor']
      ];
      for (const [names, role] of roles) {
        if (names) {
          for (const name of names) {
            allCreators.push(`${name}:${role}`);
          }
        }
      }
      return effectiveMode === 'or'
        ? filterValues.some(val => allCreators.includes(val as string))
        : filterValues.every(val => allCreators.includes(val as string));
    }
    default:
      return false;
  }
}

export function filterBooksByFilters(
  books: Book[],
  activeFilters: Record<string, unknown[]> | null,
  mode: BookFilterMode,
  excludeFilterType?: string,
  index?: InvertedIndex
): Book[] {
  if (!activeFilters) return books;

  const filterEntries = Object.entries(activeFilters)
    .filter(([type]) => type !== excludeFilterType && Array.isArray(activeFilters[type]) && activeFilters[type].length > 0);

  if (filterEntries.length === 0) return books;

  // Optimize using index if available and mode is 'and' or 'or'
  if (index && (mode === 'and' || mode === 'or')) {
    const indexedEntries = filterEntries.filter(([type]) => 
      ['author', 'category', 'tag', 'series', 'library', 'publisher'].includes(type)
    );
    const nonIndexedEntries = filterEntries.filter(([type]) => 
      !['author', 'category', 'tag', 'series', 'library', 'publisher'].includes(type)
    );

    // If all filters are indexed, we can potentially be even faster, 
    // but for now let's just use the sets to speed up the loop.
    const entrySets = indexedEntries.map(([type, values]) => {
      const sets = values.map(v => index.getMatchingIds(type, v)).filter((s): s is Set<number> => !!s);
      
      if (mode === 'or') {
        const union = new Set<number>();
        sets.forEach(s => s.forEach(id => union.add(id)));
        return union;
      } else {
        // 'and' mode: each value must match
        if (sets.length < values.length) return new Set<number>(); // Some value had no matches
        if (sets.length === 0) return new Set<number>();
        
        const intersection = new Set<number>(sets[0]);
        for (let i = 1; i < sets.length; i++) {
          for (const id of intersection) {
            if (!sets[i].has(id)) {
              intersection.delete(id);
            }
          }
        }
        return intersection;
      }
    });

    return books.filter(book => {
      if (mode === 'or') {
        // Match any indexed entry
        for (const set of entrySets) {
          if (set.has(book.id)) return true;
        }
        // Match any non-indexed entry
        for (const [filterType, filterValues] of nonIndexedEntries) {
          if (doesBookMatchFilter(book, filterType, filterValues, mode)) return true;
        }
        return false;
      } else {
        // 'and' mode: must match ALL indexed entries
        for (const set of entrySets) {
          if (!set.has(book.id)) return false;
        }
        // Must match ALL non-indexed entries
        for (const [filterType, filterValues] of nonIndexedEntries) {
          if (!doesBookMatchFilter(book, filterType, filterValues, mode)) return false;
        }
        return true;
      }
    });
  }

  return books.filter(book => matchesAllFilters(book, filterEntries, mode));
}

function matchesAllFilters(
  book: Book,
  filterEntries: [string, unknown[]][],
  mode: BookFilterMode
): boolean {
  if (mode === 'or') {
    for (const [filterType, filterValues] of filterEntries) {
      if (doesBookMatchFilter(book, filterType, filterValues, mode)) {
        return true;
      }
    }

    return false;
  }

  if (mode === 'not') {
    for (const [filterType, filterValues] of filterEntries) {
      if (doesBookMatchFilter(book, filterType, filterValues, mode)) {
        return false;
      }
    }

    return true;
  }

  for (const [filterType, filterValues] of filterEntries) {
    if (!doesBookMatchFilter(book, filterType, filterValues, mode)) {
      return false;
    }
  }

  return true;
}
