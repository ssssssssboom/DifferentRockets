package com.differentrockets.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.differentrockets.game.LuaScript;

/**
 * Player-facing resource root — THE ONLY storage location this app touches.
 * Android: the SHARED root /storage/emulated/0/DifferentRocket/
 *          (resolved via Environment.getExternalStorageDirectory() — NOT
 *          Gdx.files.getExternalStoragePath(), which since libGDX 1.9.10
 *          returns the app-private Android/data/<pkg>/files/ dir).
 *          Storage-path cleanup (round 28): the app-private fallback root
 *          was REMOVED — nothing is ever read from or written to
 *          /sdcard/Android/data/... or any app-private files dir. When the
 *          shared root is not writable yet (permission pending), resources
 *          fall back to the READ-ONLY built-in internal assets and the
 *          shared root is retried on every resume.
 * Desktop: DifferentRocket/ in the project dir (parent of core/assets, so the
 *          player copy never leaks into the Android assets tree).
 * On first run all default resources are copied there:
 *   <root>/assets/  textures + XML configs
 *   <root>/mod/     Lua part-behavior scripts + planets.lua
 * Versioned sync (round 10): each area keeps a .defaults/ shadow with the
 * last run's factory bytes — files the player never modified are auto-updated
 * when the bundled defaults change, player edits are always kept. A built-in
 * set of known legacy factory SHA-1s migrates installs from before this
 * mechanism existed.
 * If the shared root is not writable, every lookup gracefully falls back to
 * the built-in internal assets (read-only).
 */
public final class Res {

    private static FileHandle root;
    private static FileHandle sharedRoot;    // /sdcard/DifferentRocket (the ONLY root)
    private static boolean external;   // true when the player root is usable

    /**
     * Bundled default files, listed EXPLICITLY. We used to enumerate
     * Gdx.files.internal("").list() / ("mods").list() here, but Android's
     * AssetManager.list() on the APK asset root returns an empty array on a
     * range of devices — the copy loop then ran zero times and the player
     * directories were created but never populated. A hardcoded manifest is
     * deterministic everywhere; SmokeScreen verifies it stays in sync.
     */
    private static final String[] ASSET_FILES = {
            "Atmospheres.png",
            "CommonGui.png",
            "CommonGui.xml",
            "Editor.png",
            "Editor.xml",
            "Inputs.xml",
            "Menu.png",
            "Menu.xml",
            "PartList.xml",
            "PlanetCrustHalley.png",
            "PlanetCrustSmars.png",
            "PlanetCrustSmaturn.png",
            "PlanetCrustSmearth.png",
            "PlanetCrustSmenus.png",
            "PlanetCrustSmeptune.png",
            "PlanetCrustSmercury.png",
            "PlanetCrustSmoon.png",
            "PlanetCrustSmupiter.png",
            "PlanetCrustSmuranus.png",
            "PlanetCrustTitan.png",
            "PlanetSprites.png",
            "PlanetSprites.xml",
            "Runtime.png",
            "Runtime.xml",
            "ShipSprites.png",
            "ShipSprites.xml",
            "SmolarSystem.xml",
    };

    private static final String[] MOD_FILES = {
            "battery-0.lua",
            "control.lua",
            "detacher-1.lua",
            "detacher-2.lua",
            "dock-1.lua",
            "engine-0.lua",
            "engine-1.lua",
            "engine-2.lua",
            "engine-3.lua",
            "engine-4.lua",
            "flame.lua",
            "fueltank-0.lua",
            "fueltank-1.lua",
            "fueltank-2.lua",
            "fueltank-3.lua",
            "fueltank-4.lua",
            "fueltank-5.lua",
            "fuselage-1.lua",
            "ion-0.lua",
            "joints.lua",
            "lander-1.lua",
            "nosecone-1.lua",
            "parachute-1.lua",
            "physics.lua",
            "planets.lua",
            "pod-1.lua",
            "port-1.lua",
            "rcs-1.lua",
            "solar-1.lua",
            "strut-1.lua",
            "terrain.lua",
            "wheel-1.lua",
            "wheel-2.lua",
    };

    private Res() {}

    public static void init() {
        if (root != null) return;
        if (Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
            // Single root (storage-path cleanup, round 28): ONLY the shared
            // /storage/emulated/0/DifferentRocket/ — from
            // Environment.getExternalStorageDirectory() (via reflection — the
            // core module has no android.jar). The app-private
            // Android/data/<pkg>/files fallback was REMOVED: no file is ever
            // read from or written to any private storage dir.
            sharedRoot = Gdx.files.absolute(sharedBase() + "DifferentRocket/");
            Gdx.app.log("res", "shared root: "
                    + sharedRoot.file().getAbsolutePath());
        } else {
            // parent of the working dir (core/assets) so the player copy never
            // lands inside the Android assets tree and leaks into the APK
            sharedRoot = Gdx.files.local("../DifferentRocket/");
        }
        populate();
    }

    /** Environment.getExternalStorageDirectory() via reflection (core has no android.jar). */
    private static String sharedBase() {
        String p = null;
        try {
            Class<?> env = Class.forName("android.os.Environment");
            Object f = env.getMethod("getExternalStorageDirectory").invoke(null);
            p = ((java.io.File) f).getAbsolutePath();
        } catch (Throwable t) {
            Gdx.app.error("res", "Environment.getExternalStorageDirectory() reflection failed", t);
        }
        if (p == null || p.isEmpty() || p.charAt(0) != '/') {
            Gdx.app.log("res", "suspicious shared storage path '" + p
                    + "' — forcing /storage/emulated/0/");
            p = "/storage/emulated/0";
        }
        Gdx.app.log("res", "shared storage base: " + p + ("/storage/emulated/0".equals(p)
                ? " (standard)"
                : " (non-standard — multi-user or work profile? using it anyway)"));
        if (!p.endsWith("/")) p += "/";
        return p;
    }

    /**
     * Re-run the directory check/populate (called on every app resume so the
     * MANAGE_EXTERNAL_STORAGE grant takes effect WITHOUT an app restart).
     * Returns true when the active root CHANGED — built-in fallback -> external,
     * or app-private -> shared — and the caller should then reload
     * atlases/mods/world from the new source.
     */
    public static boolean refresh() {
        boolean wasExternal = external;
        FileHandle oldRoot = root;
        populate();
        if (external && (!wasExternal || root != oldRoot)) {
            Gdx.app.log("res", "resource root switched to "
                    + root.file().getAbsolutePath() + " — reloading resources");
            return true;
        }
        return false;
    }

    /**
     * Populate the shared root when writable. Sets `external`. There is NO
     * private fallback (round 28): when the shared root is not writable the
     * app runs on read-only built-in assets and retries on every resume.
     */
    private static void populate() {
        if (!probeWritable(sharedRoot)) {
            external = false;
            Gdx.app.log("res", "WARNING: shared storage root not writable "
                    + "(storage permission not granted?) — using built-in "
                    + "assets; will retry on every resume");
            return;
        }
        if (root != sharedRoot) {
            root = sharedRoot;
            Gdx.app.log("res", "resource root: " + root.file().getAbsolutePath());
        }
        external = true;
        copyDefaults();
    }

    /** mkdirs + write/delete a probe file — the only reliable writability test. */
    private static boolean probeWritable(FileHandle dir) {
        try {
            dir.mkdirs();
            if (!dir.exists() || !dir.isDirectory()) return false;
            FileHandle probe = dir.child(".probe");
            probe.writeString("ok", false);
            boolean ok = probe.exists();
            probe.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Known legacy factory bytes (round 10): SHA-1 of previously shipped
     * default scripts that predate the versioned-sync mechanism. A player file
     * whose hash is listed here is provably an UNMODIFIED factory copy from an
     * older release and is auto-updated even on first deploy (no .defaults to
     * compare against). Key: "mod/<name>".
     */
    private static final java.util.Map<String, String[]> KNOWN_OLD = buildKnownOld();

    private static java.util.Map<String, String[]> buildKnownOld() {
        java.util.Map<String, String[]> m = new java.util.HashMap<>();
        // round-8 factory physics.lua (reconstructed, verified) and the
        // round-9 build (duplicated gimbal doc paragraph, shipped in the
        // round-9 APK)
        m.put("mod/physics.lua", new String[] {
                "d99717950702341839ef4f41f973b013439b4ab0",
                "4d34baebd83b7d80befc1e66b17267d5b9a5f8d6"});
        // round-8 factory engine script — all six gimbaled engines shipped
        // byte-identical copies (cp-synced), plus the earlier pre-sync variant
        String[] engines = {
                "e7157077feb5a6630ad64662ce3782420afe562a",
                "adf7a55797d22b545b42c46a76855762b98004b8"};
        for (String e : new String[] {"engine-0.lua", "engine-1.lua", "engine-2.lua",
                "engine-3.lua", "engine-4.lua", "ion-0.lua"}) {
            m.put("mod/" + e, engines);
        }
        // round-8 factory flame.lua (triangle-cone plume, pre-particle)
        m.put("mod/flame.lua", new String[] {
                "388285e451bcfd74886ae0f76b5ee769d0e80833"});
        return m;
    }

    /**
     * Versioned default sync (round 10). For every bundled file X:
     *   - mod/X missing                        -> copy the new default        ("copied")
     *   - mod/X byte-equal to the new default  -> nothing to do               ("kept-current")
     *   - mod/X byte-equal to .defaults/X      -> player never touched it;
     *      (the previous run's factory copy)      overwrite with the new one  ("auto-updated")
     *   - mod/X matches a KNOWN_OLD hash       -> provably an old factory
     *      copy from a pre-versioning release;  overwrite with the new one  ("auto-updated")
     *   - otherwise                            -> player-modified (or unknown
     *      on first deploy); keep it           ("kept-player" / "kept-unknown")
     * All comparisons run against the OLD .defaults first; only afterwards is
     * .defaults force-refreshed from the current bundle (it is a reference
     * copy — players must not edit it; _README.txt inside says so).
     * Same mechanism applies to assets/ (textures/XML) with assets/.defaults/.
     * Every decision is logged with tag "res" plus a summary line.
     * Returns {copied, updated, keptCurrent, keptPlayer, keptUnknown, failed}.
     */
    private static int[] syncTree(FileHandle rootDir) {
        int[] n = new int[6];
        syncGroup(rootDir, "assets", "", ASSET_FILES, n);
        syncGroup(rootDir, "mod", "mods/", MOD_FILES, n);
        return n;
    }

    private static void syncGroup(FileHandle rootDir, String group, String internalPrefix,
                                  String[] manifest, int[] n) {
        FileHandle outDir = rootDir.child(group);
        FileHandle defDir = outDir.child(".defaults");
        boolean firstDeploy = !(defDir.exists() && defDir.isDirectory());
        try {
            outDir.mkdirs();
            // pass 1: compare/replace using the OLD .defaults
            for (String name : manifest) {
                FileHandle cur = outDir.child(name);
                FileHandle def = defDir.child(name);
                FileHandle internal;
                try {
                    internal = Gdx.files.internal(internalPrefix + name);
                } catch (Throwable t) {
                    n[5]++;
                    Gdx.app.error("res", "FAILED to read bundled " + group + "/" + name, t);
                    continue;
                }
                if (!cur.exists()) {
                    try {
                        cur.parent().mkdirs();
                        internal.copyTo(cur);
                        n[0]++;
                        Gdx.app.log("res", "copied " + group + "/" + name);
                    } catch (Throwable t) {
                        n[5]++;
                        Gdx.app.error("res", "FAILED to copy " + group + "/" + name, t);
                    }
                    continue;
                }
                byte[] curB, intB;
                try {
                    curB = cur.readBytes();
                    intB = internal.readBytes();
                } catch (Throwable t) {
                    n[5]++;
                    Gdx.app.error("res", "FAILED to read " + group + "/" + name, t);
                    continue;
                }
                if (java.util.Arrays.equals(curB, intB)) {
                    n[2]++;
                    continue; // already current — nothing to log per-file
                }
                boolean legacyFactory = false;
                if (!firstDeploy && def.exists()) {
                    try {
                        legacyFactory = java.util.Arrays.equals(curB, def.readBytes());
                    } catch (Throwable ignored) {}
                }
                if (!legacyFactory) {
                    String[] hashes = KNOWN_OLD.get(group + "/" + name);
                    if (hashes != null) {
                        String h = sha1Hex(curB);
                        for (String k : hashes) {
                            if (k.equals(h)) { legacyFactory = true; break; }
                        }
                    }
                }
                if (legacyFactory) {
                    try {
                        cur.parent().mkdirs();
                        internal.copyTo(cur);
                        n[1]++;
                        Gdx.app.log("res", "auto-updated " + group + "/" + name);
                    } catch (Throwable t) {
                        n[5]++;
                        Gdx.app.error("res", "FAILED to update " + group + "/" + name, t);
                    }
                } else if (name.endsWith(".lua")
                        && replaceOlderFactoryLua(curB, intB, group, name, cur, internal)) {
                    n[1]++;
                } else {
                    if (firstDeploy && !def.exists()) {
                        n[4]++;
                        Gdx.app.log("res", "kept-unknown " + group + "/" + name
                                + " (differs from bundled default; latest default in .defaults/)");
                    } else {
                        n[3]++;
                        Gdx.app.log("res", "kept-player " + group + "/" + name
                                + " (player-modified; latest default in .defaults/)");
                    }
                }
            }
            // pass 2: force-refresh the .defaults reference copy
            try {
                if (defDir.exists()) defDir.deleteDirectory();
                defDir.mkdirs();
                for (String name : manifest) {
                    FileHandle d = defDir.child(name);
                    d.parent().mkdirs();
                    Gdx.files.internal(internalPrefix + name).copyTo(d);
                }
                defDir.child("_README.txt").writeString(
                        "此目录为游戏默认脚本的参考副本，用于自动更新判定，请勿修改。\n"
                        + "This directory holds reference copies of the bundled defaults,\n"
                        + "used by the auto-updater. DO NOT EDIT anything here.\n", false);
            } catch (Throwable t) {
                Gdx.app.error("res", "FAILED to refresh " + group + "/.defaults", t);
            }
        } catch (Throwable t) {
            Gdx.app.error("res", "default sync failed for " + group + ", continuing", t);
        }
    }

    /**
     * Version-header fallback (round 16): round-8/9 era player files predate
     * the .defaults mechanism — on first deploy they are neither byte-equal to
     * the bundle nor comparable to .defaults, and KNOWN_OLD only covers three
     * scripts, so every other old factory .lua stayed "kept-unknown" forever.
     * Every factory .lua carries a `-- vX.Y.Z...` header within its first 5
     * lines (LuaScript.versionOf). When the player copy parses to a STRICTLY
     * older version than the bundled one it is an old factory script (possibly
     * with player edits on the old base): back it up ONCE to
     * `<name>.player-bak` (an existing backup is kept — it is the first
     * generation), then overwrite with the bundled default.
     * Equal or newer player versions keep the normal kept-player path.
     * Returns true when the file was replaced.
     */
    private static boolean replaceOlderFactoryLua(byte[] curB, byte[] intB,
                                                  String group, String name,
                                                  FileHandle cur, FileHandle internal) {
        String vCur, vInt;
        try {
            vCur = LuaScript.versionOf(new String(curB, "UTF-8"));
            vInt = LuaScript.versionOf(new String(intB, "UTF-8"));
        } catch (Throwable t) {
            return false;
        }
        if ("?".equals(vCur) || "?".equals(vInt)) return false;
        if (compareVersions(vCur, vInt) >= 0) return false;
        try {
            FileHandle bak = cur.sibling(name + ".player-bak");
            if (!bak.exists()) cur.copyTo(bak); // keep the FIRST backup only
            cur.parent().mkdirs();
            internal.copyTo(cur);
            Gdx.app.log("res", "auto-updated (older factory version v" + vCur
                    + " -> v" + vInt + ", backup at " + name + ".player-bak) "
                    + group + "/" + name);
            return true;
        } catch (Throwable t) {
            Gdx.app.error("res", "FAILED to update " + group + "/" + name, t);
            return false;
        }
    }

    /**
     * Dot-separated numeric version tuple compare: each segment is parsed as
     * an int, the first differing segment decides, and when all shared
     * segments are equal the longer tuple is greater ("2026.07.21" <
     * "2026.07.22.2"; "1.2" < "1.2.1"). Unparseable segments count as 0.
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int m = Math.max(pa.length, pb.length);
        for (int i = 0; i < m; i++) {
            int x = i < pa.length ? versionSeg(pa[i]) : 0;
            int y = i < pb.length ? versionSeg(pb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static int versionSeg(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String sha1Hex(byte[] b) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder(40);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xf, 16))
                    .append(Character.forDigit(x & 0xf, 16));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Test hook (round 10): run the versioned sync against an arbitrary root. */
    public static int[] syncTreeForTest(String absoluteRoot) {
        return syncTree(Gdx.files.absolute(absoluteRoot));
    }

    /**
     * Copy bundled defaults into the player root (versioned auto-update —
     * unmodified factory files track new releases, player edits are kept).
     * Per-file logging: a single failure must be visible in logcat, and the
     * summary line tells us exactly how many files landed / were kept / failed.
     */
    private static void copyDefaults() {
        int[] n = syncTree(root);
        Gdx.app.log("res", "default resources ensured in "
                + root.file().getAbsolutePath()
                + ": copied=" + n[0] + " auto-updated=" + n[1] + " kept-current=" + n[2]
                + " kept-player=" + n[3] + " kept-unknown=" + n[4] + " failed=" + n[5]);
    }

    /**
     * Desktop/dev sanity check: the hardcoded manifest must cover every file
     * bundled in the internal assets (desktop directory listing is reliable,
     * unlike Android's). Printed by SmokeScreen; guards against adding an
     * asset/mod and forgetting to list it here.
     */
    public static String checkManifest() {
        StringBuilder missing = new StringBuilder();
        try {
            for (FileHandle f : Gdx.files.internal("").list()) {
                if (f.isDirectory()) continue;
                String n = f.name();
                if (!(n.endsWith(".png") || n.endsWith(".xml"))) continue;
                if (!contains(ASSET_FILES, n)) appendMissing(missing, "assets/" + n);
            }
            FileHandle mods = Gdx.files.internal("mods");
            if (mods.exists() && mods.isDirectory()) {
                for (FileHandle f : mods.list()) {
                    if (f.isDirectory()) continue;
                    String n = f.name();
                    if (!contains(MOD_FILES, n)) appendMissing(missing, "mod/" + n);
                }
            }
        } catch (Throwable t) {
            return "ERROR: " + t;
        }
        return missing.length() == 0
                ? "OK (" + (ASSET_FILES.length + MOD_FILES.length) + " files listed)"
                : "MISSING FROM MANIFEST: " + missing;
    }

    private static boolean contains(String[] arr, String s) {
        for (String a : arr) if (a.equals(s)) return true;
        return false;
    }

    private static void appendMissing(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(s);
    }

    public static boolean usingExternal() { return external; }

    /** A texture or XML config: player copy first, built-in fallback. */
    public static FileHandle asset(String name) {
        if (external) {
            FileHandle f = root.child("assets").child(name);
            if (f.exists()) return f;
        }
        return Gdx.files.internal(name);
    }

    /** The player mods directory (may not exist when falling back). */
    public static FileHandle modDir() {
        if (external) return root.child("mod");
        return Gdx.files.internal("mods");
    }

    /** A single mod file: player copy first, built-in fallback. */
    public static FileHandle modFile(String name) {
        if (external) {
            FileHandle f = root.child("mod").child(name);
            if (f.exists()) return f;
        }
        return Gdx.files.internal("mods/" + name);
    }

    /**
     * The player ships directory (<root>/Ships/) holding Show_Rocket-compatible
     * XML ship files. Round 28: no app-local fallback — when the shared root
     * is not writable yet the handle still points at the shared tree (writes
     * simply fail until the permission lands; callers tolerate that).
     */
    public static FileHandle shipsDir() {
        if (external) return root.child("Ships");
        return sharedRoot.child("Ships");
    }

    /**
     * The player sandbox saves directory (<root>/Sandboxs/) holding
     * Show_sandbox-compatible XML world saves. Round 28: no app-local
     * fallback (same rule as shipsDir). Created here on demand (mkdirs,
     * mirroring probeWritable's dir handling).
     */
    public static FileHandle sandboxDir() {
        if (external) {
            try {
                FileHandle d = root.child("Sandboxs");
                d.mkdirs();
                if (d.exists() && d.isDirectory()) return d;
            } catch (Throwable ignored) {}
        }
        FileHandle d = sharedRoot.child("Sandboxs");
        try { d.mkdirs(); } catch (Throwable ignored) {}
        return d;
    }

    /**
     * Legacy-save directory (<root>/save/) — the pre-XML JSON world save and
     * pre-XML JSON ship files used to live in the APP-LOCAL save/ tree
     * (Gdx.files.local); round 28 moved the one-time read fallback under the
     * shared root so nothing is ever read from app-private storage.
     */
    public static FileHandle saveDir() {
        FileHandle d = (external ? root : sharedRoot).child("save");
        try { d.mkdirs(); } catch (Throwable ignored) {}
        return d;
    }
}
