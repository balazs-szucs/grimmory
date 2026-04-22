import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {firstValueFrom} from 'rxjs';

/**
 * A handle to an open dialog that provides both the raw reference and a convenient
 * promise-based result listener. This prevents race conditions where callers try
 * to subscribe to onClose after the dialog might have already closed or during
 * lazy chunk loading.
 */
export interface DialogOpenHandle<T = unknown> {
  /** The underlying PrimeNG dialog reference. */
  readonly ref: DynamicDialogRef;

  /**
   * A promise that resolves when the dialog is closed.
   * Resolves to the data passed to `ref.close(data)`, or `undefined` if closed without data.
   */
  readonly result: Promise<T | undefined>;
}

/**
 * Creates a DialogOpenHandle from a DynamicDialogRef.
 */
export function createDialogOpenHandle<T>(ref: DynamicDialogRef): DialogOpenHandle<T> {
  return {
    ref,
    result: firstValueFrom(ref.onClose)
  };
}
