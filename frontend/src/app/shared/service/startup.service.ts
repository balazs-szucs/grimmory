import {Injectable, inject} from '@angular/core';
import {AuthService} from './auth.service';
import {UserService} from '../../features/settings/user-management/user.service';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {BookService} from '../../features/book/service/book.service';
import {API_CONFIG} from '../../core/config/api-config';

@Injectable({providedIn: 'root'})
export class StartupService {
  private authService = inject(AuthService);
  private userService = inject(UserService);
  private bookService = inject(BookService);
  private queryClient = inject(QueryClient);

  load(): Promise<void> {
    const token = this.authService.getInternalAccessToken();
    if (token) {
      this.predictivePreloadLcp(token);

      // Trigger user profile fetch in the background without blocking the startup chain.
      void this.queryClient.prefetchQuery(this.userService.getUserQueryOptions());
      // Trigger the heavy book list fetch immediately to get it moving in parallel with chunk loading.
      void this.queryClient.prefetchQuery(this.bookService.getBooksQueryOptions());
    }

    return Promise.resolve();
  }

  private predictivePreloadLcp(token: string): void {
    const raw = localStorage.getItem('lcp_book_candidate');
    if (!raw) return;

    try {
      const candidate = JSON.parse(raw);
      const updatedOn = candidate.isAudio ? candidate.audioUpdatedOn : candidate.updatedOn;
      if (!updatedOn) return;

      const endpoint = candidate.isAudio ? 'audiobook-thumbnail' : 'thumbnail';
      // Book cards render at ~232x325 CSS pixels; request a 240px-wide variant.
      const url = `${API_CONFIG.BASE_URL}/api/v1/media/book/${candidate.id}/${endpoint}?${updatedOn}&w=240&token=${token}`;

      const link = document.createElement('link');
      link.rel = 'preload';
      link.as = 'image';
      link.href = url;
      link.fetchPriority = 'high';
      document.head.appendChild(link);
    } catch (e) {
      console.warn('[Startup] Failed to parse LCP candidate', e);
    }
  }
}
