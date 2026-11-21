# M0 spike: MediaPlayer playback engine

Throwaway prototype to choose the MediaPlayer playback engine (see `docs/mvp.md`, milestone M0).

A fullscreen JavaFX window plays video, audio, images, text slides, and a rest screen with fade
transitions, and composites a JavaFX text overlay (lyrics-like) on top of the video.

## Approach

Both candidate engines are integrated the same way: the engine decodes and converts each video
frame on the CPU into a native buffer that backs a JavaFX `PixelBuffer`. JavaFX then composes the
video with overlays, slides, and transitions, so all presentation logic lives in JavaFX
regardless of engine. This is the only approach that allows JavaFX nodes on top of video on all
platforms (embedding a native window via `wid` does not allow JavaFX overlays and does not work
on Wayland).

- `VideoEngine`: engine abstraction used by the spike app.
- `mpv/Mpv`: libmpv bindings written with the Java 25 FFM API (no JNA, no generated code).
- `mpv/MpvEngine`: libmpv software render API (`MPV_RENDER_API_TYPE_SW`), triple-buffered
  `PixelBuffer`, render thread signaled by mpv's update callback.
- `SpikeApp`: presentation window, transitions, metrics, scripted `--auto` mode.

## Running

```sh
./make-test-media.sh                       # synthetic 1080p30 video, audio, image into ./media
./gradlew run -Pargs="--windowed --media-dir $PWD/media"
./gradlew run -Pargs="--auto --windowed --media-dir $PWD/media"   # scripted run + metrics
```

Keys: `1` video, `2` image, `3` text slide, `4` audio, `R` rest screen, `O` toggle overlay,
`D` toggle stats, `Esc` quit. Options: `--screen N`, `--render WxH`, `--engine mpv`.

System properties (via `JAVA_OPTS` on the installed distribution): `mpv.hwdec` (default `no`),
`mpv.swFormat` (default `bgra`), `mpv.blockForTargetTime` (default `true`), `mpv.library`,
`spike.snapshotDir` (writes scene snapshots during `--auto`).

## Results: mpv (Linux)

Environment: Gentoo Linux, Wayland session (JavaFX through XWayland, Prism ES2 pipeline),
Intel i7-8650U (laptop, 4C/8T), Intel UHD 620, Java 25, JavaFX 27.0.1, libmpv 0.41.

| Criterion | Result |
|---|---|
| Video in JavaFX window | ✅ 1080p30 H.264, 30 frames/s steady, rendered at 1920×1080. |
| JavaFX overlay over video | ✅ Text with drop shadow composited correctly (verified by scene snapshot). |
| Fade transitions | ✅ Crossfades between video, image, slide, audio, rest; audio volume ramps with the fade. |
| Audio-only playback | ✅ |
| Cost | Frame conversion into the buffer: **~1.3 ms/frame** at 1080p. Whole process during 1080p30 playback with software decoding: **~30–40% of one core**. |
| Color / alpha | ✅ `bgra` output format yields opaque pixels directly; no alpha fix-up pass needed. |
| Shutdown | ✅ 4 ms (after fixing the shutdown order, see below). |
| Windows | ⏳ Not tested yet. |
| Packaging | libmpv must be bundled (`libmpv-2.dll` on Windows; system package or bundled `.so` on Linux). |

### Pitfalls found (and fixed in the spike)

1. **Locale**: libmpv refuses to initialize unless `LC_NUMERIC` is `C`. GTK/JavaFX set the
   process locale from the environment, so `setlocale(LC_NUMERIC, "C")` must be called before
   `mpv_create`.
2. **Shutdown order**: `mpv_terminate_destroy` deadlocks if another thread is inside
   `mpv_wait_event` on the same handle. Correct order: stop render thread → free render
   context → `quit` command → wait for the event loop to see `SHUTDOWN` → destroy.
3. **Hardware decoding**: `hwdec=auto-copy` picked Vulkan, failed, and stalled the video
   pipeline. Default to `hwdec=no` and enable a specific, tested hwdec per host.
4. **Render timing**: `mpv_render_context_render` blocks until the frame's display time
   (A/V sync). That is desirable, but timing measurements must disable it
   (`mpv.blockForTargetTime=false`) to see the real cost.
5. **Overlay effects**: animating the opacity of a node with `DropShadow` over video doubled
   CPU usage; `setCache(true)` on the overlay node fixes it.
6. **JavaFX on the classpath**: the main class must not extend `Application` (a separate
   `Launcher` is used).

## Results: VLC

⏳ Pending: libVLC is not installed on the test machine.

## Preliminary conclusion

mpv through the software render API meets every criterion tested on Linux with a comfortable
margin, the FFM bindings are small (one file), and the integration pattern keeps all
presentation logic in JavaFX. Pending before the final decision: the VLCJ comparison and a
Windows run.
