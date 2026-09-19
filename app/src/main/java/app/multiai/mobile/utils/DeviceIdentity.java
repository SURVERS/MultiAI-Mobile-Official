package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/**
 * Стабильный идентификатор установки приложения.
 * Генерируется один раз при первом запуске и живёт в SharedPreferences —
 * переживает обновления приложения, но не полную переустановку (по дизайну:
 * сервер дополнительно связывает устройство по аккаунту и имени модели).
 */
public final class DeviceIdentity {

    private static final String PREFS = "multiai_device_identity";
    private static final String KEY_DEVICE_ID = "device_id";

    private static volatile String cachedId;

    private DeviceIdentity() {}

    public static synchronized String getDeviceId(Context ctx) {
        if (cachedId != null) return cachedId;
        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        cachedId = id;
        return id;
    }

    /** «Tecno KL7» — человекочитаемое имя для админки. */
    public static String getDeviceName() {
        String model = Build.MODEL != null ? Build.MODEL : "";
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        }
        return (capitalize(manufacturer) + " " + model).trim();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
