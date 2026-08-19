from __future__ import annotations

from typing import Any

from pydantic import BaseModel

# Gemini structured output은 OpenAPI 스키마의 일부만 받는다. pattern·minLength 같은
# 키워드를 그대로 보내면 400으로 거절되므로, Pydantic 스키마에서 지원 키만 남긴다.
SUPPORTED_KEYS = frozenset(
    {
        "type",
        "description",
        "enum",
        "items",
        "properties",
        "required",
        "propertyOrdering",
        "nullable",
        "anyOf",
        "minItems",
        "maxItems",
    }
)
SUPPORTED_STRING_FORMATS = frozenset({"date-time", "enum"})


def response_schema_for(model: type[BaseModel]) -> dict[str, Any]:
    """Pydantic 모델을 Gemini가 받는 응답 스키마로 변환한다."""
    schema = model.model_json_schema(by_alias=True)
    definitions = schema.get("$defs", {})
    return convert(schema, definitions)


def convert(node: Any, definitions: dict[str, Any]) -> Any:
    if isinstance(node, list):
        return [convert(item, definitions) for item in node]
    if not isinstance(node, dict):
        return node

    node = resolve(node, definitions)
    nullable, variants = split_nullable(node.pop("anyOf", None))
    if variants:
        # `str | None`처럼 null과 결합된 경우 nullable 플래그로 접는다.
        node = {**convert(variants[0], definitions), **node} if len(variants) == 1 else node

    converted: dict[str, Any] = {}
    for key, value in node.items():
        if key == "format":
            if value in SUPPORTED_STRING_FORMATS:
                converted[key] = value
            continue
        if key not in SUPPORTED_KEYS:
            continue
        if key == "properties":
            # properties의 키는 스키마 키워드가 아니라 필드 이름이므로 그대로 둔다.
            converted[key] = {name: convert(field, definitions) for name, field in value.items()}
            continue
        if key in {"required", "enum"}:
            converted[key] = list(value)
            continue
        converted[key] = convert(value, definitions)

    if nullable:
        converted["nullable"] = True
    if "properties" in converted:
        converted.setdefault("propertyOrdering", list(converted["properties"]))
    return converted


def resolve(node: dict[str, Any], definitions: dict[str, Any]) -> dict[str, Any]:
    reference = node.get("$ref")
    if not isinstance(reference, str) or not reference.startswith("#/$defs/"):
        return dict(node)
    resolved = dict(definitions.get(reference.removeprefix("#/$defs/"), {}))
    resolved.update({key: value for key, value in node.items() if key != "$ref"})
    return resolved


def split_nullable(any_of: Any) -> tuple[bool, list[Any]]:
    if not isinstance(any_of, list):
        return False, []
    variants = [item for item in any_of if not is_null_type(item)]
    return len(variants) != len(any_of), variants


def is_null_type(node: Any) -> bool:
    return isinstance(node, dict) and node.get("type") == "null"
