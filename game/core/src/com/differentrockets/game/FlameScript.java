package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Bridges engine flame rendering to mod/flame.lua. Java batches the
 * primitives; the script decides what to draw. Lua API:
 *   draw.triangle(x1,y1, x2,y2, x3,y3, r,g,b,a)   -- world-space, batched
 *   draw.sprite(tex,x,y,w,h,angleDeg,alpha [,r,g,b]) -- textured quad, additive
 *   flame.emit{tex=..., x=..., y=..., vx=..., vy=..., life=..., size0=...,
 *              size1=..., r=..., g=..., b=..., a0=..., a1=..., drag=...}
 *                                             -- pooled particle (FlameFx)
 *   drawFlame(ctx)                                 -- called per running engine per frame
 * ctx fields: x, y (nozzle world pos), dirX, dirY (unit plume direction),
 * angle (nozzle angle rad), nozzleW (m), throttle (0..1+ flame level),
 * engineSize, engineHeight (m), time (sec), dt (simulated seconds this frame,
 * warp included), partId (stable per-part key for per-engine script state),
 * fuelType (2 = ion), ion (bool),
 * pressure (ambient pressure, 1.0 = sea level, 0 = vacuum), density (kg/m3).
 * If flame.lua is missing or errors, the built-in default plume is drawn
 * instead (logged once). Hot-reloads when the file changes.
 */
public final class FlameScript {

    private static final LuaScript script = new LuaScript("flame.lua");

    private static float[] buf = new float[4096];   // 10 floats per triangle
    private static int tris;
    private static float[] sbuf = new float[1024];  // 11 floats per sprite
    private static int sprites;
    private static boolean callFailed;

    private static final LuaTable ctx = new LuaTable();

    private FlameScript() {}

    public static void invalidate() { script.invalidate(); }

    public static boolean available() {
        Globals g = script.globals();
        return g != null && g.get("drawFlame").isfunction();
    }

    /**
     * Start a batched flame pass; returns false when the caller should use the
     * built-in plume. dtSim = simulated seconds covered by this frame (warp
     * included) so emission rates stay stable at 4x warp.
     */
    public static boolean begin(float dtSim) {
        Globals g = script.globals();
        if (g == null || !g.get("drawFlame").isfunction()) return false;
        if (g != lastSeen) { // script (re)loaded: fresh state, clear past errors
            lastSeen = g;
            callFailed = false;
        }
        if (callFailed) return false;
        // (re)install the draw/flame API for this globals instance
        LuaValue draw = g.get("draw");
        if (!draw.istable()) {
            LuaTable t = new LuaTable();
            t.set("triangle", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    int n = Math.min(10, a.narg());
                    ensure(tris + 1);
                    int o = tris * 10;
                    for (int i = 0; i < n; i++) buf[o + i] = (float) a.arg(i + 1).todouble();
                    tris++;
                    return LuaValue.NIL;
                }
            });
            // draw.sprite(texName, x, y, w, h, angleDeg, alpha [, r, g, b])
            t.set("sprite", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    int n = a.narg();
                    if (n < 7) return LuaValue.NIL;
                    ensureSprite(sprites + 1);
                    int o = sprites * 11;
                    sbuf[o] = FlameFx.texIdForName(a.arg(1).optjstring("glow"));
                    sbuf[o + 1] = (float) a.arg(2).todouble();  // x
                    sbuf[o + 2] = (float) a.arg(3).todouble();  // y
                    sbuf[o + 3] = (float) a.arg(4).todouble();  // w
                    sbuf[o + 4] = (float) a.arg(5).todouble();  // h
                    sbuf[o + 5] = (float) a.arg(6).todouble();  // angleDeg
                    sbuf[o + 6] = (float) a.arg(7).todouble();  // alpha
                    sbuf[o + 7] = (float) a.arg(8).optdouble(1.0);  // r
                    sbuf[o + 8] = (float) a.arg(9).optdouble(1.0);  // g
                    sbuf[o + 9] = (float) a.arg(10).optdouble(1.0); // b
                    sprites++;
                    return LuaValue.NIL;
                }
            });
            g.set("draw", t);
        }
        LuaValue flame = g.get("flame");
        if (!flame.istable()) {
            LuaTable t = new LuaTable();
            t.set("emit", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    LuaTable p = a.arg1().opttable(null);
                    if (p == null) return LuaValue.NIL;
                    FlameFx.emit(
                            FlameFx.texIdForName(p.get("tex").optjstring("glow")),
                            (float) p.get("x").optdouble(0), (float) p.get("y").optdouble(0),
                            (float) p.get("vx").optdouble(0), (float) p.get("vy").optdouble(0),
                            (float) p.get("drag").optdouble(0),
                            (float) p.get("life").optdouble(0.5),
                            (float) p.get("size0").optdouble(0.5), (float) p.get("size1").optdouble(0.5),
                            (float) p.get("r").optdouble(1), (float) p.get("g").optdouble(1),
                            (float) p.get("b").optdouble(1),
                            (float) p.get("a0").optdouble(0.5), (float) p.get("a1").optdouble(0));
                    return LuaValue.NIL;
                }
            });
            t.set("count", new VarArgFunction() {
                @Override public Varargs invoke(Varargs a) {
                    return LuaValue.valueOf(FlameFx.activeCount());
                }
            });
            g.set("flame", t);
        }
        ctx.set("dt", dtSim);
        tris = 0;
        sprites = 0;
        return true;
    }

    private static Globals lastSeen;

    /** Let the script draw one engine's plume. */
    public static void drawPart(float x, float y, float dirX, float dirY, float angle,
                                float nozzleW, float throttle, float engineSize, float engineHeight,
                                double time, int fuelType, double pressure, double density,
                                int partId) {
        Globals g = script.globals();
        if (g == null || callFailed) return;
        ctx.set("x", x); ctx.set("y", y);
        ctx.set("partId", partId);
        ctx.set("dirX", dirX); ctx.set("dirY", dirY);
        ctx.set("angle", angle);
        ctx.set("nozzleW", nozzleW);
        ctx.set("throttle", throttle);
        ctx.set("engineSize", engineSize);
        ctx.set("engineHeight", engineHeight);
        ctx.set("time", time);
        ctx.set("fuelType", fuelType);
        ctx.set("ion", LuaValue.valueOf(fuelType == 2));
        // ambient atmosphere at the nozzle (item: realistic plume) — 1.0 = sea
        // level pressure on the current planet, 0 = vacuum; scripts use it for
        // Mach diamonds / plume expansion. Old scripts simply ignore these.
        ctx.set("pressure", pressure);
        ctx.set("density", density);
        try {
            g.get("drawFlame").call(ctx);
        } catch (LuaError e) {
            if (!callFailed) {
                callFailed = true;
                Gdx.app.error("flame.lua", "drawFlame error (built-in plume takes over): "
                        + e.getMessage());
            }
        }
    }

    /** Flush the batched triangles through the ShapeRenderer (already begun). */
    public static void flush(ShapeRenderer shapes) {
        for (int i = 0; i < tris; i++) {
            int o = i * 10;
            shapes.setColor(buf[o + 6], buf[o + 7], buf[o + 8], buf[o + 9]);
            shapes.triangle(buf[o], buf[o + 1], buf[o + 2], buf[o + 3], buf[o + 4], buf[o + 5]);
        }
        tris = 0;
    }

    /**
     * Render the batched script sprites additively. Manages its own
     * batch begin/end and restores the previous blend function.
     */
    public static void flushSprites(SpriteBatch batch) {
        if (sprites == 0) return;
        FlameFx.ensureTextures();
        int oldSrc = batch.getBlendSrcFunc();
        int oldDst = batch.getBlendDstFunc();
        batch.begin();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        for (int i = 0; i < sprites; i++) {
            int o = i * 11;
            float alpha = sbuf[o + 6];
            if (alpha <= 0.003f) continue;
            float w = sbuf[o + 3], h = sbuf[o + 4];
            batch.setColor(sbuf[o + 7], sbuf[o + 8], sbuf[o + 9], Math.min(1f, alpha));
            batch.draw(FlameFx.tex((int) sbuf[o]),
                    sbuf[o + 1] - w * 0.5f, sbuf[o + 2] - h * 0.5f,
                    w * 0.5f, h * 0.5f, w, h, 1f, 1f, sbuf[o + 5]);
        }
        batch.setColor(1f, 1f, 1f, 1f);
        batch.setBlendFunction(oldSrc, oldDst);
        batch.end();
        sprites = 0;
    }

    private static void ensure(int n) {
        if (n * 10 <= buf.length) return;
        float[] nb = new float[Math.max(buf.length * 2, n * 10)];
        System.arraycopy(buf, 0, nb, 0, buf.length);
        buf = nb;
    }

    private static void ensureSprite(int n) {
        if (n * 11 <= sbuf.length) return;
        float[] nb = new float[Math.max(sbuf.length * 2, n * 11)];
        System.arraycopy(sbuf, 0, nb, 0, sbuf.length);
        sbuf = nb;
    }
}
