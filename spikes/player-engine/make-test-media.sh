#!/bin/sh
# Generates synthetic test media for the spike into ./media (requires ffmpeg).
set -e
dir="${1:-media}"
mkdir -p "$dir"
ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1920x1080:rate=30" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 20 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 128k "$dir/video.mp4"
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=330:sample_rate=48000" -t 15 -c:a aac -b:a 192k "$dir/audio.m4a"
ffmpeg -loglevel error -y -f lavfi -i "mandelbrot=size=1920x1080" -frames:v 1 "$dir/image.jpg"
echo "test media written to $dir"
