import { measurementApi } from '@/services/measurementApi';
import { progressDashboardApi } from '@/services/progressDashboardApi';
import { request } from '@/services/apiClient';
import { API_BASE_URL } from '@/config/env';

jest.mock('@/services/apiClient', () => ({
  request: jest.fn(),
}));

describe('measurementApi and progressDashboardApi', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('measurementApi.getMetricDefinitions', () => {
    it('sends GET request to /metrics with requiresAuth', async () => {
      (request as jest.Mock).mockResolvedValueOnce([{ id: 1, code: 'WEIGHT', name: 'Cân nặng' }]);

      const result = await measurementApi.getMetricDefinitions();
      expect(request).toHaveBeenCalledWith('/metrics', {
        method: 'GET',
        requiresAuth: true,
      });
      expect(result).toHaveLength(1);
      expect(result[0].code).toBe('WEIGHT');
    });
  });

  describe('measurementApi.getMeasurements', () => {
    it('sends GET request without query params when none provided', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ items: [], total: 0 });

      await measurementApi.getMeasurements();
      expect(request).toHaveBeenCalledWith('/measurements', {
        method: 'GET',
        requiresAuth: true,
      });
    });

    it('serializes all query parameters correctly', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ items: [], total: 0 });

      await measurementApi.getMeasurements({
        studentId: '11111111-1111-1111-1111-111111111111',
        metricCode: 'CHEST',
        from: '2026-01-01T00:00:00Z',
        to: '2026-02-01T00:00:00Z',
        page: 0,
        size: 20,
        sort: 'desc',
      });

      expect(request).toHaveBeenCalledWith(
        expect.stringMatching(
          /^\/measurements\?studentId=11111111-1111-1111-1111-111111111111&metricCode=CHEST&from=2026-01-01T00%3A00%3A00Z&to=2026-02-01T00%3A00%3A00Z&page=0&size=20&sort=desc$/
        ),
        { method: 'GET', requiresAuth: true }
      );
    });
  });

  describe('measurementApi.getMeasurementById', () => {
    it('sends GET request to /measurements/{id}', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'm-1', numericValue: 72.5 });

      const res = await measurementApi.getMeasurementById('m-1');
      expect(request).toHaveBeenCalledWith('/measurements/m-1', {
        method: 'GET',
        requiresAuth: true,
      });
      expect(res.numericValue).toBe(72.5);
    });
  });

  describe('measurementApi.recordMeasurement', () => {
    it('sends POST request to /measurements with body', async () => {
      const payload = {
        metricCode: 'WEIGHT',
        numericValue: 70.5,
        unitCode: 'KG',
        measuredAt: '2026-11-08T08:00:00Z',
      };
      (request as jest.Mock).mockResolvedValueOnce({ id: 'm-2', ...payload });

      const res = await measurementApi.recordMeasurement(payload);
      expect(request).toHaveBeenCalledWith('/measurements', {
        method: 'POST',
        requiresAuth: true,
        body: payload,
      });
      expect(res.id).toBe('m-2');
    });
  });

  describe('measurementApi.correctMeasurement', () => {
    it('sends POST request to /measurements/{id}/correction with reason and updated fields', async () => {
      const correction = {
        numericValue: 71.0,
        reason: 'Nhập sai cân nặng buổi sáng',
      };
      (request as jest.Mock).mockResolvedValueOnce({ id: 'm-3', numericValue: 71.0 });

      const res = await measurementApi.correctMeasurement('m-2', correction);
      expect(request).toHaveBeenCalledWith('/measurements/m-2/correction', {
        method: 'POST',
        requiresAuth: true,
        body: correction,
      });
      expect(res.numericValue).toBe(71.0);
    });
  });

  describe('measurementApi.listProgressPhotos', () => {
    it('serializes viewType and pagination query params', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ content: [], totalElements: 0 });

      await measurementApi.listProgressPhotos({
        viewType: 'FRONT',
        page: 1,
        size: 10,
      });

      expect(request).toHaveBeenCalledWith(
        expect.stringMatching(/^\/measurements\/progress-photos\?viewType=FRONT&page=1&size=10$/),
        { method: 'GET', requiresAuth: true }
      );
    });
  });

  describe('measurementApi.uploadProgressPhoto', () => {
    it('constructs FormData with file payload and optional metadata', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'p-1', viewType: 'SIDE_LEFT' });
      const localFetch = jest.spyOn(globalThis, 'fetch').mockResolvedValue({
        ok: true, blob: async () => new Blob(['photo-bytes']),
      } as Response);
      const append = jest.spyOn(FormData.prototype, 'append');

      try {
        const res = await measurementApi.uploadProgressPhoto({
          fileUri: 'file:///path/photo.jpg',
          fileName: 'photo.jpg',
          mimeType: 'image/jpeg',
          viewType: 'SIDE_LEFT',
          weightAtTime: 70.2,
          weightUnitCode: 'KG',
          takenAt: '2026-11-08T07:30:00Z',
          notes: 'Sau buổi tập chân',
        });

        expect(request).toHaveBeenCalledWith('/measurements/progress-photos', {
          method: 'POST',
          requiresAuth: true,
          body: expect.any(FormData),
        });
        expect(res.id).toBe('p-1');
        expect(localFetch).toHaveBeenCalledWith('file:///path/photo.jpg');
        expect(append).toHaveBeenCalledWith('file', expect.any(Blob), 'photo.jpg');
        const filePart = append.mock.calls.find(call => call[0] === 'file')?.[1] as Blob;
        expect(filePart.type).toBe('image/jpeg');
        expect(filePart.size).toBe(11);
      } finally {
        append.mockRestore();
        localFetch.mockRestore();
      }
    });
  });

  describe('measurementApi.deleteProgressPhoto', () => {
    it('sends DELETE request to /measurements/progress-photos/{id}', async () => {
      (request as jest.Mock).mockResolvedValueOnce(undefined);

      await measurementApi.deleteProgressPhoto('p-1');
      expect(request).toHaveBeenCalledWith('/measurements/progress-photos/p-1', {
        method: 'DELETE',
        requiresAuth: true,
      });
    });
  });

  describe('measurementApi.getProgressPhotoFileUrl', () => {
    it('returns the authorized streaming URL with API_BASE_URL', () => {
      const url = measurementApi.getProgressPhotoFileUrl('photo-123');
      expect(url).toBe(`${API_BASE_URL}/measurements/progress-photos/photo-123/file`);
    });
  });

  describe('progressDashboardApi.getSummary', () => {
    it('sends GET request to /progress/summary without query when none passed', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ scope: 'CURRENT_GOAL' });

      const res = await progressDashboardApi.getSummary();
      expect(request).toHaveBeenCalledWith('/progress/summary', {
        method: 'GET',
        requiresAuth: true,
      });
      expect(res.scope).toBe('CURRENT_GOAL');
    });

    it('serializes studentId, scope, and date range', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ scope: 'LIFETIME' });

      await progressDashboardApi.getSummary({
        studentId: '11111111-1111-1111-1111-111111111111',
        scope: 'LIFETIME',
        fromDate: '2026-01-01',
        toDate: '2026-03-01',
      });

      expect(request).toHaveBeenCalledWith(
        expect.stringMatching(
          /^\/progress\/summary\?studentId=11111111-1111-1111-1111-111111111111&scope=LIFETIME&fromDate=2026-01-01&toDate=2026-03-01$/
        ),
        { method: 'GET', requiresAuth: true }
      );
    });
  });
});
