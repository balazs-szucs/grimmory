/**
 * Converts stored PDF overlay JSON (legacy pdf.js editor or EmbedPDF export) into
 * plain objects suitable for EmbedPDF {@code importAnnotations}.
 */

/** Matches {@link @embedpdf/models#PdfAnnotationSubtype} numeric values used in JSON. */
const EMBED_SUBTYPE = {
  FREETEXT: 3,
  HIGHLIGHT: 9,
  INK: 15,
} as const;

/** pdf.js {@code AnnotationEditorType} */
const PDFJS_EDITOR = {
  FREETEXT: 3,
  HIGHLIGHT: 9,
  STAMP: 13,
  INK: 15,
} as const;

const HELVETICA = 4;
const TEXT_ALIGN_LEFT = 0;
const VERT_ALIGN_TOP = 0;

export interface AnnotationTransferItemJson {
  annotation: Record<string, unknown>;
  ctx?: Record<string, unknown>;
}

function newId(prefix: string, index: number): string {
  return `${prefix}-${index}-${Math.random().toString(36).slice(2, 10)}`;
}

function rectFromPdfJsBox(box: unknown): { origin: { x: number; y: number }; size: { width: number; height: number } } | null {
  if (!Array.isArray(box) || box.length < 4) return null;
  const x1 = Number(box[0]);
  const y1 = Number(box[1]);
  const x2 = Number(box[2]);
  const y2 = Number(box[3]);
  if ([x1, y1, x2, y2].some((n) => Number.isNaN(n))) return null;
  const left = Math.min(x1, x2);
  const right = Math.max(x1, x2);
  const top = Math.min(y1, y2);
  const bottom = Math.max(y1, y2);
  return {
    origin: { x: left, y: top },
    size: { width: Math.max(0.1, right - left), height: Math.max(0.1, bottom - top) },
  };
}

function colorToHex(c: unknown, fallback: string): string {
  if (typeof c === 'string' && /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(c)) {
    return c;
  }
  if (!Array.isArray(c) || c.length < 3) return fallback;
  const r = typeof c[0] === 'number' && c[0] <= 1 ? Math.round(c[0] * 255) : Number(c[0]);
  const g = typeof c[1] === 'number' && c[1] <= 1 ? Math.round(c[1] * 255) : Number(c[1]);
  const b = typeof c[2] === 'number' && c[2] <= 1 ? Math.round(c[2] * 255) : Number(c[2]);
  const clamp = (n: number) => Math.max(0, Math.min(255, Math.round(Number.isNaN(n) ? 0 : n)));
  return `#${[clamp(r), clamp(g), clamp(b)]
    .map((n) => n.toString(16).padStart(2, '0'))
    .join('')}`;
}

function quadPointsToSegmentRects(quadPoints: unknown): Array<{ origin: { x: number; y: number }; size: { width: number; height: number } }> {
  if (!Array.isArray(quadPoints) || quadPoints.length < 8 || quadPoints.length % 8 !== 0) {
    return [];
  }
  const out: Array<{ origin: { x: number; y: number }; size: { width: number; height: number } }> = [];
  for (let i = 0; i < quadPoints.length; i += 8) {
    const xs = [
      Number(quadPoints[i]),
      Number(quadPoints[i + 2]),
      Number(quadPoints[i + 4]),
      Number(quadPoints[i + 6]),
    ];
    const ys = [
      Number(quadPoints[i + 1]),
      Number(quadPoints[i + 3]),
      Number(quadPoints[i + 5]),
      Number(quadPoints[i + 7]),
    ];
    if (xs.some((n) => Number.isNaN(n)) || ys.some((n) => Number.isNaN(n))) continue;
    const left = Math.min(...xs);
    const right = Math.max(...xs);
    const top = Math.min(...ys);
    const bottom = Math.max(...ys);
    out.push({
      origin: { x: left, y: top },
      size: { width: Math.max(0.1, right - left), height: Math.max(0.1, bottom - top) },
    });
  }
  return out;
}

function pdfJsInkPathsToInkList(paths: unknown): { points: { x: number; y: number }[] }[] {
  if (!Array.isArray(paths)) return [];
  const inkList: { points: { x: number; y: number }[] }[] = [];
  for (const path of paths) {
    const points: { x: number; y: number }[] = [];
    if (Array.isArray(path)) {
      for (const pt of path) {
        if (Array.isArray(pt) && pt.length >= 2) {
          points.push({ x: Number(pt[0]), y: Number(pt[1]) });
        } else if (pt && typeof pt === 'object' && 'x' in pt && 'y' in pt) {
          points.push({ x: Number((pt as { x: number }).x), y: Number((pt as { y: number }).y) });
        }
      }
    }
    if (points.length > 1) {
      inkList.push({ points });
    }
  }
  return inkList;
}

function legacyPdfJsItemToTransfer(raw: Record<string, unknown>, index: number): AnnotationTransferItemJson | null {
  const t = raw['annotationType'] ?? raw['annotationEditorType'];
  const pageIndex = typeof raw['pageIndex'] === 'number' ? raw['pageIndex'] : Number(raw['pageIndex']) || 0;
  const rect = rectFromPdfJsBox(raw['rect']);

  if (t === PDFJS_EDITOR.HIGHLIGHT) {
    const quads = quadPointsToSegmentRects(raw['quadPoints']);
    const segmentRects =
      quads.length > 0
        ? quads
        : rect
          ? [{ origin: { ...rect.origin }, size: { ...rect.size } }]
          : [];
    if (segmentRects.length === 0) return null;
    const r = rect ?? segmentRects[0];
    return {
      annotation: {
        type: EMBED_SUBTYPE.HIGHLIGHT,
        id: newId('hl', index),
        pageIndex,
        rect: r,
        segmentRects,
        strokeColor: colorToHex(raw['color'], '#FFFF00'),
        opacity: typeof raw['opacity'] === 'number' ? Math.max(0, Math.min(1, raw['opacity'])) : 0.4,
      },
    };
  }

  if (t === PDFJS_EDITOR.FREETEXT) {
    if (!rect) return null;
    const value =
      typeof raw['value'] === 'string' ? raw['value'] : typeof raw['contents'] === 'string' ? raw['contents'] : '';
    const fontSize = typeof raw['fontSize'] === 'number' ? raw['fontSize'] : 12;
    return {
      annotation: {
        type: EMBED_SUBTYPE.FREETEXT,
        id: newId('ft', index),
        pageIndex,
        rect,
        contents: value,
        fontFamily: HELVETICA,
        fontSize,
        fontColor: colorToHex(raw['color'], '#000000'),
        textAlign: TEXT_ALIGN_LEFT,
        verticalAlign: VERT_ALIGN_TOP,
        opacity: typeof raw['opacity'] === 'number' ? Math.max(0, Math.min(1, raw['opacity'])) : 1,
      },
    };
  }

  if (t === PDFJS_EDITOR.INK) {
    if (!rect) return null;
    const inkList = pdfJsInkPathsToInkList(raw['paths']);
    if (inkList.length === 0) return null;
    const thickness = typeof raw['thickness'] === 'number' ? raw['thickness'] : 2;
    return {
      annotation: {
        type: EMBED_SUBTYPE.INK,
        id: newId('ink', index),
        pageIndex,
        rect,
        inkList,
        strokeColor: colorToHex(raw['color'], '#000000'),
        strokeWidth: thickness,
        opacity: typeof raw['opacity'] === 'number' ? Math.max(0, Math.min(1, raw['opacity'])) : 1,
      },
    };
  }

  if (t === PDFJS_EDITOR.STAMP) {
    return null;
  }

  return null;
}

function isEmbedTransferItem(row: unknown): row is AnnotationTransferItemJson {
  return (
    !!row &&
    typeof row === 'object' &&
    'annotation' in row &&
    typeof (row as { annotation: unknown }).annotation === 'object' &&
    (row as { annotation: { id?: unknown; type?: unknown; pageIndex?: unknown } }).annotation !== null
  );
}

/**
 * Parse API-stored JSON into transfer items for the iframe.
 */
export function parseStoredPdfAnnotationsForEmbed(data: string): AnnotationTransferItemJson[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data) as unknown;
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) {
    return [];
  }
  if (parsed.length === 0) {
    return [];
  }
  if (isEmbedTransferItem(parsed[0])) {
    return parsed.filter(isEmbedTransferItem);
  }
  const out: AnnotationTransferItemJson[] = [];
  let i = 0;
  for (const row of parsed) {
    if (!row || typeof row !== 'object') continue;
    const converted = legacyPdfJsItemToTransfer(row as Record<string, unknown>, i);
    if (converted) {
      out.push(converted);
    }
    i++;
  }
  return out;
}

/**
 * Serialize items for postMessage; preserves ArrayBuffer in ctx via base64 wrapper.
 */
export function serializeAnnotationTransferItemsForIframe(items: AnnotationTransferItemJson[]): string {
  return JSON.stringify(items, (_k, v) => {
    if (v instanceof ArrayBuffer) {
      const u8 = new Uint8Array(v);
      let binary = '';
      for (let j = 0; j < u8.length; j++) {
        binary += String.fromCharCode(u8[j]);
      }
      return { __epArrayBuffer: true, b64: btoa(binary) };
    }
    if (v instanceof Date) {
      return { __epDate: true, iso: v.toISOString() };
    }
    return v;
  });
}
