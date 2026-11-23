# Processing worker (YouTube import with yt-dlp, separation with Demucs, lyrics with Whisper).
# CPU by default; for an NVIDIA GPU build with --build-arg TORCH_INDEX=cu124 (see deploy/gpu-worker).
FROM denoland/deno:bin-2.4.5 AS deno

FROM python:3.12.12-slim-trixie
ARG TORCH_INDEX=cpu

# ffmpeg: conversions and Demucs input. Deno: the JavaScript runtime yt-dlp uses for YouTube's
# challenges (Debian's Node.js is too old for it).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=deno /deno /usr/local/bin/deno

WORKDIR /app
# PyTorch for the CPU (no CUDA download) or for CUDA 12.4 (brings the CUDA and cuDNN libraries, also
# used by Whisper's CTranslate2; 2.5 still supports older GPUs such as Pascal).
RUN pip install --no-cache-dir torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/${TORCH_INDEX}
COPY pyproject.toml ./
COPY webpresenter_worker ./webpresenter_worker
RUN pip install --no-cache-dir ".[separation,lyrics]"

# Models (Demucs, Whisper) are downloaded on first use into the /models volume.
ENV TORCH_HOME=/models HF_HOME=/models/huggingface WORK_DIR=/work PYTHONUNBUFFERED=1
# CTranslate2 finds cuBLAS and cuDNN in the wheels PyTorch installed (no effect on CPU builds).
ENV LD_LIBRARY_PATH=/usr/local/lib/python3.12/site-packages/nvidia/cublas/lib:/usr/local/lib/python3.12/site-packages/nvidia/cudnn/lib
RUN useradd --create-home --uid 10001 worker && mkdir -p /models /work && chown worker /models /work
USER worker
CMD ["webpresenter-worker"]
