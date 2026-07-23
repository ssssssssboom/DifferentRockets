package com.differentrockets.util;

/** Mutable double-precision 2D vector used for universe coordinates. */
public class Vec2d {
    public double x, y;

    public Vec2d() { this(0, 0); }
    public Vec2d(double x, double y) { this.x = x; this.y = y; }
    public Vec2d(Vec2d o) { this(o.x, o.y); }

    public Vec2d set(double x, double y) { this.x = x; this.y = y; return this; }
    public Vec2d set(Vec2d o) { return set(o.x, o.y); }
    public Vec2d add(double x, double y) { this.x += x; this.y += y; return this; }
    public Vec2d add(Vec2d o) { return add(o.x, o.y); }
    public Vec2d sub(Vec2d o) { this.x -= o.x; this.y -= o.y; return this; }
    public Vec2d mul(double s) { this.x *= s; this.y *= s; return this; }

    public double len() { return Math.sqrt(x * x + y * y); }
    public double len2() { return x * x + y * y; }
    public double dist(Vec2d o) { double dx = x - o.x, dy = y - o.y; return Math.sqrt(dx * dx + dy * dy); }
    public double dist2(Vec2d o) { double dx = x - o.x, dy = y - o.y; return dx * dx + dy * dy; }

    public Vec2d nor() { double l = len(); if (l > 1e-12) { x /= l; y /= l; } return this; }

    public double angleRad() { return Math.atan2(y, x); }

    public static Vec2d fromAngle(double rad, double len) {
        return new Vec2d(Math.cos(rad) * len, Math.sin(rad) * len);
    }
}
