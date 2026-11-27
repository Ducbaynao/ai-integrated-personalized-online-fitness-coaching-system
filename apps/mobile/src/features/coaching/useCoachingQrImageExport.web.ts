// Keep native photo-library and file-system modules outside the web/SSR import graph.
export async function saveCoachingQrImage(
  bytes: Uint8Array,
  filename: string,
  isCurrent: () => boolean
): Promise<'saved' | 'permission-denied' | 'cancelled'> {
  if (!isCurrent() || typeof document === 'undefined') return 'cancelled';

  const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: 'image/png' }));
  const anchor = document.createElement('a');
  try {
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    return 'saved';
  } finally {
    anchor.remove();
    // Allow the browser to acquire the Blob before releasing its temporary URL.
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}

export function useCoachingQrImageExport() {
  return { saveImage: saveCoachingQrImage, canOpenSettings: false };
}
