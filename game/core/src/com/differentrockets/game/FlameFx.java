package com.differentrockets.game;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * Tiny pooled particle system for engine exhaust (Item 2). World-space,
 * additive blending, hard cap; textures are generated procedurally at
 * startup so no new asset files are needed. Driven from Lua via flame.emit.
 */
public final class FlameFx {
  public static final int MAX = 600;
  public static final int TEX_GLOW = 0, TEX_SMOKE = 1, TEX_SPARK = 2;

  private static TextureRegion[] tex;

  // structure-of-arrays ring buffer
  private static final float[] x = new float[MAX], y = new float[MAX];
  private static final float[] vx = new float[MAX], vy = new float[MAX];
  private static final float[] drag = new float[MAX];
  private static final float[] life = new float[MAX], age = new float[MAX];
  private static final float[] size0 = new float[MAX], size1 = new float[MAX];
  private static final float[] cr = new float[MAX], cg = new float[MAX], cb = new float[MAX];
  private static final float[] a0 = new float[MAX], a1 = new float[MAX];
  private static final int[] texId = new int[MAX];
  private static int head = 0;      // next slot to (over)write
  private static int count = 0;     // alive particles
  private static int maxEver = 0;   // high-water mark (for tests)

  private FlameFx() {}

  public static synchronized void ensureTextures() {
    if (tex != null) return;
    tex = new TextureRegion[] {
        new TextureRegion(makeGlow()),
        new TextureRegion(makeSmoke()),
        new TextureRegion(makeSpark())};
  }

  public static int texIdForName(String name) {
    if ("smoke".equals(name)) return TEX_SMOKE;
    if ("spark".equals(name)) return TEX_SPARK;
    return TEX_GLOW;
  }

  public static TextureRegion tex(int id) {
    ensureTextures();
    if (id < 0 || id >= tex.length) id = TEX_GLOW;
    return tex[id];
  }

  public static synchronized int emit(int id, float px, float py, float pvx, float pvy,
      float pdrag, float plife, float s0, float s1,
      float r, float g, float b, float aa0, float aa1) {
    if (plife <= 0.001f) return count;
    ensureTextures();
    if (life[head] <= 0f) count++;        // reusing a dead slot
    int i = head;
    head = (head + 1) % MAX;
    x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy;
    drag[i] = pdrag; life[i] = plife; age[i] = 0f;
    size0[i] = s0; size1[i] = s1;
    cr[i] = r; cg[i] = g; cb[i] = b; a0[i] = aa0; a1[i] = aa1;
    texId[i] = id;
    if (count > maxEver) maxEver = count;
    return count;
  }

  /** Advance particles by simulated seconds (already includes warp). */
  public static synchronized void update(float dt) {
    if (dt <= 0f) return;
    for (int i = 0; i < MAX; i++) {
      if (life[i] <= 0f) continue;
      age[i] += dt;
      if (age[i] >= life[i]) { life[i] = 0f; count--; continue; }
      x[i] += vx[i] * dt;
      y[i] += vy[i] * dt;
      if (drag[i] > 0f) {
        float k = (float) Math.exp(-drag[i] * dt);
        vx[i] *= k; vy[i] *= k;
      }
    }
  }

  /** Render additively. Caller must NOT have batch begun; camera already set. */
  public static synchronized void render(SpriteBatch batch) {
    if (tex == null || count <= 0) return;
    int oldSrc = batch.getBlendSrcFunc();
    int oldDst = batch.getBlendDstFunc();
    batch.begin();
    batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
    for (int i = 0; i < MAX; i++) {
      if (life[i] <= 0f) continue;
      float t = age[i] / life[i];
      float size = size0[i] + (size1[i] - size0[i]) * t;
      float alpha = a0[i] + (a1[i] - a0[i]) * t;
      if (size <= 0.01f || alpha <= 0.003f) continue;
      batch.setColor(cr[i], cg[i], cb[i], Math.min(1f, alpha));
      batch.draw(tex[texId[i]], x[i] - size * 0.5f, y[i] - size * 0.5f, size, size);
    }
    batch.setColor(1f, 1f, 1f, 1f);
    batch.setBlendFunction(oldSrc, oldDst);
    batch.end();
  }

  public static synchronized int activeCount() { return count; }
  public static synchronized int maxActiveEver() { return maxEver; }

  public static synchronized void reset() {
    for (int i = 0; i < MAX; i++) life[i] = 0f;
    count = 0; head = 0;
  }

  /** Test hook: reset high-water mark between scenarios. */
  public static synchronized void resetMaxEver() { maxEver = 0; }

  // ---- procedural textures ----

  private static Texture makeGlow() {
    final int S = 64;
    Pixmap pm = new Pixmap(S, S, Pixmap.Format.RGBA8888);
    float c = (S - 1) * 0.5f;
    for (int py = 0; py < S; py++) {
      for (int px = 0; px < S; px++) {
        float d = (float) Math.sqrt((px - c) * (px - c) + (py - c) * (py - c)) / c;
        float a = clamp01(1f - d);
        a = a * a;                              // soft radial falloff
        pm.setColor(1f, 1f, 1f, a);
        pm.drawPixel(px, py);
      }
    }
    Texture t = new Texture(pm);
    pm.dispose();
    t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    return t;
  }

  private static Texture makeSpark() {
    final int S = 32;
    Pixmap pm = new Pixmap(S, S, Pixmap.Format.RGBA8888);
    float c = (S - 1) * 0.5f;
    for (int py = 0; py < S; py++) {
      for (int px = 0; px < S; px++) {
        float d = (float) Math.sqrt((px - c) * (px - c) + (py - c) * (py - c)) / c;
        float a = d < 0.22f ? 1f : clamp01(1f - (d - 0.22f) / 0.35f);
        pm.setColor(1f, 1f, 1f, a);
        pm.drawPixel(px, py);
      }
    }
    Texture t = new Texture(pm);
    pm.dispose();
    t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    return t;
  }

  private static Texture makeSmoke() {
    final int S = 64;
    Pixmap pm = new Pixmap(S, S, Pixmap.Format.RGBA8888);
    float c = (S - 1) * 0.5f;
    for (int py = 0; py < S; py++) {
      for (int px = 0; px < S; px++) {
        float d = (float) Math.sqrt((px - c) * (px - c) + (py - c) * (py - c)) / c;
        float mask = clamp01(1f - d);
        // two octaves of value noise for a blotchy puff
        float n = 0.65f * vnoise(px * 0.14f, py * 0.14f)
                + 0.35f * vnoise(px * 0.30f + 40f, py * 0.30f + 40f);
        float a = clamp01(n * 1.25f - 0.12f) * mask * mask;
        pm.setColor(1f, 1f, 1f, a);
        pm.drawPixel(px, py);
      }
    }
    Texture t = new Texture(pm);
    pm.dispose();
    t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    return t;
  }

  private static float vnoise(float fx, float fy) {
    int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
    float tx = fx - ix, ty = fy - iy;
    tx = tx * tx * (3f - 2f * tx);
    ty = ty * ty * (3f - 2f * ty);
    float v00 = hash01(ix, iy), v10 = hash01(ix + 1, iy);
    float v01 = hash01(ix, iy + 1), v11 = hash01(ix + 1, iy + 1);
    return v00 + (v10 - v00) * tx + (v01 - v00) * ty + (v00 - v10 - v01 + v11) * tx * ty;
  }

  private static float hash01(int ix, int iy) {
    int h = ix * 374761393 + iy * 668265263;
    h = (h ^ (h >> 13)) * 1274126177;
    h = h ^ (h >> 16);
    return (h & 0x7fffffff) / 2147483647f;
  }

  private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
