import {inject, Injectable, signal} from '@angular/core';
import {NavigationEnd, Router} from '@angular/router';
import {filter, take} from 'rxjs/operators';

/**
 * Exposes a `hasBootstrapped` signal that flips true once the first
 * route navigation completes, plus a timer fallback so services relying on
 * the gate don't stall forever if the router never emits NavigationEnd
 * (tests, early failures). Critical-path queries can gate their `enabled`
 * predicate on this signal to keep off-critical work off the initial
 * navigation waterfall.
 */
@Injectable({providedIn: 'root'})
export class BootstrapGateService {
  private readonly _hasBootstrapped = signal(false);
  private readonly router = inject(Router);

  readonly hasBootstrapped = this._hasBootstrapped.asReadonly();

  constructor() {
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        take(1),
      )
      .subscribe(() => this.markBootstrapped());

    if (typeof globalThis.window !== 'undefined') {
      const schedule = (globalThis.window as typeof window & {
        requestIdleCallback?: (cb: () => void, opts?: {timeout?: number}) => number;
      }).requestIdleCallback;
      if (schedule) {
        schedule(() => this.markBootstrapped(), {timeout: 2000});
      } else {
        globalThis.window.setTimeout(() => this.markBootstrapped(), 1500);
      }
    } else {
      this.markBootstrapped();
    }
  }

  markBootstrapped(): void {
    if (!this._hasBootstrapped()) {
      this._hasBootstrapped.set(true);
    }
  }
}
