import { computed, inject, Injectable, Injector, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, finalize } from 'rxjs';
import { RxStompService } from '../websocket/rx-stomp.service';
import { API_CONFIG } from '../../core/config/api-config';
import { createRxStompConfig } from '../websocket/rx-stomp.config';
import { Router } from '@angular/router';
import { PostLoginInitializerService } from '../../core/services/post-login-initializer.service';

@Injectable({
  providedIn: 'root',
})
export class AuthService {

  private apiUrl = `${API_CONFIG.BASE_URL}/api/v1/auth`;
  private rxStompService?: RxStompService;
  private readonly _postLoginInitialized = signal(false);
  readonly postLoginInitialized = this._postLoginInitialized.asReadonly();
  private readonly _logoutInProgress = signal(false);
  readonly logoutInProgress = this._logoutInProgress.asReadonly();

  private http = inject(HttpClient);
  private injector = inject(Injector);
  private router = inject(Router);
  private postLoginInitializer = inject(PostLoginInitializerService);

  readonly token = signal<string | null>(this.getInternalAccessToken());
  readonly isAuthenticated = computed(() => !!this.token());

  internalLogin(credentials: { username: string; password: string }): Observable<{ accessToken: string; refreshToken: string, isDefaultPassword: string }> {
    return this.http.post<{ accessToken: string; refreshToken: string, isDefaultPassword: string }>(`${this.apiUrl}/login`, credentials).pipe(
      tap((response) => {
        if (response.accessToken && response.refreshToken) {
          this.saveInternalTokens(response.accessToken, response.refreshToken);
          this.initializeWebSocketConnection();
          this.handleSuccessfulAuth();
        }
      })
    );
  }

  internalRefreshToken(): Observable<{ accessToken: string; refreshToken: string }> {
    const refreshToken = this.getInternalRefreshToken();
    return this.http.post<{ accessToken: string; refreshToken: string }>(`${this.apiUrl}/refresh`, { refreshToken }).pipe(
      tap((response) => {
        if (response.accessToken && response.refreshToken) {
          this.saveInternalTokens(response.accessToken, response.refreshToken);
        }
      })
    );
  }

  remoteLogin(): Observable<{ accessToken: string; refreshToken: string, isDefaultPassword: string }> {
    return this.http.get<{ accessToken: string; refreshToken: string, isDefaultPassword: string }>(`${this.apiUrl}/remote`).pipe(
      tap((response) => {
        if (response.accessToken && response.refreshToken) {
          this.saveInternalTokens(response.accessToken, response.refreshToken);
          this.initializeWebSocketConnection();
          this.handleSuccessfulAuth();
        }
      })
    );
  }

  saveInternalTokens(accessToken: string, refreshToken: string): void {
    localStorage.setItem('accessToken_Internal', accessToken);
    localStorage.setItem('refreshToken_Internal', refreshToken);
    this.token.set(accessToken);
  }

  getInternalAccessToken(): string | null {
    return localStorage.getItem('accessToken_Internal');
  }

  getInternalRefreshToken(): string | null {
    return localStorage.getItem('refreshToken_Internal');
  }

  logout(): void {
    if (this._logoutInProgress()) return;
    this._logoutInProgress.set(true);

    const refreshToken = this.getInternalRefreshToken();
    this.clearSession();

    this.http.post<{ logoutUrl: string | null }>(`${this.apiUrl}/logout`, { refreshToken })
      .pipe(finalize(() => {
        this._logoutInProgress.set(false);
      }))
      .subscribe({
        next: (response) => {
          if (response.logoutUrl) {
            window.location.href = response.logoutUrl;
          } else {
            window.location.replace('/login');
          }
        },
        error: () => {
          window.location.replace('/login');
        }
      });
  }

  forceLogout(reason: string): void {
    if (this._logoutInProgress()) return;
    this._logoutInProgress.set(true);
    this.clearSession();
    window.location.replace(`/login?reason=${encodeURIComponent(reason)}`);
    // Reset only for test cleanliness; in prod, the page will be torn down
    this._logoutInProgress.set(false);
  }

  clearSessionOnLoginPage(): void {
    this.clearSession();
  }

  private clearSession(): void {
    localStorage.removeItem('accessToken_Internal');
    localStorage.removeItem('refreshToken_Internal');
    this.token.set(null);
    this._postLoginInitialized.set(false);
    this.getRxStompService().deactivate();
  }

  getRxStompService(): RxStompService {
    if (!this.rxStompService) {
      this.rxStompService = this.injector.get(RxStompService);
    }
    return this.rxStompService;
  }

  initializeWebSocketConnection(): void {
    const token = this.getInternalAccessToken();
    if (!token) return;

    const stompService = this.getRxStompService();
    const config = createRxStompConfig(this);
    stompService.updateConfig(config);
    stompService.activate();

    if (!this.postLoginInitialized) {
      this.handleSuccessfulAuth();
    }
  }

  private handleSuccessfulAuth() {
    if (this._postLoginInitialized()) return;
    this._postLoginInitialized.set(true);
    this.postLoginInitializer.initialize().subscribe({
      next: () => console.log('AuthService: Post-login initialization completed'),
      error: (err) => console.error('AuthService: Post-login initialization failed:', err)
    });
  }
}

export function websocketInitializer(authService: AuthService): () => void {
  return () => authService.initializeWebSocketConnection();
}
