import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';

export function shouldRetryQuery(failureCount: number, error: Error): boolean {
  if (!(error instanceof ApiError)) {
    return failureCount < 2;
  }
  if ([401, 403, 404].includes(error.status)) {
    return false;
  }
  return (error.status === 0 || error.status >= 500) && failureCount < 2;
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetryQuery,
      retryDelay: (attempt) => Math.min(500 * 2 ** attempt, 2_000),
      refetchOnWindowFocus: false,
    },
  },
});

export function clearQueryCache(): void {
  queryClient.clear();
}

