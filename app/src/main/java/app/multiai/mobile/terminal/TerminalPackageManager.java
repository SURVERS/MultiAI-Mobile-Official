package app.multiai.mobile.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads and installs optional terminal packages outside the APK. */
public final class TerminalPackageManager {
    public static final String MANIFEST_URL =
            "https://multiai-api.vercel.app/api/tools/manifest?abi=arm64-v8a";
    private static final String DOWNLOAD_URL =
            "https://multiai-api.vercel.app/api/tools/download";
    private static final String PREFS = "terminal_packages";
    private static final String KEY_CONFIGURED = "configured";
    private static final String KEY_INSTALLED = "installed";
    private static final String KEY_VERSIONS = "versions";

    public enum Stage {
        DOWNLOADING, VERIFYING, EXTRACTING, REMOVING, APPLYING_PERMISSIONS, VALIDATING, COMPLETE
    }

    public interface ProgressListener {
        void onProgress(Stage stage, PackageSpec spec, long current, long total, String message);
    }

    private static final long PROGRESS_INTERVAL_MS = 80;
    private long lastProgressAt;

    public static final class PackageSpec {
        public final String id;
        public final String name;
        public final String subtitle;
        public final String version;
        public final long downloadBytes;
        public final long unpackedBytes;
        public final String sha256;
        public final String url;
        public final List<String> dependencies;
        public final List<String> commands;
        public final boolean hidden;

        PackageSpec(String id, String name, String subtitle, String version,
                    long downloadBytes, long unpackedBytes, String sha256, String url,
                    List<String> dependencies, List<String> commands, boolean hidden) {
            this.id = id;
            this.name = name;
            this.subtitle = subtitle;
            this.version = version;
            this.downloadBytes = downloadBytes;
            this.unpackedBytes = unpackedBytes;
            this.sha256 = sha256;
            this.url = url;
            this.dependencies = dependencies;
            this.commands = commands;
            this.hidden = hidden;
        }
    }

    /** Ids that were packages once; their directories are reclaimed on startup. */
    private static final List<String> RETIRED_PACKAGE_IDS = Collections.singletonList("clang");

    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient client;
    private final File toolsDir;
    private final File downloadsDir;
    private volatile boolean cancellationRequested;
    private volatile Call activeDownloadCall;

    public TerminalPackageManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        this.toolsDir = new File(realFilesDir(), "tools");
        this.downloadsDir = new File(realFilesDir(), "tool-downloads");
        pruneRetiredPackages();
    }

    /**
     * Drops trees left by packages that no longer exist in the manifest.
     *
     * <p>The compiler used to be a standalone {@code clang} package and now ships
     * inside the python tree. Without this, the old copy would sit in app storage
     * with no way to remove it, because the removal path only knows manifest ids.</p>
     */
    private void pruneRetiredPackages() {
        for (String id : RETIRED_PACKAGE_IDS) {
            File directory = new File(toolsDir, id);
            if (directory.exists()) deleteRecursively(directory);
            deleteMatchingDownloads(id);
            Set<String> installed = getInstalledPackageIds();
            if (installed.remove(id)) {
                prefs.edit().putStringSet(KEY_INSTALLED, installed).apply();
            }
        }
    }

    public boolean isConfigured() {
        return prefs.getBoolean(KEY_CONFIGURED, false);
    }

    /** Imports a pre-online-toolchain installation only when explicitly requested. */
    public boolean importLegacyInstall() {
        return migrateLegacyInstall();
    }

    public Set<String> getInstalledPackageIds() {
        return new HashSet<>(prefs.getStringSet(KEY_INSTALLED, Collections.emptySet()));
    }

    public boolean isPackageInstalled(String id) {
        return getInstalledPackageIds().contains(id);
    }

    public boolean isPackageHealthyForUi(PackageSpec spec) {
        return isPackageHealthy(spec);
    }

    public List<String> installedDependents(String packageId, List<PackageSpec> manifest,
                                            Set<String> pendingRemoval) {
        List<String> dependents = new ArrayList<>();
        Set<String> installed = getInstalledPackageIds();
        for (PackageSpec spec : manifest) {
            if (!installed.contains(spec.id) || pendingRemoval.contains(spec.id)) continue;
            if (spec.dependencies.contains(packageId)) dependents.add(spec.name);
        }
        return dependents;
    }

    public long installedBytes(String id, List<PackageSpec> manifest) {
        for (PackageSpec spec : manifest) if (spec.id.equals(id)) return spec.unpackedBytes;
        return 0;
    }

    public void removePackages(Set<String> packageIds, List<PackageSpec> manifest,
                               ProgressListener listener) throws Exception {
        Set<String> installed = getInstalledPackageIds();
        for (String id : packageIds) {
            List<String> dependents = installedDependents(id, manifest, packageIds);
            if (!dependents.isEmpty()) {
                throw new IllegalStateException("Используется: " + android.text.TextUtils.join(", ", dependents));
            }
        }
        JSONObject versions = readVersions();
        int done = 0;
        PackageSpec last = basicSpec();
        for (String id : packageIds) {
            PackageSpec spec = null;
            for (PackageSpec candidate : manifest) if (candidate.id.equals(id)) spec = candidate;
            if (spec == null || spec.hidden) continue;
            last = spec;
            listener.onProgress(Stage.REMOVING, spec, done, packageIds.size(), "Удаляем " + spec.name);
            File directory = packageDirectory(id);
            if (directory != null && directory.exists() && !deleteRecursivelyChecked(directory)) {
                throw new IllegalStateException("Не удалось удалить " + spec.name);
            }
            deleteMatchingDownloads(id);
            installed.remove(id);
            versions.remove(id);
            persist(installed, versions, true);
            done++;
        }
        listener.onProgress(Stage.COMPLETE, last, packageIds.size(), packageIds.size(), "Изменения применены");
    }

    private File packageDirectory(String id) {
        switch (id) {
            case "python": return new File(toolsDir, "python");
            case "node": return new File(toolsDir, "node");
            case "git": return new File(toolsDir, "git");
            case "curl": return new File(toolsDir, "curl");
            case "java": return new File(toolsDir, "java");
            case "gradle": return new File(toolsDir, "gradle");
            case "android": return new File(toolsDir, "android-sdk");
            case "runtime": return new File(toolsDir, "lib");
            default: return null;
        }
    }

    private void deleteMatchingDownloads(String id) {
        File[] files = downloadsDir.listFiles();
        if (files == null) return;
        for (File file : files) if (file.getName().startsWith(id + "-")) file.delete();
    }

    private boolean deleteRecursivelyChecked(File file) {
        if (file == null || !file.exists()) return true;
        if (isSymbolicLink(file)) return file.delete() || !file.exists();
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) if (!deleteRecursivelyChecked(child)) return false;
        }
        return file.delete() || !file.exists();
    }

    public File getToolsDir() {
        return toolsDir;
    }

    public List<PackageSpec> loadManifest() {
        try {
            Request request = new Request.Builder().url(MANIFEST_URL).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return fallbackManifest();
                }
                return parseManifest(response.body().string());
            }
        } catch (Exception ignored) {
            return fallbackManifest();
        }
    }

    /**
     * Ищет установленные пакеты, чья версия в манифесте новее установленной.
     * Используется для тихого автообновления при входе в терминал.
     */
    public List<PackageSpec> findOutdated(List<PackageSpec> manifest) {
        List<PackageSpec> outdated = new ArrayList<>();
        for (PackageSpec spec : manifest) {
            if (!isPackageInstalled(spec.id)) continue;
            if (!isCurrent(spec)) outdated.add(spec);
        }
        return outdated;
    }

    /**
     * Тихое автообновление: качает свежий манифест и переустанавливает
     * устаревшие пакеты (без вопросов). Возвращает имена обновлённых
     * пакетов или null, если обновлять было нечего/не удалось.
     * Вызывается из фонового потока.
     */
    public List<String> autoUpdateOutdated(ProgressListener listener) {
        try {
            List<PackageSpec> manifest = loadManifest();
            List<PackageSpec> outdated = findOutdated(manifest);
            if (outdated.isEmpty()) return null;
            Set<String> ids = new HashSet<>();
            for (PackageSpec spec : outdated) ids.add(spec.id);
            install(manifest, ids, listener);
            List<String> names = new ArrayList<>();
            for (PackageSpec spec : outdated) names.add(spec.name);
            return names;
        } catch (Exception e) {
            // Автообновление не критично: терминал работает на старых версиях.
            return null;
        }
    }

    public Map<String, PackageSpec> indexById(List<PackageSpec> specs) {
        Map<String, PackageSpec> index = new LinkedHashMap<>();
        for (PackageSpec spec : specs) index.put(spec.id, spec);
        return index;
    }

    /** Resolves hidden dependencies once and preserves stable manifest order. */
    public List<PackageSpec> resolveSelection(List<PackageSpec> manifest, Set<String> selected) {
        Map<String, PackageSpec> index = indexById(manifest);
        Set<String> resolved = new HashSet<>();
        for (String id : selected) addWithDependencies(id, index, resolved);
        List<PackageSpec> result = new ArrayList<>();
        for (PackageSpec spec : manifest) {
            if (resolved.contains(spec.id) && !isPackageHealthy(spec)) result.add(spec);
        }
        return result;
    }

    public long totalDownloadBytes(List<PackageSpec> manifest, Set<String> selected) {
        long total = 0;
        for (PackageSpec spec : resolveSelection(manifest, selected)) total += spec.downloadBytes;
        return total;
    }

    public void cancelInstall() {
        cancellationRequested = true;
        Call call = activeDownloadCall;
        if (call != null) call.cancel();
    }

    private void throwIfCancelled() throws InterruptedException {
        if (cancellationRequested || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Installation cancelled");
        }
    }

    public void install(List<PackageSpec> manifest, Set<String> selected,
                        ProgressListener listener) throws Exception {
        cancellationRequested = false;
        throwIfCancelled();
        List<PackageSpec> queue = resolveSelection(manifest, selected);
        long required = 64L * 1024L * 1024L;
        for (PackageSpec spec : queue) required += spec.unpackedBytes + spec.downloadBytes;
        ensureFreeSpace(required);
        downloadsDir.mkdirs();
        toolsDir.mkdirs();

        Set<String> installed = getInstalledPackageIds();
        JSONObject versions = readVersions();
        for (PackageSpec spec : queue) {
            throwIfCancelled();
            File archive = download(spec, listener);
            try {
                listener.onProgress(Stage.VERIFYING, spec, 0, archive.length(), "Проверяем SHA-256");
                verifySha256(spec, archive);
                listener.onProgress(Stage.EXTRACTING, spec, 0, spec.unpackedBytes, "Распаковываем " + spec.name);
                extractZipSafely(archive, spec, listener);
                validatePackage(spec);
                installed.add(spec.id);
                versions.put(spec.id, spec.version);
                persist(installed, versions, false);
            } catch (Exception error) {
                installed.remove(spec.id);
                persist(installed, versions, false);
                throw new IllegalStateException(spec.name + ": " + error.getMessage(), error);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
        }

        throwIfCancelled();
        PackageSpec finalSpec = queue.isEmpty() ? basicSpec() : queue.get(queue.size() - 1);
        listener.onProgress(Stage.APPLYING_PERMISSIONS, finalSpec, 0, 1, "Устанавливаем права");
        new ToolsManager(context).finalizeInstalledPackages();
        listener.onProgress(Stage.VALIDATING, finalSpec, 0, 1, "Проверяем команды");
        validateInstalled(indexById(manifest), installed);
        persist(installed, versions, true);
        listener.onProgress(Stage.COMPLETE, finalSpec, 1, 1, "Терминал готов");
    }

    public String packageForCommand(String command) {
        if (command == null) return null;
        String token = command.trim().split("\\s+")[0];
        int slash = token.lastIndexOf('/');
        if (slash >= 0) token = token.substring(slash + 1);
        switch (token) {
            case "python": case "python3": case "pip": case "pip3": return "python";
            case "node": case "npm": case "npx": return "node";
            case "git": return "git";
            case "curl": return "curl";
            case "java": case "javac": case "jar": return "java";
            case "gradle": case "gradlew": return "gradle";
            case "aapt": case "aapt2": case "adb": case "zipalign": case "apksigner": return "android";
            default: return null;
        }
    }

    private File download(PackageSpec spec, ProgressListener listener) throws Exception {
        // Очищаем УСТАРЕВШИЕ частичные/полные архивы этого пакета (от прошлой
        // версии/хэша): иначе resume по Range дописывал новые байты в конец
        // СТАРОГО зипа → SHA-256 mismatch навсегда, пока не удалишь вручную.
        File[] stale = downloadsDir.listFiles((dir, name) ->
                name.startsWith(spec.id + "-") && (name.endsWith(".zip") || name.endsWith(".zip.part")));
        if (stale != null) {
            String keep = spec.id + "-" + spec.version + ".zip.part";
            for (File f : stale) {
                if (!f.getName().equals(keep) && !f.delete()) f.deleteOnExit();
            }
        }
        File partial = new File(downloadsDir, spec.id + "-" + spec.version + ".zip.part");
        File complete = new File(downloadsDir, spec.id + "-" + spec.version + ".zip");
        long offset = partial.exists() ? partial.length() : 0;
        // Защита от битого resume: если partial крупнее ожидаемого архива — с нуля.
        if (spec.downloadBytes > 0 && offset > spec.downloadBytes) {
            if (!partial.delete()) partial.deleteOnExit();
            offset = 0;
        }
        String resolvedUrl = spec.url == null || spec.url.isEmpty()
                ? DOWNLOAD_URL + "?package=" + spec.id + "&version=" + spec.version + "&abi=arm64-v8a"
                : spec.url;
        Request.Builder builder = new Request.Builder().url(resolvedUrl).get();
        if (offset > 0) builder.header("Range", "bytes=" + offset + "-");
        long startedAt = System.currentTimeMillis();
        throwIfCancelled();
        Call call = client.newCall(builder.build());
        activeDownloadCall = call;
        try (Response response = call.execute()) {
            if (!(response.isSuccessful() || response.code() == 206) || response.body() == null) {
                throw new IllegalStateException("Download failed (HTTP " + response.code() + ")");
            }
            if (offset > 0 && response.code() != 206) {
                offset = 0;
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
            ResponseBody body = response.body();
            long responseBytes = body.contentLength();
            long total = spec.downloadBytes > 0 ? spec.downloadBytes : offset + Math.max(0, responseBytes);
            try (InputStream input = new BufferedInputStream(body.byteStream());
                 RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
                output.seek(offset);
                byte[] buffer = new byte[64 * 1024];
                long current = offset;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    throwIfCancelled();
                    output.write(buffer, 0, read);
                    current += read;
                    long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
                    long speed = Math.max(0, (current - offset) * 1000L / elapsed);
                    emitProgress(listener, Stage.DOWNLOADING, spec, current, total,
                            formatBytes(current) + " / " + formatBytes(total) + " · " + formatBytes(speed) + "/с",
                            current >= total);
                }
            }
        } finally {
            if (activeDownloadCall == call) activeDownloadCall = null;
        }
        throwIfCancelled();
        if (!partial.renameTo(complete)) {
            copyFile(partial, complete);
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
        }
        return complete;
    }

    private void verifySha256(PackageSpec spec, File archive) throws Exception {
        if (spec.sha256 == null || spec.sha256.trim().isEmpty()) {
            throw new SecurityException("Package manifest has no SHA-256 for " + spec.id);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(archive))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                throwIfCancelled();
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(Locale.US, "%02x", value));
        if (!actual.toString().equalsIgnoreCase(spec.sha256)) {
            // Логируем оба хэша + откуда качали — для полевой отладки CDN-кэша.
            app.multiai.mobile.utils.DebugFileLog.log("PkgSHA",
                    "MISMATCH " + spec.id + " expected=" + spec.sha256
                            + " actual=" + actual + " url=" + spec.url
                            + " bytes=" + archive.length());
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            throw new SecurityException("SHA-256 mismatch for " + spec.name);
        }
        app.multiai.mobile.utils.DebugFileLog.log("PkgSHA",
                "ok " + spec.id + " sha=" + actual);
    }

    private void extractZipSafely(File archive, PackageSpec spec, ProgressListener listener) throws Exception {
        File staging = new File(realFilesDir(), "tool-staging/" + spec.id);
        deleteRecursively(staging);
        staging.mkdirs();
        String stagingRoot = staging.getCanonicalPath() + File.separator;
        long extracted = 0;
        int entryCount = 0;
        long unpackLimit = spec.unpackedBytes;
        if (unpackLimit <= 0L) throw new SecurityException("Manifest has no unpacked-size limit");
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                throwIfCancelled();
                if (++entryCount > 100_000) throw new SecurityException("Archive contains too many entries");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) throw new SecurityException("Unsafe archive path");
                File output = new File(staging, name);
                String canonical = output.getCanonicalPath();
                if (!canonical.startsWith(stagingRoot)) throw new SecurityException("Archive escaped staging directory");
                if (entry.isDirectory()) {
                    output.mkdirs();
                } else {
                    File parent = output.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (BufferedOutputStream target = new BufferedOutputStream(new FileOutputStream(output))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            throwIfCancelled();
                            target.write(buffer, 0, read);
                            extracted += read;
                            if (extracted > unpackLimit) {
                                throw new SecurityException("Archive exceeds declared unpacked size");
                            }
                        }
                    }
                    emitProgress(listener, Stage.EXTRACTING, spec, extracted, spec.unpackedBytes,
                            "Распаковываем · " + formatBytes(extracted), extracted >= spec.unpackedBytes);
                }
                zip.closeEntry();
            }
        }
        File livePackage = packageDirectory(spec.id);
        if (livePackage == null) throw new IllegalStateException("Unknown package: " + spec.id);
        File stagedTools = new File(staging, "tools");
        File sourceRoot = stagedTools.exists() ? stagedTools : staging;
        File packageSource = new File(sourceRoot, livePackage.getName());
        File source = packageSource.exists() ? packageSource : sourceRoot;
        File backup = new File(realFilesDir(), "tool-backup/" + spec.id + "-" + System.nanoTime());
        backup.getParentFile().mkdirs();
        boolean movedOld = false;
        try {
            if (livePackage.exists()) {
                if (!livePackage.renameTo(backup)) throw new IOException("Cannot stage old package");
                movedOld = true;
            }
            if (!source.renameTo(livePackage)) {
                mergeDirectory(source, livePackage);
            }
            deleteRecursively(staging);
            deleteRecursively(backup);
        } catch (Exception error) {
            deleteRecursively(livePackage);
            if (movedOld) backup.renameTo(livePackage);
            deleteRecursively(staging);
            throw error;
        }
    }

    private void validatePackage(PackageSpec spec) throws Exception {
        if (spec.commands.isEmpty()) return;
        ToolsManager manager = new ToolsManager(context);
        manager.initializeEnvironment();
        for (String command : spec.commands) {
            File binary = manager.resolveCommand(command);
            if (binary == null || !binary.exists() || binary.length() == 0) {
                app.multiai.mobile.utils.DebugFileLog.log("PkgValidate",
                        "FAILED " + spec.id + ": " + command + " missing after install");
                throw new IllegalStateException("Command validation failed: " + command);
            }
        }
        // node: npx/npm обязаны быть исполняемыми обёртками (first-run fix).
        if ("node".equals(spec.id)) {
            for (String wrapper : new String[]{"npx", "npm"}) {
                File file = new File(manager.getToolsDir(), "node/" + wrapper);
                if (!file.isFile()) {
                    app.multiai.mobile.utils.DebugFileLog.log("PkgValidate",
                            "FAILED node: " + wrapper + " wrapper missing");
                    throw new IllegalStateException("Command validation failed: " + wrapper);
                }
                if (!file.canExecute()) {
                    // Wrappers are rewritten by ToolsManager; force the bit back on
                    // if this device's filesystem lost it (some OEM /data mounts).
                    //noinspection ResultOfMethodCallIgnored
                    file.setExecutable(true, false);
                    if (!file.canExecute()) {
                        app.multiai.mobile.utils.DebugFileLog.log("PkgValidate",
                                "FAILED node: " + wrapper + " wrapper not executable");
                        throw new IllegalStateException("Command validation failed: " + wrapper);
                    }
                }
            }
            app.multiai.mobile.utils.DebugFileLog.log("PkgValidate",
                    "ok node: npx/npm wrappers present and executable");
        }
    }

    private void validateInstalled(Map<String, PackageSpec> manifest, Set<String> installed) throws Exception {
        ToolsManager manager = new ToolsManager(context);
        manager.initializeEnvironment();
        List<String> missing = new ArrayList<>();
        for (String id : installed) {
            PackageSpec spec = manifest.get(id);
            if (spec == null || spec.commands.isEmpty()) continue;
            for (String command : spec.commands) {
                File binary = manager.resolveCommand(command);
                if (binary == null || !binary.exists() || binary.length() == 0) {
                    missing.add(spec.name + ": " + command);
                }
            }
        }
        // Не валим всю установку из-за одного отсутствующего бинаря (apksigner и др.
        // могли не распаковаться на конкретном ABI) — собираем warning, модуль ставится.
        if (!missing.isEmpty()) {
            android.util.Log.w("TerminalPkg", "Some commands failed validation (soft): " + missing);
        }
    }

    private boolean migrateLegacyInstall() {
        File tools = toolsDir;
        if (!tools.exists()) return false;
        Set<String> found = new HashSet<>();
        if (new File(tools, "python/bin/python").exists()) found.add("python");
        if (new File(tools, "node/node").exists()) found.add("node");
        if (new File(tools, "git/bin/git").exists()) found.add("git");
        if (new File(tools, "curl/curl").exists()) found.add("curl");
        if (new File(tools, "java/bin/java").exists()) found.add("java");
        if (new File(tools, "gradle/bin/gradle").exists()) found.add("gradle");
        if (new File(tools, "android-sdk/bin/aapt2").exists()) found.add("android");
        if (found.isEmpty()) return false;
        persist(found, new JSONObject(), true);
        return true;
    }

    private boolean isCurrent(PackageSpec spec) {
        if (!isPackageInstalled(spec.id)) return false;
        return spec.version.equals(readVersions().optString(spec.id, spec.version));
    }

    private boolean isPackageHealthy(PackageSpec spec) {
        if (!isCurrent(spec)) return false;
        if (spec.commands.isEmpty()) return toolsDir.exists();
        ToolsManager manager = new ToolsManager(context);
        manager.initializeEnvironment();
        for (String command : spec.commands) {
            File binary = manager.resolveCommand(command);
            if (binary == null || !binary.exists() || binary.length() == 0) {
                Set<String> repaired = getInstalledPackageIds();
                repaired.remove(spec.id);
                persist(repaired, readVersions(), false);
                return false;
            }
        }
        return true;
    }

    private void persist(Set<String> installed, JSONObject versions, boolean configured) {
        prefs.edit()
                .putStringSet(KEY_INSTALLED, new HashSet<>(installed))
                .putString(KEY_VERSIONS, versions.toString())
                .putBoolean(KEY_CONFIGURED, configured)
                .apply();
    }

    private JSONObject readVersions() {
        try { return new JSONObject(prefs.getString(KEY_VERSIONS, "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private void ensureFreeSpace(long required) {
        StatFs stats = new StatFs(realFilesDir().getAbsolutePath());
        if (stats.getAvailableBytes() < required) {
            throw new IllegalStateException("Недостаточно места. Нужно ещё " + formatBytes(required - stats.getAvailableBytes()));
        }
    }

    private void addWithDependencies(String id, Map<String, PackageSpec> index, Set<String> result) {
        if (!result.add(id)) return;
        PackageSpec spec = index.get(id);
        if (spec == null) return;
        for (String dependency : spec.dependencies) addWithDependencies(dependency, index, result);
    }

    private List<PackageSpec> parseManifest(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (root.optInt("schemaVersion", -1) != 1 || !"v1".equals(root.optString("release"))) {
            throw new SecurityException("Unsupported tool manifest version");
        }
        JSONArray packages = root.getJSONArray("packages");
        List<PackageSpec> result = new ArrayList<>();
        for (int i = 0; i < packages.length(); i++) {
            JSONObject item = packages.getJSONObject(i);
            String id = item.getString("id");
            String pinnedHash = pinnedSha256(id);
            String remoteHash = item.optString("sha256");
            if (pinnedHash == null || !pinnedHash.equalsIgnoreCase(remoteHash)) {
                throw new SecurityException("Untrusted tool package: " + id);
            }
            PackageSpec spec = new PackageSpec(
                    id, item.getString("name"), item.optString("subtitle"),
                    item.optString("version", "1"), item.optLong("downloadBytes"),
                    item.optLong("unpackedBytes"), pinnedHash, "",
                    strings(item.optJSONArray("dependencies")), strings(item.optJSONArray("commands")),
                    item.optBoolean("hidden", false));
            if (hasUsableSha256(spec)) result.add(spec);
        }
        return result;
    }

    /** App-embedded trust root. Updating a package requires shipping a new app build. */
    private static String pinnedSha256(String id) {
        if (id == null) return null;
        switch (id) {
            case "runtime": return "c39a16adbe11a4e18ee173f8a840d74c3d3ddbe7d707364f984a1baf3a319a0c";
            case "python": return "6041ca3bc09d32efbecccd4f971d1104771a3c86e51a32791534b4fec6b7453f";
            case "node": return "d6f7243f519430eb77788e3cf090a7e0d7d3c98102b41e13257c379072fc18ea";
            case "git": return "5c79129716e787e6acf626af88c3751cf363dd244bbe09f52ce1b36d57ca9044";
            case "curl": return "b13afd21f41f324e6012f60db9c275625a7a97188956962a203c325060ab2b14";
            case "java": return "b5805b4c28c84ae1b124d2ac1dad9cddf46a13a87a4a43914641ada51db2ea32";
            case "android": return "d4c67f0f2e65cef13ce6ebdee1e024dc1353b6925b2acce7566b2e8b79fac0fe";
            case "gradle": return "4751839f5b3cc1b442cb23d981cd1473e42095d9550e5c59687cfd28345eb0e1";
            default: return null;
        }
    }

    private List<PackageSpec> fallbackManifest() {
        List<PackageSpec> result = new ArrayList<>();
        addFallback(result, spec("runtime", "Runtime libraries", "Общие системные библиотеки", 64801030, 155041590,
                "c39a16adbe11a4e18ee173f8a840d74c3d3ddbe7d707364f984a1baf3a319a0c",
                Collections.emptyList(), Collections.emptyList(), true));
        // The C toolchain, the CPython headers and libffi/libsodium ship inside the
        // python tree at python/clang: pip cannot build a package with a C extension
        // without them, and one archive keeps install and removal in step.
        addFallback(result, spec("python", "Python", "Python 3 + pip + компилятор C/C++",
                132921498, 431905836,
                "6041ca3bc09d32efbecccd4f971d1104771a3c86e51a32791534b4fec6b7453f",
                Collections.singletonList("runtime"),
                Arrays.asList("python", "pip", "clang"), false));
        // node тянет git как зависимость: npx skills add / npm install <git-url>
        // не работают без git, а AI ставит скиллы через них.
        addFallback(result, spec("node", "Node.js", "Node.js + npm + npx", 20697229, 59612765,
                "d6f7243f519430eb77788e3cf090a7e0d7d3c98102b41e13257c379072fc18ea",
                Arrays.asList("runtime", "git"), Arrays.asList("node", "npm", "npx"), false));
        addFallback(result, spec("git", "Git", "Git + HTTPS certificates", 12577555, 23943844,
                "5c79129716e787e6acf626af88c3751cf363dd244bbe09f52ce1b36d57ca9044",
                Collections.singletonList("runtime"), Collections.singletonList("git"), false));
        addFallback(result, spec("curl", "curl", "HTTP/HTTPS client + HTTP/2 + HTTP/3", 1318908, 2908448,
                "b13afd21f41f324e6012f60db9c275625a7a97188956962a203c325060ab2b14",
                Collections.emptyList(), Collections.singletonList("curl"), false));
        addFallback(result, spec("java", "Java", "JDK: java + javac", 129057934, 246033707,
                "b5805b4c28c84ae1b124d2ac1dad9cddf46a13a87a4a43914641ada51db2ea32",
                Collections.singletonList("runtime"), Arrays.asList("java", "javac"), false));
        addFallback(result, spec("android", "Android Build Tools", "aapt2 + adb + zipalign + apksigner", 3864375, 7532900,
                "d4c67f0f2e65cef13ce6ebdee1e024dc1353b6925b2acce7566b2e8b79fac0fe",
                Collections.singletonList("runtime"), Arrays.asList("aapt2", "adb", "zipalign", "apksigner"), false));
        addFallback(result, spec("gradle", "Gradle", "Включает Java и Android Build Tools", 137419980, 152103368,
                "4751839f5b3cc1b442cb23d981cd1473e42095d9550e5c59687cfd28345eb0e1",
                Arrays.asList("runtime", "java", "android"), Collections.singletonList("gradle"), false));
        return result;
    }

    private void addFallback(List<PackageSpec> result, PackageSpec spec) {
        if (hasUsableSha256(spec)) result.add(spec);
    }

    private boolean hasUsableSha256(PackageSpec spec) {
        return spec != null && spec.sha256 != null
                && spec.sha256.matches("(?i)[0-9a-f]{64}");
    }

    private PackageSpec spec(String id, String name, String subtitle, long downloadBytes, long unpackedBytes,
                             String sha256, List<String> dependencies, List<String> commands, boolean hidden) {
        return new PackageSpec(id, name, subtitle, "v1", downloadBytes,
                unpackedBytes, sha256, "", dependencies, commands, hidden);
    }

    private PackageSpec basicSpec() {
        return new PackageSpec("basic", "Базовый набор", "Android shell + toybox", "1", 0, 0,
                "", "", Collections.emptyList(), Collections.emptyList(), false);
    }

    private List<String> strings(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }

    private void emitProgress(ProgressListener listener, Stage stage, PackageSpec spec,
                              long current, long total, String message, boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastProgressAt < PROGRESS_INTERVAL_MS) return;
        lastProgressAt = now;
        listener.onProgress(stage, spec, current, total, message);
    }

    private File realFilesDir() {
        String path = context.getFilesDir().getAbsolutePath().replaceFirst("^/data/user/\\d+/", "/data/data/");
        return new File(path);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 МБ";
        double mb = bytes / 1048576d;
        if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb);
        return String.format(Locale.US, "%.2f ГБ", mb / 1024d);
    }

    private void mergeDirectory(File source, File destination) throws Exception {
        if (!source.exists()) return;
        if (source.isDirectory()) {
            destination.mkdirs();
            File[] children = source.listFiles();
            if (children != null) for (File child : children) mergeDirectory(child, new File(destination, child.getName()));
        } else {
            File parent = destination.getParentFile();
            if (parent != null) parent.mkdirs();
            copyFile(source, destination);
        }
    }

    private void copyFile(File source, File destination) throws Exception {
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private static boolean isSymbolicLink(File file) {
        try {
            return android.system.OsConstants.S_ISLNK(
                    android.system.Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (isSymbolicLink(file)) {
            file.delete();
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
