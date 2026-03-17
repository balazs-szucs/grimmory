import {beforeEach, describe, expect, it} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {EmailV2ProviderService} from './email-v2-provider.service';
import {EmailProvider} from '../email-provider.model';

describe('EmailV2ProviderService', () => {
  let service: EmailV2ProviderService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(EmailV2ProviderService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getEmailProviders', () => {
    it('should GET all providers', () => {
      const mockProviders: Partial<EmailProvider>[] = [
        {id: 1, name: 'Gmail', host: 'smtp.gmail.com'},
        {id: 2, name: 'Outlook', host: 'smtp.outlook.com'}
      ];

      service.getEmailProviders().subscribe(providers => {
        expect(providers).toHaveLength(2);
        expect(providers[0].name).toBe('Gmail');
      });

      const req = httpMock.expectOne(r => r.url.includes('/api/v2/email/providers') && r.method === 'GET');
      req.flush(mockProviders);
    });
  });

  describe('createEmailProvider', () => {
    it('should POST new provider', () => {
      const provider = {id: 0, name: 'New', host: 'smtp.new.com', port: 587} as EmailProvider;

      service.createEmailProvider(provider).subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/api/v2/email/providers') && r.method === 'POST');
      expect(req.request.body).toEqual(provider);
      req.flush({...provider, id: 3});
    });
  });

  describe('updateProvider', () => {
    it('should PUT provider by id', () => {
      const provider = {id: 5, name: 'Updated', host: 'smtp.updated.com'} as EmailProvider;

      service.updateProvider(provider).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/providers/5') && r.method === 'PUT');
      expect(req.request.body).toEqual(provider);
      req.flush(provider);
    });
  });

  describe('deleteProvider', () => {
    it('should DELETE provider by id', () => {
      service.deleteProvider(7).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/providers/7') && r.method === 'DELETE');
      req.flush(null);
    });
  });

  describe('setDefaultProvider', () => {
    it('should PATCH set-default endpoint', () => {
      service.setDefaultProvider(3).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/providers/3/set-default') && r.method === 'PATCH');
      req.flush(null);
    });
  });
});
