from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class F06FoodTextEstimateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=4000)

    @field_validator("text")
    @classmethod
    def nonblank_text(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("text must contain non-whitespace characters")
        return value


class F06FoodTextItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    detectedName: str = Field(min_length=1, max_length=200)
    candidateNames: list[Annotated[str, Field(min_length=1, max_length=200)]] = Field(default_factory=list)
    quantity: float | None = Field(default=None, gt=0)
    unitText: str | None = Field(default=None, min_length=1, max_length=80)
    confidence: float | None = Field(default=None, ge=0, le=1)
    missingData: list[str] = Field(default_factory=list)
    limitations: list[str] = Field(default_factory=list)

    @field_validator("candidateNames", "missingData")
    @classmethod
    def unique_strings(cls, values: list[str]) -> list[str]:
        if len(values) != len(set(values)):
            raise ValueError("values must be unique")
        return values


class F06FoodTextEstimateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    resultType: Literal["ESTIMATE"] = "ESTIMATE"
    items: list[F06FoodTextItem] = Field(default_factory=list, max_length=20)
    missingData: list[str] = Field(default_factory=list)
    limitations: list[str] = Field(default_factory=list)

    @field_validator("missingData")
    @classmethod
    def unique_missing_data(cls, values: list[str]) -> list[str]:
        if len(values) != len(set(values)):
            raise ValueError("values must be unique")
        return values
