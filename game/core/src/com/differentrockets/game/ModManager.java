package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads per-part-type Lua scripts from a writable mods directory, copying the
 * bundled defaults there on first run. Every part instance gets its own Lua
 * globals/state. Hooks: onLoad(part), onUpdate(part, dt), onStage(part).
 */
public final class ModManager {

    private static FileHandle modDir;
    private static final Map<String, String> scriptSourceCache = new HashMap<>();
    private static boolean initialized = false;

    private ModManager() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        // player mods live in <resource root>/mod/ (defaults copied there on
        // first run by Res); built-in assets are the per-script fallback
        modDir = com.differentrockets.util.Res.modDir();
        Gdx.app.log("mods", "mod dir: " + modDir.path() + " (exists=" + modDir.exists() + ")");
    }

    /** Re-resolve the mod dir and drop cached sources (after a resource reload). */
    public static void reset() {
        initialized = false;
        scriptSourceCache.clear();
        init();
    }

    private static String scriptFor(String typeId) {
        if (scriptSourceCache.containsKey(typeId)) return scriptSourceCache.get(typeId);
        String src = null;
        String source = null;
        FileHandle wf = modDir.child(typeId + ".lua");
        if (wf.exists()) {
            try {
                src = wf.readString();
                source = "external";
                Gdx.app.log("mods", "script " + typeId + ".lua <- player mods (" + wf.path() + ")");
            } catch (Exception ignored) {}
        }
        if (src == null) {
            FileHandle in = Gdx.files.internal("mods/" + typeId + ".lua");
            if (in.exists()) {
                try {
                    src = in.readString();
                    source = "builtin";
                    Gdx.app.log("mods", "script " + typeId + ".lua <- built-in assets");
                } catch (Exception ignored) {}
            }
        }
        if (src != null) {
            Gdx.app.log("res", "script mod/" + typeId + ".lua v" + LuaScript.versionOf(src)
                    + " source=" + source);
        }
        scriptSourceCache.put(typeId, src);
        return src;
    }

    /**
     * Shared PID controller library injected into every part script's globals
     * (round 9). Usage in a part script:
     *   local ctl = pid.new(kp, ki, kd)          -- typically in onLoad
     *   local rate = ctl:update(target, current, dt)  -- control output
     * Gains come from physics.lua via part:physicsNumber("gimbal", "kp") etc.,
     * so players tune behavior without touching engine scripts; replacing the
     * `pid` table itself is also possible (it is plain Lua, injected once per
     * part state).
     */
    private static final String PID_LIB =
            "pid = { new = function(kp, ki, kd)\n"
            + "  return { kp = kp, ki = ki, kd = kd, int = 0, prevMeas = nil,\n"
            + "    update = function(self, target, current, dt)\n"
            + "      local err = target - current\n"
            + "      self.int = self.int + err * dt\n"
            + "      if self.int > 30 then self.int = 30 elseif self.int < -30 then self.int = -30 end\n"
            + "      local der = 0\n"
            + "      -- derivative on MEASUREMENT (not error): a step in the target\n"
            + "      -- must not kick the actuator — standard anti-kick practice\n"
            + "      if self.prevMeas ~= nil and dt > 1e-9 then der = -(current - self.prevMeas) / dt end\n"
            + "      self.prevMeas = current\n"
            + "      return self.kp * err + self.ki * self.int + self.kd * der\n"
            + "    end }\n"
            + "  end }\n";

    /** Create a fresh Lua state for one part instance; returns null if no script. */
    public static Globals createState(String typeId) {
        String src = scriptFor(typeId);
        if (src == null) return null;
        try {
            Globals g = JsePlatform.standardGlobals();
            g.load(PID_LIB, "pid.lua").call();
            LuaValue chunk = g.load(src, typeId + ".lua");
            chunk.call();
            return g;
        } catch (LuaError e) {
            Gdx.app.error("mods", "Lua load error in " + typeId + ": " + e.getMessage());
            return null;
        }
    }

    public static void callHook(Globals g, String name, ModApi api, double dt) {
        if (g == null) return;
        LuaValue fn = g.get(name);
        if (fn == null || !fn.isfunction()) return;
        try {
            if ("onUpdate".equals(name)) {
                fn.invoke(new LuaValue[]{CoerceJavaToLua.coerce(api), LuaValue.valueOf(dt)});
            } else {
                fn.invoke(new LuaValue[]{CoerceJavaToLua.coerce(api)});
            }
        } catch (LuaError e) {
            Gdx.app.error("mods", "Lua error in " + name + ": " + e.getMessage());
        }
    }
}
