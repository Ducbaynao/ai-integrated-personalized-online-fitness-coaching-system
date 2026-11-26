import * as apiClient from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import {
  SubmitTrainerApplicationRequest,
  TrainerApplicationResponse,
  TrainerDocumentUploadResponse,
} from '@/types/trainer';

export interface UploadDocumentOptions {
  sessionBinding?: apiClient.AuthSessionBinding;
  shouldContinue?: () => boolean;
}

export const trainerApplicationApi = {
  /**
   * Submit an initial or resubmitted Trainer verification application.
   * Path: /trainer-applications (API base URL includes /api/v1).
   */
  async submitApplication(data: SubmitTrainerApplicationRequest): Promise<TrainerApplicationResponse> {
    return apiClient.request<TrainerApplicationResponse>('/trainer-applications', {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Upload a verification document (PDF, PNG, JPEG).
   * Path: /trainer-applications/documents (API base URL includes /api/v1).
   */
  async uploadDocument(
    fileUri: string,
    filename: string,
    mimeType: string,
    fileBlob?: Blob,
    optionsOrGuard?: (() => boolean) | UploadDocumentOptions,
  ): Promise<TrainerDocumentUploadResponse> {
    const options: UploadDocumentOptions =
      typeof optionsOrGuard === 'function'
        ? { shouldContinue: optionsOrGuard }
        : (optionsOrGuard ?? {});
    const initialBinding = options.sessionBinding ?? apiClient.getAuthSessionBinding?.();
    const assertSameSession = () => {
      if (initialBinding && apiClient.getAuthSessionBinding) {
        const current = apiClient.getAuthSessionBinding();
        if (
          current.authEpoch !== initialBinding.authEpoch ||
          current.capabilityEpoch !== initialBinding.capabilityEpoch
        ) {
          throw new ApiError(401, 'Phiên đăng nhập đã thay đổi.');
        }
      }
      if (options.shouldContinue && !options.shouldContinue()) {
        const abortError = new Error('The operation was aborted');
        abortError.name = 'AbortError';
        throw abortError;
      }
    };

    assertSameSession();
    const formData = new FormData();
    // Expo SDK 57 fetch serializes Blob/File bytes, not React Native's legacy { uri } form part.
    if (fileBlob) {
      formData.append('file', fileBlob, filename);
    } else {
      const localResponse = await fetch(fileUri);
      assertSameSession();
      if (!localResponse.ok) {
        throw new Error('Không thể đọc tệp tài liệu đã chọn. Vui lòng chọn lại.');
      }
      const localBlob = await localResponse.blob();
      assertSameSession();
      formData.append('file', localBlob.slice(0, localBlob.size, mimeType), filename);
    }

    assertSameSession();
    return apiClient.request<TrainerDocumentUploadResponse>('/trainer-applications/documents', {
      method: 'POST',
      requiresAuth: true,
      ...(initialBinding
        ? {
            expectedAuthEpoch: initialBinding.authEpoch,
            expectedCapabilityEpoch: initialBinding.capabilityEpoch,
          }
        : {}),
      ...(options.shouldContinue ? { shouldContinue: options.shouldContinue } : {}),
      retryOn401: false,
      body: formData,
    });
  },

  /**
   * Get current authenticated trainer's verification application.
   * Path: /trainer-applications/me/current.
   */
  async getCurrentApplication(): Promise<TrainerApplicationResponse> {
    return apiClient.request<TrainerApplicationResponse>('/trainer-applications/me/current', {
      method: 'GET',
      requiresAuth: true,
    });
  },
};
