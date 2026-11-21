package webpresenter.player.engine.mpv;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** Minimal libmpv bindings through the Foreign Function & Memory API. */
final class Mpv {

    static final int EVENT_NONE = 0;
    static final int EVENT_SHUTDOWN = 1;
    static final int EVENT_END_FILE = 7;
    static final int EVENT_FILE_LOADED = 8;

    static final int END_FILE_REASON_EOF = 0;

    static final int RENDER_PARAM_INVALID = 0;
    static final int RENDER_PARAM_API_TYPE = 1;
    static final int RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12;
    static final int RENDER_PARAM_SW_SIZE = 17;
    static final int RENDER_PARAM_SW_FORMAT = 18;
    static final int RENDER_PARAM_SW_STRIDE = 19;
    static final int RENDER_PARAM_SW_POINTER = 20;

    static final long RENDER_UPDATE_FRAME = 1;

    /** struct mpv_render_param { int type; void *data; } */
    static final long RENDER_PARAM_SIZE = 16;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = loadLibrary();

    static final MethodHandle create = fn("mpv_create", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle initialize = fn("mpv_initialize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle setOptionString =
            fn("mpv_set_option_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle setPropertyString =
            fn("mpv_set_property_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle command = fn("mpv_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle waitEvent =
            fn("mpv_wait_event", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE));
    static final MethodHandle wakeup = fn("mpv_wakeup", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle terminateDestroy = fn("mpv_terminate_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle errorString = fn("mpv_error_string", FunctionDescriptor.of(ADDRESS, JAVA_INT));

    static final MethodHandle renderContextCreate =
            fn("mpv_render_context_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle renderContextSetUpdateCallback = fn(
            "mpv_render_context_set_update_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle renderContextUpdate =
            fn("mpv_render_context_update", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle renderContextRender =
            fn("mpv_render_context_render", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle renderContextFree = fn("mpv_render_context_free", FunctionDescriptor.ofVoid(ADDRESS));

    /** void (*mpv_render_update_fn)(void *cb_ctx) */
    static final FunctionDescriptor UPDATE_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS);

    private Mpv() {}

    /**
     * libmpv refuses to initialize unless LC_NUMERIC is "C" (it parses floats with the C library).
     * GTK/JavaFX set the process locale from the environment, so reset just the numeric category.
     */
    static void forceNumericCLocale() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return;
        }
        int lcNumeric = System.getProperty("os.name").toLowerCase().contains("mac") ? 4 : 1;
        MethodHandle setlocale = LINKER.downcallHandle(
                LINKER.defaultLookup().find("setlocale").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment unused = (MemorySegment) setlocale.invokeExact(lcNumeric, a.allocateFrom("C"));
        } catch (Throwable t) {
            throw new IllegalStateException("setlocale failed", t);
        }
    }

    static Linker linker() {
        return LINKER;
    }

    static void check(int rc, String what) {
        if (rc < 0) {
            throw new IllegalStateException(what + " failed: " + describe(rc));
        }
    }

    static String describe(int rc) {
        try {
            MemorySegment s = (MemorySegment) errorString.invokeExact(rc);
            return s.reinterpret(256).getString(0) + " (" + rc + ")";
        } catch (Throwable t) {
            return "error " + rc;
        }
    }

    static MemorySegment cString(Arena arena, String s) {
        return arena.allocateFrom(s);
    }

    private static SymbolLookup loadLibrary() {
        String override = System.getProperty("mpv.library");
        if (override != null) {
            return SymbolLookup.libraryLookup(override, Arena.global());
        }
        String os = System.getProperty("os.name").toLowerCase();
        String[] candidates = os.contains("win")
                ? new String[] {"libmpv-2.dll", "mpv-2.dll", "mpv-1.dll"}
                : os.contains("mac") ? new String[] {"libmpv.2.dylib", "libmpv.dylib"}
                : new String[] {"libmpv.so.2", "libmpv.so"};
        IllegalArgumentException last = null;
        for (String name : candidates) {
            try {
                return SymbolLookup.libraryLookup(name, Arena.global());
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        throw new IllegalStateException("libmpv not found; set -Dmpv.library=/path/to/libmpv", last);
    }

    private static MethodHandle fn(String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = LIB.find(name).orElseThrow(() -> new IllegalStateException("missing symbol " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }
}
