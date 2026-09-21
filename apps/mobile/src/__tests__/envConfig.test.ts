import { resolveApiBaseUrl } from '@/config/env';

describe('resolveApiBaseUrl configuration tests', () => {
  it('Web + valid env URL -> uses env URL', () => {
    const url = resolveApiBaseUrl('https://api.example.com/api/v1', 'web');
    expect(url).toBe('https://api.example.com/api/v1');
  });

  it('Web without env -> uses 127.0.0.1 fallback', () => {
    const urlUndefined = resolveApiBaseUrl(undefined, 'web');
    expect(urlUndefined).toBe('http://127.0.0.1:8080/api/v1');

    const urlEmpty = resolveApiBaseUrl('', 'web');
    expect(urlEmpty).toBe('http://127.0.0.1:8080/api/v1');
  });

  it('Android + valid env URL -> uses env URL', () => {
    const url = resolveApiBaseUrl('http://192.168.1.100:8080/api/v1', 'android');
    expect(url).toBe('http://192.168.1.100:8080/api/v1');
  });

  it('Android without env -> uses 10.0.2.2 fallback', () => {
    const urlUndefined = resolveApiBaseUrl(undefined, 'android');
    expect(urlUndefined).toBe('http://10.0.2.2:8080/api/v1');

    const urlEmpty = resolveApiBaseUrl('', 'android');
    expect(urlEmpty).toBe('http://10.0.2.2:8080/api/v1');
  });

  it('Trailing slash is stripped/normalized', () => {
    const url = resolveApiBaseUrl('http://10.0.2.2:8080/api/v1///', 'android');
    expect(url).toBe('http://10.0.2.2:8080/api/v1');

    const webUrl = resolveApiBaseUrl('https://api.example.com/api/v1/', 'web');
    expect(webUrl).toBe('https://api.example.com/api/v1');
  });

  it('Invalid protocol uses fallback', () => {
    // Protocol is neither http:// nor https:// (e.g. ftp:// or arbitrary text)
    const urlAndroid = resolveApiBaseUrl('ftp://example.com/api/v1', 'android');
    expect(urlAndroid).toBe('http://10.0.2.2:8080/api/v1');

    const urlWeb = resolveApiBaseUrl('invalid-schema://api', 'web');
    expect(urlWeb).toBe('http://127.0.0.1:8080/api/v1');
  });
});
