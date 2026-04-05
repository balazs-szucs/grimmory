import {describe, it} from 'vitest';

// NOTE(frontend-seam): Real coverage here needs seams around the EmbedPDF iframe bridge,
// reader-session lifecycle, and route-driven book loading so the component can be tested
// without booting PDFium in a real iframe.
describe.skip('PdfReaderComponent', () => {
  it('needs a viewer-runtime seam to verify page, zoom, spread, and mode switching deterministically', () => {
    // TODO(seam): Cover load, navigation, and persistence after wrapping the EmbedPDF postMessage bridge.
  });

  it('needs a route-and-session seam to verify startup and teardown side effects without real browser navigation', () => {
    // TODO(seam): Cover session start/end and progress persistence once route/book-detail dependencies are injectable test adapters.
  });
});
