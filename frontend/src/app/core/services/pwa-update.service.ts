import { inject, Injectable } from '@angular/core';
import { SwUpdate, VersionReadyEvent } from '@angular/service-worker';
import { filter } from 'rxjs/operators';

@Injectable({
    providedIn: 'root'
})
export class PwaUpdateService {
    private swUpdate = inject(SwUpdate);

    constructor() {
        if (this.swUpdate.isEnabled) {
            this.swUpdate.versionUpdates
                .pipe(filter((evt): evt is VersionReadyEvent => evt.type === 'VERSION_READY'))
                .subscribe(() => {
                    console.info('New version available. Refreshing...');
                    window.location.reload();
                });

            this.swUpdate.unrecoverable.subscribe(event => {
                console.error('Service Worker entered unrecoverable state:', event.reason);
                window.location.reload();
            });
        }
    }
}
