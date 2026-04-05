import { describe, expect, it } from 'vitest';
import {
  parseStoredPdfAnnotationsForEmbed,
  serializeAnnotationTransferItemsForIframe,
} from './pdf-annotation-transfer.util';

describe('pdf-annotation-transfer.util', () => {
  it('returns empty array for invalid or non-array JSON', () => {
    expect(parseStoredPdfAnnotationsForEmbed('')).toEqual([]);
    expect(parseStoredPdfAnnotationsForEmbed('not json')).toEqual([]);
    expect(parseStoredPdfAnnotationsForEmbed('{}')).toEqual([]);
  });

  it('converts legacy pdf.js highlight with quadPoints', () => {
    const data = JSON.stringify([
      {
        annotationType: 9,
        pageIndex: 0,
        color: [1, 1, 0],
        opacity: 0.5,
        quadPoints: [10, 10, 20, 10, 20, 20, 10, 20],
      },
    ]);
    const items = parseStoredPdfAnnotationsForEmbed(data);
    expect(items).toHaveLength(1);
    const ann = items[0].annotation;
    expect(ann['type']).toBe(9);
    expect(ann['segmentRects']).toHaveLength(1);
    expect(ann['strokeColor']).toBe('#ffff00');
    expect(ann['opacity']).toBe(0.5);
  });

  it('converts legacy ink with paths', () => {
    const data = JSON.stringify([
      {
        annotationType: 15,
        pageIndex: 1,
        rect: [0, 0, 100, 100],
        paths: [
          [
            [0, 0],
            [10, 10],
          ],
        ],
        color: [0, 0, 0],
        thickness: 3,
      },
    ]);
    const items = parseStoredPdfAnnotationsForEmbed(data);
    expect(items).toHaveLength(1);
    const ann = items[0].annotation;
    expect(ann['type']).toBe(15);
    expect(ann['pageIndex']).toBe(1);
    expect(ann['inkList']).toHaveLength(1);
    expect(ann['strokeWidth']).toBe(3);
  });

  it('passes through Embed-shaped transfer items', () => {
    const raw = [
      {
        annotation: {
          type: 9,
          id: 'existing-id',
          pageIndex: 0,
          rect: { origin: { x: 0, y: 0 }, size: { width: 10, height: 10 } },
          segmentRects: [{ origin: { x: 0, y: 0 }, size: { width: 5, height: 5 } }],
        },
      },
    ];
    const items = parseStoredPdfAnnotationsForEmbed(JSON.stringify(raw));
    expect(items).toHaveLength(1);
    expect(items[0].annotation['id']).toBe('existing-id');
  });

  it('serializes ArrayBuffer nested in ctx for postMessage', () => {
    const buf = new ArrayBuffer(3);
    new Uint8Array(buf).set([1, 2, 3]);
    const json = serializeAnnotationTransferItemsForIframe([
      {
        annotation: { type: 3, id: 't', pageIndex: 0 },
        ctx: { preview: buf } as Record<string, unknown>,
      },
    ]);
    expect(json).toContain('__epArrayBuffer');
    const parsed = JSON.parse(json) as Array<{ ctx: { preview: { b64: string } } }>;
    expect(parsed[0].ctx.preview.b64).toBeDefined();
  });
});
