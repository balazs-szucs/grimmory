import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it} from 'vitest';

import {CbxPageInfo, CbxReaderService} from './cbx-reader.service';

describe('CbxReaderService', () => {
  let service: CbxReaderService;
  let httpTestingController: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        CbxReaderService,
      ],
    });

    service = TestBed.inject(CbxReaderService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    TestBed.resetTestingModule();
  });

  it('requests available pages without token query parameter', () => {
    const response = [1, 2, 3];

    let result: number[] | undefined;
    service.getAvailablePages(7).subscribe(value => {
      result = value;
    });

    const request = httpTestingController.expectOne(req => req.urlWithParams.endsWith('/api/v1/cbx/7/pages'));
    expect(request.request.method).toBe('GET');
    request.flush(response);

    expect(result).toEqual(response);
  });

  it('includes book type when requesting page info', () => {
    const response: CbxPageInfo[] = [{pageNumber: 1, displayName: 'Cover'}];

    let result: CbxPageInfo[] | undefined;
    service.getPageInfo(9, 'CBX').subscribe(value => {
      result = value;
    });

    const request = httpTestingController.expectOne(req => req.urlWithParams.endsWith('/api/v1/cbx/9/page-info?bookType=CBX'));
    expect(request.request.method).toBe('GET');
    request.flush(response);

    expect(result).toEqual(response);
  });

  it('returns page image urls with book type when provided', () => {
    const url = service.getPageImageUrl(12, 4, 'CBX');

    expect(url).toBe('http://localhost:6060/api/v1/media/book/12/cbx/pages/4?bookType=CBX');
  });

  it('adds convert parameter when requested', () => {
    const url = service.getPageImageUrl(12, 4, 'CBX', 'jpeg');
    expect(url).toBe('http://localhost:6060/api/v1/media/book/12/cbx/pages/4?bookType=CBX&convert=jpeg');
  });
});
