package emulator.debug;

import emulator.UILocale;
import javax.microedition.lcdui.Image;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DrawCapture {
        private static final ThreadLocal<CaptureSession> SESSION = new ThreadLocal<CaptureSession>() {
                protected CaptureSession initialValue() {
                        return new CaptureSession();
                }
        };
        private static final ThreadLocal<Deque<SpriteContext>> SPRITE_CONTEXT = new ThreadLocal<Deque<SpriteContext>>() {
                protected Deque<SpriteContext> initialValue() {
                        return new ArrayDeque<SpriteContext>();
                }
        };
        private static final char[] HEX = "0123456789ABCDEF".toCharArray();

        private static volatile boolean enabled;

        private DrawCapture() {
                super();
        }

        public static boolean isEnabled() {
                return enabled;
        }

        public static void setEnabled(final boolean captureEnabled) {
                enabled = captureEnabled;
                if (!captureEnabled) {
                        SESSION.get().clear();
                        SPRITE_CONTEXT.get().clear();
                }
        }

        public static void reset() {
                SESSION.get().clear();
        }

        public static void pushSpriteContext(final int refX,
                                             final int refY,
                                             final int sequenceIndex,
                                             final int rawFrameIndex,
                                             final int collisionX,
                                             final int collisionY,
                                             final int collisionW,
                                             final int collisionH) {
                if (!enabled) {
                        return;
                }
                SPRITE_CONTEXT.get().push(new SpriteContext(refX, refY, sequenceIndex, rawFrameIndex,
                                collisionX, collisionY, collisionW, collisionH));
        }

        public static void popSpriteContext() {
                if (!enabled) {
                        return;
                }
                final Deque<SpriteContext> stack = SPRITE_CONTEXT.get();
                if (!stack.isEmpty()) {
                        stack.pop();
                }
        }

        public static void record(final Image image,
                                   final int sx,
                                   final int sy,
                                   final int sw,
                                   final int sh,
                                   final int transform,
                                   final int dx,
                                   final int dy,
                                   final int dw,
                                   final int dh,
                                   final int anchor) {
                if (!enabled) {
                        return;
                }
                final CaptureSession session = SESSION.get();
                final SpriteContext context = peekSpriteContext();
                session.add(new CapturePart(image, sx, sy, sw, sh, transform, dx, dy, dw, dh, anchor, context));
        }

        public static String toJson() {
                return SESSION.get().toJson();
        }

        public static String toJsonAndReset() {
                final CaptureSession session = SESSION.get();
                final String json = session.toJson();
                session.clear();
                return json;
        }

        private static SpriteContext peekSpriteContext() {
                final Deque<SpriteContext> stack = SPRITE_CONTEXT.get();
                return stack.isEmpty() ? null : stack.peek();
        }

        private static void appendJsonString(final StringBuilder sb, final String value) {
                sb.append('"');
                for (int i = 0; i < value.length(); ++i) {
                        final char ch = value.charAt(i);
                        switch (ch) {
                                case '\\':
                                case '"':
                                        sb.append('\\').append(ch);
                                        break;
                                case '\b':
                                        sb.append("\\b");
                                        break;
                                case '\f':
                                        sb.append("\\f");
                                        break;
                                case '\n':
                                        sb.append("\\n");
                                        break;
                                case '\r':
                                        sb.append("\\r");
                                        break;
                                case '\t':
                                        sb.append("\\t");
                                        break;
                                default:
                                        if (ch < 0x20) {
                                                sb.append("\\u");
                                                sb.append(HEX[(ch >> 12) & 0xF]);
                                                sb.append(HEX[(ch >> 8) & 0xF]);
                                                sb.append(HEX[(ch >> 4) & 0xF]);
                                                sb.append(HEX[ch & 0xF]);
                                        } else {
                                                sb.append(ch);
                                        }
                                        break;
                        }
                }
                sb.append('"');
        }

        private static void appendIntArray(final StringBuilder sb, final int[] values) {
                sb.append('[');
                for (int i = 0; i < values.length; ++i) {
                        if (i > 0) {
                                sb.append(',');
                        }
                        sb.append(values[i]);
                }
                sb.append(']');
        }

        private static final class CaptureSession {
                private final List<CapturePart> parts = new ArrayList<CapturePart>();
                private final Map<String, String> legend = new LinkedHashMap<String, String>();

                CaptureSession() {
                        super();
                        refreshLegend();
                }

                void add(final CapturePart part) {
                        this.parts.add(part);
                }

                void clear() {
                        this.parts.clear();
                }

                private void refreshLegend() {
                        legend.put("image_id", UILocale.get("DRAW_CAPTURE_LEGEND_IMAGE_ID", "Image identity (hex)"));
                        legend.put("source", UILocale.get("DRAW_CAPTURE_LEGEND_SOURCE", "Source region [x, y, width, height]"));
                        legend.put("destination", UILocale.get("DRAW_CAPTURE_LEGEND_DESTINATION", "Destination [x, y, width, height]"));
                        legend.put("transform", UILocale.get("DRAW_CAPTURE_LEGEND_TRANSFORM", "MIDP transform value"));
                        legend.put("anchor", UILocale.get("DRAW_CAPTURE_LEGEND_ANCHOR", "Anchor bitmask"));
                        legend.put("sequence_index", UILocale.get("DRAW_CAPTURE_LEGEND_SEQUENCE_INDEX", "Frame sequence index"));
                        legend.put("ref_pixel", UILocale.get("DRAW_CAPTURE_LEGEND_REF_PIXEL", "Sprite reference pixel [x, y]"));
                        legend.put("raw_frame", UILocale.get("DRAW_CAPTURE_LEGEND_RAW_FRAME", "Raw frame index"));
                        legend.put("collision_bounds", UILocale.get("DRAW_CAPTURE_LEGEND_COLLISION_BOUNDS", "Collision bounds [x, y, width, height]"));
                }

                String toJson() {
                        refreshLegend();
                        final StringBuilder sb = new StringBuilder();
                        sb.append('{');
                        sb.append("\"legend\":{");
                        boolean first = true;
                        for (final Map.Entry<String, String> entry : legend.entrySet()) {
                                if (!first) {
                                        sb.append(',');
                                }
                                first = false;
                                appendJsonString(sb, entry.getKey());
                                sb.append(':');
                                appendJsonString(sb, entry.getValue());
                        }
                        sb.append("},\"parts\":[");
                        for (int i = 0; i < parts.size(); ++i) {
                                if (i > 0) {
                                        sb.append(',');
                                }
                                parts.get(i).appendJson(sb);
                        }
                        sb.append("],\"count\":");
                        sb.append(parts.size());
                        sb.append('}');
                        return sb.toString();
                }
        }

        private static final class CapturePart {
                private final String imageId;
                private final int[] source;
                private final int[] destination;
                private final int transform;
                private final int anchor;
                private final Integer sequenceIndex;
                private final int[] refPixel;
                private final Integer rawFrame;
                private final int[] collisionBounds;

                CapturePart(final Image image,
                            final int sx,
                            final int sy,
                            final int sw,
                            final int sh,
                            final int transform,
                            final int dx,
                            final int dy,
                            final int dw,
                            final int dh,
                            final int anchor,
                            final SpriteContext context) {
                        this.imageId = image == null ? null : Integer.toHexString(System.identityHashCode(image));
                        this.source = new int[]{sx, sy, sw, sh};
                        this.destination = new int[]{dx, dy, dw, dh};
                        this.transform = transform;
                        this.anchor = anchor;
                        if (context != null) {
                                this.sequenceIndex = context.sequenceIndex;
                                this.rawFrame = context.rawFrameIndex;
                                this.refPixel = new int[]{context.refX, context.refY};
                                this.collisionBounds = new int[]{context.collisionX, context.collisionY, context.collisionW, context.collisionH};
                        } else {
                                this.sequenceIndex = null;
                                this.rawFrame = null;
                                this.refPixel = null;
                                this.collisionBounds = null;
                        }
                }

                void appendJson(final StringBuilder sb) {
                        sb.append('{');
                        boolean first = true;
                        if (imageId != null) {
                                first = false;
                                appendJsonString(sb, "image_id");
                                sb.append(':');
                                appendJsonString(sb, imageId);
                        }
                        first = appendIntArrayField(sb, "source", source, first);
                        first = appendIntArrayField(sb, "destination", destination, first);
                        first = appendIntField(sb, "transform", transform, first);
                        first = appendIntField(sb, "anchor", anchor, first);
                        first = appendIntegerField(sb, "sequence_index", sequenceIndex, first);
                        first = appendIntArrayField(sb, "ref_pixel", refPixel, first);
                        first = appendIntegerField(sb, "raw_frame", rawFrame, first);
                        appendIntArrayField(sb, "collision_bounds", collisionBounds, first);
                        sb.append('}');
                }
        }

        private static boolean appendIntArrayField(final StringBuilder sb, final String name, final int[] values, boolean first) {
                if (values == null) {
                        return first;
                }
                if (!first) {
                        sb.append(',');
                }
                appendJsonString(sb, name);
                sb.append(':');
                appendIntArray(sb, values);
                return false;
        }

        private static boolean appendIntField(final StringBuilder sb, final String name, final int value, boolean first) {
                if (!first) {
                        sb.append(',');
                }
                appendJsonString(sb, name);
                sb.append(':');
                sb.append(value);
                return false;
        }

        private static boolean appendIntegerField(final StringBuilder sb, final String name, final Integer value, boolean first) {
                if (value == null) {
                        return first;
                }
                if (!first) {
                        sb.append(',');
                }
                appendJsonString(sb, name);
                sb.append(':');
                sb.append(value.intValue());
                return false;
        }

        private static final class SpriteContext {
                final int refX;
                final int refY;
                final int sequenceIndex;
                final int rawFrameIndex;
                final int collisionX;
                final int collisionY;
                final int collisionW;
                final int collisionH;

                SpriteContext(final int refX,
                              final int refY,
                              final int sequenceIndex,
                              final int rawFrameIndex,
                              final int collisionX,
                              final int collisionY,
                              final int collisionW,
                              final int collisionH) {
                        this.refX = refX;
                        this.refY = refY;
                        this.sequenceIndex = sequenceIndex;
                        this.rawFrameIndex = rawFrameIndex;
                        this.collisionX = collisionX;
                        this.collisionY = collisionY;
                        this.collisionW = collisionW;
                        this.collisionH = collisionH;
                }
        }
}
