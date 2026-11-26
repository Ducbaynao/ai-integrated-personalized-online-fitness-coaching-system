/**
 * P7-012 — Vietnamese text must render with the app font, not a per-glyph fallback.
 *
 * jest-expo replaces `.ttf` requires with a number, so the font package is mocked
 * from its own generated index: each export resolves to the absolute TTF path the
 * real bundle would load. The TTF bytes are then inspected directly (cmap, OS/2,
 * GPOS) for every family the typography tokens use.
 */
import React from 'react';
import renderer from 'react-test-renderer';
import { appFontAssets } from '@/design-system/fonts';
import { fontFamily, typography } from '@/design-system/tokens/typography';
import RootLayout from '@/app/_layout';

// The Mobile project has no @types/node; type only the Node APIs this test reads.
interface ByteBuffer {
  readUInt16BE(offset: number): number;
  readInt16BE(offset: number): number;
  readUInt32BE(offset: number): number;
  toString(encoding: 'latin1', start: number, end: number): string;
}
interface NodeFs {
  readFileSync(path: string): ByteBuffer;
  existsSync(path: string): boolean;
}
const fs = jest.requireActual<NodeFs>('fs');
type MockNodeRequire = { resolve(id: string): string };

jest.mock('@expo-google-fonts/be-vietnam-pro', () => {
  const nodeFs = jest.requireActual('fs');
  const nodePath = jest.requireActual('path');
  const nodeRequire = require as unknown as MockNodeRequire;
  const pkgDir = nodePath.dirname(nodeRequire.resolve('@expo-google-fonts/be-vietnam-pro/package.json'));
  const source: string = nodeFs.readFileSync(nodePath.join(pkgDir, 'index.js'), 'utf8');
  const exportsByName: Record<string, string> = {};
  for (const match of source.matchAll(/export const (\w+) = require\('(\.\/[^']+\.ttf)'\)/g)) {
    exportsByName[match[1]] = nodePath.join(pkgDir, match[2]);
  }
  return exportsByName;
});

const mockUseFonts = jest.fn();
jest.mock('expo-font', () => ({ useFonts: (map: unknown) => mockUseFonts(map) }));

jest.mock('expo-router', () => {
  const { Text: MockText } = jest.requireActual('react-native');
  return { Stack: () => <MockText>app-stack</MockText> };
});

jest.mock('@/features/auth/AuthContext', () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({
    status: 'UNAUTHENTICATED',
    user: null,
    isLoading: false,
    restoreError: null,
    retrySessionRestore: jest.fn(),
  }),
}));

jest.mock('@/features/chat/PushReceiptListener', () => ({ PushReceiptListener: () => null }));

// ---------------------------------------------------------------------------
// Minimal TrueType reader (only what the assertions need).
// ---------------------------------------------------------------------------
interface ParsedFont {
  codePoints: Set<number>;
  weightClass: number;
  isItalic: boolean;
  gposFeatures: Set<string>;
}

function readTables(buf: ByteBuffer): Map<string, number> {
  const tables = new Map<string, number>();
  const numTables = buf.readUInt16BE(4);
  for (let i = 0; i < numTables; i += 1) {
    const rec = 12 + i * 16;
    tables.set(buf.toString('latin1', rec, rec + 4), buf.readUInt32BE(rec + 8));
  }
  return tables;
}

function readCmap(buf: ByteBuffer, cmap: number): Set<number> {
  const points = new Set<number>();
  const count = buf.readUInt16BE(cmap + 2);
  for (let i = 0; i < count; i += 1) {
    const rec = cmap + 4 + i * 8;
    const platform = buf.readUInt16BE(rec);
    const sub = cmap + buf.readUInt32BE(rec + 4);
    const format = buf.readUInt16BE(sub);
    if (platform === 3 && format === 12) {
      const groups = buf.readUInt32BE(sub + 12);
      for (let g = 0; g < groups; g += 1) {
        const start = buf.readUInt32BE(sub + 16 + g * 12);
        const end = buf.readUInt32BE(sub + 20 + g * 12);
        for (let cp = start; cp <= end; cp += 1) points.add(cp);
      }
    } else if (platform === 3 && format === 4) {
      const segX2 = buf.readUInt16BE(sub + 6);
      const ends = sub + 14;
      const starts = ends + segX2 + 2;
      const deltas = starts + segX2;
      const rangeOffsets = deltas + segX2;
      for (let s = 0; s < segX2 / 2; s += 1) {
        const end = buf.readUInt16BE(ends + s * 2);
        const start = buf.readUInt16BE(starts + s * 2);
        const delta = buf.readInt16BE(deltas + s * 2);
        const rangeOffsetPos = rangeOffsets + s * 2;
        const rangeOffset = buf.readUInt16BE(rangeOffsetPos);
        for (let cp = start; cp <= end && cp !== 0xffff; cp += 1) {
          const glyph =
            rangeOffset === 0
              ? (cp + delta) & 0xffff
              : buf.readUInt16BE(rangeOffsetPos + rangeOffset + (cp - start) * 2);
          if (glyph !== 0) points.add(cp);
        }
      }
    }
  }
  return points;
}

function readGposFeatures(buf: ByteBuffer, gpos: number | undefined): Set<string> {
  const tags = new Set<string>();
  if (gpos === undefined) return tags;
  const featureList = gpos + buf.readUInt16BE(gpos + 6);
  const count = buf.readUInt16BE(featureList);
  for (let i = 0; i < count; i += 1) {
    const rec = featureList + 2 + i * 6;
    tags.add(buf.toString('latin1', rec, rec + 4));
  }
  return tags;
}

function parseFont(file: string): ParsedFont {
  const buf = fs.readFileSync(file);
  const tables = readTables(buf);
  const os2 = tables.get('OS/2')!;
  return {
    codePoints: readCmap(buf, tables.get('cmap')!),
    weightClass: buf.readUInt16BE(os2 + 4),
    isItalic: (buf.readUInt16BE(os2 + 62) & 1) === 1,
    gposFeatures: readGposFeatures(buf, tables.get('GPOS')),
  };
}

// ---------------------------------------------------------------------------
// Vietnamese sample set
// ---------------------------------------------------------------------------
const TONES = ['', '̀', '́', '̉', '̃', '̣'];
const VOWELS = 'aăâeêioôơuưy';
const ALPHABET = Array.from(VOWELS)
  .flatMap((v) => [v, v.toUpperCase()])
  .flatMap((v) => TONES.map((tone) => (v + tone).normalize('NFC')))
  .concat(['đ', 'Đ']);

const SAMPLE_TEXT = [
  'ă â ê ô ơ ư đ',
  'Ă Â Ê Ô Ơ Ư Đ',
  'Người dùng ước lượng dinh dưỡng bữa ăn',
  'Mục tiêu thể hình, lịch tập và tiến độ',
  'TRƯỜNG HỢP ĐỔI HUẤN LUYỆN VIÊN',
  'Học viên xác nhận đề xuất của Huấn luyện viên',
  'Ưu tiên sức khỏe — kỹ thuật đúng, nghỉ ngơi đủ',
];

const EXPECTED_WEIGHT: Record<keyof typeof fontFamily, number> = {
  regular: 400,
  medium: 500,
  semiBold: 600,
  bold: 700,
};

const assets = appFontAssets as unknown as Record<string, string>;

function missingCodePoints(text: string, font: ParsedFont): string[] {
  return Array.from(text)
    .filter((ch) => ch.trim() !== '' && !font.codePoints.has(ch.codePointAt(0)!))
    .map((ch) => `U+${ch.codePointAt(0)!.toString(16).toUpperCase().padStart(4, '0')}`);
}

describe('P7-012 app font covers Vietnamese for every token family', () => {
  it('uses each token family in typography and registers it with a real TTF', () => {
    const usedFamilies = new Set(Object.values(typography).map((style) => style.fontFamily));
    expect([...usedFamilies].sort()).toEqual(Object.values(fontFamily).sort());
    expect(Object.keys(assets).sort()).toEqual(Object.values(fontFamily).sort());
    for (const family of Object.values(fontFamily)) {
      expect(typeof assets[family]).toBe('string');
      expect(fs.existsSync(assets[family])).toBe(true);
    }
  });

  describe.each(Object.entries(fontFamily) as [keyof typeof fontFamily, string][])(
    '%s (%s)',
    (tokenKey, family) => {
      const font = parseFont(assets[family]);

      it('maps to an upright file of the matching weight, not a synthesized variant', () => {
        expect(font.weightClass).toBe(EXPECTED_WEIGHT[tokenKey]);
        expect(font.isItalic).toBe(false);
      });

      it('contains all 144 precomposed Vietnamese vowels with tones plus đ/Đ', () => {
        expect(ALPHABET).toHaveLength(146);
        expect(missingCodePoints(ALPHABET.join(''), font)).toEqual([]);
      });

      it.each(SAMPLE_TEXT)('renders NFC sample "%s" without fallback glyphs', (sample) => {
        expect(missingCodePoints(sample.normalize('NFC'), font)).toEqual([]);
      });

      it.each(SAMPLE_TEXT)('supports decomposed NFD sample "%s" with mark positioning', (sample) => {
        expect(missingCodePoints(sample.normalize('NFD'), font)).toEqual([]);
        expect(font.gposFeatures.has('mark')).toBe(true);
        expect(font.gposFeatures.has('mkmk')).toBe(true);
      });
    },
  );
});

describe('P7-012 root layout font loading', () => {
  beforeEach(() => mockUseFonts.mockReset());

  function renderLayout(): renderer.ReactTestRenderer {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<RootLayout />);
    });
    return tree;
  }

  const hasLoadingGate = (tree: renderer.ReactTestRenderer) =>
    tree.root.findAll((node) => node.props.testID === 'session-loading-screen').length > 0;
  const hasAppStack = (tree: renderer.ReactTestRenderer) =>
    JSON.stringify(tree.toJSON()).includes('app-stack');

  it('loads exactly the token families', () => {
    mockUseFonts.mockReturnValue([true, null]);
    const tree = renderLayout();
    expect(Object.keys(mockUseFonts.mock.calls[0][0]).sort()).toEqual(
      Object.values(fontFamily).sort(),
    );
    expect(hasAppStack(tree)).toBe(true);
  });

  it('keeps the loading gate while fonts are pending', () => {
    mockUseFonts.mockReturnValue([false, null]);
    const tree = renderLayout();
    expect(hasLoadingGate(tree)).toBe(true);
    expect(hasAppStack(tree)).toBe(false);
  });

  it('does not hang when font loading fails; the app continues with system text', () => {
    mockUseFonts.mockReturnValue([false, new Error('font load failed')]);
    const tree = renderLayout();
    expect(hasLoadingGate(tree)).toBe(false);
    expect(hasAppStack(tree)).toBe(true);
  });
});
