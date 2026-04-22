import {Injectable, inject} from '@angular/core';
import {PreloadingStrategy, Route} from '@angular/router';
import {Observable, EMPTY, defer, timer, of} from 'rxjs';
import {filter, take, switchMap, catchError} from 'rxjs/operators';
import {toObservable} from '@angular/core/rxjs-interop';
import {BootstrapGateService} from '../../shared/service/bootstrap-gate.service';

/**
 * Preloads lazy route chunks after the first navigation completes.
 *
 * Routes opt in by setting `data: {preload: true}`. Routes can also opt out
 * of preloading entirely with `data: {preload: false}`. This keeps initial
 * navigation lean while still warming likely follow-up destinations in the
 * background once the critical path has painted.
 */
@Injectable({providedIn: 'root'})
export class PostBootstrapPreloadingStrategy implements PreloadingStrategy {
  private readonly bootstrapGate = inject(BootstrapGateService);
  private readonly bootstrapped$ = toObservable(this.bootstrapGate.hasBootstrapped).pipe(
    filter((v) => v),
    take(1),
  );

  preload(route: Route, load: () => Observable<unknown>): Observable<unknown> {
    if (route.data?.['preload'] === false) {
      return EMPTY;
    }
    if (route.data?.['preload'] !== true) {
      return EMPTY;
    }

    return defer(() =>
      this.bootstrapped$.pipe(
        switchMap(() => timer(250)),
        switchMap(() => load()),
        catchError((err) => {
          console.warn('[PostBootstrapPreloading] preload failed', err);
          return of(null);
        }),
      ),
    );
  }
}
