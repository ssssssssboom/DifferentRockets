package com.differentrockets.game;

import com.badlogic.gdx.utils.XmlReader;
import com.differentrockets.util.Json;
import com.differentrockets.util.Xml;

import java.util.ArrayList;
import java.util.List;

/**
 * A ship as edited in the build editor: a list of placed parts (local editor
 * coordinates, meters, y-up, rotation steps of 90 degrees) plus a stage list.
 * Persistent format: the Show_Rocket-compatible XML (see toXml/fromXml);
 * JSON is kept only for in-memory undo/redo snapshots.
 */
public class ShipDesign {

    public static class DesignPart {
        public String typeId;
        public float x, y;       // editor position (meters)
        public int rot;          // 0..3 (steps of 90 deg CCW) == XML editorAngle
        /** activation group 0 = none, 1..8 (item 6); one group per part.
         *  Maps to the XML Pod/Staging Step list (group g -> Step g-1). */
        public int group;
        /** XML flippedX / flippedY (visual mirror; preserved even where the
         *  editor has no flip-specific behavior). */
        public boolean flippedX, flippedY;

        public DesignPart() {}
        public DesignPart(String typeId, float x, float y, int rot) {
            this.typeId = typeId; this.x = x; this.y = y; this.rot = rot;
        }
    }

    public final List<DesignPart> parts = new ArrayList<>();
    /** stage index -> list of part indices fired by that stage */
    public final List<List<Integer>> stages = new ArrayList<>();
    /** ship name recovered from the XML Pod name attribute on load (null if none). */
    public String loadedName;

    /**
     * A weld between two placed parts (shared editor/game interface).
     * partA = parent (the pre-existing part that was snapped TO),
     * partB = child (the part that was attached); attachA/attachB are indices
     * into each part type's attach list. Every non-root part (index 0 = root)
     * has at most one incoming connection as partB, so the connections form a
     * forest rooted at part 0.
     */
    public static class Connection {
        public int partA, partB;
        public int attachA, attachB;
        public Connection() {}
        public Connection(int partA, int partB, int attachA, int attachB) {
            this.partA = partA; this.partB = partB;
            this.attachA = attachA; this.attachB = attachB;
        }
    }

    /** weld records of the design (see Connection); maps to XML Connections. */
    public final List<Connection> connections = new ArrayList<>();

    public void clear() { parts.clear(); stages.clear(); connections.clear(); }

    /**
     * The part itself plus every descendant hanging off it via parent->child
     * connections (the block that comes off when this part is detached).
     */
    public List<Integer> subtreeOf(int idx) {
        List<Integer> out = new ArrayList<>();
        if (idx < 0 || idx >= parts.size()) return out;
        boolean[] seen = new boolean[parts.size()];
        List<Integer> queue = new ArrayList<>();
        seen[idx] = true;
        queue.add(idx);
        while (!queue.isEmpty()) {
            int cur = queue.remove(queue.size() - 1);
            out.add(cur);
            for (Connection c : connections) {
                if (c.partA == cur && c.partB >= 0 && c.partB < parts.size() && !seen[c.partB]) {
                    seen[c.partB] = true;
                    queue.add(c.partB);
                }
            }
        }
        return out;
    }

    /** Remove parts and every connection touching them, reindexing the rest. */
    public void removeParts(java.util.Collection<Integer> victims) {
        boolean[] kill = new boolean[parts.size()];
        for (int v : victims) if (v >= 0 && v < parts.size()) kill[v] = true;
        int[] remap = new int[parts.size()];
        List<DesignPart> np = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (kill[i]) { remap[i] = -1; continue; }
            remap[i] = n++;
            np.add(parts.get(i));
        }
        parts.clear();
        parts.addAll(np);
        List<Connection> nc = new ArrayList<>();
        for (Connection c : connections) {
            if (c.partA < 0 || c.partA >= kill.length || c.partB < 0 || c.partB >= kill.length) continue;
            if (kill[c.partA] || kill[c.partB]) continue;
            nc.add(new Connection(remap[c.partA], remap[c.partB], c.attachA, c.attachB));
        }
        connections.clear();
        connections.addAll(nc);
        // stages reference part indices too — the safest rebuild is autoStage
        autoStage();
    }

    /** Connectivity mask: parts reachable from the root (index 0), BFS over
     *  the UNDIRECTED connection graph (a detached block stays internally
     *  connected but is not root-connected). */
    public boolean[] componentFromRoot() {
        boolean[] conn = new boolean[parts.size()];
        if (parts.isEmpty()) return conn;
        List<Integer> queue = new ArrayList<>();
        conn[0] = true;
        queue.add(0);
        while (!queue.isEmpty()) {
            int cur = queue.remove(queue.size() - 1);
            for (Connection c : connections) {
                int other = c.partA == cur ? c.partB : c.partB == cur ? c.partA : -1;
                if (other >= 0 && other < parts.size() && !conn[other]) {
                    conn[other] = true;
                    queue.add(other);
                }
            }
        }
        return conn;
    }

    /** Deep copy via the JSON round-trip (used for undo/redo snapshots). */
    public ShipDesign snapshot() { return fromJson(toJson()); }

    /** Replace this design's contents with another design's (keeps identity). */
    public void copyFrom(ShipDesign o) {
        parts.clear();
        for (DesignPart p : o.parts) {
            DesignPart np = new DesignPart(p.typeId, p.x, p.y, p.rot);
            np.group = p.group;
            np.flippedX = p.flippedX;
            np.flippedY = p.flippedY;
            parts.add(np);
        }
        stages.clear();
        for (List<Integer> s : o.stages) stages.add(new ArrayList<>(s));
        connections.clear();
        for (Connection c : o.connections) {
            connections.add(new Connection(c.partA, c.partB, c.attachA, c.attachB));
        }
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

    // ---------------- Show_Rocket-compatible XML ----------------

    private static String f6(double v) {
        return String.format(java.util.Locale.US, "%.6f", v);
    }

    /** XML attribute-value escaping (&, <, "). */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    /**
     * Serialize in the Show_Rocket ship format:
     *   Ship[version,liftedOff,touchingGround] > (DisconnectedParts, Parts, Connections)
     *   Part[partType,id,x,y,angle,angleV,editorAngle] plus, for non-tank/non-pod
     *   parts, [activated,exploded,flippedX,flippedY] (and solar: extension).
     *   Type children: Tank[fuel], Engine[fuel], Pod[throttle,name] > Staging.
     * Editor activation groups are written as the sample's staging: group g
     * becomes Step #g (1-based) of every Pod's Staging element; the ship name
     * rides in the first Pod's name attribute. Weld records (connections list)
     * map to Connections with 1-based attach numbering; empty when none.
     */
    public String toXml(String shipName) {
        String[] ids = new String[parts.size()];
        for (int i = 0; i < parts.size(); i++) ids[i] = i == 0 ? "1" : String.valueOf(100000000 + i);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Ship version=\"1\" liftedOff=\"0\" touchingGround=\"0\">\n");
        sb.append("<DisconnectedParts/>\n");
        sb.append("<Parts>\n");
        boolean named = false;
        for (int i = 0; i < parts.size(); i++) {
            DesignPart p = parts.get(i);
            PartType t = PartList.get(p.typeId);
            boolean isTank = t != null && t.tank != null;
            boolean isPod = t != null && "pod".equals(t.type);
            sb.append("<Part partType=\"").append(esc(p.typeId)).append("\" id=\"").append(ids[i])
                    .append("\" x=\"").append(f6(p.x)).append("\" y=\"").append(f6(p.y))
                    .append("\" angle=\"").append(f6(p.rot * Math.PI / 2)).append("\"")
                    .append(" angleV=\"0.000000\" editorAngle=\"").append(p.rot).append("\"");
            if (!isTank && !isPod) {
                sb.append(" activated=\"0\" exploded=\"0\"")
                        .append(" flippedX=\"").append(p.flippedX ? 1 : 0).append("\"")
                        .append(" flippedY=\"").append(p.flippedY ? 1 : 0).append("\"");
                if (t != null && t.solar != null) sb.append(" extension=\"0.000000\"");
            }
            String child = null;
            if (isTank) child = "<Tank fuel=\"" + f6(t.tank.fuel) + "\"/>";
            else if (t != null && t.engine != null) child = "<Engine fuel=\"0.000000\"/>";
            if (isPod) {
                sb.append(">\n");
                sb.append("<Pod throttle=\"0.000000\" name=\"")
                        .append(named ? "" : esc(shipName)).append("\">\n");
                named = true;
                appendStagingXml(sb, ids);
                sb.append("</Pod>\n</Part>\n");
            } else if (child != null) {
                sb.append(">\n").append(child).append("\n</Part>\n");
            } else {
                sb.append("/>\n");
            }
        }
        sb.append("</Parts>\n");
        if (connections.isEmpty()) {
            sb.append("<Connections/>\n");
        } else {
            sb.append("<Connections>\n");
            for (Connection c : connections) {
                if (c.partA < 0 || c.partA >= parts.size() || c.partB < 0 || c.partB >= parts.size())
                    continue;
                // XML attach numbering is 1-based (sample: values 1..4)
                sb.append("<Connection parentAttachPoint=\"").append(c.attachA + 1)
                        .append("\" childAttachPoint=\"").append(c.attachB + 1)
                        .append("\" parentPart=\"").append(ids[c.partA])
                        .append("\" childPart=\"").append(ids[c.partB]).append("\"/>\n");
            }
            sb.append("</Connections>\n");
        }
        sb.append("</Ship>");
        return sb.toString();
    }

    /** group g -> Step g (1-based), parts as Activate Id refs (sample semantics). */
    private void appendStagingXml(StringBuilder sb, String[] ids) {
        sb.append("<Staging currentStage=\"0\">\n");
        for (int g = 1; g <= 8; g++) {
            StringBuilder step = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).group == g) {
                    step.append("<Activate Id=\"").append(ids[i]).append("\" moved=\"0\"/>\n");
                }
            }
            if (step.length() == 0) continue; // sample omits empty steps
            sb.append("<Step>\n").append(step).append("</Step>\n");
        }
        sb.append("</Staging>\n");
    }

    /**
     * Parse a Show_Rocket ship XML. Tolerant of missing fields (defaults):
     * editorAngle falls back to round(angle / 90deg); flipped/activated absent
     * -> false/0; activation groups come from the first Pod's Staging Steps
     * (Step k -> group k+1). Only the main Parts list is loaded;
     * DisconnectedParts are accepted but not merged into the editor design.
     */
    public static ShipDesign fromXml(String xml) {
        if (!xml.isEmpty() && xml.charAt(0) == '﻿') xml = xml.substring(1);
        XmlReader.Element root;
        try {
            root = new XmlReader().parse(xml);
        } catch (Exception e) {
            throw new RuntimeException("Invalid ship XML: " + e.getMessage(), e);
        }
        if (root == null || !"Ship".equals(root.getName())) {
            throw new RuntimeException("Not a ship XML (root element is not <Ship>)");
        }
        ShipDesign d = new ShipDesign();
        List<String> ids = new ArrayList<>();
        XmlReader.Element partsEl = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            XmlReader.Element ch = root.getChild(i);
            if ("Parts".equals(ch.getName())) { partsEl = ch; break; }
        }
        if (partsEl != null) {
            for (int i = 0; i < partsEl.getChildCount(); i++) {
                XmlReader.Element pe = partsEl.getChild(i);
                if (!"Part".equals(pe.getName())) continue;
                DesignPart dp = new DesignPart();
                dp.typeId = pe.getAttribute("partType", "pod-1");
                dp.x = pe.getFloatAttribute("x", 0f);
                dp.y = pe.getFloatAttribute("y", 0f);
                int ea = pe.getIntAttribute("editorAngle", -1);
                if (ea < 0) {
                    double ang = Xml.getDouble(pe, "angle", 0);
                    ea = (int) Math.round(ang / (Math.PI / 2));
                }
                dp.rot = ((ea % 4) + 4) % 4;
                // the sample encodes booleans as 0/1, which Boolean.parseBoolean
                // would misread (only "true" is true) — parse as ints instead
                dp.flippedX = pe.getIntAttribute("flippedX", 0) != 0;
                dp.flippedY = pe.getIntAttribute("flippedY", 0) != 0;
                ids.add(pe.getAttribute("id", String.valueOf(i)));
                d.parts.add(dp);
            }
        }
        // staging: first Pod that carries a Staging element wins
        if (partsEl != null) {
            for (int i = 0; i < partsEl.getChildCount(); i++) {
                XmlReader.Element pe = partsEl.getChild(i);
                if (!"Part".equals(pe.getName())) continue;
                XmlReader.Element pod = null;
                for (int c = 0; c < pe.getChildCount(); c++) {
                    if ("Pod".equals(pe.getChild(c).getName())) { pod = pe.getChild(c); break; }
                }
                if (pod == null) continue;
                String nm = pod.getAttribute("name", null);
                if (nm != null && !nm.isEmpty() && d.loadedName == null) d.loadedName = nm;
                XmlReader.Element staging = null;
                for (int c = 0; c < pod.getChildCount(); c++) {
                    if ("Staging".equals(pod.getChild(c).getName())) { staging = pod.getChild(c); break; }
                }
                if (staging == null) continue;
                int stepIdx = 0;
                for (int s = 0; s < staging.getChildCount(); s++) {
                    XmlReader.Element step = staging.getChild(s);
                    if (!"Step".equals(step.getName())) continue;
                    stepIdx++;
                    if (stepIdx > 8) break;
                    for (int a = 0; a < step.getChildCount(); a++) {
                        XmlReader.Element act = step.getChild(a);
                        if (!"Activate".equals(act.getName())) continue;
                        String ref = act.getAttribute("Id", null);
                        int partIdx = ref == null ? -1 : ids.indexOf(ref);
                        if (partIdx >= 0) d.parts.get(partIdx).group = stepIdx;
                    }
                }
                break; // one pod's staging is enough
            }
        }
        // connections: parentPart/childPart are id refs; attach numbering is 1-based
        for (int i = 0; i < root.getChildCount(); i++) {
            XmlReader.Element ch = root.getChild(i);
            if (!"Connections".equals(ch.getName())) continue;
            for (int c = 0; c < ch.getChildCount(); c++) {
                XmlReader.Element ce = ch.getChild(c);
                if (!"Connection".equals(ce.getName())) continue;
                int pa = ids.indexOf(ce.getAttribute("parentPart", ""));
                int pb = ids.indexOf(ce.getAttribute("childPart", ""));
                if (pa < 0 || pb < 0) continue;
                d.connections.add(new Connection(pa, pb,
                        ce.getIntAttribute("parentAttachPoint", 1) - 1,
                        ce.getIntAttribute("childAttachPoint", 1) - 1));
            }
            break;
        }
        if (d.stages.isEmpty()) d.autoStage();
        return d;
    }

    // ---------------- JSON (undo/redo snapshots only) ----------------

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
            if (p.flippedX) w.set("fx", 1);
            if (p.flippedY) w.set("fy", 1);
            w.endObj();
        }
        w.endArr();
        w.key("conn"); w.arr();
        for (Connection c : connections) {
            w.arr();
            w.val(c.partA); w.val(c.partB); w.val(c.attachA); w.val(c.attachB);
            w.endArr();
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
                dp.flippedX = o.getInt("fx", 0) != 0;
                dp.flippedY = o.getInt("fy", 0) != 0;
                d.parts.add(dp);
            }
        }
        List<Json.Value> cs = root.getArr("conn");
        if (cs != null) {
            for (Json.Value v : cs) {
                List<Json.Value> a = v.asArr();
                if (a.size() >= 4) {
                    d.connections.add(new Connection(
                            a.get(0).asInt(0), a.get(1).asInt(0),
                            a.get(2).asInt(0), a.get(3).asInt(0)));
                }
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
