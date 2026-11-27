import { invalidateAuthSession, invalidateCapabilitySession } from '@/services/apiClient';
import { measurementApi } from '@/services/measurementApi';
import { tokenStorage } from '@/services/storage';

const originalFetch = globalThis.fetch;
const params = { fileUri: 'file:///fixture.jpg', fileName: 'fixture.jpg', mimeType: 'image/jpeg' };

afterEach(() => { globalThis.fetch = originalFetch; });

it.each([invalidateAuthSession, invalidateCapabilitySession])('does not POST the old image after the session changes during local file read', async invalidate => {
  await tokenStorage.saveTokens('owner-token', 'owner-refresh');
  let finish!: (value: Response) => void;
  const fetchMock = jest.fn().mockReturnValueOnce(new Promise<Response>(resolve => { finish = resolve; }));
  globalThis.fetch = fetchMock;
  const upload = measurementApi.uploadProgressPhoto(params);
  invalidate();
  await tokenStorage.saveTokens('new-token', 'new-refresh');
  finish({ ok: true, blob: async () => new Blob(['fixture']) } as Response);
  await expect(upload).rejects.toMatchObject({ status: 401 });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('does not POST after leaving the screen while reading the image', async () => {
  let active = true;
  globalThis.fetch = jest.fn().mockResolvedValue({ ok: true, blob: async () => { active = false; return new Blob(['fixture']); } });
  await expect(measurementApi.uploadProgressPhoto(params, () => active)).rejects.toMatchObject({ name: 'AbortError' });
  expect(globalThis.fetch).toHaveBeenCalledTimes(1);
});

it('does not automatically replay an upload on 401', async () => {
  await tokenStorage.saveTokens('owner-token', 'owner-refresh');
  globalThis.fetch = jest.fn()
    .mockResolvedValueOnce({ ok: true, blob: async () => new Blob(['fixture']) })
    .mockResolvedValueOnce({ ok: false, status: 401, headers: { get: () => 'application/json' }, json: async () => ({ errorCode: 'UNAUTHORIZED', message: 'private detail' }) });
  await expect(measurementApi.uploadProgressPhoto(params)).rejects.toMatchObject({ status: 401 });
  expect(globalThis.fetch).toHaveBeenCalledTimes(2);
});
