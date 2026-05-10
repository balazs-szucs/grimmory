import {Injectable} from '@angular/core';
import {Book} from '../model/book.model';
import {SortOption} from "../model/sort.model";
import {multiSort} from '../util/sort-utils';

@Injectable({
  providedIn: 'root',
})
export class SortService {
  applySort(books: Book[], selectedSort: SortOption | null): Book[] {
    if (!selectedSort) return books;
    return this.applyMultiSort(books, [selectedSort]);
  }

  applyMultiSort(books: Book[], sortCriteria: SortOption[]): Book[] {
    return multiSort(books, sortCriteria);
  }
}
