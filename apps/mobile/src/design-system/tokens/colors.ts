export const colors = {
  brand: {
    50: '#F4F0FF',
    100: '#E9E0FF',
    300: '#B9A1FF',
    500: '#6D3DF5',
    600: '#5B2EEA',
    700: '#4720C7',
  },
  neutral: {
    0: '#FFFFFF',
    50: '#F8F9FC',
    100: '#F0F2F7',
    200: '#E1E5EC',
    300: '#CBD1DC',
    500: '#7B8496',
    600: '#8E95A5',
    700: '#3B4252',
    800: '#2D323F',
    850: '#1C1F26',
    900: '#151822',
    950: '#12141A',
  },
  success: { 100: '#DDF7EA', 600: '#159A61' },
  warning: { 100: '#FFF3D6', 600: '#D88800' },
  danger: { 100: '#FFE2E3', 600: '#D9434E', 700: '#B82E38' },
} as const;

export interface SemanticColorScheme {
  readonly canvas: string;
  readonly surface: string;
  readonly surfaceSubtle: string;
  readonly primary: string;
  readonly primaryPressed: string;
  readonly brandSoft: string;
  readonly aiSurface: string;
  readonly textPrimary: string;
  readonly textSecondary: string;
  readonly textOnPrimary: string;
  readonly border: string;
  readonly borderStrong: string;
  readonly successSurface: string;
  readonly successText: string;
  readonly warningSurface: string;
  readonly warningText: string;
  readonly dangerSurface: string;
  readonly dangerText: string;
  readonly dangerPressed: string;
}

export const lightSemanticColors: SemanticColorScheme = {
  canvas: colors.neutral[50],
  surface: colors.neutral[0],
  surfaceSubtle: colors.neutral[100],
  primary: colors.brand[600],
  primaryPressed: colors.brand[700],
  brandSoft: colors.brand[50],
  aiSurface: colors.brand[100],
  textPrimary: colors.neutral[900],
  textSecondary: colors.neutral[500],
  textOnPrimary: colors.neutral[0],
  border: colors.neutral[200],
  borderStrong: colors.neutral[300],
  successSurface: colors.success[100],
  successText: colors.success[600],
  warningSurface: colors.warning[100],
  warningText: colors.warning[600],
  dangerSurface: colors.danger[100],
  dangerText: colors.danger[600],
  dangerPressed: colors.danger[700],
};

export const darkSemanticColors: SemanticColorScheme = {
  canvas: colors.neutral[950],
  surface: colors.neutral[850],
  surfaceSubtle: colors.neutral[800],
  primary: colors.brand[500],
  primaryPressed: colors.brand[600],
  brandSoft: colors.brand[50],
  aiSurface: colors.brand[100],
  textPrimary: colors.neutral[0],
  textSecondary: colors.neutral[600],
  textOnPrimary: colors.neutral[0],
  border: colors.neutral[800],
  borderStrong: colors.neutral[700],
  successSurface: colors.success[100],
  successText: colors.success[600],
  warningSurface: colors.warning[100],
  warningText: colors.warning[600],
  dangerSurface: colors.danger[100],
  dangerText: colors.danger[600],
  dangerPressed: colors.danger[700],
};

export const themeSemanticColors = {
  light: lightSemanticColors,
  dark: darkSemanticColors,
} as const;

export const semanticColors = lightSemanticColors;

export function getSemanticColors(isDark: boolean): SemanticColorScheme {
  return isDark ? darkSemanticColors : lightSemanticColors;
}

