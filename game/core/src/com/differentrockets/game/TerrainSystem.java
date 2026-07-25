package com.differentrockets.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.PolygonShape;
import com.badlogic.gdx.utils.Disposable;
import com.differentrockets.util.Res;
import com.differentrockets.util.Vec2d;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Columnar planet terrain (round 18 rewrite). The surface is a ring of
 * COLUMNS, `terrainRender.blockWidthM` meters of arc each (default 4 m).
 * Column i is the QUADRILATERAL between junction heights h[i] and h[i+1]:
 *   top edge    = (x_i, h_i) -> (x_{i+1}, h_{i+1})        (the ground skin)
 *   bottom edge = same, `terrainRender.depthM` meters down (default 32)
 * Junction heights come from ONE Lua function, so columns share their
 * junctions and tile seamlessly by construction; the SAME vertex data builds
 * both the render mesh and the collision fixtures (what you see is what you
 * collide with).
 *
 * Lua interface (mod/terrain.lua, hot-reloaded):
 *   surfaceHeight(planetInfo[name], xArcMeters) -> absolute radius in meters
 *   terrainRender = { blockWidthM, depthM, rangeM, physicsRangeM, friction,
 *                     restitution, topBrightness, bottomBrightness,
 *                     bandVariation, texture, deepColor }
 *
 * Performance engineering:
 *  - window management runs at 10 Hz (REFRESH_S): it only creates/removes
 *    chunks at the edges of the +-rangeM window around the ship;
 *  - junction heights are CACHED per junction index (deterministic pure Lua
 *    function), so Lua is called once per NEW junction only;
 *  - physics fixtures exist only inside +-physicsRangeM (default 10 km —
 *    25k columns at 4 m over the full 100 km render range would mean ~25k
 *    fixtures; the render mesh covers the full range, colliders don't need
 *    to). Chunks crossing the physics boundary gain/lose their body.
 *
 * Anti-regression (carried over from the chunked system):
 *  - per-chunk KINEMATIC bodies, velocity-driven toward their planet-following
 *    target every frame: the contact solver sees the ground's true surface
 *    velocity, so a landed ship rides the moving planet via friction instead
 *    of being swallowed by a teleporting static collider;
 *  - block polygons in chunk-LOCAL coordinates (planet-centered ~637 km
 *    vertices assert natively in Box2D 2.3's float32 centroid math);
 *  - SEAM_OVERLAP_M column overlap: no tunneling gaps, no sideslip snags;
 *  - restitution 0 (no bounce), high default friction 1.0 (no sideslip);
 *  - degenerate-quad guard in double precision before Box2D sees a polygon.
 */
public class TerrainSystem implements Disposable {

    // ---- lua-tunable defaults (terrainRender{...} in mod/terrain.lua) ----
    private static final double DEF_BLOCK_W = 4.0;        // meters of arc per column
    private static final double DEF_DEPTH = 32.0;         // shell depth below the skin
    private static final double DEF_RANGE = 100000.0;     // render/load window (+/-m)
    private static final double DEF_PHYS_RANGE = 10000.0; // collider window (+/-m)
    private static final float DEF_FRICTION = 1.0f;
    private static final float DEF_RESTITUTION = 0.0f;    // no bounce
    private static final float DEF_TOP_B = 1.35f;         // skin brightness (clamped)
    private static final float DEF_BOT_B = 0.25f;         // shell-bottom brightness
    private static final float DEF_BAND = 0.06f;          // per-column brightness jitter
    private static final float DEF_DEEP_R = 0.23f, DEF_DEEP_G = 0.15f, DEF_DEEP_B = 0.09f;

    private static final double MAX_CRUST = 260.0;   // visual crust bottom cap
    private static final int COLS_PER_CHUNK = 64;    // columns per chunk (256 m at 4 m)
    private static final double SEAM_OVERLAP_M = 0.05;  // column overlap: no gaps/snags
    private static final double REFRESH_S = 0.1;     // 10 Hz window management
    private static final int HEIGHT_CACHE_CAP = 200000; // safety valve, deterministic re-fill

    private final GameWorld world;
    private Planet planet;
    private double colW;             // adjusted meters per column (tiles the planet exactly)
    private int totalCols;
    private int totalChunks;
    private final Map<Integer, Chunk> loaded = new HashMap<>();
    /** junction index (mod totalCols) -> absolute radius; pure-function cache. */
    private final Map<Integer, Double> hCache = new HashMap<>();
    private double refreshT = REFRESH_S;   // manage on the first update
    private boolean lastPhysicsActive = true; // B3: super-warp suspends terrain physics

    // live parameters from terrain.lua (hot-reloaded; changes rebuild everything)
    private final LuaScript cfgScript = new LuaScript("terrain.lua");
    private double blockW = DEF_BLOCK_W;
    private double depthM = DEF_DEPTH;
    private double rangeM = DEF_RANGE;
    private double physRangeM = DEF_PHYS_RANGE;
    private float friction = DEF_FRICTION;
    private float restitution = DEF_RESTITUTION;
    private float topB = DEF_TOP_B;
    private float botB = DEF_BOT_B;
    private float bandVar = DEF_BAND;
    private float deepR = DEF_DEEP_R, deepG = DEF_DEEP_G, deepB = DEF_DEEP_B;
    private String textureName = null;
    private Texture texture;          // null = procedural gradient
    private static Texture whiteTex;  // bound when textureless (shader always samples)
    private int degenerateSkips = 0;  // diagnostic counter for skipped degenerate quads
    private Object cfgToken, heightToken; // terrain.lua reload detection (identity tokens)

    private final ShapeRenderer shapes = new ShapeRenderer();
    private static ShaderProgram shader;
    private final Matrix4 model = new Matrix4();
    private final Matrix4 tmpMat = new Matrix4();

    private class Chunk {
        int index;
        Body body;               // per-chunk KINEMATIC body at the chunk center (physics range only)
        double cx, cy;           // chunk center in planet frame
        double lastBX, lastBY;   // last physics-frame target position
        boolean hasLast;
        Mesh mesh;
        float[] waterPoly;       // x,y pairs of water surface area (planet frame)
        double arcCenter;        // chunk center in arc meters (planet frame)

        void destroyBody() {
            if (body != null) {
                world.boxWorld.destroyBody(body); // takes the fixtures with it
                body = null;
            }
        }

        void dispose() {
            destroyBody();
            if (mesh != null) {
                mesh.dispose();
                mesh = null;
            }
        }
    }

    public TerrainSystem(GameWorld world) {
        this.world = world;
        if (shader == null) {
            String vs = "attribute vec2 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\n"
                    + "uniform mat4 u_projTrans;\nvarying vec4 v_color;\nvarying vec2 v_texCoord;\n"
                    + "void main(){ v_color = a_color; v_texCoord = a_texCoord0; "
                    + "gl_Position = u_projTrans * vec4(a_position, 0.0, 1.0); }";
            String fs = "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
                    + "varying vec4 v_color;\nvarying vec2 v_texCoord;\nuniform sampler2D u_texture;\n"
                    + "void main(){ gl_FragColor = texture2D(u_texture, v_texCoord) * v_color; }";
            shader = new ShaderProgram(vs, fs);
            if (!shader.isCompiled()) throw new RuntimeException("terrain shader: " + shader.getLog());
        }
        if (whiteTex == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            whiteTex = new Texture(pm);
            pm.dispose();
        }
    }

    public Planet currentPlanet() { return planet; }

    public int loadedCount() { return loaded.size(); }

    /**
     * Re-read terrainRender{...} from mod/terrain.lua. Any change rebuilds
     * all chunks and drops the height cache (the Lua height function itself
     * may have been edited — LuaScript hot-reloads the file independently).
     */
    private void refreshParams() {
        TerrainScript.ensureBound(world);
        // round 18 fix: terrain.lua hot-reload invalidates EVERYTHING derived
        // from it — the height cache and all chunks. Param edits were already
        // caught below, but editing surfaceHeight/specialTerrains changes no
        // terrainRender value, and the stale cached columns kept the OLD
        // collision shape (an invisible relic boundary for the player).
        Object ct = cfgScript.globals();
        Object ht = TerrainScript.loadedToken();
        if (ct != cfgToken || ht != heightToken) {
            cfgToken = ct;
            heightToken = ht;
            hCache.clear();
            clearChunks();
        }
        double bw = blockW, dm = depthM, rm = rangeM, pr = physRangeM;
        float f = friction, r = restitution, tb = topB, bb = botB, bv = bandVar;
        float dr = deepR, dg = deepG, db = deepB;
        String tex = textureName;
        Globals g = cfgScript.globals();
        if (g != null) {
            LuaValue t = g.get("terrainRender");
            if (t.istable()) {
                bw = t.get("blockWidthM").optdouble(DEF_BLOCK_W);
                dm = t.get("depthM").optdouble(DEF_DEPTH);
                rm = t.get("rangeM").optdouble(DEF_RANGE);
                pr = t.get("physicsRangeM").optdouble(DEF_PHYS_RANGE);
                f = (float) t.get("friction").optdouble(DEF_FRICTION);
                r = (float) t.get("restitution").optdouble(DEF_RESTITUTION);
                tb = (float) t.get("topBrightness").optdouble(DEF_TOP_B);
                bb = (float) t.get("bottomBrightness").optdouble(DEF_BOT_B);
                bv = (float) t.get("bandVariation").optdouble(DEF_BAND);
                LuaValue tx = t.get("texture");
                tex = tx.isstring() && !tx.tojstring().isEmpty() ? tx.tojstring() : null;
                LuaValue dc = t.get("deepColor");
                if (dc.istable()) {
                    dr = (float) dc.get(1).optdouble(DEF_DEEP_R);
                    dg = (float) dc.get(2).optdouble(DEF_DEEP_G);
                    db = (float) dc.get(3).optdouble(DEF_DEEP_B);
                }
            }
        }
        // sane clamps
        bw = Math.max(0.5, Math.min(64.0, bw));
        dm = Math.max(2.0, Math.min(500.0, dm));
        rm = Math.max(2000.0, Math.min(500000.0, rm));
        pr = Math.max(500.0, Math.min(rm, pr));
        f = Math.max(0f, Math.min(5f, f));
        r = Math.max(0f, Math.min(1f, r));
        tb = Math.max(0.05f, Math.min(4f, tb));
        bb = Math.max(0f, Math.min(1f, bb));
        bv = Math.max(0f, Math.min(0.5f, bv));
        boolean texChanged = tex == null ? textureName != null : !tex.equals(textureName);
        if (bw != blockW || dm != depthM || rm != rangeM || pr != physRangeM
                || f != friction || r != restitution || tb != topB || bb != botB
                || bv != bandVar || dr != deepR || dg != deepG || db != deepB || texChanged) {
            blockW = bw; depthM = dm; rangeM = rm; physRangeM = pr;
            friction = f; restitution = r;
            topB = tb; botB = bb; bandVar = bv;
            deepR = dr; deepG = dg; deepB = db;
            if (texChanged) {
                textureName = tex;
                if (texture != null) { texture.dispose(); texture = null; }
                if (textureName != null) {
                    try {
                        texture = new Texture(Res.asset(textureName), true);
                        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear,
                                Texture.TextureFilter.Linear);
                        texture.setWrap(Texture.TextureWrap.ClampToEdge,
                                Texture.TextureWrap.ClampToEdge);
                    } catch (Throwable t) {
                        Gdx.app.error("terrain", "texture " + textureName + " failed, using gradient", t);
                        texture = null;
                    }
                }
            }
            hCache.clear();
            if (planet != null) setupPlanet(); // colW/totalCols derive from blockW
            clearChunks();
        }
    }

    private void setupPlanet() {
        double circ = 2 * Math.PI * planet.radius;
        totalCols = Math.max(8, (int) Math.round(circ / blockW));
        colW = circ / totalCols; // adjusted so columns tile the planet seamlessly
        totalChunks = Math.max(1, (totalCols + COLS_PER_CHUNK - 1) / COLS_PER_CHUNK);
    }

    /**
     * Force the chunk window (physics colliders included) to exist around pos
     * IMMEDIATELY, bypassing the 10 Hz manage() throttle. launchShip places a
     * ship in direct contact with the ground; waiting for the next throttled
     * update() would step the first frames with no ground at all — a hidden
     * free-fall followed by an impact spike on every weld.
     */
    public void forceRefresh(Vec2d pos) {
        refreshT = REFRESH_S;
        update(pos, 0);
    }

    /** Call every frame with the active ship's universe position + this frame's simulated seconds. */
    public void update(Vec2d shipUniverse, double simDt) {
        refreshParams();
        // round 26 item B3: at super-warp (> PHYS_WARP_MAX) there is no Box2D
        // stepping for ships at all — every ship rides rails and a landed
        // ship is held by the planet-riding logic in GameWorld.superWarp /
        // Ship.integrateRails, NOT by chunk contacts. Managing kinematic
        // chunk bodies (create/drive/destroy) is pure waste then, so terrain
        // PHYSICS is suspended: chunk bodies are dropped and not recreated
        // while the RENDER mesh keeps streaming normally. Bodies come back
        // automatically via manage() within one refresh after warp ends.
        boolean physicsActive = world.warp <= GameWorld.PHYS_WARP_MAX;
        if (physicsActive && !lastPhysicsActive) {
            refreshT = REFRESH_S; // warp just ended: rebuild colliders immediately
        }
        lastPhysicsActive = physicsActive;
        // pick the planet we're closest to the surface of
        Planet best = null;
        double bestAlt = Double.MAX_VALUE;
        for (Planet p : world.planets) {
            double d = Math.sqrt(p.pos.dist2(shipUniverse)) - p.radius;
            if (d < bestAlt) { bestAlt = d; best = p; }
        }
        // only manage terrain when reasonably close to a surface
        if (best == null || bestAlt > 200000) {
            if (planet != null) { clearChunks(); planet = null; }
            return;
        }
        if (best != planet) {
            clearChunks();
            hCache.clear();
            planet = best;
            setupPlanet();
        }

        // chunk bodies follow the planet relative to the floating origin.
        // KINEMATIC and driven by VELOCITY (not teleported): the contact
        // solver sees the ground's true surface velocity, so a landed ship
        // rides the moving planet via friction (the lone-pod sinking fix).
        double px = planet.pos.x - world.origin.x;
        double py = planet.pos.y - world.origin.y;
        for (Chunk c : loaded.values()) {
            if (c.body != null) {
                if (!physicsActive) { // super-warp: no contacts needed (B3)
                    c.destroyBody();
                    continue;
                }
                double tx = px + c.cx, ty = py + c.cy;
                if (c.hasLast && simDt > 1e-9) {
                    double mvx = tx - c.lastBX, mvy = ty - c.lastBY;
                    // round 19 fix (probe-verified): Box2D clamps EVERY body,
                    // kinematic included, to b2_maxTranslation = 2 m per
                    // substep. A purely velocity-driven chunk body whose
                    // per-substep demand exceeds that (ship moving faster
                    // than ~120 m/s relative to the ground) silently LAGS —
                    // the collision shell drifted >1.3 km behind the rendered
                    // terrain at 800 m/s in the probe. Beyond the clamp
                    // threshold, snap the body to its target (teleport) while
                    // STILL reporting the true average velocity, so the
                    // contact solver keeps seeing the real ground speed.
                    double perSubstep = Math.hypot(mvx, mvy) * (GameWorld.PHYS_DT / simDt);
                    if (perSubstep > 1.8) {
                        // snap exactly onto the target and STAND STILL until
                        // the next drive: setting the true (huge) velocity
                        // here would let the clamp advance the body 2 m PAST
                        // the target every substep, oscillating around it.
                        // Friction fidelity is moot at >120 m/s ground speed.
                        c.body.setTransform((float) tx, (float) ty, 0);
                        c.body.setLinearVelocity(0, 0);
                    } else {
                        c.body.setLinearVelocity(
                                (float) (mvx / simDt),
                                (float) (mvy / simDt));
                    }
                } else {
                    c.body.setTransform((float) tx, (float) ty, 0);
                    c.body.setLinearVelocity(0, 0);
                }
                c.lastBX = tx; c.lastBY = ty; c.hasLast = true;
            }
        }

        // 10 Hz window management: only edge chunks are created/removed
        refreshT += Gdx.graphics.getDeltaTime();
        if (refreshT >= REFRESH_S) {
            refreshT = 0;
            manage(shipUniverse);
        }
    }

    /** Create/unload chunks for the current window; add/remove physics at the physics boundary. */
    private void manage(Vec2d shipUniverse) {
        double sx = shipUniverse.x - planet.pos.x;
        double sy = shipUniverse.y - planet.pos.y;
        double shipAngle = Math.atan2(sy, sx);
        if (shipAngle < 0) shipAngle += 2 * Math.PI;
        double shipArc = shipAngle * planet.radius;

        double chunkArc = COLS_PER_CHUNK * colW;
        int centerChunk = (int) Math.floor(shipArc / chunkArc);
        int span = Math.min(totalChunks, (int) Math.ceil(rangeM / chunkArc) + 1);

        Set<Integer> want = new HashSet<>();
        for (int i = -span; i <= span; i++) {
            int idx = ((centerChunk + i) % totalChunks + totalChunks) % totalChunks;
            want.add(idx);
            if (!loaded.containsKey(idx)) loadChunk(idx);
        }
        // physics membership follows the (smaller) physics window; suspended
        // entirely during super-warp (B3 — no ship contacts exist then)
        boolean physicsActive = world.warp <= GameWorld.PHYS_WARP_MAX;
        for (Chunk c : loaded.values()) {
            double d = wrappedArcDist(c.arcCenter, shipArc);
            boolean need = physicsActive && d <= physRangeM;
            if (need && c.body == null) createBody(c);
            else if (!need && c.body != null) c.destroyBody();
        }
        // unload out-of-range
        Set<Integer> toRemove = new HashSet<>();
        for (Map.Entry<Integer, Chunk> e : loaded.entrySet()) {
            if (!want.contains(e.getKey())) {
                e.getValue().dispose();
                toRemove.add(e.getKey());
            }
        }
        for (int i : toRemove) loaded.remove(i);
    }

    /** Shortest wrapped arc distance (meters) between two arc positions. */
    private double wrappedArcDist(double a, double b) {
        double circ = 2 * Math.PI * planet.radius;
        double d = Math.abs(a - b) % circ;
        return d > circ / 2 ? circ - d : d;
    }

    /** Junction height = absolute radius, cached (Lua is a deterministic pure function). */
    private double junctionHeight(int j) {
        int c = ((j % totalCols) + totalCols) % totalCols;
        Double v = hCache.get(c);
        if (v != null) return v;
        double x = c * colW;
        double r = TerrainScript.surfaceHeight(planet.name, x);
        if (Double.isNaN(r) || Double.isInfinite(r) || r < planet.radius * 0.5) {
            r = planet.radius + planet.heightAt(x / planet.radius); // built-in fallback
        }
        if (hCache.size() > HEIGHT_CACHE_CAP) hCache.clear();
        hCache.put(c, r);
        return r;
    }

    /** Deterministic per-column brightness jitter in [-1, 1] (texture-grain substitute). */
    private static float colJitter(int colIdx) {
        long h = colIdx * 2654435761L;
        h ^= (h >>> 16);
        return ((h >>> 8) % 2001) / 1000f - 1f;
    }

    private void loadChunk(int idx) {
        Chunk c = new Chunk();
        c.index = idx;
        int col0 = idx * COLS_PER_CHUNK;
        int cols = Math.min(COLS_PER_CHUNK, totalCols - col0);
        c.arcCenter = (col0 + cols / 2.0) * colW;
        double ac = c.arcCenter / planet.radius;
        c.cx = Math.cos(ac) * planet.radius;
        c.cy = Math.sin(ac) * planet.radius;

        // ---- visual mesh: shell quads (gradient) + deep quads (solid deep color) ----
        double crust = Math.max(depthM, Math.min(MAX_CRUST, Math.max(40.0, planet.radius * 0.05)));
        Color cc = planet.crustColor;
        int vertsPerCol = 8;             // 4 shell + 4 deep
        int floatsPerVert = 8;           // x,y,r,g,b,a,u,v
        float[] mv = new float[cols * vertsPerCol * floatsPerVert];
        short[] indices = new short[cols * 12];
        for (int k = 0; k < cols; k++) {
            int jL = col0 + k, jR = jL + 1;
            double aL = jL * colW / planet.radius;
            double aR = jR * colW / planet.radius;
            double hL = junctionHeight(jL), hR = junctionHeight(jR);
            // deep-fill bottom (round 19, floating-mountains fix): extend the
            // solid block down to the DATUM radius, not just h-crust. Terrain
            // more than `crust` meters above datum (mountain ridges) otherwise
            // ended 260 m under the skin, and beyond ~20 km of arc the planet
            // body disc (drawn at radius R) dips below the local horizon — the
            // chunk then floated against the starfield as a straight brown
            // bar. Filling to R welds every chunk onto the body disc.
            double botL = Math.min(hL - crust, planet.radius);
            double botR = Math.min(hR - crust, planet.radius);
            float cosL = (float) Math.cos(aL), sinL = (float) Math.sin(aL);
            float cosR = (float) Math.cos(aR), sinR = (float) Math.sin(aR);
            float topScale = 1f + colJitter(jL) * bandVar;
            float tr = Math.min(1f, cc.r * topB * topScale);
            float tg = Math.min(1f, cc.g * topB * topScale);
            float tb = Math.min(1f, cc.b * topB * topScale);
            float br = cc.r * botB, bg = cc.g * botB, bb = cc.b * botB;
            int v = k * vertsPerCol * floatsPerVert;
            // shell quad: TL TR BR BL (uv stretches the optional texture across each quad)
            v = putVert(mv, v, cosL * (float) hL, sinL * (float) hL, tr, tg, tb, 0f, 0f);
            v = putVert(mv, v, cosR * (float) hR, sinR * (float) hR, tr, tg, tb, 1f, 0f);
            v = putVert(mv, v, cosR * (float) (hR - depthM), sinR * (float) (hR - depthM), br, bg, bb, 1f, 1f);
            v = putVert(mv, v, cosL * (float) (hL - depthM), sinL * (float) (hL - depthM), br, bg, bb, 0f, 1f);
            // deep quad: solid deep color down to the datum-welded bottom
            v = putVert(mv, v, cosL * (float) (hL - depthM), sinL * (float) (hL - depthM), deepR, deepG, deepB, 0f, 0f);
            v = putVert(mv, v, cosR * (float) (hR - depthM), sinR * (float) (hR - depthM), deepR, deepG, deepB, 0f, 0f);
            v = putVert(mv, v, cosR * (float) botR, sinR * (float) botR, deepR, deepG, deepB, 0f, 0f);
            v = putVert(mv, v, cosL * (float) botL, sinL * (float) botL, deepR, deepG, deepB, 0f, 0f);
            int s = k * 8; // first vertex of this column
            int t = k * 12;
            indices[t] = (short) s; indices[t + 1] = (short) (s + 1); indices[t + 2] = (short) (s + 2);
            indices[t + 3] = (short) s; indices[t + 4] = (short) (s + 2); indices[t + 5] = (short) (s + 3);
            indices[t + 6] = (short) (s + 4); indices[t + 7] = (short) (s + 5); indices[t + 8] = (short) (s + 6);
            indices[t + 9] = (short) (s + 4); indices[t + 10] = (short) (s + 6); indices[t + 11] = (short) (s + 7);
        }
        c.mesh = new Mesh(true, cols * vertsPerCol, indices.length,
                new VertexAttributes(
                        new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
                        new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_color"),
                        VertexAttribute.TexCoords(0)));
        c.mesh.setVertices(mv);
        c.mesh.setIndices(indices);

        // water polygon (columns below sea level filled up to r = R)
        if (planet.waterDensity > 0) {
            boolean anyBelow = false;
            for (int k = 0; k <= cols; k++) {
                if (junctionHeight(col0 + k) < planet.radius) { anyBelow = true; break; }
            }
            java.util.List<Float> poly = anyBelow ? new java.util.ArrayList<>() : null;
            if (poly != null) {
                for (int k = 0; k <= cols; k++) { // top edge: sea-level arc
                    double a = (col0 + k) * colW / planet.radius;
                    double r = Math.max(planet.radius, junctionHeight(col0 + k));
                    poly.add((float) (Math.cos(a) * r));
                    poly.add((float) (Math.sin(a) * r));
                }
                for (int k = cols; k >= 0; k--) { // bottom edge: submerged terrain
                    double h = junctionHeight(col0 + k);
                    if (h < planet.radius) {
                        double a = (col0 + k) * colW / planet.radius;
                        poly.add((float) (Math.cos(a) * h));
                        poly.add((float) (Math.sin(a) * h));
                    }
                }
                if (poly.size() >= 6) {
                    float[] arr = new float[poly.size()];
                    for (int i = 0; i < arr.length; i++) arr[i] = poly.get(i);
                    c.waterPoly = arr;
                }
            }
        }

        loaded.put(idx, c);
    }

    private static int putVert(float[] mv, int v, float x, float y,
                               float r, float g, float b, float u, float vv) {
        mv[v] = x; mv[v + 1] = y;
        mv[v + 2] = r; mv[v + 3] = g; mv[v + 4] = b; mv[v + 5] = 1f;
        mv[v + 6] = u; mv[v + 7] = vv;
        return v + 8;
    }

    /**
     * Collision body for a chunk: one quad fixture per column, built from the
     * SAME junction heights as the render mesh (see-loadChunk). Chunk-local
     * coordinates (planet-centered floats assert natively in Box2D 2.3),
     * SEAM_OVERLAP_M overlap on both sides of each column, restitution 0,
     * lua friction, degenerate-area guard in double precision.
     */
    private void createBody(Chunk c) {
        int col0 = c.index * COLS_PER_CHUNK;
        int cols = Math.min(COLS_PER_CHUNK, totalCols - col0);
        double px = planet.pos.x - world.origin.x;
        double py = planet.pos.y - world.origin.y;
        BodyDef bd = new BodyDef();
        bd.type = BodyDef.BodyType.KinematicBody; // velocity-driven ground
        bd.position.set((float) (px + c.cx), (float) (py + c.cy));
        c.body = world.boxWorld.createBody(bd);
        c.lastBX = px + c.cx; c.lastBY = py + c.cy; c.hasLast = true;

        double ovAng = SEAM_OVERLAP_M / planet.radius;
        for (int k = 0; k < cols; k++) {
            int jL = col0 + k, jR = jL + 1;
            double aL = jL * colW / planet.radius - ovAng;
            double aR = jR * colW / planet.radius + ovAng;
            double rTL = junctionHeight(jL), rTR = junctionHeight(jR);
            double rBL = rTL - depthM, rBR = rTR - depthM;
            float[] v = new float[8];
            v[0] = (float) (Math.cos(aL) * rTL - c.cx);
            v[1] = (float) (Math.sin(aL) * rTL - c.cy);
            v[2] = (float) (Math.cos(aR) * rTR - c.cx);
            v[3] = (float) (Math.sin(aR) * rTR - c.cy);
            v[4] = (float) (Math.cos(aR) * rBR - c.cx);
            v[5] = (float) (Math.sin(aR) * rBR - c.cy);
            v[6] = (float) (Math.cos(aL) * rBL - c.cx);
            v[7] = (float) (Math.sin(aL) * rBL - c.cy);
            // guard: never hand Box2D a degenerate/NaN polygon (native assert)
            double axx = v[0], ayy = v[1];
            double area2 = (v[2] - axx) * (v[5] - ayy) - (v[4] - axx) * (v[3] - ayy)
                         + (v[4] - axx) * (v[7] - ayy) - (v[6] - axx) * (v[5] - ayy);
            if (!(Math.abs(area2) >= 0.01)) { // also catches NaN
                if (degenerateSkips < 5) {
                    degenerateSkips++;
                    Gdx.app.error("terrain", "skip degenerate quad chunk=" + c.index + " col=" + jL
                            + " rTL=" + rTL + " rTR=" + rTR + " area2=" + area2);
                }
                continue;
            }
            PolygonShape ps = new PolygonShape();
            ps.set(v);
            FixtureDef fd = new FixtureDef();
            fd.shape = ps;
            // own collision category (round 27 wheels): tires mask terrain in,
            // parts out — without this split, tires would hit parts too
            fd.filter.categoryBits = PartType.CAT_TERRAIN;
            fd.filter.maskBits = -1;
            fd.friction = friction;
            fd.restitution = restitution;
            c.body.createFixture(fd);
            ps.dispose();
        }
    }

    private void clearChunks() {
        for (Chunk c : loaded.values()) c.dispose();
        loaded.clear();
    }

    public void render(OrthographicCamera cam) {
        if (planet == null) return;
        double px = planet.pos.x - world.origin.x;
        double py = planet.pos.y - world.origin.y;
        model.setToTranslation((float) px, (float) py, 0);

        // vertex-colored quads; the texture uniform multiplies the gradient
        // (white 1x1 bound on the procedural path, so one shader serves both)
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shader.bind();
        tmpMat.set(cam.combined).mul(model);
        shader.setUniformMatrix("u_projTrans", tmpMat);
        if (shader.hasUniform("u_texture")) shader.setUniformi("u_texture", 0);
        (texture != null ? texture : whiteTex).bind(0);
        for (Chunk c : loaded.values()) {
            c.mesh.render(shader, GL20.GL_TRIANGLES);
        }

        // water
        boolean anyWater = false;
        for (Chunk c : loaded.values()) if (c.waterPoly != null) { anyWater = true; break; }
        if (anyWater) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.setProjectionMatrix(cam.combined);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.15f, 0.35f, 0.75f, 0.75f);
            for (Chunk c : loaded.values()) {
                if (c.waterPoly == null) continue;
                float[] p = c.waterPoly;
                for (int i = 2; i < p.length / 2; i++) { // fan-triangulate from vertex 0
                    shapes.triangle(
                            p[0] + (float) px, p[1] + (float) py,
                            p[(i - 1) * 2] + (float) px, p[(i - 1) * 2 + 1] + (float) py,
                            p[i * 2] + (float) px, p[i * 2 + 1] + (float) py);
                }
            }
            shapes.end();
        }
    }

    public void renderDebug(OrthographicCamera cam, com.badlogic.gdx.physics.box2d.Box2DDebugRenderer dbg) {}

    @Override
    public void dispose() {
        clearChunks();
        if (texture != null) { texture.dispose(); texture = null; }
        shapes.dispose();
    }
}
