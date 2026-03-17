import {beforeEach, describe, expect, it} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {EmailV2RecipientService} from './email-v2-recipient.service';
import {EmailRecipient} from '../email-recipient.model';

describe('EmailV2RecipientService', () => {
  let service: EmailV2RecipientService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(EmailV2RecipientService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getRecipients', () => {
    it('should GET all recipients', () => {
      const mockRecipients: Partial<EmailRecipient>[] = [
        {id: 1, email: 'alice@example.com', name: 'Alice'},
        {id: 2, email: 'bob@example.com', name: 'Bob'}
      ];

      service.getRecipients().subscribe(recipients => {
        expect(recipients).toHaveLength(2);
        expect(recipients[0].email).toBe('alice@example.com');
      });

      const req = httpMock.expectOne(r => r.url.includes('/api/v2/email/recipients') && r.method === 'GET');
      req.flush(mockRecipients);
    });
  });

  describe('createRecipient', () => {
    it('should POST new recipient', () => {
      const recipient = {id: 0, email: 'new@example.com', name: 'New'} as EmailRecipient;

      service.createRecipient(recipient).subscribe();
      const req = httpMock.expectOne(r => r.url.includes('/api/v2/email/recipients') && r.method === 'POST');
      expect(req.request.body).toEqual(recipient);
      req.flush({...recipient, id: 10});
    });
  });

  describe('updateRecipient', () => {
    it('should PUT recipient by id', () => {
      const recipient = {id: 5, email: 'updated@example.com', name: 'Updated'} as EmailRecipient;

      service.updateRecipient(recipient).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/recipients/5') && r.method === 'PUT');
      expect(req.request.body).toEqual(recipient);
      req.flush(recipient);
    });
  });

  describe('deleteRecipient', () => {
    it('should DELETE recipient by id', () => {
      service.deleteRecipient(8).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/recipients/8') && r.method === 'DELETE');
      req.flush(null);
    });
  });

  describe('setDefaultRecipient', () => {
    it('should PATCH set-default endpoint', () => {
      service.setDefaultRecipient(4).subscribe();
      const req = httpMock.expectOne(r => r.url.endsWith('/recipients/4/set-default') && r.method === 'PATCH');
      req.flush(null);
    });
  });
});
