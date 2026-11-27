import { Blob as NodeBlob } from 'node:buffer';
import { saveCoachingQrImage } from '@/features/coaching/useCoachingQrImageExport.web';

jest.mock('expo-media-library', () => { throw new Error('Native media must not load on web'); });
jest.mock('expo-file-system', () => { throw new Error('Native files must not load on web'); });

describe('Coaching QR web image export', () => {
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document');
  const originalBlob = globalThis.Blob;
  const originalCreateUrl = URL.createObjectURL;
  const originalRevokeUrl = URL.revokeObjectURL;
  const click = jest.fn();
  const remove = jest.fn();
  const appendChild = jest.fn();
  const createUrl = jest.fn((_blob: Blob) => 'blob:qa-qr');
  const revokeUrl = jest.fn();
  let anchor: { href: string; download: string; click: typeof click; remove: typeof remove };

  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();
    anchor = { href: '', download: '', click, remove };
    globalThis.Blob = NodeBlob as unknown as typeof Blob;
    URL.createObjectURL = createUrl;
    URL.revokeObjectURL = revokeUrl;
    Object.defineProperty(globalThis, 'document', {
      configurable: true,
      value: { createElement: jest.fn(() => anchor), body: { appendChild } },
    });
  });

  afterEach(() => {
    jest.runOnlyPendingTimers();
    jest.useRealTimers();
    globalThis.Blob = originalBlob;
    URL.createObjectURL = originalCreateUrl;
    URL.revokeObjectURL = originalRevokeUrl;
    if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument);
    else Reflect.deleteProperty(globalThis, 'document');
  });

  it('downloads the supplied PNG and releases the temporary URL', async () => {
    const save = saveCoachingQrImage;
    const bytes = new Uint8Array([137, 80, 78, 71]);
    await expect(save(bytes, 'coaching-qr-student.png', () => true)).resolves.toBe('saved');
    const blob = createUrl.mock.calls[0][0] as Blob;
    expect(blob.type).toBe('image/png');
    expect(new Uint8Array(await blob.arrayBuffer())).toEqual(bytes);
    expect(anchor.download).toBe('coaching-qr-student.png');
    expect(anchor.href).toBe('blob:qa-qr');
    expect(appendChild).toHaveBeenCalledWith(anchor);
    expect(click).toHaveBeenCalledTimes(1);
    expect(remove).toHaveBeenCalledTimes(1);
    expect(revokeUrl).not.toHaveBeenCalled();
    jest.runOnlyPendingTimers();
    expect(revokeUrl).toHaveBeenCalledWith('blob:qa-qr');
  });

  it('does not export an image after its actor operation is invalidated', async () => {
    await expect(saveCoachingQrImage(new Uint8Array([1]), 'qr.png', () => false))
      .resolves.toBe('cancelled');
    expect(createUrl).not.toHaveBeenCalled();
    expect(click).not.toHaveBeenCalled();
  });

  it('is safe when imported and called without a browser document', async () => {
    Reflect.deleteProperty(globalThis, 'document');
    await expect(saveCoachingQrImage(new Uint8Array([1]), 'qr.png', () => true))
      .resolves.toBe('cancelled');
    expect(createUrl).not.toHaveBeenCalled();
  });

  it('propagates a browser download failure and cleans up its resources', async () => {
    click.mockImplementationOnce(() => { throw new Error('Download rejected'); });
    await expect(saveCoachingQrImage(new Uint8Array([1]), 'qr.png', () => true))
      .rejects.toThrow('Download rejected');
    expect(remove).toHaveBeenCalledTimes(1);
    jest.runOnlyPendingTimers();
    expect(revokeUrl).toHaveBeenCalledWith('blob:qa-qr');
  });
});
