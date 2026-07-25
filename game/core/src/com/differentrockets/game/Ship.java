package com.differentrockets.game;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.RayCastCallback;
import com.badlogic.gdx.physics.box2d.joints.WeldJointDef;
import com.differentrockets.util.Json;
import com.differentrockets.util.Vec2d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A rocket: a set of independent part bodies connected by spring-damper weld
 * joints. Owns its fuel network, stage list, and universe position. Inactive
 * ships far from the active one are put on rails (bodies deactivated, simple
 * gravity integration) but remain in the world and are switchable.
 */
public class Ship {

    /** Last resolved weld params (smoke-test diagnostics for per-part overrides). */
    public static float debugLastWeldHz = -1, debugLastWeldDamp = -1;

    public static class Link {
        public Joint joint;
        public Part a, b;
        public boolean fuelEdge;
        public float breakForce = Float.MAX_VALUE;
        /** index of each side's attach point in its part's attachDefs (-1 = unknown). */
        public int attachIndexA = -1, attachIndexB = -1;
    }

    public final GameWorld world;
    public final List<Part> parts = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();
    /**
     * Legacy design stage list (design-part indices). Still loaded/saved for
     * backward compatibility, but STAGE activation is group-number based now
     * (see activateStage); this no longer drives anything.
     */
    public final List<List<Integer>> stages = new ArrayList<>();
    /** Last stage NUMBER fired (group-based staging, round 26); 0 = none. */
    public int currentStage = 0;

    /** universe position of this ship's local frame origin */
    public final Vec2d origin = new Vec2d();
    /** universe velocity of the local frame (zero for the active ship) */
    public final Vec2d originVel = new Vec2d();
    public boolean onRails = false;
    public boolean landed = false;
    public String name = "Ship";

    private final Vec2d tmp = new Vec2d();
    private int id;
    private static int nextId = 1;

    public Ship(GameWorld world) {
        this.world = world;
        this.id = nextId++;
        this.name = "Ship-" + id;
    }

    // ---------------------------------------------------------------- build

    /** Instantiate parts (bodies created at design positions, rotated by spawnAngle). */
    public void buildFromDesign(ShipDesign d, float spawnAngle) {
        // design index -> runtime Part (null for unknown types, which are skipped)
        List<Part> byDesign = new ArrayList<>();
        for (ShipDesign.DesignPart dp : d.parts) {
            PartType t = PartList.get(dp.typeId);
            if (t == null) { byDesign.add(null); continue; }
            Part p = new Part(t, this, dp);
            p.group = dp.group;
            // rotate design offset by spawn angle
            float c = (float) Math.cos(spawnAngle), s = (float) Math.sin(spawnAngle);
            float ox = dp.x * c - dp.y * s;
            float oy = dp.x * s + dp.y * c;
            p.createBody(ox, oy, spawnAngle);
            parts.add(p);
            byDesign.add(p);
        }
        for (List<Integer> st : d.stages) stages.add(new ArrayList<>(st));
        // onLoad BEFORE welding (round 9 item 1): part scripts set per-part
        // joint overrides (setJointParams) in onLoad — welds must resolve
        // against those, not against the global defaults.
        for (Part p : parts) p.callOnLoad();
        // round 27: connection-record-driven welding (Show_Rocket Connections).
        // The editor records exactly which attach points mate; weld those.
        // Designs without records (old saves, hand-built) fall back to the
        // geometric overlap sweep.
        if (!d.connections.isEmpty()) {
            for (ShipDesign.Connection c : d.connections) {
                if (c.partA < 0 || c.partA >= byDesign.size()
                        || c.partB < 0 || c.partB >= byDesign.size()) continue;
                Part a = byDesign.get(c.partA), b = byDesign.get(c.partB);
                if (a == null || b == null || a == b) continue;
                weldAt(a, c.attachA, b, c.attachB);
            }
            // a design whose records ALL failed (e.g. every part type missing)
            // still needs structure — fall back if nothing got welded
            if (links.isEmpty() && parts.size() > 1) connectAttachPoints();
        } else {
            connectAttachPoints();
        }
    }

    /**
     * Weld two parts at SPECIFIC attach indices: the anchor is the midpoint of
     * the closest pair between the two attach segments (same contact rule as
     * the geometric sweep), and the weld params resolve against those exact
     * attach defs. Out-of-range indices fall back to nearest-attach welding.
     */
    public void weldAt(Part a, int ai, Part b, int bi) {
        if (ai < 0 || bi < 0 || ai >= a.attachDefs().size() || bi >= b.attachDefs().size()) {
            weldLoaded(a, b);
            return;
        }
        Vector2 a1 = new Vector2(), a2 = new Vector2();
        Vector2 b1 = new Vector2(), b2 = new Vector2();
        Vector2 cA = new Vector2(), cB = new Vector2();
        a.attachWorldSegment(ai, a1, a2);
        b.attachWorldSegment(bi, b1, b2);
        Attach.closestBetweenSegments(a1, a2, b1, b2, cA, cB);
        Vector2 anchor = new Vector2((cA.x + cB.x) / 2f, (cA.y + cB.y) / 2f);
        weld(a, b, anchor, a.attachDefs().get(ai), b.attachDefs().get(bi));
    }

    /**
     * Public wrapper for connectAttachPoints: re-weld by attach-point overlap
     * (sandbox XML loader fallback for saves without a Connections list).
     */
    public void connectOverlaps() { connectAttachPoints(); }

    /** Connect overlapping attach points with spring-damper weld joints. */
    private void connectAttachPoints() {        float threshold = 0.35f;
        Vector2 a1 = new Vector2(), a2 = new Vector2();
        Vector2 b1 = new Vector2(), b2 = new Vector2();
        Vector2 cA = new Vector2(), cB = new Vector2();
        for (int i = 0; i < parts.size(); i++) {
            Part pa = parts.get(i);
            int na = pa.attachDefs().size();
            for (int j = i + 1; j < parts.size(); j++) {
                Part pb = parts.get(j);
                int nb = pb.attachDefs().size();
                float best = Float.MAX_VALUE;
                int bestA = -1, bestB = -1;
                float bestAX = 0, bestAY = 0, bestBX = 0, bestBY = 0;
                for (int ai = 0; ai < na; ai++) {
                    pa.attachWorldSegment(ai, a1, a2);
                    for (int bi = 0; bi < nb; bi++) {
                        // edge-aware contact (round 11 item 5): an edge-type
                        // attach point matches anywhere along its segment, so
                        // rotated parts weld even when only edge CENTERS would
                        // have been near under the old point-to-point rule.
                        pb.attachWorldSegment(bi, b1, b2);
                        float dst = Attach.closestBetweenSegments(a1, a2, b1, b2, cA, cB);
                        if (dst < best) {
                            best = dst; bestA = ai; bestB = bi;
                            bestAX = cA.x; bestAY = cA.y; bestBX = cB.x; bestBY = cB.y;
                        }
                    }
                }
                if (bestA >= 0 && best < threshold) {
                    // weld anchor at the contact midpoint between the segments
                    Vector2 anchor = new Vector2((bestAX + bestBX) / 2f, (bestAY + bestBY) / 2f);
                    weld(pa, pb, anchor, pa.attachDefs().get(bestA), pb.attachDefs().get(bestB));
                }
            }
        }
    }

    /** "lua" when joints.lua resolved the last weld, "fallback" for the built-in rule. */
    public static String debugLastWeldSource = "";

    private void weld(Part a, Part b, Vector2 worldAnchor,
                      PartType.AttachPoint apA, PartType.AttachPoint apB) {
        if (Boolean.getBoolean("dr.nojoints")) return; // debug toggle
        WeldJointDef jd = new WeldJointDef();
        jd.initialize(a.body, b.body, worldAnchor);
        // spring-damper joints transmitting forces (owner requirement);
        // parameters editable in mod/joints.lua (round 11 item 6). When the
        // script is missing/broken, fall back to the built-in rule: per-part
        // overrides (Lua part:setJointParams, round 9) where the override
        // with the HIGHER frequencyHz wins — the stiffer side rules the
        // connection — and its dampingRatio comes along.
        JointScript.Params jp = new JointScript.Params();
        float breakForce;
        if (JointScript.resolve(a, apA, b, apB, jp)) {
            jd.frequencyHz = jp.frequencyHz;
            jd.dampingRatio = jp.dampingRatio;
            breakForce = jp.breakForce > 0 ? jp.breakForce : Math.min(apA.breakForce, apB.breakForce);
            debugLastWeldSource = "lua";
        } else {
            Part stiff = null;
            if (!Double.isNaN(a.jointFreqHz)
                    && (Double.isNaN(b.jointFreqHz) || a.jointFreqHz >= b.jointFreqHz)) {
                stiff = a;
            } else if (!Double.isNaN(b.jointFreqHz)) {
                stiff = b;
            }
            jd.frequencyHz = (float) (stiff != null
                    ? stiff.jointFreqHz : PhysicsScript.jointParam("frequencyHz"));
            jd.dampingRatio = (float) (stiff != null && !Double.isNaN(stiff.jointDampRatio)
                    ? stiff.jointDampRatio : PhysicsScript.jointParam("dampingRatio"));
            breakForce = Math.min(apA.breakForce, apB.breakForce);
            debugLastWeldSource = "fallback";
        }
        debugLastWeldHz = jd.frequencyHz;
        debugLastWeldDamp = jd.dampingRatio;
        jd.collideConnected = false;
        Joint joint = world.boxWorld.createJoint(jd);
        Link l = new Link();
        l.joint = joint;
        l.a = a;
        l.b = b;
        l.fuelEdge = apA.fuelLine && apB.fuelLine;
        l.breakForce = breakForce;
        l.attachIndexA = a.attachDefs().indexOf(apA);
        l.attachIndexB = b.attachDefs().indexOf(apB);
        links.add(l);
    }

    public void removeJointsOf(Part p) {
        List<Link> dead = new ArrayList<>();
        for (Link l : links) {
            if (l.a == p || l.b == p) dead.add(l);
        }
        for (Link l : dead) destroyLink(l);
        splitIfDisconnected();
    }

    /**
     * Detach MODE 2 (detacher-*.lua, round 26): sever ONLY the joint sitting
     * on this part's FIRST attach point (index 0 — the parent/upstream side:
     * TopCenter on detacher-1, LeftCenter on detacher-2). Joints on every
     * other attach point survive, so the detacher ring stays with the lower
     * stage instead of falling free. Links without a recorded attach index
     * (-1, e.g. rebuilt from an old save) are treated as parent joints and
     * severed too — a detacher that keeps a mystery link would never staged.
     */
    public void removeParentJointOf(Part p) {
        List<Link> dead = new ArrayList<>();
        for (Link l : links) {
            if (l.a == p && l.attachIndexA <= 0) dead.add(l);
            else if (l.b == p && l.attachIndexB <= 0) dead.add(l);
        }
        for (Link l : dead) destroyLink(l);
        if (!dead.isEmpty()) splitIfDisconnected();
    }

    private void destroyLink(Link l) {
        if (l.joint != null) {
            world.boxWorld.destroyJoint(l.joint);
            l.joint = null;
        }
        links.remove(l);
    }

    /** Destroy joints whose reaction force exceeds their breakForce. */
    public void checkJointBreaks(float invDt) {
        List<Link> dead = new ArrayList<>();
        for (Link l : links) {
            if (l.breakForce == Float.MAX_VALUE) continue;
            Vector2 f = l.joint.getReactionForce(invDt);
            float kn = f.len() / 1000f; // reaction force in kN
            if (kn > l.breakForce) dead.add(l);
        }
        for (Link l : dead) destroyLink(l);
        if (!dead.isEmpty()) splitIfDisconnected();
    }

    /** Split into multiple ships if the joint graph is disconnected. */
    public void splitIfDisconnected() {
        if (parts.size() < 2) return;
        Map<Part, Integer> comp = new IdentityHashMap<>();
        int ncomp = 0;
        for (Part p : parts) {
            if (comp.containsKey(p)) continue;
            // BFS
            List<Part> stack = new ArrayList<>();
            stack.add(p);
            comp.put(p, ncomp);
            while (!stack.isEmpty()) {
                Part cur = stack.remove(stack.size() - 1);
                for (Link l : links) {
                    Part other = l.a == cur ? l.b : (l.b == cur ? l.a : null);
                    if (other != null && !comp.containsKey(other)) {
                        comp.put(other, ncomp);
                        stack.add(other);
                    }
                }
            }
            ncomp++;
        }
        if (ncomp < 2) return;
        // main component = the one containing a pod, else the largest
        int mainComp = 0;
        int bestScore = -1;
        Map<Integer, Integer> sizes = new java.util.HashMap<>();
        for (Map.Entry<Part, Integer> e : comp.entrySet()) {
            sizes.merge(e.getValue(), 1, Integer::sum);
        }
        for (Map.Entry<Part, Integer> e : comp.entrySet()) {
            int score = sizes.get(e.getValue()) + ("pod".equals(e.getKey().type.type) ? 10000 : 0);
            if (score > bestScore) { bestScore = score; mainComp = e.getValue(); }
        }
        for (int c = 0; c < ncomp; c++) {
            if (c == mainComp) continue;
            Ship ns = new Ship(world);
            ns.origin.set(this.origin);
            ns.originVel.set(this.originVel);
            List<Part> moving = new ArrayList<>();
            for (Part p : parts) if (comp.get(p) == c) moving.add(p);
            for (Part p : moving) {
                parts.remove(p);
                ns.parts.add(p);
                p.ship = ns;
            }
            // move links
            List<Link> mv = new ArrayList<>();
            for (Link l : links) if (moving.contains(l.a) || moving.contains(l.b)) mv.add(l);
            links.removeAll(mv);
            ns.links.addAll(mv);
            // detached fragments cannot stage further (indices no longer valid)
            ns.currentStage = Integer.MAX_VALUE / 2;
            world.addShip(ns);
        }
        // remap remaining stage indices for the main ship (parts shifted)
        if (!stages.isEmpty()) {
            // rebuild index: original design index is no longer tracked post-split;
            // safest is to drop un-fired stages that reference missing parts.
            List<List<Integer>> kept = new ArrayList<>();
            for (int i = currentStage; i < stages.size(); i++) {
                List<Integer> st = new ArrayList<>();
                for (int idx : stages.get(i)) {
                    // keep only indices still valid
                    if (idx < parts.size()) st.add(idx);
                }
                if (!st.isEmpty()) kept.add(st);
            }
            stages.clear();
            stages.addAll(kept);
            currentStage = 0;
        }
    }

    // ---------------------------------------------------------------- fuel

    /** parts in the same fuel-network component as `from` (fuelLine edges). */
    private List<Part> fuelComponent(Part from, int fuelType) {
        List<Part> out = new ArrayList<>();
        Set<Part> seen = new HashSet<>();
        List<Part> stack = new ArrayList<>();
        stack.add(from);
        seen.add(from);
        while (!stack.isEmpty()) {
            Part cur = stack.remove(stack.size() - 1);
            if (cur.type.tank != null && cur.getFuelType() == fuelType) out.add(cur);
            for (Link l : links) {
                if (!l.fuelEdge) continue;
                Part other = l.a == cur ? l.b : (l.b == cur ? l.a : null);
                if (other != null && !seen.contains(other)) {
                    seen.add(other);
                    stack.add(other);
                }
            }
        }
        return out;
    }

    private List<Part> tanksOf(int fuelType) {
        List<Part> out = new ArrayList<>();
        for (Part p : parts) {
            if (p.type.tank != null && p.getFuelType() == fuelType) out.add(p);
        }
        return out;
    }

    public double fuelTotal(int fuelType) {
        double t = 0;
        for (Part p : tanksOf(fuelType)) t += p.fuel;
        return t;
    }

    public double fuelCapacity(int fuelType) {
        double t = 0;
        for (Part p : tanksOf(fuelType)) t += p.getFuelCapacity();
        return t;
    }

    /**
     * Tanks a consumer is allowed to draw from (item: fuel-line supply).
     *  - solid (3): only the consumer's own tank (SRBs burn internally);
     *  - electric (2): the whole ship's grid (batteries have no fuel lines);
     *  - monopropellant (1): the whole ship too (round 11 item 4 — RCS
     *    thrusters sip from any mono tank aboard, matching KSP rules);
     *  - liquid (0): the fuel-line networks of every tank directly attached
     *    to the consumer (any link), plus the consumer's own network when it
     *    is a tank itself. A tank reachable only through parts without
     *    fuelLine attach points (pods, detachers, batteries...) is isolated.
     */
    private List<Part> drainScope(Part consumer, int fuelType) {
        if (fuelType == 3) {
            List<Part> s = new ArrayList<>();
            if (consumer != null && consumer.type.tank != null && consumer.getFuelType() == 3) {
                s.add(consumer);
            }
            return s;
        }
        if (fuelType == 2) return tanksOf(2);
        if (fuelType == 1) return tanksOf(1);
        Set<Part> scope = new LinkedHashSet<>();
        List<Part> seeds = new ArrayList<>();
        if (consumer != null) {
            if (consumer.type.tank != null) seeds.add(consumer);
            for (Link l : links) {
                Part o = l.a == consumer ? l.b : (l.b == consumer ? l.a : null);
                if (o != null && o.type.tank != null) seeds.add(o);
            }
        }
        for (Part s : seeds) scope.addAll(fuelComponent(s, fuelType));
        return new ArrayList<>(scope);
    }

    /**
     * Drain fuel for a specific consumer from its supply scope: liquid fuel
     * comes only through fuel lines, mono/electric from the whole ship —
     * see drainScope. Returns the amount actually drained, spread evenly
     * across the scope.
     */
    public double drainFuel(Part consumer, int fuelType, double amount) {
        List<Part> tanks = drainScope(consumer, fuelType);
        double total = 0;
        for (Part p : tanks) total += p.fuel;
        double want = Math.min(amount, total);
        if (want <= 0) return 0;
        double frac = want / total;
        for (Part p : tanks) p.setFuel(p.fuel * (1 - frac));
        return want;
    }

    /** Drain fuel evenly across all tanks of that type in the ship; returns amount actually drained. */
    public double drainFuel(int fuelType, double amount) {
        List<Part> tanks = tanksOf(fuelType);
        double total = 0;
        for (Part p : tanks) total += p.fuel;
        double want = Math.min(amount, total);
        if (want <= 0) return 0;
        double frac = want / total;
        for (Part p : tanks) p.setFuel(p.fuel * (1 - frac));
        return want;
    }

    /** Transfer fuel between tanks: takes from `part`'s tank and spreads to others (or reverse if negative). */
    public double transferFuel(Part part, int fuelType, double amount) {
        if (part.type.tank == null || part.getFuelType() != fuelType) return 0;
        // equalize only inside this tank's own supply scope: the fuel-line
        // network for liquid, the ship-wide grid for electric/mono. Solid
        // fuel never transfers.
        List<Part> others = (fuelType == 2 || fuelType == 1)
                ? tanksOf(fuelType) : fuelComponent(part, fuelType);
        others.remove(part);
        if (others.isEmpty()) return 0;
        if (amount > 0) {
            double give = Math.min(amount, part.fuel);
            double space = 0;
            for (Part o : others) space += o.getFuelCapacity() - o.fuel;
            give = Math.min(give, space);
            if (give <= 0) return 0;
            double frac = give / space;
            for (Part o : others) o.setFuel(o.fuel + (o.getFuelCapacity() - o.fuel) * frac);
            part.setFuel(part.fuel - give);
            return give;
        } else {
            double take = Math.min(-amount, part.getFuelCapacity() - part.fuel);
            double avail = 0;
            for (Part o : others) avail += o.fuel;
            take = Math.min(take, avail);
            if (take <= 0) return 0;
            double frac = take / avail;
            for (Part o : others) o.setFuel(o.fuel * (1 - frac));
            part.setFuel(part.fuel + take);
            return -take;
        }
    }

    /** Add fuel into the network, spread across tanks with free space; returns amount actually added. */
    public double addFuel(int fuelType, double amount) {
        List<Part> tanks = tanksOf(fuelType);
        double space = 0;
        for (Part p : tanks) space += p.getFuelCapacity() - p.fuel;
        double add = Math.min(amount, space);
        if (add <= 0 || space <= 0) return 0;
        double frac = add / space;
        for (Part p : tanks) p.setFuel(p.fuel + (p.getFuelCapacity() - p.fuel) * frac);
        return add;
    }

    // ------------------------------------------- drag occlusion (round 11 item 2)

    private float dragCacheTime = -100f;
    private float dragCacheDirX, dragCacheDirY;
    private int dragCachePartCount = -1;
    private final Vector2 dragP1 = new Vector2(), dragP2 = new Vector2();

    /** Reusable raycast callback: does this sample ray hit another part of this ship? */
    private final class OcclusionQuery implements RayCastCallback {
        Part self;
        boolean blocked;
        @Override
        public float reportRayFixture(Fixture fixture, Vector2 point, Vector2 normal, float fraction) {
            Object ud = fixture.getBody().getUserData();
            if (ud instanceof Part && ud != self && ((Part) ud).ship == Ship.this) {
                blocked = true;
                return 0; // shadowed — terminate the ray
            }
            return -1; // ignore other ships / ground, keep looking
        }
    }
    private final OcclusionQuery occlusionQuery = new OcclusionQuery();

    /**
     * Recompute each part's aerodynamic exposure (0..1): 8 sample points are
     * spread across the part's silhouette perpendicular to the airflow, and a
     * sample counts as exposed when a ray cast upwind hits no other part of
     * THIS ship. Cached at ~15 Hz; recomputed early when the flow direction
     * turns more than ~8° or the part count changes. Must be called BEFORE
     * boxWorld.step (raycasts are illegal while the world is locked).
     */
    public void updateDragExposure(float rvx, float rvy, float time) {
        float sp2 = rvx * rvx + rvy * rvy;
        if (sp2 < 1f) { // no meaningful airflow — everything fully exposed
            for (Part p : parts) p.dragExposure = 1f;
            return;
        }
        float inv = 1f / (float) Math.sqrt(sp2);
        float dx = rvx * inv, dy = rvy * inv; // upwind direction
        boolean dirty = dragCachePartCount != parts.size()
                || time - dragCacheTime > 1f / 15f
                || Math.abs(dx * dragCacheDirY - dy * dragCacheDirX) > 0.1392f; // sin(8°)
        if (!dirty) return;
        dragCacheTime = time;
        dragCacheDirX = dx;
        dragCacheDirY = dy;
        dragCachePartCount = parts.size();
        if (world == null || world.boxWorld == null) return;
        // ray length: furthest upwind extent of the ship + margin
        float rayLen = 30f;
        for (Part p : parts) {
            if (p.body == null) continue;
            Vector2 c = p.body.getWorldCenter();
            rayLen = Math.max(rayLen, Math.abs(c.x * dx + c.y * dy) + 30f);
        }
        final int SAMPLES = 8;
        float px = -dy, py = dx; // unit vector perpendicular to the flow
        for (Part p : parts) {
            if (p.body == null) { p.dragExposure = 1f; continue; }
            float ang = p.body.getAngle();
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            // projected half-width of the part's box onto the perpendicular:
            // (w/2)|ex·perp| + (h/2)|ey·perp|, shrunk 10% to skip grazing rays
            float halfW = ((p.type.width / 2f) * Math.abs(ca * px + sa * py)
                    + (p.type.height / 2f) * Math.abs(-sa * px + ca * py)) * 0.9f;
            Vector2 center = p.body.getWorldCenter();
            int exposed = 0;
            occlusionQuery.self = p;
            for (int i = 0; i < SAMPLES; i++) {
                float t = (i / (float) (SAMPLES - 1)) * 2f - 1f; // -1..1
                dragP1.set(center.x + px * halfW * t, center.y + py * halfW * t);
                dragP2.set(dragP1.x + dx * rayLen, dragP1.y + dy * rayLen);
                occlusionQuery.blocked = false;
                world.boxWorld.rayCast(occlusionQuery, dragP1, dragP2);
                if (!occlusionQuery.blocked) exposed++;
            }
            p.dragExposure = exposed / (float) SAMPLES;
        }
    }

    // ---------------------------------------------------------------- stages

    /**
     * STAGE semantics (round 26, item B4): a part's `group` IS its stage
     * number (0 = not staged, set in the editor by dragging parts into STAGE
     * slots). Pressing STAGE fires the NEXT stage number — the smallest
     * group number greater than `currentStage` among this ship's parts — by
     * calling onStage on every part in it (engines ignite at the current
     * throttle, detachers sever per their MODE, chutes/legs deploy).
     * Returns the stage number fired, or -1 when no staged parts remain.
     *
     * This replaces the old design-stage-list semantics (the `stages` list
     * is still loaded/saved for backward compatibility but no longer drives
     * activation) and supersedes the ACTIVATE-by-group behavior.
     * `currentStage` now means "last stage number fired".
     */
    public int activateStage() {
        int next = -1;
        for (Part p : parts) {
            if (p.group > currentStage && (next < 0 || p.group < next)) next = p.group;
        }
        if (next < 0) return -1;
        // snapshot targets BEFORE firing: a detacher's onStage defers joint
        // destruction + ship split, which mutates `parts` after we return —
        // resolving the group to references first keeps every member reachable.
        // Order within the stage (round 26): DETACHERS FIRE LAST. The parts
        // being dropped (engines, tanks...) must complete their onStage
        // first, so a staged-away engine is already burning at the instant
        // the cut happens instead of waking up dead.
        List<Part> targets = new ArrayList<>();
        List<Part> detachers = new ArrayList<>();
        for (Part p : parts) {
            if (p.group == next) {
                if ("detacher".equals(p.type.type)) detachers.add(p);
                else targets.add(p);
            }
        }
        targets.addAll(detachers);
        for (Part p : targets) {
            if (p.body == null || p.ship == null || !p.ship.parts.contains(p)) continue;
            p.callOnStage();
        }
        world.processDeferredStructure();
        currentStage = next;
        return next;
    }

    /** map from design-part index to runtime Part (same order, parts may have been filtered). */
    public Part partAt(int designIndex) {
        return (designIndex >= 0 && designIndex < parts.size()) ? parts.get(designIndex) : null;
    }

    /** Runtime id of this ship (session-unique; written as ShipNode id in sandbox saves). */
    public int getId() { return id; }

    /**
     * Re-weld two parts restored from a save file: anchor at the midpoint of
     * their nearest attach points and resolve the weld params from those
     * attach defs (same rule as the JSON loader used).
     */
    public void weldLoaded(Part a, Part b) {
        Vector2 anchor = bestAnchor(a, b);
        if (anchor != null) {
            weld(a, b, anchor, nearestAttach(a, anchor), nearestAttach(b, anchor));
        }
    }

    // ---------------------------------------------------------------- update

    public void updateScripts(double dt) {
        for (Part p : parts) p.flameLevel = 0f;
        for (Part p : parts) p.callOnUpdate(dt);
        // stage flags persist until the end of the frame so Lua onUpdate can read them
        for (Part p : parts) p.stageActivatedThisFrame = false;
    }

    public Vector2 centerOfMass(Vector2 out) {
        out.set(0, 0);
        if (parts.isEmpty()) return out;
        float m = 0;
        for (Part p : parts) {
            if (p.body == null) continue;
            float pm = p.body.getMass();
            out.x += p.body.getPosition().x * pm;
            out.y += p.body.getPosition().y * pm;
            m += pm;
        }
        if (m > 0) out.scl(1f / m);
        return out;
    }

    public Vector2 velocity(Vector2 out) {
        out.set(0, 0);
        if (parts.isEmpty()) return out;
        float m = 0;
        for (Part p : parts) {
            if (p.body == null) continue;
            float pm = p.body.getMass();
            out.x += p.body.getLinearVelocity().x * pm;
            out.y += p.body.getLinearVelocity().y * pm;
            m += pm;
        }
        if (m > 0) out.scl(1f / m);
        return out;
    }

    public Vec2d getUniverseVel() {
        Vector2 v = velocity(new Vector2());
        return new Vec2d(world.frameVel.x + originVel.x + v.x, world.frameVel.y + originVel.y + v.y);
    }

    public Vec2d getUniversePos() {
        Vector2 c = centerOfMass(new Vector2());
        return new Vec2d(origin.x + c.x, origin.y + c.y);
    }

    public void setBodiesActive(boolean active) {
        for (Part p : parts) {
            if (p.body != null) p.body.setActive(active);
        }
    }

    /** The part that provides control input/heading reference: the pod, else the first part. */
    public Part controlPart() {
        for (Part p : parts) if ("pod".equals(p.type.type)) return p;
        return parts.isEmpty() ? null : parts.get(0);
    }

    /** On-rails integration for distant inactive ships (bodies parked, origin moved). */
    public void integrateRails(double dt) {
        Vec2d com0 = getUniversePos();
        Planet np = world.nearestPlanetTo(com0.x, com0.y);
        if (np != null) {
            double dx = com0.x - np.pos.x, dy = com0.y - np.pos.y;
            double dist = Math.hypot(dx, dy);
            double surf = np.radius + np.heightAt(Math.atan2(dy, dx));
            double alt = dist - surf;
            // round 13 fix: a ship PARKED on the surface must not gravity-sink
            // through the terrain while on rails (no collision in rails mode).
            // Landed = near the surface AND slow relative to the planet.
            // Round 14: the hold must RIDE the planet — the body translates
            // along its rail (and super-warp advances it fast); a parked ship
            // that just froze its origin was left behind by the moving planet.
            double uvx = originVel.x + world.frameVel.x - np.vel.x;
            double uvy = originVel.y + world.frameVel.y - np.vel.y;
            if (alt < 50.0 && Math.hypot(uvx, uvy) < 1.0) {
                origin.add(np.vel.x * dt, np.vel.y * dt);
                originVel.set(np.vel.x - world.frameVel.x, np.vel.y - world.frameVel.y);
                return; // hold position relative to the surface
            }
        }
        Vec2d g = world.gravityAt(com0.x, com0.y);
        originVel.add(g.x * dt, g.y * dt);
        origin.add(originVel.x * dt, originVel.y * dt);
        // Hard floor: never end a rails step below the terrain (ballistic
        // stages falling in while distant get clamped to the surface instead
        // of tunneling through the planet).
        if (np != null) {
            Vec2d com1 = getUniversePos();
            double dx = com1.x - np.pos.x, dy = com1.y - np.pos.y;
            double dist = Math.hypot(dx, dy);
            double ang = Math.atan2(dy, dx);
            double surf = np.radius + np.heightAt(ang);
            if (dist < surf + 0.5 && dist > 1e-9) {
                double ux = dx / dist, uy = dy / dist;
                double push = (surf + 0.5) - dist;
                origin.x += ux * push;
                origin.y += uy * push;
                double rv = originVel.x * ux + originVel.y * uy; // radial (frame dirs match universe)
                if (rv < 0) { originVel.x -= rv * ux; originVel.y -= rv * uy; }
            }
        }
    }

    /** Shift every body of this ship by (dx,dy) (used when the floating origin moves). */
    public void shiftBodies(double dx, double dy) {
        for (Part p : parts) {
            if (p.body != null) {
                p.body.setTransform((float) (p.body.getPosition().x + dx),
                        (float) (p.body.getPosition().y + dy), p.body.getAngle());
            }
        }
        origin.x -= dx;
        origin.y -= dy;
    }

    public void destroy() {
        // joints first: Box2D auto-destroys attached joints when a body dies
        for (Link l : new ArrayList<>(links)) destroyLink(l);
        for (Part p : parts) p.destroyBody();
        parts.clear();
    }

    // ---------------------------------------------------------------- save

    public String toJson() {
        Json.Writer w = new Json.Writer();
        w.obj();
        w.set("name", name);
        w.set("originX", origin.x);
        w.set("originY", origin.y);
        w.set("velX", originVel.x);
        w.set("velY", originVel.y);
        w.set("stage", currentStage);
        w.key("parts"); w.arr();
        Vector2 com = centerOfMass(new Vector2());
        for (Part p : parts) {
            w.obj();
            w.set("t", p.type.id);
            if (p.body != null) {
                w.set("x", (double) p.body.getPosition().x);
                w.set("y", (double) p.body.getPosition().y);
                w.set("a", (double) p.body.getAngle());
                Vector2 v = p.body.getLinearVelocity();
                w.set("vx", (double) v.x);
                w.set("vy", (double) v.y);
                w.set("va", (double) p.body.getAngularVelocity());
            }
            w.set("fuel", p.fuel);
            w.set("dep", p.deployed);
            if (p.group > 0) w.set("grp", p.group);
            if (p.flippedX) w.set("fx", 1);
            if (p.flippedY) w.set("fy", 1);
            w.endObj();
        }
        w.endArr();
        // save link structure as index pairs
        w.key("links"); w.arr();
        for (Link l : links) {
            int ia = parts.indexOf(l.a), ib = parts.indexOf(l.b);
            if (ia < 0 || ib < 0) continue;
            w.obj();
            w.set("a", ia);
            w.set("b", ib);
            w.endObj();
        }
        w.endArr();
        w.key("stages"); w.arr();
        for (List<Integer> st : stages) {
            w.arr();
            for (int i : st) w.val(i);
            w.endArr();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    /** Rebuild a ship from its JSON record. */
    public static Ship fromJson(GameWorld world, Json.JObj o) {
        Ship s = new Ship(world);
        s.name = o.getStr("name", "Ship");
        s.origin.set(o.getNum("originX", 0), o.getNum("originY", 0));
        s.originVel.set(o.getNum("velX", 0), o.getNum("velY", 0));
        s.currentStage = o.getInt("stage", 0);
        List<Json.Value> ps = o.getArr("parts");
        if (ps != null) {
            for (Json.Value v : ps) {
                Json.JObj po = v.asObj();
                PartType t = PartList.get(po.getStr("t", "fuselage-1"));
                if (t == null) continue;
                ShipDesign.DesignPart dp = new ShipDesign.DesignPart(t.id, 0, 0, 0);
                Part p = new Part(t, s, dp);
                // mirror flags BEFORE createBody: collider verts and attach
                // defs mirror off these (round 27)
                p.flippedX = po.getInt("fx", 0) != 0;
                p.flippedY = po.getInt("fy", 0) != 0;
                p.createBody((float) po.getNum("x", 0), (float) po.getNum("y", 0),
                        (float) po.getNum("a", 0));
                p.body.setTransform(p.body.getPosition(), (float) po.getNum("a", 0));
                p.body.setLinearVelocity((float) po.getNum("vx", 0), (float) po.getNum("vy", 0));
                p.body.setAngularVelocity((float) po.getNum("va", 0));
                p.fuel = po.getNum("fuel", p.fuel);
                p.deployed = po.getBool("dep", false);
                p.group = po.getInt("grp", 0);
                p.updateMass();
                s.parts.add(p);
            }
        }
        // onLoad BEFORE re-welding saved links (round 9 item 1): per-part
        // joint overrides set in onLoad must drive the weld resolution.
        for (Part p : s.parts) p.callOnLoad();
        List<Json.Value> ls = o.getArr("links");
        if (ls != null) {
            for (Json.Value v : ls) {
                Json.JObj lo = v.asObj();
                int ia = lo.getInt("a", -1), ib = lo.getInt("b", -1);
                if (ia < 0 || ib < 0 || ia >= s.parts.size() || ib >= s.parts.size()) continue;
                Part a = s.parts.get(ia), b = s.parts.get(ib);
                // weld at the average of their nearest attach points (approx: midpoint of COMs is wrong; use closest attach)
                Vector2 anchor = bestAnchor(a, b);
                if (anchor != null) {
                    PartType.AttachPoint apA = nearestAttach(a, anchor);
                    PartType.AttachPoint apB = nearestAttach(b, anchor);
                    s.weld(a, b, anchor, apA, apB);
                }
            }
        }
        List<Json.Value> ss = o.getArr("stages");
        if (ss != null) {
            for (Json.Value v : ss) {
                List<Integer> st = new ArrayList<>();
                for (Json.Value i : v.asArr()) st.add(i.asInt(0));
                s.stages.add(st);
            }
        }
        return s;
    }

    private static Vector2 bestAnchor(Part a, Part b) {
        List<Vector2> wa = a.attachWorldPositions();
        List<Vector2> wb = b.attachWorldPositions();
        float best = Float.MAX_VALUE;
        Vector2 bestP = null;
        for (Vector2 x : wa) for (Vector2 y : wb) {
            float d = x.dst(y);
            if (d < best) { best = d; bestP = new Vector2((x.x + y.x) / 2, (x.y + y.y) / 2); }
        }
        return bestP;
    }

    private static PartType.AttachPoint nearestAttach(Part p, Vector2 world) {
        PartType.AttachPoint best = null;
        float bd = Float.MAX_VALUE;
        List<Vector2> ws = p.attachWorldPositions();
        for (int i = 0; i < ws.size(); i++) {
            float d = ws.get(i).dst(world);
            if (d < bd) { bd = d; best = p.attachDefs().get(i); }
        }
        if (best == null) best = new PartType.AttachPoint();
        return best;
    }
}
