# Processing worker (YouTube import with yt-dlp, separation with Demucs, lyrics with Whisper).
# CPU by default; for an NVIDIA GPU build with --build-arg TORCH_INDEX=cu124 (see deploy/gpu-worker).
FROM denoland/deno:bin-2.4.5 AS deno

FROM python:3.12.12-slim-trixie
ARG TORCH_INDEX=cpu

# ffmpeg: conversions and Demucs input. Tesseract (Portuguese data): the text read from videos for lyrics.
# Deno: the JavaScript runtime yt-dlp uses for YouTube's challenges (Debian's Node.js is too old for it).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates tesseract-ocr tesseract-ocr-por \
    && rm -rf /var/lib/apt/lists/*
COPY --from=deno /deno /usr/local/bin/deno

WORKDIR /app
# PyTorch for the CPU (no CUDA download) or for CUDA 12.4 (brings the CUDA and cuDNN libraries, also
# used by Whisper's CTranslate2; 2.5 still supports older GPUs such as Pascal).
RUN pip install --no-cache-dir torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/${TORCH_INDEX}
COPY pyproject.toml ./
COPY webpresenter_worker ./webpresenter_worker
RUN pip install --no-cache-dir ".[separation,voices,lyrics]"
# ACappellaSet (MIT), pinned: the choir-voice model's code. Its weights are downloaded on first use.
ARG ACAPPELLASET_COMMIT=856a4222ed81648ce14bf832af83bbb8fb2ed056
ADD https://github.com/pting126/ACappellaSet/archive/${ACAPPELLASET_COMMIT}.tar.gz /tmp/acappellaset.tar.gz
RUN mkdir -p /opt/acappellaset && tar -xzf /tmp/acappellaset.tar.gz -C /opt/acappellaset --strip-components=1 \
    && rm /tmp/acappellaset.tar.gz

# Models (Demucs, Whisper) are downloaded on first use into the /models volume.
ENV TORCH_HOME=/models HF_HOME=/models/huggingface WORK_DIR=/work PYTHONUNBUFFERED=1
# CTranslate2 finds cuBLAS and cuDNN in the wheels PyTorch installed (no effect on CPU builds).
ENV LD_LIBRARY_PATH=/usr/local/lib/python3.12/site-packages/nvidia/cublas/lib:/usr/local/lib/python3.12/site-packages/nvidia/cudnn/lib
RUN useradd --create-home --uid 10001 worker && mkdir -p /models /work && chown worker /models /work
USER worker
CMD ["webpresenter-worker"]
