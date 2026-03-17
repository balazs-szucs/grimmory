import {describe, expect, it} from 'vitest';
import {
  FILTER_EXTRACTORS,
  FILE_SIZE_RANGES,
  PAGE_COUNT_RANGES,
  MATCH_SCORE_RANGES,
  RATING_RANGES_5,
  RATING_OPTIONS_10,
  AGE_RATING_OPTIONS,
  READ_STATUS_LABELS,
  CONTENT_RATING_LABELS,
  NUMERIC_ID_FILTER_TYPES,
  FILTER_LABELS,
  FILTER_LABEL_KEYS,
  FILTER_CONFIGS,
} from './book-filter.config';
import {Book, ReadStatus} from '../../../model/book.model';

const createBook = (overrides: Partial<Book> = {}): Book => ({
  id: 1,
  libraryId: 1,
  libraryName: 'Test Library',
  metadata: {bookId: 1, title: 'Test Book'},
  shelves: [],
  ...overrides,
});

describe('book-filter.config', () => {

  describe('FILE_SIZE_RANGES', () => {
    it('should have contiguous ranges without gaps', () => {
      for (let i = 1; i < FILE_SIZE_RANGES.length; i++) {
        const gap = FILE_SIZE_RANGES[i].min - FILE_SIZE_RANGES[i - 1].max;
        expect(gap).toBeGreaterThanOrEqual(0);
      }
    });

    it('should start at 0 and end at Infinity', () => {
      expect(FILE_SIZE_RANGES[0].min).toBe(0);
      expect(FILE_SIZE_RANGES[FILE_SIZE_RANGES.length - 1].max).toBe(Infinity);
    });

    it('should have ascending sortIndex', () => {
      for (let i = 1; i < FILE_SIZE_RANGES.length; i++) {
        expect(FILE_SIZE_RANGES[i].sortIndex).toBeGreaterThan(FILE_SIZE_RANGES[i - 1].sortIndex);
      }
    });
  });

  describe('PAGE_COUNT_RANGES', () => {
    it('should have contiguous ranges', () => {
      for (let i = 1; i < PAGE_COUNT_RANGES.length; i++) {
        expect(PAGE_COUNT_RANGES[i].min).toBe(PAGE_COUNT_RANGES[i - 1].max);
      }
    });

    it('last range should go to Infinity', () => {
      expect(PAGE_COUNT_RANGES[PAGE_COUNT_RANGES.length - 1].max).toBe(Infinity);
    });
  });

  describe('RATING_RANGES_5', () => {
    it('should cover 0 to Infinity', () => {
      expect(RATING_RANGES_5[0].min).toBe(0);
      expect(RATING_RANGES_5[RATING_RANGES_5.length - 1].max).toBe(Infinity);
    });
  });

  describe('RATING_OPTIONS_10', () => {
    it('should have exactly 10 entries', () => {
      expect(RATING_OPTIONS_10).toHaveLength(10);
    });

    it('should have ids from 1 to 10', () => {
      RATING_OPTIONS_10.forEach((opt, i) => {
        expect(opt.id).toBe(i + 1);
      });
    });
  });

  describe('FILTER_EXTRACTORS', () => {

    describe('author extractor', () => {
      it('should extract author names from metadata', () => {
        const book = createBook({metadata: {bookId: 1, authors: ['Alice', 'Bob']}});
        const result = FILTER_EXTRACTORS.author(book);
        expect(result).toEqual([{id: 'Alice', name: 'Alice'}, {id: 'Bob', name: 'Bob'}]);
      });

      it('should return empty array when no authors', () => {
        const book = createBook({metadata: {bookId: 1}});
        expect(FILTER_EXTRACTORS.author(book)).toEqual([]);
      });
    });

    describe('category extractor', () => {
      it('should extract categories', () => {
        const book = createBook({metadata: {bookId: 1, categories: ['Fiction', 'Sci-Fi']}});
        const result = FILTER_EXTRACTORS.category(book);
        expect(result).toHaveLength(2);
        expect(result[0].name).toBe('Fiction');
      });
    });

    describe('series extractor', () => {
      it('should extract trimmed series name', () => {
        const book = createBook({metadata: {bookId: 1, seriesName: '  My Series  '}});
        const result = FILTER_EXTRACTORS.series(book);
        expect(result).toEqual([{id: 'My Series', name: 'My Series'}]);
      });

      it('should return empty for blank series', () => {
        const book = createBook({metadata: {bookId: 1, seriesName: '   '}});
        expect(FILTER_EXTRACTORS.series(book)).toEqual([]);
      });
    });

    describe('bookType extractor', () => {
      it('should extract book type from primary file', () => {
        const book = createBook({primaryFile: {id: 1, bookId: 1, bookType: 'PDF'}});
        const result = FILTER_EXTRACTORS.bookType(book);
        expect(result).toEqual([{id: 'PDF', name: 'PDF'}]);
      });

      it('should return PHYSICAL for physical books', () => {
        const book = createBook({isPhysical: true});
        const result = FILTER_EXTRACTORS.bookType(book);
        expect(result).toEqual([{id: 'PHYSICAL', name: 'PHYSICAL'}]);
      });
    });

    describe('readStatus extractor', () => {
      it('should extract read status', () => {
        const book = createBook({readStatus: ReadStatus.READ});
        const result = FILTER_EXTRACTORS.readStatus(book);
        expect(result).toEqual([{id: ReadStatus.READ, name: 'Read'}]);
      });

      it('should default to UNSET when no status', () => {
        const book = createBook({});
        const result = FILTER_EXTRACTORS.readStatus(book);
        expect(result[0].id).toBe(ReadStatus.UNSET);
      });
    });

    describe('personalRating extractor', () => {
      it('should extract valid rating 1-10', () => {
        const book = createBook({personalRating: 7});
        const result = FILTER_EXTRACTORS.personalRating(book);
        expect(result).toHaveLength(1);
        expect(result[0].id).toBe(7);
        expect(result[0].name).toBe('7');
      });

      it('should return empty for out-of-range rating', () => {
        expect(FILTER_EXTRACTORS.personalRating(createBook({personalRating: 0}))).toEqual([]);
        expect(FILTER_EXTRACTORS.personalRating(createBook({personalRating: 11}))).toEqual([]);
      });

      it('should return empty for null rating', () => {
        expect(FILTER_EXTRACTORS.personalRating(createBook({personalRating: null}))).toEqual([]);
      });
    });

    describe('matchScore extractor', () => {
      it('should normalize scores > 1 to 0-1 range', () => {
        const book = createBook({metadataMatchScore: 95});
        const result = FILTER_EXTRACTORS.matchScore(book);
        expect(result).toHaveLength(1);
        expect(result[0].name).toContain('Outstanding');
      });

      it('should handle scores already in 0-1 range', () => {
        const book = createBook({metadataMatchScore: 0.85});
        const result = FILTER_EXTRACTORS.matchScore(book);
        expect(result).toHaveLength(1);
        expect(result[0].name).toContain('Great');
      });

      it('should return empty for null score', () => {
        expect(FILTER_EXTRACTORS.matchScore(createBook({metadataMatchScore: null}))).toEqual([]);
      });
    });

    describe('fileSize extractor', () => {
      it('should map file size to correct range', () => {
        const book = createBook({primaryFile: {id: 1, bookId: 1, fileSizeKb: 5000}});
        const result = FILTER_EXTRACTORS.fileSize(book);
        expect(result).toHaveLength(1);
        expect(result[0].name).toBe('1–10 MB');
      });

      it('should handle very small files', () => {
        const book = createBook({primaryFile: {id: 1, bookId: 1, fileSizeKb: 500}});
        const result = FILTER_EXTRACTORS.fileSize(book);
        expect(result).toHaveLength(1);
        expect(result[0].name).toBe('< 1 MB');
      });

      it('should return empty when no primary file', () => {
        expect(FILTER_EXTRACTORS.fileSize(createBook())).toEqual([]);
      });
    });

    describe('shelf extractor', () => {
      it('should extract shelf ids and names', () => {
        const book = createBook({shelves: [{id: 1, name: 'Favorites'}, {id: 2, name: 'To Read'}]});
        const result = FILTER_EXTRACTORS.shelf(book);
        expect(result).toHaveLength(2);
        expect(result[0]).toEqual({id: 1, name: 'Favorites'});
      });
    });

    describe('shelfStatus extractor', () => {
      it('should return shelved when book has shelves', () => {
        const book = createBook({shelves: [{id: 1, name: 'Fav'}]});
        expect(FILTER_EXTRACTORS.shelfStatus(book)[0].id).toBe('shelved');
      });

      it('should return unshelved when no shelves', () => {
        const book = createBook({shelves: []});
        expect(FILTER_EXTRACTORS.shelfStatus(book)[0].id).toBe('unshelved');
      });
    });

    describe('publishedDate extractor', () => {
      it('should extract year from date', () => {
        const book = createBook({metadata: {bookId: 1, publishedDate: '2023-06-15'}});
        const result = FILTER_EXTRACTORS.publishedDate(book);
        expect(result).toEqual([{id: '2023', name: '2023'}]);
      });

      it('should return empty for missing date', () => {
        expect(FILTER_EXTRACTORS.publishedDate(createBook())).toEqual([]);
      });
    });

    describe('pageCount extractor', () => {
      it('should map page count to range', () => {
        const book = createBook({metadata: {bookId: 1, pageCount: 350}});
        const result = FILTER_EXTRACTORS.pageCount(book);
        expect(result).toHaveLength(1);
        expect(result[0].name).toBe('200–400 pages');
      });
    });

    describe('amazonRating extractor', () => {
      it('should map rating to 5-star range', () => {
        const book = createBook({metadata: {bookId: 1, amazonRating: 4.2}});
        const result = FILTER_EXTRACTORS.amazonRating(book);
        expect(result).toHaveLength(1);
        expect(result[0].name).toBe('4 to 4.5');
      });
    });

    describe('contentRating extractor', () => {
      it('should map known content rating', () => {
        const book = createBook({metadata: {bookId: 1, contentRating: 'MATURE'}});
        const result = FILTER_EXTRACTORS.contentRating(book);
        expect(result).toEqual([{id: 'MATURE', name: 'Mature'}]);
      });

      it('should use raw value for unknown rating', () => {
        const book = createBook({metadata: {bookId: 1, contentRating: 'CUSTOM'}});
        const result = FILTER_EXTRACTORS.contentRating(book);
        expect(result).toEqual([{id: 'CUSTOM', name: 'CUSTOM'}]);
      });
    });

    describe('ageRating extractor', () => {
      it('should match exact age rating option', () => {
        const book = createBook({metadata: {bookId: 1, ageRating: 13}});
        const result = FILTER_EXTRACTORS.ageRating(book);
        expect(result).toHaveLength(1);
        expect(result[0].name).toBe('13+');
      });

      it('should return empty for unknown age rating', () => {
        const book = createBook({metadata: {bookId: 1, ageRating: 5}});
        expect(FILTER_EXTRACTORS.ageRating(book)).toEqual([]);
      });
    });

    describe('comicCreator extractor', () => {
      it('should aggregate creators from all roles', () => {
        const book = createBook({
          metadata: {
            bookId: 1,
            comicMetadata: {
              pencillers: ['John'],
              inkers: ['Jane'],
              colorists: ['Bob']
            }
          }
        });
        const result = FILTER_EXTRACTORS.comicCreator(book);
        expect(result).toHaveLength(3);
        expect(result[0]).toEqual({id: 'John:penciller', name: 'John (Penciller)'});
        expect(result[1]).toEqual({id: 'Jane:inker', name: 'Jane (Inker)'});
        expect(result[2]).toEqual({id: 'Bob:colorist', name: 'Bob (Colorist)'});
      });

      it('should return empty when no comic metadata', () => {
        expect(FILTER_EXTRACTORS.comicCreator(createBook())).toEqual([]);
      });
    });

    describe('narrator extractor', () => {
      it('should extract narrator', () => {
        const book = createBook({metadata: {bookId: 1, narrator: 'Morgan Freeman'}});
        const result = FILTER_EXTRACTORS.narrator(book);
        expect(result).toEqual([{id: 'Morgan Freeman', name: 'Morgan Freeman'}]);
      });
    });

    describe('language extractor', () => {
      it('should extract language', () => {
        const book = createBook({metadata: {bookId: 1, language: 'en'}});
        expect(FILTER_EXTRACTORS.language(book)).toEqual([{id: 'en', name: 'en'}]);
      });
    });
  });

  describe('constants consistency', () => {
    it('READ_STATUS_LABELS should cover all ReadStatus values', () => {
      for (const status of Object.values(ReadStatus)) {
        expect(READ_STATUS_LABELS[status]).toBeDefined();
      }
    });

    it('FILTER_LABELS should have entries for all filter types', () => {
      const expectedTypes: string[] = [
        'author', 'category', 'series', 'bookType', 'readStatus',
        'personalRating', 'publisher', 'matchScore', 'library', 'shelf',
        'shelfStatus', 'tag', 'publishedDate', 'fileSize', 'amazonRating',
        'goodreadsRating', 'hardcoverRating', 'language', 'pageCount', 'mood',
        'ageRating', 'contentRating', 'narrator',
        'comicCharacter', 'comicTeam', 'comicLocation', 'comicCreator'
      ];
      for (const type of expectedTypes) {
        expect(FILTER_LABELS[type as keyof typeof FILTER_LABELS]).toBeDefined();
      }
    });

    it('FILTER_LABEL_KEYS should match FILTER_LABELS keys', () => {
      const labelKeys = Object.keys(FILTER_LABELS);
      const translationKeys = Object.keys(FILTER_LABEL_KEYS);
      expect(translationKeys.sort()).toEqual(labelKeys.sort());
    });

    it('FILTER_CONFIGS should match FILTER_EXTRACTORS keys', () => {
      const extractorKeys = Object.keys(FILTER_EXTRACTORS).sort();
      const configKeys = Object.keys(FILTER_CONFIGS).sort();
      expect(configKeys).toEqual(extractorKeys);
    });

    it('NUMERIC_ID_FILTER_TYPES should only reference valid filter types', () => {
      const allTypes = Object.keys(FILTER_LABELS);
      for (const type of NUMERIC_ID_FILTER_TYPES) {
        expect(allTypes).toContain(type);
      }
    });
  });
});
