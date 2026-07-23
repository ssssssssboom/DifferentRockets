package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.differentrockets.util.Res;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Shared loader for single-file gameplay scripts (flame.lua, terrain.lua,
 * physics.lua). Source resolution: player mod dir first, built-in assets as
 * fallback. Hot-reload: the player file is stat'ed once per second and the
 * script recompiled when it changes. A script that fails to load is marked
 * broken (logged once) and the caller falls back to the built-in behavior.
 * Not thread-safe — call from the render thread only.
 */
public final class LuaScript {

    private final String fileName;      // e.g. "physics.lua"
    private final String logTag;
    private Globals globals;
    private boolean loadFailed;
    private long playerModStamp = -2;   // lastModified of the player file (-1 = absent)
    private long lastStat;

    public LuaScript(String fileName) {
        this.fileName = fileName;
        this.logTag = "lua:" + fileName;
    }

    /** Live globals, or null when no script exists or it failed to load. */
    public Globals globals() {
        refreshIfNeeded();
        return globals;
    }

    /** Force a re-read on next access (e.g. after resources were reloaded). */
    public void invalidate() {
        playerModStamp = -2;
        lastStat = 0;
    }

    /**
     * Version tag of a script source (round 11 item 1b): the first `-- v...`
     * comment within the first 5 lines, e.g. `-- v2026.07.21`. Returns "?"
     * when absent so old player files are visibly unversioned in the logs.
     */
    public static String versionOf(String src) {
        if (src == null) return "?";
        String[] lines = src.split("\n", 6);
        for (int i = 0; i < Math.min(5, lines.length); i++) {
            String l = lines[i].trim();
            if (l.startsWith("--")) {
                String rest = l.substring(2).trim();
                if (rest.length() > 1 && rest.charAt(0) == 'v'
                        && Character.isDigit(rest.charAt(1))) {
                    int end = rest.indexOf(' ');
                    return end > 0 ? rest.substring(1, end) : rest.substring(1);
                }
            }
        }
        return "?";
    }

    private FileHandle playerFile() {
        FileHandle dir = Res.modDir();
        if (dir == null) return null;
        FileHandle f = dir.child(fileName);
        return f.exists() ? f : null;
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastStat < 1000) return;
        lastStat = now;
        FileHandle pf = playerFile();
        long stamp = pf != null ? pf.lastModified() : -1;
        if (globals != null && stamp == playerModStamp) return; // unchanged
        playerModStamp = stamp;

        String src = null;
        String origin = null;
        String source = null;
        if (pf != null) {
            try {
                src = pf.readString();
                origin = "player mods (" + pf.path() + ")";
                source = "external";
            } catch (Exception ignored) {}
        }
        if (src == null) {
            FileHandle in = Gdx.files.internal("mods/" + fileName);
            if (in.exists()) {
                try {
                    src = in.readString();
                    origin = "built-in assets";
                    source = "builtin";
                } catch (Exception ignored) {}
            }
        }
        if (src == null) {
            if (!loadFailed) {
                loadFailed = true;
                Gdx.app.log(logTag, "no " + fileName + " found anywhere; using built-in defaults");
            }
            globals = null;
            return;
        }
        try {
            Globals g = JsePlatform.standardGlobals();
            g.load(src, fileName).call();
            globals = g;
            loadFailed = false;
            Gdx.app.log(logTag, "loaded " + fileName + " <- " + origin);
            Gdx.app.log("res", "script mod/" + fileName + " v" + versionOf(src)
                    + " source=" + source);
        } catch (LuaError e) {
            globals = null;
            if (!loadFailed) {
                loadFailed = true;
                Gdx.app.error(logTag, "load error (using built-in defaults): " + e.getMessage());
            }
        }
    }
}
