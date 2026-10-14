from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class ActorContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    userId: UUID
    role: Literal["STUDENT", "TRAINER"]


class ExerciseContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    exerciseId: UUID
    exerciseName: str = Field(min_length=1, max_length=200)
    movementPattern: str | None = Field(default=None, max_length=100)
    equipmentRequired: str | None = Field(default=None, max_length=100)


class KnowledgeScopeContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal["ACTIVE"]
    allowedSourceIds: list[str] = Field(default_factory=list)


class F01InternalContextEnvelope(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requestId: UUID
    requestType: Literal["F01_GENERAL_KNOWLEDGE"]
    schemaVersion: Literal["1.0.0"]
    correlationId: UUID
    actor: ActorContext
    purpose: Literal["GENERAL_FITNESS_EXPLANATION"]
    allowedActions: list[Literal["READ_INFORMATION"]] = Field(min_length=1, max_length=1)
    asOf: str
    question: str = Field(min_length=5, max_length=1000)
    language: Literal["vi", "en"]
    exerciseContext: ExerciseContext | None = None
    knowledgeScope: KnowledgeScopeContext


class LocatorDto(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sourcePath: str = Field(min_length=1)
    heading: str | None = None
    pageRange: list[int] | None = Field(default=None, min_length=2, max_length=2)
    sectionIndex: int | None = Field(default=None, ge=0)


class CitationDto(BaseModel):
    model_config = ConfigDict(extra="forbid")

    documentId: str = Field(pattern=r"^[A-Z0-9-]+-VI-[0-9]{2}$")
    version: str = Field(pattern=r"^[0-9]+\.[0-9]+\.[0-9]+$")
    sourceId: str = Field(pattern=r"^[A-Z0-9-]+$")
    locator: LocatorDto
    quoteOrExcerpt: str | None = Field(default=None, max_length=1000)


class UsageDto(BaseModel):
    model_config = ConfigDict(extra="forbid")

    promptTokens: int = Field(ge=0)
    completionTokens: int = Field(ge=0)
    totalTokens: int = Field(ge=0)
    latencyMs: int = Field(ge=0)


class ErrorDto(BaseModel):
    model_config = ConfigDict(extra="forbid")

    code: str = Field(min_length=1)
    message: str = Field(min_length=1)


class GenerationMetadataDto(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schemaVersion: str = Field(min_length=1)
    promptVersion: str = Field(min_length=1)
    ruleVersion: str = Field(min_length=1)
    provider: str = Field(min_length=1)
    model: str = Field(min_length=1)
    embeddingModelSpace: str | None = None
    indexVersion: str | None = None


class F01KnowledgeQueryResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    runId: UUID
    correlationId: UUID | None = None
    runStatus: Literal["SUCCEEDED", "FAILED", "REJECTED_BY_VALIDATOR"]
    resultType: Literal["INFORMATION", "INSUFFICIENT_DATA", "BLOCKED"] | None = None
    reasonCode: Literal[
        "NONE",
        "MISSING_REQUIRED_CONTEXT",
        "NO_RELEVANT_EVIDENCE",
        "RULE_BLOCKED",
        "VALIDATION_FAILED",
        "MODEL_ERROR",
        "PROVIDER_ERROR",
        "TIMEOUT",
        "PROCESSING_ERROR",
    ] | None = None
    answer: str | None = Field(default=None, min_length=1, max_length=5000)
    citations: list[CitationDto] | None = None
    limitations: list[str] | None = None
    missingData: list[str] | None = None
    asOf: str
    usage: UsageDto | None = None
    error: ErrorDto | None = None
    generationMetadata: GenerationMetadataDto | None = None
