import { filterBooksBySearchTerm } from './HeaderFilter';
import { filterBooksByFilters } from './sidebar-filter';
import { Book } from '../../../model/book.model';
import { InvertedIndex } from './inverted-index';

let cachedBooks: Book[] = [];
let cachedIndex: InvertedIndex | null = null;

addEventListener('message', ({ data }) => {
  const { books, searchTerm, activeFilters, mode, excludeFilterType, requestId } = data;

  if (books) {
    cachedBooks = books;
    cachedIndex = new InvertedIndex(books);
  }

  let result = cachedBooks;

  if (searchTerm) {
    result = filterBooksBySearchTerm(result, searchTerm);
  }

  result = filterBooksByFilters(result, activeFilters, mode, excludeFilterType, cachedIndex || undefined);
  postMessage({ filteredBooks: result, requestId });
});
