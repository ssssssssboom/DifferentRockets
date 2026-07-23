package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.differentrockets.util.Res;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Planet definitions from player-editable Lua. Looks for mod/planets.lua and
 * mod/planets/*.lua inside the resource root (player mods override); when no
 * Lua planet definitions exist, falls back to SmolarSystem.xml.
 *
 * Lua API:
 *   definePlanet{
 *     name="Smearth", parent="Sun", gravity=9.798, radius=637100,
 *     mapColor={103,157,255}, icon="Earth.png", launchEnabled=true,
 *     description="...",
 *     orbit={a=.., e=.., w=.., v=.., prograde=1},
 *     atmosphere={height=70000, surfacePressure=1.0},
 *     terrain={maxHeight=3250, minHeight=-1000, noise=2.0,
 *              texture="PlanetCrustSmearth.png", color={39,28,21},
 *              waterDensity=75,
 *              ranges={{startAngle=20,endAngle=89,minHeight=-2000,maxHeight=-1000}, ...}}
 *   }
 * radius + terrain drive BOTH the rendered crust chunks and the collision
 * heightfield, so collision and texture are definable exactly like parts.
 */
public final class PlanetDefs {

    private PlanetDefs() {}

    private static class Def {
        String name, parentName;
        Planet p = new Planet();
    }

    public static Planet load() {
        List<FileHandle> scripts = new ArrayList<>();
        FileHandle modDir = Res.modDir();
        if (modDir != null && modDir.exists()) {
            FileHandle single = modDir.child("planets.lua");
            if (single.exists()) scripts.add(single);
            FileHandle dir = modDir.child("planets");
            if (dir.exists() && dir.isDirectory()) {
                for (FileHandle f : dir.list()) {
                    if (!f.isDirectory() && f.name().endsWith(".lua")) scripts.add(f);
                }
            }
        }
        boolean fromMods = !scripts.isEmpty();
        if (!fromMods) {
            FileHandle in = Gdx.files.internal("mods/planets.lua");
            if (in.exists()) scripts.add(in);
        }
        if (!scripts.isEmpty()) {
            Planet sun = runScripts(scripts, fromMods ? "external" : "builtin");
            if (sun != null) return sun;
            Gdx.app.error("planets", "Lua planet definitions failed; falling back to SmolarSystem.xml");
        }
        Gdx.app.log("planets", "planet definitions <- SmolarSystem.xml (no Lua defs found)");
        return Planet.loadSolarSystem(Res.asset("SmolarSystem.xml"));
    }

    private static Planet runScripts(List<FileHandle> scripts, String sourceLabel) {
        final Map<String, Def> defs = new HashMap<>();
        final List<String> order = new ArrayList<>();
        try {
            Globals g = JsePlatform.standardGlobals();
            g.set("definePlanet", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue arg) {
                    if (!arg.istable()) throw new LuaError("definePlanet expects a table");
                    Def d = parse((LuaTable) arg);
                    if (defs.containsKey(d.name)) {
                        // later definitions override (planets/*.lua after planets.lua)
                        order.remove(d.name);
                    }
                    defs.put(d.name, d);
                    order.add(d.name);
                    Gdx.app.log("planets", "definePlanet " + d.name
                            + (d.parentName != null ? " (parent " + d.parentName + ")" : ""));
                    return LuaValue.NIL;
                }
            });
            for (FileHandle f : scripts) {
                String src = f.readString();
                g.load(src, f.name()).call();
                Gdx.app.log("planets", "loaded " + f.name() + " <- " + sourceLabel);
                Gdx.app.log("res", "script mod/" + f.name() + " v" + LuaScript.versionOf(src)
                        + " source=" + sourceLabel);
            }
        } catch (LuaError e) {
            Gdx.app.error("planets", "Lua error: " + e.getMessage());
            return null;
        }
        if (defs.isEmpty()) return null;
        // link parents
        Planet root = null;
        for (String name : order) {
            Def d = defs.get(name);
            if (d.parentName == null || d.parentName.isEmpty()) {
                if (root == null) root = d.p;
                continue;
            }
            Def parent = defs.get(d.parentName);
            if (parent == null) {
                Gdx.app.error("planets", "unknown parent '" + d.parentName + "' for " + d.name);
                continue;
            }
            d.p.parent = parent.p;
            parent.p.children.add(d.p);
        }
        if (root == null) {
            Gdx.app.error("planets", "no root planet (a definePlanet without parent) defined");
            return null;
        }
        return root;
    }

    private static Def parse(LuaTable t) {
        Def d = new Def();
        d.name = t.get("name").checkjstring();
        d.p.name = d.name;
        d.parentName = t.get("parent").isnil() ? null : t.get("parent").checkjstring();
        d.p.gravity = num(t, "gravity", 0);
        d.p.radius = num(t, "radius", 1000);
        d.p.mapColor = color(t.get("mapColor"), new Color(0.7f, 0.7f, 0.7f, 1f));
        d.p.icon = str(t, "icon", null);
        d.p.launchEnabled = bool(t, "launchEnabled", true);
        d.p.description = str(t, "description", "");

        LuaValue orbit = t.get("orbit");
        if (orbit.istable()) {
            d.p.a = num(orbit, "a", 0);
            d.p.e = num(orbit, "e", 0);
            d.p.w = num(orbit, "w", 0);
            d.p.v0 = num(orbit, "v", 0);
            d.p.prograde = num(orbit, "prograde", 1) != 0;
        }
        LuaValue atmo = t.get("atmosphere");
        if (atmo.istable()) {
            d.p.atmoHeight = num(atmo, "height", 0);
            d.p.surfacePressure = num(atmo, "surfacePressure", 0);
        }
        LuaValue terrain = t.get("terrain");
        if (terrain.istable()) {
            d.p.maxHeight = num(terrain, "maxHeight", 0);
            d.p.minHeight = num(terrain, "minHeight", 0);
            d.p.noise = num(terrain, "noise", 2.0);
            d.p.crustTexture = str(terrain, "texture", null);
            d.p.crustColor = color(terrain.get("color"), new Color(0.4f, 0.3f, 0.2f, 1f));
            d.p.waterDensity = num(terrain, "waterDensity", 0);
            LuaValue ranges = terrain.get("ranges");
            if (ranges.istable()) {
                for (int i = 1; i <= ranges.length(); i++) {
                    LuaValue r = ranges.get(i);
                    if (!r.istable()) continue;
                    Planet.Range rg = new Planet.Range();
                    rg.startDeg = num(r, "startAngle", 0);
                    rg.endDeg = num(r, "endAngle", 0);
                    rg.minH = num(r, "minHeight", 0);
                    rg.maxH = num(r, "maxHeight", 0);
                    d.p.ranges.add(rg);
                }
            }
        }
        return d;
    }

    private static double num(LuaValue t, String key, double def) {
        LuaValue v = t.get(key);
        return v.isnumber() ? v.todouble() : def;
    }

    private static String str(LuaValue t, String key, String def) {
        LuaValue v = t.get(key);
        return v.isstring() ? v.tojstring() : def;
    }

    private static boolean bool(LuaValue t, String key, boolean def) {
        LuaValue v = t.get(key);
        return v.isboolean() ? v.toboolean() : def;
    }

    /** Accepts {r,g,b} table (0-255) or "r,g,b" string. */
    private static Color color(LuaValue v, Color def) {
        try {
            if (v.istable()) {
                return new Color(v.get(1).toint() / 255f, v.get(2).toint() / 255f,
                        v.get(3).toint() / 255f, 1f);
            }
            if (v.isstring()) {
                String[] parts = v.tojstring().split(",");
                return new Color(Integer.parseInt(parts[0].trim()) / 255f,
                        Integer.parseInt(parts[1].trim()) / 255f,
                        Integer.parseInt(parts[2].trim()) / 255f, 1f);
            }
        } catch (Exception ignored) {}
        return def;
    }
}
