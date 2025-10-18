package emulator.debug;

import emulator.Emulator;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DrawCapture {
        private static final Object LOCK = new Object();

        private static boolean sessionActive;
        private static int sessionCounter = 1;
        private static int currentSessionIndex;
        private static long currentSessionStart;
        private static long currentFrameStart;

        private static final List<DrawCommand> commands = new ArrayList<>();
        private static final List<FrameSnapshot> sessionFrames = new ArrayList<>();

        private DrawCapture() {
        }

        public static CaptureStatus toggleCapture() {
                List<FrameSnapshot> framesToWrite = null;
                long sessionStart = 0L;
                String sessionId;
                synchronized (LOCK) {
                        if (!sessionActive) {
                                sessionActive = true;
                                currentSessionIndex = sessionCounter++;
                                currentSessionStart = System.currentTimeMillis();
                                currentFrameStart = currentSessionStart;
                                commands.clear();
                                sessionFrames.clear();
                                sessionId = formatSessionId(currentSessionIndex);
                                return new CaptureStatus(true, null, 0, sessionId, null);
                        }

                        sessionStart = currentSessionStart;
                        sessionId = formatSessionId(currentSessionIndex);
                        if (!commands.isEmpty()) {
                                long now = System.currentTimeMillis();
                                sessionFrames.add(new FrameSnapshot(currentFrameStart, now, new ArrayList<>(commands)));
                                commands.clear();
                                currentFrameStart = now;
                        }
                        if (!sessionFrames.isEmpty()) {
                                framesToWrite = new ArrayList<>(sessionFrames);
                        }
                        sessionFrames.clear();
                        commands.clear();
                        sessionActive = false;
                }

                Path savedFile = null;
                String error = null;
                int frameCount = 0;
                if (framesToWrite != null) {
                                frameCount = framesToWrite.size();
                                try {
                                        savedFile = writeSession(currentSessionIndex, sessionStart, framesToWrite);
                                } catch (IOException e) {
                                        e.printStackTrace();
                                        String message = e.getMessage();
                                        error = message != null ? message : e.getClass().getSimpleName();
                                }
                }

                return new CaptureStatus(false, savedFile, frameCount, sessionId, error);
        }

        public static boolean isRecording() {
                synchronized (LOCK) {
                        return sessionActive;
                }
        }

        public static void onFlush() {
                synchronized (LOCK) {
                        if (!sessionActive) {
                                commands.clear();
                                return;
                        }
                        if (commands.isEmpty()) {
                                currentFrameStart = System.currentTimeMillis();
                                return;
                        }
                        long end = System.currentTimeMillis();
                        sessionFrames.add(new FrameSnapshot(currentFrameStart, end, new ArrayList<>(commands)));
                        commands.clear();
                        currentFrameStart = end;
                }
        }

        public static void record(Image image, int sx, int sy, int sw, int sh,
                                  int dx, int dy, int dw, int dh, int transform, int anchor) {
                synchronized (LOCK) {
                        if (!sessionActive) {
                                return;
                        }
                        commands.add(new DrawCommand(
                                        image.getDebugId(),
                                        sx, sy, sw, sh,
                                        dx, dy, dw, dh,
                                        transform, anchor));
                }
        }

        private static Path writeSession(int index, long sessionStart, List<FrameSnapshot> frames) throws IOException {
                Path dir = Paths.get(Emulator.getUserPath(), "capture", "draw");
                Files.createDirectories(dir);

                String sessionId = formatSessionId(index);
                Path file = dir.resolve(sessionId + ".json");

                long totalDuration = 0L;
                Set<Integer> spriteIds = new LinkedHashSet<>();
                for (FrameSnapshot frame : frames) {
                        totalDuration += Math.max(0L, frame.end - frame.start);
                        for (DrawCommand command : frame.commands) {
                                spriteIds.add(command.imageId);
                        }
                }

                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                        writer.write("{\n");
                        writer.write("  \"meta\": {\n");
                        writer.write(String.format("    \"session\": \"%s\",\n", sessionId));
                        writer.write(String.format("    \"captured_at\": \"%s\",\n", Instant.ofEpochMilli(sessionStart)));
                        writer.write(String.format("    \"frame_count\": %d,\n", frames.size()));
                        writer.write(String.format("    \"duration_ms\": %d,\n", totalDuration));
                        writer.write("    \"sprite_ids\": [");
                        int spriteIndex = 0;
                        for (Integer spriteId : spriteIds) {
                                if (spriteIndex++ > 0) {
                                        writer.write(", ");
                                }
                                writer.write(String.valueOf(spriteId));
                        }
                        writer.write("]\n");
                        writer.write("  },\n");
                        writer.write("  \"legend\": {\n");
                        writer.write("    \"meta.session\": \"Unique capture identifier for this recording.\",\n");
                        writer.write("    \"meta.captured_at\": \"UTC timestamp when the session started.\",\n");
                        writer.write("    \"meta.duration_ms\": \"Sum of all frame durations in milliseconds.\",\n");
                        writer.write("    \"meta.sprite_ids\": \"Stable image debug IDs as shown in Memory View.\",\n");
                        writer.write("    \"frame.index\": \"Zero-based order of the frame within the capture.\",\n");
                        writer.write("    \"frame.started_at\": \"UTC timestamp when the frame was queued.\",\n");
                        writer.write("    \"frame.duration_ms\": \"Milliseconds between frame start and finish.\",\n");
                        writer.write("    \"frame.bounds\": \"Absolute bounding rectangle for the frame in screen coordinates.\",\n");
                        writer.write("    \"part.order\": \"Draw order (0 renders first).\",\n");
                        writer.write("    \"part.offset\": \"Offset relative to frame.bounds for positioning sprite parts.\",\n");
                        writer.write("    \"part.absolute_position\": \"Screen-space top-left after anchor resolution.\",\n");
                        writer.write("    \"part.size\": \"Destination width and height after scaling or transforms.\",\n");
                        writer.write("    \"part.source\": \"Source rectangle inside the original sprite image.\",\n");
                        writer.write("    \"part.transform\": \"Sprite.TRANS_* constant describing orientation.\",\n");
                        writer.write("    \"part.anchor\": \"Graphics anchor flags (TOP/LEFT/HCENTER/etc) used when drawing.\"\n");
                        writer.write("  },\n");
                        writer.write("  \"frames\": [\n");
                        for (int i = 0; i < frames.size(); i++) {
                                FrameSnapshot snapshot = frames.get(i);
                                writeFrame(writer, snapshot, i, i + 1 < frames.size());
                        }
                        writer.write("  ]\n");
                        writer.write("}\n");
                }

                return file;
        }

        private static void writeFrame(BufferedWriter writer, FrameSnapshot frame, int index, boolean hasNext) throws IOException {
                FrameBounds bounds = calculateBounds(frame.commands);
                long duration = Math.max(0L, frame.end - frame.start);

                writer.write("    {\n");
                writer.write(String.format("      \"index\": %d,\n", index));
                writer.write(String.format("      \"started_at\": \"%s\",\n", Instant.ofEpochMilli(frame.start)));
                writer.write(String.format("      \"ended_at\": \"%s\",\n", Instant.ofEpochMilli(frame.end)));
                writer.write(String.format("      \"duration_ms\": %d,\n", duration));
                writer.write(String.format("      \"bounds\": { \"x\": %d, \"y\": %d, \"width\": %d, \"height\": %d },\n",
                                bounds.x, bounds.y, bounds.width, bounds.height));
                writer.write("      \"parts\": [\n");
                for (int i = 0; i < frame.commands.size(); i++) {
                        DrawCommand command = frame.commands.get(i);
                        writePart(writer, command, bounds, i, i + 1 < frame.commands.size());
                }
                writer.write("      ]\n");
                writer.write("    }");
                if (hasNext) {
                        writer.write(",");
                }
                writer.write("\n");
        }

        private static void writePart(BufferedWriter writer, DrawCommand command, FrameBounds bounds, int order, boolean hasNext) throws IOException {
                int width = Math.abs(command.dw);
                int height = Math.abs(command.dh);
                int absoluteX = resolveAnchorX(command.dx, width, command.anchor);
                int absoluteY = resolveAnchorY(command.dy, height, command.anchor);
                int offsetX = absoluteX - bounds.x;
                int offsetY = absoluteY - bounds.y;

                writer.write("        {\n");
                writer.write(String.format("          \"order\": %d,\n", order));
                writer.write(String.format("          \"sprite_id\": %d,\n", command.imageId));
                writer.write(String.format("          \"offset\": { \"x\": %d, \"y\": %d },\n", offsetX, offsetY));
                writer.write(String.format("          \"absolute_position\": { \"x\": %d, \"y\": %d },\n", absoluteX, absoluteY));
                writer.write(String.format("          \"size\": { \"width\": %d, \"height\": %d },\n", width, height));
                writer.write(String.format("          \"source\": { \"x\": %d, \"y\": %d, \"width\": %d, \"height\": %d },\n",
                                command.sx, command.sy, command.sw, command.sh));
                writer.write(String.format("          \"transform\": { \"value\": %d, \"name\": \"%s\" },\n",
                                command.transform, transformName(command.transform)));
                writer.write(String.format("          \"anchor\": { \"value\": %d, \"components\": %s }\n",
                                command.anchor, anchorComponentsJson(command.anchor)));
                writer.write("        }");
                if (hasNext) {
                        writer.write(",");
                }
                writer.write("\n");
        }

        private static FrameBounds calculateBounds(List<DrawCommand> commands) {
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (DrawCommand command : commands) {
                        int width = Math.abs(command.dw);
                        int height = Math.abs(command.dh);
                        int x = resolveAnchorX(command.dx, width, command.anchor);
                        int y = resolveAnchorY(command.dy, height, command.anchor);
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x + width);
                        maxY = Math.max(maxY, y + height);
                }
                if (minX == Integer.MAX_VALUE) {
                        return new FrameBounds(0, 0, 0, 0);
                }
                return new FrameBounds(minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY));
        }

        private static int resolveAnchorX(int x, int width, int anchor) {
                if ((anchor & Graphics.RIGHT) != 0) {
                        return x - width;
                }
                if ((anchor & Graphics.HCENTER) != 0) {
                        return x - width / 2;
                }
                return x;
        }

        private static int resolveAnchorY(int y, int height, int anchor) {
                if ((anchor & Graphics.BOTTOM) != 0 || (anchor & Graphics.BASELINE) != 0) {
                        return y - height;
                }
                if ((anchor & Graphics.VCENTER) != 0) {
                        return y - height / 2;
                }
                return y;
        }

        private static String anchorComponentsJson(int anchor) {
                String[] components = describeAnchorComponents(anchor);
                StringBuilder builder = new StringBuilder("[");
                for (int i = 0; i < components.length; i++) {
                        if (i > 0) {
                                builder.append(", ");
                        }
                        builder.append('\"').append(components[i]).append('\"');
                }
                builder.append(']');
                return builder.toString();
        }

        private static String[] describeAnchorComponents(int anchor) {
                List<String> components = new ArrayList<>();
                if ((anchor & Graphics.TOP) != 0) {
                        components.add("TOP");
                }
                if ((anchor & Graphics.BOTTOM) != 0) {
                        components.add("BOTTOM");
                }
                if ((anchor & Graphics.VCENTER) != 0) {
                        components.add("VCENTER");
                }
                if ((anchor & Graphics.BASELINE) != 0) {
                        components.add("BASELINE");
                }
                if ((anchor & Graphics.LEFT) != 0) {
                        components.add("LEFT");
                }
                if ((anchor & Graphics.RIGHT) != 0) {
                        components.add("RIGHT");
                }
                if ((anchor & Graphics.HCENTER) != 0) {
                        components.add("HCENTER");
                }
                if (components.isEmpty()) {
                        components.add("DEFAULT");
                }
                return components.toArray(new String[0]);
        }

        private static String transformName(int transform) {
                switch (transform) {
                        case 0:
                                return "NONE";
                        case 1:
                                return "ROTATE_90";
                        case 2:
                                return "ROTATE_180";
                        case 3:
                                return "ROTATE_270";
                        case 4:
                                return "MIRROR";
                        case 5:
                                return "MIRROR_ROTATE_90";
                        case 6:
                                return "MIRROR_ROTATE_180";
                        case 7:
                                return "MIRROR_ROTATE_270";
                        default:
                                return "UNKNOWN";
                }
        }

        private static String formatSessionId(int index) {
                return String.format("capture_%04d", index);
        }

        private static final class DrawCommand {
                final int imageId;
                final int sx;
                final int sy;
                final int sw;
                final int sh;
                final int dx;
                final int dy;
                final int dw;
                final int dh;
                final int transform;
                final int anchor;

                DrawCommand(int imageId, int sx, int sy, int sw, int sh, int dx, int dy, int dw, int dh, int transform, int anchor) {
                        this.imageId = imageId;
                        this.sx = sx;
                        this.sy = sy;
                        this.sw = sw;
                        this.sh = sh;
                        this.dx = dx;
                        this.dy = dy;
                        this.dw = dw;
                        this.dh = dh;
                        this.transform = transform;
                        this.anchor = anchor;
                }
        }

        private static final class FrameSnapshot {
                final long start;
                final long end;
                final List<DrawCommand> commands;

                FrameSnapshot(long start, long end, List<DrawCommand> commands) {
                        this.start = start;
                        this.end = end;
                        this.commands = commands;
                }
        }

        private static final class FrameBounds {
                final int x;
                final int y;
                final int width;
                final int height;

                FrameBounds(int x, int y, int width, int height) {
                        this.x = x;
                        this.y = y;
                        this.width = width;
                        this.height = height;
                }
        }

        public static final class CaptureStatus {
                public final boolean active;
                public final Path file;
                public final int frames;
                public final String sessionId;
                public final String error;

                CaptureStatus(boolean active, Path file, int frames, String sessionId, String error) {
                        this.active = active;
                        this.file = file;
                        this.frames = frames;
                        this.sessionId = sessionId;
                        this.error = error;
                }
        }
}
