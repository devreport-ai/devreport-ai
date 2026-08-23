import json

import pytest
from google.genai import types

from app.clients.response_schema import response_schema_for
from app.schemas.pipeline import (
    ImageObservation,
    ReportPlan,
    RequirementAnalysis,
    SourceAnalysis,
)

ANALYSIS_MODELS = [RequirementAnalysis, SourceAnalysis, ImageObservation, ReportPlan]


@pytest.mark.parametrize("model", ANALYSIS_MODELS)
def test_generated_schema_is_accepted_by_the_sdk_schema_type(model: type):
    schema = types.Schema.model_validate(response_schema_for(model))

    assert schema.type == types.Type.OBJECT
    assert schema.properties


@pytest.mark.parametrize("model", ANALYSIS_MODELS)
def test_drops_keywords_gemini_structured_output_rejects(model: type):
    # pattern·minLength·format=uuid를 그대로 보내면 400으로 거절된다.
    serialized = json.dumps(response_schema_for(model))

    assert "pattern" not in serialized
    assert "minLength" not in serialized
    assert "uuid" not in serialized
    assert "$ref" not in serialized


def test_keeps_field_names_and_nested_models():
    schema = response_schema_for(RequirementAnalysis)

    requirement = schema["properties"]["requirements"]["items"]
    assert list(requirement["properties"]) == [
        "id",
        "description",
        "evidenceFileIds",
        "acceptanceCriteria",
    ]
    assert requirement["properties"]["evidenceFileIds"]["type"] == "array"


def test_orders_properties_for_deterministic_output():
    schema = response_schema_for(ImageObservation)

    assert schema["propertyOrdering"] == ["alt", "caption", "findings"]
