package app.multiai.mobile.utils;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Detects a shake gesture using the accelerometer.
 *
 * <p>Standard algorithm: track the acceleration delta against gravity; if the net
 * acceleration magnitude exceeds {@code SHAKE_THRESHOLD} (m/s²) we register one
 * shake sample. When enough samples occur within {@code SHAKE_WINDOW_MS}, the
 * callback fires. A cooldown ({@link #COOLDOWN_MS}) prevents double-firing from
 * a single shake motion.
 *
 * <p>Usage:
 * <pre>
 *   shakeDetector = new ShakeDetector(() -> showReportBugSheet());
 *   shakeDetector.register(this);   // in onCreate
 *   shakeDetector.unregister();     // in onDestroy
 * </pre>
 */
public class ShakeDetector implements SensorEventListener {

    /** Net acceleration (m/s²) above which a sample counts as a shake movement. */
    private static final float SHAKE_THRESHOLD = 14f;
    /** Sliding window in which {@code MIN_SHAKE_SAMPLES} movements must occur. */
    private static final int SHAKE_WINDOW_MS = 3000;
    /** Minimum number of shake samples within the window to trigger the callback. */
    private static final int MIN_SHAKE_SAMPLES = 6;
    /** Don't fire more than once per cooldown period. */
    private static final int COOLDOWN_MS = 3000;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private final OnShakeListener listener;

    private float lastX, lastY, lastZ;
    private boolean firstSample = true;

    private long[] shakeTimestamps = new long[MIN_SHAKE_SAMPLES * 4];
    private int shakeIndex = 0;

    private long lastFireTime = 0;
    private volatile boolean enabled = true;

    public interface OnShakeListener {
        void onShake();
    }

    public ShakeDetector(OnShakeListener listener) {
        this.listener = listener;
    }

    /** Runtime kill-switch: when false, sensor events are ignored entirely. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            shakeTimestamps = new long[MIN_SHAKE_SAMPLES * 4];
            shakeIndex = 0;
            lastFireTime = 0;
        }
    }

    /** Register with the default accelerometer. No-op if no sensor is available. */
    public void register(Context context) {
        if (sensorManager != null) return; // already registered
        sensorManager = (SensorManager) context.getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void unregister() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            sensorManager = null;
            accelerometer = null;
        }
        firstSample = true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (listener == null || event == null || !enabled) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        if (firstSample) {
            lastX = x; lastY = y; lastZ = z;
            firstSample = false;
            return;
        }

        float dx = Math.abs(x - lastX);
        float dy = Math.abs(y - lastY);
        float dz = Math.abs(z - lastZ);
        lastX = x; lastY = y; lastZ = z;

        // Ignore tiny movements (noise).
        if (dx < 1f && dy < 1f && dz < 1f) return;

        float delta = dx + dy + dz;
        if (delta > SHAKE_THRESHOLD) {
            long now = System.currentTimeMillis();
            // Roll the ring buffer of recent shake timestamps.
            shakeTimestamps[shakeIndex % MIN_SHAKE_SAMPLES] = now;
            shakeIndex++;

            // Count samples inside the sliding window.
            int count = 0;
            for (long ts : shakeTimestamps) {
                if (now - ts < SHAKE_WINDOW_MS) count++;
            }

            if (count >= MIN_SHAKE_SAMPLES) {
                // Enough movement within the window → fire (respecting cooldown).
                if (now - lastFireTime > COOLDOWN_MS) {
                    lastFireTime = now;
                    // Reset window so a continuous shake doesn't immediately re-trigger.
                    shakeTimestamps = new long[MIN_SHAKE_SAMPLES * 4];
                    shakeIndex = 0;
                    listener.onShake();
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }
}
