import { Book } from '../../../model/book.model';

export type IndexType = 'author' | 'category' | 'tag' | 'series' | 'library' | 'publisher';

export class InvertedIndex {
  private indexes = new Map<string, Map<unknown, Set<number>>>();

  constructor(books: Book[]) {
    this.build(books);
  }

  private build(books: Book[]): void {
    const types: IndexType[] = ['author', 'category', 'tag', 'series', 'library', 'publisher'];
    
    types.forEach(type => {
      this.indexes.set(type, new Map());
    });

    for (const book of books) {
      this.addToIndex('author', book.metadata?.authors, book.id);
      this.addToIndex('category', book.metadata?.categories, book.id);
      this.addToIndex('tag', book.metadata?.tags, book.id);
      this.addToIndex('series', book.metadata?.seriesName?.trim(), book.id);
      this.addToIndex('library', book.libraryId, book.id);
      this.addToIndex('publisher', book.metadata?.publisher, book.id);
    }
  }

  private addToIndex(type: IndexType, values: unknown | unknown[], bookId: number): void {
    const index = this.indexes.get(type)!;
    const valueArray = Array.isArray(values) ? values : [values];
    
    for (const val of valueArray) {
      if (val == null) continue;
      if (!index.has(val)) {
        index.set(val, new Set());
      }
      index.get(val)!.add(bookId);
    }
  }

  getMatchingIds(type: string, value: unknown): Set<number> | null {
    return this.indexes.get(type)?.get(value) ?? null;
  }
}
