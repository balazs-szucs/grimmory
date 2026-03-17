import {beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {AuthorService} from './author.service';
import {AuthService} from '../../../shared/service/auth.service';
import {SseClient} from 'ngx-sse-client';

describe('AuthorService', () => {
  let service: AuthorService;
  let httpMock: HttpTestingController;
  let authServiceMock: { getInternalAccessToken: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    authServiceMock = {getInternalAccessToken: vi.fn().mockReturnValue('test-token')};

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {provide: AuthService, useValue: authServiceMock},
        {provide: SseClient, useValue: {}},
      ],
    });

    service = TestBed.inject(AuthorService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getAllAuthors', () => {
    it('should fetch all authors and cache them', () => {
      const mockAuthors = [{id: 1, name: 'Author 1'}, {id: 2, name: 'Author 2'}];
      let emitted: any;

      service.allAuthors$.subscribe(v => emitted = v);
      expect(emitted).toBeNull();

      service.getAllAuthors().subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/api/v1/authors') && r.method === 'GET');
      req.flush(mockAuthors);

      expect(emitted).toEqual(mockAuthors);
    });
  });

  describe('getAuthorDetails', () => {
    it('should fetch author details by id', () => {
      service.getAuthorDetails(42).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/api/v1/authors/42') && r.method === 'GET');
      expect(req).toBeTruthy();
      req.flush({id: 42, name: 'Test'});
    });
  });

  describe('getAuthorByName', () => {
    it('should pass name as query parameter', () => {
      service.getAuthorByName('Jane Austen').subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/by-name') && r.params.get('name') === 'Jane Austen');
      expect(req).toBeTruthy();
      req.flush({});
    });
  });

  describe('searchAuthorMetadata', () => {
    it('should use asin param when asin is provided', () => {
      service.searchAuthorMetadata(1, 'query', 'us', 'B001ASIN').subscribe();
      const req = httpMock.expectOne(r =>
        r.url.includes('/1/search-metadata') &&
        r.params.get('asin') === 'B001ASIN' &&
        r.params.get('region') === 'us' &&
        !r.params.has('q')
      );
      expect(req).toBeTruthy();
      req.flush([]);
    });

    it('should use q param when asin is not provided', () => {
      service.searchAuthorMetadata(1, 'search term', 'uk').subscribe();
      const req = httpMock.expectOne(r =>
        r.url.includes('/1/search-metadata') &&
        r.params.get('q') === 'search term' &&
        r.params.get('region') === 'uk' &&
        !r.params.has('asin')
      );
      expect(req).toBeTruthy();
      req.flush([]);
    });
  });

  describe('matchAuthor', () => {
    it('should POST match request', () => {
      const request = {asin: 'B001'};
      service.matchAuthor(5, request as any).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/5/match') && r.method === 'POST');
      expect(req.request.body).toEqual(request);
      req.flush({});
    });
  });

  describe('quickMatchAuthor', () => {
    it('should default region to us', () => {
      service.quickMatchAuthor(3).subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/3/quick-match') && r.params.get('region') === 'us');
      expect(req).toBeTruthy();
      req.flush({});
    });

    it('should use provided region', () => {
      service.quickMatchAuthor(3, 'uk').subscribe();
      const req = httpMock.expectOne(r => r.params.get('region') === 'uk');
      expect(req).toBeTruthy();
      req.flush({});
    });
  });

  describe('updateAuthor', () => {
    it('should PUT update request', () => {
      const request = {name: 'Updated'};
      service.updateAuthor(7, request as any).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/7') && r.method === 'PUT');
      expect(req.request.body).toEqual(request);
      req.flush({});
    });
  });

  describe('unmatchAuthors', () => {
    it('should POST author ids', () => {
      service.unmatchAuthors([1, 2, 3]).subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/unmatch') && r.method === 'POST');
      expect(req.request.body).toEqual([1, 2, 3]);
      req.flush(null);
    });
  });

  describe('deleteAuthors', () => {
    it('should DELETE with author ids in body', () => {
      service.deleteAuthors([4, 5]).subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/api/v1/authors') && r.method === 'DELETE');
      expect(req.request.body).toEqual([4, 5]);
      req.flush(null);
    });
  });

  describe('searchAuthorPhotos', () => {
    it('should GET with q query param', () => {
      service.searchAuthorPhotos(1, 'portrait').subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/1/search-photos') && r.params.get('q') === 'portrait');
      expect(req).toBeTruthy();
      req.flush([]);
    });
  });

  describe('uploadAuthorPhotoFromUrl', () => {
    it('should POST with url param', () => {
      service.uploadAuthorPhotoFromUrl(1, 'http://example.com/photo.jpg').subscribe();
      const req = httpMock.expectOne(r =>
        r.url.includes('/1/photo/url') &&
        r.params.get('url') === 'http://example.com/photo.jpg'
      );
      expect(req.request.method).toBe('POST');
      req.flush(null);
    });
  });

  describe('getUploadAuthorPhotoUrl', () => {
    it('should return upload URL for author', () => {
      const url = service.getUploadAuthorPhotoUrl(42);
      expect(url).toContain('/api/v1/authors/42/photo/upload');
    });
  });

  describe('getAuthorPhotoUrl', () => {
    it('should include token when available', () => {
      const url = service.getAuthorPhotoUrl(10);
      expect(url).toContain('/api/v1/media/author/10/photo');
      expect(url).toContain('token=test-token');
    });

    it('should omit token query param when no token', () => {
      authServiceMock.getInternalAccessToken.mockReturnValue(null);
      const url = service.getAuthorPhotoUrl(10);
      expect(url).toContain('/api/v1/media/author/10/photo');
      expect(url).not.toContain('token=');
    });
  });

  describe('getAuthorThumbnailUrl', () => {
    it('should include token and cache buster', () => {
      const url = service.getAuthorThumbnailUrl(5, 12345);
      expect(url).toContain('/api/v1/media/author/5/thumbnail');
      expect(url).toContain('token=test-token');
      expect(url).toContain('&t=12345');
    });

    it('should use ? for cache buster when no token', () => {
      authServiceMock.getInternalAccessToken.mockReturnValue(null);
      const url = service.getAuthorThumbnailUrl(5, 99);
      expect(url).not.toContain('token=');
      expect(url).toContain('?t=99');
    });

    it('should omit cache buster when not provided', () => {
      const url = service.getAuthorThumbnailUrl(5);
      expect(url).not.toContain('t=');
    });
  });
});
