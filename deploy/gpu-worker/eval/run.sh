#!/bin/sh
# Downloads the candidate models (pinned) and evaluates them on a vocals track:
#   EXPECTED=soprano,alto ./run.sh /path/to/vocals.m4a      (the voices it really has; optional)
#   MEASURE_ONLY=1 EXPECTED=… ./run.sh …                    (measure results again, without running the models)
# Results (report.json and a file per voice) in ./results/<input name>/.
set -eu
cd "$(dirname "$0")"
input=$(realpath "$1")
mkdir -p models results
[ -d models/SepACap ] || git clone -q https://github.com/ETH-DISCO/SepACap models/SepACap
[ -d models/ACappellaSet ] || git clone -q https://github.com/pting126/ACappellaSet models/ACappellaSet
hf=https://huggingface.co
[ -f models/SepACap.pth ] || curl -sSfL --http1.1 --retry 5 --retry-all-errors -C - -o models/SepACap.pth $hf/Tino3141/sepacap/resolve/main/SepACap.pth
[ -f models/modelMusicSep.yaml ] || curl -sSfL --http1.1 --retry 5 --retry-all-errors -C - -o models/modelMusicSep.yaml $hf/Tino3141/sepacap/resolve/main/modelMusicSep.yaml
[ -f models/Fixed_6stem_htdemucs_SKnJC.pt ] || curl -sSfL --http1.1 --retry 5 --retry-all-errors -C - -o models/Fixed_6stem_htdemucs_SKnJC.pt \
    $hf/AcappellaSet/ACappellaSet-Models/resolve/main/fixed/SKnJC/legacy/Fixed_6stem_htdemucs_SKnJC.pt
docker build -q -t webpresenter-voices-eval . >/dev/null
name=$(basename "$input" | sed 's/\.[^.]*$//')
[ -n "${MEASURE_ONLY:-}" ] || rm -rf "results/$name"
docker run --rm --user "$(id -u):$(id -g)" --runtime=nvidia -e NVIDIA_VISIBLE_DEVICES=all -e NVIDIA_DRIVER_CAPABILITIES=compute,utility \
    -v "$PWD/../../../worker/tools:/tools:ro" -v "$PWD/models:/models:ro" -v "$PWD/results:/results" \
    -v "$input:/input/$(basename "$input"):ro" -e HOME=/tmp -e NUMBA_CACHE_DIR=/tmp \
    webpresenter-voices-eval --input "/input/$(basename "$input")" --out "/results/$name" \
    --sepacap-repo /models/SepACap --sepacap-checkpoint /models/SepACap.pth --sepacap-config /models/modelMusicSep.yaml \
    --acappellaset-repo /models/ACappellaSet --acappellaset-checkpoint /models/Fixed_6stem_htdemucs_SKnJC.pt \
    ${EXPECTED:+--expected "$EXPECTED"} ${MEASURE_ONLY:+--measure-only} ${SEPACAP_DEVICE:+--sepacap-device "$SEPACAP_DEVICE"}
