package app.multiai.mobile.terminal;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ToolsManager — extracts bundled terminal tools (python, node, git, java,
 * gradle, android-sdk) from APK assets to app-private storage and runs
 * commands locally via ProcessBuilder.
 *
 * Runtime notes:
 *  - targetSdk 35 enables Android's W^X policy for writable app_data
 *  - ELF entry points are loaded through /system/bin/linker64 instead of direct execve
 *  - LD_LIBRARY_PATH is ordered so bundled runtimes find their private libraries
 *  - Path canonicalization uses /data/data/ instead of /data/user/0/
 */
public class ToolsManager {

    private static final String TAG = "MultiAITerminal";
    private static final String ASSETS_DIR = "tools";
    private static final String LINKER64 = "/system/bin/linker64";
    /** Module name of the generated site hook that fixes child process execs. */
    private static final String SHIM_MODULE = "multiai_exec_shim";
    /** APK assets folder holding prebuilt wheels for this interpreter's ABI. */
    private static final String WHEELS_ASSET_DIR = "wheels";
    /** APK assets folder with the prebuilt exec interceptor. */
    private static final String NATIVE_ASSET_DIR = "native/arm64-v8a";
    private static final String EXEC_INTERCEPTOR = "libmultiaiexec.so";
    // Bump this when extraction/runtime repair logic changes.
    private static final String TOOLS_VERSION = "v25-linker64-bootstrap";
    private static final int MAX_CAPTURED_OUTPUT_CHARS = 1_000_000;

    /** Compatibility hook retained for old diagnostic call sites; never persists data. */
    private static void flog(String ignored) {
        // Commands, output, environment paths, and toolchain paths may contain user data.
    }

    public interface OutputCallback {
        void onOutput(String text);
    }

    public interface ProgressCallback {
        void onProgress(String message, int current, int total);
    }

    /**
     * Cooperative cancellation flag polled while a process runs. Pure Java so
     * it is unit-testable on the JVM; WorkModeTools passes an
     * {@code OperationCancelToken::check} method reference here.
     */
    public interface CancelSignal {
        boolean isCancelled();
    }

    /** Marker returned (as a prefix) when a command is killed by a cancel. */
    public static final String CANCELLED_PREFIX = "CANCELLED";

    private interface FileCallback {
        void onFileExtracted(String path);
    }

    private final Context context;
    private File toolsDir;
    private Map<String, String> env;
    private Map<String, String> javaEnv;
    private boolean ready = false;

    // Processes started by execute() on THIS instance, tracked so callers
    // (WorkModeTools cancel path) can kill them without touching the
    // terminal UI's executeStreaming() processes.
    private final java.util.concurrent.ConcurrentHashMap<Process, Boolean> activeProcesses =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ToolsManager(Context context) {
        this.context = context;
    }

    /**
     * Check if tools are already extracted (cached from previous run).
     */
    public boolean isReady() {
        if (ready) return true;
        TerminalPackageManager packages = new TerminalPackageManager(context);
        if (!packages.isConfigured()) return false;
        initializeEnvironment();
        return true;
    }

    /**
     * Extract tools from assets to filesDir/tools.
     * Does NOT delete existing extraction — skips if already present.
     */
    public void extractIfNeeded(ProgressCallback progress) {
        if (isReady()) {
            flog("extractIfNeeded: already ready, skip");
            progress.onProgress("Ready", 1, 1);
            return;
        }
        progress.onProgress("Don't access. Configure terminal first.", 0, 1);
        return;
    }

    public File getToolsDir() {
        return toolsDir;
    }

    /** Fast startup path: only builds the environment, never walks the tool tree. */
    public synchronized void initializeEnvironment() {
        File realFilesDir = realFilesDir();
        toolsDir = new File(realFilesDir, ASSETS_DIR);
        toolsDir.mkdirs();
        setupEnvironment(realFilesDir);
        // Ensure wrapper scripts for tools whose shebangs may be broken.
        ensurePipWrappers(new File(toolsDir, "python"));
        ensureNpmWrappers(new File(toolsDir, "node"));
        // Existing package installs may have lost ZIP executable bits. This
        // targeted repair is cheap enough for every terminal startup.
        repairEntryPointPermissions();
        ready = true;
    }

    /** Heavy one-time finalization used only after package extraction. */
    public void finalizeInstalledPackages() {
        File realFilesDir = realFilesDir();
        toolsDir = new File(realFilesDir, ASSETS_DIR);
        toolsDir.mkdirs();
        makeExecutables(toolsDir);
        rewriteShebangs(toolsDir, realFilesDir);
        fixUnderscoreDirs(toolsDir);
        if (new File(toolsDir, "gradle").exists()) {
            fixGradleWrapper(toolsDir);
            extractNativePlatformLib(toolsDir);
        }
        if (new File(toolsDir, "android-sdk").exists()) patchAapt2(toolsDir);
        if (new File(toolsDir, "git").exists()) {
            fixGitHardlinks(toolsDir);
            fixCaBundle(toolsDir);
        }
        // Create pip wrappers after Python is extracted.
        ensurePipWrappers(new File(toolsDir, "python"));
        ensureNpmWrappers(new File(toolsDir, "node"));
        setupEnvironment(realFilesDir);
        ready = true;
    }

    /** Compatibility alias for callers that only need to start the shell. */
    public void prepareDownloadedTools() {
        initializeEnvironment();
    }

    private File realFilesDir() {
        String realPath = context.getFilesDir().getAbsolutePath().replaceFirst(
                "^/data/user/\\d+/", "/data/data/");
        return new File(realPath);
    }

    /** Resolve a command against the optional downloaded package directories. */
    public File resolveCommand(String command) {
        if (toolsDir == null) initializeEnvironment();
        String[][] candidates = {
                {"python", "python/bin/python"}, {"python3", "python/bin/python"},
                {"pip", "python/bin/pip"}, {"pip3", "python/bin/pip"},
                {"node", "node/node"}, {"npm", "node/npm"}, {"npx", "node/npx"},
                {"git", "git/bin/git"}, {"curl", "curl/curl"},
                {"java", "java/bin/java"}, {"javac", "java/bin/javac"},
                {"gradle", "gradle/bin/gradle"}, {"aapt2", "android-sdk/bin/aapt2"},
                {"adb", "android-sdk/bin/adb"}, {"zipalign", "android-sdk/bin/zipalign"},
                {"apksigner", "android-sdk/bin/apksigner"},
                {"clang", "python/clang/bin/clang"}, {"clang++", "python/clang/bin/clang++"},
                {"cc", "python/clang/bin/cc"}, {"c++", "python/clang/bin/c++"},
                {"ld", "python/clang/bin/ld.lld"}, {"ld.lld", "python/clang/bin/ld.lld"},
                {"aarch64-linux-android-clang", "python/clang/bin/aarch64-linux-android-clang"},
                {"aarch64-linux-android-clang++", "python/clang/bin/aarch64-linux-android-clang++"}
        };
        for (String[] candidate : candidates) {
            if (candidate[0].equals(command)) {
                File file = new File(toolsDir, candidate[1]);
                return file.exists() ? file : null;
            }
        }
        return null;
    }

    /**
     * Execute a command string. Returns combined stdout+stderr.
     */
    public String execute(String command, long timeoutSec) {
        return execute(command, timeoutSec, null);
    }

    /**
     * Execute a command string with cooperative cancellation. Returns combined
     * stdout+stderr. When {@code cancel} fires while the process is running,
     * the process tree is killed immediately (same terminateProcessTree used
     * for timeouts) and the output is prefixed with {@link #CANCELLED_PREFIX}.
     *
     * @param cancel optional cancellation signal polled ~every 50ms; null
     *               behaves exactly like the two-argument overload
     */
    public String execute(String command, long timeoutSec, CancelSignal cancel) {
        if (!ready) {
            return "Error: tools not extracted yet\n";
        }

        Process process = null;
        try {
            ProcessBuilder pb = buildProcess(command);
            pb.redirectErrorStream(true);
            pb.directory(new File(env.get("HOME")));

            final Process started = pb.start();
            process = started;
            activeProcesses.put(started, Boolean.TRUE);
            StringBuilder output = new StringBuilder();
            Thread drain = new Thread(() -> drainProcessOutput(started, output),
                    "multiai-process-output");
            drain.setDaemon(true);
            drain.start();
            long deadlineNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSec));
            boolean exited = false;
            boolean cancelledFlag = false;
            while (true) {
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    exited = true;
                    break;
                }
                if (cancel != null && cancel.isCancelled()) {
                    cancelledFlag = true;
                    break;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
            }
            if (!exited) {
                terminateProcessTree(process);
            }
            try {
                drain.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (cancelledFlag) return CANCELLED_PREFIX + " (by user)\n";
            if (!exited) return "TIMEOUT (" + timeoutSec + "s)\n";
            synchronized (output) {
                return output.toString();
            }
        } catch (Exception e) {
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n";
        } finally {
            if (process != null) activeProcesses.remove(process);
        }
    }

    /**
     * Kill every process currently tracked as active on this ToolsManager
     * instance (only processes started through the cancellable execute()
     * above). Safe to call from any thread, including the UI thread; never
     * throws. Returns the number of process trees signalled.
     */
    public int killActiveProcesses() {
        int killed = 0;
        for (Process process : activeProcesses.keySet()) {
            try {
                terminateProcessTree(process);
                killed++;
            } catch (Exception ignored) {
            }
        }
        return killed;
    }

    /** Number of live processes tracked by the cancellable execute path. */
    public int activeProcessCount() {
        return activeProcesses.size();
    }

    private void drainProcessOutput(Process process, StringBuilder output) {
        byte[] buffer = new byte[4096];
        try {
            int read;
            while ((read = process.getInputStream().read(buffer)) != -1) {
                synchronized (output) {
                    output.append(new String(buffer, 0, read));
                    if (output.length() > MAX_CAPTURED_OUTPUT_CHARS) {
                        output.delete(0, output.length() - MAX_CAPTURED_OUTPUT_CHARS);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Execute a command with streaming output (for terminal UI).
     */
    public void executeStreaming(String command, OutputCallback callback) {
        flog("executeStreaming: cmd='" + command + "' ready=" + ready);
        if (!ready) {
            flog("executeStreaming: NOT READY");
            callback.onOutput("Error: tools not extracted yet\n");
            return;
        }

        flog("env HOME=" + env.get("HOME") + " LD_LIBRARY_PATH=" + env.get("LD_LIBRARY_PATH"));
        try {
            ProcessBuilder pb = buildProcess(command);
            pb.redirectErrorStream(true);
            pb.directory(new File(env.get("HOME")));

            flog("ProcessBuilder cmd: " + pb.command());
            Process process = pb.start();
            process.getOutputStream().close();
            flog("Process started, pid likely active, reading output...");

            java.util.concurrent.atomic.AtomicBoolean hadOutput =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread reader = new Thread(() -> {
                byte[] buffer = new byte[4096];
                try {
                    int bytesRead;
                    while ((bytesRead = process.getInputStream().read(buffer)) != -1) {
                        hadOutput.set(true);
                        callback.onOutput(new String(buffer, 0, bytesRead));
                    }
                } catch (IOException ignored) {
                }
            }, "terminal-stream-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                terminateProcessTree(process);
                callback.onOutput("\n(timeout - process killed)\n");
                flog("Process timed out after 30s");
            } else {
                flog("Process finished, exit=" + process.exitValue());
            }
            try { reader.join(1000L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!hadOutput.get()) {
                callback.onOutput("(no output)\n");
            }
        } catch (Exception e) {
            flog("executeStreaming exception: " + e);
            callback.onOutput("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
        }
    }

    public static void terminateProcessTree(Process process) {
        if (process == null) return;
        try {
            java.lang.reflect.Method pidMethod = Process.class.getMethod("pid");
            long pid = ((Number) pidMethod.invoke(process)).longValue();
            killDescendants(pid, new HashSet<>());
        } catch (Exception ignored) {
        }
        try {
            process.destroy();
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (Exception ignored) {
            try {
                process.destroyForcibly();
            } catch (Exception ignoredToo) {
            }
        }
    }

    private static void killDescendants(long pid, Set<Long> visited) {
        if (pid <= 0 || !visited.add(pid)) return;
        File childrenFile = new File("/proc/" + pid + "/task/" + pid + "/children");
        String children = "";
        try (FileInputStream input = new FileInputStream(childrenFile)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            children = output.toString("UTF-8").trim();
        } catch (Exception ignored) {
        }
        if (children.isEmpty()) return;
        for (String child : children.split("\\s+")) {
            try {
                long childPid = Long.parseLong(child);
                killDescendants(childPid, visited);
                android.system.Os.kill((int) childPid, android.system.OsConstants.SIGKILL);
            } catch (Exception ignored) {
            }
        }
    }

    // =================== Internal helpers ===================

    private ProcessBuilder buildProcess(String command) {
        // Shell functions route writable app_data ELF files through linker64,
        // bypassing Android 10+ W^X direct-exec denial while preserving pipes,
        // redirects, cd/export and other shell syntax in the user's command.
        String bootstrap = getShellBootstrap();
        String shellCommand = bootstrap.isEmpty() ? command : bootstrap + "\n" + command;
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", shellCommand);

        pb.environment().clear();
        Map<String, String> useEnv = shouldUseJavaEnv(command) ? javaEnv : env;
        pb.environment().putAll(filterAndroidRuntimeKeys(useEnv));

        return pb;
    }

    private boolean shouldUseJavaEnv(String command) {
        return command.startsWith("java") || command.startsWith("javac") ||
                command.startsWith("gradle") || command.startsWith("apksigner") ||
                command.contains(".jar");
    }

    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Post a runnable to the UI thread (used by PersistentShell). */
    public void postOnUi(Runnable r) {
        uiHandler.post(r);
    }

    /** The base environment map (HOME, PATH, LD_LIBRARY_PATH, JAVA_HOME, ...). */
    public Map<String, String> getEnv() {
        return env;
    }

    /**
     * Defines shell functions for bundled ELF entry points. Android blocks
     * direct execve from writable app_data for modern target SDKs, while the
     * system linker can safely map the same binaries.
     */
    public synchronized String getShellBootstrap() {
        if (toolsDir == null || env == null) initializeEnvironment();
        if (!new File(LINKER64).canExecute()) return "";

        StringBuilder script = new StringBuilder();
        File python = new File(toolsDir, "python/bin/python");
        appendLinkerFunction(script, "python", python, null, null);
        appendLinkerFunction(script, "python3", python, null, null);
        appendLinkerFunction(script, "pip", python, "-m pip", null);
        appendLinkerFunction(script, "pip3", python, "-m pip", null);

        File node = new File(toolsDir, "node/node");
        appendLinkerFunction(script, "node", node, null, null);
        appendLinkerFunction(script, "nodejs", node, null, null);
        appendLinkerFunction(script, "npm", node,
                shellQuote(new File(toolsDir, "node/lib/node_modules/npm/bin/npm-cli.js")
                        .getAbsolutePath()), null);
        appendLinkerFunction(script, "npx", node,
                shellQuote(new File(toolsDir, "node/lib/node_modules/npm/bin/npx-cli.js")
                        .getAbsolutePath()), null);

        String javaLibraries = javaEnv != null ? javaEnv.get("LD_LIBRARY_PATH") : null;
        appendLinkerFunction(script, "java", new File(toolsDir, "java/bin/java"),
                null, javaLibraries);
        appendLinkerFunction(script, "javac", new File(toolsDir, "java/bin/javac"),
                null, javaLibraries);
        appendLinkerFunction(script, "jar", new File(toolsDir, "java/bin/jar"),
                null, javaLibraries);

        appendLinkerFunction(script, "git", new File(toolsDir, "git/bin/git"),
                null, null);
        appendLinkerFunction(script, "curl", new File(toolsDir, "curl/curl"),
                null, null);
        appendLinkerFunction(script, "aapt2", new File(toolsDir, "android-sdk/bin/aapt2"),
                null, null);
        appendLinkerFunction(script, "adb", new File(toolsDir, "android-sdk/bin/adb"),
                null, null);
        appendLinkerFunction(script, "zipalign", new File(toolsDir, "android-sdk/bin/zipalign"),
                null, null);
        return script.toString();
    }

    private static void appendLinkerFunction(StringBuilder script, String name, File binary,
                                             String fixedArguments, String libraryPath) {
        if (!isElf(binary)) return;
        script.append(name).append("() { ");
        if (libraryPath != null && !libraryPath.isEmpty()) {
            script.append("LD_LIBRARY_PATH=").append(shellQuote(libraryPath)).append(' ');
        }
        script.append(shellQuote(LINKER64)).append(' ')
                .append(shellQuote(binary.getAbsolutePath()));
        if (fixedArguments != null && !fixedArguments.isEmpty()) {
            script.append(' ').append(fixedArguments);
        }
        script.append(" \"$@\"; }\n");
    }

    private static boolean isElf(File file) {
        if (file == null || !file.isFile() || file.length() < 4) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 0x7f && input.read() == 'E'
                    && input.read() == 'L' && input.read() == 'F';
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public Map<String, String> filterAndroidRuntimeKeys(Map<String, String> source) {
        String[] androidKeys = {
                "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH",
                "STANDALONE_SYSTEMSERVER_JARS", "CLASSPATH"
        };
        Map<String, String> filtered = new HashMap<>(source);
        for (String key : androidKeys) {
            filtered.remove(key);
        }
        return filtered;
    }

    private void setupEnvironment(File realFilesDir) {
        File javaHome = new File(toolsDir, "java");
        File androidHome = new File(toolsDir, "android-sdk");
        File pythonHome = new File(toolsDir, "python");
        File nodeHome = new File(toolsDir, "node");
        File gitHome = new File(toolsDir, "git");
        File gradleDir = new File(toolsDir, "gradle");
        File commonLib = new File(toolsDir, "lib");
        File tmpDir = new File(realFilesDir, "tmp");
        tmpDir.mkdirs();

        // Ensure pip / pip3 wrapper scripts exist in python/bin so they work
        // directly from PATH. The Python toolchain ships pip as a module, not
        // a standalone binary, so we create thin sh wrappers.
        ensurePipWrappers(pythonHome);
        // Teach the interpreter to launch its own children through the linker.
        ensurePythonExecShim(pythonHome);
        File wheelhouse = ensureWheelhouse(realFilesDir);
        File execInterceptor = ensureExecInterceptor(commonLib);

        // The C toolchain is nested inside the python package, so installing or
        // removing Python takes its compiler along.
        File clangHome = new File(pythonHome, "clang");
        StringBuilder pathEntries = new StringBuilder();
        pathEntries.append(new File(clangHome, "bin").getAbsolutePath()).append(":");
        pathEntries.append(new File(pythonHome, "bin").getAbsolutePath()).append(":");
        pathEntries.append(nodeHome.getAbsolutePath()).append(":");
        pathEntries.append(new File(gitHome, "bin").getAbsolutePath()).append(":");
        pathEntries.append(new File(toolsDir, "curl").getAbsolutePath()).append(":");
        pathEntries.append(new File(javaHome, "bin").getAbsolutePath()).append(":");
        pathEntries.append(new File(gradleDir, "bin").getAbsolutePath()).append(":");
        pathEntries.append(new File(androidHome, "bin").getAbsolutePath()).append(":");
        pathEntries.append(System.getenv("PATH") != null ? System.getenv("PATH") : "");

        StringBuilder ldLibraryPath = new StringBuilder();
        ldLibraryPath.append(new File(pythonHome, "lib").getAbsolutePath()).append(":");
        // libffi / libsodium ship with the compiler package, and freshly built
        // extensions load them at import time.
        ldLibraryPath.append(new File(clangHome, "lib").getAbsolutePath()).append(":");
        ldLibraryPath.append(commonLib.getAbsolutePath()).append(":");
        ldLibraryPath.append(new File(gradleDir, "lib").getAbsolutePath()).append(":");
        ldLibraryPath.append(new File(nodeHome, "lib").getAbsolutePath());

        env = new HashMap<>();
        env.put("HOME", realFilesDir.getAbsolutePath());
        env.put("PATH", pathEntries.toString());
        env.put("JAVA_HOME", javaHome.getAbsolutePath());
        env.put("ANDROID_HOME", androidHome.getAbsolutePath());
        env.put("ANDROID_SDK_ROOT", androidHome.getAbsolutePath());
        env.put("GRADLE_HOME", gradleDir.getAbsolutePath());

        // Git needs GIT_EXEC_PATH to find its sub-commands and helpers, and
        // GIT_TEMPLATE_DIR for the default hooks/description used by init
        // and clone. Without these git falls back to compile-time defaults
        // (/data/data/com.termux/files/usr/...) that don't exist here, and
        // every clone prints a templates warning + can't find remote-https.
        // help.format=man is git's only working format without a browser,
        // and a tiny 'man' shim in android-sdk/bin rewrites 'man git-X' to
        // 'git X --help' so 'git help <cmd>' actually prints something
        // instead of "no man viewer handled the request".
        File gitCore = new File(gitHome, "libexec/git-core");
        env.put("GIT_EXEC_PATH", gitCore.getAbsolutePath());
        env.put("GIT_TEMPLATE_DIR", new File(gitCore, "templates").getAbsolutePath());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_PAGER", "cat");
        env.put("GIT_CONFIG_COUNT", "4");
        env.put("GIT_CONFIG_KEY_0", "help.autocorrect");
        env.put("GIT_CONFIG_VALUE_0", "0");
        env.put("GIT_CONFIG_KEY_1", "core.preloadindex");
        env.put("GIT_CONFIG_VALUE_1", "false");
        // The bundled git looks for system-wide gitattributes at the
        // hardcoded Termux path on every clone; the directory is not
        // readable, so it prints "warning: unable to access ... Permission
        // denied" before Cloning completes. Point git at an empty
        // attributes file we create in our own storage so the warning
        // goes away.
        File emptyAttrs = new File(realFilesDir, "gitattributes-empty");
        try {
            if (!emptyAttrs.exists()) new FileOutputStream(emptyAttrs).close();
        } catch (Exception ignored) {}
        env.put("GIT_ATTR_NOSYSTEM", "1");
        env.put("GIT_CONFIG_KEY_2", "core.attributesFile");
        env.put("GIT_CONFIG_VALUE_2", emptyAttrs.getAbsolutePath());
        // The bundled git/curl were built against the Termux cert path
        // (/data/data/com.termux/files/usr/etc/tls/cert.pem) which does
        // not exist in our app's private storage. fixCaBundle() writes
        // tools/cacerts/bundle.pem from /system/etc/security/cacerts-*;
        // point openssl/curl at it so HTTPS clones work.
        File caBundle = new File(toolsDir, "cacerts/bundle.pem");
        if (caBundle.exists()) {
            env.put("GIT_SSL_CAINFO", caBundle.getAbsolutePath());
            env.put("SSL_CERT_FILE", caBundle.getAbsolutePath());
            env.put("SSL_CERT_DIR", "/system/etc/security");
            env.put("GIT_CONFIG_KEY_3", "http.sslCAInfo");
            env.put("GIT_CONFIG_VALUE_3", caBundle.getAbsolutePath());
        }

        // GRADLE_USER_HOME must be set explicitly - the bundled JDK has a
        // hardcoded HOME pointing at com.itsaky.androidide, which makes
        // Gradle try to create native libs in a nonexistent directory.
        File gradleUserHome = new File(realFilesDir, "gradle-home");
        gradleUserHome.mkdirs();
        env.put("GRADLE_USER_HOME", gradleUserHome.getAbsolutePath());
        // AGP downloads aapt2 from Maven by default. The only published
        // aapt2-linux artifact is x86_64, which cannot run on aarch64
        // Android. android.aapt2FromMavenOverride forces AGP to use our
        // bundled aarch64 aapt2 instead. Passing it via -D on the java
        // command line is unreliable in embedded Tooling-API mode (it
        // only affects the JVM system properties, not AGP's project-
        // scope Gradle properties). Writing it to gradle.properties in
        // GRADLE_USER_HOME is the documented mechanism and is read by
        // AGP's Aapt2MavenArtifactCache unconditionally.
        File gradleProps = new File(gradleUserHome, "gradle.properties");
        String aapt2Override = new File(androidHome, "bin/aapt2").getAbsolutePath();
        try {
            String existing = gradleProps.exists()
                    ? new String(java.nio.file.Files.readAllBytes(gradleProps.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8)
                    : "";
            StringBuilder additions = new StringBuilder();
            if (!hasGradleProperty(existing, "android.aapt2FromMavenOverride")) {
                additions.append("android.aapt2FromMavenOverride=").append(aapt2Override).append("\n");
            }
            if (!hasGradleProperty(existing, "android.builder.sdkDownload")) {
                additions.append("android.builder.sdkDownload=true\n");
            }
            if (!hasGradleProperty(existing, "org.gradle.jvmargs")) {
                additions.append("org.gradle.jvmargs=-Dfile.encoding=UTF-8\n");
            }
            if (additions.length() > 0) {
                StringBuilder merged = new StringBuilder(existing);
                if (merged.length() > 0 && merged.charAt(merged.length() - 1) != '\n') merged.append('\n');
                merged.append(additions);
                java.nio.file.Files.write(gradleProps.toPath(),
                        merged.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
        }
        // Disable Jansi and native platform - both try to extract/load .so
        // files into hardcoded paths that don't exist on this device.
        env.put("GRADLE_OPTS", "-Djansi.passthrough=true -Dorg.gradle.native=false");
        env.put("LD_LIBRARY_PATH", ldLibraryPath.toString());
        env.put("TERM", "dumb");
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("MALLOC_BIONIC_DISABLE_MEMORY_TAGGING", "1");
        env.put("OPENSSL_CONF", "/dev/null");
        // Root of the bundled toolchain; the Python exec shim uses it to decide
        // which child processes must be routed through the linker.
        env.put("MULTIAI_TOOLS_DIR", toolsDir.getAbsolutePath());
        if (execInterceptor != null) {
            // Every process in the terminal inherits this, so a bundled tool
            // spawning another bundled tool (clang calling ld.lld, a build
            // backend calling a compiler) is rewritten too — the shell-level
            // linker64 wrappers only cover what the user types.
            env.put("LD_PRELOAD", execInterceptor.getAbsolutePath());
        }
        if (wheelhouse != null) {
            // Rust-based extensions cannot be built on the device, so pip must
            // find the prebuilt wheels shipped in the APK before it falls back
            // to an sdist it has no toolchain for.
            env.put("PIP_FIND_LINKS", wheelhouse.getAbsolutePath());
            env.put("PIP_PREFER_BINARY", "1");
        }
        applyCompilerEnvironment(env, clangHome, pythonHome, commonLib);
        // Python needs PYTHONHOME to find its stdlib and site-packages
        env.put("PYTHONHOME", pythonHome.getAbsolutePath());
        // PYTHONPATH must include both stdlib and site-packages (pip lives there)
        env.put("PYTHONPATH",
                new File(pythonHome, "lib/python3.14").getAbsolutePath() + ":" +
                new File(pythonHome, "lib/python3.14/site-packages").getAbsolutePath());

        StringBuilder javaLdLibraryPath = new StringBuilder();
        javaLdLibraryPath.append(new File(javaHome, "lib/server").getAbsolutePath()).append(":");
        javaLdLibraryPath.append(new File(javaHome, "lib").getAbsolutePath()).append(":");
        javaLdLibraryPath.append(commonLib.getAbsolutePath());

        javaEnv = new HashMap<>(env);
        javaEnv.put("LD_LIBRARY_PATH", javaLdLibraryPath.toString());

    }

    private static boolean hasGradleProperty(String content, String key) {
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            int separator = trimmed.indexOf('=');
            if (separator < 0) separator = trimmed.indexOf(':');
            String existingKey = separator >= 0 ? trimmed.substring(0, separator).trim() : trimmed;
            if (key.equals(existingKey)) return true;
        }
        return false;
    }

    // =================== Asset extraction ===================

    private void extractDirectory(AssetManager am, String assetPath, File destDir,
                                   FileCallback callback) {
        String[] assets;
        try {
            assets = am.list(assetPath);
        } catch (IOException e) {
            return;
        }
        if (assets == null || assets.length == 0) return;

        for (String asset : assets) {
            String subPath = assetPath + "/" + asset;
            File destFile = new File(destDir, asset);

            try (InputStream input = am.open(subPath)) {
                destFile.getParentFile().mkdirs();
                try (OutputStream output = new FileOutputStream(destFile)) {
                    copyStream(input, output);
                }
                if (callback != null) callback.onFileExtracted(subPath);
            } catch (IOException e) {
                destFile.mkdirs();
                extractDirectory(am, subPath, destFile, callback);
            }
        }
    }

    private int countAssets(AssetManager am, String path) {
        int count = 0;
        String[] assets;
        try {
            assets = am.list(path);
        } catch (IOException e) {
            return 0;
        }
        if (assets == null || assets.length == 0) return 0;

        for (String asset : assets) {
            String subPath = path + "/" + asset;
            String[] subAssets;
            try {
                subAssets = am.list(subPath);
            } catch (IOException e) {
                subAssets = null;
            }
            if (subAssets == null || subAssets.length == 0) {
                count++;
            } else {
                count += countAssets(am, subPath);
            }
        }
        return count;
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }

    /** Restores mode 0755 for command entry points without walking the whole tool tree. */
    private void repairEntryPointPermissions() {
        String[] entryPoints = {
                "python/bin/python", "python/bin/python3", "python/bin/python3.14",
                "python/bin/pip", "python/bin/pip3", "python/bin/pip3.14",
                "node/node", "node/npm", "node/npx",
                // The compiler driver and its linker: setuptools reaches them by
                // the aarch64-* names taken from sysconfigdata.
                "python/clang/bin/clang", "python/clang/bin/clang-21",
                "python/clang/bin/clang++", "python/clang/bin/cc",
                "python/clang/bin/c++", "python/clang/bin/cpp",
                "python/clang/bin/ld", "python/clang/bin/ld.lld", "python/clang/bin/lld",
                "python/clang/bin/aarch64-linux-android-clang",
                "python/clang/bin/aarch64-linux-android-clang++",
                "python/clang/bin/aarch64-linux-android-cpp",
                "python/clang/bin/aarch64-linux-android-gcc",
                "python/clang/bin/aarch64-linux-android-g++",
                "git/bin/git", "curl/curl", "java/bin/java", "java/bin/javac", "java/bin/jar",
                "gradle/bin/gradle", "android-sdk/bin/aapt2", "android-sdk/bin/adb",
                "android-sdk/bin/zipalign", "android-sdk/bin/apksigner"
        };
        for (String relativePath : entryPoints) {
            File entryPoint = new File(toolsDir, relativePath);
            if (!entryPoint.exists()) continue;
            try {
                Os.chmod(entryPoint.getAbsolutePath(), 0755);
            } catch (Exception ignored) {
                // java.io fallback handles devices where android.system.Os is restricted.
                try {
                    entryPoint.setReadable(true, false);
                    entryPoint.setExecutable(true, false);
                } catch (Exception ignoredToo) {
                }
            }
        }
    }

    private void makeExecutables(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) {
                // Make ALL files executable (scripts + binaries). Android
                // assets lose exec bits, and many tools (npm, pip, git helpers)
                // are scripts that need +x.
                try {
                    file.setExecutable(true, false);
                } catch (Exception ignored) {
                }
            } else if (file.isDirectory()) {
                makeExecutables(file);
            }
        }
    }

    /**
     * Create pip / pip3 wrapper scripts in python/bin/ if the Python
     * toolchain is installed but pip is missing as a standalone binary.
     * The wrappers delegate to "python -m pip" so pip works directly
     * from PATH without any command interception.
     */
    private void ensurePipWrappers(File pythonHome) {
        File binDir = new File(pythonHome, "bin");
        File pythonBin = new File(binDir, "python");
        if (!pythonBin.exists()) return; // Python not installed yet
        binDir.mkdirs();
        String pythonPath = pythonBin.getAbsolutePath();

        // Create python3.14 symlink → python so rewritten shebangs resolve.
        File py314 = new File(binDir, "python3.14");
        if (!py314.exists()) {
            try {
                Os.symlink(pythonPath, py314.getAbsolutePath());
            } catch (Exception ignored) {
                // Fallback: copy as a small sh wrapper.
                try {
                    java.io.FileWriter w = new java.io.FileWriter(py314);
                    w.write("#!/system/bin/sh\n");
                    w.write("exec \"" + pythonPath + "\" \"$@\"\n");
                    w.close();
                    py314.setExecutable(true, false);
                } catch (Exception ignored2) {
                }
            }
        }

        // Always overwrite pip wrappers: the shipped pip may have a broken
        // shebang pointing at a different package (e.g. com.wolftoolchain.tester).
        String[] names = {"pip", "pip3", "pip3.14"};
        for (String name : names) {
            File wrapper = new File(binDir, name);
            try {
                java.io.FileWriter w = new java.io.FileWriter(wrapper);
                w.write("#!/system/bin/sh\n");
                w.write("exec \"" + pythonPath + "\" -m pip \"$@\"\n");
                w.close();
                wrapper.setExecutable(true, false);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Installs a {@code site} hook that routes child processes through the linker.
     *
     * <p>Android forbids {@code execve()} of anything inside the app data
     * directory for targetSdk&nbsp;&ge;&nbsp;29 (SELinux drops
     * {@code execute_no_trans} on {@code app_data_file}; the rule is
     * {@code dontaudit}, so it fails with EACCES and logs nothing). Mapping the
     * same file is still allowed, which is why the terminal launches every
     * bundled ELF as {@code linker64 <binary>}.</p>
     *
     * <p>That rewrite only covers commands typed into the shell. Anything the
     * interpreter spawns itself — pip's build isolation running
     * {@code [sys.executable, "-m", "pip", …]}, {@code venv},
     * {@code multiprocessing}, {@code setup.py} — performs a direct execve and
     * dies with "Permission denied". Wrapper scripts cannot help: a shebang
     * script in app data is refused exactly like an ELF, and this device's
     * linker has no {@code --argv0}, so {@code sys.executable} cannot be pointed
     * at a launcher either. Patching the exec boundary inside Python is what is
     * left, and it fixes every one of those callers at once.</p>
     */
    private void ensurePythonExecShim(File pythonHome) {
        File siteDir = findSitePackages(pythonHome);
        if (siteDir == null) return;
        try {
            File module = new File(siteDir, SHIM_MODULE + ".py");
            try (OutputStream out = new FileOutputStream(module)) {
                out.write(buildExecShimSource().getBytes("UTF-8"));
            }
            // A .pth line starting with "import" is executed by site.py at
            // startup, so the hook installs itself without PYTHONSTARTUP.
            File hook = new File(siteDir, "zzz-" + SHIM_MODULE + ".pth");
            try (OutputStream out = new FileOutputStream(hook)) {
                out.write(("import " + SHIM_MODULE + "\n").getBytes("UTF-8"));
            }
            flog("ensurePythonExecShim: installed in " + siteDir);
        } catch (Exception error) {
            flog("ensurePythonExecShim error: " + error);
        }
    }

    /**
     * Points the relocated Termux compiler at its files.
     *
     * <p>Termux builds clang with {@code /data/data/com.termux/files/usr} as its
     * prefix, and that path is compiled in: after being unpacked here the driver
     * would look for its sysroot, headers and libraries in another app's data
     * directory. The Python interpreter gets away with the same problem because
     * {@code PYTHONHOME} overrides it; for clang the equivalent knobs are
     * {@code --sysroot} and the standard {@code CPPFLAGS}/{@code LDFLAGS}, which
     * distutils appends to every compile and link command.</p>
     *
     * <p>The clang package lays out {@code clang/include} and {@code clang/lib}
     * exactly as a sysroot expects, so one {@code --sysroot} covers the system
     * headers; the CPython and libffi headers are added explicitly because
     * sysconfigdata still names Termux-absolute paths for them.</p>
     */
    private void applyCompilerEnvironment(Map<String, String> environment, File clangHome,
                                          File pythonHome, File commonLib) {
        File clangLib = new File(clangHome, "lib");
        if (!new File(clangHome, "bin").isDirectory()) return;

        // clang derives its resource directory from /proc/self/exe. Launching it
        // through linker64 makes that the linker inside the runtime APEX, so the
        // driver concluded it was installed at /apex/com.android.runtime and
        // looked for stddef.h, libclang_rt.builtins.a and the crt objects there.
        // Naming the directory explicitly is what fixes all three at once.
        File resourceDir = resolveClangResourceDir(clangHome);
        String flags = "--sysroot=" + clangHome.getAbsolutePath()
                // -B lets the driver find crtbegin_dynamic.o / crtend_android.o,
                // which live next to the libraries rather than in the sysroot.
                + " -B" + clangLib.getAbsolutePath();
        if (resourceDir != null) flags += " -resource-dir=" + resourceDir.getAbsolutePath();

        File include = new File(clangHome, "include");
        // The CPython headers ship with the compiler as
        // python/clang/include/python3.14. sysconfig still advertises them under
        // python/include, and that -I simply resolves to nothing; this one is what
        // actually makes Python.h reachable.
        //
        // The triple directory holds the architecture headers (asm/*). Clang
        // normally derives it as <sysroot>/usr/include/<triple>, but a Termux
        // prefix has include/ without the usr/ level, so it has to be named.
        String includes = "-I" + include.getAbsolutePath()
                + " -I" + new File(include, "aarch64-linux-android").getAbsolutePath()
                + " -I" + new File(include, "python3.14").getAbsolutePath();
        String libraries = " -L" + clangLib.getAbsolutePath()
                + " -L" + new File(pythonHome, "lib").getAbsolutePath()
                + " -L" + commonLib.getAbsolutePath();

        // Flags travel in CC/CXX rather than CFLAGS so that callers which only
        // honour the driver variable get them too: distutils builds link_exe
        // straight from CC and never appends LDFLAGS to it, which is why the
        // compiler probes were still resolving paths under /apex.
        environment.put("CC", "aarch64-linux-android-clang " + flags);
        environment.put("CXX", "aarch64-linux-android-clang++ " + flags);
        environment.put("LD", "ld.lld");
        environment.put("CPPFLAGS", includes);
        environment.put("LDFLAGS", flags + libraries);
        // pkg-config style hint for builds that probe libsodium instead of
        // taking the bundled headers on faith.
        environment.put("SODIUM_INSTALL", "system");
    }

    /**
     * Locates the clang resource directory inside the package.
     *
     * <p>Termux ships {@code lib/clang/<major>} plus a {@code latest} alias.
     * Preferring the alias keeps this working when the bundled LLVM is bumped,
     * with a numeric scan as the fallback.
     */
    private File resolveClangResourceDir(File clangHome) {
        File root = new File(clangHome, "lib/clang");
        File latest = new File(root, "latest");
        if (new File(latest, "include").isDirectory()) return latest;

        File[] candidates = root.listFiles();
        File best = null;
        int bestMajor = -1;
        if (candidates == null) return null;
        for (File candidate : candidates) {
            if (!new File(candidate, "include").isDirectory()) continue;
            int major;
            try {
                major = Integer.parseInt(candidate.getName().split("\\.")[0]);
            } catch (Exception ignored) {
                continue;
            }
            if (major > bestMajor) {
                bestMajor = major;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Copies the APK's native payload into the shared library directory.
     *
     * <p>Holds two kinds of file. {@code libmultiaiexec.so} is built from
     * {@code toolchain-release/exec-interceptor/multiai_exec.c} and interposes on
     * the exec family to rewrite bundled binaries into
     * {@code linker64 <binary>} — the only way one bundled tool can start
     * another on targetSdk&nbsp;&ge;&nbsp;29. The rest are small libraries the
     * toolchain links against; shipping them here means a fix does not require
     * republishing and re-downloading the 120 MB compiler package, and they are
     * restored on every start if a package install replaces the directory.</p>
     *
     * @return the exec interceptor, or {@code null} when it is unavailable
     */
    private File ensureExecInterceptor(File libDir) {
        if (!libDir.isDirectory() && !libDir.mkdirs()) return null;
        String[] payload;
        try {
            payload = context.getAssets().list(NATIVE_ASSET_DIR);
        } catch (Exception error) {
            return null;
        }
        if (payload == null) return null;

        File interceptor = null;
        for (String name : payload) {
            if (name == null || !name.contains(".so")) continue;
            File target = new File(libDir, name);
            if (EXEC_INTERCEPTOR.equals(name)) interceptor = target;
            String asset = NATIVE_ASSET_DIR + "/" + name;
            try (InputStream in = context.getAssets().open(asset)) {
                // Assets are immutable per install, so equal size means the file
                // on disk is already the right one.
                if (target.isFile() && target.length() == in.available()) continue;
            } catch (Exception error) {
                continue;
            }
            try (InputStream in = context.getAssets().open(asset);
                 OutputStream out = new FileOutputStream(target)) {
                copyStream(in, out);
            } catch (Exception error) {
                flog("ensureExecInterceptor: failed to copy " + name + ": " + error);
            }
        }
        return interceptor != null && interceptor.isFile() ? interceptor : null;
    }

    /**
     * Copies the APK's prebuilt wheels to disk so pip can install from them.
     *
     * <p>The bundled interpreter is a Termux CPython 3.14 build, and packages
     * whose native part is Rust ({@code cryptography}, {@code bcrypt}) have no
     * wheel published for that ABI and cannot be compiled on the device. Termux
     * publishes binaries for this exact interpreter, so
     * {@code toolchain-release/build_python_wheelhouse.py} repacks them into
     * wheels under {@code assets/wheels} and this method exposes them through
     * {@code PIP_FIND_LINKS}.</p>
     *
     * @return the wheelhouse directory, or {@code null} when no wheels ship
     */
    private File ensureWheelhouse(File realFilesDir) {
        String[] wheels;
        try {
            wheels = context.getAssets().list(WHEELS_ASSET_DIR);
        } catch (Exception error) {
            return null;
        }
        if (wheels == null || wheels.length == 0) return null;

        File wheelhouse = new File(realFilesDir, "wheelhouse");
        if (!wheelhouse.exists() && !wheelhouse.mkdirs()) return null;

        // Drop wheels left over from an earlier build. A stale file keeps its
        // old compatibility tag, and pip would happily consider it alongside the
        // current one.
        java.util.Set<String> shipped = new HashSet<>(java.util.Arrays.asList(wheels));
        File[] existing = wheelhouse.listFiles();
        if (existing != null) {
            for (File candidate : existing) {
                if (candidate.isFile() && candidate.getName().endsWith(".whl")
                        && !shipped.contains(candidate.getName())) {
                    if (candidate.delete()) flog("ensureWheelhouse: removed stale " + candidate.getName());
                }
            }
        }

        for (String wheel : wheels) {
            if (wheel == null || !wheel.endsWith(".whl")) continue;
            File target = new File(wheelhouse, wheel);
            // Assets are immutable per install, so an existing file of the same
            // size is already the right one; re-copying on every start would
            // burn I/O for nothing.
            try (InputStream in = context.getAssets().open(WHEELS_ASSET_DIR + "/" + wheel)) {
                if (target.isFile() && target.length() == in.available()) continue;
            } catch (Exception ignored) {
                continue;
            }
            try (InputStream in = context.getAssets().open(WHEELS_ASSET_DIR + "/" + wheel);
                 OutputStream out = new FileOutputStream(target)) {
                copyStream(in, out);
            } catch (Exception error) {
                flog("ensureWheelhouse: failed to copy " + wheel + ": " + error);
            }
        }
        return wheelhouse;
    }

    /** Locates {@code lib/python3.x/site-packages} without hardcoding the version. */
    private File findSitePackages(File pythonHome) {
        File lib = new File(pythonHome, "lib");
        File[] children = lib.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            if (!child.getName().startsWith("python")) continue;
            File site = new File(child, "site-packages");
            if (site.isDirectory() || site.mkdirs()) return site;
        }
        return null;
    }

    private String buildExecShimSource() {
        return "\"\"\"Route child execs of bundled tools through the system linker.\n"
                + "\n"
                + "Generated by MultiAI ToolsManager; edits are overwritten on startup.\n"
                + "Android denies execve() inside the app data directory, so a direct\n"
                + "subprocess call on sys.executable raises PermissionError. Mapping the\n"
                + "binary is allowed, so 'linker64 <binary>' works instead.\n"
                + "\"\"\"\n"
                + "import os\n"
                + "import sys\n"
                + "\n"
                + "_LINKER = " + pythonLiteral(LINKER64) + "\n"
                + "\n"
                + "\n"
                + "def _tools_root():\n"
                + "    root = os.environ.get('MULTIAI_TOOLS_DIR')\n"
                + "    if root:\n"
                + "        return os.path.realpath(root)\n"
                + "    # Fallback for children started with -E: <tools>/python is sys.prefix.\n"
                + "    return os.path.realpath(os.path.dirname(sys.prefix))\n"
                + "\n"
                + "\n"
                + "_ROOT = _tools_root()\n"
                + "\n"
                + "\n"
                + "def _is_bundled_elf(path):\n"
                + "    if not path or not _ROOT or not os.path.exists(_LINKER):\n"
                + "        return False\n"
                + "    try:\n"
                + "        real = os.path.realpath(os.fspath(path))\n"
                + "    except (TypeError, ValueError):\n"
                + "        return False\n"
                + "    if not real.startswith(_ROOT + os.sep) or not os.path.isfile(real):\n"
                + "        return False\n"
                + "    try:\n"
                + "        with open(real, 'rb') as handle:\n"
                + "            return handle.read(4) == b'\\x7fELF'\n"
                + "    except OSError:\n"
                + "        return False\n"
                + "\n"
                + "\n"
                + "def _rewrite(program, arguments):\n"
                + "    \"\"\"Returns (program, arguments) with the linker prepended when needed.\"\"\"\n"
                + "    if not _is_bundled_elf(program):\n"
                + "        return program, arguments\n"
                + "    real = os.path.realpath(os.fspath(program))\n"
                + "    return _LINKER, [_LINKER, real, *list(arguments)[1:]]\n"
                + "\n"
                + "\n"
                + "def _install_subprocess_hook():\n"
                + "    import subprocess\n"
                + "    original = subprocess.Popen.__init__\n"
                + "    if getattr(original, '_multiai_shim', False):\n"
                + "        return\n"
                + "\n"
                + "    def patched(self, args, *rest, **options):\n"
                + "        executable = options.get('executable')\n"
                + "        if isinstance(args, (list, tuple)) and args:\n"
                + "            program = executable if executable else args[0]\n"
                + "            if _is_bundled_elf(program):\n"
                + "                real = os.path.realpath(os.fspath(program))\n"
                + "                args = [_LINKER, real, *list(args)[1:]]\n"
                + "                options.pop('executable', None)\n"
                + "        elif isinstance(args, str) and _is_bundled_elf(executable):\n"
                + "            options['executable'] = None\n"
                + "        return original(self, args, *rest, **options)\n"
                + "\n"
                + "    patched._multiai_shim = True\n"
                + "    subprocess.Popen.__init__ = patched\n"
                + "\n"
                + "\n"
                + "def _install_os_hooks():\n"
                + "    for name in ('execv', 'execvp'):\n"
                + "        original = getattr(os, name, None)\n"
                + "        if original is None or getattr(original, '_multiai_shim', False):\n"
                + "            continue\n"
                + "\n"
                + "        def make(original=original):\n"
                + "            def patched(path, arguments):\n"
                + "                path, arguments = _rewrite(path, arguments)\n"
                + "                return original(path, arguments)\n"
                + "            patched._multiai_shim = True\n"
                + "            return patched\n"
                + "\n"
                + "        setattr(os, name, make())\n"
                + "\n"
                + "    for name in ('execve', 'execvpe'):\n"
                + "        original = getattr(os, name, None)\n"
                + "        if original is None or getattr(original, '_multiai_shim', False):\n"
                + "            continue\n"
                + "\n"
                + "        def make(original=original):\n"
                + "            def patched(path, arguments, environment):\n"
                + "                path, arguments = _rewrite(path, arguments)\n"
                + "                return original(path, arguments, environment)\n"
                + "            patched._multiai_shim = True\n"
                + "            return patched\n"
                + "\n"
                + "        setattr(os, name, make())\n"
                + "\n"
                + "    for name in ('posix_spawn', 'posix_spawnp'):\n"
                + "        original = getattr(os, name, None)\n"
                + "        if original is None or getattr(original, '_multiai_shim', False):\n"
                + "            continue\n"
                + "\n"
                + "        def make(original=original):\n"
                + "            def patched(path, arguments, environment, **options):\n"
                + "                path, arguments = _rewrite(path, arguments)\n"
                + "                return original(path, arguments, environment, **options)\n"
                + "            patched._multiai_shim = True\n"
                + "            return patched\n"
                + "\n"
                + "        setattr(os, name, make())\n"
                + "\n"
                + "\n"
                + "try:\n"
                + "    _install_os_hooks()\n"
                + "    _install_subprocess_hook()\n"
                + "except Exception:\n"
                + "    # A broken shim must never stop the interpreter from starting.\n"
                + "    pass\n";
    }

    /** Quotes a path for embedding into generated Python source. */
    private static String pythonLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * Create npm/npx wrapper scripts in node/ if the shipped scripts have
     * broken shebangs pointing at a different install prefix. The wrappers
     * use the absolute path to the node binary so they work regardless of
     * PATH or shebang issues.
     */
    private void ensureNpmWrappers(File nodeHome) {
        File nodeBin = new File(nodeHome, "node");
        if (!nodeBin.exists()) return; // Node not installed yet
        String nodePath = nodeBin.getAbsolutePath();
        String npmCli = new File(nodeHome, "lib/node_modules/npm/bin/npm-cli.js").getAbsolutePath();
        String npxCli = new File(nodeHome, "lib/node_modules/npm/bin/npx-cli.js").getAbsolutePath();
        String[][] entries = {
                {"npm", npmCli},
                {"npx", npxCli}
        };
        for (String[] entry : entries) {
            File wrapper = new File(nodeHome, entry[0]);
            try {
                java.io.FileWriter w = new java.io.FileWriter(wrapper);
                w.write("#!/system/bin/sh\n");
                w.write("exec \"" + nodePath + "\" \"" + entry[1] + "\" \"$@\"\n");
                w.close();
                wrapper.setExecutable(true, false);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Rewrite hardcoded shebangs in extracted scripts.
     * Some bundled tools were built for a different install prefix
     * (/data/data/<other-package>/files/toolchain/...) so shebangs may
     * point at a path that doesn't exist here. Rewrite any such prefix
     * to the actual path on this device:
     * /data/data/app.multiai.mobile/files/tools/...
     */
    private void rewriteShebangs(File dir, File realFilesDir) {
        String newPrefix = toolsDir.getAbsolutePath();
        flog("rewriteShebangs: any /data/data/*/files/toolchain -> " + newPrefix);
        rewriteShebangsRecursive(dir, newPrefix);
    }

    private void rewriteShebangsRecursive(File dir, String newPrefix) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) {
                try {
                    // Only read first 512 bytes to check shebang — never read
                    // entire file (some are 50MB+ binaries → OOM).
                    byte[] header = new byte[512];
                    String oldPrefix;
                    try (InputStream in = new java.io.FileInputStream(file)) {
                        int read = in.read(header);
                        if (read <= 0) continue;
                        String firstLine = new String(header, 0, read);
                        if (!firstLine.startsWith("#!/data/data/")) continue;
                        // Find "/files/toolchain" in the shebang — the package
                        // name may contain dots, so a simple split on '/' is
                        // not reliable. Search for the literal substring.
                        int tcIdx = firstLine.indexOf("/files/toolchain");
                        if (tcIdx < 0) continue;
                        oldPrefix = firstLine.substring(0, tcIdx) + "/files/toolchain";
                        if (oldPrefix.equals("#!" + newPrefix) || oldPrefix.equals(newPrefix)) continue;
                    }

                    // Re-read only the first line properly
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new java.io.FileInputStream(file)))) {
                        String shebang = reader.readLine();
                        if (shebang == null || !shebang.contains(oldPrefix)) continue;

                        // We need to rewrite — read full file THIS TIME but only
                        // for small text files (scripts), skip files > 1MB
                        if (file.length() > 1_000_000) continue;

                        String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                        String rewritten = content.replace(oldPrefix, newPrefix);
                        try (OutputStream out = new FileOutputStream(file)) {
                            out.write(rewritten.getBytes());
                        }
                        flog("rewriteShebangs: fixed " + file.getAbsolutePath());
                    }
                } catch (Exception e) {
                    // Binary files or read errors — skip
                }
            } else if (file.isDirectory()) {
                rewriteShebangsRecursive(file, newPrefix);
            }
        }
    }

    private void fixUnderscoreDirs(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                // Assets starting with _ are stored with x_ prefix by the build
                // system. Strip the x_ prefix to restore the original name.
                // e.g. x_internal -> _internal, x__phello__ -> __phello__
                if (file.getName().startsWith("x_")) {
                    String newName = "_" + file.getName().substring(2);
                    File newDir = new File(dir, newName);
                    flog("fixUnderscoreDirs: " + file.getName() + " -> " + newName);
                    if (file.renameTo(newDir)) {
                        fixUnderscoreDirs(newDir);
                        continue;
                    } else {
                        flog("fixUnderscoreDirs: RENAME FAILED for " + file.getAbsolutePath());
                    }
                }
                fixUnderscoreDirs(file);
            }
        }
    }

    /**
     * Extract libnative-platform.so from the native-platform-linux-aarch64 jar
     * into lib/ so Gradle can load it via LD_LIBRARY_PATH.
     */
    private void extractNativePlatformLib(File toolsDir) {
        File gradleLib = new File(toolsDir, "gradle/lib");
        File targetDir = new File(toolsDir, "lib");
        targetDir.mkdirs();

        flog("extractNativePlatformLib: gradleLib=" + gradleLib + " exists=" + gradleLib.exists());

        File[] allJars = gradleLib.listFiles();
        File found = null;
        if (allJars != null) {
            for (File f : allJars) {
                String name = f.getName();
                // Must be exactly "native-platform-linux-aarch64-*.jar",
                // NOT the ncurses variants (which don't contain libnative-platform.so)
                if (name.startsWith("native-platform-linux-aarch64-") &&
                        name.endsWith(".jar") &&
                        !name.contains("ncurses")) {
                    found = f;
                    break;
                }
            }
        }

        if (found == null) {
            flog("extractNativePlatformLib: no aarch64 jar found in " + gradleLib);
            return;
        }

        flog("extractNativePlatformLib: found jar " + found.getName());

        try {
            java.util.jar.JarFile jar = new java.util.jar.JarFile(found);
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith("libnative-platform.so")) {
                    // Extract to multiple locations: lib/ (for LD_LIBRARY_PATH),
                    // java/lib/ (JVM's default lib dir), and java/lib/server/
                    File[] targets = {
                        new File(targetDir, "libnative-platform.so"),
                        new File(toolsDir, "java/lib/libnative-platform.so")
                    };
                    for (File out : targets) {
                        out.getParentFile().mkdirs();
                        try (InputStream in = jar.getInputStream(entry);
                             OutputStream out2 = new FileOutputStream(out)) {
                            copyStream(in, out2);
                        }
                        out.setExecutable(true, false);
                    }
                    flog("extractNativePlatformLib: extracted to " + targets.length + " locations, size=" + targets[0].length());
                    break;
                }
            }
            jar.close();
        } catch (Exception e) {
            flog("extractNativePlatformLib error: " + e);
        }
    }

    /**
     * Replace the stock gradle/bin/gradle script (which uses xargs and breaks
     * on Android toybox) with a minimal launcher that calls java directly.
     */
    private void fixGradleWrapper(File toolsDir) {
        File gradleBin = new File(toolsDir, "gradle/bin/gradle");
        File gradleHome = new File(toolsDir, "gradle");
        File javaHome = new File(toolsDir, "java");
        File commonLib = new File(toolsDir, "lib");
        File multiaiGradleBuildJar = new File(commonLib, "multiai-gradle-build.jar");
        if (!gradleBin.exists()) return;

        try {
            String tmpDir = new File(context.getFilesDir(), "tmp").getAbsolutePath();
            new File(tmpDir).mkdirs();
            // Use MultiAiGradleBuild (embedded mode via GradleConnector.embedded(true))
            // instead of GradleMain wrapper. This avoids forking a daemon process
            // (which fails due to loopback socket + tmpdir issues on Android).
            // Direct port of the proven approach from MultiAI Toolchain Test.
            String gradleCp = multiaiGradleBuildJar.getAbsolutePath() + ":" +
                    new File(gradleHome, "lib").getAbsolutePath() + "/*";
            String standardCp = new File(gradleHome, "lib").getAbsolutePath() + "/*";
            String aapt2Path = new File(toolsDir, "android-sdk/bin/aapt2").getAbsolutePath();
            String wrapper = "#!/system/bin/sh\n" +
                    "JAVA_HOME=\"" + javaHome.getAbsolutePath() + "\"\n" +
                    "TMPDIR=\"" + tmpDir + "\"\n" +
                    "case \"${1:-}\" in\n" +
                    "  --version|-v|-version|--help|-h|help)\n" +
                    "    exec \"$JAVA_HOME/bin/java\" -Djava.io.tmpdir=\"" + tmpDir + "\" " +
                    "-Djansi.passthrough=true -Dorg.gradle.native=false " +
                    "-cp \"" + standardCp + "\" org.gradle.launcher.GradleMain --no-daemon \"$@\" ;;\n" +
                    "esac\n" +
                    "exec \"$JAVA_HOME/bin/java\" " +
                    "-Djava.io.tmpdir=\"" + tmpDir + "\" " +
                    "-Djansi.passthrough=true " +
                    "-Dorg.gradle.native=false " +
                    "-Dandroid.aapt2FromMavenOverride=\"" + aapt2Path + "\" " +
                    "-cp \"" + gradleCp + "\" " +
                    "MultiAiGradleBuild \"$PWD\" \"" + gradleHome.getAbsolutePath() + "\" \"$@\"\n";
            try (OutputStream out = new FileOutputStream(gradleBin)) {
                out.write(wrapper.getBytes());
            }
            gradleBin.setExecutable(true, false);
            flog("fixGradleWrapper: replaced gradle/bin/gradle with embedded MultiAiGradleBuild launcher");
        } catch (Exception e) {
            flog("fixGradleWrapper error: " + e);
        }
    }

    /**
     * Replace every aapt2 binary shipped under the SDK (build-tools/* and
     * any AGP-downloaded copy in the Gradle transforms cache) with our
     * working aarch64 aapt2. The SDK build-tools ship x86_64 aapt2 binaries
     * (Google only publishes x86_64-linux aapt2 in the build-tools packages
     * and in Maven), which fail with "syntax error: unexpected '('" when
     * Android tries to exec them and falls back to /system/bin/sh. AGP's
     * android.aapt2FromMavenOverride gradle property (written to
     * gradle.properties in setupEnvironment) is the primary fix, but AGP
     * can still end up caching the broken binary in its transforms cache
     * from an earlier run - clearing that cache and replacing the
     * build-tools copy is belt-and-suspenders.
     */
    private void patchAapt2(File toolsDir) {
        File goodAapt2 = new File(toolsDir, "android-sdk/bin/aapt2");
        if (!goodAapt2.exists() || goodAapt2.length() < 100000) {
            flog("patchAapt2: no good aarch64 aapt2 at " + goodAapt2);
            return;
        }
        // Replace build-tools/<ver>/aapt2
        File buildTools = new File(toolsDir, "android-sdk/build-tools");
        if (buildTools.isDirectory()) {
            File[] versions = buildTools.listFiles();
            if (versions != null) {
                for (File ver : versions) {
                    if (!ver.isDirectory()) continue;
                    File btAapt2 = new File(ver, "aapt2");
                    if (btAapt2.exists()) {
                        try {
                            copyFile(goodAapt2, btAapt2);
                            btAapt2.setExecutable(true, false);
                            flog("patchAapt2: replaced " + btAapt2);
                        } catch (Exception e) {
                            flog("patchAapt2 build-tools error: " + e);
                        }
                    }
                }
            }
        }
        // Wipe stale AGP transforms cache so the x86_64 aapt2 from a
        // previous build (cached before the override took effect) is not
        // reused. AGP will re-resolve and respect the override next run.
        File gradleUserHome = new File(context.getFilesDir(), "gradle-home");
        File transformsCache = new File(gradleUserHome,
                "caches/transforms");
        if (transformsCache.isDirectory()) {
            deleteRecursively(transformsCache);
            flog("patchAapt2: cleared transforms cache " + transformsCache);
        }
        File modulesCache = new File(gradleUserHome,
                "caches/modules-2/files-2.1/com.android.tools.build/aapt2");
        if (modulesCache.isDirectory()) {
            deleteRecursively(modulesCache);
            flog("patchAapt2: cleared aapt2 maven cache");
        }
    }

    /**
     * Restore git's libexec/git-core hardlinks that were destroyed by the
     * APK asset packaging. In the original tarball, every git built-in
     * (git-add, git-clone, ...) is a hardlink to the same git ELF binary
     * in bin/git; when assets are packed into the APK and extracted with
     * AssetManager, those hardlinks come back as 0-byte regular files.
     * Git then fails to spawn its remote helpers ("remote helper 'https'
     * aborted session") because it execs git-core/git which is empty.
     *
     * Fix: copy bin/git over every 0-byte file in libexec/git-core. The
     * git binary dispatches on argv[0], so one binary can stand in for
     * all built-ins. Also create the missing templates directory so
     * 'git init'/'git clone' stop warning about it.
     */
    private void fixGitHardlinks(File toolsDir) {
        File gitBin = new File(toolsDir, "git/bin/git");
        if (!gitBin.exists() || gitBin.length() < 100000) {
            flog("fixGitHardlinks: git binary missing or too small: " + gitBin);
            return;
        }
        File coreDir = new File(toolsDir, "git/libexec/git-core");
        if (!coreDir.isDirectory()) {
            flog("fixGitHardlinks: no git-core dir at " + coreDir);
            return;
        }
        int fixed = 0;
        File[] children = coreDir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String name = f.getName();
            // Only restore git-* / git built-ins that should be hardlinks
            // to the main binary. Leave non-empty helpers (git-remote-https,
            // git-http-backend, scrypt, etc.) and scripts untouched.
            if (!name.equals("git") && !name.startsWith("git-")) continue;
            if (f.isDirectory()) continue;
            if (f.length() > 0) continue;
            try {
                copyFile(gitBin, f);
                f.setExecutable(true, false);
                fixed++;
            } catch (Exception e) {
                flog("fixGitHardlinks: failed to restore " + name + ": " + e);
            }
        }
        // Provide the default templates dir (git looks here for
        // hooks/description/HEAD samples on init/clone).
        File templates = new File(coreDir, "templates");
        File defaultTemplate = new File(toolsDir, "git/share/git-core/templates");
        if (!templates.isDirectory() && defaultTemplate.isDirectory()) {
            try {
                copyRecursively(defaultTemplate, templates);
                flog("fixGitHardlinks: copied templates from " + defaultTemplate);
            } catch (Exception e) {
                flog("fixGitHardlinks: templates copy failed: " + e);
            }
        } else if (!templates.isDirectory()) {
            templates.mkdirs();
            new File(templates, "hooks").mkdirs();
            flog("fixGitHardlinks: created empty templates dir");
        }
        flog("fixGitHardlinks: restored " + fixed + " built-in binaries");

        // Drop a tiny 'man' shim into android-sdk/bin (already on PATH) so
        // 'git help <cmd>' works. Git invokes `man git-<cmd>` to render
        // help; with no man binary on Android it dies with "no man viewer
        // handled the request". Our shim rewrites `man git-<cmd>` (or
        // `man <n> git-<cmd>`) into `git <cmd> --help`, which prints git's
        // own help text directly to stdout.
        File sdkBin = new File(toolsDir, "android-sdk/bin");
        if (sdkBin.isDirectory()) {
            File manShim = new File(sdkBin, "man");
            String script = "#!/system/bin/sh\n" +
                    "# man shim for git help: rewrite `man git-X` -> `git X -h`\n" +
                    "# (NOT --help: that recurses back into man git-X.)\n" +
                    "cmd=\"\"\n" +
                    "for a in \"$@\"; do\n" +
                    "  case \"$a\" in\n" +
                    "    [0-9]) ;;            # skip section number\n" +
                    "    -*) ;;               # skip flags\n" +
                    "    git-*) cmd=\"${a#git-}\"; break;;\n" +
                    "    *) break;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -n \"$cmd\" ]; then\n" +
                    "  exec git \"$cmd\" -h\n" +
                    "fi\n" +
                    "echo \"man: no manual available for: $*\" >&2\n" +
                    "exit 1\n";
            try (OutputStream out = new FileOutputStream(manShim)) {
                out.write(script.getBytes());
                manShim.setExecutable(true, false);
                flog("fixGitHardlinks: installed man shim at " + manShim);
            } catch (Exception e) {
                flog("fixGitHardlinks: man shim failed: " + e);
            }
        }
    }

    /**
     * Build a PEM CA bundle from the system trust store at
     * /system/etc/security/cacerts-*. Android's git (built from Termux)
     * hardcodes the path /data/data/com.termux/files/usr/etc/tls/cert.pem
     * which doesn't exist in our app's private storage, so every HTTPS
     * clone dies with "error adding trust anchors from file: ...termux..."
     * We concatenate the system certificates into tools/cacerts/bundle.pem
     * and point git (and the shell's openssl) at it via env vars.
     */
    private void fixCaBundle(File toolsDir) {
        File sysDir = new File("/system/etc/security/cacerts");
        if (!sysDir.isDirectory()) {
            flog("fixCaBundle: no system certs dir at " + sysDir);
            return;
        }
        File outDir = new File(toolsDir, "cacerts");
        outDir.mkdirs();
        File bundle = new File(outDir, "bundle.pem");
        if (bundle.exists() && bundle.length() > 10000) {
            flog("fixCaBundle: bundle exists, size=" + bundle.length());
            return;
        }
        int written = 0;
        try (OutputStream out = new FileOutputStream(bundle)) {
            File[] files = sysDir.listFiles();
            if (files == null) return;
            // Sort for deterministic output
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : files) {
                if (!f.isFile() || f.length() == 0) continue;
                try (java.io.InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.write('\n');
                    written++;
                } catch (Exception e) {
                    // skip unreadable individual certs
                }
            }
        } catch (Exception e) {
            flog("fixCaBundle: failed: " + e);
            return;
        }
        flog("fixCaBundle: wrote " + written + " certs to " + bundle +
                " size=" + bundle.length());
    }

    private void copyRecursively(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids == null) return;
            for (File k : kids) copyRecursively(k, new File(dst, k.getName()));
        } else {
            copyFile(src, dst);
            dst.setExecutable(true, false);
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (java.io.InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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
        if (isSymbolicLink(file)) {
            file.delete();
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
