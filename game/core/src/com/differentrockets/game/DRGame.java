package com.differentrockets.game;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.differentrockets.ui.MenuScreen;
import com.differentrockets.ui.Ui;
import com.differentrockets.util.AtlasPack;
import com.differentrockets.util.Res;
public class DRGame extends Game {

    /** Global UI font scale (owner requirement: bigger buttons/touch targets). */
    public static final float FONT_SCALE = 1.75f;
    /**
     * Sandbox-only upscale (owner task: sandbox buttons at least 2x larger).
     * Deliberately NOT folded into FONT_SCALE — Ui.UI_SCALE already upscales
     * every screen globally, so menus/editor would double up. SandboxScreen
     * multiplies its own button boxes and per-label font scales by this.
     */
    public static final float SANDBOX_SCALE = 2.0f;

    public SpriteBatch batch;
    public ShapeRenderer shapes;
    public BitmapFont font;
    public BitmapFont bigFont;
    public Ui ui;

    public AtlasPack shipSprites;
    public AtlasPack planetSprites;
    public AtlasPack runtimeSprites;

    public GameWorld world;

    /**
     * Build version string (yyyymmddhhmm), generated into assets/version.txt
     * by android/build.gradle at build time. Read chain: player-external copy
     * → APK/internal asset → "dev" (desktop runs without the generated file).
     */
    public String version = "dev";

    @Override
    public void create() {
        Res.init(); // player-editable resource root (copies defaults on first run)
        // build version: assets/version.txt (Android build stamps it; absent on desktop)
        try {
            com.badlogic.gdx.files.FileHandle vf = Res.asset("version.txt");
            if (vf.exists()) {
                String v = vf.readString("UTF-8").trim();
                if (!v.isEmpty()) version = v;
            }
        } catch (Throwable ignored) { /* stay on "dev" */ }

        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        font = new BitmapFont();
        font.getData().setScale(FONT_SCALE);
        bigFont = new BitmapFont();
        bigFont.getData().setScale(2.5f);
        ui = new Ui(font);

        PartList.load();
        ModManager.init();

        shipSprites = new AtlasPack(Res.asset("ShipSprites.xml"));
        planetSprites = new AtlasPack(Res.asset("PlanetSprites.xml"));
        runtimeSprites = new AtlasPack(Res.asset("Runtime.xml"));

        world = new GameWorld();

        setScreen(new MenuScreen(this));
    }

    /**
     * Re-read all resources from the (newly granted) external root without an
     * app restart: atlases, part defs, Lua mods, and the world itself (the
     * running session is saved first and restored afterwards).
     */
    public void reloadResources() {
        Gdx.app.log("res", "switching resource loading to the external root (no restart needed)");
        world.save();
        PartList.load();
        ModManager.reset();
        FlameScript.invalidate();
        TerrainScript.invalidate();
        PhysicsScript.invalidate();
        JointScript.invalidate();
        shipSprites.dispose();
        planetSprites.dispose();
        runtimeSprites.dispose();
        shipSprites = new AtlasPack(Res.asset("ShipSprites.xml"));
        planetSprites = new AtlasPack(Res.asset("PlanetSprites.xml"));
        runtimeSprites = new AtlasPack(Res.asset("Runtime.xml"));
        world.dispose();
        world = new GameWorld();
        world.load();
    }

    // steering ring convenience delegates (see GameWorld)
    public void setTargetHeading(double rad) { world.setTargetHeading(rad); }
    public double getTargetHeading() { return world.getTargetHeading(); }

    @Override
    public void dispose() {
        if (world != null) world.save();
        if (world != null) world.dispose();
        batch.dispose();
        shapes.dispose();
        font.dispose();
        bigFont.dispose();
        ui.dispose();
        shipSprites.dispose();
        planetSprites.dispose();
        if (runtimeSprites != null) runtimeSprites.dispose();
        super.dispose();
    }
}
