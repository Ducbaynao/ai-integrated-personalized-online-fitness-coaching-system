import { isInSessionWeek, isMonday, proposalTimezones, validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';

describe('Trainer-selected IANA timezone', () => {
  it('resolves an ordinary local wall time without using device timezone', () => {
    expect(validProposalOffsets('Asia/Ho_Chi_Minh', '2026-10-12T18:30')).toEqual([
      { offset: '+07:00', instant: Date.parse('2026-10-12T11:30:00Z') },
    ]);
    expect(proposalTimezones('Ho_Chi_Minh')).toContain('Asia/Ho_Chi_Minh');
    expect(validProposalOffsets('Not/A_Zone', '2026-10-12T18:30')).toEqual([]);
  });

  it('rejects DST gaps and requires one of two explicit offsets for repeated wall time', () => {
    expect(validProposalOffsets('America/New_York', '2026-03-08T02:30')).toEqual([]);
    expect(validProposalOffsets('America/New_York', '2026-11-01T01:30').map((choice) => choice.offset)).toEqual(['-04:00', '-05:00']);
  });

  it('keeps week coordinates anchored to Monday', () => {
    expect(isMonday('2026-10-12')).toBe(true);
    expect(isMonday('2026-10-13')).toBe(false);
    expect(isInSessionWeek('2026-10-12', 2, '2026-10-19T18:30')).toBe(true);
    expect(isInSessionWeek('2026-10-12', 2, '2026-10-12T18:30')).toBe(false);
  });
});
