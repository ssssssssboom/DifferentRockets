package com.differentrockets.game;

import com.differentrockets.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * A ship as edited in the build editor: a list of placed parts (local editor
 * coordinates, meters, y-up, rotation steps of 90 degrees) plus a stage list.
 * Serializes to/from JSON for save/load.
 */
public class ShipDesign {

    public static class DesignPart {
        public String typeId;
        public float x, y;       // editor position (meters)
        public int rot;          // 0..3 (steps of 90 deg CCW)
        /** activation group 0 = none, 1..8 (item 6); one group per part. */
        public int group;

        public DesignPart() {}
        public DesignPart(String typeId, float x, float y, int rot) {
            this.typeId = typeId; this.x = x; this.y = y; this.rot = rot;
        }
    }

    public final List<DesignPart> parts = new ArrayList<>();
    /** stage index -> list of part indices fired by that stage */
    public final List<List<Integer>> stages = new ArrayList<>();

    public void clear() { parts.clear(); stages.clear(); }

    /** Deep copy via the JSON round-trip (used for undo/redo snapshots). */
    public ShipDesign snapshot() { return fromJson(toJson()); }

    /** Replace this design's contents with another design's (keeps identity). */
    public void copyFrom(ShipDesign o) {
        parts.clear();
        for (DesignPart p : o.parts) {
            DesignPart np = new DesignPart(p.typeId, p.x, p.y, p.rot);
            np.group = p.group;
            parts.add(np);
        }
        stages.clear();
        for (List<Integer> s : o.stages) stages.add(new ArrayList<>(s));
    }

    public void autoStage() {
        stages.clear();
        // stage 0: engines; stage 1: detachers; stage 2: parachutes + landers (if any)
        List<Integer> engines = new ArrayList<>();
        List<Integer> detachers = new ArrayList<>();
        List<Integer> aux = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            PartType t = PartList.get(parts.get(i).typeId);
            if (t == null) continue;
            if (t.engine != null) engines.add(i);
            else if ("detacher".equals(t.type)) detachers.add(i);
            else if ("parachute".equals(t.type) || "lander".equals(t.type)) aux.add(i);
        }
        if (!engines.isEmpty()) stages.add(engines);
        if (!detachers.isEmpty()) stages.add(detachers);
        if (!aux.isEmpty()) stages.add(aux);
        if (stages.isEmpty()) stages.add(new ArrayList<>());
    }

    // ---------------- JSON ----------------

    public String toJson() {
        Json.Writer w = new Json.Writer();
        w.obj();
        w.key("parts"); w.arr();
        for (DesignPart p : parts) {
            w.obj();
            w.set("t", p.typeId);
            w.set("x", p.x);
            w.set("y", p.y);
            w.set("r", p.rot);
            if (p.group > 0) w.set("g", p.group);
            w.endObj();
        }
        w.endArr();
        w.key("stages"); w.arr();
        for (List<Integer> st : stages) {
            w.arr();
            for (int idx : st) w.val(idx);
            w.endArr();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public static ShipDesign fromJson(String json) {
        ShipDesign d = new ShipDesign();
        Json.JObj root = Json.parse(json);
        List<Json.Value> ps = root.getArr("parts");
        if (ps != null) {
            for (Json.Value v : ps) {
                Json.JObj o = v.asObj();
                DesignPart dp = new DesignPart(
                        o.getStr("t", "pod-1"),
                        (float) o.getNum("x", 0),
                        (float) o.getNum("y", 0),
                        o.getInt("r", 0));
                dp.group = o.getInt("g", 0);
                d.parts.add(dp);
            }
        }
        List<Json.Value> ss = root.getArr("stages");
        if (ss != null) {
            for (Json.Value v : ss) {
                List<Integer> st = new ArrayList<>();
                for (Json.Value idx : v.asArr()) st.add(idx.asInt(0));
                d.stages.add(st);
            }
        }
        if (d.stages.isEmpty()) d.autoStage();
        return d;
    }
}
