import { trainerApplicationApi } from '@/services/trainerApplicationApi';
import { request } from '@/services/apiClient';

jest.mock('@/services/apiClient', () => ({
  request: jest.fn(),
}));

describe('trainerApplicationApi', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('calls POST /trainer-applications on submitApplication', async () => {
    const mockResponse = {
      id: 'app-1',
      trainerId: 'trainer-1',
      status: 'PENDING',
      submittedAt: '2026-11-08T10:00:00Z',
      reviewedAt: null,
      rejectionReason: null,
      updatedAt: '2026-11-08T10:00:00Z',
    };
    (request as jest.Mock).mockResolvedValueOnce(mockResponse);

    const payload = {
      applicantNote: 'Certified personal trainer with NASM credentials',
      certificateIds: [],
      documentMediaIds: [],
    };

    const result = await trainerApplicationApi.submitApplication(payload);

    expect(request).toHaveBeenCalledWith('/trainer-applications', {
      method: 'POST',
      requiresAuth: true,
      body: payload,
    });
    expect(result).toEqual(mockResponse);
  });

  it('calls GET /trainer-applications/me/current on getCurrentApplication', async () => {
    const mockResponse = {
      id: 'app-1',
      trainerId: 'trainer-1',
      status: 'REJECTED',
      submittedAt: '2026-11-08T10:00:00Z',
      reviewedAt: '2026-11-08T11:00:00Z',
      rejectionReason: 'Certification expired. Please upload valid certificate.',
      updatedAt: '2026-11-08T11:00:00Z',
    };
    (request as jest.Mock).mockResolvedValueOnce(mockResponse);

    const result = await trainerApplicationApi.getCurrentApplication();

    expect(request).toHaveBeenCalledWith('/trainer-applications/me/current', {
      method: 'GET',
      requiresAuth: true,
    });
    expect(result).toEqual(mockResponse);
  });

  it('calls POST /trainer-applications/documents with FormData Blob on uploadDocument', async () => {
    const mockUploadResponse = {
      id: 'doc-uuid-1',
      originalFilename: 'cert.pdf',
      contentType: 'application/pdf',
      sizeBytes: 10240,
      scanStatus: 'CLEAN',
      createdAt: '2026-11-08T10:00:00Z',
    };
    (request as jest.Mock).mockResolvedValueOnce(mockUploadResponse);
    const originalFetch = globalThis.fetch;
    const mockBlob = new Blob(['pdf-bytes'], { type: 'application/pdf' });
    const fetchMock = jest.fn().mockResolvedValueOnce({
      ok: true,
      blob: async () => mockBlob,
    });
    (globalThis as unknown as { fetch: typeof fetch }).fetch = fetchMock as unknown as typeof fetch;

    try {
      const result = await trainerApplicationApi.uploadDocument(
        'file://cache/cert.pdf',
        'cert.pdf',
        'application/pdf',
      );

      expect(fetchMock).toHaveBeenCalledWith('file://cache/cert.pdf');
      expect(request).toHaveBeenCalledWith('/trainer-applications/documents', {
        method: 'POST',
        requiresAuth: true,
        retryOn401: false,
        body: expect.any(FormData),
      });
      const sentFormData = (request as jest.Mock).mock.calls[0][1].body as FormData & {
        get: (name: string) => unknown;
      };
      expect(sentFormData.get('file')).toBeTruthy();
      expect(result).toEqual(mockUploadResponse);
    } finally {
      (globalThis as unknown as { fetch: typeof fetch }).fetch = originalFetch;
    }
  });

  it('uses provided fileBlob directly without fetching local URI and rejects unreadable local files', async () => {
    const mockUploadResponse = {
      id: 'doc-uuid-2',
      originalFilename: 'cert2.pdf',
      contentType: 'application/pdf',
      sizeBytes: 2048,
      scanStatus: 'CLEAN',
      createdAt: '2026-11-08T10:05:00Z',
    };
    (request as jest.Mock).mockResolvedValueOnce(mockUploadResponse);
    const originalFetch = globalThis.fetch;
    const fetchMock = jest.fn().mockResolvedValueOnce({ ok: false });
    (globalThis as unknown as { fetch: typeof fetch }).fetch = fetchMock as unknown as typeof fetch;

    try {
      const providedBlob = new Blob(['web-bytes'], { type: 'application/pdf' });
      const res = await trainerApplicationApi.uploadDocument(
        'blob:http://localhost/1',
        'cert2.pdf',
        'application/pdf',
        providedBlob,
      );
      expect(fetchMock).not.toHaveBeenCalled();
      expect(res).toEqual(mockUploadResponse);

      await expect(
        trainerApplicationApi.uploadDocument('file://cache/missing.pdf', 'missing.pdf', 'application/pdf'),
      ).rejects.toThrow('Không thể đọc tệp tài liệu đã chọn.');
      expect(request).toHaveBeenCalledTimes(1);
    } finally {
      (globalThis as unknown as { fetch: typeof fetch }).fetch = originalFetch;
    }
  });
});
