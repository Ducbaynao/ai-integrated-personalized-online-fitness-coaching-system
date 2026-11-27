export const spacing = {
  xxs: 2,
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

export const layout = {
  mobileScreenPadding: spacing.lg,
  sectionGap: spacing.xl,
  cardGap: spacing.md,
  minimumTouchTarget: 44,
  buttonHeight: 48,
  largeButtonHeight: 54,
  featureIconContainer: 64,
  mobileContentMaxWidth: 640,
} as const;

export const iconSize = { small: 20, medium: 24, large: 32 } as const;

