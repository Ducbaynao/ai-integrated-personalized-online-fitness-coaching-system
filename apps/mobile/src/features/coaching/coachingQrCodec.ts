import QRCode from 'qrcode';

export const COACHING_QR_SCHEME = 'ai-fitness-coaching';
export const COACHING_QR_HOST = 'coaching';
export const COACHING_QR_PATH = '/qr';
export const COACHING_QR_VERSION = 1;
const PUBLIC_CODE_PATTERN = /^[A-Za-z0-9_-]{32}$/;

export type QrParseResult =
  | { ok: true; payloadVersion: 1; publicCode: string }
  | { ok: false; reason: 'EMPTY' | 'INVALID_URI' | 'UNSUPPORTED_VERSION' | 'INVALID_CODE' };

export function parseCoachingQrUri(raw: string): QrParseResult {
  const trimmed = raw.trim();
  if (!trimmed) {
    return { ok: false, reason: 'EMPTY' };
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return { ok: false, reason: 'INVALID_URI' };
  }

  if (parsed.protocol !== `${COACHING_QR_SCHEME}:`) {
    return { ok: false, reason: 'INVALID_URI' };
  }

  // Handle both custom-scheme `ai-fitness-coaching://coaching/qr` (host='coaching', pathname='/qr')
  // and `ai-fitness-coaching:/coaching/qr` (host='', pathname='/coaching/qr').
  const normalizedRoute = parsed.host
    ? `${parsed.host}${parsed.pathname}`.replace(/\/+$/, '')
    : parsed.pathname.replace(/^\/+/, '').replace(/\/+$/, '');
  if (normalizedRoute !== `${COACHING_QR_HOST}${COACHING_QR_PATH}`) {
    return { ok: false, reason: 'INVALID_URI' };
  }

  const versionParam = parsed.searchParams.get('v');
  if (versionParam !== String(COACHING_QR_VERSION)) {
    return { ok: false, reason: 'UNSUPPORTED_VERSION' };
  }

  const publicCode = parsed.searchParams.get('c') ?? '';
  if (!PUBLIC_CODE_PATTERN.test(publicCode)) {
    return { ok: false, reason: 'INVALID_CODE' };
  }

  return { ok: true, payloadVersion: 1, publicCode };
}

export interface QrMatrix {
  size: number;
  data: Uint8Array;
}

export function buildQrMatrix(qrUri: string): QrMatrix {
  const qr = QRCode.create(qrUri, { errorCorrectionLevel: 'M' });
  return {
    size: qr.modules.size,
    data: qr.modules.data,
  };
}

const CRC32_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) !== 0 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c >>> 0;
  }
  return table;
})();

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    crc = CRC32_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function adler32(bytes: Uint8Array): number {
  let a = 1;
  let b = 0;
  for (let i = 0; i < bytes.length; i++) {
    a = (a + bytes[i]) % 65521;
    b = (b + a) % 65521;
  }
  return ((b << 16) | a) >>> 0;
}

function writeU32BE(target: Uint8Array, offset: number, value: number): void {
  target[offset] = (value >>> 24) & 0xff;
  target[offset + 1] = (value >>> 16) & 0xff;
  target[offset + 2] = (value >>> 8) & 0xff;
  target[offset + 3] = value & 0xff;
}

function makeChunk(type: string, data: Uint8Array): Uint8Array {
  const chunk = new Uint8Array(12 + data.length);
  writeU32BE(chunk, 0, data.length);
  for (let i = 0; i < 4; i++) {
    chunk[4 + i] = type.charCodeAt(i);
  }
  chunk.set(data, 8);
  const crcInput = chunk.subarray(4, 8 + data.length);
  writeU32BE(chunk, 8 + data.length, crc32(crcInput));
  return chunk;
}

function zlibStore(raw: Uint8Array): Uint8Array {
  const maxBlock = 65535;
  const blockCount = Math.max(1, Math.ceil(raw.length / maxBlock));
  const out = new Uint8Array(2 + raw.length + blockCount * 5 + 4);
  let pos = 0;
  out[pos++] = 0x78;
  out[pos++] = 0x01;
  for (let offset = 0; offset < raw.length || offset === 0; offset += maxBlock) {
    const end = Math.min(raw.length, offset + maxBlock);
    const len = end - offset;
    const isFinal = end === raw.length ? 1 : 0;
    out[pos++] = isFinal;
    out[pos++] = len & 0xff;
    out[pos++] = (len >>> 8) & 0xff;
    const nlen = (~len) & 0xffff;
    out[pos++] = nlen & 0xff;
    out[pos++] = (nlen >>> 8) & 0xff;
    out.set(raw.subarray(offset, end), pos);
    pos += len;
    if (end === raw.length) break;
  }
  writeU32BE(out, pos, adler32(raw));
  return out;
}

/**
 * Encodes a QR URI into an 8-bit grayscale PNG with explicit quiet zone and crisp contrast
 * so it can be saved to the device gallery and decoded by any standard QR reader.
 */
export function encodeQrMatrixToPngBytes(qrUri: string, scale = 10, quietZoneModules = 4): Uint8Array {
  const matrix = buildQrMatrix(qrUri);
  const totalModules = matrix.size + quietZoneModules * 2;
  const width = totalModules * scale;
  const height = width;
  const stride = 1 + width;
  const raw = new Uint8Array(stride * height);
  raw.fill(255);

  for (let py = 0; py < height; py++) {
    raw[py * stride] = 0; // PNG filter type 0 (None)
    const modY = Math.floor(py / scale) - quietZoneModules;
    if (modY < 0 || modY >= matrix.size) continue;
    for (let px = 0; px < width; px++) {
      const modX = Math.floor(px / scale) - quietZoneModules;
      if (modX < 0 || modX >= matrix.size) continue;
      if (matrix.data[modY * matrix.size + modX]) {
        raw[py * stride + 1 + px] = 0;
      }
    }
  }

  const ihdr = new Uint8Array(13);
  writeU32BE(ihdr, 0, width);
  writeU32BE(ihdr, 4, height);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 0; // color type 0 = grayscale
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  const idatData = zlibStore(raw);
  const sig = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdrChunk = makeChunk('IHDR', ihdr);
  const idatChunk = makeChunk('IDAT', idatData);
  const iendChunk = makeChunk('IEND', new Uint8Array(0));

  const png = new Uint8Array(sig.length + ihdrChunk.length + idatChunk.length + iendChunk.length);
  let offset = 0;
  png.set(sig, offset); offset += sig.length;
  png.set(ihdrChunk, offset); offset += ihdrChunk.length;
  png.set(idatChunk, offset); offset += idatChunk.length;
  png.set(iendChunk, offset);
  return png;
}

export function uint8ArrayToBase64(bytes: Uint8Array): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let result = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
    result += chars[b0 >> 2];
    result += chars[((b0 & 3) << 4) | (b1 >> 4)];
    result += i + 1 < bytes.length ? chars[((b1 & 15) << 2) | (b2 >> 6)] : '=';
    result += i + 2 < bytes.length ? chars[b2 & 63] : '=';
  }
  return result;
}
