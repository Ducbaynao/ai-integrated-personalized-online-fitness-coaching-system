import { API_BASE_URL } from '@/config/env';
import { request } from '@/services/apiClient';
import {
  CorrectMeasurementRequest,
  Measurement,
  MeasurementPageResponse,
  MetricDefinition,
  ProgressPhoto,
  ProgressPhotoPageResponse,
  RecordMeasurementRequest,
  UploadProgressPhotoParams,
} from '@/types/measurement';

export const measurementApi = {
  /**
   * List all active metric definitions with canonical units and bounds.
   */
  async getMetricDefinitions(): Promise<MetricDefinition[]> {
    return request<MetricDefinition[]>('/metrics', {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Query paginated measurement time series with optional metric, date range, and sorting.
   */
  async getMeasurements(params?: {
    studentId?: string;
    metricCode?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
    sort?: 'asc' | 'desc';
  }): Promise<MeasurementPageResponse> {
    const query = new URLSearchParams();
    if (params?.studentId) query.set('studentId', params.studentId);
    if (params?.metricCode) query.set('metricCode', params.metricCode);
    if (params?.from) query.set('from', params.from);
    if (params?.to) query.set('to', params.to);
    if (params?.page !== undefined) query.set('page', params.page.toString());
    if (params?.size !== undefined) query.set('size', params.size.toString());
    if (params?.sort) query.set('sort', params.sort);

    const queryString = query.toString();
    const path = queryString ? `/measurements?${queryString}` : '/measurements';
    return request<MeasurementPageResponse>(path, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Fetch a single measurement by ID.
   */
  async getMeasurementById(id: string): Promise<Measurement> {
    return request<Measurement>(`/measurements/${id}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Record a new body measurement.
   */
  async recordMeasurement(data: RecordMeasurementRequest): Promise<Measurement> {
    return request<Measurement>('/measurements', {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Correct an erroneous measurement preserving history and audit.
   */
  async correctMeasurement(id: string, data: CorrectMeasurementRequest): Promise<Measurement> {
    return request<Measurement>(`/measurements/${id}/correction`, {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * List paginated progress photos with date/viewType filters.
   */
  async listProgressPhotos(params?: {
    studentId?: string;
    fromDate?: string;
    toDate?: string;
    viewType?: string;
    page?: number;
    size?: number;
  }): Promise<ProgressPhotoPageResponse> {
    const query = new URLSearchParams();
    if (params?.studentId) query.set('studentId', params.studentId);
    if (params?.fromDate) query.set('fromDate', params.fromDate);
    if (params?.toDate) query.set('toDate', params.toDate);
    if (params?.viewType) query.set('viewType', params.viewType);
    if (params?.page !== undefined) query.set('page', params.page.toString());
    if (params?.size !== undefined) query.set('size', params.size.toString());

    const queryString = query.toString();
    const path = queryString ? `/measurements/progress-photos?${queryString}` : '/measurements/progress-photos';
    return request<ProgressPhotoPageResponse>(path, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Upload a new progress photo with antivirus scan and validation.
   */
  async uploadProgressPhoto(params: UploadProgressPhotoParams): Promise<ProgressPhoto> {
    const formData = new FormData();
    // Expo SDK 57 fetch serializes Blob bytes, not React Native's legacy URI object.
    const localResponse = await fetch(params.fileUri);
    if (!localResponse.ok) throw new Error('Không thể đọc ảnh đã chọn. Vui lòng chọn lại ảnh.');
    const localBlob = await localResponse.blob();
    formData.append('file', localBlob.slice(0, localBlob.size, params.mimeType), params.fileName);

    if (params.studentId) formData.append('studentId', params.studentId);
    if (params.viewType) formData.append('viewType', params.viewType);
    if (params.weightAtTime !== undefined) formData.append('weightAtTime', params.weightAtTime.toString());
    if (params.weightUnitCode) formData.append('weightUnitCode', params.weightUnitCode);
    if (params.takenAt) formData.append('takenAt', params.takenAt);
    if (params.notes) formData.append('notes', params.notes);

    return request<ProgressPhoto>('/measurements/progress-photos', {
      method: 'POST',
      requiresAuth: true,
      body: formData,
    });
  },

  /**
   * Soft-delete a progress photo.
   */
  async deleteProgressPhoto(id: string): Promise<void> {
    return request<void>(`/measurements/progress-photos/${id}`, {
      method: 'DELETE',
      requiresAuth: true,
    });
  },

  /**
   * Helper to construct authorized photo streaming URL.
   */
  getProgressPhotoFileUrl(id: string): string {
    return `${API_BASE_URL}/measurements/progress-photos/${id}/file`;
  },
};
