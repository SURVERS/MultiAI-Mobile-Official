package app.multiai.mobile.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import androidx.core.graphics.PathParser;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.multiai.mobile.R;

/** Lightweight SVG reader for the small vscode-icons assets bundled in res/raw. */
public final class FileIconDrawable extends Drawable {
    private static final int DEFAULT_COLOR = Color.rgb(197, 197, 197);
    private final List<Shape> shapes = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private float viewWidth = 32f;
    private float viewHeight = 32f;

    private static final class Shape {
        final Path path;
        final int color;
        Shape(Path path, int color) { this.path = path; this.color = color; }
    }

    public static FileIconDrawable from(Context context, int rawRes) {
        FileIconDrawable drawable = new FileIconDrawable();
        try (InputStream stream = context.getResources().openRawResource(rawRes)) {
            drawable.parse(stream);
        } catch (Exception ignored) {
            drawable.shapes.clear();
        }
        return drawable;
    }

    /** Цветная vscode-иконка по имени файла (расширению). Единый источник для тулов и раздела Файлы. */
    public static FileIconDrawable forName(Context context, String name) {
        return from(context, rawForName(context, name));
    }

    /** raw-ресурс иконки по имени файла; fallback — fileicon_default. */
    public static int rawForName(Context context, String name) {
        String key = iconKey(name);
        int id = context.getResources().getIdentifier(
                "fileicon_" + key, "raw", context.getPackageName());
        if (id == 0) id = R.raw.fileicon_default;
        return id;
    }

    /** Ключ иконки по расширению файла. */
    public static String iconKey(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.equals("dockerfile") || lower.endsWith(".dockerfile")) return "docker";
        if (lower.endsWith(".mjs") || lower.endsWith(".cjs") || lower.endsWith(".jsx")
                || lower.endsWith(".js")) return "js";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".d.ts")) return "typescript";
        if (lower.endsWith(".java") || lower.endsWith(".jar")) return "java";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "kotlin";
        if (lower.endsWith(".xml") || lower.endsWith(".axml")) return "xml";
        if (lower.endsWith(".json") || lower.endsWith(".json5") || lower.endsWith(".jsonl")) return "json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".py") || lower.endsWith(".pyw")) return "python";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "markdown";
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "shell";
        if (lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")) return "gradle";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) return "css";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "c";
        if (lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".cxx")
                || lower.endsWith(".hpp")) return "cpp";
        if (lower.endsWith(".toml")) return "toml";
        if (lower.endsWith(".ini") || lower.endsWith(".conf") || lower.endsWith(".properties")) return "ini";
        if (lower.endsWith(".svg")) return "svg";
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp|ico)$")) return "image";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text";
        return "default";
    }

    private void parse(InputStream stream) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, "UTF-8");
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) continue;
            String tag = parser.getName().toLowerCase(Locale.ROOT);
            if ("svg".equals(tag)) {
                String viewBox = parser.getAttributeValue(null, "viewBox");
                if (viewBox != null) {
                    String[] values = viewBox.trim().split("[ ,]+", 4);
                    if (values.length == 4) {
                        viewWidth = parseFloat(values[2], 32f);
                        viewHeight = parseFloat(values[3], 32f);
                    }
                }
            } else if ("path".equals(tag)) {
                String data = parser.getAttributeValue(null, "d");
                if (data != null && !data.trim().isEmpty()) add(PathParser.createPathFromPathData(data), fill(parser));
            } else if ("polygon".equals(tag) || "polyline".equals(tag)) {
                Path path = pointsPath(parser.getAttributeValue(null, "points"));
                if (path != null) add(path, fill(parser));
            } else if ("rect".equals(tag)) {
                float x = parseFloat(parser.getAttributeValue(null, "x"), 0);
                float y = parseFloat(parser.getAttributeValue(null, "y"), 0);
                float w = parseFloat(parser.getAttributeValue(null, "width"), 0);
                float h = parseFloat(parser.getAttributeValue(null, "height"), 0);
                Path path = new Path();
                path.addRect(x, y, x + w, y + h, Path.Direction.CW);
                add(path, fill(parser));
            } else if ("circle".equals(tag)) {
                float cx = parseFloat(parser.getAttributeValue(null, "cx"), 0);
                float cy = parseFloat(parser.getAttributeValue(null, "cy"), 0);
                float r = parseFloat(parser.getAttributeValue(null, "r"), 0);
                Path path = new Path();
                path.addCircle(cx, cy, r, Path.Direction.CW);
                add(path, fill(parser));
            }
        }
    }

    private void add(Path path, int color) {
        if (path != null) shapes.add(new Shape(path, color));
    }

    private static Path pointsPath(String points) {
        if (points == null) return null;
        String[] values = points.trim().split("[ ,]+", -1);
        Path path = new Path();
        boolean first = true;
        for (int i = 0; i + 1 < values.length; i += 2) {
            float x = parseFloat(values[i], 0);
            float y = parseFloat(values[i + 1], 0);
            if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
        }
        if (!first) path.close();
        return first ? null : path;
    }

    private static float parseFloat(String value, float fallback) {
        try { return value == null ? fallback : Float.parseFloat(value); }
        catch (Exception ignored) { return fallback; }
    }

    private static int fill(XmlPullParser parser) {
        String value = parser.getAttributeValue(null, "fill");
        String style = parser.getAttributeValue(null, "style");
        if ((value == null || value.isEmpty()) && style != null) {
            for (String part : style.split(";")) {
                String[] pair = part.split(":", 2);
                if (pair.length == 2 && "fill".equalsIgnoreCase(pair[0].trim())) value = pair[1].trim();
            }
        }
        if (value == null || value.isEmpty() || value.startsWith("url(")) return DEFAULT_COLOR;
        try { return Color.parseColor(value); } catch (Exception ignored) { return DEFAULT_COLOR; }
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float sx = bounds.width() / viewWidth;
        float sy = bounds.height() / viewHeight;
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(sx, sy);
        for (Shape shape : shapes) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(shape.color);
            canvas.drawPath(shape.path, paint);
        }
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 20; }
    @Override public int getIntrinsicHeight() { return 20; }
}
