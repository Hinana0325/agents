"""Shared intent envelope for all nodes (phone, watch, PC, earphones, cloud).

Mirrors protocol/envelope.schema.json, which is authoritative, and
protocol/AgentEnvelope.kt. Standard library only.

Agents exchange only these envelopes, never free-form execution
instructions. ``validate_envelope`` rejects unknown intent types, unknown
parameter keys, missing required parameters, bad tiers, bad statuses and
bad timestamps. A sender cannot lower its own confirmation tier: the
receiving agent applies its own tier policy on top of the tier field.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

INTENT_TYPES = (
    "OPEN_APP",
    "SET_ALARM",
    "CREATE_CALENDAR_EVENT",
    "SET_TIMER",
    "WEB_SEARCH",
    "SAVE_NOTE",
    "CHAT_ONLY",
    "HANDOFF_TASK",
    "VOICE_COMMAND",
)

TASK_STATUSES = ("pending", "confirmed", "executing", "completed", "cancelled")

# Mirrors the phone MVP IntentValidator for the original seven types.
# HANDOFF_TASK and VOICE_COMMAND are cross-device only: they carry
# routing/transcript data and must be converted to a concrete intent
# (and confirmed) by the receiving agent before execution.
ALLOWED_PARAMS = {
    "OPEN_APP": {"appName"},
    "SET_ALARM": {"hour", "minute", "label"},
    "CREATE_CALENDAR_EVENT": {
        "title", "date", "startTime", "durationMinutes", "description", "location",
    },
    "SET_TIMER": {"seconds"},
    "WEB_SEARCH": {"query"},
    "SAVE_NOTE": {"content", "title"},
    "CHAT_ONLY": set(),
    "HANDOFF_TASK": {"target_device_id", "original_task_id", "summary"},
    "VOICE_COMMAND": {"transcript"},
}

REQUIRED_PARAMS = {
    "OPEN_APP": {"appName"},
    "SET_ALARM": {"hour", "minute"},
    "CREATE_CALENDAR_EVENT": {"title", "date", "startTime"},
    "SET_TIMER": {"seconds"},
    "WEB_SEARCH": {"query"},
    "SAVE_NOTE": {"content"},
    "CHAT_ONLY": set(),
    "HANDOFF_TASK": {"target_device_id", "original_task_id"},
    "VOICE_COMMAND": {"transcript"},
}

_ISO_UTC = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")


@dataclass
class AgentEnvelope:
    task_id: str
    device_id: str
    agent_id: str
    intent_type: str
    params: dict
    confirmation_tier: int
    status: str
    created_at: str
    target_device_id: str | None = None

    def to_dict(self) -> dict:
        data = {
            "task_id": self.task_id,
            "device_id": self.device_id,
            "agent_id": self.agent_id,
            "intent_type": self.intent_type,
            "params": dict(self.params),
            "confirmation_tier": self.confirmation_tier,
            "status": self.status,
            "created_at": self.created_at,
        }
        if self.target_device_id is not None:
            data["target_device_id"] = self.target_device_id
        return data


def validate_envelope(data: dict) -> list[str]:
    """Return a list of validation errors; empty means valid. Never raises
    on bad input: unknown types/keys are reported, not guessed."""
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["envelope must be an object"]
    for key in (
        "task_id", "device_id", "agent_id", "intent_type",
        "params", "confirmation_tier", "status", "created_at",
    ):
        if key not in data:
            errors.append(f"missing required field: {key}")
    if errors:
        return errors
    if not isinstance(data["task_id"], str) or not data["task_id"].strip():
        errors.append("task_id must not be blank")
    if not isinstance(data["device_id"], str) or not data["device_id"].strip():
        errors.append("device_id must not be blank")
    if not isinstance(data["agent_id"], str) or not data["agent_id"].strip():
        errors.append("agent_id must not be blank")
    intent = data["intent_type"]
    if intent not in INTENT_TYPES:
        errors.append(f"unknown intent_type: {intent!r}")
        return errors
    params = data["params"]
    if not isinstance(params, dict):
        errors.append("params must be an object")
        return errors
    unknown = set(params) - ALLOWED_PARAMS[intent]
    if unknown:
        errors.append(f"unknown params for {intent}: {sorted(unknown)}")
    missing = REQUIRED_PARAMS[intent] - set(params)
    if missing:
        errors.append(f"missing required params for {intent}: {sorted(missing)}")
    if intent == "HANDOFF_TASK" and not data.get("target_device_id"):
        errors.append("HANDOFF_TASK requires target_device_id")
    if data["confirmation_tier"] not in (1, 2, 3):
        errors.append("confirmation_tier must be 1, 2 or 3")
    if data["status"] not in TASK_STATUSES:
        errors.append(f"unknown status: {data['status']!r}")
    if not isinstance(data["created_at"], str) or not _ISO_UTC.match(data["created_at"]):
        errors.append("created_at must be an ISO 8601 UTC timestamp")
    return errors


def envelope_from_dict(data: dict) -> AgentEnvelope:
    """Build an envelope or raise ValueError with the validation errors."""
    errors = validate_envelope(data)
    if errors:
        raise ValueError("; ".join(errors))
    return AgentEnvelope(
        task_id=data["task_id"],
        device_id=data["device_id"],
        agent_id=data["agent_id"],
        intent_type=data["intent_type"],
        params=dict(data["params"]),
        confirmation_tier=data["confirmation_tier"],
        status=data["status"],
        created_at=data["created_at"],
        target_device_id=data.get("target_device_id"),
    )


def now_utc() -> str:
    """UTC timestamp in the envelope format."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
