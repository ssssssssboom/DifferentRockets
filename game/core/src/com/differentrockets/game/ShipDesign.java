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
        /** activation group 0 = none, 1..N (dynamic, item 6); one group per part.
         *  Maps to the XML Pod/Staging Step list (group g -> Step g). */
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

    /**
     * Show_Rocket / SR1 XML files store positions at half our internal unit
     * scale: our PartList sizes are exactly 2x SR1's (pod 4x3 vs our 2x1.5,
     * fueltank-3 4x16 vs our 2x8, ...), and the sample's coordinates fit
     * perfectly once doubled (pod y=0.75 -> bottom at 0 in our units).
     * XML coordinates are multiplied by this factor on load, divided on save.
     */
    private static final float XML_UNIT = 2f;

    /**
     * Rebuild the stages list from per-part groups (group g -> stages[g-1]).
     * Called whenever groups change so stages never go stale.
     */
    public void syncStagesFromGroups() {
        int max = 0;
        for (DesignPart p : parts) if (p.group > max) max = p.group;
        stages.clear();
        for (int g = 1; g <= max; g++) {
            List<Integer> st = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).group == g) st.add(i);
            }
            stages.add(st);
        }
        if (stages.isEmpty()) stages.add(new ArrayList<>());
    }

    /**
     * Copy of this design restricted to the root-connected weld tree (the
     * flyable main ship): floating blocks are dropped so launch never spawns
     * debris. Connections, groups and stages are remapped to the new indices.
     */
    public ShipDesign mainTreeOnly() {
        boolean[] mask = componentFromRoot();
        int[] remap = new int[parts.size()];
        ShipDesign d = new ShipDesign();
        for (int i = 0; i < parts.size(); i++) {
            if (!mask[i]) { remap[i] = -1; continue; }
            remap[i] = d.parts.size();
            DesignPart p = parts.get(i);
            DesignPart np = new DesignPart(p.typeId, p.x, p.y, p.rot);
            np.group = p.group;
            np.flippedX = p.flippedX;
            np.flippedY = p.flippedY;
            d.parts.add(np);
        }
        for (Connection c : connections) {
            if (c.partA < 0 || c.partA >= mask.length || c.partB < 0 || c.partB >= mask.length) continue;
            if (!mask[c.partA] || !mask[c.partB]) continue;
            d.connections.add(new Connection(remap[c.partA], remap[c.partB], c.attachA, c.attachB));
        }
        d.loadedName = loadedName;
        d.syncStagesFromGroups();
        return d;
    }

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
        // stages reference part indices, but groups are per-part and survive
        // the reindex — rebuild stages from groups, preserving manual groups
        syncStagesFromGroups();
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

    /**
     * Automatic staging, matching the sample Show_Rocket's Staging semantics:
     * only ACTIVATABLE parts of the root-connected tree are staged (engines,
     * detachers, parachutes/landers, solars — the sample never stages pods,
     * tanks, struts, ports or RCS). Parts are grouped by their tree depth
     * from the root (BFS down the parent->child weld tree, root pod = 0);
     * deepest parts fire first. Within one depth, engines come before
     * detachers, then parachutes/landers, then solars (the sample: side
     * engine d7 -> side detacher d6 -> main engine d2 -> radial detachers
     * d2 -> solars d2). The number of groups is dynamic; everything else
     * stays unassigned (group 0).
     */
    public void autoStage() {
        int n = parts.size();
        for (DesignPart p : parts) p.group = 0;
        if (n == 0) { stages.clear(); stages.add(new ArrayList<>()); return; }
        // BFS depth from the root (part 0) along parent->child edges; parts
        // outside the root tree keep depth -1 and are never staged
        int[] depth = new int[n];
        java.util.Arrays.fill(depth, -1);
        depth[0] = 0;
        List<Integer> queue = new ArrayList<>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int cur = queue.remove(0);
            for (Connection c : connections) {
                if (c.partA == cur && c.partB >= 0 && c.partB < n && depth[c.partB] < 0) {
                    depth[c.partB] = depth[cur] + 1;
                    queue.add(c.partB);
                }
            }
        }
        List<int[]> keys = new ArrayList<>(); // [depth, rank, index]
        for (int i = 0; i < n; i++) {
            int rank = categoryRank(i);
            if (depth[i] <= 0 || rank < 0) continue; // root itself never fires
            keys.add(new int[]{depth[i], rank, i});
        }
        java.util.Collections.sort(keys, (a, b) ->
                a[0] != b[0] ? b[0] - a[0] : a[1] != b[1] ? a[1] - b[1] : a[2] - b[2]);
        int g = 0, prevDepth = Integer.MIN_VALUE, prevRank = -1;
        for (int[] k : keys) {
            if (k[0] != prevDepth || k[1] != prevRank) { g++; prevDepth = k[0]; prevRank = k[1]; }
            parts.get(k[2]).group = g;
        }
        syncStagesFromGroups();
    }

    /** Staging category: engine 0, detacher 1, parachute/lander 2, solar 3;
     *  -1 = never auto-staged (pod, tank, strut, port, rcs, ...). */
    private int categoryRank(int idx) {
        PartType t = PartList.get(parts.get(idx).typeId);
        if (t == null) return -1;
        if (t.engine != null) return 0;
        if ("detacher".equals(t.type)) return 1;
        if ("parachute".equals(t.type) || "lander".equals(t.type)) return 2;
        if (t.solar != null) return 3;
        return -1;
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
     * The root-connected weld tree (component of part 0) is the main ship
     * (Parts + Connections); every OTHER undirected component becomes one
     * <DisconnectedPart> block with its own Parts + Connections — exactly the
     * sample's structure. Editor activation groups are written as the sample's
     * staging: group g becomes Step #g (1-based) of every Pod's Staging
     * element, scoped to the pod's own component; the ship name rides in the
     * first main Pod's name attribute. Attach numbering is 1-based (sample).
     */
    public String toXml(String shipName) {
        int n = parts.size();
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) ids[i] = i == 0 ? "1" : String.valueOf(100000000 + i);

        boolean[] rootMask = componentFromRoot();
        List<Integer> mainIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) if (rootMask[i]) mainIdx.add(i);
        List<List<Integer>> blocks = disconnectedComponents(rootMask);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Ship version=\"1\" liftedOff=\"0\" touchingGround=\"0\">\n");
        if (blocks.isEmpty()) {
            sb.append("<DisconnectedParts/>\n");
        } else {
            sb.append("<DisconnectedParts>\n");
            for (List<Integer> comp : blocks) {
                boolean[] mask = maskOf(comp);
                sb.append("<DisconnectedPart>\n");
                appendPartsBlock(sb, comp, mask, ids, null);
                appendConnectionsBlock(sb, mask, ids);
                sb.append("</DisconnectedPart>\n");
            }
            sb.append("</DisconnectedParts>\n");
        }
        appendPartsBlock(sb, mainIdx, rootMask, ids, shipName);
        appendConnectionsBlock(sb, rootMask, ids);
        sb.append("</Ship>");
        return sb.toString();
    }

    /** Undirected components among parts NOT in the root component. */
    private List<List<Integer>> disconnectedComponents(boolean[] rootMask) {
        List<List<Integer>> out = new ArrayList<>();
        boolean[] seen = new boolean[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            if (rootMask[i] || seen[i]) continue;
            List<Integer> comp = new ArrayList<>();
            List<Integer> queue = new ArrayList<>();
            seen[i] = true;
            queue.add(i);
            while (!queue.isEmpty()) {
                int cur = queue.remove(queue.size() - 1);
                comp.add(cur);
                for (Connection c : connections) {
                    int other = c.partA == cur ? c.partB : c.partB == cur ? c.partA : -1;
                    if (other >= 0 && other < parts.size() && !rootMask[other] && !seen[other]) {
                        seen[other] = true;
                        queue.add(other);
                    }
                }
            }
            java.util.Collections.sort(comp);
            out.add(comp);
        }
        return out;
    }

    private boolean[] maskOf(List<Integer> idxs) {
        boolean[] m = new boolean[parts.size()];
        for (int i : idxs) m[i] = true;
        return m;
    }

    /** One <Parts> block for the given component (shipName only on its first pod). */
    private void appendPartsBlock(StringBuilder sb, List<Integer> idxs, boolean[] mask,
                                  String[] ids, String shipName) {
        sb.append("<Parts>\n");
        boolean named = shipName == null; // disconnected blocks carry no ship name
        for (int i : idxs) {
            DesignPart p = parts.get(i);
            PartType t = PartList.get(p.typeId);
            boolean isTank = t != null && t.tank != null;
            boolean isPod = t != null && "pod".equals(t.type);
            sb.append("<Part partType=\"").append(esc(p.typeId)).append("\" id=\"").append(ids[i])
                    .append("\" x=\"").append(f6(p.x / XML_UNIT)).append("\" y=\"").append(f6(p.y / XML_UNIT))
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
                appendStagingXml(sb, ids, mask);
                sb.append("</Pod>\n</Part>\n");
            } else if (child != null) {
                sb.append(">\n").append(child).append("\n</Part>\n");
            } else {
                sb.append("/>\n");
            }
        }
        sb.append("</Parts>\n");
    }

    /** One <Connections> block (welds whose both ends are inside mask). */
    private void appendConnectionsBlock(StringBuilder sb, boolean[] mask, String[] ids) {
        StringBuilder body = new StringBuilder();
        for (Connection c : connections) {
            if (c.partA < 0 || c.partA >= parts.size() || c.partB < 0 || c.partB >= parts.size())
                continue;
            if (!mask[c.partA] || !mask[c.partB]) continue;
            // XML attach numbering is 1-based (sample: values 1..4)
            body.append("<Connection parentAttachPoint=\"").append(c.attachA + 1)
                    .append("\" childAttachPoint=\"").append(c.attachB + 1)
                    .append("\" parentPart=\"").append(ids[c.partA])
                    .append("\" childPart=\"").append(ids[c.partB]).append("\"/>\n");
        }
        if (body.length() == 0) sb.append("<Connections/>\n");
        else sb.append("<Connections>\n").append(body).append("</Connections>\n");
    }

    /** group g -> Step g (1-based), Activate Id refs scoped to the component. */
    private void appendStagingXml(StringBuilder sb, String[] ids, boolean[] mask) {
        int maxGroup = 0;
        for (DesignPart p : parts) if (p.group > maxGroup) maxGroup = p.group;
        sb.append("<Staging currentStage=\"0\">\n");
        for (int g = 1; g <= maxGroup; g++) {
            StringBuilder step = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (mask[i] && parts.get(i).group == g) {
                    step.append("<Activate Id=\"").append(ids[i]).append("\" moved=\"0\"/>\n");
                }
            }
            if (step.length() == 0) continue; // sample omits empty steps
            sb.append("<Step>\n").append(step).append("</Step>\n");
        }
        sb.append("</Staging>\n");
    }

    /**
     * Parse a Show_Rocket ship XML. Loads the main Parts AND every
     * DisconnectedPart block (they become unwelded blocks in the editor, shown
     * translucent until the player welds them on). Tolerant of missing fields
     * (defaults): editorAngle falls back to round(angle / 90deg);
     * flipped/activated absent -> false/0; activation groups come from the
     * first main Pod's Staging Steps (Step k -> group k+1); part ids form one
     * global namespace across all blocks, so cross-block references resolve.
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
        // ORDER MATTERS: the main Parts block must be parsed FIRST so part 0
        // stays the root (pod) of the weld tree; DisconnectedParts append
        // after. ids form one global namespace across all blocks.
        XmlReader.Element mainParts = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            XmlReader.Element ch = root.getChild(i);
            if ("Parts".equals(ch.getName())) {
                if (mainParts == null) mainParts = ch;
                parsePartsBlock(ch, d, ids);
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            XmlReader.Element ch = root.getChild(i);
            if (!"DisconnectedParts".equals(ch.getName())) continue;
            for (int k = 0; k < ch.getChildCount(); k++) {
                XmlReader.Element dp = ch.getChild(k);
                if (!"DisconnectedPart".equals(dp.getName())) continue;
                for (int c = 0; c < dp.getChildCount(); c++) {
                    XmlReader.Element sub = dp.getChild(c);
                    if ("Parts".equals(sub.getName())) parsePartsBlock(sub, d, ids);
                }
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            XmlReader.Element ch = root.getChild(i);
            if ("Connections".equals(ch.getName())) {
                parseConnectionsBlock(ch, d, ids);
            } else if ("DisconnectedParts".equals(ch.getName())) {
                for (int k = 0; k < ch.getChildCount(); k++) {
                    XmlReader.Element dp = ch.getChild(k);
                    if (!"DisconnectedPart".equals(dp.getName())) continue;
                    for (int c = 0; c < dp.getChildCount(); c++) {
                        XmlReader.Element sub = dp.getChild(c);
                        if ("Connections".equals(sub.getName())) parseConnectionsBlock(sub, d, ids);
                    }
                }
            }
        }
        // staging: first main-ship Pod that carries a Staging element wins
        boolean hadStaging = false;
        if (mainParts != null) {
            for (int i = 0; i < mainParts.getChildCount(); i++) {
                XmlReader.Element pe = mainParts.getChild(i);
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
                hadStaging = true;
                int stepIdx = 0;
                for (int s = 0; s < staging.getChildCount(); s++) {
                    XmlReader.Element step = staging.getChild(s);
                    if (!"Step".equals(step.getName())) continue;
                    stepIdx++;
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
        // a file with explicit staging keeps its groups; otherwise derive them
        if (hadStaging) d.syncStagesFromGroups();
        else d.autoStage();
        return d;
    }

    /** Append one <Parts> block's parts to the design (global id namespace). */
    private static void parsePartsBlock(XmlReader.Element partsEl, ShipDesign d, List<String> ids) {
        for (int i = 0; i < partsEl.getChildCount(); i++) {
            XmlReader.Element pe = partsEl.getChild(i);
            if (!"Part".equals(pe.getName())) continue;
            DesignPart dp = new DesignPart();
            dp.typeId = pe.getAttribute("partType", "pod-1");
            dp.x = pe.getFloatAttribute("x", 0f) * XML_UNIT;
            dp.y = pe.getFloatAttribute("y", 0f) * XML_UNIT;
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
            ids.add(pe.getAttribute("id", String.valueOf(ids.size())));
            d.parts.add(dp);
        }
    }

    /** Append one <Connections> block (id refs; attach numbering is 1-based). */
    private static void parseConnectionsBlock(XmlReader.Element connEl, ShipDesign d, List<String> ids) {
        for (int c = 0; c < connEl.getChildCount(); c++) {
            XmlReader.Element ce = connEl.getChild(c);
            if (!"Connection".equals(ce.getName())) continue;
            int pa = ids.indexOf(ce.getAttribute("parentPart", ""));
            int pb = ids.indexOf(ce.getAttribute("childPart", ""));
            if (pa < 0 || pb < 0) continue;
            d.connections.add(new Connection(pa, pb,
                    ce.getIntAttribute("parentAttachPoint", 1) - 1,
                    ce.getIntAttribute("childAttachPoint", 1) - 1));
        }
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
