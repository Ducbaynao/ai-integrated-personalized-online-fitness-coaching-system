import { QueryClient } from '@tanstack/react-query'
import { isRetryableApiError } from './apiClient.ts'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) => failureCount < 2 && isRetryableApiError(error),
      refetchOnWindowFocus: true,
    },
    mutations: {
      retry: false,
    },
  },
})
