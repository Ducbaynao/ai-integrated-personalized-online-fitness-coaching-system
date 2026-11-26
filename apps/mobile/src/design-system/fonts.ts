import {
  BeVietnamPro_400Regular,
  BeVietnamPro_500Medium,
  BeVietnamPro_600SemiBold,
  BeVietnamPro_700Bold,
} from '@expo-google-fonts/be-vietnam-pro';
import { fontFamily } from './tokens/typography';

/**
 * Font files registered with expo-font at app start. Keys are the typography
 * token family names, so every token resolves to a loaded file of its own weight.
 * Source: Google Fonts "Be Vietnam Pro" via @expo-google-fonts (SIL OFL 1.1).
 */
export const appFontAssets = {
  [fontFamily.regular]: BeVietnamPro_400Regular,
  [fontFamily.medium]: BeVietnamPro_500Medium,
  [fontFamily.semiBold]: BeVietnamPro_600SemiBold,
  [fontFamily.bold]: BeVietnamPro_700Bold,
};
