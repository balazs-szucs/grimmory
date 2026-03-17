import {beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {AudiobookService} from './audiobook.service';
import {AuthService} from '../../../shared/service/auth.service';

describe('AudiobookService', () => {
  let service: AudiobookService;
  let httpMock: HttpTestingController;
  let authServiceMock: { getInternalAccessToken: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    authServiceMock = {getInternalAccessToken: vi.fn().mockReturnValue('my-token')};

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {provide: AuthService, useValue: authServiceMock},
      ],
    });

    service = TestBed.inject(AudiobookService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getAudiobookInfo', () => {
    it('should GET audiobook info without bookType', () => {
      service.getAudiobookInfo(42).subscribe();
      const req = httpMock.expectOne(r =>
        r.url.includes('/api/v1/audiobooks/42/info') &&
        !r.params.has('bookType')
      );
      expect(req.request.method).toBe('GET');
      req.flush({});
    });

    it('should include bookType param when provided', () => {
      service.getAudiobookInfo(42, 'AUDIOBOOK').subscribe();
      const req = httpMock.expectOne(r =>
        r.url.includes('/42/info') &&
        r.params.get('bookType') === 'AUDIOBOOK'
      );
      expect(req).toBeTruthy();
      req.flush({});
    });
  });

  describe('getStreamUrl', () => {
    it('should build stream URL with token', () => {
      const url = service.getStreamUrl(10);
      expect(url).toContain('/api/v1/audiobooks/10/stream');
      expect(url).toContain('ngsw-bypass=true');
      expect(url).toContain('token=my-token');
    });

    it('should append trackIndex when provided', () => {
      const url = service.getStreamUrl(10, 3);
      expect(url).toContain('&trackIndex=3');
    });

    it('should not append trackIndex when undefined', () => {
      const url = service.getStreamUrl(10);
      expect(url).not.toContain('trackIndex');
    });

    it('should encode empty token', () => {
      authServiceMock.getInternalAccessToken.mockReturnValue('');
      const url = service.getStreamUrl(10);
      expect(url).toContain('token=');
    });
  });

  describe('getTrackStreamUrl', () => {
    it('should build track-specific stream URL', () => {
      const url = service.getTrackStreamUrl(7, 2);
      expect(url).toContain('/api/v1/audiobooks/7/track/2/stream');
      expect(url).toContain('token=my-token');
    });
  });

  describe('getEmbeddedCoverUrl', () => {
    it('should build cover URL with token', () => {
      const url = service.getEmbeddedCoverUrl(15);
      expect(url).toContain('/api/v1/audiobooks/15/cover');
      expect(url).toContain('token=my-token');
    });
  });

  describe('saveProgress', () => {
    it('should send audiobookProgress when no bookFileId', () => {
      const progress = {positionMs: 5000, percentage: 0.5, trackIndex: 1};
      service.saveProgress(42, progress).subscribe();

      const req = httpMock.expectOne(r =>
        r.url.includes('/api/v1/books/progress') && r.method === 'POST'
      );
      expect(req.request.body).toEqual({bookId: 42, audiobookProgress: progress});
      req.flush(null);
    });

    it('should send fileProgress when bookFileId is provided', () => {
      const progress = {positionMs: 3000, percentage: 0.3, trackIndex: 0};
      service.saveProgress(42, progress, 99).subscribe();

      const req = httpMock.expectOne(r => r.url.includes('/api/v1/books/progress'));
      expect(req.request.body.fileProgress).toBeDefined();
      expect(req.request.body.fileProgress.bookFileId).toBe(99);
      expect(req.request.body.fileProgress.positionData).toBe('3000');
      expect(req.request.body.fileProgress.positionHref).toBe('0');
      expect(req.request.body.fileProgress.progressPercent).toBe(0.3);
      req.flush(null);
    });
  });
});
