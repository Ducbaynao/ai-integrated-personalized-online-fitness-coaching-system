import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { coachingQueryKeys, useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { coachingApi } from '@/services/coachingApi';

jest.mock('@/services/coachingApi', () => ({ coachingApi: { getRelationship: jest.fn() } }));

it('rechecks a cached relationship on Attention screen entry before granting access', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  client.setQueryData(coachingQueryKeys.detail('relationship-1'), { relationship: { status: 'ACTIVE' } });
  let resolveRead!: (value: unknown) => void;
  (coachingApi.getRelationship as jest.Mock).mockReturnValue(new Promise(resolve => { resolveRead = resolve; }));
  let fetchedAfterMount: boolean | undefined;
  function Probe() {
    fetchedAfterMount = useRelationshipDetail('relationship-1', true).isFetchedAfterMount;
    return null;
  }
  let tree!: renderer.ReactTestRenderer;
  try {
    renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}><Probe /></QueryClientProvider>); });
    expect(coachingApi.getRelationship).toHaveBeenCalledWith('relationship-1');
    expect(fetchedAfterMount).toBe(false);
    await renderer.act(async () => {
      resolveRead({ relationship: { status: 'PAUSED' } });
      await new Promise(resolve => setTimeout(resolve, 0));
    });
    expect(client.getQueryData(coachingQueryKeys.detail('relationship-1'))).toEqual({ relationship: { status: 'PAUSED' } });
  } finally {
    renderer.act(() => tree?.unmount());
    client.clear();
  }
});
