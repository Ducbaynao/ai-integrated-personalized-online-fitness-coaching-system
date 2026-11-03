# -*- coding: utf-8 -*-
with open('apps/mobile/src/__tests__/workoutPlanScreens.test.tsx', 'r', encoding='utf-8') as f:
    text = f.read()

new_test = """
  it('guarantees Trainer adjustment modal receives current version prescriptions even when viewing a historical version', () => {
    const historicalVersion = {
      ...version,
      id: 'version-0',
      versionNumber: 0,
      current: false,
    };
    const currentVersion = {
      ...version,
      id: 'version-1',
      versionNumber: 1,
      current: true,
    };
    (useWorkoutPlan as jest.Mock).mockReturnValue({
      data: {
        plan: {
          ...plan,
          status: 'ACTIVE',
          decisionOwnerType: 'TRAINER',
          decisionOwnerId: 'trainer-1',
          readContext: 'CURRENT',
        },
        currentVersion,
      },
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    (useWorkoutPlanVersions as jest.Mock).mockReturnValue({
      data: { pages: [{ items: [currentVersion, historicalVersion] }] },
      isPending: false,
      isError: false,
      error: null,
      hasNextPage: false,
      isFetchingNextPage: false,
      refetch: jest.fn(),
      fetchNextPage: jest.fn(),
    });
    (useWorkoutPlanVersion as jest.Mock).mockImplementation((_studentId, _planId, verId) => {
      if (verId === 'version-0') {
        return {
          data: {
            version: historicalVersion,
            sessions: [
              {
                id: 's0',
                weekNumber: 1,
                dayNumber: 1,
                sequenceNumber: 1,
                name: 'Ngày 1 Cũ',
                prescriptions: [
                  {
                    id: 'historical-p1',
                    exercise: { exerciseName: 'Bài Cũ' },
                    targetLoad: 10,
                  },
                ],
              },
            ],
          },
          isPending: false,
          isError: false,
          error: null,
          refetch: jest.fn(),
        };
      }
      return {
        data: {
          version: currentVersion,
          sessions: [
            {
              id: 's1',
              weekNumber: 1,
              dayNumber: 1,
              sequenceNumber: 1,
              name: 'Ngày 1 Hiện Tại',
              prescriptions: [
                {
                  id: 'current-p1',
                  exercise: { exerciseName: 'Bài Hiện Tại' },
                  targetLoad: 50,
                },
              ],
            },
          ],
        },
        isPending: false,
        isError: false,
        error: null,
        refetch: jest.fn(),
      };
    });

    const tree = render(
      <WorkoutPlanDetailScreen
        studentId="student-1"
        planId="plan-1"
        relationshipId={relationship.id}
      />
    );

    const histBtn = tree.root.findByProps({ label: 'Phiên bản 0' });
    renderer.act(() => {
      histBtn.props.onPress();
    });

    const adjustBtn = tree.root.findByProps({ testID: 'trainer-ai-adjustment-button' });
    renderer.act(() => {
      adjustBtn.props.onPress();
    });

    const modal = tree.root.findByProps({ visible: true });
    expect(modal.props.planContext.planVersionNumber).toBe(1);
    expect(modal.props.planContext.prescriptions).toHaveLength(1);
    expect(modal.props.planContext.prescriptions[0].id).toBe('current-p1');

    renderer.act(() => tree.unmount());
  });
});
"""

target = "  });\n});"
if target not in text:
    target = "  });\r\n});"

assert target in text, "Target end of describe not found"

text = text.replace(target, "  });" + new_test)

with open('apps/mobile/src/__tests__/workoutPlanScreens.test.tsx', 'w', encoding='utf-8', newline='\r\n') as f:
    f.write(text)

print("Added regression test to workoutPlanScreens.test.tsx!")
