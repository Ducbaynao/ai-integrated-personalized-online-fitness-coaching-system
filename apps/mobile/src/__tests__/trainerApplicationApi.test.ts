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

  it('calls POST /trainer-applications/documents with FormData on uploadDocument', async () => {
    const mockUploadResponse = {
      id: 'doc-uuid-1',
      originalFilename: 'cert.pdf',
      contentType: 'application/pdf',
      sizeBytes: 10240,
      scanStatus: 'CLEAN',
      createdAt: '2026-11-08T10:00:00Z',
    };
    (request as jest.Mock).mockResolvedValueOnce(mockUploadResponse);

    const result = await trainerApplicationApi.uploadDocument(
      'file://cache/cert.pdf',
      'cert.pdf',
      'application/pdf',
    );

    expect(request).toHaveBeenCalledWith('/trainer-applications/documents', {
      method: 'POST',
      requiresAuth: true,
      body: expect.any(FormData),
    });
    expect(result).toEqual(mockUploadResponse);
  });
});
