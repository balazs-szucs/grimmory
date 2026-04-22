import {Injectable, inject} from '@angular/core';
import {AuthService} from './auth.service';
import {UserService} from '../../features/settings/user-management/user.service';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {BookService} from '../../features/book/service/book.service';

@Injectable({providedIn: 'root'})
export class StartupService {
  private authService = inject(AuthService);
  private userService = inject(UserService);
  private bookService = inject(BookService);
  private queryClient = inject(QueryClient);

  load(): Promise<void> {
    if (this.authService.token()) {
      // Trigger user profile fetch in the background without blocking the startup chain.
      void this.queryClient.prefetchQuery(this.userService.getUserQueryOptions());
      // Trigger the heavy book list fetch immediately to get it moving in parallel with chunk loading.
      void this.queryClient.prefetchQuery(this.bookService.getBooksQueryOptions());
    }

    return Promise.resolve();
  }
}
