package webpresenter.player.engine.mpv;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import webpresenter.player.engine.VideoEngine;

/**
 * libmpv engine using the software render API: mpv decodes, scales and converts each frame on the
 * CPU into a native buffer that backs a JavaFX {@link PixelBuffer}. JavaFX composes overlays and
 * transitions on top of the resulting image.
 */
public final class MpvEngine implements VideoEngine {

    private static final System.Logger LOG = System.getLogger(MpvEngine.class.getName());

    private static final int BUFFERS = 3;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment handle;
    private final MemorySegment renderContext;
    private final MemorySegment updateCallbackStub;

    private final int width;
    private final int height;
    private final long stride;
    private final MemorySegment[] pixels = new MemorySegment[BUFFERS];
    private final PixelBuffer<ByteBuffer>[] pixelBuffers;
    private final WritableImage[] images = new WritableImage[BUFFERS];
    private final MemorySegment[] renderParams = new MemorySegment[BUFFERS];
    private volatile boolean fixAlpha;

    private final ImageView view = new ImageView();
    private final Semaphore frameSignal = new Semaphore(0);
    private final AtomicInteger displayed = new AtomicInteger(-1);
    private final AtomicInteger pending = new AtomicInteger(-1);
    private volatile boolean running = true;
    private volatile Runnable endListener = () -> {};

    private final Thread renderThread;
    private final Thread eventThread;

    // Stats, guarded by "this".
    private int statFrames;
    private double statTotalMs;
    private double statMaxMs;

    @SuppressWarnings("unchecked")
    public MpvEngine(int width, int height) {
        this.width = width;
        this.height = height;
        this.stride = alignUp(width * 4L, 64);
        this.pixelBuffers = (PixelBuffer<ByteBuffer>[]) new PixelBuffer<?>[BUFFERS];

        try {
            Mpv.forceNumericCLocale();
            handle = (MemorySegment) Mpv.create.invokeExact();
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("mpv_create returned NULL");
            }
            setOption("vo", "libmpv");
            setOption("hwdec", System.getProperty("mpv.hwdec", "no"));
            setOption("sws-fast", System.getProperty("mpv.swsFast", "yes"));
            setOption("idle", "yes");
            setOption("keep-open", "no");
            setOption("audio-display", "no");
            setOption("terminal", "yes");
            setOption("msg-level", System.getProperty("mpv.msgLevel", "all=warn"));
            Mpv.check((int) Mpv.initialize.invokeExact(handle), "mpv_initialize");

            // Render context: software API.
            MemorySegment createParams = arena.allocate(Mpv.RENDER_PARAM_SIZE * 2, 8);
            putParam(createParams, 0, Mpv.RENDER_PARAM_API_TYPE, arena.allocateFrom("sw"));
            putParam(createParams, 1, Mpv.RENDER_PARAM_INVALID, MemorySegment.NULL);
            MemorySegment ctxOut = arena.allocate(ADDRESS);
            Mpv.check((int) Mpv.renderContextCreate.invokeExact(ctxOut, handle, createParams),
                    "mpv_render_context_create");
            renderContext = ctxOut.get(ADDRESS, 0);

            MethodHandle onUpdate = MethodHandles.lookup()
                    .findVirtual(MpvEngine.class, "onUpdate", MethodType.methodType(void.class, MemorySegment.class))
                    .bindTo(this);
            updateCallbackStub = Mpv.linker().upcallStub(onUpdate, Mpv.UPDATE_CALLBACK, arena);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to initialize mpv", t);
        }

        String format = System.getProperty("mpv.swFormat", "bgra");
        fixAlpha = !format.equals("bgra");
        for (int i = 0; i < BUFFERS; i++) {
            pixels[i] = arena.allocate(stride * height, 64);
            ByteBuffer bb = pixels[i].asByteBuffer();
            pixelBuffers[i] = new PixelBuffer<>((int) (stride / 4), height, bb, PixelFormat.getByteBgraPreInstance());
            images[i] = new WritableImage(pixelBuffers[i]);
            renderParams[i] = buildRenderParams(pixels[i], format);
        }
        view.setViewport(new javafx.geometry.Rectangle2D(0, 0, width, height));
        view.setPreserveRatio(true);

        renderThread = Thread.ofPlatform().name("mpv-render").daemon().start(this::renderLoop);
        eventThread = Thread.ofPlatform().name("mpv-events").daemon().start(this::eventLoop);
        try {
            Mpv.renderContextSetUpdateCallback.invokeExact(renderContext, updateCallbackStub, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    @Override
    public String name() {
        return "mpv (libmpv sw render)";
    }

    @Override
    public Node view() {
        return view;
    }

    @Override
    public void play(Path media, boolean loop) {
        setProperty("loop-file", loop ? "inf" : "no");
        command("loadfile", media.toAbsolutePath().toString(), "replace");
    }

    @Override
    public void stop() {
        command("stop");
    }

    @Override
    public void setVolume(double volume) {
        setProperty("volume", String.format(java.util.Locale.ROOT, "%.1f", volume));
    }

    @Override
    public void onEndOfMedia(Runnable listener) {
        this.endListener = listener;
    }

    @Override
    public synchronized RenderStats drainStats() {
        RenderStats s = new RenderStats(statFrames, statFrames == 0 ? 0 : statTotalMs / statFrames, statMaxMs);
        statFrames = 0;
        statTotalMs = 0;
        statMaxMs = 0;
        return s;
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        frameSignal.release();
        long t0 = System.nanoTime();
        try {
            renderThread.join(2000);
            long t1 = System.nanoTime();
            Mpv.renderContextFree.invokeExact(renderContext);
            long t2 = System.nanoTime();
            // The handle must not be destroyed while another thread is inside mpv_wait_event():
            // ask the core to quit, let the event loop observe SHUTDOWN and exit, then destroy.
            command("quit");
            eventThread.join(2000);
            Mpv.terminateDestroy.invokeExact(handle);
            long t3 = System.nanoTime();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format("mpv close: render thread %d ms, render ctx free %d ms, quit+destroy %d ms, total %d ms",
                    (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000,
                    (System.nanoTime() - t0) / 1_000_000));
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "mpv shutdown: " + t);
        }
        // Arena intentionally not closed: JavaFX may still reference the pixel buffers until exit.
    }

    // Called by mpv from one of its own threads: must not render here, just signal.
    @SuppressWarnings("unused")
    private void onUpdate(MemorySegment ignored) {
        frameSignal.release();
    }

    private void renderLoop() {
        while (running) {
            frameSignal.acquireUninterruptibly();
            frameSignal.drainPermits();
            if (!running) {
                return;
            }
            try {
                long flags = (long) Mpv.renderContextUpdate.invokeExact(renderContext);
                if ((flags & Mpv.RENDER_UPDATE_FRAME) == 0) {
                    continue;
                }
                int target = pickBuffer();
                long t0 = System.nanoTime();
                int rc = (int) Mpv.renderContextRender.invokeExact(renderContext, renderParams[target]);
                if (rc < 0) {
                    LOG.log(System.Logger.Level.WARNING, "render failed: " + Mpv.describe(rc));
                    continue;
                }
                if (fixAlpha) {
                    forceOpaque(pixels[target]);
                }
                record((System.nanoTime() - t0) / 1e6);
                publish(target);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "render loop: " + t);
            }
        }
    }

    private int pickBuffer() {
        int shown = displayed.get();
        int queued = pending.get();
        for (int i = 0; i < BUFFERS; i++) {
            if (i != shown && i != queued) {
                return i;
            }
        }
        return (shown + 1) % BUFFERS;
    }

    private void publish(int index) {
        int previous = pending.getAndSet(index);
        if (previous != -1) {
            return; // An FX update is already scheduled; it will pick up the newest pending buffer.
        }
        Platform.runLater(() -> {
            int i = pending.getAndSet(-1);
            if (i < 0) {
                return;
            }
            pixelBuffers[i].updateBuffer(b -> null);
            view.setImage(images[i]);
            displayed.set(i);
        });
    }

    private void eventLoop() {
        while (true) {
            try {
                MemorySegment event = ((MemorySegment) Mpv.waitEvent.invokeExact(handle, -1.0)).reinterpret(24);
                int id = event.get(JAVA_INT, 0);
                if (id == Mpv.EVENT_SHUTDOWN) {
                    return;
                }
                if (id == Mpv.EVENT_END_FILE) {
                    MemorySegment data = event.get(ADDRESS, 16).reinterpret(16);
                    if (data.get(JAVA_INT, 0) == Mpv.END_FILE_REASON_EOF) {
                        endListener.run();
                    }
                }
            } catch (Throwable t) {
                if (running) {
                    LOG.log(System.Logger.Level.WARNING, "event loop: " + t);
                }
                return;
            }
        }
    }

    private synchronized void record(double ms) {
        statFrames++;
        statTotalMs += ms;
        statMaxMs = Math.max(statMaxMs, ms);
    }

    /** The "0"/alpha byte of bgr0 is garbage; JavaFX expects premultiplied BGRA, so force A=255. */
    private void forceOpaque(MemorySegment segment) {
        ByteBuffer bb = segment.asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.nio.IntBuffer ints = bb.asIntBuffer();
        int n = ints.limit();
        for (int i = 0; i < n; i++) {
            ints.put(i, ints.get(i) | 0xFF000000);
        }
    }

    private MemorySegment buildRenderParams(MemorySegment target, String format) {
        MemorySegment size = arena.allocate(JAVA_INT, 2);
        size.setAtIndex(JAVA_INT, 0, width);
        size.setAtIndex(JAVA_INT, 1, height);
        MemorySegment strideSeg = arena.allocate(JAVA_LONG);
        strideSeg.set(JAVA_LONG, 0, stride);
        MemorySegment block = arena.allocate(JAVA_INT);
        block.set(JAVA_INT, 0, Boolean.parseBoolean(System.getProperty("mpv.blockForTargetTime", "true")) ? 1 : 0);
        MemorySegment params = arena.allocate(Mpv.RENDER_PARAM_SIZE * 6, 8);
        putParam(params, 0, Mpv.RENDER_PARAM_SW_SIZE, size);
        putParam(params, 1, Mpv.RENDER_PARAM_SW_FORMAT, arena.allocateFrom(format));
        putParam(params, 2, Mpv.RENDER_PARAM_SW_STRIDE, strideSeg);
        putParam(params, 3, Mpv.RENDER_PARAM_SW_POINTER, target);
        putParam(params, 4, Mpv.RENDER_PARAM_BLOCK_FOR_TARGET_TIME, block);
        putParam(params, 5, Mpv.RENDER_PARAM_INVALID, MemorySegment.NULL);
        return params;
    }

    private static void putParam(MemorySegment array, int index, int type, MemorySegment data) {
        long base = index * Mpv.RENDER_PARAM_SIZE;
        array.set(JAVA_INT, base, type);
        array.set(ADDRESS, base + 8, data);
    }

    private void setOption(String name, String value) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            Mpv.check((int) Mpv.setOptionString.invokeExact(handle, a.allocateFrom(name), a.allocateFrom(value)),
                    "set option " + name);
        }
    }

    private void setProperty(String name, String value) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) Mpv.setPropertyString.invokeExact(handle, a.allocateFrom(name), a.allocateFrom(value));
            if (rc < 0) {
                LOG.log(System.Logger.Level.WARNING, "set property " + name + ": " + Mpv.describe(rc));
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void command(String... args) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment argv = a.allocate(ADDRESS, args.length + 1);
            for (int i = 0; i < args.length; i++) {
                argv.setAtIndex(ADDRESS, i, a.allocateFrom(args[i]));
            }
            argv.setAtIndex(ADDRESS, args.length, MemorySegment.NULL);
            int rc = (int) Mpv.command.invokeExact(handle, argv);
            if (rc < 0) {
                LOG.log(System.Logger.Level.WARNING, "command " + args[0] + ": " + Mpv.describe(rc));
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
