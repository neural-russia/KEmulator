package emulator.debug;

import emulator.Emulator;

import javax.microedition.lcdui.Image;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class DrawCapture {
        private static final Object LOCK = new Object();
        private static boolean armed;
        private static boolean capturing;
        private static int captureCounter = 1;
        private static int currentCaptureIndex;
        private static long currentCaptureStart;
        private static final List<DrawCommand> commands = new ArrayList<>();

        private DrawCapture() {
        }

        public static void requestOneFrameCapture() {
                synchronized (LOCK) {
                        armed = true;
                }
        }

        public static void onFlush() {
                List<DrawCommand> frameCommands = null;
                int captureIndex = 0;
                long captureStart = 0L;
                long captureEnd = 0L;

                synchronized (LOCK) {
                        if (capturing) {
                                frameCommands = new ArrayList<>(commands);
                                commands.clear();
                                capturing = false;
                                captureIndex = currentCaptureIndex;
                                captureStart = currentCaptureStart;
                                captureEnd = System.currentTimeMillis();
                        }

                        if (armed) {
                                capturing = true;
                                armed = false;
                                currentCaptureIndex = captureCounter++;
                                currentCaptureStart = System.currentTimeMillis();
                                commands.clear();
                        }
                }

                if (frameCommands != null) {
                        writeFrame(captureIndex, captureStart, captureEnd, frameCommands);
                }
        }

        public static void record(Image image, int sx, int sy, int sw, int sh,
                                  int dx, int dy, int dw, int dh, int transform, int anchor) {
                synchronized (LOCK) {
                        if (!capturing) {
                                return;
                        }
                        commands.add(new DrawCommand(
                                        image.getDebugId(),
                                        sx, sy, sw, sh,
                                        dx, dy, dw, dh,
                                        transform, anchor));
                }
        }

        private static void writeFrame(int index, long start, long end, List<DrawCommand> lines) {
                Path dir = Paths.get(Emulator.getUserPath(), "capture", "draw");
                try {
                        Files.createDirectories(dir);
                        String fileName = String.format("frame-%04d.json", index);
                        try (BufferedWriter writer = Files.newBufferedWriter(dir.resolve(fileName), StandardCharsets.UTF_8)) {
                                long duration = Math.max(0L, end - start);
                                writer.write("{\n");
                                writer.write("  \"animations\": {\n");
                                String animationName = String.format("capture_%04d", index);
                                writer.write(String.format("    \"%s\": {\n", animationName));
                                writer.write("      \"frames\": [\n");
                                writer.write("        {\n");
                                writer.write(String.format("          \"started_at\": \"%s\",\n", Instant.ofEpochMilli(start)));
                                writer.write(String.format("          \"duration\": %d,\n", duration));
                                writer.write("          \"parts\": [\n");
                                for (int i = 0; i < lines.size(); i++) {
                                        DrawCommand command = lines.get(i);
                                        writer.write("            {\n");
                                        writer.write(String.format("              \"sprite_id\": %d,\n", command.imageId));
                                        writer.write(String.format("              \"source\": { \"x\": %d, \"y\": %d, \"width\": %d, \"height\": %d },\n",
                                                        command.sx, command.sy, command.sw, command.sh));
                                        writer.write(String.format("              \"position\": { \"x\": %d, \"y\": %d, \"width\": %d, \"height\": %d },\n",
                                                        command.dx, command.dy, command.dw, command.dh));
                                        writer.write(String.format("              \"transform\": %d,\n", command.transform));
                                        writer.write(String.format("              \"anchor\": %d\n", command.anchor));
                                        writer.write("            }");
                                        if (i + 1 < lines.size()) {
                                                writer.write(",");
                                        }
                                        writer.write("\n");
                                }
                                writer.write("          ]\n");
                                writer.write("        }\n");
                                writer.write("      ]\n");
                                writer.write("    }\n");
                                writer.write("  }\n");
                                writer.write("}\n");
                        }
                } catch (IOException e) {
                        e.printStackTrace();
                }
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
}
