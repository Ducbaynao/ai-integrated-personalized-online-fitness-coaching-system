import React, { useRef, useState } from 'react';
import { useRouter, useLocalSearchParams } from 'expo-router';
import { Pressable, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';
import { nutritionApi } from './nutritionApi';
import { foodTextAnalysisApi, FoodTextAnalysisTimeoutError } from '@/services/foodTextAnalysisApi';
import type {
  FoodAnalysisConfirmationReceipt,
  FoodAnalysisConfirmRequest,
  FoodAnalysisItem,
  FoodAnalysisItemCorrectionRequest,
  FoodAnalysisMealType,
  FoodAnalysisNutrients,
  FoodAnalysisPreview,
  FoodAnalysisPreviewRequest,
  FoodAnalysisQuantityBasis,
  FoodAnalysisSelectedItem,
  FoodTextAnalysisRequest,
} from '@/types/foodTextAnalysis';

export const foodAnalysisKeys = {
  draft: (actor: string, draftId: string) => ['food-analysis', actor, 'draft', draftId] as const,
};

export function nutrientDisplay(value: number | null | undefined, unit: string): string {
  if (value === null || value === undefined) return 'Chưa xác định';
  return `${value} ${unit}`;
}

export function formatNutrientSummary(nutrients: FoodAnalysisNutrients): string {
  return [
    `Năng lượng: ${nutrientDisplay(nutrients.energyKcal, 'kcal')}`,
    `Protein: ${nutrientDisplay(nutrients.proteinG, 'g')}`,
    `Carb: ${nutrientDisplay(nutrients.carbohydrateG, 'g')}`,
    `Chất béo: ${nutrientDisplay(nutrients.fatG, 'g')}`,
  ].join(' · ');
}

const mealTypeLabels: Record<FoodAnalysisMealType, string> = {
  BREAKFAST: 'Bữa sáng',
  LUNCH: 'Bữa trưa',
  DINNER: 'Bữa tối',
  SNACK: 'Bữa phụ',
  OTHER: 'Khác',
};

const basisLabels: Record<FoodAnalysisQuantityBasis, string> = {
  GRAM: 'Gam (g)',
  SERVING: 'Khẩu phần',
  MILLILITRE: 'Mililít (ml)',
};

export function FoodAnalysisScreen() {
  const { user, activeCapability, status } = useAuth();
  // Each observed session/capability transition unmounts the previous scope.
  // Returning A → B → A creates a fresh A; the old A's callbacks stay invalid.
  return <FoodAnalysisSession key={`${user?.id ?? ''}:${activeCapability ?? ''}:${status ?? ''}`} />;
}

function safeRevision(value: number, minimum = 0): boolean {
  return Number.isSafeInteger(value) && value >= minimum;
}

// Conservative Mobile precision limit, NOT an approved Nutrition domain policy.
function safeQuantity(value: number): boolean {
  return Number.isFinite(value) && value > 0 && value <= 999999 && /^\d+(\.\d{1,6})?$/.test(String(value));
}

function FoodAnalysisSession() {
  const router = useRouter();
  const params = useLocalSearchParams<{ day?: string; timezone?: string; mealType?: string }>();
  const { user, activeCapability, refreshUser } = useAuth();
  const actor = user?.id ?? '';
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const client = useQueryClient();

  // Coherent Context Initialization: day, timezone and localTime all derive from the exact same instant in effectiveTz
  const effectiveTz = params.timezone || user?.timezone || 'Asia/Ho_Chi_Minh';
  const initialNow = moment.utc().tz(moment.tz.zone(effectiveTz) ? effectiveTz : 'Asia/Ho_Chi_Minh');
  const [day, setDay] = useState(() => params.day || initialNow.format('YYYY-MM-DD'));
  const [timezone, setTimezone] = useState(() => effectiveTz);
  const [localTime, setLocalTime] = useState(() => initialNow.format('HH:mm'));
  const [chosenOffset, setChosenOffset] = useState('');
  const [mealType, setMealType] = useState<FoodAnalysisMealType>(() => {
    const raw = (params.mealType || '').toUpperCase();
    return (['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK', 'OTHER'].includes(raw) ? raw : 'LUNCH') as FoodAnalysisMealType;
  });
  const [confirmedContext, setConfirmedContext] = useState(false);

  // Analysis / generation state
  const [inputText, setInputText] = useState('');
  const [draftId, setDraftId] = useState<string | null>(null);
  const [generationIntent, setGenerationIntent] = useState<FoodTextAnalysisRequest | null>(null);

  // Editing state & correction intent
  const [editingItem, setEditingItem] = useState<FoodAnalysisItem | null>(null);
  const [editFoodId, setEditFoodId] = useState('');
  const [editRevisionId, setEditRevisionId] = useState('');
  const [editQuantity, setEditQuantity] = useState('');
  const [editBasis, setEditBasis] = useState<FoodAnalysisQuantityBasis>('GRAM');
  const [editPortionId, setEditPortionId] = useState('');
  const [correctionIntent, setCorrectionIntent] = useState<{
    draftId: string;
    itemId: string;
    body: FoodAnalysisItemCorrectionRequest;
  } | null>(null);

  // Selection & Preview state
  const [selectedItemIds, setSelectedItemIds] = useState<string[]>([]);
  const [preview, setPreview] = useState<FoodAnalysisPreview | null>(null);
  const [previewIntent, setPreviewIntent] = useState<{
    draftId: string; body: FoodAnalysisPreviewRequest; reviewVersion: number;
  } | null>(null);
  const reviewVersion = useRef(0);

  // Confirmation state
  const [confirmIntent, setConfirmIntent] = useState<{ draftId: string; body: FoodAnalysisConfirmRequest } | null>(null);
  const [receipt, setReceipt] = useState<FoodAnalysisConfirmationReceipt | null>(null);
  const [receiptHistory, setReceiptHistory] = useState<FoodAnalysisConfirmationReceipt[]>([]);

  // General feedback & lock
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const mounted = useRef(true);
  const [accessDenied, setAccessDenied] = useState(false);
  const denied = useRef(false);
  type ActiveRequest = { id: symbol; kind: string };
  const activeRequest = useRef<ActiveRequest | null>(null);
  React.useLayoutEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; activeRequest.current = null; };
  }, []);

  const beginRequest = (kind: string): ActiveRequest => {
    const token = { id: Symbol(kind), kind };
    activeRequest.current = token;
    submitting.current = true;
    setBusy(true);
    return token;
  };
  const isCurrent = (token: ActiveRequest) => mounted.current && !denied.current && activeRequest.current === token;
  const finishRequest = (token: ActiveRequest) => {
    if (!isCurrent(token)) return;
    activeRequest.current = null;
    submitting.current = false;
    setBusy(false);
  };
  const clearAccess = () => {
    denied.current = true;
    activeRequest.current = null;
    submitting.current = false;
    setBusy(false);
    setAccessDenied(true);
    setDraftId(null); setGenerationIntent(null); setCorrectionIntent(null);
    setPreviewIntent(null); setConfirmIntent(null); setEditingItem(null);
    setPreview(null); setReceipt(null); setReceiptHistory([]); setSelectedItemIds([]); setInputText('');
    client.removeQueries({ queryKey: ['food-analysis', actor] });
  };
  const handleAuthFailure = async (
    token: ActiveRequest,
    error: ApiError,
    deniedMessage: string
  ) => {
    if (error.status === 401 && typeof refreshUser === 'function') {
      try {
        const refreshedUser = await refreshUser();
        if (!isCurrent(token)) return;
        if (
          refreshedUser &&
          (refreshedUser.id !== actor || refreshedUser.capabilities?.hasStudentProfile === false)
        ) {
          clearAccess();
          setMessage(deniedMessage);
          return;
        }
        setMessage('Phiên truy cập vừa được làm mới. Vui lòng bấm thử lại thao tác vừa rồi với đúng mã lệnh đã lưu.');
        return;
      } catch (refreshError) {
        if (!isCurrent(token)) return;
        if (!(refreshError instanceof ApiError && [401, 403].includes(refreshError.status))) {
          setMessage('Không thể làm mới phiên truy cập do lỗi kết nối. Bạn có thể bấm thử lại sau với đúng mã lệnh đã lưu.');
          return;
        }
      }
    }
    clearAccess();
    setMessage(error.status === 401 ? 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.' : deniedMessage);
  };
  const invalidatePreview = () => {
    reviewVersion.current += 1;
    setPreview(null);
    // Supersede only Preview. Abort would not prove the backend rolled back.
    // Its saved intent is still available for explicit receipt/snapshot replay.
    if (activeRequest.current?.kind === 'preview') {
      activeRequest.current = null;
      submitting.current = false;
      setBusy(false);
    }
  };
  const revisionError = () => setMessage('Số phiên bản không an toàn trên thiết bị (cần safe integer không âm). Không thể gửi lệnh mới; hãy tải lại hoặc báo lỗi phiên bản.');

  // Context validity
  const validContext = Boolean(
    actor &&
    activeCapability === 'STUDENT' &&
    day &&
    /^\d{4}-\d{2}-\d{2}$/.test(day) &&
    moment.tz.zone(timezone) &&
    confirmedContext
  );

  // Queries
  const draftQuery = useQuery({
    queryKey: foodAnalysisKeys.draft(actor, draftId ?? ''),
    queryFn: async ({ signal }) => {
      const result = await foodTextAnalysisApi.getDetail(draftId!, { signal });
      if (!mounted.current || denied.current) {
        const error = new Error('Food analysis session ended'); error.name = 'AbortError'; throw error;
      }
      return result;
    },
    enabled: Boolean(actor && activeCapability === 'STUDENT' && draftId && !accessDenied),
    retry: false,
  });

  const catalogQuery = useQuery({
    queryKey: ['nutrition-catalog', 'published'],
    queryFn: nutritionApi.catalogFoods,
    enabled: Boolean(actor && activeCapability === 'STUDENT'),
  });

  const authorityError = draftQuery.error instanceof ApiError && [401, 403, 404].includes(draftQuery.error.status);
  React.useEffect(() => {
    if (authorityError && !denied.current) clearAccess();
  });
  const draft = !accessDenied && !authorityError && draftQuery.data?.studentId === actor ? draftQuery.data : null;
  const draftSyncedToReceipt = Boolean(
    receipt &&
    draft &&
    draft.draftId === receipt.draftId &&
    draft.draftRevision >= receipt.resultingDraftRevision
  );
  const authoritativeRemainingCount = draftSyncedToReceipt && draft
    ? draft.items.filter((item) => !item.confirmedFoodLogItemId).length
    : receipt?.remainingDraftItemIds.length ?? 0;
  const canContinueRemaining = Boolean(
    receipt &&
    (draftSyncedToReceipt
      ? draft?.confirmationStatus !== 'CONFIRMED' && authoritativeRemainingCount > 0
      : receipt.remainingDraftItemIds.length > 0)
  );
  const priorReceipts = receiptHistory.filter((entry) => entry.receiptId !== receipt?.receiptId);
  const savedEditingItem = editingItem ? draft?.items.find(i => i.itemId === editingItem.itemId) : null;
  const editorDirty = Boolean(editingItem && (!savedEditingItem
    || editingItem.itemRevision !== savedEditingItem.itemRevision
    || editFoodId !== (savedEditingItem.selectedFoodId ?? '')
    || editRevisionId !== (savedEditingItem.selectedRevisionId ?? '')
    || (editQuantity.trim() === '' ? null : Number(editQuantity)) !== (savedEditingItem.quantity ?? null)
    || editBasis !== (savedEditingItem.quantityBasis ?? 'GRAM')
    || editPortionId !== (savedEditingItem.portionId ?? '')));
  const unsavedEditorMessage = 'Có chỉnh sửa chưa lưu. Hãy Lưu chỉnh sửa rồi Xem trước lại trước khi xác nhận.';

  // Resolve ISO instant for consumedAt
  const resolveConsumedAt = (): string | null => {
    const offsets = validProposalOffsets(timezone, `${day}T${localTime}`);
    if (offsets.length === 0) return null;
    const selected = offsets.length === 1 ? offsets[0] : offsets.find((o) => o.offset === chosenOffset);
    if (!selected) return null;
    return new Date(selected.instant).toISOString();
  };

  // Generate action
  const handleGenerate = async () => {
    if (!mounted.current || denied.current || submitting.current) return;
    if (confirmIntent || correctionIntent) {
      setMessage('Lệnh trước chưa rõ kết quả. Hãy thử lại đúng lệnh đã lưu trước khi tạo phân tích mới.');
      return;
    }
    if (!validContext) {
      setMessage('Hãy xác nhận ngày và múi giờ hợp lệ trước khi phân tích.');
      return;
    }
    const trimmed = inputText.trim();
    if (!trimmed) {
      setMessage('Hãy nhập mô tả bữa ăn (ví dụ: 1 bát phở bò, 1 quả trứng luộc).');
      return;
    }
    const consumedAt = resolveConsumedAt();
    if (!consumedAt) {
      setMessage('Giờ ăn địa phương chưa đúng hoặc bị lặp. Hãy chọn giờ và offset hợp lệ.');
      return;
    }

    const currentActor = actor;
    const commandKey = createCommandKey();
    const requestBody: FoodTextAnalysisRequest = {
      text: trimmed,
      day,
      timezone,
      consumedAt,
      mealType,
      commandKey,
    };

    const token = beginRequest('generate');
    setMessage('');
    setGenerationIntent(requestBody);
    invalidatePreview();
    setPreviewIntent(null);
    setReceipt(null);

    try {
      const result = await foodTextAnalysisApi.generate(requestBody);
      if (!isCurrent(token)) return;
      setDraftId(result.draftId);
      setReceiptHistory([]);
      client.setQueryData(foodAnalysisKeys.draft(currentActor, result.draftId), result);
      if (result.analysisStatus === 'READY') {
        setMessage('Đã hoàn tất phân tích mô tả bữa ăn. Vui lòng rà soát và định lượng từng món.');
      } else if (result.analysisStatus === 'PROCESSING') {
        setMessage('Hệ thống đang phân tích mô tả bữa ăn. Bạn có thể bấm "Làm mới trạng thái".');
      } else if (result.analysisStatus === 'BLOCKED') {
        setMessage('Mô tả bữa ăn bị chặn hoặc không phù hợp phân tích.');
      } else {
        setMessage('Không thể phân tích bữa ăn từ mô tả này. Bạn có thể thử lại với mô tả rõ hơn.');
      }
    } catch (error) {
      if (!isCurrent(token)) return;
      if (error instanceof FoodTextAnalysisTimeoutError) {
        setMessage('Chưa rõ kết quả phân tích do phản hồi quá lâu. Bạn có thể bấm "Khôi phục kết quả phân tích" bên dưới.');
      } else if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
        await handleAuthFailure(token, error, 'Bạn không có quyền thực hiện phân tích dinh dưỡng.');
      } else if (error instanceof ApiError && error.status === 409) {
        setMessage('Lệnh đã bị trùng lặp hoặc xung đột. Vui lòng kiểm tra lại.');
      } else if (error instanceof ApiError && error.status === 400) {
        setMessage('Thông tin mô tả hoặc ngữ cảnh chưa hợp lệ. Vui lòng kiểm tra lại.');
      } else {
        setMessage('Không thể kết nối máy chủ. Bạn có thể thử khôi phục lại kết quả phân tích.');
      }
    } finally {
      finishRequest(token);
    }
  };

  // Recovery action
  const handleRecover = async () => {
    if (!mounted.current || denied.current || submitting.current || !generationIntent) return;
    const currentActor = actor;
    const token = beginRequest('recover');
    setMessage('');

    try {
      const recovered = await foodTextAnalysisApi.recoverByCommandKey(generationIntent.commandKey);
      if (!isCurrent(token)) return;
      if (draftId && draftId !== recovered.draftId) {
        setReceiptHistory([]);
      }
      setDraftId(recovered.draftId);
      client.setQueryData(foodAnalysisKeys.draft(currentActor, recovered.draftId), recovered);
      setMessage(`Đã khôi phục bản nháp (Trạng thái: ${recovered.analysisStatus}).`);
    } catch (error) {
      if (!isCurrent(token)) return;
      if (error instanceof ApiError && error.status === 404) {
        setMessage('Chưa tìm thấy bản nháp với mã lệnh này. Nếu vừa gửi, hệ thống có thể đang xử lý. Bạn có thể thử lại sau.');
      } else if (error instanceof ApiError && [401, 403].includes(error.status)) {
        await handleAuthFailure(token, error, 'Bạn không còn quyền truy cập bản nháp này.');
      } else {
        setMessage('Chưa khôi phục được. Kiểm tra mạng và thử lại sau.');
      }
    } finally {
      finishRequest(token);
    }
  };

  // Start editing an item
  const startEditItem = (item: FoodAnalysisItem) => {
    setEditingItem(item);
    setEditFoodId(item.selectedFoodId ?? '');
    setEditRevisionId(item.selectedRevisionId ?? '');
    setEditQuantity(item.quantity !== null && item.quantity !== undefined ? String(item.quantity) : '');
    setEditBasis(item.quantityBasis ?? 'GRAM');
    setEditPortionId(item.portionId ?? '');
    setMessage('');
  };

  // Save correction
  const handleSaveCorrection = async (retryIntent?: { draftId: string; itemId: string; body: FoodAnalysisItemCorrectionRequest }) => {
    if (!mounted.current || denied.current || submitting.current) return;
    if (confirmIntent || (!retryIntent && correctionIntent)) {
      setMessage('Lệnh trước chưa rõ kết quả. Hãy thử lại đúng lệnh đã lưu; chỉnh form không thay đổi intent cũ.');
      return;
    }
    const currentDraftId = retryIntent?.draftId ?? draft?.draftId;
    const currentItemId = retryIntent?.itemId ?? editingItem?.itemId;
    if (!currentDraftId || !currentItemId) return;

    let correctionBody: FoodAnalysisItemCorrectionRequest;
    if (retryIntent) {
      correctionBody = retryIntent.body;
    } else {
      if (!draft || !editingItem) return;
      const currentItem = draft.items.find(i => i.itemId === editingItem.itemId);
      if (!safeRevision(draft.draftRevision) || !safeRevision(editingItem.itemRevision)
          || (currentItem && !safeRevision(currentItem.itemRevision))) {
        revisionError(); return;
      }
      if (!currentItem || currentItem.itemRevision !== editingItem.itemRevision || currentItem.confirmedFoodLogItemId) {
        setMessage('Món đã thay đổi trong lúc chỉnh sửa. Hãy tải lại và rà soát trước khi lưu.'); return;
      }
      const qty = Number(editQuantity);
      if (!editFoodId) {
        setMessage('Vui lòng chọn một thực phẩm từ danh mục hoặc gợi ý.');
        return;
      }
      if (!Number.isFinite(qty) || qty <= 0 || !/^\d+(\.\d{1,6})?$/.test(editQuantity.trim())) {
        setMessage('Số lượng phải lớn hơn 0 và tối đa 6 chữ số thập phân.');
        return;
      }
      if (qty > 999999) {
        setMessage('Số lượng vượt giới hạn kỹ thuật của Mobile (999,999); đây không phải giới hạn nghiệp vụ dinh dưỡng.');
        return;
      }

      const chosenFood = catalogQuery.data?.find((f) => f.id === editFoodId);
      if (!chosenFood) {
        setMessage('Không tìm thấy thực phẩm được chọn trong danh mục đã công bố.');
        return;
      }

      const revisionId = chosenFood.revisionId;
      let portionId: string | null = null;
      if (editBasis === 'SERVING' || editBasis === 'MILLILITRE') {
        portionId = editPortionId || (chosenFood.portions[0]?.id ?? null);
        if (!portionId) {
          setMessage('Món này chưa có quy đổi khẩu phần hoặc thể tích. Hãy chọn đơn vị gam (g).');
          return;
        }
      }

      correctionBody = {
        commandKey: createCommandKey(),
        expectedDraftRevision: draft.draftRevision,
        expectedItemRevision: editingItem.itemRevision,
        selectedFoodId: editFoodId,
        selectedRevisionId: editRevisionId || revisionId,
        quantity: qty,
        quantityBasis: editBasis,
        portionId,
      };
    }

    const currentActor = actor;
    const token = beginRequest('correct');
    setMessage('');
    setCorrectionIntent({ draftId: currentDraftId, itemId: currentItemId, body: correctionBody });

    try {
      const updated = await foodTextAnalysisApi.correctItem(currentDraftId, currentItemId, correctionBody);
      if (!isCurrent(token)) return;
      client.setQueryData(foodAnalysisKeys.draft(currentActor, updated.draftId), updated);
      setEditingItem(null);
      setCorrectionIntent(null);
      invalidatePreview(); // Changing an item makes any previous preview obsolete
      setPreviewIntent(null);
      setMessage('Đã cập nhật định lượng món ăn.');
    } catch (error) {
      if (!isCurrent(token)) return;
      if (error instanceof FoodTextAnalysisTimeoutError) {
        setMessage('Chưa rõ kết quả lưu chỉnh sửa do phản hồi quá lâu. Bạn có thể bấm "Thử lại lưu món" để gửi lại an toàn bằng mã lệnh cũ.');
      } else if (error instanceof ApiError && error.status === 409) {
        setMessage('Bản nháp hoặc món ăn đã thay đổi. Đang tải lại dữ liệu...');
        setCorrectionIntent(null);
        await draftQuery.refetch();
      } else if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
        await handleAuthFailure(token, error, 'Bạn không còn quyền sửa bản nháp này.');
      } else if (error instanceof ApiError && error.status === 400) {
        setMessage('Thông tin định lượng chưa hợp lệ.');
        setCorrectionIntent(null);
      } else {
        setMessage('Chưa rõ kết quả lưu món. Bạn có thể bấm "Thử lại lưu món" với đúng mã lệnh đã gửi.');
      }
    } finally {
      finishRequest(token);
    }
  };

  // Toggle item selection for preview / confirm
  const toggleItemSelection = (itemId: string) => {
    if (!selectedItemIds.includes(itemId)) {
      const item = draft?.items.find((i) => i.itemId === itemId);
      if (item) {
        if (!safeRevision(item.itemRevision)) {
          setMessage(`Món "${item.detectedName ?? item.itemId}" có số phiên bản không an toàn.`);
          return;
        }
        if (item.quantity !== null && (!Number.isFinite(item.quantity) || item.quantity <= 0 || item.quantity > 999999 || !/^\d+(\.\d{1,6})?$/.test(String(item.quantity)))) {
          setMessage(`Món "${item.detectedName ?? item.itemId}" có định lượng không an toàn theo giới hạn kỹ thuật Mobile (999,999 hoặc sai định dạng), không phải giới hạn nghiệp vụ. Vui lòng chỉnh sửa trước khi chọn.`);
          return;
        }
      }
    }
    setSelectedItemIds((prev) =>
      prev.includes(itemId) ? prev.filter((id) => id !== itemId) : [...prev, itemId]
    );
    invalidatePreview(); // Any change in selection clears current preview
  };

  // Preview persists a snapshot and command receipt in Spring. A lost response
  // is an unknown outcome, so retry uses the saved intent, never current form.
  const handlePreview = async (retryIntent?: NonNullable<typeof previewIntent>) => {
    if (!mounted.current || denied.current || submitting.current || !draft) return;
    if (confirmIntent || correctionIntent) {
      setMessage('Lệnh sửa/xác nhận trước chưa rõ kết quả. Hãy thử lại đúng lệnh đã lưu trước.'); return;
    }
    if (!retryIntent && editorDirty) { setMessage(unsavedEditorMessage); return; }
    let intent: NonNullable<typeof previewIntent>;
    if (retryIntent) {
      intent = retryIntent;
    } else {
      if (selectedItemIds.length === 0) {
        setMessage('Vui lòng chọn ít nhất một món đã định lượng để xem trước dinh dưỡng.'); return;
      }
      const consumedAt = resolveConsumedAt();
      if (!consumedAt) { setMessage('Giờ ăn địa phương chưa hợp lệ.'); return; }
      if (!safeRevision(draft.draftRevision)) { revisionError(); return; }
      const selectedList: FoodAnalysisSelectedItem[] = [];
      for (const itemId of selectedItemIds) {
        const item = draft.items.find((i) => i.itemId === itemId);
        if (!item || !item.selectedFoodId || !item.selectedRevisionId || item.quantity === null || !item.quantityBasis) {
          setMessage('Mỗi món được chọn phải có thực phẩm và số lượng hoàn chỉnh.'); return;
        }
        if (item.confirmedFoodLogItemId) { setMessage('Món đã được xác nhận trước đó.'); return; }
        if (!safeRevision(item.itemRevision)) { revisionError(); return; }
        if (!safeQuantity(item.quantity)) {
          setMessage('Định lượng không nằm trong giới hạn kỹ thuật Mobile (999,999 và 6 chữ số thập phân), không phải giới hạn nghiệp vụ.'); return;
        }
        selectedList.push({ itemId: item.itemId, itemRevision: item.itemRevision, foodId: item.selectedFoodId,
          catalogRevisionId: item.selectedRevisionId, quantity: item.quantity, quantityBasis: item.quantityBasis,
          portionId: item.portionId });
      }
      intent = { draftId: draft.draftId, reviewVersion: reviewVersion.current,
        body: { day, timezone, consumedAt, mealType, commandKey: createCommandKey(),
          expectedDraftRevision: draft.draftRevision, selectedItems: selectedList } };
    }
    const token = beginRequest('preview');
    setPreviewIntent(intent); // Save before I/O, including exact key, context, order and revisions.
    setPreview(null);
    setMessage('');
    try {
      const result = await foodTextAnalysisApi.preview(intent.draftId, intent.body);
      if (!isCurrent(token)) return;
      setPreviewIntent(null);
      if (intent.reviewVersion !== reviewVersion.current || intent.draftId !== draft.draftId) {
        setMessage('Đã khôi phục bản xem trước của ngữ cảnh cũ. Hãy tạo bản xem trước mới cho lựa chọn hiện tại.');
        return;
      }
      setPreview(result);
      setMessage('Đã lưu bản xem trước dinh dưỡng từ danh mục.');
    } catch (error) {
      if (!isCurrent(token)) return;
      if (error instanceof ApiError && error.status === 409) {
        setPreviewIntent(null);
        setMessage('Bản nháp đã thay đổi so với bản xem trước. Đang tải lại dữ liệu...');
        await draftQuery.refetch();
      } else if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
        await handleAuthFailure(token, error, 'Bạn không còn quyền xem bản nháp này.');
      } else if (error instanceof ApiError && error.status === 400) {
        setPreviewIntent(null); setMessage('Lựa chọn xem trước chưa hợp lệ.');
      } else {
        setMessage('Chưa rõ kết quả lưu bản xem trước. Thử lại xem trước sẽ dùng đúng mã lệnh và nội dung cũ; xem trước mới là intent mới.');
      }
    } finally { finishRequest(token); }
  };

  // Confirm action
  const handleConfirm = async (retryIntent?: { draftId: string; body: FoodAnalysisConfirmRequest }) => {
    if (!mounted.current || denied.current || submitting.current) return;
    if (!retryIntent && (confirmIntent || correctionIntent)) {
      setMessage('Lệnh trước chưa rõ kết quả. Hãy thử lại đúng intent đã lưu trước.'); return;
    }
    // A dirty editor cannot authorize a NEW command from an older snapshot.
    // Exact replay of a previously sent Confirm must remain available.
    if (!retryIntent && editorDirty) { setMessage(unsavedEditorMessage); return; }
    const currentDraftId = retryIntent?.draftId ?? draft?.draftId;
    if (!currentDraftId) return;

    let confirmBody: FoodAnalysisConfirmRequest;
    if (retryIntent) {
      confirmBody = retryIntent.body;
    } else {
      if (!draft || !preview) {
        setMessage('Hãy tạo và kiểm tra bản xem trước dinh dưỡng trước khi xác nhận.');
        return;
      }
      // Every new write path validates its own revisions; selection guards alone
      // cannot cover a server refetch or an unsafe Preview response.
      if (!safeRevision(draft.draftRevision) || !safeRevision(preview.draftRevision)
          || !safeRevision(preview.previewRevision, 1)
          || preview.items.some(({ selection }) => !safeRevision(selection.itemRevision))
          || draft.items.filter(i => selectedItemIds.includes(i.itemId)).some(i => !safeRevision(i.itemRevision))) {
        revisionError(); return;
      }
      if (preview.draftId !== draft.draftId || preview.draftRevision !== draft.draftRevision
          || preview.items.length === 0 || preview.items.length !== selectedItemIds.length
          || new Set(preview.items.map(i => i.selection.itemId)).size !== preview.items.length
          || preview.items.some(({ selection: s }) => {
            const item = draft.items.find(i => i.itemId === s.itemId);
            return !selectedItemIds.includes(s.itemId) || !item || item.confirmedFoodLogItemId
              || !safeQuantity(s.quantity) || !item.selectedFoodId || !item.selectedRevisionId
              || item.itemRevision !== s.itemRevision || item.selectedFoodId !== s.foodId
              || item.selectedRevisionId !== s.catalogRevisionId || item.quantity !== s.quantity
              || item.quantityBasis !== s.quantityBasis || item.portionId !== s.portionId;
          })) {
        setMessage('Lựa chọn hoặc bản nháp không còn khớp bản xem trước. Hãy xem trước lại; không thể tạo xác nhận mới.'); return;
      }
      const consumedAt = resolveConsumedAt();
      if (!consumedAt) {
        setMessage('Giờ ăn địa phương chưa hợp lệ.');
        return;
      }
      confirmBody = {
        commandKey: createCommandKey(),
        expectedDraftRevision: draft.draftRevision,
        previewId: preview.previewId,
        expectedPreviewRevision: preview.previewRevision,
        day,
        timezone,
        consumedAt,
        mealType,
        selectedItems: preview.items.map((i) => i.selection),
      };
    }

    const currentActor = actor;
    const token = beginRequest('confirm');
    setMessage('');
    setConfirmIntent({ draftId: currentDraftId, body: confirmBody });

    try {
      const receiptResult = await foodTextAnalysisApi.confirm(currentDraftId, confirmBody);
      if (!isCurrent(token)) return;
      setReceipt(receiptResult);
      setReceiptHistory((prev) =>
        prev.some((existing) => existing.receiptId === receiptResult.receiptId)
          ? prev
          : [...prev, receiptResult]
      );
      setConfirmIntent(null);
      setPreview(null);
      setSelectedItemIds([]);

      // Invalidate relevant nutrition queries
      await Promise.all([
        client.invalidateQueries({ queryKey: ['nutrition', currentActor] }),
        client.invalidateQueries({ queryKey: foodAnalysisKeys.draft(currentActor, currentDraftId) }),
      ]);
      if (!isCurrent(token)) return;
      setMessage(
        receiptResult.replayed
          ? 'Lệnh xác nhận đã được hoàn tất trước đó (biên lai đã khôi phục).'
          : 'Đã xác nhận ghi vào nhật ký dinh dưỡng thành công.'
      );
    } catch (error) {
      if (!isCurrent(token)) return;
      if (error instanceof FoodTextAnalysisTimeoutError) {
        setMessage('Chưa rõ kết quả xác nhận do phản hồi quá lâu. Bạn có thể bấm "Gửi lại xác nhận (thử lại an toàn)" mà không sợ bị ghi trùng.');
      } else if (error instanceof ApiError && error.status === 409) {
        setMessage('Dữ liệu hoặc bản xem trước đã thay đổi. Vui lòng xem trước lại trước khi xác nhận.');
        setConfirmIntent(null);
        await draftQuery.refetch();
      } else if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
        await handleAuthFailure(token, error, 'Bạn không còn quyền xác nhận bản nháp này.');
      } else {
        setMessage('Chưa rõ kết quả xác nhận. Bạn có thể bấm "Gửi lại xác nhận (thử lại an toàn)" với đúng mã lệnh đã lưu.');
      }
    } finally {
      finishRequest(token);
    }
  };

  const handleContinueRemaining = async () => {
    if (!mounted.current || denied.current || submitting.current || !receipt) return;
    const currentReceipt = receipt;
    const token = beginRequest('continue-remaining');
    setMessage('');
    try {
      const refetched = await draftQuery.refetch();
      if (!isCurrent(token)) return;
      if (refetched?.isError || refetched?.error) {
        const refetchErr = refetched.error;
        if (refetchErr instanceof ApiError && [401, 403, 404].includes(refetchErr.status)) {
          await handleAuthFailure(token, refetchErr, 'Bạn không còn quyền truy cập bản nháp này.');
          return;
        }
        setMessage('Chưa tải được trạng thái bản nháp mới nhất từ máy chủ. Vui lòng thử lại.');
        return;
      }
      const latestDraft = refetched?.data ?? (draftQuery.data?.studentId === actor ? draftQuery.data : null);
      if (
        !latestDraft ||
        latestDraft.studentId !== actor ||
        latestDraft.draftId !== currentReceipt.draftId ||
        latestDraft.draftRevision < currentReceipt.resultingDraftRevision
      ) {
        setMessage('Trạng thái bản nháp chưa đồng bộ với biên lai mới nhất. Vui lòng thử lại.');
        return;
      }
      const remaining = latestDraft.items.filter((item) => !item.confirmedFoodLogItemId);
      setSelectedItemIds([]);
      invalidatePreview();
      setPreviewIntent(null);
      setEditingItem(null);
      if (latestDraft.confirmationStatus === 'CONFIRMED' || remaining.length === 0) {
        setMessage('Tất cả món trong bản nháp đã được xác nhận.');
        return;
      }
      setReceipt(null);
    } catch (error) {
      if (!isCurrent(token)) return;
      if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
        await handleAuthFailure(token, error, 'Bạn không còn quyền truy cập bản nháp này.');
      } else {
        setMessage('Chưa tải được trạng thái bản nháp mới nhất từ máy chủ. Vui lòng thử lại.');
      }
    } finally {
      finishRequest(token);
    }
  };

  const inputField = (
    label: string,
    value: string,
    onChange: (s: string) => void,
    keyboardType: 'default' | 'decimal-pad' = 'default',
    placeholder?: string
  ) => (
    <View style={{ marginBottom: 10 }}>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        placeholder={placeholder}
        placeholderTextColor={theme.textSecondary}
        value={value}
        onChangeText={(value) => {
          invalidatePreview();
          onChange(value);
        }}
        keyboardType={keyboardType}
        style={{
          borderWidth: 1,
          borderColor: theme.border,
          color: theme.textPrimary,
          minHeight: 48,
          padding: 10,
          borderRadius: 8,
        }}
      />
    </View>
  );

  if (activeCapability !== 'STUDENT') {
    return (
      <WorkoutState
        title="Chỉ dành cho học viên"
        message="Vui lòng chuyển sang vai trò học viên để sử dụng tính năng ước tính dinh dưỡng."
      />
    );
  }

  if (!actor || accessDenied || authorityError) {
    return <WorkoutState title="Không thể truy cập bản nháp" message={message || 'Bạn không còn quyền truy cập dữ liệu dinh dưỡng này.'} />;
  }

  return (
    <ScrollView
      testID="food-analysis-screen"
      style={[styles.screen, { backgroundColor: theme.canvas }]}
      contentContainerStyle={styles.content}>
      <Text style={[styles.title, { color: theme.textPrimary }]}>Ước tính dinh dưỡng từ văn bản (AI)</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>
        Nhập mô tả bữa ăn tự nhiên. Hệ thống AI đưa ra gợi ý ước tính (ESTIMATE). Dữ liệu chỉ được ghi vào nhật ký khi bạn xác nhận.
      </Text>

      {/* Context Card */}
      <WorkoutCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Ngữ cảnh bữa ăn</Text>
        {inputField('Ngày ghi (YYYY-MM-DD)', day, (v) => {
          setDay(v);
          setConfirmedContext(false);
        })}
        {inputField('Múi giờ IANA', timezone, (v) => {
          setTimezone(v);
          setConfirmedContext(false);
        })}
        {inputField('Giờ ăn địa phương (HH:mm)', localTime, (v) => {
          setLocalTime(v);
          setChosenOffset('');
        })}
        {validProposalOffsets(timezone, `${day}T${localTime}`).length > 1 ? (
          <View style={{ marginBottom: 10 }}>
            <Text style={[styles.body, { color: theme.textPrimary }]}>Giờ này bị lặp do đổi giờ. Chọn UTC offset:</Text>
            {validProposalOffsets(timezone, `${day}T${localTime}`).map((opt) => (
              <Pressable
                key={opt.offset}
                accessibilityRole="radio"
                accessibilityLabel={`Chọn offset ${opt.offset}`}
                accessibilityState={{ checked: chosenOffset === opt.offset }}
                onPress={() => { invalidatePreview(); setChosenOffset(opt.offset); }}
                style={{ paddingVertical: 6 }}>
                <Text style={[styles.body, { color: theme.textPrimary }]}>
                  {chosenOffset === opt.offset ? '◉' : '○'} {opt.offset}
                </Text>
              </Pressable>
            ))}
          </View>
        ) : null}

        {/* Meal Type selection */}
        <Text style={[styles.label, { color: theme.textPrimary, marginBottom: 6 }]}>Bữa ăn</Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
          {(['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK', 'OTHER'] as FoodAnalysisMealType[]).map((type) => (
            <Pressable
              key={type}
              accessibilityRole="radio"
              accessibilityLabel={`Chọn ${mealTypeLabels[type]}`}
              accessibilityState={{ checked: mealType === type }}
              onPress={() => { invalidatePreview(); setMealType(type); }}
              style={{
                paddingHorizontal: 12,
                paddingVertical: 8,
                borderRadius: 6,
                borderWidth: 1,
                borderColor: mealType === type ? theme.primary : theme.border,
                backgroundColor: mealType === type ? theme.surfaceSubtle : 'transparent',
              }}>
              <Text style={{ color: mealType === type ? theme.primary : theme.textPrimary }}>
                {mealTypeLabels[type]}
              </Text>
            </Pressable>
          ))}
        </View>

        <WorkoutButton
          label={confirmedContext ? 'Đã xác nhận ngữ cảnh' : 'Xác nhận ngữ cảnh'}
          disabled={!day || !moment(day, 'YYYY-MM-DD', true).isValid() || !moment.tz.zone(timezone)}
          onPress={() => setConfirmedContext(true)}
        />
      </WorkoutCard>

      {/* Message Banner */}
      {message ? (
        <Text accessibilityRole="alert" style={[styles.body, { color: theme.textPrimary, marginVertical: 8 }]}>
          {message}
        </Text>
      ) : null}

      {/* Retry Intent / Recovery Actions */}
      {generationIntent && !draftId && !busy ? (
        <WorkoutCard>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Có một yêu cầu phân tích chưa hoàn tất. Bạn có thể khôi phục kết quả:
          </Text>
          <WorkoutButton label="Khôi phục kết quả phân tích" onPress={handleRecover} />
        </WorkoutCard>
      ) : null}

      {correctionIntent && !busy ? (
        <WorkoutCard>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Lệnh lưu chỉnh sửa món ăn trước đó chưa rõ kết quả:
          </Text>
          <WorkoutButton label="Thử lại lưu món" onPress={() => handleSaveCorrection(correctionIntent)} />
        </WorkoutCard>
      ) : null}

      {previewIntent && !busy ? (
        <WorkoutCard>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Bản xem trước đã gửi chưa rõ kết quả. Thử lại dùng intent cũ; đổi form rồi bấm xem trước là intent mới.
          </Text>
          <WorkoutButton label="Thử lại xem trước" onPress={() => handlePreview(previewIntent)} />
        </WorkoutCard>
      ) : null}

      {confirmIntent && !busy ? (
        <WorkoutCard>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Lệnh xác nhận trước đó chưa rõ kết quả:
          </Text>
          <WorkoutButton label="Gửi lại xác nhận (thử lại an toàn)" onPress={() => handleConfirm(confirmIntent)} />
        </WorkoutCard>
      ) : null}

      {/* Receipt View */}
      {receipt ? (
        <WorkoutCard>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>Biên lai xác nhận nhật ký</Text>
          {(() => {
            const activeRoundIndex = receiptHistory.findIndex((entry) => entry.receiptId === receipt.receiptId);
            const roundNumber = activeRoundIndex >= 0 ? activeRoundIndex + 1 : 1;
            return (
              <Text style={[styles.body, { color: theme.textSecondary }]}>
                {`Lượt ${roundNumber} · Mã biên lai: ${receipt.receiptId}`}
              </Text>
            );
          })()}
          {receipt.replayed ? (
            <Text style={[styles.body, { color: theme.primary }]}>
              (Biên lai khôi phục từ lệnh đã xử lý thành công trước đó)
            </Text>
          ) : null}
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Thời gian ghi: {moment(receipt.committedAt).format('YYYY-MM-DD HH:mm:ss')}
          </Text>
          <Text style={[styles.label, { color: theme.textPrimary, marginTop: 8 }]}>Các món đã ghi thành công:</Text>
          {receipt.confirmedItems.map((ci) => (
            <View key={ci.itemId} style={{ paddingVertical: 4 }}>
              <Text style={[styles.body, { color: theme.textPrimary }]}>
                • {ci.quantity} {basisLabels[ci.quantityBasis as FoodAnalysisQuantityBasis] ?? ci.quantityBasis}
              </Text>
              <Text style={[styles.body, { color: theme.textSecondary, fontSize: 13 }]}>
                {formatNutrientSummary(ci.nutrients)}
              </Text>
            </View>
          ))}
          {priorReceipts.length > 0 ? (
            <View style={{ marginTop: 10, paddingTop: 8, borderTopWidth: 1, borderTopColor: theme.border }}>
              <Text style={[styles.label, { color: theme.textPrimary }]}>
                Các lượt đã xác nhận trước trong bản nháp này:
              </Text>
              {priorReceipts.map((prevReceipt, index) => (
                <Text key={prevReceipt.receiptId} style={[styles.body, { color: theme.textSecondary, fontSize: 13 }]}>
                  {`Lượt ${index + 1} · Mã biên lai: ${prevReceipt.receiptId} · Bản nháp #${prevReceipt.resultingDraftRevision} · ${prevReceipt.confirmedItems.length} món`}
                </Text>
              ))}
            </View>
          ) : null}
          {canContinueRemaining ? (
            <>
              <Text style={[styles.body, { color: theme.textSecondary, marginTop: 8 }]}>
                {`Còn ${authoritativeRemainingCount} món chưa xác nhận vẫn đang được giữ ở bản nháp.`}
              </Text>
              <WorkoutButton
                label={busy ? 'Đang tải bản nháp...' : 'Tiếp tục xác nhận món còn lại'}
                secondary
                disabled={busy}
                onPress={() => {
                  void handleContinueRemaining();
                }}
              />
            </>
          ) : null}
          <WorkoutButton
            label="Quay lại nhật ký dinh dưỡng"
            onPress={() => router.push('/nutrition' as never)}
          />
        </WorkoutCard>
      ) : null}

      {/* Generate Section */}
      {!receipt && (
        <WorkoutCard>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>Nhập mô tả bữa ăn</Text>
          <TextInput
            accessibilityLabel="Mô tả bữa ăn"
            placeholder="Ví dụ: 1 bát phở bò tái chín, 2 cái quẩy, 1 ly trà đá"
            placeholderTextColor={theme.textSecondary}
            multiline
            numberOfLines={4}
            value={inputText}
            onChangeText={setInputText}
            style={{
              borderWidth: 1,
              borderColor: theme.border,
              color: theme.textPrimary,
              minHeight: 80,
              padding: 10,
              borderRadius: 8,
              textAlignVertical: 'top',
              marginBottom: 10,
            }}
          />
          <WorkoutButton
            label={busy ? 'Đang phân tích...' : 'Phân tích bữa ăn (AI)'}
            disabled={busy || !inputText.trim() || !validContext}
            onPress={handleGenerate}
          />
        </WorkoutCard>
      )}

      {/* Draft Status / Detail */}
      {draft && !receipt ? (
        <WorkoutCard>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>
            Kết quả ước tính ({draft.analysisStatus})
          </Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            {`Loại: ${draft.resultType} · Bản nháp #${draft.draftRevision} · ${
              draft.confirmationStatus === 'CONFIRMED'
                ? 'Đã xác nhận toàn bộ'
                : draft.confirmationStatus === 'PARTIALLY_CONFIRMED'
                ? 'Đã xác nhận một phần'
                : 'Chưa ghi nhật ký'
            }`}
          </Text>
          {priorReceipts.length > 0 ? (
            <View style={{ marginVertical: 6, padding: 10, borderRadius: 8, backgroundColor: theme.surfaceSubtle }}>
              <Text style={[styles.label, { color: theme.textPrimary }]}>
                Lịch sử biên lai các lượt đã xác nhận:
              </Text>
              {priorReceipts.map((prevReceipt, index) => (
                <Text key={prevReceipt.receiptId} style={[styles.body, { color: theme.textSecondary, fontSize: 13 }]}>
                  {`Lượt ${index + 1} · Mã biên lai: ${prevReceipt.receiptId} · ${prevReceipt.confirmedItems.length} món · ${moment(prevReceipt.committedAt).format('HH:mm:ss')}`}
                </Text>
              ))}
            </View>
          ) : null}

          {draft.analysisStatus === 'PROCESSING' ? (
            <View style={{ marginVertical: 8 }}>
              <WorkoutState title="Đang xử lý phân tích" message="Hệ thống đang trích xuất danh sách món ăn từ mô tả." busy />
              <WorkoutButton label="Làm mới trạng thái" onPress={() => draftQuery.refetch()} />
            </View>
          ) : null}

          {draft.analysisStatus === 'FAILED' ? (
            <WorkoutState
              title="Phân tích không thành công"
              message="Không thể phân tích thực phẩm từ mô tả này. Bạn có thể sửa lại mô tả và gửi lại."
            />
          ) : null}

          {draft.analysisStatus === 'READY' && draft.items.length === 0 ? (
            <WorkoutState
              title="Không nhận diện được món"
              message="Không tìm thấy thực phẩm nào trong mô tả bữa ăn."
            />
          ) : null}

          {/* Item List */}
          {draft.analysisStatus === 'READY' &&
            draft.items.map((item) => {
              const isConfirmed = Boolean(item.confirmedFoodLogItemId);
              const isSelected = selectedItemIds.includes(item.itemId);
              const selectedFood = catalogQuery.data?.find((f) => f.id === item.selectedFoodId);

              return (
                <View
                  key={item.itemId}
                  style={{
                    borderWidth: 1,
                    borderColor: theme.border,
                    borderRadius: 8,
                    padding: 12,
                    marginVertical: 6,
                    backgroundColor: isConfirmed ? theme.surfaceSubtle : 'transparent',
                  }}>
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                    <Text style={[styles.label, { color: theme.textPrimary, fontSize: 16 }]}>
                      {item.detectedName ?? 'Món chưa rõ tên'}
                    </Text>
                    {isConfirmed ? (
                      <Text style={{ color: theme.primary, fontSize: 13, fontWeight: 'bold' }}>✓ Đã ghi nhật ký</Text>
                    ) : (
                      <Pressable
                        accessibilityRole="checkbox"
                        accessibilityLabel={`Chọn món ${item.detectedName ?? item.itemId}`}
                        accessibilityState={{ checked: isSelected, disabled: isConfirmed || !item.selectedFoodId }}
                        disabled={isConfirmed || !item.selectedFoodId}
                        onPress={() => toggleItemSelection(item.itemId)}
                        style={{ padding: 4 }}>
                        <Text style={{ fontSize: 18, color: theme.textPrimary }}>
                          {isSelected ? '☑' : '☐'}
                        </Text>
                      </Pressable>
                    )}
                  </View>

                  {/* Badges for missing data & limitations */}
                  {item.missingData && item.missingData.length > 0 ? (
                    <Text style={[styles.body, { color: theme.textSecondary, fontSize: 13 }]}>
                      Thiếu: {item.missingData.join(', ')}
                    </Text>
                  ) : null}
                  {item.limitations && item.limitations.length > 0 ? (
                    <Text style={[styles.body, { color: theme.textSecondary, fontSize: 13 }]}>
                      Lưu ý: {item.limitations.join(', ')}
                    </Text>
                  ) : null}

                  {/* Current selection summary */}
                  <View style={{ marginTop: 6 }}>
                    <Text style={[styles.body, { color: theme.textPrimary }]}>
                      Thực phẩm ghép nối: {selectedFood ? selectedFood.name : 'Chưa ghép nối thực phẩm'}
                    </Text>
                    {item.quantity !== null && item.quantity !== undefined ? (
                      <Text style={[styles.body, { color: theme.textPrimary }]}>
                        Định lượng: {item.quantity} {basisLabels[item.quantityBasis as FoodAnalysisQuantityBasis] ?? item.quantityBasis}
                      </Text>
                    ) : (
                      <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có số lượng</Text>
                    )}
                  </View>

                  {!isConfirmed ? (
                    <WorkoutButton
                      label="Chỉnh sửa / định lượng"
                      secondary
                      onPress={() => startEditItem(item)}
                    />
                  ) : null}
                </View>
              );
            })}
        </WorkoutCard>
      ) : null}

      {/* Inline Item Editor */}
      {editingItem && draft ? (
        <WorkoutCard>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>
            {`Chỉnh sửa: ${editingItem.detectedName ?? 'Món ăn'}`}
          </Text>
          {editorDirty ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.textPrimary }]}>
            {unsavedEditorMessage}
          </Text> : null}

          {/* AI Candidate Suggestions */}
          {editingItem.candidates && editingItem.candidates.length > 0 ? (
            <View style={{ marginBottom: 10 }}>
              <Text style={[styles.label, { color: theme.textPrimary }]}>Gợi ý từ AI:</Text>
              {editingItem.candidates.map((cand) => (
                <Pressable
                  key={cand.foodId}
                  accessibilityRole="radio"
                  accessibilityLabel={`Chọn gợi ý ${cand.name}`}
                  accessibilityState={{ checked: editFoodId === cand.foodId }}
                  onPress={() => {
                    invalidatePreview();
                    setEditFoodId(cand.foodId);
                    setEditRevisionId(cand.revisionId);
                  }}
                  style={{ paddingVertical: 6, flexDirection: 'row', alignItems: 'center' }}>
                  <Text style={[styles.body, { color: theme.textPrimary }]}>
                    {editFoodId === cand.foodId ? '◉ ' : '○ '} {cand.name}
                  </Text>
                </Pressable>
              ))}
            </View>
          ) : null}

          {/* Catalog Picker */}
          <Text style={[styles.label, { color: theme.textPrimary }]}>Hoặc chọn từ danh mục thực phẩm:</Text>
          <ScrollView style={{ maxHeight: 150, borderWidth: 1, borderColor: theme.border, borderRadius: 6, marginBottom: 10 }}>
            {catalogQuery.data?.map((food) => (
              <Pressable
                key={food.id}
                accessibilityRole="radio"
                accessibilityLabel={`Chọn món danh mục ${food.name}`}
                accessibilityState={{ checked: editFoodId === food.id }}
                onPress={() => {
                  invalidatePreview();
                  setEditFoodId(food.id);
                  setEditRevisionId(food.revisionId);
                  if (food.portions.length > 0 && !editPortionId) {
                    setEditPortionId(food.portions[0].id);
                  }
                }}
                style={{
                  padding: 8,
                  borderBottomWidth: 1,
                  borderBottomColor: theme.border,
                  backgroundColor: editFoodId === food.id ? theme.surfaceSubtle : 'transparent',
                }}>
                <Text style={{ color: theme.textPrimary, fontWeight: editFoodId === food.id ? 'bold' : 'normal' }}>
                  {food.name}
                </Text>
              </Pressable>
            ))}
          </ScrollView>

          {/* Basis Picker */}
          <Text style={[styles.label, { color: theme.textPrimary, marginBottom: 6 }]}>Đơn vị tính</Text>
          <View style={{ flexDirection: 'row', gap: 8, marginBottom: 10 }}>
            {(['GRAM', 'SERVING', 'MILLILITRE'] as FoodAnalysisQuantityBasis[]).map((b) => (
              <Pressable
                key={b}
                accessibilityRole="radio"
                accessibilityLabel={`Chọn đơn vị ${basisLabels[b]}`}
                accessibilityState={{ checked: editBasis === b }}
                onPress={() => { invalidatePreview(); setEditBasis(b); }}
                style={{
                  paddingHorizontal: 12,
                  paddingVertical: 8,
                  borderRadius: 6,
                  borderWidth: 1,
                  borderColor: editBasis === b ? theme.primary : theme.border,
                  backgroundColor: editBasis === b ? theme.surfaceSubtle : 'transparent',
                }}>
                <Text style={{ color: editBasis === b ? theme.primary : theme.textPrimary }}>
                  {basisLabels[b]}
                </Text>
              </Pressable>
            ))}
          </View>

          {/* Portion Picker if not GRAM */}
          {editBasis !== 'GRAM' && editFoodId ? (
            <View style={{ marginBottom: 10 }}>
              <Text style={[styles.label, { color: theme.textPrimary }]}>Khẩu phần quy đổi:</Text>
              {catalogQuery.data
                ?.find((f) => f.id === editFoodId)
                ?.portions.map((p) => (
                  <Pressable
                    key={p.id}
                    accessibilityRole="radio"
                    accessibilityLabel={`Chọn khẩu phần ${p.label}`}
                    accessibilityState={{ checked: editPortionId === p.id }}
                    onPress={() => { invalidatePreview(); setEditPortionId(p.id); }}
                    style={{ paddingVertical: 4 }}>
                    <Text style={[styles.body, { color: theme.textPrimary }]}>
                      {editPortionId === p.id ? '◉ ' : '○ '} {p.label}
                    </Text>
                  </Pressable>
                ))}
            </View>
          ) : null}

          {/* Quantity Input */}
          {inputField('Số lượng', editQuantity, setEditQuantity, 'decimal-pad', 'Ví dụ: 100 hoặc 1.5')}

          <WorkoutButton label="Lưu chỉnh sửa" disabled={busy} onPress={() => handleSaveCorrection()} />
          <WorkoutButton label="Hủy" secondary onPress={() => setEditingItem(null)} />
        </WorkoutCard>
      ) : null}

      {/* Preview Section */}
      {draft && draft.analysisStatus === 'READY' && selectedItemIds.length > 0 && !receipt ? (
        <WorkoutCard>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>
            Xem trước dinh dưỡng ({selectedItemIds.length} món được chọn)
          </Text>
          <WorkoutButton
            label={busy ? 'Đang tính toán...' : 'Xem trước dinh dưỡng'}
            disabled={busy || editorDirty}
            onPress={() => handlePreview()}
          />

          {preview ? (
            <View style={{ marginTop: 12 }}>
              <Text style={[styles.label, { color: theme.textPrimary }]}>Tổng dinh dưỡng ước tính:</Text>
              <Text style={[styles.body, { color: theme.textPrimary, marginVertical: 4 }]}>
                {formatNutrientSummary(preview.totalNutrients)}
              </Text>

              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 8 }]}>Chi tiết từng món:</Text>
              {preview.items.map((pi) => (
                <View key={pi.selection.itemId} style={{ paddingVertical: 4 }}>
                  <Text style={[styles.body, { color: theme.textPrimary }]}>
                    • {pi.selection.quantity} {basisLabels[pi.selection.quantityBasis as FoodAnalysisQuantityBasis] ?? pi.selection.quantityBasis}
                  </Text>
                  <Text style={[styles.body, { color: theme.textSecondary, fontSize: 13 }]}>
                    {formatNutrientSummary(pi.nutrients)}
                  </Text>
                </View>
              ))}

              <View style={{ marginTop: 12 }}>
                <WorkoutButton
                  label={busy ? 'Đang xác nhận...' : 'Xác nhận ghi vào nhật ký'}
                  disabled={busy || editorDirty}
                  onPress={() => handleConfirm()}
                />
              </View>
            </View>
          ) : null}
        </WorkoutCard>
      ) : null}

      <View style={{ height: 40 }} />
    </ScrollView>
  );
}
