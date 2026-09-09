package app.multiai.mobile.ui;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import app.multiai.mobile.R;

/**
 * QR-сканер — поведение и отрисовка скопированы с Telegram CameraScanActivity
 * (TMessagesProj/src/main/java/org/telegram/ui/CameraScanActivity.java).
 *
 * Камера: CameraX Preview + ImageAnalysis в 1920x1080 (на 720p дальний QR
 * даёт слишком мало пикселей на модуль; частоту держит KEEP_ONLY_LATEST).
 * Распознавание: ML Kit BarcodeScanner, режим QR_CODE, с автозумом на мелкий код.
 * Рамка: shadow + белые углы (arcTo/aroundPoint) 1:1 из Telegram drawChild.
 */
public class CameraScanActivity extends AppCompatActivity {

    private static final String TAG = "CameraScan";

    public static final String EXTRA_RESULT = "qr_result";

    /** Telegram: CubicBezierInterpolator.DEFAULT. */
    private static final PathInterpolator DEFAULT_INTERPOLATOR =
            new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    // ── Отрисовка (из Telegram) ──
    private final Paint paint = new Paint();
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private SpringAnimation qrAppearing = null;
    private float qrAppearingValue = 0;
    private float backShadowAlpha = 0.5f;

    private final PointF[] fromPoints = new PointF[4];
    private final PointF[] points = new PointF[4];
    {
        for (int i = 0; i < 4; ++i) {
            fromPoints[i] = new PointF(-1, -1);
            points[i] = new PointF(-1, -1);
        }
    }

    private final RectF fromBounds = new RectF();
    private final RectF bounds = new RectF();
    private long lastBoundsUpdate = 0;
    private final long boundsUpdateDuration = 75;

    private RectF normalBounds;
    private ValueAnimator recognizedAnimator;
    private float recognizedT = 0;
    private float newRecognizedT = 0;
    private SpringAnimation useRecognizedBoundsAnimator;
    private float useRecognizedBounds = 0;

    // ── Состояние распознавания (из Telegram) ──
    private volatile boolean recognized = false;
    private long recognizedStart = 0;
    private int recognizeFailed = 0;
    private int stableDetectionCount = 0;
    private String recognizedText;
    private volatile boolean hasResult = false;

    /** Два одинаковых decode либо короткая стабилизация — без декоративной секундной паузы. */
    private static final int STABLE_RESULT_FRAMES = 2;
    private static final long STABLE_RESULT_HOLD_MS = 120;

    /**
     * Потолок автозума ML Kit. Для QR с 10–20 м нужен весь доступный зум камеры,
     * поэтому ограничение задрано высоко: фактический предел = maxZoomRatio камеры
     * (обычно 8–30x). ML Kit сам приближает, когда видит QR-подобный паттерн, но
     * ещё не может его декодировать — раньше 12x упирался в кап на дальнем коде.
     */
    private static final float MAX_SCAN_ZOOM = 30f;

    /**
     * Доля кадра под AF/AE. Широкая зона: QR может быть в любом углу кадра,
     * а не только внутри рамки, и узкий AF по центру такой код не вытягивал.
     */
    private static final float FOCUS_REGION_SIZE = 0.85f;
    private static final java.util.concurrent.Executor DIRECT_EXECUTOR = Runnable::run;

    // ── Камера ──
    private PreviewView previewView;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private volatile BarcodeScanner barcodeScanner;
    private androidx.camera.core.Camera camera;
    private float maxZoomRatio = 1f;
    private long lastZoomApply = 0;
    private volatile boolean zoomInFlight = false;
    private volatile boolean focusInFlight = false;
    private boolean focusLoopEnabled = false;
    private boolean torchInFlight = false;
    private boolean flashAvailable = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── UI ──
    private ImageView flashButton;
    private ImageView closeButton;
    private TextView titleTextView;
    private View overlayView;
    private boolean torchEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        paint.setColor(0x7f000000);
        cornerPaint.setColor(0xffffffff);
        cornerPaint.setStyle(Paint.Style.FILL);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        setContentView(createRootView());

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            // post() — чтобы PreviewView успел получить размер: без него нет ViewPort,
            // а без ViewPort координаты QR не сходятся с картинкой на экране.
            previewView.post(this::startCamera);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 100);
        }
    }

    private View createRootView() {
        FrameLayout root = new FrameLayout(this) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                int width = r - l;
                int height = b - t;
                if (width <= 0 || height <= 0) return;

                // Геометрия из Telegram onLayout: квадрат min(w,h)/1.5 по центру,
                // подпись над ним (-dp(64)), фонарик под ним (+dp(80)).
                int size = (int) (min(width, height) / 1.5f);
                int y = (height - size) / 2 - titleTextView.getMeasuredHeight() - dp(64);
                titleTextView.layout(dp(36), y,
                        dp(36) + titleTextView.getMeasuredWidth(), y + titleTextView.getMeasuredHeight());

                int fx = width / 2 - flashButton.getMeasuredWidth() / 2;
                int fy = (height - size) / 2 + size + dp(80);
                flashButton.layout(fx, fy,
                        fx + flashButton.getMeasuredWidth(), fy + flashButton.getMeasuredHeight());

                updateNormalBounds(width, height);
            }
        };
        root.setBackgroundColor(Color.BLACK);

        // Превью камеры
        previewView = new PreviewView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // COMPATIBLE (TextureView) — надёжнее на всех устройствах.
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView);

        // Оверлей — затемнение + углы рамки
        overlayView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                drawOverlay(canvas);
            }
        };
        overlayView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlayView);

        // Подпись
        titleTextView = new TextView(this);
        titleTextView.setText(getString(R.string.scr_sec_link_scan_hint));
        titleTextView.setTextColor(0xffffffff);
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleTextView.setLineSpacing(dp(2), 1.0f);
        titleTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = dp(36);
        titleLp.rightMargin = dp(36);
        root.addView(titleTextView, titleLp);

        // Закрыть
        closeButton = new ImageView(this);
        closeButton.setImageResource(R.drawable.ic_close_white);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setBackground(createCircleDrawable(dp(34), 0x28000000, 0x28FFFFFF));
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(34), dp(34));
        closeLp.leftMargin = dp(14);
        closeLp.topMargin = dp(14);
        closeLp.gravity = Gravity.TOP | Gravity.START;
        root.addView(closeButton, closeLp);
        closeButton.setOnClickListener(v -> cancel());

        // Фонарик (позицию считает onLayout, как в Telegram)
        flashButton = new ImageView(this);
        flashButton.setImageResource(R.drawable.qr_flashlight);
        flashButton.setScaleType(ImageView.ScaleType.CENTER);
        flashButton.setBackground(createCircleDrawable(dp(60), 0x22FFFFFF, 0x44FFFFFF));
        root.addView(flashButton, new FrameLayout.LayoutParams(dp(60), dp(60)));
        flashButton.setOnClickListener(v -> toggleTorch());

        return root;
    }

    // ═══════════════════════════════════════════════════════════════
    // Отрисовка рамки (1:1 Telegram drawChild)
    // ═══════════════════════════════════════════════════════════════

    private void drawOverlay(Canvas canvas) {
        int w = overlayView.getWidth();
        int h = overlayView.getHeight();
        if (w <= 0 || h <= 0) return;

        RectF b = getBounds(w, h);
        int sizex = (int) (w * b.width());
        int sizey = (int) (h * b.height());
        int cx = (int) (w * b.centerX());
        int cy = (int) (h * b.centerY());

        sizex *= (0.5f + qrAppearingValue * 0.5f);
        sizey *= (0.5f + qrAppearingValue * 0.5f);
        int x = cx - sizex / 2;
        int y = cy - sizey / 2;

        // Затемнение
        paint.setAlpha((int) (255 * (1f - (1f - backShadowAlpha) * min(1, qrAppearingValue))));
        canvas.drawRect(0, 0, w, y, paint);
        canvas.drawRect(0, y + sizey, w, h, paint);
        canvas.drawRect(0, y, x, y + sizey, paint);
        canvas.drawRect(x + sizex, y, w, y + sizey, paint);
        paint.setAlpha((int) (255 * max(0, 1f - qrAppearingValue)));
        canvas.drawRect(x, y, x + sizex, y + sizey, paint);

        // Углы
        final int lineWidth = lerp(0, dp(4), min(1, qrAppearingValue * 20f));
        final int halfLineWidth = lineWidth / 2;
        final int lineLength = lerp(min(sizex, sizey), dp(20),
                min(1.2f, (float) Math.pow(qrAppearingValue, 1.8f)));

        cornerPaint.setAlpha((int) (255 * min(1, qrAppearingValue)));

        path.reset();
        path.arcTo(aroundPoint(x, y + lineLength, halfLineWidth), 0, 180);
        path.arcTo(aroundPoint((int) (x + lineWidth * 1.5f), (int) (y + lineWidth * 1.5f), lineWidth * 2), 180, 90);
        path.arcTo(aroundPoint(x + lineLength, y, halfLineWidth), 270, 180);
        path.lineTo(x + halfLineWidth, y + halfLineWidth);
        path.arcTo(aroundPoint((int) (x + lineWidth * 1.5f), (int) (y + lineWidth * 1.5f), lineWidth), 270, -90);
        path.close();
        canvas.drawPath(path, cornerPaint);

        path.reset();
        path.arcTo(aroundPoint(x + sizex, y + lineLength, halfLineWidth), 180, -180);
        path.arcTo(aroundPoint((int) (x + sizex - lineWidth * 1.5f), (int) (y + lineWidth * 1.5f), lineWidth * 2), 0, -90);
        path.arcTo(aroundPoint(x + sizex - lineLength, y, halfLineWidth), 270, -180);
        path.arcTo(aroundPoint((int) (x + sizex - lineWidth * 1.5f), (int) (y + lineWidth * 1.5f), lineWidth), 270, 90);
        path.close();
        canvas.drawPath(path, cornerPaint);

        path.reset();
        path.arcTo(aroundPoint(x, y + sizey - lineLength, halfLineWidth), 0, -180);
        path.arcTo(aroundPoint((int) (x + lineWidth * 1.5f), (int) (y + sizey - lineWidth * 1.5f), lineWidth * 2), 180, -90);
        path.arcTo(aroundPoint(x + lineLength, y + sizey, halfLineWidth), 90, -180);
        path.arcTo(aroundPoint((int) (x + lineWidth * 1.5f), (int) (y + sizey - lineWidth * 1.5f), lineWidth), 90, 90);
        path.close();
        canvas.drawPath(path, cornerPaint);

        path.reset();
        path.arcTo(aroundPoint(x + sizex, y + sizey - lineLength, halfLineWidth), 180, 180);
        path.arcTo(aroundPoint((int) (x + sizex - lineWidth * 1.5f), (int) (y + sizey - lineWidth * 1.5f), lineWidth * 2), 0, 90);
        path.arcTo(aroundPoint(x + sizex - lineLength, y + sizey, halfLineWidth), 90, 180);
        path.arcTo(aroundPoint((int) (x + sizex - lineWidth * 1.5f), (int) (y + sizey - lineWidth * 1.5f), lineWidth), 90, -90);
        path.close();
        canvas.drawPath(path, cornerPaint);
    }

    private final RectF tmpRect = new RectF();

    private RectF aroundPoint(int x, int y, int r) {
        tmpRect.set(x - r, y - r, x + r, y + r);
        return tmpRect;
    }

    // ═══════════════════════════════════════════════════════════════
    // Bounds рамки (1:1 Telegram)
    // ═══════════════════════════════════════════════════════════════

    private void updateNormalBounds(int w, int h) {
        if (normalBounds == null) normalBounds = new RectF();
        int side = (int) (min(w, h) / 1.5f);
        normalBounds.set(
                (w - side) / 2f / w,
                (h - side) / 2f / h,
                (w + side) / 2f / w,
                (h + side) / 2f / h
        );
    }

    private RectF getBounds(int w, int h) {
        RectF recognizedBounds = getRecognizedBounds();
        if (useRecognizedBounds < 1f) {
            if (normalBounds == null) updateNormalBounds(w, h);
            lerp(normalBounds, recognizedBounds, useRecognizedBounds, recognizedBounds);
        }
        return recognizedBounds;
    }

    private final RectF boundsTmp = new RectF();

    private RectF getRecognizedBounds() {
        float t = (SystemClock.elapsedRealtime() - lastBoundsUpdate) / (float) boundsUpdateDuration;
        t = min(1, max(0, t));
        if (t < 1f && overlayView != null) {
            // Telegram инвалидирует вьюху пока идёт интерполяция — иначе рамка замирает.
            overlayView.postInvalidateOnAnimation();
        }
        lerp(fromBounds, bounds, t, boundsTmp);
        return boundsTmp;
    }

    private void updateRecognizedBounds(RectF newBounds, PointF[] newPoints) {
        final long now = SystemClock.elapsedRealtime();
        if (lastBoundsUpdate == 0) {
            lastBoundsUpdate = now - boundsUpdateDuration;
            bounds.set(newBounds);
            fromBounds.set(newBounds);
            if (newPoints == null) {
                setPointsFromBounds(newBounds, fromPoints);
                setPointsFromBounds(newBounds, points);
            } else {
                for (int i = 0; i < 4; i++) {
                    fromPoints[i].set(newPoints[i].x, newPoints[i].y);
                    points[i].set(newPoints[i].x, newPoints[i].y);
                }
            }
        } else {
            if (now - lastBoundsUpdate < boundsUpdateDuration) {
                float t = (now - lastBoundsUpdate) / (float) boundsUpdateDuration;
                t = min(1, max(0, t));
                lerp(fromBounds, bounds, t, fromBounds);
                for (int i = 0; i < 4; ++i) {
                    fromPoints[i].set(lerp(fromPoints[i].x, points[i].x, t),
                            lerp(fromPoints[i].y, points[i].y, t));
                }
            } else {
                fromBounds.set(bounds);
                for (int i = 0; i < 4; ++i) fromPoints[i].set(points[i].x, points[i].y);
            }
            bounds.set(newBounds);
            if (newPoints == null) {
                setPointsFromBounds(bounds, points);
            } else {
                for (int i = 0; i < 4; ++i) points[i].set(newPoints[i].x, newPoints[i].y);
            }
            lastBoundsUpdate = now;
        }
        if (overlayView != null) overlayView.invalidate();
    }

    private void setPointsFromBounds(RectF b, PointF[] pts) {
        pts[0].set(b.left, b.top);
        pts[1].set(b.right, b.top);
        pts[2].set(b.right, b.bottom);
        pts[3].set(b.left, b.bottom);
    }

    /** Telegram updateRecognized(): гасит подпись/фонарик, усиливает затемнение, тянет рамку к QR. */
    private void updateRecognized() {
        float wasNewRecognizedT = recognizedT;
        newRecognizedT = recognized ? 1f : 0f;
        if (wasNewRecognizedT != newRecognizedT) {
            if (recognizedAnimator != null) recognizedAnimator.cancel();
        } else {
            return;
        }

        recognizedAnimator = ValueAnimator.ofFloat(recognizedT, newRecognizedT);
        recognizedAnimator.addUpdateListener(a -> {
            recognizedT = (float) a.getAnimatedValue();
            titleTextView.setAlpha(1f - recognizedT);
            flashButton.setAlpha(1f - recognizedT);
            backShadowAlpha = 0.5f + recognizedT * 0.25f;
            if (overlayView != null) overlayView.invalidate();
        });
        recognizedAnimator.setDuration((long) (300 * Math.abs(recognizedT - newRecognizedT)));
        recognizedAnimator.setInterpolator(DEFAULT_INTERPOLATOR);
        recognizedAnimator.start();

        if (useRecognizedBoundsAnimator != null) useRecognizedBoundsAnimator.cancel();
        final float force = 500f;
        useRecognizedBoundsAnimator = new SpringAnimation(new FloatValueHolder(
                (recognized ? useRecognizedBounds : 1f - useRecognizedBounds) * force));
        useRecognizedBoundsAnimator.addUpdateListener((animation, value, velocity) -> {
            useRecognizedBounds = recognized ? value / force : (1f - value / force);
            if (overlayView != null) overlayView.invalidate();
        });
        useRecognizedBoundsAnimator.setSpring(new SpringForce(force));
        useRecognizedBoundsAnimator.getSpring().setDampingRatio(1f);
        useRecognizedBoundsAnimator.getSpring().setStiffness(500.0f);
        useRecognizedBoundsAnimator.start();
    }

    // ═══════════════════════════════════════════════════════════════
    // CameraX
    // ═══════════════════════════════════════════════════════════════

    @SuppressLint("RestrictedApi")
    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                cameraProvider = ProcessCameraProvider.getInstance(this).get();

                // Превью — 16:9, максимальный доступный размер.
                Preview preview = new Preview.Builder()
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                .build())
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Анализ — 1920x1080. На 720p дальний QR занимает слишком мало пикселей
                // на модуль, чтобы декодироваться; частоту кадров держит KEEP_ONLY_LATEST.
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(new ResolutionStrategy(new Size(1920, 1080),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::processFrame);

                UseCaseGroup.Builder group = new UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(analysis);
                // ViewPort приравнивает область анализа к тому, что реально видно
                // в PreviewView — без него координаты QR не совпадают с картинкой.
                ViewPort viewPort = previewView.getViewPort();
                if (viewPort != null) group.setViewPort(viewPort);

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, group.build());

                if (camera.getCameraInfo().getZoomState().getValue() != null) {
                    maxZoomRatio = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
                }
                flashAvailable = camera.getCameraInfo().hasFlashUnit();
                flashButton.setEnabled(flashAvailable);
                flashButton.setVisibility(flashAvailable ? View.VISIBLE : View.GONE);
                barcodeScanner = BarcodeScanning.getClient(buildScannerOptions());

                // Периодически обновляем AF/AE только пока scanner видим.
                if (focusLoopEnabled) {
                    mainHandler.removeCallbacks(focusLoop);
                    mainHandler.post(focusLoop);
                }

                if (qrAppearing != null) qrAppearing.cancel();
                qrAppearing = new SpringAnimation(new FloatValueHolder(0));
                qrAppearing.addUpdateListener((animation, value, velocity) -> {
                    qrAppearingValue = value / 500f;
                    if (overlayView != null) overlayView.invalidate();
                });
                qrAppearing.setSpring(new SpringForce(500f));
                qrAppearing.getSpring().setDampingRatio(0.8f);
                qrAppearing.getSpring().setStiffness(250.0f);
                qrAppearing.start();
            } catch (Exception e) {
                android.util.Log.e(TAG, "startCamera failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Автозум ML Kit: когда код в кадре есть, но слишком мелкий для декодирования,
     * ML Kit сам просит нужную кратность — так сканируется QR с расстояния.
     */
    private BarcodeScannerOptions buildScannerOptions() {
        BarcodeScannerOptions.Builder builder = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE);
        float allowedZoom = min(maxZoomRatio, MAX_SCAN_ZOOM);
        if (allowedZoom > 1.05f) {
            builder.setZoomSuggestionOptions(new ZoomSuggestionOptions.Builder(this::applySuggestedZoom)
                    .setMaxSupportedZoomRatio(allowedZoom)
                    .build());
        }
        return builder.build();
    }

    private boolean applySuggestedZoom(float zoomRatio) {
        if (hasResult || camera == null || zoomInFlight) return false;
        long now = SystemClock.elapsedRealtime();
        // Быстрый debounce: ML Kit может слать подсказки часто, но линза не должна дёргаться.
        if (now - lastZoomApply < 150) return false;
        final float target = min(max(zoomRatio, 1f), min(maxZoomRatio, MAX_SCAN_ZOOM));
        lastZoomApply = now;
        zoomInFlight = true;
        mainHandler.post(() -> {
            androidx.camera.core.Camera activeCamera = camera;
            if (activeCamera == null || hasResult) {
                zoomInFlight = false;
                return;
            }
            try {
                com.google.common.util.concurrent.ListenableFuture<Void> future =
                        activeCamera.getCameraControl().setZoomRatio(target);
                future.addListener(() -> {
                    try {
                        future.get();
                    } catch (Exception ignored) {
                        // Следующая подсказка ML Kit сможет повторить zoom.
                    } finally {
                        zoomInFlight = false;
                    }
                }, DIRECT_EXECUTOR);
            } catch (Exception ignored) {
                zoomInFlight = false;
            }
        });
        return true;
    }

    // ── Последние известные координаты QR для фокуса на код вне рамки ──
    private volatile float lastQrCenterX = -1f;
    private volatile float lastQrCenterY = -1f;

    private final Runnable focusLoop = new Runnable() {
        @Override
        public void run() {
            if (!focusLoopEnabled || hasResult || isFinishing() || isDestroyed()) return;
            if (!focusInFlight) {
                // Если QR обнаружен (есть координаты) — фокусируем на него,
                // даже если он за пределами рамки. Как в Telegram.
                if (lastQrCenterX >= 0 && lastQrCenterY >= 0) {
                    focusToPoint(lastQrCenterX, lastQrCenterY);
                } else if (!recognized) {
                    focusToCenter();
                }
            }
            mainHandler.postDelayed(this, 700);
        }
    };

    private void focusToCenter() {
        if (camera == null || previewView.getWidth() <= 0 || focusInFlight) return;
        focusToPoint(previewView.getWidth() / 2f, previewView.getHeight() / 2f);
    }

    /**
     * Фокус на произвольную точку экрана. Когда QR обнаружен за пределами рамки,
     * фокусировка смещается к нему — так автофокус вытягивает код на краю кадра.
     */
    private void focusToPoint(float x, float y) {
        if (camera == null || previewView.getWidth() <= 0 || focusInFlight) return;
        try {
            MeteringPointFactory factory = previewView.getMeteringPointFactory();
            MeteringPoint point = factory.createPoint(x, y, FOCUS_REGION_SIZE);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(1200, TimeUnit.MILLISECONDS)
                    .build();
            focusInFlight = true;
            com.google.common.util.concurrent.ListenableFuture<androidx.camera.core.FocusMeteringResult>
                    future = camera.getCameraControl().startFocusAndMetering(action);
            future.addListener(() -> focusInFlight = false, DIRECT_EXECUTOR);
        } catch (Exception ignored) {
            focusInFlight = false;
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void processFrame(@NonNull ImageProxy image) {
        BarcodeScanner scanner = barcodeScanner;
        if (hasResult || scanner == null || image.getImage() == null) {
            image.close();
            return;
        }
        final int rotation = image.getImageInfo().getRotationDegrees();
        final int bufferW = image.getWidth();
        final int bufferH = image.getHeight();
        final Rect crop = new Rect(image.getCropRect());
        try {
            InputImage inputImage = InputImage.fromMediaImage(image.getImage(), rotation);
            scanner.process(inputImage)
                    .addOnSuccessListener(DIRECT_EXECUTOR, barcodes ->
                            mainHandler.post(() -> onFrameScanned(
                                    barcodes, rotation, bufferW, bufferH, crop)))
                    .addOnFailureListener(DIRECT_EXECUTOR, error ->
                            mainHandler.post(() -> onFrameScanned(
                                    null, rotation, bufferW, bufferH, crop)))
                    // Закрываем буфер сразу на completion thread, не ждём очередь main UI.
                    .addOnCompleteListener(DIRECT_EXECUTOR, task -> image.close());
        } catch (Exception e) {
            image.close();
        }
    }

    /** Результат ML Kit переносится сюда на main thread; QR payload нигде не логируется. */
    private void onFrameScanned(List<Barcode> barcodes, int rotation,
                                int bufferW, int bufferH, Rect crop) {
        if (hasResult || isFinishing() || isDestroyed()) return;

        String text = null;
        RectF codeBounds = null;
        PointF[] cornerPoints = null;

        if (barcodes != null) {
            for (Barcode code : barcodes) {
                String raw = code.getRawValue();
                if (raw == null || raw.isEmpty()) continue;

                // Decode уже валиден, даже если декоративные corner points не удалось
                // переложить в координаты PreviewView.
                text = raw;
                Point[] cps = code.getCornerPoints();
                if (cps != null && cps.length == 4) {
                    PointF[] mapped = mapToViewNormalized(cps, rotation, bufferW, bufferH, crop);
                    if (mapped != null) {
                        cornerPoints = mapped;
                        codeBounds = boundsOf(mapped);
                    }
                }
                break;
            }
        }

        long now = SystemClock.elapsedRealtime();
        if (text != null) {
            boolean sameCandidate = text.equals(recognizedText);
            if (sameCandidate) {
                stableDetectionCount++;
            } else {
                recognizedText = text;
                recognizedStart = now;
                stableDetectionCount = 1;
            }
            recognizeFailed = 0;

            // Запоминаем координаты QR для AF — фокус будет наводиться на код,
            // даже если он за пределами рамки (как в Telegram).
            if (codeBounds != null) {
                float viewW = overlayView.getWidth();
                float viewH = overlayView.getHeight();
                if (viewW > 0 && viewH > 0) {
                    lastQrCenterX = codeBounds.centerX() * viewW;
                    lastQrCenterY = codeBounds.centerY() * viewH;
                }
            }

            if (!recognized) {
                recognized = true;
                // Если mapping углов недоступен, рамка остаётся в обычном квадрате,
                // но валидный decoded text всё равно проходит debounce.
                if (codeBounds == null && normalBounds != null) {
                    updateRecognizedBounds(new RectF(normalBounds), null);
                }
                updateRecognized();
            }
            if (codeBounds != null) {
                updateRecognizedBounds(codeBounds, cornerPoints);
            }
        } else if (recognized) {
            recognizeFailed++;
            stableDetectionCount = 0;
            if (recognizeFailed > 4) {
                recognized = false;
                stableDetectionCount = 0;
                recognizedText = null;
                lastQrCenterX = -1f;
                lastQrCenterY = -1f;
                updateRecognized();
                return;
            }
        }

        boolean stableResult = text != null && recognized && recognizedText != null
                && text.equals(recognizedText)
                && (stableDetectionCount >= STABLE_RESULT_FRAMES
                || now - recognizedStart >= STABLE_RESULT_HOLD_MS);
        if (stableResult) finishWithResult(recognizedText);
    }

    /**
     * Углы QR из системы координат ML Kit (изображение, повёрнутое в upright)
     * в нормализованные координаты вьюхи с учётом кропа превью (FILL_CENTER).
     * Без этого пересчёта рамка улетала: ML Kit отдаёт точки в повёрнутом кадре,
     * а нормализация шла по размерам неповёрнутого буфера.
     */
    private PointF[] mapToViewNormalized(Point[] cps, int rotation,
                                         int bufferW, int bufferH, Rect crop) {
        final float viewW = overlayView.getWidth();
        final float viewH = overlayView.getHeight();
        if (viewW <= 0 || viewH <= 0) return null;

        RectF uCrop = new RectF();
        rotateRect(crop, bufferW, bufferH, rotation, uCrop);
        if (uCrop.width() <= 0 || uCrop.height() <= 0) return null;

        // PreviewView FILL_CENTER: видимая область масштабируется «по большему».
        float scale = max(viewW / uCrop.width(), viewH / uCrop.height());
        float dx = (viewW - uCrop.width() * scale) / 2f;
        float dy = (viewH - uCrop.height() * scale) / 2f;

        PointF[] out = new PointF[4];
        float cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) {
            float vx = (cps[i].x - uCrop.left) * scale + dx;
            float vy = (cps[i].y - uCrop.top) * scale + dy;
            out[i] = new PointF(vx / viewW, vy / viewH);
            cx += out[i].x / 4f;
            cy += out[i].y / 4f;
        }
        // Код у самого края кадра раньше отбрасывался целиком, и рамка не двигалась
        // к нему вовсе. Теперь точки подтягиваются в пределы вьюхи: рамка доезжает
        // до кода, оставаясь на экране. Совсем бессмысленные координаты (код вне
        // кадра более чем на его размер) по-прежнему игнорируем.
        if (cx < -0.5f || cx > 1.5f || cy < -0.5f || cy > 1.5f) return null;
        for (PointF p : out) {
            p.x = min(max(p.x, 0f), 1f);
            p.y = min(max(p.y, 0f), 1f);
        }
        return out;
    }

    /** Прямоугольник буфера → в систему координат upright-изображения. */
    private static void rotateRect(Rect src, int bufW, int bufH, int rotation, RectF out) {
        switch (rotation) {
            case 90:
                out.set(bufH - src.bottom, src.left, bufH - src.top, src.right);
                break;
            case 180:
                out.set(bufW - src.right, bufH - src.bottom, bufW - src.left, bufH - src.top);
                break;
            case 270:
                out.set(src.top, bufW - src.right, src.bottom, bufW - src.left);
                break;
            default:
                out.set(src);
                break;
        }
    }

    /** Габариты QR + отступы dp(25)/dp(15), как в Telegram tryReadQr. */
    private RectF boundsOf(PointF[] normalizedPoints) {
        final float viewW = overlayView.getWidth();
        final float viewH = overlayView.getHeight();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (PointF p : normalizedPoints) {
            minX = min(minX, p.x * viewW);
            maxX = max(maxX, p.x * viewW);
            minY = min(minY, p.y * viewH);
            maxY = max(maxY, p.y * viewH);
        }
        int padX = dp(25), padY = dp(15);
        return new RectF(
                (minX - padX) / viewW,
                (minY - padY) / viewH,
                (maxX + padX) / viewW,
                (maxY + padY) / viewH
        );
    }

    private void finishWithResult(String text) {
        completeOnce(Activity.RESULT_OK, text);
    }

    /** Single terminal gate: a late ML callback can never overwrite user cancellation. */
    private void completeOnce(int resultCode, String text) {
        if (hasResult) return;
        hasResult = true;
        focusLoopEnabled = false;
        mainHandler.removeCallbacks(focusLoop);
        if (resultCode == Activity.RESULT_OK && text != null) {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT, text);
            setResult(resultCode, data);
        } else {
            setResult(Activity.RESULT_CANCELED);
        }
        finish();
    }

    private void toggleTorch() {
        if (camera == null || !flashAvailable || torchInFlight) return;
        final boolean target = !torchEnabled;
        torchInFlight = true;
        flashButton.setEnabled(false);
        try {
            com.google.common.util.concurrent.ListenableFuture<Void> future =
                    camera.getCameraControl().enableTorch(target);
            future.addListener(() -> {
                boolean applied = false;
                try {
                    future.get();
                    applied = true;
                } catch (Exception ignored) {
                    // Оставляем прежнее состояние, если камера отклонила запрос.
                }
                final boolean success = applied;
                mainHandler.post(() -> {
                    torchInFlight = false;
                    if (hasResult || isFinishing() || isDestroyed()) return;
                    if (success) torchEnabled = target;
                    flashButton.setSelected(torchEnabled);
                    flashButton.setEnabled(flashAvailable);
                });
            }, DIRECT_EXECUTOR);
        } catch (Exception ignored) {
            torchInFlight = false;
            flashButton.setEnabled(flashAvailable);
        }
    }

    // ── Жизненный цикл ──

    @Override
    protected void onStart() {
        super.onStart();
        focusLoopEnabled = true;
        if (camera != null && !hasResult) {
            mainHandler.removeCallbacks(focusLoop);
            mainHandler.post(focusLoop);
        }
    }

    @Override
    protected void onStop() {
        focusLoopEnabled = false;
        focusInFlight = false;
        mainHandler.removeCallbacks(focusLoop);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        focusLoopEnabled = false;
        focusInFlight = false;
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception ignored) {
            }
        }
        camera = null;
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (barcodeScanner != null) barcodeScanner.close();
        if (qrAppearing != null) qrAppearing.cancel();
        if (recognizedAnimator != null) recognizedAnimator.cancel();
        if (useRecognizedBoundsAnimator != null) useRecognizedBoundsAnimator.cancel();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            previewView.post(this::startCamera);
        } else {
            cancel();
        }
    }

    private void cancel() {
        completeOnce(Activity.RESULT_CANCELED, null);
    }

    @Override
    public void onBackPressed() {
        cancel();
        super.onBackPressed();
    }

    // ── Хелперы ──

    private int dp(float value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private int lerp(int a, int b, float t) {
        return (int) (a + (b - a) * t);
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void lerp(RectF a, RectF b, float t, RectF out) {
        out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
                a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t);
    }

    private android.graphics.drawable.Drawable createCircleDrawable(int size, int normalColor, int pressedColor) {
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.ShapeDrawable normal = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
        normal.getPaint().setColor(normalColor);
        normal.setIntrinsicWidth(size);
        normal.setIntrinsicHeight(size);
        android.graphics.drawable.ShapeDrawable pressed = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
        pressed.getPaint().setColor(pressedColor);
        pressed.setIntrinsicWidth(size);
        pressed.setIntrinsicHeight(size);
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }
}
