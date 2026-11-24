/** F06 Spring wire types. Required nullable fields must be sent explicitly as null.
 * Revisions use JSON int64 and amounts use JSON decimal numbers, as in Nutrition.
 * JS numbers cannot represent every int64 or 18-digit decimal exactly. Do not
 * calculate nutrients or reconstruct retry bodies from rounded display values.
 */
export type FoodAnalysisMealType = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK' | 'OTHER';
export type FoodAnalysisQuantityBasis = 'GRAM' | 'SERVING' | 'MILLILITRE';
export type FoodAnalysisStatus = 'PROCESSING' | 'READY' | 'BLOCKED' | 'FAILED';
export type FoodAnalysisConfirmationStatus = 'DRAFT' | 'PARTIALLY_CONFIRMED' | 'CONFIRMED';

export interface FoodAnalysisContext {
  day: string;
  timezone: string;
  consumedAt: string;
  mealType: FoodAnalysisMealType;
}

export interface FoodTextAnalysisRequest extends FoodAnalysisContext {
  text: string;
  commandKey: string;
}

export interface FoodAnalysisCandidate {
  foodId: string;
  revisionId: string;
  name: string;
}

export interface FoodAnalysisItem {
  itemId: string;
  itemRevision: number;
  detectedName: string | null;
  candidates: FoodAnalysisCandidate[];
  selectedFoodId: string | null;
  selectedRevisionId: string | null;
  quantity: number | null;
  quantityBasis: FoodAnalysisQuantityBasis | null;
  portionId: string | null;
  confidence: number | null;
  missingData: string[];
  limitations: string[];
  confirmedFoodLogItemId: string | null;
}

export interface FoodAnalysisDraft extends FoodAnalysisContext {
  draftId: string;
  studentId: string;
  commandKey: string;
  resultType: 'ESTIMATE';
  analysisStatus: FoodAnalysisStatus;
  confirmationStatus: FoodAnalysisConfirmationStatus;
  draftRevision: number;
  runId: string | null;
  expiresAt: string | null;
  items: FoodAnalysisItem[];
  latestPreview: FoodAnalysisPreview | null;
  createdAt: string;
  updatedAt: string;
}

export interface FoodAnalysisItemCorrectionRequest {
  commandKey: string;
  expectedDraftRevision: number;
  expectedItemRevision: number;
  selectedFoodId: string | null;
  selectedRevisionId: string | null;
  quantity: number | null;
  quantityBasis: FoodAnalysisQuantityBasis | null;
  portionId: string | null;
}

export interface FoodAnalysisSelectedItem {
  itemId: string;
  itemRevision: number;
  foodId: string;
  catalogRevisionId: string;
  quantity: number;
  quantityBasis: FoodAnalysisQuantityBasis;
  portionId: string | null;
}

export interface FoodAnalysisPreviewRequest extends FoodAnalysisContext {
  commandKey: string;
  expectedDraftRevision: number;
  selectedItems: FoodAnalysisSelectedItem[];
}

export interface FoodAnalysisNutrients {
  energyKcal: number | null;
  proteinG: number | null;
  carbohydrateG: number | null;
  fatG: number | null;
}

export interface FoodAnalysisPreviewItem {
  selection: FoodAnalysisSelectedItem;
  nutrients: FoodAnalysisNutrients;
  catalogSource: string;
}

export interface FoodAnalysisPreview {
  previewId: string;
  previewRevision: number;
  draftId: string;
  draftRevision: number;
  items: FoodAnalysisPreviewItem[];
  totalNutrients: FoodAnalysisNutrients;
  createdAt: string;
}

export interface FoodAnalysisConfirmRequest extends FoodAnalysisPreviewRequest {
  previewId: string;
  expectedPreviewRevision: number;
}

export interface FoodAnalysisConfirmedItem extends FoodAnalysisSelectedItem {
  foodLogItemId: string;
  mealId: string;
  nutrients: FoodAnalysisNutrients;
  catalogSource: string;
}

export interface FoodAnalysisConfirmationReceipt {
  receiptId: string;
  commandKey: string;
  draftId: string;
  committedAt: string;
  replayed: boolean;
  confirmedItems: FoodAnalysisConfirmedItem[];
  resultingDraftRevision: number;
  remainingDraftItemIds: string[];
}
