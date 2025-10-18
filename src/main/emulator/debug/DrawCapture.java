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
        private static final List<String> commands = new ArrayList<>();

        private DrawCapture() {
        }

        public static void requestOneFrameCapture() {
                synchronized (LOCK) {
                        armed = true;
                }
        }

        public static void onFlush() {
                List<String> frameCommands = null;
                int captureIndex = 0;
                long captureStart = 0L;

                synchronized (LOCK) {
                        if (capturing) {
                                frameCommands = new ArrayList<>(commands);
                                commands.clear();
                                capturing = false;
                                captureIndex = currentCaptureIndex;
                                captureStart = currentCaptureStart;
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
                        writeFrame(captureIndex, captureStart, frameCommands);
                }
        }

        public static void record(Image image, int sx, int sy, int sw, int sh,
                                  int dx, int dy, int dw, int dh, int transform, int anchor) {
                synchronized (LOCK) {
                        if (!capturing) {
                                return;
                        }
                        commands.add(String.format(
                                        "image=%d src=(%d,%d,%d,%d) dest=(%d,%d,%d,%d) transform=%d anchor=%d",
                                        image.getDebugId(), sx, sy, sw, sh, dx, dy, dw, dh, transform, anchor));
                }
        }

        private static void writeFrame(int index, long start, List<String> lines) {
                Path dir = Paths.get(Emulator.getUserPath(), "capture", "draw");
                try {
                        Files.createDirectories(dir);
                        String fileName = String.format("frame-%04d.txt", index);
                        try (BufferedWriter writer = Files.newBufferedWriter(dir.resolve(fileName), StandardCharsets.UTF_8)) {
                                writer.write("# frame " + index);
                                writer.newLine();
                                writer.write("# started " + Instant.ofEpochMilli(start));
                                writer.newLine();
                                writer.write("# commands " + lines.size());
                                writer.newLine();
                                for (String line : lines) {
                                        writer.write(line);
                                        writer.newLine();
                                }
                        }
                } catch (IOException e) {
                        e.printStackTrace();
                }
        }
}
