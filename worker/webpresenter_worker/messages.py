"""Messages exchanged with the server through RabbitMQ (plain JSON, see README.md)."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

JOBS = "webpresenter.jobs"
HEAVY_JOBS = "webpresenter.jobs.heavy"  # separation and recognition: GPU workers first
EVENTS = "webpresenter.job-events"
CONTROL = "webpresenter.job-control"


@dataclass
class JobRequest:
    job_id: str
    attempt: int
    type: str
    params: dict[str, Any] = field(default_factory=dict)
    input_url: str | None = None
    output_url: str = ""
    outputs: dict[str, str] = field(default_factory=dict)  # several results: name -> presigned PUT

    @staticmethod
    def parse(data: dict[str, Any]) -> "JobRequest":
        return JobRequest(
            job_id=data["jobId"],
            attempt=int(data.get("attempt", 1)),
            type=data["type"],
            params=data.get("params") or {},
            input_url=data.get("inputUrl"),
            output_url=data["outputUrl"],
            outputs=data.get("outputs") or {},
        )


def event(job: JobRequest, status: str, progress: int | None = None, message: str | None = None,
          filename: str | None = None, title: str | None = None,
          files: dict[str, str] | None = None) -> dict[str, Any]:
    payload = {"jobId": job.job_id, "attempt": job.attempt, "status": status, "progress": progress,
               "message": message, "filename": filename, "title": title}
    if files is not None:
        payload["files"] = files
    return payload
