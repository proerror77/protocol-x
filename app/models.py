from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ObjectRef(StrictModel):
    ontology_id: str = Field(min_length=1, max_length=120)
    object_type: str = Field(min_length=1, max_length=120)
    id: str = Field(min_length=1, max_length=120)


class MessageInput(StrictModel):
    text: str = Field(min_length=1, max_length=4000)
    subject_ref: ObjectRef | None = None

    @field_validator("text")
    @classmethod
    def nonempty(cls, value):
        value = value.strip()
        if not value:
            raise ValueError("消息不能为空")
        return value


class ProposalInput(StrictModel):
    message_id: str = Field(min_length=1, max_length=120)


class ActionParameters(StrictModel):
    replacement_ref: ObjectRef | None = None
    progress_index: int | None = Field(default=None, ge=0, strict=True)
    pickup_at: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00$")


class ActionInput(StrictModel):
    action_type: Literal["acknowledge", "replace_resource", "confirm_progress", "reschedule_pickup", "decline_response"]
    target_ref: ObjectRef
    expected_version: int = Field(ge=1)
    idempotency_key: str = Field(min_length=8, max_length=128)
    parameters: ActionParameters = Field(default_factory=ActionParameters)
    proposal_id: str | None = Field(default=None, min_length=1, max_length=120)
