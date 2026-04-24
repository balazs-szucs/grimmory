import {HttpClient} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {API_CONFIG} from '../../../core/config/api-config';

export interface CbxPageInfo {
  pageNumber: number;
  displayName: string;
}

@Injectable({providedIn: 'root'})
export class CbxReaderService {

  private readonly pagesUrl = `${API_CONFIG.BASE_URL}/api/v1/cbx`;
  private readonly imageUrl = `${API_CONFIG.BASE_URL}/api/v1/media/book`;
  private http = inject(HttpClient);

  getAvailablePages(bookId: number, bookType?: string) {
    let url = `${this.pagesUrl}/${bookId}/pages`;
    if (bookType) {
      url += `?bookType=${bookType}`;
    }
    return this.http.get<number[]>(url);
  }

  getPageInfo(bookId: number, bookType?: string) {
    let url = `${this.pagesUrl}/${bookId}/page-info`;
    if (bookType) {
      url += `?bookType=${bookType}`;
    }
    return this.http.get<CbxPageInfo[]>(url);
  }

  getPageImageUrl(bookId: number, page: number, bookType?: string, convert?: 'jpeg' | 'png'): string {
    let url = `${this.imageUrl}/${bookId}/cbx/pages/${page}`;
    const query = new URLSearchParams();
    if (bookType) {
      query.set('bookType', bookType);
    }
    if (convert) {
      query.set('convert', convert);
    }
    const qs = query.toString();
    return qs ? `${url}?${qs}` : url;
  }
}
