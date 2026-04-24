let supportCheckPromise: Promise<boolean> | null = null;

function canDecodeMimeType(mimeType: string): Promise<boolean> {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve(true);
    img.onerror = () => resolve(false);
    img.src = `data:${mimeType};base64,UklGRiIAAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=`;
  });
}

export async function supportsModernComicFormats(): Promise<boolean> {
  if (!supportCheckPromise) {
    supportCheckPromise = Promise.all([
      canDecodeMimeType('image/webp'),
      canDecodeMimeType('image/avif')
    ]).then(([webp, avif]) => webp || avif);
  }
  return supportCheckPromise;
}

