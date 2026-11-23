# Choir-voice model evaluation (worker/tools/evaluate_voices.py) on the GPU worker image, with the
# candidate models' extra dependencies. The models' repositories and checkpoints are mounted.
FROM webpresenter-worker-gpu:latest
USER root
# diffq (an ACappellaSet dependency) builds a C extension.
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir librosa==0.11.0 loguru==0.7.3 PyYAML==6.0.2 omegaconf==2.3.0 einops==0.8.1 \
        openunmix==1.3.0 diffq==0.2.4 scipy==1.15.3
USER worker
ENTRYPOINT ["python", "/tools/evaluate_voices.py"]
