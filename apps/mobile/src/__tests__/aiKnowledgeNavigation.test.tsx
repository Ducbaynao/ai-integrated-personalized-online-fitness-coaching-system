import React from 'react';
import { View, Text } from 'react-native';
import renderer from 'react-test-renderer';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { TrainerHomeScreen } from '@/features/trainer/TrainerHomeScreen';
import { useAuth } from '@/features/auth/AuthContext';
import AiKnowledgeRoute from '@/app/(app)/ai-knowledge/index';
import TrainerAiKnowledgeRoute from '@/app/(app)/coaching/[relationshipId]/ai-knowledge';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';

const mockPush = jest.fn();
let mockSearchParams: Record<string, string> = {};

const mockAiKnowledgeScreen = (props: any) => (
  <View testID="mock-ai-knowledge-screen" {...props}>
    <Text>AiKnowledgeScreen</Text>
  </View>
);

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
  useLocalSearchParams: () => mockSearchParams,
  Redirect: ({ href }: { href: string }) => null,
}));

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('@/features/coaching/coachingQueries', () => ({
  useRelationshipDetail: jest.fn(),
}));

jest.mock('@/features/ai-knowledge/AiKnowledgeScreen', () => ({
  AiKnowledgeScreen: (props: any) => mockAiKnowledgeScreen(props),
}));

const studentUser = {
  id: 'student-1',
  email: 'student@example.com',
  displayName: 'Học viên Test',
  status: 'ACTIVE',
  roles: ['STUDENT'],
  timezone: 'Asia/Ho_Chi_Minh',
  settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
  capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
};

const trainerUser = {
  id: 'trainer-1',
  email: 'trainer@example.com',
  displayName: 'Coach Test',
  status: 'ACTIVE',
  roles: ['TRAINER'],
  timezone: 'Asia/Ho_Chi_Minh',
  settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
  capabilities: { hasStudentProfile: false, hasTrainerProfile: true, canCoach: true },
};

describe('AI Knowledge Navigation Entries', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockSearchParams = {};
  });

  it('renders student AI Knowledge navigation entry and navigates to /ai-knowledge', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: studentUser,
      isLoading: false,
      logout: jest.fn(),
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<HomeScreen />);
    });

    const button = tree.root.findByProps({ testID: 'view-ai-knowledge-button' }).findByProps({ accessibilityRole: 'button' });
    expect(button.props.accessibilityRole).toBe('button');
    expect(button.props.accessibilityLabel).toBe('Tra cứu kiến thức thể hình AI');

    renderer.act(() => {
      button.props.onPress();
    });

    expect(mockPush).toHaveBeenCalledWith('/ai-knowledge');
  });

  it('renders trainer AI Knowledge navigation entry and navigates to /ai-knowledge', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: trainerUser,
      isLoading: false,
      logout: jest.fn(),
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerHomeScreen />);
    });

    const button = tree.root.findByProps({ testID: 'trainer-view-ai-knowledge-button' }).findByProps({ accessibilityRole: 'button' });
    expect(button.props.accessibilityRole).toBe('button');
    expect(button.props.accessibilityLabel).toBe('Tra cứu kiến thức thể hình AI');

    renderer.act(() => {
      button.props.onPress();
    });

    expect(mockPush).toHaveBeenCalledWith('/ai-knowledge');
  });

  it('AiKnowledgeRoute parses studentId, relationshipId, runId from params and passes to AiKnowledgeScreen', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: trainerUser,
      isLoading: false,
    });
    mockSearchParams = {
      studentId: 'student-param-1',
      relationshipId: 'rel-param-2',
      runId: 'run-param-3',
    };

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<AiKnowledgeRoute />);
    });

    const screenMock = tree.root.findByProps({ testID: 'mock-ai-knowledge-screen' });
    expect(screenMock.props.studentId).toBe('student-param-1');
    expect(screenMock.props.relationshipId).toBe('rel-param-2');
    expect(screenMock.props.initialRunId).toBe('run-param-3');
  });

  it('TrainerAiKnowledgeRoute resolves studentId from relationship and passes to AiKnowledgeScreen', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: trainerUser,
      isLoading: false,
    });
    mockSearchParams = {
      relationshipId: 'rel-abc-123',
    };
    (useRelationshipDetail as jest.Mock).mockReturnValue({
      data: {
        relationship: {
          id: 'rel-abc-123',
          studentId: 'student-from-rel',
        },
      },
      isLoading: false,
      isError: false,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerAiKnowledgeRoute />);
    });

    const screenMock = tree.root.findByProps({ testID: 'mock-ai-knowledge-screen' });
    expect(screenMock.props.studentId).toBe('student-from-rel');
    expect(screenMock.props.relationshipId).toBe('rel-abc-123');
  });
});
