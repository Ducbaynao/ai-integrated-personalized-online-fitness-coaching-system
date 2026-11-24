import React from 'react';
import renderer from 'react-test-renderer';
import moment from 'moment-timezone';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useLocalSearchParams } from 'expo-router';
import { createCommandKey } from '@/services/coachingApi';
import { foodTextAnalysisApi, FoodTextAnalysisTimeoutError } from '@/services/foodTextAnalysisApi';
import { nutritionApi } from '@/features/nutrition/nutritionApi';
import { FoodAnalysisScreen, nutrientDisplay, formatNutrientSummary } from '@/features/nutrition/FoodAnalysisScreen';
import { ApiError } from '@/types/auth';
import type {
  FoodAnalysisConfirmationReceipt,
  FoodAnalysisDraft,
  FoodAnalysisPreview,
} from '@/types/foodTextAnalysis';

jest.mock('@tanstack/react-query', () => ({ useQuery: jest.fn(), useQueryClient: jest.fn() }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('expo-router', () => ({
  useRouter: jest.fn(() => ({ push: jest.fn(), back: jest.fn() })),
  useLocalSearchParams: jest.fn(() => ({})),
}));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/services/foodTextAnalysisApi', () => ({
  foodTextAnalysisApi: {
    generate: jest.fn(),
    recoverByCommandKey: jest.fn(),
    getDetail: jest.fn(),
    correctItem: jest.fn(),
    preview: jest.fn(),
    confirm: jest.fn(),
  },
  FoodTextAnalysisTimeoutError: class FoodTextAnalysisTimeoutError extends Error {
    constructor() {
      super('Food analysis response timed out; the operation outcome is unknown.');
      this.name = 'FoodTextAnalysisTimeoutError';
    }
  },
}));
jest.mock('@/features/nutrition/nutritionApi', () => ({
  nutritionApi: {
    catalogFoods: jest.fn(),
  },
}));

const mockCatalogFoods = [
  {
    id: 'catalog-food-1',
    name: 'Phở bò chín',
    revisionId: 'rev-cat-1',
    portions: [{ id: 'portion-1', label: 'Bát vừa (500g)', gramsPerServing: 500, factorSource: 'USDA' }],
  },
];

const mockDraftItem = {
  itemId: 'item-1',
  itemRevision: 0,
  detectedName: 'Phở bò',
  candidates: [{ foodId: 'catalog-food-1', revisionId: 'rev-cat-1', name: 'Phở bò tái chín' }],
  selectedFoodId: 'catalog-food-1',
  selectedRevisionId: 'rev-cat-1',
  quantity: 1,
  quantityBasis: 'SERVING' as const,
  portionId: 'portion-1',
  confidence: 0.95,
  missingData: [],
  limitations: ['Ước tính'],
  confirmedFoodLogItemId: null,
};

const mockDraftReady: FoodAnalysisDraft = {
  draftId: 'draft-101',
  studentId: 'student-1',
  commandKey: 'gen-key-1',
  resultType: 'ESTIMATE',
  analysisStatus: 'READY',
  confirmationStatus: 'DRAFT',
  draftRevision: 0,
  day: '2026-11-24',
  timezone: 'Asia/Ho_Chi_Minh',
  consumedAt: '2026-11-24T12:00:00Z',
  mealType: 'LUNCH',
  runId: null,
  expiresAt: null,
  items: [mockDraftItem],
  latestPreview: null,
  createdAt: '2026-11-24T12:00:00Z',
  updatedAt: '2026-11-24T12:00:00Z',
};

const mockPreview: FoodAnalysisPreview = {
  previewId: 'prev-1',
  previewRevision: 1,
  draftId: 'draft-101',
  draftRevision: 0,
  items: [
    {
      selection: {
        itemId: 'item-1',
        itemRevision: 0,
        foodId: 'catalog-food-1',
        catalogRevisionId: 'rev-cat-1',
        quantity: 1,
        quantityBasis: 'SERVING',
        portionId: 'portion-1',
      },
      nutrients: {
        energyKcal: 450,
        proteinG: 25,
        carbohydrateG: 60,
        fatG: 12,
      },
      catalogSource: 'Catalog v1',
    },
  ],
  totalNutrients: {
    energyKcal: 450,
    proteinG: 25,
    carbohydrateG: 60,
    fatG: null, // Unknown fat, must not show as 0!
  },
  createdAt: '2026-11-24T12:01:00Z',
};

const mockReceipt: FoodAnalysisConfirmationReceipt = {
  receiptId: 'receipt-1',
  commandKey: 'confirm-key-1',
  draftId: 'draft-101',
  committedAt: '2026-11-24T12:02:00Z',
  replayed: false,
  confirmedItems: [
    {
      itemId: 'item-1',
      itemRevision: 1,
      foodLogItemId: 'log-item-1',
      mealId: 'meal-1',
      foodId: 'catalog-food-1',
      catalogRevisionId: 'rev-cat-1',
      quantity: 1,
      quantityBasis: 'SERVING',
      portionId: 'portion-1',
      nutrients: mockPreview.items[0].nutrients,
      catalogSource: 'Catalog v1',
    },
  ],
  resultingDraftRevision: 1,
  remainingDraftItemIds: [],
};

function press(tree: renderer.ReactTestRenderer, label: string) {
  const elements = tree.root.findAll((node) => node.props.label === label && typeof node.props.onPress === 'function');
  expect(elements.length).toBeGreaterThan(0);
  // Exercise the real WorkoutButton -> Pressable boundary, including the event
  // React Native supplies. Calling the action without arguments hid P7-019.
  const buttons = elements[0].findAll(node => node.props.accessibilityRole === 'button'
    && node.props.accessibilityLabel === label && typeof node.props.onPress === 'function');
  expect(buttons.length).toBeGreaterThan(0);
  buttons[0].props.onPress({
    nativeEvent: { target: 1, timestamp: 123, pageX: 10, pageY: 20 },
    currentTarget: 1,
    target: 1,
    persist: jest.fn(),
  });
}

function input(tree: renderer.ReactTestRenderer, label: string, value: string) {
  const element = tree.root.findByProps({ accessibilityLabel: label });
  renderer.act(() => element.props.onChangeText(value));
}

describe('F06 FoodAnalysisScreen (Student Mobile UI)', () => {
  const refresh = jest.fn().mockResolvedValue(undefined);
  const removeQueries = jest.fn();
  const setQueryData = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    moment.now = () => Date.parse('2026-11-24T12:00:00Z');
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh' },
      activeCapability: 'STUDENT',
    });
    (useQueryClient as jest.Mock).mockReturnValue({
      invalidateQueries: refresh,
      removeQueries,
      setQueryData,
    });
    (createCommandKey as jest.Mock).mockReturnValue('mock-command-key');
    (useLocalSearchParams as jest.Mock).mockReturnValue({
      day: '2026-11-24',
      timezone: 'Asia/Ho_Chi_Minh',
      mealType: 'LUNCH',
    });
    (nutritionApi.catalogFoods as jest.Mock).mockResolvedValue(mockCatalogFoods);
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => {
      if (queryKey.includes('published')) {
        return { data: mockCatalogFoods, isPending: false, isError: false, error: null, refetch: jest.fn() };
      }
      if (queryKey.includes('draft')) {
        return { data: mockDraftReady, isPending: false, isError: false, error: null, refetch: jest.fn() };
      }
      return { data: null, isPending: false, isError: false, error: null, refetch: jest.fn() };
    });
  });

  afterEach(() => {
    moment.now = () => Date.now();
  });

  it('restricts access when activeCapability is not STUDENT', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'trainer-1' },
      activeCapability: 'TRAINER',
    });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });
    expect(JSON.stringify(tree.toJSON())).toContain('Chỉ dành cho học viên');
    renderer.act(() => tree.unmount());
  });

  it('renders nutrient helper with unknown shown as "Chưa xác định", never 0', () => {
    expect(nutrientDisplay(null, 'g')).toBe('Chưa xác định');
    expect(nutrientDisplay(undefined, 'kcal')).toBe('Chưa xác định');
    expect(nutrientDisplay(0, 'g')).toBe('0 g');
    expect(nutrientDisplay(50.5, 'g')).toBe('50.5 g');

    const summary = formatNutrientSummary({
      energyKcal: 200,
      proteinG: null,
      carbohydrateG: 30,
      fatG: 5,
    });
    expect(summary).toContain('Năng lượng: 200 kcal');
    expect(summary).toContain('Protein: Chưa xác định');
    expect(summary).toContain('Carb: 30 g');
    expect(summary).toContain('Chất béo: 5 g');
  });

  it('generates food analysis draft and transitions to READY detail', async () => {
    (foodTextAnalysisApi.generate as jest.Mock).mockResolvedValueOnce(mockDraftReady);
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    // Confirm context
    renderer.act(() => press(tree, 'Xác nhận ngữ cảnh'));
    expect(JSON.stringify(tree.toJSON())).toContain('Đã xác nhận ngữ cảnh');

    // Input meal text
    input(tree, 'Mô tả bữa ăn', '1 bát phở bò');

    // Press generate
    await renderer.act(async () => {
      press(tree, 'Phân tích bữa ăn (AI)');
    });

    expect(foodTextAnalysisApi.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        text: '1 bát phở bò',
        day: '2026-11-24',
        timezone: 'Asia/Ho_Chi_Minh',
        mealType: 'LUNCH',
        commandKey: 'mock-command-key',
      })
    );
    expect(setQueryData).toHaveBeenCalledWith(['food-analysis', 'student-1', 'draft', 'draft-101'], mockDraftReady);
    renderer.act(() => tree.unmount());
  });

  it('on Generate timeout, retains intent and allows recovery with the exact same commandKey', async () => {
    (foodTextAnalysisApi.generate as jest.Mock).mockRejectedValueOnce(new FoodTextAnalysisTimeoutError());
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    renderer.act(() => press(tree, 'Xác nhận ngữ cảnh'));
    input(tree, 'Mô tả bữa ăn', '1 bát phở');

    await renderer.act(async () => {
      press(tree, 'Phân tích bữa ăn (AI)');
    });

    expect(JSON.stringify(tree.toJSON())).toContain('Khôi phục kết quả phân tích');

    // If recovery returns 404, does NOT assume backend never received request
    (foodTextAnalysisApi.recoverByCommandKey as jest.Mock).mockRejectedValueOnce(
      new ApiError(404, 'NUTRITION_ANALYSIS_NOT_FOUND')
    );
    await renderer.act(async () => {
      press(tree, 'Khôi phục kết quả phân tích');
    });
    expect(JSON.stringify(tree.toJSON())).toContain('Chưa tìm thấy bản nháp với mã lệnh này');

    // Retry recovery with success
    (foodTextAnalysisApi.recoverByCommandKey as jest.Mock).mockResolvedValueOnce(mockDraftReady);
    await renderer.act(async () => {
      press(tree, 'Khôi phục kết quả phân tích');
    });
    expect(foodTextAnalysisApi.recoverByCommandKey).toHaveBeenCalledWith('mock-command-key');
    expect(setQueryData).toHaveBeenCalledWith(['food-analysis', 'student-1', 'draft', 'draft-101'], mockDraftReady);
    renderer.act(() => tree.unmount());
  });

  it('corrects an item selection and quantity, invalidating previous preview', async () => {
    const updatedDraft = {
      ...mockDraftReady,
      draftRevision: 1,
      items: [{ ...mockDraftItem, itemRevision: 1, quantity: 2 }],
    };
    (foodTextAnalysisApi.correctItem as jest.Mock).mockResolvedValueOnce(updatedDraft);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    // Start editing item
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    expect(JSON.stringify(tree.toJSON())).toContain('Chỉnh sửa: Phở bò');

    // Change quantity to 2
    input(tree, 'Số lượng', '2');

    // Save correction
    await renderer.act(async () => {
      press(tree, 'Lưu chỉnh sửa');
    });

    expect(foodTextAnalysisApi.correctItem).toHaveBeenCalledWith(
      'draft-101',
      'item-1',
      expect.objectContaining({
        commandKey: 'mock-command-key',
        expectedDraftRevision: 0,
        expectedItemRevision: 0,
        quantity: 2,
        quantityBasis: 'SERVING',
      })
    );
    expect(setQueryData).toHaveBeenCalledWith(['food-analysis', 'student-1', 'draft', 'draft-101'], updatedDraft);
    renderer.act(() => tree.unmount());
  });

  it('native Correct press sends 1.25 with exact revisions/body and retries the saved intent after form edits', async () => {
    (foodTextAnalysisApi.correctItem as jest.Mock)
      .mockRejectedValueOnce(new ApiError(0, 'offline'))
      .mockResolvedValueOnce(mockDraftReady);
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<FoodAnalysisScreen />); });
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '1.25');
    await renderer.act(async () => press(tree, 'Lưu chỉnh sửa'));

    const expected = {
      commandKey: 'mock-command-key',
      expectedDraftRevision: 0,
      expectedItemRevision: 0,
      selectedFoodId: 'catalog-food-1',
      selectedRevisionId: 'rev-cat-1',
      quantity: 1.25,
      quantityBasis: 'SERVING',
      portionId: 'portion-1',
    };
    expect(foodTextAnalysisApi.correctItem).toHaveBeenCalledWith('draft-101', 'item-1', expected);
    expect(createCommandKey).toHaveBeenCalledTimes(1);
    const originalBody = (foodTextAnalysisApi.correctItem as jest.Mock).mock.calls[0][2];
    input(tree, 'Số lượng', '9');
    await renderer.act(async () => press(tree, 'Thử lại lưu món'));
    expect(foodTextAnalysisApi.correctItem).toHaveBeenCalledTimes(2);
    expect((foodTextAnalysisApi.correctItem as jest.Mock).mock.calls[1]).toEqual(['draft-101', 'item-1', expected]);
    expect((foodTextAnalysisApi.correctItem as jest.Mock).mock.calls[1][2]).toBe(originalBody);
    expect(createCommandKey).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
  });

  it('generates preview and executes atomic Confirm, with retry on timeout', async () => {
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(mockPreview);
    (foodTextAnalysisApi.confirm as jest.Mock).mockRejectedValueOnce(new FoodTextAnalysisTimeoutError());

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    // Select item-1 checkbox
    const checkbox = tree.root.findByProps({ accessibilityLabel: 'Chọn món Phở bò' });
    renderer.act(() => checkbox.props.onPress());

    // Generate preview
    await renderer.act(async () => {
      press(tree, 'Xem trước dinh dưỡng');
    });

    expect(foodTextAnalysisApi.preview).toHaveBeenCalledWith(
      'draft-101',
      expect.objectContaining({
        commandKey: 'mock-command-key',
        expectedDraftRevision: 0,
        selectedItems: [expect.objectContaining({ itemId: 'item-1', quantity: 1 })],
      })
    );
    expect(JSON.stringify(tree.toJSON())).toContain('Tổng dinh dưỡng ước tính:');
    expect(JSON.stringify(tree.toJSON())).toContain('Chất béo: Chưa xác định');

    // Confirm - first attempt times out
    await renderer.act(async () => {
      press(tree, 'Xác nhận ghi vào nhật ký');
    });
    expect(JSON.stringify(tree.toJSON())).toContain('Gửi lại xác nhận (thử lại an toàn)');

    // Re-try confirm with the exact same intent
    (foodTextAnalysisApi.confirm as jest.Mock).mockResolvedValueOnce(mockReceipt);
    await renderer.act(async () => {
      press(tree, 'Gửi lại xác nhận (thử lại an toàn)');
    });

    expect(foodTextAnalysisApi.confirm).toHaveBeenCalledTimes(2);
    // The second call must have the exact same commandKey and body as the first call!
    expect((foodTextAnalysisApi.confirm as jest.Mock).mock.calls[1][1]).toEqual(
      (foodTextAnalysisApi.confirm as jest.Mock).mock.calls[0][1]
    );

    // Shows receipt
    expect(JSON.stringify(tree.toJSON())).toContain('Biên lai xác nhận nhật ký');
    expect(refresh).toHaveBeenCalledWith({ queryKey: ['nutrition', 'student-1'] });
    renderer.act(() => tree.unmount());
  });

  it('initializes day, timezone, and localTime coherently when params.timezone differs from user.timezone', () => {
    // Current UTC time: 2026-11-24T02:30:00Z
    // In Asia/Ho_Chi_Minh (+7): 09:30 AM on 2026-11-24
    // In America/New_York (-5): 21:30 PM on 2026-11-23 (yesterday evening!)
    moment.now = () => Date.parse('2026-11-24T02:30:00Z');
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh' },
      activeCapability: 'STUDENT',
    });
    (useLocalSearchParams as jest.Mock).mockReturnValue({
      timezone: 'America/New_York',
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    const dayInput = tree.root.findByProps({ accessibilityLabel: 'Ngày ghi (YYYY-MM-DD)' });
    const tzInput = tree.root.findByProps({ accessibilityLabel: 'Múi giờ IANA' });
    const timeInput = tree.root.findByProps({ accessibilityLabel: 'Giờ ăn địa phương (HH:mm)' });

    expect(tzInput.props.value).toBe('America/New_York');
    expect(dayInput.props.value).toBe('2026-11-23'); // Formatted in America/New_York, NOT local device/user timezone
    expect(timeInput.props.value).toBe('21:30'); // Formatted in America/New_York (21:30), NOT Asia/Ho_Chi_Minh (09:30)

    renderer.act(() => tree.unmount());
  });

  it('on Correct timeout, retains correction intent and retries with exact same commandKey and body', async () => {
    (foodTextAnalysisApi.correctItem as jest.Mock).mockRejectedValueOnce(new FoodTextAnalysisTimeoutError());

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '3.5');

    await renderer.act(async () => {
      press(tree, 'Lưu chỉnh sửa');
    });

    expect(JSON.stringify(tree.toJSON())).toContain('Thử lại lưu món');

    const updatedDraft = {
      ...mockDraftReady,
      draftRevision: 1,
      items: [{ ...mockDraftItem, itemRevision: 1, quantity: 3.5 }],
    };
    (foodTextAnalysisApi.correctItem as jest.Mock).mockResolvedValueOnce(updatedDraft);

    await renderer.act(async () => {
      press(tree, 'Thử lại lưu món');
    });

    expect(foodTextAnalysisApi.correctItem).toHaveBeenCalledTimes(2);
    // The retry call must have the identical commandKey and body as the initial attempt
    expect((foodTextAnalysisApi.correctItem as jest.Mock).mock.calls[1][2]).toEqual(
      (foodTextAnalysisApi.correctItem as jest.Mock).mock.calls[0][2]
    );

    renderer.act(() => tree.unmount());
  });

  it('blocks Preview and displays warning when server draft contains unsafe integer revision or quantity exceeding safe bounds', async () => {
    // Draft from server containing an unsafe integer revision (> Number.MAX_SAFE_INTEGER)
    const unsafeDraft: FoodAnalysisDraft = {
      ...mockDraftReady,
      draftRevision: 9007199254740994, // Unsafe integer in JS
      items: [
        {
          ...mockDraftItem,
          quantity: 1000000000000, // Quantity corrupted by float64 rounding
        },
      ],
    };

    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => {
      if (queryKey.includes('published')) {
        return { data: mockCatalogFoods, isPending: false, isError: false, error: null, refetch: jest.fn() };
      }
      if (queryKey.includes('draft')) {
        return { data: unsafeDraft, isPending: false, isError: false, error: null, refetch: jest.fn() };
      }
      return { data: null, isPending: false, isError: false, error: null, refetch: jest.fn() };
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    // Selecting an item with unsafe quantity warns the user
    const checkbox = tree.root.findByProps({ accessibilityLabel: 'Chọn món Phở bò' });
    renderer.act(() => checkbox.props.onPress());

    expect(JSON.stringify(tree.toJSON())).toContain('có định lượng không an toàn');
    expect(foodTextAnalysisApi.preview).not.toHaveBeenCalled();

    renderer.act(() => tree.unmount());
  });

  it('isolates actor and discards pending responses when user logs out or switches account', async () => {
    let resolveGenerate!: (draft: FoodAnalysisDraft) => void;
    const pendingPromise = new Promise<FoodAnalysisDraft>((resolve) => {
      resolveGenerate = resolve;
    });
    (foodTextAnalysisApi.generate as jest.Mock).mockReturnValueOnce(pendingPromise);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    renderer.act(() => press(tree, 'Xác nhận ngữ cảnh'));
    input(tree, 'Mô tả bữa ăn', '1 tô cháo sườn');

    // Launch generate in flight
    renderer.act(() => {
      press(tree, 'Phân tích bữa ăn (AI)');
    });

    // In the middle of in-flight request, switch user from student-1 to student-2
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-2', timezone: 'Asia/Ho_Chi_Minh' },
      activeCapability: 'STUDENT',
    });

    // Re-render component with new actor
    renderer.act(() => {
      tree.update(<FoodAnalysisScreen />);
    });

    // Late response arrives from student-1's request
    await renderer.act(async () => {
      resolveGenerate(mockDraftReady);
    });

    // Verify student-1's response was NOT set into student-2's query cache or local draftId
    expect(setQueryData).not.toHaveBeenCalledWith(
      ['food-analysis', 'student-2', 'draft', 'draft-101'],
      expect.anything()
    );
    expect(JSON.stringify(tree.toJSON())).not.toContain('draft-101');

    renderer.act(() => tree.unmount());
  });


  it('cleans up query cache and displays error when access is denied (403/404)', async () => {
    (foodTextAnalysisApi.generate as jest.Mock).mockRejectedValueOnce(
      new ApiError(403, 'NUTRITION_ACCESS_DENIED')
    );
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<FoodAnalysisScreen />);
    });

    renderer.act(() => press(tree, 'Xác nhận ngữ cảnh'));
    input(tree, 'Mô tả bữa ăn', 'cơm trưa');

    await renderer.act(async () => {
      press(tree, 'Phân tích bữa ăn (AI)');
    });

    expect(removeQueries).toHaveBeenCalledWith({ queryKey: ['food-analysis', 'student-1'] });
    expect(JSON.stringify(tree.toJSON())).toContain('Bạn không có quyền thực hiện phân tích dinh dưỡng.');
    renderer.act(() => tree.unmount());
  });

  function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (error: unknown) => void;
    const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
  }

  function mount() {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<FoodAnalysisScreen />); });
    return tree;
  }

  function serveDraft(read: () => FoodAnalysisDraft) {
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
      data: queryKey.includes('published') ? mockCatalogFoods : read(),
      isPending: false, isError: false, error: null, refetch: jest.fn(),
    }));
  }

  function switchActor(tree: renderer.ReactTestRenderer, id: string, capability = 'STUDENT') {
    (useAuth as jest.Mock).mockReturnValue({ user: { id, timezone: 'Asia/Ho_Chi_Minh' }, activeCapability: capability });
    renderer.act(() => tree.update(<FoodAnalysisScreen />));
  }

  function generateFromForm(tree: renderer.ReactTestRenderer) {
    renderer.act(() => press(tree, 'Xác nhận ngữ cảnh'));
    input(tree, 'Mô tả bữa ăn', 'cơm');
    renderer.act(() => press(tree, 'Phân tích bữa ăn (AI)'));
  }

  function selectFirst(tree: renderer.ReactTestRenderer) {
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Chọn món Phở bò' }).props.onPress());
  }

  it.each([
    ['draft', Number.MAX_SAFE_INTEGER + 1], ['draft', -1],
    ['item', Number.MAX_SAFE_INTEGER + 1], ['item', -1],
  ])('blocks new Correct from unsafe %s revision %s through the save action', async (field, value) => {
    serveDraft(() => ({ ...mockDraftReady, draftRevision: field === 'draft' ? value as number : 0,
      items: [{ ...mockDraftItem, itemRevision: field === 'item' ? value as number : 0 }] }));
    const tree = mount();
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '2');
    await renderer.act(async () => press(tree, 'Lưu chỉnh sửa'));
    expect(foodTextAnalysisApi.correctItem).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('phiên bản');
    expect(JSON.stringify(tree.toJSON())).not.toContain('Vui lòng chỉnh sửa lại món');
    renderer.act(() => tree.unmount());
  });

  it.each(['draft', 'item', 'preview', 'previewDraft', 'selection'] as const)(
    'checks %s again on new Confirm, not just on selecting/Preview', async field => {
      let current = mockDraftReady;
      serveDraft(() => current);
      const unsafe = Number.MAX_SAFE_INTEGER + 1;
      const result = { ...mockPreview,
        previewRevision: field === 'preview' ? unsafe : 1,
        draftRevision: field === 'previewDraft' ? unsafe : 0,
        items: [{ ...mockPreview.items[0], selection: { ...mockPreview.items[0].selection,
          itemRevision: field === 'selection' ? unsafe : 0 } }],
      };
      (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(result);
      const tree = mount(); selectFirst(tree);
      await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
      current = { ...mockDraftReady, draftRevision: field === 'draft' ? unsafe : 0,
        items: [{ ...mockDraftItem, itemRevision: field === 'item' ? unsafe : 0 }] };
      renderer.act(() => tree.update(<FoodAnalysisScreen />));
      await renderer.act(async () => press(tree, 'Xác nhận ghi vào nhật ký'));
      expect(foodTextAnalysisApi.confirm).not.toHaveBeenCalled();
      expect(JSON.stringify(tree.toJSON())).toContain('phiên bản');
      renderer.act(() => tree.unmount());
    });

  it('A → B → A never revives an old successful Generate or clears the new request lock', async () => {
    const old = deferred<FoodAnalysisDraft>(), next = deferred<FoodAnalysisDraft>();
    (foodTextAnalysisApi.generate as jest.Mock).mockReturnValueOnce(old.promise).mockReturnValueOnce(next.promise);
    const tree = mount(); generateFromForm(tree);
    switchActor(tree, 'student-2'); switchActor(tree, 'student-1');
    generateFromForm(tree);
    await renderer.act(async () => old.resolve({ ...mockDraftReady, draftId: 'old-session-secret' }));
    expect(setQueryData).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).not.toContain('old-session-secret');
    expect(JSON.stringify(tree.toJSON())).toContain('Đang phân tích...');
    renderer.act(() => press(tree, 'Đang phân tích...'));
    expect(foodTextAnalysisApi.generate).toHaveBeenCalledTimes(2);
    await renderer.act(async () => next.resolve(mockDraftReady));
    expect(setQueryData).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
  });

  it('late rejection/finally from the old session cannot deny access or unlock a new request', async () => {
    const old = deferred<FoodAnalysisDraft>(), next = deferred<FoodAnalysisDraft>();
    (foodTextAnalysisApi.generate as jest.Mock).mockReturnValueOnce(old.promise).mockReturnValueOnce(next.promise);
    const tree = mount(); generateFromForm(tree);
    switchActor(tree, 'student-2'); switchActor(tree, 'student-1'); generateFromForm(tree);
    await renderer.act(async () => old.reject(new ApiError(403, 'old permission failure')));
    expect(removeQueries).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('Đang phân tích...');
    renderer.act(() => press(tree, 'Đang phân tích...'));
    expect(foodTextAnalysisApi.generate).toHaveBeenCalledTimes(2);
    await renderer.act(async () => next.resolve(mockDraftReady));
    renderer.act(() => tree.unmount());
  });

  it('Preview timeout retries its saved key/body even after form/selection changes; only an explicit new Preview gets a new intent', async () => {
    (createCommandKey as jest.Mock).mockReturnValueOnce('preview-old').mockReturnValueOnce('preview-new');
    (foodTextAnalysisApi.preview as jest.Mock).mockRejectedValueOnce(new FoodTextAnalysisTimeoutError())
      .mockResolvedValueOnce(mockPreview).mockResolvedValueOnce({ ...mockPreview, previewId: 'new-preview' });
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    const original = (foodTextAnalysisApi.preview as jest.Mock).mock.calls[0][1];
    const saved = JSON.stringify(original);
    input(tree, 'Giờ ăn địa phương (HH:mm)', '20:30');
    selectFirst(tree); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Thử lại xem trước'));
    expect((foodTextAnalysisApi.preview as jest.Mock).mock.calls[1]).toEqual(['draft-101', original]);
    expect(JSON.stringify(original)).toBe(saved);
    expect(JSON.stringify(tree.toJSON())).not.toContain('Tổng dinh dưỡng ước tính:');
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    const fresh = (foodTextAnalysisApi.preview as jest.Mock).mock.calls[2][1];
    expect(fresh.commandKey).toBe('preview-new');
    expect(fresh.consumedAt).not.toBe(original.consumedAt);
    expect(JSON.stringify(tree.toJSON())).toContain('Tổng dinh dưỡng ước tính:');
    renderer.act(() => tree.unmount());
  });

  it('late Preview response/finally cannot overwrite or unlock a newer Preview intent', async () => {
    const old = deferred<FoodAnalysisPreview>(), next = deferred<FoodAnalysisPreview>();
    (foodTextAnalysisApi.preview as jest.Mock).mockReturnValueOnce(old.promise).mockReturnValueOnce(next.promise);
    const tree = mount(); selectFirst(tree);
    renderer.act(() => press(tree, 'Xem trước dinh dưỡng'));
    input(tree, 'Giờ ăn địa phương (HH:mm)', '20:30');
    renderer.act(() => press(tree, 'Xem trước dinh dưỡng'));
    await renderer.act(async () => old.resolve({ ...mockPreview, totalNutrients: { ...mockPreview.totalNutrients, energyKcal: 111 } }));
    expect(JSON.stringify(tree.toJSON())).not.toContain('111 kcal');
    expect(JSON.stringify(tree.toJSON())).toContain('Đang tính toán...');
    renderer.act(() => press(tree, 'Đang tính toán...'));
    expect(foodTextAnalysisApi.preview).toHaveBeenCalledTimes(2);
    await renderer.act(async () => next.resolve({ ...mockPreview, totalNutrients: { ...mockPreview.totalNutrients, energyKcal: 222 } }));
    expect(JSON.stringify(tree.toJSON())).toContain('222 kcal');
    renderer.act(() => tree.unmount());
  });

  it('Correct network error retains the old intent while editing, blocking a fresh Save with a new key', async () => {
    (foodTextAnalysisApi.correctItem as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'offline'))
      .mockResolvedValueOnce(mockDraftReady);
    const tree = mount();
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '3.5');
    await renderer.act(async () => press(tree, 'Lưu chỉnh sửa'));
    const original = (foodTextAnalysisApi.correctItem as jest.Mock).mock.calls[0][2];
    input(tree, 'Số lượng', '9');
    await renderer.act(async () => press(tree, 'Lưu chỉnh sửa'));
    expect(foodTextAnalysisApi.correctItem).toHaveBeenCalledTimes(1);
    await renderer.act(async () => press(tree, 'Thử lại lưu món'));
    expect((foodTextAnalysisApi.correctItem as jest.Mock).mock.calls[1][2]).toEqual(original);
    expect(original.quantity).toBe(3.5);
    renderer.act(() => tree.unmount());
  });

  it('unmount and capability loss discard old responses without restoring receipt/intents/cache', async () => {
    const pending = deferred<FoodAnalysisConfirmationReceipt>();
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(mockPreview);
    (foodTextAnalysisApi.confirm as jest.Mock).mockReturnValueOnce(pending.promise);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    renderer.act(() => press(tree, 'Xác nhận ghi vào nhật ký'));
    switchActor(tree, 'student-1', 'TRAINER'); switchActor(tree, 'student-1');
    await renderer.act(async () => pending.resolve(mockReceipt));
    expect(refresh).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).not.toContain('Biên lai xác nhận nhật ký');
    renderer.act(() => tree.unmount());
  });

  it('Correct rechecks the current server item revision after opening the edit form', async () => {
    let current = mockDraftReady;
    serveDraft(() => current);
    const tree = mount();
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    current = { ...mockDraftReady, items: [{ ...mockDraftItem, itemRevision: Number.MAX_SAFE_INTEGER + 1 }] };
    renderer.act(() => tree.update(<FoodAnalysisScreen />));
    await renderer.act(async () => press(tree, 'Lưu chỉnh sửa'));
    expect(foodTextAnalysisApi.correctItem).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('phiên bản');
    renderer.act(() => tree.unmount());
  });

  it.each(['quantity', 'food', 'revisionMismatch', 'negativeRevision', 'zeroPreviewRevision'] as const)(
    'new Confirm rejects the mismatched/invalid %s in the actual Preview selection', async field => {
      const result = { ...mockPreview, previewRevision: field === 'zeroPreviewRevision' ? 0 : 1,
        items: [{ ...mockPreview.items[0], selection: { ...mockPreview.items[0].selection,
          quantity: field === 'quantity' ? 999999999999 : 1,
          foodId: field === 'food' ? 'other-food' : 'catalog-food-1',
          itemRevision: field === 'negativeRevision' ? -1 : field === 'revisionMismatch' ? 1 : 0 } }],
      };
      (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(result);
      const tree = mount(); selectFirst(tree);
      await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
      await renderer.act(async () => press(tree, 'Xác nhận ghi vào nhật ký'));
      expect(foodTextAnalysisApi.confirm).not.toHaveBeenCalled();
      renderer.act(() => tree.unmount());
    });

  it('a late old Preview rejection does not clear a new successful preview', async () => {
    const old = deferred<FoodAnalysisPreview>();
    (foodTextAnalysisApi.preview as jest.Mock).mockReturnValueOnce(old.promise).mockResolvedValueOnce(mockPreview);
    const tree = mount(); selectFirst(tree);
    renderer.act(() => press(tree, 'Xem trước dinh dưỡng'));
    input(tree, 'Giờ ăn địa phương (HH:mm)', '21:00');
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    await renderer.act(async () => old.reject(new ApiError(403, 'stale error')));
    expect(removeQueries).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('Tổng dinh dưỡng ước tính:');
    expect(JSON.stringify(tree.toJSON())).not.toContain('Thử lại xem trước');
    renderer.act(() => tree.unmount());
  });

  it('Preview network failure exposes explicit retry with the same intent, without automatically resending', async () => {
    (foodTextAnalysisApi.preview as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValueOnce(mockPreview);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    expect(foodTextAnalysisApi.preview).toHaveBeenCalledTimes(1);
    await renderer.act(async () => press(tree, 'Thử lại xem trước'));
    expect((foodTextAnalysisApi.preview as jest.Mock).mock.calls[1]).toEqual(
      (foodTextAnalysisApi.preview as jest.Mock).mock.calls[0]);
    renderer.act(() => tree.unmount());
  });

  it('a late old Preview success cannot replace the already displayed new preview', async () => {
    const old = deferred<FoodAnalysisPreview>();
    (foodTextAnalysisApi.preview as jest.Mock).mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce({ ...mockPreview, totalNutrients: { ...mockPreview.totalNutrients, energyKcal: 222 } });
    const tree = mount(); selectFirst(tree);
    renderer.act(() => press(tree, 'Xem trước dinh dưỡng'));
    input(tree, 'Giờ ăn địa phương (HH:mm)', '21:00');
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    expect(JSON.stringify(tree.toJSON())).toContain('222 kcal');
    await renderer.act(async () => old.resolve({ ...mockPreview, totalNutrients: { ...mockPreview.totalNutrients, energyKcal: 111 } }));
    expect(JSON.stringify(tree.toJSON())).toContain('222 kcal');
    expect(JSON.stringify(tree.toJSON())).not.toContain('111 kcal');
    renderer.act(() => tree.unmount());
  });

  it('Confirm timeout intent survives changed form, unsafe new server data and an attempted new Preview', async () => {
    let current = mockDraftReady;
    serveDraft(() => current);
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(mockPreview);
    (foodTextAnalysisApi.confirm as jest.Mock).mockRejectedValueOnce(new FoodTextAnalysisTimeoutError())
      .mockResolvedValueOnce({ ...mockReceipt, replayed: true });
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    await renderer.act(async () => press(tree, 'Xác nhận ghi vào nhật ký'));
    const original = (foodTextAnalysisApi.confirm as jest.Mock).mock.calls[0][1];
    current = { ...mockDraftReady, draftRevision: Number.MAX_SAFE_INTEGER + 1,
      expiresAt: '2000-01-01T00:00:00Z' };
    renderer.act(() => tree.update(<FoodAnalysisScreen />));
    input(tree, 'Giờ ăn địa phương (HH:mm)', '22:00');
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    expect(foodTextAnalysisApi.preview).toHaveBeenCalledTimes(1);
    await renderer.act(async () => press(tree, 'Gửi lại xác nhận (thử lại an toàn)'));
    expect((foodTextAnalysisApi.confirm as jest.Mock).mock.calls[1][1]).toEqual(original);
    expect(JSON.stringify(tree.toJSON())).toContain('Biên lai xác nhận nhật ký');
    expect(JSON.stringify(tree.toJSON())).toContain('Biên lai khôi phục');
    renderer.act(() => tree.unmount());
  });

  it('old Confirm invalidation continuation cannot overwrite a new session message or lock', async () => {
    const invalidation = deferred<void>(), next = deferred<FoodAnalysisDraft>();
    refresh.mockReturnValueOnce(invalidation.promise).mockReturnValueOnce(invalidation.promise);
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(mockPreview);
    (foodTextAnalysisApi.confirm as jest.Mock).mockResolvedValueOnce(mockReceipt);
    (foodTextAnalysisApi.generate as jest.Mock).mockReturnValueOnce(next.promise);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    await renderer.act(async () => { press(tree, 'Xác nhận ghi vào nhật ký'); await Promise.resolve(); });
    switchActor(tree, 'student-2'); switchActor(tree, 'student-1'); generateFromForm(tree);
    await renderer.act(async () => invalidation.resolve());
    expect(JSON.stringify(tree.toJSON())).not.toContain('Đã xác nhận ghi vào nhật ký thành công.');
    expect(JSON.stringify(tree.toJSON())).toContain('Đang phân tích...');
    renderer.act(() => press(tree, 'Đang phân tích...'));
    expect(foodTextAnalysisApi.generate).toHaveBeenCalledTimes(1);
    await renderer.act(async () => next.resolve(mockDraftReady));
    renderer.act(() => tree.unmount());
  });

  it('unmounted Generate cannot publish its result into the actor query cache', async () => {
    const old = deferred<FoodAnalysisDraft>();
    (foodTextAnalysisApi.generate as jest.Mock).mockReturnValueOnce(old.promise);
    const tree = mount(); generateFromForm(tree);
    renderer.act(() => tree.unmount());
    await renderer.act(async () => old.resolve(mockDraftReady));
    expect(setQueryData).not.toHaveBeenCalled();
  });

  it('P7-021 dirty quantity cannot Confirm the old snapshot or create another Preview', async () => {
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValue(mockPreview);
    (foodTextAnalysisApi.confirm as jest.Mock).mockResolvedValue(mockReceipt);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '2');
    const oldConfirm = tree.root.findAll(n => n.props.label === 'Xác nhận ghi vào nhật ký');
    if (oldConfirm.length) await renderer.act(async () => press(tree, 'Xác nhận ghi vào nhật ký'));
    expect(foodTextAnalysisApi.confirm).not.toHaveBeenCalled();
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    expect(foodTextAnalysisApi.preview).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(tree.toJSON())).toContain('Có chỉnh sửa chưa lưu');
    expect(foodTextAnalysisApi.correctItem).not.toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it.each(['food', 'revision', 'basis', 'portion'] as const)(
    'P7-021 dirty %s cannot bypass the new Confirm/Preview guard', async field => {
      const catalog = [
        { ...mockCatalogFoods[0], portions: [...mockCatalogFoods[0].portions,
          { ...mockCatalogFoods[0].portions[0], id: 'portion-2', label: 'Khẩu phần khác' }] },
        { ...mockCatalogFoods[0], id: 'food-2', name: 'Món khác', revisionId: 'rev-2' },
      ];
      (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
        data: queryKey.includes('published') ? catalog : { ...mockDraftReady,
          items: [{ ...mockDraftItem, candidates: [{ foodId: 'catalog-food-1', revisionId: 'rev-2', name: 'Bản mới' }] }] },
        isPending: false, isError: false, error: null, refetch: jest.fn(),
      }));
      (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValue(mockPreview);
      const tree = mount(); selectFirst(tree);
      await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
      renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
      const label = { food: 'Chọn món danh mục Món khác', revision: 'Chọn gợi ý Bản mới',
        basis: 'Chọn đơn vị Gam (g)', portion: 'Chọn khẩu phần Khẩu phần khác' }[field];
      renderer.act(() => tree.root.findByProps({ accessibilityLabel: label }).props.onPress());
      expect(JSON.stringify(tree.toJSON())).not.toContain('Xác nhận ghi vào nhật ký');
      await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
      expect(foodTextAnalysisApi.preview).toHaveBeenCalledTimes(1);
      expect(foodTextAnalysisApi.confirm).not.toHaveBeenCalled();
      expect(JSON.stringify(tree.toJSON())).toContain('Có chỉnh sửa chưa lưu');
      renderer.act(() => tree.unmount());
    }
  );

  it.each(['Cancel', 'revert'] as const)('P7-021 %s does not resurrect the old preview; a fresh matching Preview permits Confirm', async action => {
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValue(mockPreview);
    (foodTextAnalysisApi.confirm as jest.Mock).mockResolvedValue(mockReceipt);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '2');
    if (action === 'Cancel') renderer.act(() => press(tree, 'Hủy'));
    else input(tree, 'Số lượng', '1');
    expect(JSON.stringify(tree.toJSON())).not.toContain('Xác nhận ghi vào nhật ký');
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    await renderer.act(async () => press(tree, 'Xác nhận ghi vào nhật ký'));
    expect((foodTextAnalysisApi.confirm as jest.Mock).mock.calls[0][1].selectedItems[0].quantity).toBe(1);
    renderer.act(() => tree.unmount());
  });

  it('P7-021 successful Save requires a new Preview and Confirms the new saved quantity/revisions', async () => {
    let current = mockDraftReady; serveDraft(() => current);
    const updated = { ...mockDraftReady, draftRevision: 1, items: [{ ...mockDraftItem, itemRevision: 1, quantity: 2 }] };
    const nextPreview = { ...mockPreview, draftRevision: 1, previewRevision: 2,
      items: [{ ...mockPreview.items[0], selection: { ...mockPreview.items[0].selection, itemRevision: 1, quantity: 2 } }] };
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(mockPreview).mockResolvedValueOnce(nextPreview);
    (foodTextAnalysisApi.correctItem as jest.Mock).mockImplementationOnce(async () => { current = updated; return updated; });
    (foodTextAnalysisApi.confirm as jest.Mock).mockResolvedValue(mockReceipt);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '2');
    await renderer.act(async () => press(tree, 'Lưu chỉnh sửa'));
    expect(JSON.stringify(tree.toJSON())).not.toContain('Xác nhận ghi vào nhật ký');
    expect(foodTextAnalysisApi.confirm).not.toHaveBeenCalled();
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    await renderer.act(async () => press(tree, 'Xác nhận ghi vào nhật ký'));
    expect((foodTextAnalysisApi.confirm as jest.Mock).mock.calls[0][1]).toEqual(expect.objectContaining({
      expectedDraftRevision: 1, expectedPreviewRevision: 2,
      selectedItems: [expect.objectContaining({ quantity: 2, itemRevision: 1 })],
    }));
    renderer.act(() => tree.unmount());
  });

  it('P7-021 late Preview cannot authorize Confirm after the editor changed or Cancel', async () => {
    const pending = deferred<FoodAnalysisPreview>();
    (foodTextAnalysisApi.preview as jest.Mock).mockReturnValueOnce(pending.promise);
    const tree = mount(); selectFirst(tree);
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    renderer.act(() => press(tree, 'Xem trước dinh dưỡng'));
    input(tree, 'Số lượng', '2');
    renderer.act(() => press(tree, 'Hủy'));
    await renderer.act(async () => pending.resolve(mockPreview));
    expect(JSON.stringify(tree.toJSON())).not.toContain('Xác nhận ghi vào nhật ký');
    expect(foodTextAnalysisApi.confirm).not.toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('P7-021 saved Preview retry keeps its old key/body after dirty edits without authorizing Confirm', async () => {
    (foodTextAnalysisApi.preview as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'offline')).mockResolvedValueOnce(mockPreview);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    const original = (foodTextAnalysisApi.preview as jest.Mock).mock.calls[0][1];
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '2');
    await renderer.act(async () => press(tree, 'Thử lại xem trước'));
    expect((foodTextAnalysisApi.preview as jest.Mock).mock.calls[1][1]).toBe(original);
    expect(JSON.stringify(tree.toJSON())).not.toContain('Xác nhận ghi vào nhật ký');
    renderer.act(() => tree.unmount());
  });

  it.each([false, true])('P7-021 dirty editor with Cancel=%s preserves exact timed-out Confirm replay', async cancel => {
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(mockPreview);
    (foodTextAnalysisApi.confirm as jest.Mock).mockRejectedValueOnce(new FoodTextAnalysisTimeoutError()).mockResolvedValueOnce(mockReceipt);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    await renderer.act(async () => press(tree, 'Xác nhận ghi vào nhật ký'));
    const original = (foodTextAnalysisApi.confirm as jest.Mock).mock.calls[0][1];
    const keys = (createCommandKey as jest.Mock).mock.calls.length;
    renderer.act(() => press(tree, 'Chỉnh sửa / định lượng'));
    input(tree, 'Số lượng', '2');
    if (cancel) renderer.act(() => press(tree, 'Hủy'));
    await renderer.act(async () => press(tree, 'Gửi lại xác nhận (thử lại an toàn)'));
    expect((foodTextAnalysisApi.confirm as jest.Mock).mock.calls[1][1]).toBe(original);
    expect(original.selectedItems[0].quantity).toBe(1);
    expect(createCommandKey).toHaveBeenCalledTimes(keys);
    expect(JSON.stringify(tree.toJSON())).toContain('Biên lai xác nhận nhật ký');
    renderer.act(() => tree.unmount());
  });

  it('a detail authority error hides cached data and revokes pending callbacks in the same session', async () => {
    let denied = false;
    const old = deferred<FoodAnalysisConfirmationReceipt>();
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
      data: queryKey.includes('published') ? mockCatalogFoods : mockDraftReady,
      isPending: false, isError: denied, error: denied ? new ApiError(403, 'revoked') : null, refetch: jest.fn(),
    }));
    (foodTextAnalysisApi.preview as jest.Mock).mockResolvedValueOnce(mockPreview);
    (foodTextAnalysisApi.confirm as jest.Mock).mockReturnValueOnce(old.promise);
    const tree = mount(); selectFirst(tree);
    await renderer.act(async () => press(tree, 'Xem trước dinh dưỡng'));
    renderer.act(() => press(tree, 'Xác nhận ghi vào nhật ký'));
    denied = true; renderer.act(() => tree.update(<FoodAnalysisScreen />));
    expect(JSON.stringify(tree.toJSON())).not.toContain('Phở bò');
    expect(removeQueries).toHaveBeenCalledWith({ queryKey: ['food-analysis', 'student-1'] });
    await renderer.act(async () => old.resolve(mockReceipt));
    expect(refresh).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).not.toContain('Biên lai xác nhận nhật ký');
    renderer.act(() => tree.unmount());
  });
});
