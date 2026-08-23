from __future__ import annotations

from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field

DocumentRole = Literal["assignment", "reference", "project-description", "other"]


class DocumentClassification(BaseModel):
    """문서의 역할을 구분해 요구사항 추출 범위를 고정한다."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    file_id: UUID = Field(alias="fileId")
    role: DocumentRole
    rationale: str = Field(min_length=1)


class Requirement(BaseModel):
    """과제 문서에서 추출한 검증 가능한 요구사항."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: str = Field(pattern=r"^req-[A-Za-z0-9_-]{1,60}$")
    description: str = Field(min_length=1)
    evidence_file_ids: list[UUID] = Field(default_factory=list, alias="evidenceFileIds")
    acceptance_criteria: list[str] = Field(min_length=1, alias="acceptanceCriteria")


class RequirementAnalysis(BaseModel):
    """과제 문서 분석 결과. 문서가 없으면 requirements는 빈 배열이다."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    summary: str = Field(min_length=1)
    document_roles: list[DocumentClassification] = Field(alias="documentRoles")
    requirements: list[Requirement] = Field(default_factory=list)


class SourceFinding(BaseModel):
    """소스 코드·설정 파일에서 확인한 구현 근거."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    title: str = Field(min_length=1)
    description: str = Field(min_length=1)
    implementation_evidence: list[str] = Field(min_length=1, alias="implementationEvidence")
    evidence_file_ids: list[UUID] = Field(default_factory=list, alias="evidenceFileIds")


class SourceAnalysis(BaseModel):
    """소스 코드와 설정 파일의 구조·기능 분석 결과."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    summary: str = Field(min_length=1)
    findings: list[SourceFinding] = Field(default_factory=list)


class ImageObservation(BaseModel):
    """한 스크린샷에서 추출한 실행 결과 설명."""

    model_config = ConfigDict(extra="forbid")

    alt: str = Field(min_length=1)
    caption: str = Field(min_length=1)
    findings: list[str] = Field(default_factory=list)


class ImageAnalysis(BaseModel):
    """ImageObservation에 호출 입력의 fileId를 결합한 내부 분석 결과."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    file_id: UUID = Field(alias="fileId")
    observation: ImageObservation


class ReportPlanSection(BaseModel):
    """최종 문서 생성 전에 확정하는 섹션 계획."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
    title: str = Field(min_length=1)
    purpose: str = Field(min_length=1)
    evidence_file_ids: list[UUID] = Field(default_factory=list, alias="evidenceFileIds")
    image_file_ids: list[UUID] = Field(default_factory=list, alias="imageFileIds")


class ReportPlan(BaseModel):
    """ReportDocument를 만들기 위한 사실 기반 섹션 구성."""

    model_config = ConfigDict(extra="forbid")

    sections: list[ReportPlanSection] = Field(min_length=1)
