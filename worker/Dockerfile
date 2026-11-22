# Processing worker (YouTube import with yt-dlp, vocal separation with Demucs on CPU).
FROM python:3.12.12-slim-trixie

# ffmpeg: conversions and Demucs input; Node.js (20+, from trixie): JavaScript runtime yt-dlp needs for YouTube.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg nodejs ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# CPU build of PyTorch (no CUDA download); Demucs models are cached in /models (a volume).
RUN pip install --no-cache-dir torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/cpu
COPY pyproject.toml ./
COPY webpresenter_worker ./webpresenter_worker
RUN pip install --no-cache-dir ".[separation]"

ENV TORCH_HOME=/models WORK_DIR=/work PYTHONUNBUFFERED=1
RUN useradd --create-home --uid 10001 worker && mkdir -p /models /work && chown worker /models /work
USER worker
CMD ["webpresenter-worker"]
