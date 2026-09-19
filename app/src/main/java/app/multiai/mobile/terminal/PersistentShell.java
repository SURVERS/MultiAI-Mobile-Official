package app.multiai.mobile.terminal;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PersistentShell — a long-lived interactive /system/bin/sh process.
 *
 * Why two stdin channels:
 *  -------------------------
 *  Naively feeding "<command>\n<marker-probe>\n" to sh works fine for
 *  commands that never read stdin (ls, cd, gradle, ...). But the moment a
 *  command DOES read stdin (read, adb pair, python input(), node
 *  readline), it gobbles up the marker line we queued right after it,
 *  so the marker never fires and the UI stalls. Fixing the marker delay
 *  doesn't help: the marker is always behind the command in the same
 *  pipe.
 *
 *  Solution: give each user command its OWN stdin via a FIFO. At shell
 *  startup we create cmd_input.fifo and open fd 3 on it. Each command is
 *  launched as  { command; } <&3   so the command reads user input from
 *  fd 3 (the FIFO), while the control stream — the marker probe — stays
 *  on fd 0 (the pipe Java owns). They never collide.
 *
 *  User keystrokes in INPUT mode are written to the FIFO (fd 3 ->
 *  command's stdin). The marker probe runs the instant the command
 *  finishes because fd 0 was never touched by the command itself.
 *
 * Lifecycle:
 *  - start() spawns sh, creates the FIFO, opens fd 3 inside the shell,
 *    opens the FIFO write-end from Java.
 *  - sendCommand(cmd): writes "{ cmd; } <&3" + marker probe to fd 0.
 *  - sendInput(line): writes line + "\n" to the FIFO write-end.
 *  - The reader thread parses output, strips markers, reports cwd/exit
 *    back to the UI, and flips into INPUT mode when the command is
 *    blocked on stdin (no marker, no output for a moment).
 *
 * The Activity holds this object across rotation (configChanges), so the
 * shell process survives orientation changes.
 */
public class PersistentShell {

    private static final String TAG = "MultiAITerminal";
    private static final String MARKER_PREFIX = "\u0001WGEND";
    private static final long IDLE_TIMEOUT_MS = 1200;
    private static final String FIFO_NAME = "cmd_input.fifo";

    public interface Listener {
        /** Streamed raw output (marker already stripped). UI thread. */
        void onOutput(String text);
        /** A command finished (exit>=0) OR is waiting for stdin
         *  (waitingForInput=true, exit<0). cwd is the new PWD. UI thread. */
        void onCommandDone(int exitCode, String cwd, boolean waitingForInput);
        /** Shell process died. UI thread. */
        void onDied();
    }

    private final Context context;
    private final ToolsManager tools;
    private final Listener listener;

    private Process process;
    private OutputStream stdin;       // fd 0 — control stream (commands + markers)
    private InputStream stdout;
    private OutputStream fifoWrite;   // user input -> fd 3 of the shell

    private File fifo;

    private final AtomicBoolean alive = new AtomicBoolean(false);
    private volatile boolean commandInFlight = false;
    private volatile boolean inputMode = false;
    private volatile long lastOutputTime = 0;
    private volatile String lastCwd = "";

    private Thread readerThread;
    private Thread idleWatcherThread;

    public PersistentShell(Context context, ToolsManager tools, Listener listener) {
        this.context = context;
        this.tools = tools;
        this.listener = listener;
    }

    public boolean isAlive() {
        return alive.get() && process != null && process.isAlive();
    }

    /** Start the underlying sh process and the FIFO plumbing. */
    public void start() {
        if (alive.get()) return;
        Map<String, String> env = tools.getEnv();
        File home = new File(env.getOrDefault("HOME",
                context.getFilesDir().getAbsolutePath()));

        // Create the FIFO once. mkfifo is idempotent enough if the file
        // is already a FIFO; if a stale regular file is in the way,
        // remove it first.
        fifo = new File(home, FIFO_NAME);
        try {
            if (fifo.exists()) fifo.delete();
            Os.mkfifo(fifo.getAbsolutePath(), 0700);
        } catch (Exception e) {
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh");
            pb.redirectErrorStream(true);
            pb.directory(home);
            pb.environment().clear();
            pb.environment().putAll(tools.filterAndroidRuntimeKeys(env));
            process = pb.start();
            stdin = process.getOutputStream();
            stdout = process.getInputStream();
            alive.set(true);

            // Tell sh to attach fd 3 to the FIFO. This call BLOCKS inside
            // sh until a writer opens the FIFO, so we must open our write
            // end in parallel right now.
            writeStdin("exec 3<" + fifo.getAbsolutePath() + "\n");

            // Open the write end on a background thread (open() blocks
            // until the reader side connects, which is the exec 3< above).
            new Thread(() -> {
                try {
                    // Retry briefly in case sh hasn't reached exec yet.
                    for (int i = 0; i < 50; i++) {
                        try {
                            fifoWrite = new FileOutputStream(fifo);
                            break;
                        } catch (Exception e) {
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        }
                    }
                } catch (Exception e) {
                }
            }, "WGT-fifo-open").start();

            // Install linker64-backed functions before any user command is
            // queued. They persist for the lifetime of this interactive shell.
            String bootstrap = tools.getShellBootstrap();
            if (bootstrap != null && !bootstrap.isEmpty()) {
                writeStdin(bootstrap + "\n");
            }

            readerThread = new Thread(this::readLoop, "WGT-shell-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            idleWatcherThread = new Thread(this::idleWatcher, "WGT-shell-idle");
            idleWatcherThread.setDaemon(true);
            idleWatcherThread.start();
        } catch (Exception e) {
            alive.set(false);
        }
    }

    /** Send a user command. Wrapped so it reads stdin from the FIFO. */
    public synchronized void sendCommand(String command) {
        if (!isAlive()) return;
        if (inputMode) {
            // Already feeding an interactive command; treat as input.
            sendInput(command);
            return;
        }
        // Bare "python" (no args) would drop into REPL and stall the terminal.
        // Auto-append --help so the user sees usage instead of a hung prompt.
        String effective = expandBareInterpreters(command);
        // Define linker64-backed functions for every command. This deliberately
        // does not rely on start-up ordering: a persisted shell can otherwise
        // miss its initial bootstrap after FIFO creation races.
        String bootstrap = tools.getShellBootstrap();
        String prepared = bootstrap == null || bootstrap.isEmpty()
                ? effective : bootstrap + "\n" + effective;
        commandInFlight = true;
        lastOutputTime = System.currentTimeMillis();
        // { ...; } <&3 runs the command in the CURRENT shell (so cd /
        // export persist) while redirecting its stdin to fd 3 (the FIFO).
        // The marker probe runs on fd 0 once the group completes.
        String wrapped =
                "{ " + prepared + " ; } <&3\n" +
                "printf '" + MARKER_PREFIX + "%d\\1%s\\1\\n' \"$?\" \"$(pwd)\"\n";
        writeStdin(wrapped);
    }

    /**
     * Bare interpreter invocations (e.g. "python", "python3", "node") drop
     * into an interactive REPL that stalls the terminal. Auto-append --help
     * so the user sees usage output instead of a hung prompt.
     *
     * Only triggers when the command is EXACTLY the interpreter name with no
     * arguments — "python script.py" or "python -c ..." are left untouched.
     */
    private static String expandBareInterpreters(String command) {
        if (command == null) return command;
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return command;
        // Split on whitespace to get the first token.
        String[] parts = trimmed.split("\\s+", 2);
        String first = parts[0];
        boolean hasArgs = parts.length > 1 && !parts[1].trim().isEmpty();
        if (hasArgs) return command;
        // Only match bare interpreter names.
        if (first.equals("python") || first.equals("python3")
                || first.equals("node") || first.equals("nodejs")
                || first.equals("ruby") || first.equals("lua")) {
            return first + " --help";
        }
        return command;
    }

    /** Feed one line of user input to the running command's stdin. */
    public void sendInput(String line) {
        if (fifoWrite == null) return;
        synchronized (fifoWrite) {
            try {
                fifoWrite.write((line + "\n").getBytes());
                fifoWrite.flush();
                lastOutputTime = System.currentTimeMillis();
            } catch (Exception e) {
            }
        }
    }

    /** Signal end-of-input to the running command by closing its stdin. */
    public void sendEof() {
        // Closing the FIFO write-end makes the command's read() return EOF.
        // We then reopen a fresh write-end for the next command.
        try {
            if (fifoWrite != null) {
                fifoWrite.close();
                fifoWrite = null;
            }
        } catch (Exception ignored) {}
        // Reopen for the next command.
        new Thread(() -> {
            try {
                fifoWrite = new FileOutputStream(fifo);
            } catch (Exception e) {
            }
        }, "WGT-fifo-reopen").start();
    }

    private void writeStdin(String s) {
        try {
            stdin.write(s.getBytes());
            stdin.flush();
        } catch (Exception e) {
        }
    }

    private void readLoop() {
        byte[] buf = new byte[4096];
        StringBuilder accum = new StringBuilder();
        try {
            int n;
            while ((n = stdout.read(buf)) != -1) {
                String chunk = new String(buf, 0, n);
                lastOutputTime = System.currentTimeMillis();
                accum.append(chunk);
                drainAccum(accum);
            }
        } catch (Exception e) {
            if (alive.get()) {
            }
        } finally {
            alive.set(false);
            tools.postOnUi(listener::onDied);
        }
    }

    /** Pull complete lines out of accum, detect & strip the marker. */
    private void drainAccum(StringBuilder accum) {
        while (true) {
            int mIdx = accum.indexOf(MARKER_PREFIX);
            if (mIdx >= 0) {
                String before = accum.substring(0, mIdx);
                if (!before.isEmpty()) emitOutput(before);
                int after = accum.indexOf("\n", mIdx);
                if (after < 0) return; // marker line incomplete
                String markerLine = accum.substring(mIdx, after);
                parseMarkerAndNotify(markerLine);
                accum.delete(0, after + 1);
                continue;
            }
            // No marker yet. Hold back a possible partial-marker tail and
            // any incomplete final line so we don't fragment output.
            int safeLen = accum.length();
            int maxRetain = MARKER_PREFIX.length();
            for (int retain = Math.min(maxRetain, accum.length()); retain > 0; retain--) {
                String tail = accum.substring(accum.length() - retain);
                if (MARKER_PREFIX.startsWith(tail)) {
                    safeLen = accum.length() - retain;
                    break;
                }
            }
            if (safeLen <= 0) return;
            int lastNl = accum.lastIndexOf("\n", safeLen - 1);
            if (lastNl >= 0 && lastNl < safeLen - 1) {
                String emit = accum.substring(0, lastNl + 1);
                if (!emit.isEmpty()) emitOutput(emit);
                accum.delete(0, lastNl + 1);
                return;
            }
            String emit = accum.substring(0, safeLen);
            if (!emit.isEmpty()) emitOutput(emit);
            accum.delete(0, safeLen);
            return;
        }
    }

    private void parseMarkerAndNotify(String markerLine) {
        try {
            String body = markerLine.substring(MARKER_PREFIX.length());
            String[] parts = body.split("\u0001", -1);
            int exit = parts.length > 0 && !parts[0].isEmpty()
                    ? Integer.parseInt(parts[0].trim()) : 0;
            String cwd = parts.length > 1 ? parts[1] : "";
            if (!cwd.isEmpty()) lastCwd = cwd;
            commandInFlight = false;
            inputMode = false;
            final String c = cwd;
            final int e = exit;
            tools.postOnUi(() -> listener.onCommandDone(e, c, false));
        } catch (Exception e) {
            commandInFlight = false;
            inputMode = false;
            tools.postOnUi(() -> listener.onCommandDone(0, lastCwd, false));
        }
    }

    private void emitOutput(String text) {
        StringBuilder clean = new StringBuilder();
        String[] lines = text.split("(?<=\\n)", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("WG:") || trimmed.startsWith("WGT:")
                    || trimmed.startsWith("MG:")) continue;
            clean.append(line);
        }
        if (clean.length() == 0) return;
        final String output = clean.toString();
        tools.postOnUi(() -> listener.onOutput(output));
    }

    /** Flip into INPUT mode when a command looks blocked on stdin. */
    private void idleWatcher() {
        while (alive.get()) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {
                if (!alive.get()) return;
            }
            synchronized (this) {
                if (commandInFlight && !inputMode) {
                    long since = System.currentTimeMillis() - lastOutputTime;
                    if (since > IDLE_TIMEOUT_MS) {
                        inputMode = true;
                        final String c = lastCwd;
                        tools.postOnUi(() -> listener.onCommandDone(-1, c, true));
                    }
                }
            }
        }
    }

    public void stop() {
        alive.set(false);
        try { if (stdin != null) stdin.close(); } catch (Exception ignored) {}
        try { if (stdout != null) stdout.close(); } catch (Exception ignored) {}
        try { if (fifoWrite != null) fifoWrite.close(); } catch (Exception ignored) {}
        if (process != null) ToolsManager.terminateProcessTree(process);
    }
}
