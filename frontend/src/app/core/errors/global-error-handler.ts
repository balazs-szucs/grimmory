import { ErrorHandler, Injectable } from '@angular/core';

@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
    handleError(error: unknown): void {
        const err = error as Error;
        const message = err.message || '';
        const name = err.name || '';

        const chunkFailedMessage = /Loading chunk [\d]+ failed/;
        const moduleFailedMessage = /Loading failed for the module/;

        if (chunkFailedMessage.test(message) || moduleFailedMessage.test(message) || name === 'ChunkLoadError') {
            console.error('Chunk/Module load failed. Forcing reload to fetch latest version...', error);

            // Prevent infinite reload loop if the reload itself fails
            const lastReload = localStorage.getItem('last_chunk_error_reload');
            const now = Date.now();
            if (!lastReload || now - parseInt(lastReload, 10) > 10000) {
                localStorage.setItem('last_chunk_error_reload', now.toString());
                window.location.reload();
            } else {
                console.error('Chunk load failed multiple times in a short period. Stopped reloading to avoid loop.');
            }
        } else {
            console.error('An unexpected error occurred:', error);
        }
    }
}
