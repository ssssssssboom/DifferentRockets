package com.differentrockets.game;

import com.badlogic.gdx.math.Vector2;

/**
 * Attach-point geometry shared by the editor snap and Ship welding (round 11).
 * Edge-type attach points (LeftSide/RightSide/Top/Bottom — see PartType) mean
 * the WHOLE side segment is attachable: a mating part may contact anywhere
 * along it. Center locations stay classic single points. Contact resolution:
 * point↔point = the points themselves; point↔edge = the point and its
 * projection on the segment; edge↔edge = the closest pair between segments.
 */
public final class Attach {

    private Attach() {}

    /**
     * Slide quantization step (m) for edge-type attach snapping in the editor:
     * a part sliding along an edge locks to multiples of this step (0.5 m).
     * Center-type (point) contacts are never quantized.
     */
    public static final float EDGE_SNAP_STEP = 0.5f;

    /**
     * Quantize the free slide component of a snapped position (px,py) along the
     * edge segment (a,b) to the nearest multiple of EDGE_SNAP_STEP; the
     * perpendicular (contact) component is unchanged. Degenerate segments
     * (center-type points) leave the position untouched. Writes into out and
     * returns it; out may alias a or b.
     */
    public static Vector2 quantizeAlongSegment(float px, float py, Vector2 a, Vector2 b, Vector2 out) {
        float abx = b.x - a.x, aby = b.y - a.y;
        float len2 = abx * abx + aby * aby;
        if (len2 < 1e-9f) return out.set(px, py);
        float inv = 1f / (float) Math.sqrt(len2);
        float ux = abx * inv, uy = aby * inv;
        float s = px * ux + py * uy;
        float q = (float) (Math.round(s / (double) EDGE_SNAP_STEP) * EDGE_SNAP_STEP);
        return out.set(px + ux * (q - s), py + uy * (q - s));
    }

    /** Local-space segment endpoints of an attach point (degenerate for points). */
    public static void localSegment(PartType t, PartType.AttachPoint ap, Vector2 outA, Vector2 outB) {
        float hw = t.width / 2f, hh = t.height / 2f;
        switch (ap.edge) {
            case PartType.AttachPoint.EDGE_LEFT:   outA.set(-hw, -hh); outB.set(-hw, hh); break;
            case PartType.AttachPoint.EDGE_RIGHT:  outA.set(hw, -hh);  outB.set(hw, hh); break;
            case PartType.AttachPoint.EDGE_TOP:    outA.set(-hw, hh);  outB.set(hw, hh); break;
            case PartType.AttachPoint.EDGE_BOTTOM: outA.set(-hw, -hh); outB.set(hw, -hh); break;
            default: outA.set(ap.x, ap.y); outB.set(ap.x, ap.y); return;
        }
        // edge attach zones are shrunk inward by EDGE_SHRINK at both ends
        // (round 28): corner-adjacent contacts no longer count as edge mates,
        // so e.g. a strut pair only welds through the intended face pair.
        shrink(outA, outB, EDGE_SHRINK);
    }

    /** Inward shrink (length units) applied at each end of an edge attach segment. */
    public static final float EDGE_SHRINK = 0.25f;

    private static void shrink(Vector2 a, Vector2 b, float amount) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= amount * 2f) { // segment shorter than the shrink: collapse to midpoint
            float mx = (a.x + b.x) / 2f, my = (a.y + b.y) / 2f;
            a.set(mx, my); b.set(mx, my);
            return;
        }
        float ux = dx / len, uy = dy / len;
        a.x += ux * amount; a.y += uy * amount;
        b.x -= ux * amount; b.y -= uy * amount;
    }

    /** Closest point on segment (a,b) to point p; writes into out and returns it. */
    public static Vector2 closestOnSegment(Vector2 p, Vector2 a, Vector2 b, Vector2 out) {
        float abx = b.x - a.x, aby = b.y - a.y;
        float len2 = abx * abx + aby * aby;
        if (len2 < 1e-9f) return out.set(a);
        float t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        return out.set(a.x + abx * t, a.y + aby * t);
    }

    private static final Vector2 tA = new Vector2(), tB = new Vector2();
    private static final Vector2 c2 = new Vector2();

    /**
     * Closest pair between segments (a1,a2) and (b1,b2); writes into outA/outB,
     * returns the distance. Handles the degenerate point-segment cases.
     */
    public static float closestBetweenSegments(Vector2 a1, Vector2 a2, Vector2 b1, Vector2 b2,
                                               Vector2 outA, Vector2 outB) {
        float best = Float.MAX_VALUE;
        Vector2 bestA = tA, bestB = tB;
        float d = closestOnSegment(a1, b1, b2, c2).dst(a1);
        if (d < best) { best = d; bestA.set(a1); bestB.set(c2); }
        d = closestOnSegment(a2, b1, b2, c2).dst(a2);
        if (d < best) { best = d; bestA.set(a2); bestB.set(c2); }
        d = closestOnSegment(b1, a1, a2, c2).dst(b1);
        if (d < best) { best = d; bestA.set(c2); bestB.set(b1); }
        d = closestOnSegment(b2, a1, a2, c2).dst(b2);
        if (d < best) { best = d; bestA.set(c2); bestB.set(b2); }
        // proper crossing: distance is 0 — anchor at the mean of the four ends
        if (segmentsCross(a1, a2, b1, b2)) {
            best = 0f;
            bestA.set((a1.x + a2.x + b1.x + b2.x) / 4f, (a1.y + a2.y + b1.y + b2.y) / 4f);
            bestB.set(bestA);
        }
        outA.set(bestA);
        outB.set(bestB);
        return best;
    }

    private static float cross(float ox, float oy, float ax, float ay, float bx, float by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    private static boolean segmentsCross(Vector2 a1, Vector2 a2, Vector2 b1, Vector2 b2) {
        float d1 = cross(b1.x, b1.y, b2.x, b2.y, a1.x, a1.y);
        float d2 = cross(b1.x, b1.y, b2.x, b2.y, a2.x, a2.y);
        float d3 = cross(a1.x, a1.y, a2.x, a2.y, b1.x, b1.y);
        float d4 = cross(a1.x, a1.y, a2.x, a2.y, b2.x, b2.y);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }
}
