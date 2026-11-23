"""
Worker loop: takes one job at a time from RabbitMQ, runs it in a thread, reports progress (also
every 30 s as a heartbeat), uploads the result through the presigned link and reports the outcome.

Environment:
  RABBITMQ_URL   amqp://user:password@host:5672/%2F (default: local development broker)
  WORK_DIR       scratch directory (default: system temporary directory)
  DEMUCS_MODEL   Demucs model (default htdemucs); DEMUCS_DEVICE cpu|cuda (default: cuda if available)
  HEAVY_JOBS_AFTER  seconds a heavy job (separation, recognition) must have waited before this worker
                 takes it (default 0). CPU workers next to a GPU worker use a delay, so the GPU worker
                 takes them first and the CPU one only when the GPU worker is away.
  JOB_KINDS      "all" (default), "light" or "heavy"
"""
from __future__ import annotations

import json
import logging
import os
import shutil
import signal
import tempfile
import threading
import time
from pathlib import Path

import pika

from . import jobs
from .messages import CONTROL, EVENTS, HEAVY_JOBS, JOBS, JobRequest, event

log = logging.getLogger("webpresenter.worker")
HEARTBEAT_SECONDS = 30


class Worker:
    def __init__(self, url: str, workdir: Path, heavy_after: float = 0, kinds: str = "all"):
        self.url = url
        self.workdir = workdir
        self.heavy_after = heavy_after
        self.queues = {"light": [JOBS], "heavy": [HEAVY_JOBS]}.get(kinds, [HEAVY_JOBS, JOBS])
        self.stopping = False

    def run(self) -> None:
        while not self.stopping:
            try:
                self._session()
            except pika.exceptions.AMQPError as e:
                if self.stopping:
                    break
                log.warning("RabbitMQ unavailable (%s); retrying in 5 s", e.__class__.__name__)
                time.sleep(5)

    def _session(self) -> None:
        connection = pika.BlockingConnection(pika.URLParameters(self.url))
        channel = connection.channel()
        channel.queue_declare(JOBS, durable=True)
        channel.queue_declare(HEAVY_JOBS, durable=True)
        channel.queue_declare(EVENTS, durable=True)
        channel.exchange_declare(CONTROL, exchange_type="fanout", durable=True)
        control = channel.queue_declare("", exclusive=True, auto_delete=True).method.queue
        channel.queue_bind(control, CONTROL)

        current: dict = {}

        def on_control(ch, method, properties, body):
            try:
                message = json.loads(body)
            except ValueError:
                return
            if message.get("action") == "CANCEL" and current.get("job") and current["job"].job_id == message.get("jobId"):
                log.info("cancelling job %s", message["jobId"])
                current["cancelled"].set()

        channel.basic_consume(control, on_control, auto_ack=True)

        def publish(payload: dict) -> None:
            channel.basic_publish("", EVENTS, json.dumps(payload).encode(),
                                  pika.BasicProperties(content_type="application/json", delivery_mode=2))

        log.info("worker ready, waiting for jobs (%s%s)", ", ".join(self.queues),
                 f"; heavy jobs after {self.heavy_after:.0f} s" if self.heavy_after and HEAVY_JOBS in self.queues else "")
        while not self.stopping:
            method = body = None
            for queue in self.queues:
                method, properties, body = channel.basic_get(queue, auto_ack=False)
                if method is None:
                    continue
                waited = time.time() - (properties.timestamp or 0)
                if queue == HEAVY_JOBS and waited < self.heavy_after:
                    # Left for a faster (GPU) worker for now; taken later if none does.
                    channel.basic_nack(method.delivery_tag, requeue=True)
                    method = None
                    continue
                break
            if method is None:
                connection.process_data_events(time_limit=2)
                continue
            # Acknowledged at once: long jobs would outlive RabbitMQ's delivery timeout. A worker that
            # dies mid-job stops reporting and the server marks the job failed (it can be retried).
            channel.basic_ack(method.delivery_tag)
            try:
                job = JobRequest.parse(json.loads(body))
            except (ValueError, KeyError) as e:
                log.warning("discarding malformed job: %s", e)
                continue
            self._process(job, connection, publish, current)

    def _process(self, job: JobRequest, connection, publish, current: dict) -> None:
        log.info("job %s (%s) attempt %s", job.job_id, job.type, job.attempt)
        cancelled = threading.Event()
        current.update(job=job, cancelled=cancelled)
        state = {"progress": 0, "message": "Iniciando", "dirty": True}
        lock = threading.Lock()

        def progress(value: int, message: str) -> None:
            with lock:
                if value != state["progress"] or message != state["message"]:
                    state.update(progress=value, message=message, dirty=True)

        outcome: dict = {}
        workdir = Path(tempfile.mkdtemp(prefix=f"job-{job.job_id}-", dir=self.workdir))

        def run() -> None:
            try:
                handler = jobs.HANDLERS.get(job.type)
                if handler is None:
                    raise jobs.JobFailed(f"Tipo de trabalho desconhecido: {job.type}")
                result = handler(job, workdir, jobs.Reporter(progress, cancelled))
                progress(95, "Enviando o resultado")
                if result.files is not None:
                    for name, path in result.files.items():
                        if name not in job.outputs:
                            raise jobs.JobFailed(f"Resultado inesperado: {name}")
                        jobs.upload(job.outputs[name], path)
                else:
                    jobs.upload(job.output_url, result.path)
                outcome["done"] = result
            except jobs.Cancelled:
                outcome["cancelled"] = True
            except jobs.JobFailed as e:
                outcome["failed"] = str(e)
            except Exception as e:  # unexpected: report it rather than dying
                log.exception("job %s failed", job.job_id)
                outcome["failed"] = f"Erro inesperado: {e}"

        thread = threading.Thread(target=run, name=f"job-{job.job_id}", daemon=True)
        thread.start()
        last_sent = 0.0
        try:
            while thread.is_alive():
                connection.process_data_events(time_limit=0.5)
                with lock:
                    elapsed = time.monotonic() - last_sent
                    due = (state["dirty"] and elapsed >= 1) or elapsed >= HEARTBEAT_SECONDS
                    snapshot = dict(state)
                    state["dirty"] = False if due else state["dirty"]
                if due and not cancelled.is_set():
                    publish(event(job, "RUNNING", snapshot["progress"], snapshot["message"]))
                    last_sent = time.monotonic()
            if "done" in outcome:
                result = outcome["done"]
                files = None if result.files is None else {n: p.name for n, p in result.files.items()}
                publish(event(job, "DONE", 100, "Concluído", result.filename, result.title, files))
                log.info("job %s done", job.job_id)
            elif "failed" in outcome:
                publish(event(job, "FAILED", None, outcome["failed"]))
                log.info("job %s failed: %s", job.job_id, outcome["failed"])
            else:
                log.info("job %s cancelled", job.job_id)  # the server already shows it cancelled
        finally:
            current.clear()
            shutil.rmtree(workdir, ignore_errors=True)


def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    url = os.environ.get("RABBITMQ_URL", "amqp://webpresenter:webpresenter@localhost:5672/%2F")
    workdir = Path(os.environ.get("WORK_DIR", tempfile.gettempdir()))
    workdir.mkdir(parents=True, exist_ok=True)
    worker = Worker(url, workdir, float(os.environ.get("HEAVY_JOBS_AFTER", "0")),
                    os.environ.get("JOB_KINDS", "all"))

    def stop(*_):
        worker.stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    worker.run()


if __name__ == "__main__":
    main()
