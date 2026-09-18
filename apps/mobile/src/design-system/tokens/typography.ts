export const fontFamily = {
  regular: 'DMSans_400Regular',
  medium: 'DMSans_500Medium',
  semiBold: 'DMSans_600SemiBold',
  bold: 'DMSans_700Bold',
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

