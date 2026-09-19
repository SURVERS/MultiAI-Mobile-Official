package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import app.multiai.mobile.BuildConfig;
import app.multiai.mobile.R;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Система обязательных обновлений Mobile IDE.
 *
 * <p>При запуске (каждые 6 часов) сверяет версию приложения с последним
 * релизом GitHub {@code SURVERS/MultiAI-Mobile-Official}. Ченжлог берётся из
 * body релиза. Диалог показывает версию, тип (мажор/минор) и ченжлог:
 * <ul>
 *   <li>первые 3 дня с публикации — можно отложить («Не сейчас»);</li>
 *   <li>после 3 дней кнопки «Не сейчас» нет: окно нельзя закрыть, а если
 *       юзер всё же попал в приложение — модели, профиль и кастомные
 *       провайдеры не грузятся, все запросы отвечают ошибкой
 *       «Unsupported version» ({@link #isUnsupportedBlocked(Context)}).</li>
 * </ul>
 * Установка: скачивается APK релиза и открывается системный установщик.
 */
public final class UpdateManager {

    private static final String RELEASES_API =
            "https://api.github.com/repos/SURVERS/MultiAI-Mobile-Official/releases?per_page=10";
    private static final String PREFS = "app_updates";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_DISMISSED_TAG = "dismissed_tag";
    private static final String KEY_DISMISSED_AT = "dismissed_at_ms";
    /** Повторная проверка не чаще раза в 6 часов. */
    private static final long CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    /** Сколько дней обнову можно откладывать. */
    private static final long GRACE_DAYS = 3;

    private UpdateManager() {}

    public static final class ReleaseInfo {
        public final String tagName;
        public final String version;
        public final boolean major;
        public final String changelog;
        public final long publishedAtMs;
        public final String apkUrl;

        ReleaseInfo(String tagName, String version, boolean major, String changelog,
                    long publishedAtMs, String apkUrl) {
            this.tagName = tagName;
            this.version = version;
            this.major = major;
            this.changelog = changelog;
            this.publishedAtMs = publishedAtMs;
            this.apkUrl = apkUrl;
        }
    }

    private static volatile ReleaseInfo cachedUpdate;
    private static volatile boolean graceExpired;
    private static final java.util.Set<String> postponedThisProcess =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Проверить обновления при старте приложения. Фоновый поток; в UI-потоке
     * показывает диалог, если есть более новый релиз.
     */
    private static final Handler updateHandler = new Handler(Looper.getMainLooper());
    private static java.lang.ref.WeakReference<Activity> foreground = new java.lang.ref.WeakReference<>(null);
    private static boolean lifecycleRegistered;
    private static boolean downloading;
    private static android.app.Dialog downloadDialog;
    private static final Runnable periodicCheck = new Runnable() {
        @Override public void run() {
            Activity activity = foreground.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) checkOnLaunch(activity);
            updateHandler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    public static void checkOnLaunch(Activity activity) {
        if (!lifecycleRegistered) {
            lifecycleRegistered = true;
            foreground = new java.lang.ref.WeakReference<>(activity);
            activity.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a, android.os.Bundle b) {}
                public void onActivityStarted(Activity a) {}
                public void onActivityResumed(Activity a) { foreground = new java.lang.ref.WeakReference<>(a); }
                public void onActivityPaused(Activity a) { if (foreground.get() == a) foreground.clear(); }
                public void onActivityStopped(Activity a) {}
                public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
                public void onActivityDestroyed(Activity a) { if (foreground.get() == a) foreground.clear(); }
            });
            updateHandler.postDelayed(periodicCheck, CHECK_INTERVAL_MS);
        }
        if (downloading) return;
        long now = System.currentTimeMillis();
        Context app = activity.getApplicationContext();
        if (now - app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            // Проверку пропускаем, но активный блок «Unsupported version» живёт.
            ReleaseInfo known = readCachedUpdate(app);
            if (known != null && isNewer(known.version, BuildConfig.VERSION_NAME)) {
                graceExpired = isGraceExpired(app, known);
                if (graceExpired) showBlockingDialog(activity, known);
                else maybeShowDialog(activity, known);
            }
            return;
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_CHECK, now).apply();

        new Thread(() -> {
            // Сетевая сверка времени: high-water mark пополняется от 10+ серверов.
            TimeGuard.refreshFromNetwork(activity.getApplicationContext());
            ReleaseInfo release = fetchLatestReleaseAsync();
            if (release == null || !isNewer(release.version, BuildConfig.VERSION_NAME)) return;
            cacheUpdate(app, release);
            graceExpired = isGraceExpired(app, release);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (graceExpired) showBlockingDialog(activity, release);
                else maybeShowDialog(activity, release);
            });
        }, "update-check").start();
    }

    /** Последний релиз фида: только не-draft, только semver-теги vX.Y.Z. */
    private static ReleaseInfo fetchLatestReleaseAsync() {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder().url(RELEASES_API).get()
                    .header("Accept", "application/vnd.github+json").build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                JSONArray arr = new JSONArray(response.body().string());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject rel = arr.optJSONObject(i);
                    if (rel == null || rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue;
                    String tag = rel.optString("tag_name", "").trim();
                    String version = normalizeVersion(tag);
                    if (version == null) continue;
                    String apkUrl = findApk(rel.optJSONArray("assets"));
                    long published = parseDate(rel.optString("published_at", ""));
                    return new ReleaseInfo(tag, version,
                            isMajorBump(BuildConfig.VERSION_NAME, version),
                            rel.optString("body", ""), published, apkUrl);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String findApk(JSONArray assets) {
        if (assets == null) return "";
        String fallback = "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            String url = a.optString("browser_download_url", "");
            if (name.endsWith(".apk")) {
                if (name.contains("release") || fallback.isEmpty()) {
                    if (name.contains("release")) return url;
                    fallback = url;
                }
            }
        }
        return fallback;
    }

    private static final Pattern VERSION_P = Pattern.compile("v?(\\d+)\\.(\\d+)(\\.(\\d+))?");

    private static String normalizeVersion(String tag) {
        if (tag == null) return null;
        var m = VERSION_P.matcher(tag.trim());
        if (!m.find()) return null;
        return (m.group(1) + "." + m.group(2) + "." + (m.group(4) != null ? m.group(4) : "0"));
    }

    /** true, если remote новее current по semver (major.minor.patch). */
    static boolean isNewer(String remote, String current) {
        int[] r = parseTriple(remote), c = parseTriple(current);
        if (r == null || c == null) return false;
        for (int i = 0; i < 3; i++) {
            if (r[i] != c[i]) return r[i] > c[i];
        }
        return false;
    }

    /** Мажорный апдейт: выросла первая цифра (или минор при равной мажорной). */
    static boolean isMajorBump(String current, String remote) {
        int[] r = parseTriple(remote), c = parseTriple(current);
        if (r == null || c == null) return false;
        if (r[0] != c[0]) return true;
        return r[1] > c[1];
    }

    private static int[] parseTriple(String v) {
        if (v == null) return null;
        var m = VERSION_P.matcher(v.trim());
        if (!m.find()) return null;
        return new int[]{
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                m.group(4) != null ? Integer.parseInt(m.group(4)) : 0
        };
    }

    /** ISO-8601 ("2026-09-07T14:40:26Z") → epoch ms. Без javax.xml.bind (нет на Android). */
    private static long parseDate(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try {
            // Формат GitHub: yyyy-MM-dd'T'HH:mm:ss'Z' (UTC).
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = fmt.parse(iso);
            return d != null ? d.getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ─────────── состояние «отложено» / «грейс истёк» ───────────

    private static void cacheUpdate(Context ctx, ReleaseInfo release) {
        cachedUpdate = release;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("cached_tag", release.tagName)
                .putString("cached_version", release.version)
                .putBoolean("cached_major", release.major)
                .putString("cached_changelog", release.changelog)
                .putLong("cached_published", release.publishedAtMs)
                .putString("cached_apk", release.apkUrl)
                .apply();
    }

    private static ReleaseInfo readCachedUpdate(Context ctx) {
        ReleaseInfo mem = cachedUpdate;
        if (mem != null) return mem;
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String version = p.getString("cached_version", null);
        if (version == null) return null;
        ReleaseInfo r = new ReleaseInfo(
                p.getString("cached_tag", "v" + version), version,
                p.getBoolean("cached_major", false),
                p.getString("cached_changelog", ""),
                p.getLong("cached_published", 0L),
                p.getString("cached_apk", ""));
        cachedUpdate = r;
        return r;
    }

    /** Юзер уже жал «Не сейчас» на этот релиз? */
    private static boolean isDismissed(Context ctx, ReleaseInfo release) {
        return postponedThisProcess.contains(release.tagName);
    }

    /**
     * Сброс «Не сейчас» — вызывается при возвращении приложения из фона
     * или при новом запуске. После этого промпт обновления снова показывается.
     */
    public static void resetPostponed() {
        postponedThisProcess.clear();
    }

    /** Прошло ли 3 дня с публикации (по защищённому времени TimeGuard). */
    private static boolean isGraceExpired(Context ctx, ReleaseInfo release) {
        long published = release.publishedAtMs;
        long graceMs = TimeUnit.DAYS.toMillis(GRACE_DAYS);
        // Откат системных часов грейс не продлевает: TimeGuard держит
        // high-water mark, пополненную 10+ сетевыми time-API.
        return published > 0 && TimeGuard.now(ctx) - published >= graceMs;
    }

    // ─────────── UI ───────────

    private static void maybeShowDialog(Activity activity, ReleaseInfo release) {
        if (isDismissed(activity, release)) return;
        showUpdateDialog(activity, release, true);
    }

    private static void showBlockingDialog(Activity activity, ReleaseInfo release) {
        showUpdateDialog(activity, release, false);
    }

    /**
     * Повторно показать блокирующий диалог (вызов из onResume): юзер закрыл
     * окно, но грейс истёк — диалог возвращается, пока обнова не установлена.
     * Есть активный диалог — не дублируем.
     */
    public static void reshowBlockingIfNeeded(Activity activity) {
        if (blockingDialog != null && blockingDialog.get() != null && blockingDialog.get().isShowing()) return;
        if (!isUnsupportedBlocked(activity)) return;
        ReleaseInfo release = readCachedUpdate(activity);
        if (release != null) showBlockingDialog(activity, release);
    }

    /** Ссылка на текущий блокирующий диалог (для защиты от дублей). */
    private static final java.util.concurrent.atomic.AtomicReference<android.app.Dialog> blockingDialog =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static void showUpdateDialog(Activity activity, ReleaseInfo release, boolean canPostpone) {
        if (downloading || (blockingDialog.get() != null && blockingDialog.get().isShowing())) return;
        graceExpired = !canPostpone;
        String typeLabel = release.major
                ? activity.getString(R.string.update_type_major)
                : activity.getString(R.string.update_type_minor);

        // Ограничиваем только ченжлог, чтобы заголовок и кнопки оставались видимыми.
        float density = activity.getResources().getDisplayMetrics().density;

        android.widget.LinearLayout body = new android.widget.LinearLayout(activity);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.TextView versionLine = new android.widget.TextView(activity);
        versionLine.setText(activity.getString(R.string.update_dialog_version, release.version, typeLabel));
        versionLine.setTextColor(activity.getColor(R.color.text_primary));
        versionLine.setTextSize(14);
        versionLine.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(versionLine);

        String changelog = release.changelog == null ? "" : release.changelog.trim();
        final android.widget.ScrollView scroll = new android.widget.ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int windowHeight = activity.getWindow().getDecorView().getHeight();
                if (windowHeight <= 0) {
                    windowHeight = getResources().getDisplayMetrics().heightPixels;
                }
                int maxHeight = (int) (windowHeight * 0.4f);
                if (android.view.View.MeasureSpec.getMode(heightMeasureSpec)
                        != android.view.View.MeasureSpec.UNSPECIFIED) {
                    maxHeight = Math.min(maxHeight,
                            android.view.View.MeasureSpec.getSize(heightMeasureSpec));
                }
                super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(
                        maxHeight, android.view.View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength((int) (24 * density));
        scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        android.widget.TextView changelogView = new android.widget.TextView(activity);
        changelogView.setTextColor(activity.getColor(R.color.text_primary));
        changelogView.setTextSize(13.5f);
        changelogView.setLineSpacing(2 * density, 1f);
        MarkdownRenderer.render(activity, changelogView, changelog);
        scroll.addView(changelogView);
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = (int) (10 * density);
        scroll.setLayoutParams(scrollLp);
        body.addView(scroll);

        android.app.Dialog dialog = app.multiai.mobile.ui.kit.ModernDialog.show(
                activity,
                activity.getString(R.string.update_dialog_title),
                null, body,
                canPostpone ? activity.getString(R.string.update_not_now) : null,
                activity.getString(R.string.update_install),
                false,
                () -> installUpdate(activity, release), canPostpone);
        blockingDialog.set(dialog);
        dialog.setOnDismissListener(d -> {
            blockingDialog.compareAndSet(dialog, null);
            if (canPostpone && !downloading) postpone(activity, release);
        });
    }
    /** Убрать маркдаун-разметку ченжлога для простого TextView. */
    private static String stripMarkdown(String md) {
        if (md == null || md.isEmpty()) return "";
        return md
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("(?m)^[-*]\\s+", "• ")
                .replaceAll("(?m)^>\\s?", "")
                .trim();
    }

    /** «Не сейчас»: запоминаем релиз и момент — грейс 3 дня стартует заново. */
    private static void postpone(Activity activity, ReleaseInfo release) {
        postponedThisProcess.add(release.tagName);
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DISMISSED_TAG)
                .remove(KEY_DISMISSED_AT)
                .apply();
    }

    // ─────────── установка ───────────

    private static void installUpdate(Activity activity, ReleaseInfo release) {
        if (release.apkUrl == null || release.apkUrl.isEmpty()) {
            openReleasesPage(activity);
            return;
        }
        if (downloading) return;
        downloading = true;
        android.widget.LinearLayout body = new android.widget.LinearLayout(activity);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView status = new android.widget.TextView(activity);
        status.setTextColor(activity.getColor(R.color.text_secondary));
        status.setText(activity.getString(R.string.update_downloading));
        android.widget.ProgressBar progress = new android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent)));
        progress.setMax(100);
        body.addView(status);
        body.addView(progress, new android.widget.LinearLayout.LayoutParams(-1, (int)(24 * activity.getResources().getDisplayMetrics().density)));
        android.app.Dialog dialog = app.multiai.mobile.ui.kit.ModernDialog.show(activity,
                activity.getString(R.string.update_dialog_title), release.version, body, null, null, false, null, false);
        downloadDialog = dialog;
        new Thread(() -> {
            try {
                File apk = downloadApk(activity, release, percent -> updateHandler.post(() -> {
                    if (!activity.isDestroyed()) {
                        progress.setIndeterminate(percent < 0);
                        progress.setProgress(Math.max(0, percent));
                        status.setText(activity.getString(R.string.update_downloading) + (percent >= 0 ? " " + percent + "%" : ""));
                    }
                }));
                updateHandler.post(() -> {
                    downloading = false;
                    dialog.dismiss();
                    downloadDialog = null;
                    if (!activity.isDestroyed() && !activity.isFinishing()) openApk(activity, apk);
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    downloading = false;
                    dialog.dismiss();
                    downloadDialog = null;
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    app.multiai.mobile.ui.kit.ModernToast.show(activity,
                            activity.getString(R.string.update_download_failed));
                    openReleasesPage(activity);
                });
            }
        }, "update-download").start();
    }

    private interface DownloadProgress { void update(int percent); }

    private static File downloadApk(Context ctx, ReleaseInfo release, DownloadProgress progress) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        Request request = new Request.Builder().url(release.apkUrl).get().build();
        File dir = new File(ctx.getCacheDir(), "apk_installs");
        dir.mkdirs();
        File out = new File(dir, "multiai-update-" + release.version + ".apk");
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            try (InputStream in = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = response.body().contentLength(), received = 0;
                int lastPercent = -2;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    received += n;
                    int percent = total > 0 ? (int)(received * 100 / total) : -1;
                    if (percent != lastPercent) { progress.update(percent); lastPercent = percent; }
                }
                if (received == 0 || (total > 0 && received != total)) throw new java.io.IOException("Incomplete APK download");
            }
        }
        return out;
    }

    private static void openApk(Activity activity, File apk) {
        try {
            android.net.Uri uri;
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        activity, activity.getPackageName() + ".fileprovider", apk);
            } else {
                uri = Uri.fromFile(apk);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            activity.startActivity(intent);
        } catch (Exception e) {
            openReleasesPage(activity);
        }
    }

    private static void openReleasesPage(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/SURVERS/MultiAI-Mobile-Official/releases/latest")));
        } catch (Exception ignored) {
        }
    }

    // ─────────── блок «Unsupported version» ───────────

    /**
     * Гарантирует свежий кэш фида перед проверкой блокировки. При холодном
     * старте процесса cached_update пуст, пока фоновый поток не отработает —
     * это давало окно, через которое запросы уходили без блока.
     */
    private static volatile boolean feedCheckedThisSession;

    private static void ensureFeedChecked(Context ctx) {
        if (feedCheckedThisSession) return;
        synchronized (UpdateManager.class) {
            if (feedCheckedThisSession) return;
            long now = System.currentTimeMillis();
            if (now - ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_LAST_CHECK, 0L) >= CHECK_INTERVAL_MS) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_CHECK, now).apply();
                // Блокирующая проверка тоже сверяет время с сетью.
                TimeGuard.refreshFromNetwork(ctx);
                ReleaseInfo release = fetchLatestReleaseAsync();
                if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    cacheUpdate(ctx, release);
                }
            }
            feedCheckedThisSession = true;
        }
    }

    /**
     * true, если доступно обновление, грейс 3 дня истёк и юзер всё ещё на
     * старой версии. В этом состоянии модели, профиль и кастомные
     * провайдеры не грузятся — все сетевые фичи отвечают «Unsupported version».
     *
     * <p>Проверяется фактическое состояние (кэш фида + часы устройства),
     * а не «показывался ли диалог»: рут-запуск активити или обход UI
     * блокировку не отменяет.
     */
    public static boolean isUnsupportedBlocked(Context ctx) {
        ensureFeedChecked(ctx);
        ReleaseInfo release = readCachedUpdate(ctx);
        if (release == null || !isNewer(release.version, BuildConfig.VERSION_NAME)) return false;
        return isGraceExpired(ctx, release);
    }

    /** Локализованный текст «Unsupported version» — во все заблокированные фичи. */
    public static String unsupportedMessage(Context ctx) {
        return ctx.getString(R.string.update_unsupported);
    }
}
