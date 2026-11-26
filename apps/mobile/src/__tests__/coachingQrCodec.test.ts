import jsQR from 'jsqr';
import { PNG } from 'pngjs';
import { encodeQrMatrixToPngBytes, parseCoachingQrUri } from '@/features/coaching/coachingQrCodec';

describe('coachingQrCodec', () => {
  const validCode = 'k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a';
  const validUri = `ai-fitness-coaching://coaching/qr?v=1&c=${validCode}`;

  it('parses valid coaching QR URI and rejects malformed or unsupported payloads', () => {
    expect(parseCoachingQrUri(validUri)).toEqual({
      ok: true,
      payloadVersion: 1,
      publicCode: validCode,
    });
    expect(parseCoachingQrUri('')).toEqual({ ok: false, reason: 'EMPTY' });
    expect(parseCoachingQrUri('https://example.com/coaching/qr?v=1&c=' + validCode)).toEqual({
      ok: false,
      reason: 'INVALID_URI',
    });
    expect(parseCoachingQrUri(`ai-fitness-coaching://coaching/other?v=1&c=${validCode}`)).toEqual({
      ok: false,
      reason: 'INVALID_URI',
    });
    expect(parseCoachingQrUri(`ai-fitness-coaching://coaching/qr?v=2&c=${validCode}`)).toEqual({
      ok: false,
      reason: 'UNSUPPORTED_VERSION',
    });
    expect(parseCoachingQrUri('ai-fitness-coaching://coaching/qr?v=1&c=short')).toEqual({
      ok: false,
      reason: 'INVALID_CODE',
    });
  });

  it('encodes a real PNG image that round-trips back to the exact backend QR URI', () => {
    const pngBytes = encodeQrMatrixToPngBytes(validUri, 8, 4);
    const decodedPng = PNG.sync.read(Buffer.from(pngBytes));
    const rgba = new Uint8ClampedArray(decodedPng.data.buffer, decodedPng.data.byteOffset, decodedPng.data.byteLength);
    const qrResult = jsQR(rgba, decodedPng.width, decodedPng.height);

    expect(qrResult).not.toBeNull();
    expect(qrResult?.data).toBe(validUri);
    expect(parseCoachingQrUri(qrResult?.data ?? '')).toEqual({
      ok: true,
      payloadVersion: 1,
      publicCode: validCode,
    });
  });
});
