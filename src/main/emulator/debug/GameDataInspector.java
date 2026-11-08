package emulator.debug;

import emulator.Emulator;
import emulator.graphics2D.IImage;

import javax.microedition.lcdui.Image;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Opt-in research instrumentation for J2ME games. Does not depend on game class names.
 *
 * Toggle via MemoryView's "Game trace" checkbox, -Dkem.inspector=true, or
 * KEM_INSPECTOR=1 (the environment variable survives KEmulator's child JVM).
 *
 * Files are written as JSON Lines; raw resources and bitmap files are separate.
 * This deliberately does not pretend to decode a game's private animation format.
 */
public final class GameDataInspector {
    private static volatile boolean enabled =
            Boolean.getBoolean("kem.inspector") || "1".equals(System.getenv("KEM_INSPECTOR"));
    private static final boolean MUTABLE_VERSIONS =
            "1".equals(System.getenv("KEM_INSPECT_MUTABLE"));
    private static final boolean DRAW_CALLSITES =
            "1".equals(System.getenv("KEM_INSPECT_CALLSITES"));
    private static final boolean TRACE_METHODS =
            "1".equals(System.getenv("KEM_INSPECT_METHODS"));
    private static final int MAX_ARRAY_ITEMS = Math.max(128, Math.min(1000000,
            intEnv("KEM_INSPECT_MAX_ARRAY", 65536)));
    private static final int STATE_EVERY = Math.max(1, intEnv("KEM_INSPECT_STATE_EVERY", 30));
    private static final String STATE_CLASSES =
            System.getenv("KEM_INSPECT_STATE_CLASSES") == null ? "g,f" :
                    System.getenv("KEM_INSPECT_STATE_CLASSES");

    private static final Map<String, BufferedWriter> writers = new HashMap<String, BufferedWriter>();
    private static final Map<Image, Integer> imageIds = new WeakHashMap<Image, Integer>();
    private static final Map<IImage, Integer> nativeImageIds = new WeakHashMap<IImage, Integer>();
    private static final Map<Integer, String> imageVersions = new HashMap<Integer, String>();
    private static final HashSet<Integer> savedImages = new HashSet<Integer>();
    private static final Map<String, String> stateValues = new HashMap<String, String>();
    private static final HashSet<String> savedResources = new HashSet<String>();
    private static File root;
    private static long frame = 0L;
    private static long serial = 0L;
    private static int nextImageId = 1;
    private static boolean warned;

    private GameDataInspector() {}

    public static boolean isEnabled() {
        return enabled;
    }
    /** Method instrumentation must be selected before classes are first loaded. */
    public static boolean isTracingMethods() {
        return TRACE_METHODS;
    }

    public static synchronized void methodEntered(String method) {
        if (!enabled || !TRACE_METHODS) return;
        record("methods.jsonl", "{\"frame\":" + frame + ",\"method\":" + json(method) +
                ",\"thread\":" + Thread.currentThread().getId() + "}");
    }

    public static synchronized void setEnabled(boolean value) {
        if (!value) {
            enabled = false;
            closeWriters();
            return;
        }
        enabled = true;
        start();
    }

    private static int intEnv(String name, int fallback) {
        try {
            String s = System.getenv(name);
            return s == null ? fallback : Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean start() {
        if (!enabled) return false;
        if (root != null) return true;
        String configured = System.getenv("KEM_INSPECT_DIR");
        if (configured == null || configured.trim().length() == 0) {
            configured = System.getProperty("kem.inspector.dir", "game-inspector-output");
        }
        try {
            root = new File(configured).getAbsoluteFile();
            mkdir("resources");
            mkdir("images");
            mkdir("heap");
            System.out.println("[GameInspector] Capturing to " + root);
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() { synchronized (GameDataInspector.class) { closeWriters(); } }
            }, "GameInspectorShutdown"));
            return true;
        } catch (Throwable t) {
            enabled = false;
            if (!warned) {
                warned = true;
                System.err.println("[GameInspector] Cannot initialize output: " + t);
            }
            return false;
        }
    }

    private static File mkdir(String name) throws IOException {
        File folder = new File(root, name);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Cannot create directory " + folder);
        }
        return folder;
    }

    private static String json(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32) {
                        String h = Integer.toHexString(c);
                        b.append("\\u");
                        for (int k = h.length(); k < 4; k++) b.append('0');
                        b.append(h);
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    private static void record(String filename, String line) {
        if (!start()) return;
        try {
            BufferedWriter w = writers.get(filename);
            if (w == null) {
                w = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(root, filename), true), "UTF-8"), 65536);
                writers.put(filename, w);
            }
            w.write(line);
            w.newLine();
            serial++;
            if (serial % 1024 == 0) w.flush();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.err.println("[GameInspector] Write failure: " + t);
            }
        }
    }

    private static void closeWriters() {
        for (BufferedWriter w : writers.values()) {
            try { w.flush(); w.close(); } catch (IOException ignored) {}
        }
        writers.clear();
    }

    private static void flushWriters() {
        for (BufferedWriter w : writers.values()) {
            try { w.flush(); } catch (IOException ignored) {}
        }
    }

    private static String caller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : stack) {
            String n = e.getClassName();
            if (n.startsWith("java.") || n.startsWith("sun.") ||
                    n.startsWith("javax.microedition.") || n.startsWith("emulator.") ||
                    n.startsWith("org.eclipse.") || n.startsWith("com.sun.") ||
                    n.equals("java.lang.Thread")) continue;
            return e.toString();
        }
        return "";
    }

    private static String digest(byte[] data) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder();
            for (byte c : bytes) b.append(String.format("%02x", c & 255));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(data));
        }
    }

    private static String safe(String path) {
        String s = path.replace('\\', '/');
        if (s.startsWith("/")) s = s.substring(1);
        s = s.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (s.equals(".") || s.equals("..") || s.length() == 0) s = "resource";
        if (s.length() > 100) s = s.substring(s.length() - 100);
        return s;
    }

    /**
     * Records all bytes read by the KEmulator resource manager.
     * Hooked before the InputStream is handed back to the game.
     */
    public static synchronized void resourceOpened(String name, byte[] data) {
        if (!enabled || data == null || !start()) return;
        String hash = digest(data);
        String key = name + "|" + hash;
        if (savedResources.add(key)) {
            String filename = safe(name) + "_" + hash.substring(0, Math.min(hash.length(), 12)) + ".bin";
            try {
                Files.write(new File(root, "resources/" + filename).toPath(), data);
            } catch (IOException e) {
                System.err.println("[GameInspector] Cannot save resource " + name + ": " + e);
            }
            record("resources.jsonl",
                    "{\"frame\":" + frame + ",\"name\":" + json(name) +
                    ",\"file\":" + json(filename) + ",\"length\":" + data.length +
                    ",\"sha256\":" + json(hash) + ",\"caller\":" + json(caller()) + "}");
        }
    }

    private static int imageId(Image image) {
        Integer id = imageIds.get(image);
        if (id != null) return id;
        int next = nextImageId++;
        imageIds.put(image, next);
        try {
            nativeImageIds.put(image.getImpl(), next);
            record("images.jsonl", "{\"event\":\"create\",\"id\":" + next +
                    ",\"width\":" + image.getWidth() + ",\"height\":" + image.getHeight() +
                    ",\"mutable\":" + image.isMutable() + ",\"frame\":" + frame + "}");
        } catch (Throwable e) {
            record("images.jsonl", "{\"event\":\"create\",\"id\":" + next +
                    ",\"error\":" + json(e.toString()) + "}");
        }
        return next;
    }

    public static synchronized void imageCreated(Image image) {
        if (enabled && image != null) imageId(image);
    }

    private static String capture(Image image, int id) {
        if (!start()) return "";
        String existing = imageVersions.get(id);
        if (existing != null && (!image.isMutable() || !MUTABLE_VERSIONS)) return existing;
        String name = String.format(Locale.ROOT, "img_%05d", id);
        if (image.isMutable() && MUTABLE_VERSIONS) {
            try {
                int w = image.getWidth(), h = image.getHeight();
                if (w > 0 && h > 0 && (long) w * h <= 2000000) {
                    int[] px = new int[w * h];
                    image.getRGB(px, 0, w, 0, 0, w, h);
                    CRC32 crc = new CRC32();
                    for (int n : px) {
                        crc.update((n >>> 24) & 255);
                        crc.update((n >>> 16) & 255);
                        crc.update((n >>> 8) & 255);
                        crc.update(n & 255);
                    }
                    name += "_" + Long.toHexString(crc.getValue());
                }
            } catch (Throwable ignored) {}
        }
        String filename = name + ".png";
        if (filename.equals(existing)) return filename;
        try {
            File dest = new File(root, "images/" + filename);
            if (!dest.isFile()) image.getImpl().saveToFile(dest.getAbsolutePath());
            imageVersions.put(id, filename);
            if (savedImages.add(id) || image.isMutable()) {
                record("images.jsonl", "{\"event\":\"capture\",\"id\":" + id +
                        ",\"file\":" + json(filename) + ",\"mutableAtCapture\":" + image.isMutable() + ",\"frame\":" + frame + "}");
            }
        } catch (Throwable e) {
            record("images.jsonl", "{\"event\":\"capture_error\",\"id\":" + id +
                    ",\"error\":" + json(e.toString()) + "}");
        }
        return filename;
    }

    private static String target(IImage output) {
        if (output == null) return "null";
        Integer id = nativeImageIds.get(output);
        return id == null ? json("native@" + Integer.toHexString(System.identityHashCode(output))) :
                json("img_" + id);
    }

    public static synchronized void drawImage(Image source, IImage dest, int x, int y,
                                              int translateX, int translateY,
                                              int clipX, int clipY, int clipW, int clipH) {
        if (!enabled || source == null) return;
        int id = imageId(source);
        String filename = capture(source, id);
        record("draws.jsonl", "{\"frame\":" + frame + ",\"op\":\"drawImage\",\"imageId\":" + id +
                ",\"file\":" + json(filename) + ",\"target\":" + target(dest) +
                ",\"x\":" + x + ",\"y\":" + y +
                ",\"tx\":" + translateX + ",\"ty\":" + translateY +
                ",\"clip\":[" + clipX + "," + clipY + "," + clipW + "," + clipH + "]" +
                (DRAW_CALLSITES ? ",\"caller\":" + json(caller()) : "") + "}");
    }

    public static synchronized void drawRegion(Image source, IImage dest,
                                               int sx, int sy, int w, int h,
                                               int transform, int dx, int dy,
                                               int dw, int dh, int anchor,
                                               int translateX, int translateY,
                                               int clipX, int clipY, int clipW, int clipH) {
        if (!enabled || source == null) return;
        int id = imageId(source);
        String filename = capture(source, id);
        record("draws.jsonl", "{\"frame\":" + frame + ",\"op\":\"drawRegion\",\"imageId\":" + id +
                ",\"file\":" + json(filename) + ",\"target\":" + target(dest) +
                ",\"src\":[" + sx + "," + sy + "," + w + "," + h + "]" +
                ",\"dst\":[" + dx + "," + dy + "," + dw + "," + dh + "]" +
                ",\"transform\":" + transform + ",\"anchor\":" + anchor +
                ",\"tx\":" + translateX + ",\"ty\":" + translateY +
                ",\"clip\":[" + clipX + "," + clipY + "," + clipW + "," + clipH + "]" +
                (DRAW_CALLSITES ? ",\"caller\":" + json(caller()) : "") + "}");
    }

    public static synchronized void drawRGB(IImage dest, int x, int y, int w, int h) {
        if (!enabled) return;
        record("draws.jsonl", "{\"frame\":" + frame + ",\"op\":\"drawRGB\",\"target\":" + target(dest) +
                ",\"dst\":[" + x + "," + y + "," + w + "," + h + "],\"pixelsNotCaptured\":true}");
    }

    public static synchronized void framePresented(String source) {
        if (!enabled) return;
        record("frames.jsonl", "{\"frame\":" + frame + ",\"source\":" + json(source) +
                ",\"timeNanos\":" + System.nanoTime() + "}");
        frame++;
        if (frame % STATE_EVERY == 0) captureState();
        flushWriters();
    }

    /** Track primitive and String static fields of selected game classes. */
    private static void captureState() {
        for (String name : STATE_CLASSES.split(",")) {
            name = name.trim();
            if (name.length() == 0) continue;
            try {
                Class<?> cls = Class.forName(name, false, Emulator.getCustomClassLoader());
                for (Field f : cls.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> type = f.getType();
                    if (!(type.isPrimitive() || type == String.class)) continue;
                    f.setAccessible(true);
                    Object v = f.get(null);
                    String key = cls.getName() + "." + f.getName() + ":" + type.getName();
                    String str = String.valueOf(v);
                    String prior = stateValues.put(key, str);
                    if (prior == null || !prior.equals(str)) {
                        record("state.jsonl", "{\"frame\":" + frame +
                                ",\"field\":" + json(key) + ",\"value\":" + json(str) +
                                ",\"previous\":" + json(prior) + "}");
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Snapshot the objects that MemoryView can reach, including primitive arrays.
     * Call explicitly from its "Dump game heap" button, not on the rendering thread.
     */
    public static synchronized void dumpHeap() {
        if (!enabled) setEnabled(true);
        if (!start()) return;
        try {
            Memory memory = Memory.getInstance();
            memory.updateEverything();
            File dest = new File(root, "heap/heap_" + System.currentTimeMillis() + ".jsonl");
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(dest), "UTF-8"));
            int count = 0, arrays = 0;
            try {
                for (Map.Entry<String, ClassInfo> entry : memory.classesTable.entrySet()) {
                    for (ObjInstance inst : entry.getValue().objs) {
                        Object value = inst.value;
                        if (value == null) continue;
                        String cls = value.getClass().getName();
                        StringBuilder paths = new StringBuilder("[");
                        int n = 0;
                        for (ReferencePath path : inst.paths) {
                            if (n++ > 0) paths.append(',');
                            if (n > 8) break;
                            paths.append(json(path.toString()));
                        }
                        paths.append(']');
                        String base = "{\"class\":" + json(cls) +
                                ",\"identity\":" + System.identityHashCode(value) +
                                ",\"paths\":" + paths;
                        if (value.getClass().isArray()) {
                            int size = Array.getLength(value);
                            Class<?> element = value.getClass().getComponentType();
                            StringBuilder values = new StringBuilder("[");
                            int limit = Math.min(size, MAX_ARRAY_ITEMS);
                            for (int i = 0; i < limit; i++) {
                                if (i > 0) values.append(',');
                                Object item = Array.get(value, i);
                                if (item == null) values.append("null");
                                else if (item instanceof Number || item instanceof Boolean) values.append(item);
                                else if (item instanceof String || item instanceof Character) values.append(json(item.toString()));
                                else values.append(json(item.getClass().getName() + "@" +
                                        Integer.toHexString(System.identityHashCode(item))));
                            }
                            values.append(']');
                            out.write(base + ",\"kind\":\"array\",\"elementType\":" + json(element.getName()) +
                                    ",\"length\":" + size + ",\"truncated\":" + (size > limit) +
                                    ",\"values\":" + values + "}\n");
                            arrays++;
                        } else if (Emulator.jarClasses.contains(cls)) {
                            StringBuilder fields = new StringBuilder("{");
                            int fCount = 0;
                            for (Field f : value.getClass().getDeclaredFields()) {
                                if (Modifier.isStatic(f.getModifiers())) continue;
                                try {
                                    f.setAccessible(true);
                                    Object v = f.get(value);
                                    if (fCount++ > 0) fields.append(',');
                                    fields.append(json(f.getName() + ":" + f.getType().getName())).append(':');
                                    if (v == null) fields.append("null");
                                    else if (v instanceof Number || v instanceof Boolean) fields.append(v);
                                    else if (v instanceof String || v instanceof Character) fields.append(json(v.toString()));
                                    else fields.append(json(v.getClass().getName() + "@" +
                                            Integer.toHexString(System.identityHashCode(v))));
                                } catch (Throwable ignored) {}
                            }
                            out.write(base + ",\"kind\":\"gameObject\",\"fields\":" + fields + "}}\n");
                        } else {
                            continue;
                        }
                        count++;
                    }
                }
            } finally {
                out.flush();
                out.close();
            }
            record("snapshots.jsonl", "{\"frame\":" + frame + ",\"file\":" +
                    json("heap/" + dest.getName()) + ",\"objects\":" + count + ",\"arrays\":" + arrays + "}");
            flushWriters();
            System.out.println("[GameInspector] Heap snapshot " + dest + " (" + arrays + " arrays)");
        } catch (Throwable e) {
            System.err.println("[GameInspector] Heap export failed: " + e);
            e.printStackTrace();
        }
    }
}
