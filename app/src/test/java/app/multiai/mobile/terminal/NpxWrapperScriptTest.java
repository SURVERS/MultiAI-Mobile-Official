package app.multiai.mobile.terminal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Контракт генератора npx-обёртки: чинит exec-бит ДО и ПОСЛЕ прогона,
 * держит чистое shell-окружение, ретрай после chmod, без скрытых путей.
 * Скрипт исполняется /system/bin/sh на устройстве — здесь проверяем его
 * статическую структуру.
 */
public class NpxWrapperScriptTest {

    private static final String NODE = "/data/data/app.multiai.mobile/files/tools/node/node";
    private static final String NPX_CLI =
            "/data/data/app.multiai.mobile/files/tools/node/lib/node_modules/npm/bin/npx-cli.js";

    private static String script() {
        return ToolsManager.buildNpxWrapperScript(NODE, NPX_CLI);
    }

    @Test
    public void containsShebanglessBodyInvokingRealNpxCli() {
        String script = script();
        assertTrue(script.contains("\"" + NODE + "\" \"" + NPX_CLI + "\" \"$@\""));
        assertFalse("body must not repeat the shebang (ensureNpmWrappers writes it)",
                script.startsWith("#!"));
    }

    @Test
    public void chmodRunsBeforeAndAfterTheNpxRun() {
        String script = script();
        int chmodDef = script.indexOf("_chmod_cache()");
        int firstCall = script.indexOf("_chmod_cache\n", chmodDef + 1);
        int runCall = script.indexOf("_run \"$@\"", firstCall);
        int postRepair = script.indexOf("_chmod_cache", script.indexOf("_rc=$?"));
        assertTrue("wrapper must define _chmod_cache", chmodDef >= 0);
        assertTrue("wrapper must invoke _chmod_cache before the run", firstCall >= 0);
        assertTrue("wrapper must run npx after the pre-run chmod", runCall > firstCall);
        assertTrue("wrapper must chmod again after the run (first-run fix)",
                postRepair > runCall);
    }

    @Test
    public void retriesOnceAfterFailureFollowedByChmod() {
        String script = script();
        int retryBranch = script.indexOf("if [ $_rc -ne 0 ]");
        int retryRun = script.indexOf("_run \"$@\"", retryBranch);
        assertTrue("wrapper must retry npx once on failure", retryBranch >= 0 && retryRun > retryBranch);
        assertTrue("retry branch must chmod again afterwards",
                script.indexOf("_chmod_cache", retryRun) > retryRun);
        assertTrue("wrapper must preserve the original exit code",
                script.contains("exit $_rc"));
    }

    @Test
    public void forcesCleanShellEnvironmentForNpmChildren() {
        String script = script();
        // PATH ре-резолвится из MULTIAI_TOOLS_DIR (npm сбрасывает PATH как root).
        assertTrue(script.contains("MULTIAI_TOOLS_DIR="));
        assertTrue(script.contains("export PATH"));
        assertTrue(script.contains("SHELL=/system/bin/sh"));
        assertTrue(script.contains("BASH_ENV=/system/bin/sh"));
        // IFS сбрасывается на дефолт — переменная не наследуется мусорной.
        assertTrue(script.contains("IFS="));
    }

    @Test
    public void chmodTargetsOnlyDotBinDirectoriesInCache() {
        String script = script();
        // Чиним только shebang-бинари в .bin кэша npx/npm, не весь каталог.
        assertTrue(script.contains("-type d -name .bin"));
        assertTrue(script.contains("${NPM_CONFIG_CACHE:-$HOME/.npm}"));
    }
}
