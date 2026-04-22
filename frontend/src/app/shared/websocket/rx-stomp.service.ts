import {DestroyRef, inject, Injectable} from '@angular/core';
import {RxStomp, RxStompConfig} from '@stomp/rx-stomp';
import {AuthService} from '../service/auth.service';
import {createRxStompConfig} from './rx-stomp.config';

@Injectable({
  providedIn: 'root',
})
export class RxStompService extends RxStomp {
  private authService = inject(AuthService);
  private deactivatedForBfCache = false;
  /** Prevents `pageshow` + `resume` (or either event firing twice) from double-activating. */
  private bfCacheActivateInFlight = false;

  constructor() {
    super();
    const stompConfig = createRxStompConfig(this.authService);
    this.configure(stompConfig);
    this.setupBfCacheListeners();
  }

  public updateConfig(config: RxStompConfig) {
    this.configure(config);
  }

  private setupBfCacheListeners(): void {
    if (typeof globalThis === 'undefined' || !('window' in globalThis)) return;

    const destroyRef = inject(DestroyRef);
    const win = globalThis.window;

    const handleSuspend = () => {
      if (this.active) {
        this.deactivatedForBfCache = true;
        this.deactivate();
      }
    };

    const handleResume = (persisted: boolean) => {
      if (!persisted) return;
      if (!this.deactivatedForBfCache) return;
      if (this.active) return;
      if (this.bfCacheActivateInFlight) return;
      if (!this.authService.isAuthenticated()) return;

      this.bfCacheActivateInFlight = true;
      this.deactivatedForBfCache = false;
      try {
        this.activate();
      } finally {
        // Activation is idempotent for our purposes; release the lock on the
        // next microtask so any closely-following duplicate event is ignored.
        queueMicrotask(() => {
          this.bfCacheActivateInFlight = false;
        });
      }
    };

    const onResume = () => handleResume(true);
    const onPageShow = (event: PageTransitionEvent) => handleResume(event.persisted);

    win.addEventListener('pagehide', handleSuspend);
    win.addEventListener('freeze', handleSuspend);
    win.addEventListener('resume', onResume);
    win.addEventListener('pageshow', onPageShow);

    destroyRef.onDestroy(() => {
      win.removeEventListener('pagehide', handleSuspend);
      win.removeEventListener('freeze', handleSuspend);
      win.removeEventListener('resume', onResume);
      win.removeEventListener('pageshow', onPageShow);
    });
  }
}
