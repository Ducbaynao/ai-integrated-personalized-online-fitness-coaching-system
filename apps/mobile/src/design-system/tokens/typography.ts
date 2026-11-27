/**
 * App font families. Each value is the name a font file is registered under in
 * `appFontAssets` (see `src/design-system/fonts.ts`), one file per weight/style.
 *
 * Request a weight or italic by choosing the matching family here, NOT with `fontWeight` or
 * `fontStyle: 'italic'` on top of a regular family: the platform would then synthesise bold/oblique from
 * the regular file instead of using the real Be Vietnam Pro face. Only the faces the app actually uses
 * are registered (regular, medium, semi-bold, bold, plus regular/medium italic).
 *
 * Be Vietnam Pro replaces DM Sans (P7-012): DM Sans ships only latin/latin-ext and
 * has no glyphs for ơ, ư or the Latin Extended Additional tone-mark letters.
 */
export const fontFamily = {
  regular: 'BeVietnamPro_400Regular',
  medium: 'BeVietnamPro_500Medium',
  semiBold: 'BeVietnamPro_600SemiBold',
  bold: 'BeVietnamPro_700Bold',
  regularItalic: 'BeVietnamPro_400Regular_Italic',
  mediumItalic: 'BeVietnamPro_500Medium_Italic',
} as const;

export const typography = {
  display: { fontFamily: fontFamily.bold, fontSize: 32, lineHeight: 38 },
  h1: { fontFamily: fontFamily.bold, fontSize: 28, lineHeight: 34 },
  h2: { fontFamily: fontFamily.bold, fontSize: 22, lineHeight: 28 },
  h3: { fontFamily: fontFamily.semiBold, fontSize: 18, lineHeight: 24 },
  body: { fontFamily: fontFamily.regular, fontSize: 16, lineHeight: 24 },
  bodySmall: { fontFamily: fontFamily.regular, fontSize: 14, lineHeight: 20 },
  label: { fontFamily: fontFamily.semiBold, fontSize: 14, lineHeight: 18 },
  caption: { fontFamily: fontFamily.medium, fontSize: 12, lineHeight: 16 },
} as const;

