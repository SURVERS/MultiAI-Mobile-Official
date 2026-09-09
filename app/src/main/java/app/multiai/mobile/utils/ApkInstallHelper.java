package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Вспомогательный класс для установки APK через системный инсталлятор Android.
 *
 * <p>Единственный правильный способ: ACTION_VIEW с mime application/vnd.android.package-archive
 * через FileProvider — так же как в {@link app.multiai.mobile.ui.EditorActivity#installApk}.
 *
 * <p>Если пользователь не разрешил установку из неизвестных источников —
 * открываем экран разрешений Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES (API 26+)
 * или Settings.ACTION_SECURITY_SETTINGS на старых версиях.
 */
public final class ApkInstallHelper {

    public interface ResultCallback {
        /**
         * Вызывается в main-thread после попытки старта установщика.
         * @param message короткое сообщение для отображения в терминале
         */
        void onResult(String message);
    }

    private ApkInstallHelper() {}

    /**
     * Устанавливает APK из произвольного пути файловой системы.
     * Копирует файл в cache-директорию (FileProvider требует content:// URI),
     * затем запускает системный установщик.
     *
     * @param context  application context
     * @param activity активити для startActivity (может совпадать с context)
     * @param absPath  абсолютный путь к .apk файлу
     * @param callback результат в main-thread
     */
    public static void installApkFromPath(Context context, Activity activity,
                                          String absPath, ResultCallback callback) {
        new Thread(() -> {
            try {
                File src = new File(absPath);
                if (!src.exists()) {
                    postResult(activity, callback, "Файл не найден: " + absPath);
                    return;
                }
                if (!src.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    postResult(activity, callback, "Файл не является APK: " + src.getName());
                    return;
                }

                // Копируем в app cache (FileProvider не раздаёт произвольные пути /sdcard)
                File installDir = new File(context.getCacheDir(), "apk_installs");
                if (!installDir.exists() && !installDir.mkdirs()) {
                    postResult(activity, callback, "Не удалось создать директорию кэша");
                    return;
                }
                String safeName = src.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                if (!safeName.toLowerCase(Locale.ROOT).endsWith(".apk")) safeName += ".apk";
                File dest = new File(installDir, safeName);

                try (FileInputStream in = new FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }

                Uri apkUri = FileProvider.getUriForFile(
                        context, context.getPackageName() + ".fileprovider", dest);

                Runnable launch = () -> {
                    // Проверяем разрешение на установку из неизвестных источников (API 26+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        if (!context.getPackageManager().canRequestPackageInstalls()) {
                            // Открываем настройки разрешений
                            Intent permIntent = new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + context.getPackageName()));
                            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                context.startActivity(permIntent);
                                if (callback != null) {
                                    callback.onResult("Разреши установку из неизвестных источников, затем повтори команду");
                                }
                            } catch (Exception e2) {
                                openSecuritySettings(context, callback);
                            }
                            return;
                        }
                    }
                    launchInstaller(context, apkUri, callback);
                };
                if (activity != null) activity.runOnUiThread(launch);
                else new android.os.Handler(android.os.Looper.getMainLooper()).post(launch);
            } catch (Exception e) {
                postResult(activity, callback, "Ошибка: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }, "apk-install-helper").start();
    }

    private static void launchInstaller(Context context, Uri apkUri, ResultCallback callback) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(install);
            if (callback != null) callback.onResult("Установщик открыт ✓");
        } catch (Exception e) {
            if (callback != null) {
                callback.onResult("Не удалось открыть установщик: " + e.getMessage());
            }
        }
    }

    private static void openSecuritySettings(Context context, ResultCallback callback) {
        try {
            Intent s = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(s);
            if (callback != null) callback.onResult("Открыты настройки безопасности — включи 'Неизвестные источники'");
        } catch (Exception e) {
            if (callback != null) callback.onResult("Открой: Настройки → Безопасность → Неизвестные источники");
        }
    }

    private static void postResult(Activity activity, ResultCallback callback, String msg) {
        if (callback == null) return;
        if (activity != null) {
            activity.runOnUiThread(() -> callback.onResult(msg));
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onResult(msg));
        }
    }
}
