import { Platform } from 'react-native';

/**
 * Mobile environment configuration.
 *
 * EXPO_PUBLIC_API_BASE_URL can be provided via .env / environment variables.
 * For local development:
 * - Android Emulator: http://10.0.2.2:8080/api/v1
 * - iOS Simulator / Web: http://127.0.0.1:8080/api/v1
 * - Physical device: http://<LAN_IP>:8080/api/v1
 *
 * In accordance with security rules, NO sensitive secrets (API private keys,
 * database credentials, JWT signing keys) are bundled in mobile code.
 */
export function resolveApiBaseUrl(
  envUrl: string | undefined = process.env.EXPO_PUBLIC_API_BASE_URL,
  platformOS: typeof Platform.OS = Platform.OS
): string {
  const trimmed = envUrl?.trim();
  if (trimmed && (trimmed.startsWith('http://') || trimmed.startsWith('https://'))) {
    const sanitized = trimmed.replace(/\/+$/, '');
    if (platformOS === 'web') {
      try {
        const parsed = new URL(sanitized);
        if (parsed.hostname === '10.0.2.2') {
          parsed.hostname = 'localhost';
          return parsed.toString().replace(/\/+$/, '');
        }
      } catch {
        if (sanitized.includes('10.0.2.2')) {
          return sanitized.replace('10.0.2.2', 'localhost');
        }
      }
    }
    return sanitized;
  }

  // Fallback when environment variable is absent or invalid
  if (platformOS === 'android') {
    return 'http://10.0.2.2:8080/api/v1';
  }
  return 'http://127.0.0.1:8080/api/v1';
}

export const API_BASE_URL = resolveApiBaseUrl();
