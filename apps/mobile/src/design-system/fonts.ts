import {
  BeVietnamPro_400Regular,
  BeVietnamPro_400Regular_Italic,
  BeVietnamPro_500Medium,
  BeVietnamPro_500Medium_Italic,
  BeVietnamPro_600SemiBold,
  BeVietnamPro_700Bold,
} from '@expo-google-fonts/be-vietnam-pro';
import { fontFamily } from './tokens/typography';

/**
 * Font files registered with expo-font at app start. Keys are the typography
 * token family names, so every token resolves to a loaded file of its own weight.
 * Italics are real italic faces (not synthetic oblique) and are registered only for the two weights the
 * app uses in italic (regular body/help text, medium captions); further faces are added when a caller needs
 * them. Source: Google Fonts "Be Vietnam Pro" via @expo-google-fonts (SIL OFL 1.1).
 */
export const appFontAssets = {
  [fontFamily.regular]: BeVietnamPro_400Regular,
  [fontFamily.medium]: BeVietnamPro_500Medium,
  [fontFamily.semiBold]: BeVietnamPro_600SemiBold,
  [fontFamily.bold]: BeVietnamPro_700Bold,
  [fontFamily.regularItalic]: BeVietnamPro_400Regular_Italic,
  [fontFamily.mediumItalic]: BeVietnamPro_500Medium_Italic,
};
