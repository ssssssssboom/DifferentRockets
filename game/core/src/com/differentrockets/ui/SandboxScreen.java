package com.differentrockets.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.differentrockets.game.Attach;
import com.differentrockets.game.DRGame;
import com.differentrockets.game.FlameFx;
import com.differentrockets.game.FlameScript;
import com.differentrockets.game.OrbitPredictor;
import com.differentrockets.game.Part;
import com.differentrockets.game.Planet;
import com.differentrockets.game.Ship;
import com.differentrockets.game.SteeringIO;
import com.differentrockets.util.Vec2d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Flight view: renders the sandbox (planets, terrain, ships, flames), handles
 * flight input (keyboard + on-screen controls), map view, time warp.
 */
public class SandboxScreen extends ScreenAdapter {

    private final DRGame game;
    private Stage stage;
    private final OrthographicCamera cam;
    private final OrthographicCamera mapCam;
    private boolean mapMode;
    /**
     * Map orbit-frame anchor (round 17): index into game.world.planets of the
     * body the predicted path AND the map camera are anchored to; -1 = Auto
     * (the ship's dominant body, round-15 default). Selected from a gravity-
     * sorted list popped up by the FRAME button.
     */
    private int anchorIndex = -1;
    private TextButton frameBtn;
    private Table frameList;
    // menu drawer (task D1): the top-left MENU button slides out a drawer
    // holding the SWITCH SHIP list (round-20 logic, distance-sorted) and the
    // MAIN MENU entry; SHIP/Menu no longer live in the top-right bar
    private Table menuDrawer;
    // map target marker (task D4): tapping another ship or a planet in map
    // view selects it as the relative target — the map draws a "C" circle at
    // the closest point of the predicted orbit to it (purple "X" under 500 m),
    // and the steering ring shows the velocity relative to it (task D6)
    private Ship mapTargetShip;
    private Planet mapTargetPlanet;
    // back-key confirmation (task D2): modal ask-before-leaving dialog
    private Table backDialog;
    /** Camera-follow state: the map center rides the selected anchor body. */
    private Planet lastAnchorBody;
    private double lastAnchorX, lastAnchorY;
    private boolean mapInit;
    private int mapPanPointer = -1;
    private float mapPanLastX, mapPanLastY;
    private float mapPanDist;
    private boolean mapPanned;
    // map camera center in DOUBLE precision (round 13): mapCam.position is a
    // float copy derived from these every frame — at universe coords ~1e10 m
    // float32 has ~1 km resolution, which jittered/segmented the prediction
    // polyline; pan/zoom/tap update the doubles, the float cam follows
    private double mapCX, mapCY;

    // multitouch camera control (item 2): two-finger pan + pinch zoom
    private final Vector2 camPan = new Vector2(); // flight camera pan offset (world meters)
    private int touchA = -1, touchB = -1;         // active pointer ids (-1 = free)
    private float paX, paY, pbX, pbY;             // last screen positions

    // steering ring (item 4)
    private boolean ringDrag;
    private float ringX, ringY, ringR;
    private final com.badlogic.gdx.math.Matrix4 ringMat = new com.badlogic.gdx.math.Matrix4();
    private int slewDir;                          // -1/0/+1 while turn buttons/keys held

    // tap-to-activate (item 6b)
    private Part selectedPart;
    private boolean tapCandidate;
    private boolean tapMoved;
    private float tapDist;
    private double ringDelta; // grab offset between touch angle and target heading (item 11)

    // drag resultant overlay (item 6): DRAG HUD toggle; on by default so the
    // smoke flight screenshots show the CoP arrow
    private boolean dragOverlay = true;

    // orbit prediction in map view (item 10): cached, re-propagated at ~2 Hz
    private final OrbitPredictor predictor = new OrbitPredictor();
    /** Task 5: second propagator for the tapped TARGET ship's gray orbit line. */
    private final OrbitPredictor targetPredictor = new OrbitPredictor();
    /** Map orbit re-propagation interval (round 18: 15 Hz, was 4 Hz). */
    private static final float ORBIT_INTERVAL = 1f / 15f;
    private float orbitTimer = Float.MAX_VALUE;

    private Label telemetry;
    private Label stageLabel;
    private SegmentedThrottle throttle;
    private Texture starTex;
    private final List<float[]> stars = new ArrayList<>();
    private Texture atmoTex;

    private final Vector3 tmp3 = new Vector3();
    private final Vector2 tmp2 = new Vector2();
    private float lastSimDt; // simulated seconds in the current frame (0 when paused)

    public SandboxScreen(DRGame game) {
        this.game = game;
        cam = new OrthographicCamera();
        cam.viewportHeight = 45;
        mapCam = new OrthographicCamera();
        // starfield
        Random rnd = new Random(42);
        Pixmap sp = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        sp.setColor(Color.WHITE);
        sp.fill();
        starTex = new Texture(sp);
        sp.dispose();
        for (int i = 0; i < 300; i++) {
            stars.add(new float[]{rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat() * 0.6f});
        }
        // soft circle texture for atmosphere
        int sz = 256;
        Pixmap ap = new Pixmap(sz, sz, Pixmap.Format.RGBA8888);
        for (int y = 0; y < sz; y++) {
            for (int x = 0; x < sz; x++) {
                float dx = (x - sz / 2f) / (sz / 2f);
                float dy = (y - sz / 2f) / (sz / 2f);
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float a = 0;
                if (d < 1f) {
                    // ring: strongest near r=0.85 fading in and out
                    float ring = 1f - Math.abs(d - 0.82f) / 0.18f;
                    a = Math.max(0, ring);
                    if (d < 0.82f) a = Math.max(0.05f, a);
                }
                ap.setColor(1f, 1f, 1f, a);
                ap.drawPixel(x, y);
            }
        }
        atmoTex = new Texture(ap);
        ap.dispose();
    }

    @Override
    public void show() {
        // sandbox-entry reset (user request): warp 1x (+ stale super-warp
        // trajectory dropped), throttle 0, steering ring inactive — on EVERY
        // entry path (launch, continue/load); in-sandbox ship switches don't
        // come through here and keep the current state
        game.world.resetEntryState();
        slewDir = 0;
        ringDrag = false;
        stage = new Stage(new ScreenViewport());
        buildHud();
        InputMultiplexer mux = new InputMultiplexer();
        mux.addProcessor(stage);
        mux.addProcessor(new GameInput());
        Gdx.input.setInputProcessor(mux);
        // fix (back-key interception): Android does NOT deliver the BACK key to
        // the game unless it is explicitly caught — without this the keyDown
        // below never fires and the app just backgrounds to the launcher
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    @Override
    public void hide() {
        // release the BACK key for the screens we switch to
        Gdx.input.setCatchKey(Input.Keys.BACK, false);
    }

    // ---------------------------------------------------------------- HUD

    // sandbox-only upscale (owner task: sandbox buttons at least 2x). The skin
    // font is shared with the menu/editor (Ui.UI_SCALE), so we never touch the
    // font itself — button boxes scale by DRGame.SANDBOX_SCALE and each
    // button's label gets its own fontScale multiplier on top.
    private static final float BS = DRGame.SANDBOX_SCALE;

    /** Apply the sandbox font multiplier to a button's label (2x by default). */
    private static TextButton big(TextButton b) { return big(b, BS); }
    private static TextButton big(TextButton b, float fontScale) {
        b.getLabel().setFontScale(fontScale);
        return b;
    }

    private void buildHud() {
        Table root = new Table();
        root.setFillParent(true);
        stage.addActor(root);

        telemetry = new Label("", game.ui.skin);
        // sandbox upscale: telemetry 1.3x on top of the shared UI font
        telemetry.setFontScale(1.3f);

        stageLabel = new Label("", game.ui.skin);
        stageLabel.setColor(new Color(1f, 0.85f, 0.3f, 1f));
        stageLabel.setFontScale(1.3f);

        TextButton mapBtn = big(new TextButton("MAP", game.ui.skin));
        mapBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { toggleMap(); }
        });
        // round 17: map orbit-frame anchor — opens a gravity-sorted body
        // list (see toggleFrameList). State is shown in the TEXT (like
        // DRAG:on/off); a plain ClickListener keeps the return-to-gray
        // behavior (no checked state).
        frameBtn = big(new TextButton("FRAME:Auto", game.ui.skin), 1.4f);
        frameBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { toggleFrameList(); }
        });
        // item 6: toggle for the aerodynamic drag resultant overlay.
        // Round 14: buttons must return to gray on touch-up — the on/off
        // state is shown in the TEXT, not a stuck green tint.
        final TextButton dragBtn = big(new TextButton("DRAG:on", game.ui.skin));
        dragBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                dragOverlay = !dragOverlay;
                dragBtn.setText(dragOverlay ? "DRAG:on" : "DRAG:off");
            }
        });
        TextButton pauseBtn = big(new TextButton("II", game.ui.skin));
        pauseBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.world.paused = !game.world.paused;
            }
        });
        // task D1: the old Menu/SHIP buttons moved into the top-left MENU
        // drawer (toggleMenuDrawer); MAP moved to the left column below MENU
        // round 14 item 7: warp ladder — "-" / "+" step through WARP_LEVELS
        // (1x 2x 4x physical, then 25x..250000x on rails), label shows current.
        TextButton warpDown = big(new TextButton("-", game.ui.skin));
        warpDown.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { warpStep(-1); }
        });
        warpLabel = new Label("1x", game.ui.skin);
        warpLabel.setFontScale(BS);
        warpLabel.setAlignment(com.badlogic.gdx.utils.Align.center);
        TextButton warpUp = big(new TextButton("+", game.ui.skin));
        warpUp.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { warpStep(1); }
        });

        // sandbox 2x layout: the top-right cluster is split into TWO rows so
        // the doubled boxes still fit a portrait phone width
        Table topRight = new Table();
        topRight.add(warpDown).width(120).height(128).pad(2);
        topRight.add(warpLabel).width(220).height(128).pad(2);
        topRight.add(warpUp).width(120).height(128).pad(2);
        topRight.add(pauseBtn).width(120).height(128).pad(2).row();
        topRight.add(dragBtn).width(220).height(128).pad(2);
        topRight.add(frameBtn).width(340).height(128).pad(2);

        // task D1: top-left column — MENU on top, MAP directly below it.
        // MENU slides out the drawer (switch-ship list + MAIN MENU entry);
        // a plain ClickListener keeps the return-to-gray behavior.
        TextButton menuBtn = big(new TextButton("MENU", game.ui.skin));
        menuBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { toggleMenuDrawer(); }
        });
        Table leftTop = new Table();
        leftTop.add(menuBtn).width(240).height(128).pad(2).row();
        leftTop.add(mapBtn).width(240).height(128).pad(2);

        // throttle: 10-segment bar from the Runtime atlas, right edge
        // (round 11 item 10 — ThrottleControl track + ThrottleLevel sprites);
        // sandbox 2x: taller/wider bar for a fat-finger scrub target
        throttle = new SegmentedThrottle();

        TextButton stageBtn = new TextButton("STAGE\n(Space)", game.ui.skin);
        stageBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { doStage(); }
        });

        TextButton activateBtn = new TextButton("ACTIVATE", game.ui.skin);
        activateBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { activateSelected(); }
        });

        // heading slew: "<" rotates the nose left (target heading increases)
        // (user feedback: the 7 bottom buttons stay at the ORIGINAL 1x size —
        //  only the top bars/throttle/ring keep the sandbox 2x upscale)
        TextButton leftBtn = holdBtn("<", 1);
        TextButton rightBtn = holdBtn(">", -1);

        TextButton zoomIn = new TextButton("+", game.ui.skin);
        zoomIn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { zoom(0.8f); }
        });
        TextButton zoomOut = new TextButton("-", game.ui.skin);
        zoomOut.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { zoom(1.25f); }
        });
        // round 17: one-tap recenter — clears the drag offset so the flight
        // camera snaps back onto the ship (lost-the-ship rescue)
        TextButton centerBtn = new TextButton("CENTER", game.ui.skin);
        centerBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { camPan.setZero(); }
        });

        // portrait HUD: telemetry row / top bar (left MENU+MAP column, right
        // two-row warp/pause/drag/frame cluster at 2x) / right-edge throttle /
        // TWO bottom rows (the 7 bottom buttons are back to the original 1x)
        root.top().left();
        root.add(telemetry).pad(10).left().expandX().row();

        Table topBar = new Table();
        topBar.add(leftTop).left().padLeft(6).top();
        topBar.add().expandX();
        topBar.add(topRight).right();
        root.add(topBar).fillX().padRight(6).row();

        Table mid = new Table();
        mid.add().expandX();
        mid.add(throttle).height(640).width(150).right().padRight(10);
        root.add(mid).expandY().fillX().row();

        Table bottomA = new Table();
        bottomA.add(stageLabel).expandX().center().padLeft(8);
        bottomA.add(zoomIn).width(96).height(96).pad(4);
        bottomA.add(zoomOut).width(96).height(96).pad(4);
        bottomA.add(centerBtn).width(130).height(96).pad(4);
        root.add(bottomA).fillX().row();

        // bottom row (user request): ACTIVATE/STAGE pinned LEFT, turn buttons
        // < > CENTERED, BACK pinned to the right edge (same size as STAGE,
        // same behavior as the phone BACK key — toggleBackDialog, task D2)
        Table bottomB = new Table();
        bottomB.add(activateBtn).width(170).height(110).pad(6).padLeft(16);
        bottomB.add(stageBtn).width(200).height(110).pad(6);
        bottomB.add().expandX();
        bottomB.add(leftBtn).width(120).height(96).pad(4);
        bottomB.add(rightBtn).width(120).height(96).pad(4);
        bottomB.add().expandX();
        TextButton backBtn = new TextButton("BACK", game.ui.skin);
        backBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { toggleBackDialog(); }
        });
        bottomB.add(backBtn).width(200).height(110).pad(6).padRight(16);
        root.add(bottomB).fillX().padBottom(8);
    }

    private Label warpLabel;

    /** Step the time-warp ladder (round 14 item 7). */
    private void warpStep(int dir) {
        int[] levels = game.world.WARP_LEVELS;
        int idx = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] <= game.world.warp) idx = i;
        }
        idx = Math.max(0, Math.min(levels.length - 1, idx + dir));
        game.world.warp = levels[idx];
        refreshWarpLabel();
    }

    private void refreshWarpLabel() {
        if (warpLabel != null) warpLabel.setText(game.world.warp + "x");
    }

    private TextButton warpBtn(String label, int w) {
        TextButton b = new TextButton(label, game.ui.skin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.world.warp = w;
            }
        });
        return b;
    }

    private TextButton holdBtn(String label, final int dir) {
        // turn buttons (semantics revision): while HELD the steering ring is
        // deactivated (target heading UNCHANGED — SteeringIO.targetHeadingRad
        // is never touched here) and buttonTurn carries the direction at full
        // deflection: "<" (dir=+1, nose left) = -1, ">" (dir=-1) = +1.
        // control.lua reads buttonTurn and swings every gimbaled engine to
        // its own max angle (and the wheel script uses the same signal).
        // On release buttonTurn returns to 0 and the ring's previous
        // activation state is restored.
        TextButton b = new TextButton(label, game.ui.skin);
        b.addListener(new ClickListener() {
            boolean prevRingActive;
            @Override public boolean touchDown(InputEvent e, float x, float y, int pointer, int button) {
                prevRingActive = SteeringIO.ringActive;
                game.world.deactivateRing(); // task 1: ring off + target dropped
                SteeringIO.buttonTurn = dir > 0 ? -1 : 1; // contract: -1 left, +1 right (held)
                return true;
            }
            @Override public void touchUp(InputEvent e, float x, float y, int pointer, int button) {
                SteeringIO.buttonTurn = 0; // released: no deflection
                SteeringIO.ringActive = prevRingActive;
            }
        });
        return b;
    }

    /**
     * SteeringIO contract (item 2-UI): the turn buttons write buttonTurn while
     * held. slewDir = +1 slews the nose LEFT (heading is CCW from "up"), so
     * the contract sign is flipped: buttonTurn = -1 left, +1 right.
     */
    private void syncButtonTurn() {
        SteeringIO.buttonTurn = slewDir > 0 ? -1 : (slewDir < 0 ? 1 : 0);
    }

    /** Single write path for the steering target: world controller + SteeringIO. */
    private void setSteerTarget(double rad) {
        game.world.setTargetHeading(rad);
        SteeringIO.targetHeadingRad = rad;
    }

    private void doStage() {
        if (game.world.active != null) {
            int s = game.world.active.activateStage();
            stageLabel.setText(s >= 0 ? "Stage " + (s + 1) + " fired!" : "No stages left");
        }
    }

    public void zoom(float f) {
        if (!Float.isFinite(f) || f <= 0) return;
        if (mapMode) {
            // item 8: zoom out far enough to frame the whole solar system
            // (Smeptune a ~= 4.5e11 m; Smalley's Comet apoapsis ~5.2e11 m)
            mapCam.viewportHeight = clamp(mapCam.viewportHeight * f, 1000, 1.2e12f);
            if (!Float.isFinite(mapCam.viewportHeight)) mapCam.viewportHeight = 200000;
        } else {
            cam.viewportHeight = clamp(cam.viewportHeight * f, 8, 200000);
            if (!Float.isFinite(cam.viewportHeight)) cam.viewportHeight = 45;
        }
        updateCamViewport();
    }

    private static float clamp(float v, double lo, double hi) {
        return (float) Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- input

    private class GameInput extends InputAdapter {
        @Override
        public boolean keyDown(int keycode) {
            switch (keycode) {
                case Input.Keys.SPACE: doStage(); return true;
                case Input.Keys.BACK: case Input.Keys.ESCAPE: onBackKey(); return true;
                case Input.Keys.TAB: toggleMap(); return true;
                case Input.Keys.P: game.world.paused = !game.world.paused; return true;
                case Input.Keys.Z: setThrottleLevel(throttleLevel() + 1); return true;
                case Input.Keys.X: setThrottleLevel(throttleLevel() - 1); return true;
                case Input.Keys.SHIFT_LEFT: setThrottle(game.world.inputThrottle + 0.05); return true;
                case Input.Keys.CONTROL_LEFT: setThrottle(game.world.inputThrottle - 0.05); return true;
                case Input.Keys.LEFT: case Input.Keys.A: slewDir = 1; syncButtonTurn(); return true;
                case Input.Keys.RIGHT: case Input.Keys.D: slewDir = -1; syncButtonTurn(); return true;
                case Input.Keys.COMMA: zoom(0.8f); return true;
                case Input.Keys.PERIOD: zoom(1.25f); return true;
                case Input.Keys.NUM_1: game.world.warp = 1; return true;
                case Input.Keys.NUM_2: game.world.warp = 2; return true;
                case Input.Keys.NUM_3: game.world.warp = 4; return true;
                case Input.Keys.NUM_4: game.world.warp = 25; return true;
                case Input.Keys.NUM_5: game.world.warp = 100; return true;
                case Input.Keys.NUM_6: game.world.warp = 1000; return true;
                case Input.Keys.NUM_7: game.world.warp = 7500; return true;
                case Input.Keys.NUM_8: game.world.warp = 50000; return true;
                case Input.Keys.NUM_9: game.world.warp = 250000; return true;
            }
            return false;
        }

        @Override
        public boolean keyUp(int keycode) {
            switch (keycode) {
                case Input.Keys.LEFT: case Input.Keys.A:
                    if (slewDir > 0) slewDir = 0;
                    syncButtonTurn();
                    return true;
                case Input.Keys.RIGHT: case Input.Keys.D:
                    if (slewDir < 0) slewDir = 0;
                    syncButtonTurn();
                    return true;
            }
            return false;
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            zoom(amountY > 0 ? 1.15f : 0.87f);
            return true;
        }

        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (touchA != -1 && touchB != -1) return false; // ignore 3rd finger
            if (touchA == -1) {
                touchA = pointer; paX = screenX; paY = screenY;
                if (mapMode) {
                    // task D5: the ring check runs FIRST in map view too —
                    // a touch landing in the annulus is steering (activate +
                    // drag), never a map pan/tap; mapPanPointer is left
                    // cleared so the drag can never fall through to panning
                    ringDrag = nearRing(screenX, screenY);
                    if (ringDrag) {
                        mapPanPointer = -1;
                        grabRing(screenX, screenY);
                    } else {
                        mapPanPointer = pointer;
                        mapPanDist = 0;
                        mapPanned = false;
                    }
                } else {
                    // steering ring takes priority over tap-select near screen center
                    ringDrag = nearRing(screenX, screenY);
                    if (ringDrag) grabRing(screenX, screenY);
                    tapCandidate = !ringDrag;
                    tapMoved = false;
                    tapDist = 0;
                }
                return true;
            }
            // second finger: two-finger camera gesture begins; one-finger ops cancel
            touchB = pointer; pbX = screenX; pbY = screenY;
            ringDrag = false;
            tapCandidate = false;
            mapPanned = true; // suppress the map tap when the gesture ends
            return true;
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (pointer == touchA && touchB != -1) {
                twoFinger(screenX, screenY, true);
                return true;
            }
            if (pointer == touchB) {
                twoFinger(screenX, screenY, false);
                return true;
            }
            if (pointer != touchA) return false;
            // one-finger drag
            if (mapMode) {
                if (ringDrag) {
                    // ring steering in map view (round 13 item 2)
                    steerRingTo(screenX, screenY);
                    paX = screenX; paY = screenY;
                    return true;
                }
                // pan the double-precision map center directly (float unproject
                // of a ~1e10 camera position was a jitter source, round 13)
                double w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
                mapCX += (paX - screenX) / w * mapCam.viewportWidth;
                mapCY += (screenY - paY) / h * mapCam.viewportHeight;
                mapPanDist += Math.hypot(screenX - paX, screenY - paY);
                if (mapPanDist > 12) mapPanned = true;
                paX = screenX; paY = screenY;
                return true;
            }
            if (ringDrag) {
                steerRingTo(screenX, screenY);
                paX = screenX; paY = screenY;
                return true;
            }
            if (tapCandidate) {
                tapDist += Math.hypot(screenX - paX, screenY - paY);
                if (tapDist > 12) tapMoved = true;
                // task 4: an empty-space drag in flight view pans the camera
                // (map-view parity). It is NOT a tap, so it never deselects a
                // part and never deactivates the steering ring. The camera
                // position is refreshed here so consecutive drag events in
                // the same frame unproject against the live pan offset.
                if (tapMoved) {
                    panCamera(cam, camPan, paX, paY, screenX, screenY);
                    cam.position.set(camPan.x, camPan.y, 0);
                    cam.update();
                }
                paX = screenX; paY = screenY;
                return true;
            }
            return false;
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            if (pointer == touchB) {
                touchB = -1;
                // re-anchor the remaining finger at its live position (no camera jump);
                // touchA may already be gone (lifted first) — never query a dead pointer
                if (touchA >= 0) {
                    paX = Gdx.input.getX(touchA);
                    paY = Gdx.input.getY(touchA);
                }
                return true;
            }
            if (pointer != touchA) return false;
            // A lifted while B is still down: promote B to the primary finger
            // instead of leaving a stale pointer id (Android getX(deadId) crashes)
            if (touchB != -1) {
                touchA = touchB;
                touchB = -1;
                paX = Gdx.input.getX(touchA);
                paY = Gdx.input.getY(touchA);
                ringDrag = false;
                tapCandidate = false;
                return true;
            }
            touchA = -1;
            boolean wasTap = tapCandidate && !tapMoved;
            boolean wasRingDrag = ringDrag;
            ringDrag = false;
            tapCandidate = false;
            if (mapMode) {
                if (mapPanPointer == pointer) mapPanPointer = -1;
                // a ring drag in map view is steering, not a map tap (item 2)
                if (!mapPanned && !wasRingDrag) mapTap(screenX, screenY);
                mapPanned = false;
                return true;
            }
            if (wasTap) flightTap(screenX, screenY);
            return true;
        }

        /** Two-finger gesture: midpoint drag pans, pinch ratio zooms (anchored at midpoint). */
        private void twoFinger(float x, float y, boolean movedIsA) {
            float nAX = movedIsA ? x : paX, nAY = movedIsA ? y : paY;
            float nBX = movedIsA ? pbX : x, nBY = movedIsA ? pbY : y;
            float midX = (nAX + nBX) / 2f, midY = (nAY + nBY) / 2f;
            float prevMidX = (paX + pbX) / 2f, prevMidY = (paY + pbY) / 2f;
            double prevDist = Math.hypot(paX - pbX, paY - pbY);
            double dist = Math.hypot(nAX - nBX, nAY - nBY);
            double sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
            if (mapMode) {
                // pan + zoom on the double-precision map center (round 13)
                mapCX += (prevMidX - midX) / sw * mapCam.viewportWidth;
                mapCY += (midY - prevMidY) / sh * mapCam.viewportHeight;
                if (prevDist > 10 && dist > 10) {
                    float factor = (float) (prevDist / dist);
                    double oldH = mapCam.viewportHeight, oldW = mapCam.viewportWidth;
                    float newH = clamp((float) (oldH * factor), 1000, 1.2e12f);
                    if (Float.isFinite(newH) && newH > 0 && newH != oldH) {
                        // world point under the midpoint, kept anchored (doubles)
                        double wx = mapCX + (midX - sw / 2) / sw * oldW;
                        double wy = mapCY + (sh / 2 - midY) / sh * oldH;
                        mapCam.viewportHeight = newH;
                        updateCamViewport();
                        mapCX = wx - (midX - sw / 2) / sw * mapCam.viewportWidth;
                        mapCY = wy - (sh / 2 - midY) / sh * newH;
                    }
                }
            } else {
                // pan by midpoint delta
                panCamera(cam, camPan, prevMidX, prevMidY, midX, midY);
                // pinch zoom anchored at the midpoint
                if (prevDist > 10 && dist > 10) {
                    float factor = (float) (prevDist / dist);
                    float oldH = cam.viewportHeight;
                    float newH = clamp(oldH * factor, 8, 200000);
                    // reject NaN/Inf results (degenerate gesture data once crashed us)
                    if (Float.isFinite(newH) && newH > 0 && newH != oldH) {
                        // world point under the midpoint before the zoom
                        tmp3.set(midX, midY, 0);
                        cam.unproject(tmp3);
                        float wx = tmp3.x, wy = tmp3.y;
                        cam.viewportHeight = newH;
                        updateCamViewport();
                        tmp3.set(midX, midY, 0);
                        cam.unproject(tmp3);
                        // keep that world point anchored under the midpoint
                        camPan.x += wx - tmp3.x;
                        camPan.y += wy - tmp3.y;
                    }
                }
            }
            paX = nAX; paY = nAY; pbX = nBX; pbY = nBY;
        }

        /** Pan a camera by a screen-space drag delta (unprojected). */
        private void panCamera(OrthographicCamera c, Vector2 offset,
                               float fromX, float fromY, float toX, float toY) {
            tmp3.set(fromX, fromY, 0);
            c.unproject(tmp3);
            float fx = tmp3.x, fy = tmp3.y;
            tmp3.set(toX, toY, 0);
            c.unproject(tmp3);
            float ddx = fx - tmp3.x, ddy = fy - tmp3.y;
            if (offset != null) {
                offset.x += ddx;
                offset.y += ddy;
            } else {
                c.position.x += ddx;
                c.position.y += ddy;
            }
        }
    }

    // ------------------------------------------------------------ gestures

    private boolean nearRing(float sx, float sy) {
        float my = Gdx.graphics.getHeight() - sy; // ring math is y-up
        float d = (float) Math.hypot(sx - ringX, my - ringY);
        return d > ringR * 0.55f && d < ringR * 1.6f;
    }

    /** Angle on the steering ring of a screen position (heading convention). */
    private double ringAngle(float sx, float sy) {
        float vx = sx - ringX;
        float vy = (Gdx.graphics.getHeight() - sy) - ringY;
        // nose direction for heading θ is (-sinθ, cosθ): solve for θ
        return Math.atan2(-vx, vy);
    }

    /**
     * Ring grab (tasks 2/3): an ALREADY-ACTIVE ring keeps its target heading —
     * only the grab offset (touch direction vs target direction at touch
     * start) is re-anchored, so the target follows the finger at a fixed
     * angle and does NOT snap to the ship's current heading. An INACTIVE
     * ring activates as before: the target restarts from the ship's current
     * heading, then follows with the same fixed-offset rule.
     */
    private void grabRing(float sx, float sy) {
        if (!SteeringIO.ringActive) {
            SteeringIO.ringActive = true;
            setSteerTarget(game.world.currentHeading());
        }
        ringDelta = ringAngle(sx, sy) - game.world.getTargetHeading();
    }

    /** Set the steering target heading from a ring drag position. */
    private void steerRingTo(float sx, float sy) {
        float vx = sx - ringX;
        float vy = (Gdx.graphics.getHeight() - sy) - ringY;
        if (vx * vx + vy * vy < 4) return;
        // item 11: heading follows the finger by the grab offset (ringDelta),
        // not absolutely — dragging starts from the current marker position
        setSteerTarget(ringAngle(sx, sy) - ringDelta);
    }

    /**
     * Tap on the map (task D4): select a TARGET — the tapped other-ship or
     * planet. The target gets a "C" circle marker at the closest point of the
     * predicted orbit (purple "X" when the gap is under 500 m, see
     * drawMapTargetMarker) and the steering ring switches to the velocity
     * relative to it (task D6). Tapping the same target again deselects.
     * (Ship switching moved to the MENU drawer, task D1.)
     */
    private void mapTap(int screenX, int screenY) {
        // an open anchor list eats the next tap anywhere (collapse-only)
        if (frameList != null) {
            toggleFrameList();
            return;
        }
        if (menuDrawer != null) {
            toggleMenuDrawer();
            return;
        }
        // tap point in world coords from the double-precision center (round 13)
        double sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        double wx = mapCX + (screenX - sw / 2) / sw * mapCam.viewportWidth;
        double wy = mapCY + (sh / 2 - screenY) / sh * mapCam.viewportHeight;
        Ship bestShip = null;
        double bestD = mapCam.viewportHeight * 0.05;
        for (Ship s : game.world.ships) {
            if (s == game.world.active) continue;
            Vec2d p = s.getUniversePos();
            double d = Math.hypot(p.x - wx, p.y - wy);
            if (d < bestD) { bestD = d; bestShip = s; }
        }
        if (bestShip != null) {
            if (mapTargetShip == bestShip) {
                mapTargetShip = null; // tapped the same target again: deselect
                stageLabel.setText("");
            } else {
                mapTargetShip = bestShip;
                mapTargetPlanet = null;
                orbitTimer = Float.MAX_VALUE; // task 5: gray orbit line NOW
                stageLabel.setText("Target " + bestShip.name);
            }
            return;
        }
        for (Planet p : game.world.planets) {
            double d = Math.hypot(p.pos.x - wx, p.pos.y - wy);
            if (d < Math.max(p.radius * 1.5, mapCam.viewportHeight * 0.03)) {
                if (mapTargetPlanet == p) {
                    mapTargetPlanet = null;
                    stageLabel.setText("");
                } else {
                    mapTargetPlanet = p;
                    mapTargetShip = null;
                    stageLabel.setText("Target " + p.name);
                }
                return;
            }
        }
    }

    /** Tap in flight view: select a part of the active ship (item 6b).
     *  Task D3 rewrite: the primary test is a DIRECT per-part hit test —
     *  Box2D body.testPoint against every part's fixtures, i.e. the tapped
     *  point must lie inside the part's actual collision polygon (the same
     *  shape the sprite covers). Among overlapping hits the part whose center
     *  is nearest the tap wins. When no polygon contains the point, the old
     *  round-11 nearest-EDGE fallback still runs so thin parts (struts,
     *  panels) remain tappable. Threshold: max(3 m, 64 px in world units). */
    private void flightTap(int screenX, int screenY) {
        if (game.world.active == null) return;
        tmp3.set(screenX, screenY, 0);
        cam.unproject(tmp3);
        final float wx = tmp3.x, wy = tmp3.y;
        final float threshold = Math.max(3f, 64f / Gdx.graphics.getHeight() * cam.viewportHeight);
        // pass 1 (task D3): direct inside-the-part hit test
        Part best = null;
        float bestD = Float.MAX_VALUE;
        for (Part p : game.world.active.parts) {
            if (p.body == null) continue;
            boolean inside = false;
            for (com.badlogic.gdx.physics.box2d.Fixture f : p.body.getFixtureList()) {
                if (f.testPoint(wx, wy)) { inside = true; break; }
            }
            if (inside) {
                float d = p.body.getPosition().dst(wx, wy);
                if (d < bestD) { bestD = d; best = p; }
            }
        }
        if (best == null) {
            // pass 2: nearest-edge fallback for thin parts
            bestD = threshold;
            Vector2 va = new Vector2(), vb = new Vector2();
            for (Part p : game.world.active.parts) {
                if (p.body == null) continue;
                for (com.badlogic.gdx.physics.box2d.Fixture f : p.body.getFixtureList()) {
                    if (!(f.getShape() instanceof com.badlogic.gdx.physics.box2d.PolygonShape)) continue;
                    com.badlogic.gdx.physics.box2d.PolygonShape poly =
                            (com.badlogic.gdx.physics.box2d.PolygonShape) f.getShape();
                    int n = poly.getVertexCount();
                    for (int i = 0; i < n; i++) {
                        poly.getVertex(i, va);
                        p.body.getWorldPoint(va);
                        poly.getVertex((i + 1) % n, vb);
                        p.body.getWorldPoint(vb);
                        float d = Attach.closestOnSegment(tmp2.set(wx, wy), va, vb, va).dst(tmp2);
                        if (d < bestD) { bestD = d; best = p; }
                    }
                }
            }
        }
        selectedPart = best;
        if (best == null) {
            // item 2-UI: a tap that hits NO button (we're past the stage) and
            // NO part DEACTIVATES the steering ring — engines center.
            // Task 1: deactivation also DROPS the target heading (cleared,
            // re-primed from the live heading; next activation restarts fresh)
            game.world.deactivateRing();
        }
        stageLabel.setText(best != null
                ? "Selected " + best.type.name + (best.group > 0 ? " [group " + best.group + "]" : "")
                : "");
    }

    /**
     * BACK key consumption layers (innermost UI first):
     *   1. the back-confirmation dialog is open → dismiss it (acts as NO);
     *   2. the MENU drawer is open → slide it closed;
     *   3. otherwise → open the return-to-editor confirmation (task D2).
     */
    private void onBackKey() {
        if (backDialog != null) {
            toggleBackDialog();
        } else if (menuDrawer != null) {
            toggleMenuDrawer();
        } else {
            toggleBackDialog();
        }
    }

    /**
     * Back-key confirmation (task D2): the phone back key opens a modal ask —
     * "Return to the editor?" — YES saves the world and goes straight to the
     * build editor (EditorScreen), NO (or back again) dismisses.
     */
    private void toggleBackDialog() {
        if (backDialog != null) {
            backDialog.remove();
            backDialog = null;
            return;
        }
        backDialog = new Table();
        backDialog.setBackground(game.ui.tinted(new Color(0.10f, 0.11f, 0.16f, 0.95f)));
        backDialog.add(new Label("Return to the editor?", game.ui.skin)).pad(24).colspan(2).row();
        TextButton yes = big(new TextButton("YES", game.ui.skin));
        yes.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.world.save();
                game.setScreen(new EditorScreen(game, null));
            }
        });
        TextButton no = big(new TextButton("NO", game.ui.skin));
        no.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { toggleBackDialog(); }
        });
        backDialog.add(yes).width(300).height(130).pad(16);
        backDialog.add(no).width(300).height(130).pad(16);
        backDialog.pack();
        backDialog.setPosition((Gdx.graphics.getWidth() - backDialog.getWidth()) / 2f,
                (Gdx.graphics.getHeight() - backDialog.getHeight()) / 2f);
        stage.addActor(backDialog);
    }

    /** test hook: true while the back-key confirmation is on screen. */
    public boolean backDialogForTest() { return backDialog != null; }

    /** ACTIVATE button: fire onStage on the selected part and its activation group. */
    public void activateSelected() {
        if (selectedPart == null || game.world.active == null
                || !game.world.active.parts.contains(selectedPart)) {
            stageLabel.setText("Tap a part first, then ACTIVATE");
            return;
        }
        int fired = 0;
        int grp = selectedPart.group;
        // snapshot group members FIRST: a detacher's onStage defers a ship
        // split that mutates the live parts list — iterating it here while it
        // changes was the group-activate crash.
        List<Part> targets = new ArrayList<>();
        for (Part p : game.world.active.parts) {
            if (p == selectedPart || (grp > 0 && p.group == grp)) targets.add(p);
        }
        for (Part p : targets) {
            // skip parts already destroyed/moved by an earlier member's activation
            if (p.body == null || p.ship == null || !p.ship.parts.contains(p)) continue;
            p.callOnStage();
            fired++;
        }
        game.world.processDeferredStructure(); // apply detach/split immediately
        stageLabel.setText(fired > 1
                ? "Activated group " + grp + " (" + fired + " parts)"
                : "Activated " + selectedPart.type.name);
    }

    /** test hook: select a part without a synthetic tap (smoke-detacher). */
    public void debugSelectPart(Part p) {
        selectedPart = p;
        if (p == null) stageLabel.setText(""); // mirror the tap-path deselect
    }

    private void setThrottle(double v) {
        game.world.inputThrottle = Math.max(0, Math.min(1, v));
    }

    // ------------------------------ segmented throttle (round 11 item 10)

    /** Current throttle segment 0..10 (k = round(throttle * 10)). */
    private int throttleLevel() {
        return (int) Math.round(game.world.inputThrottle * SegmentedThrottle.SEGMENTS);
    }

    private void setThrottleLevel(int k) {
        k = Math.max(0, Math.min(SegmentedThrottle.SEGMENTS, k));
        game.world.inputThrottle = k / (double) SegmentedThrottle.SEGMENTS;
    }

    /** Smoke-test hooks: read/set the throttle in whole segments. */
    public int throttleLevelForTest() { return throttleLevel(); }
    public void setThrottleLevelForTest(int k) { setThrottleLevel(k); }

    /**
     * 10-segment throttle bar drawn from the Runtime atlas (round 11 item 10,
     * follow-up): ThrottleControl.png is the track/frame and each
     * ThrottleLevel{1..10}.png is one segment — a white alpha-mask bar that
     * grows wider with its level, stacked bottom-to-top. Lit segments are
     * tinted green, unlit ones dark red (the sprites themselves are white).
     * Tap or drag to scrub; the touched segment count is round(yNorm * 10),
     * giving 11 states (0..100% in 10% steps). Falls back to flat rectangles
     * when the atlas is unavailable.
     */
    private final class SegmentedThrottle extends Actor {
        static final int SEGMENTS = 10;
        private TextureRegion track;
        private final Texture[] seg = new Texture[SEGMENTS];
        private com.differentrockets.util.AtlasPack loadedFrom;

        SegmentedThrottle() {
            addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent e, float x, float y, int pointer, int button) {
                    scrub(y);
                    return true;
                }
                @Override
                public void touchDragged(InputEvent e, float x, float y, int pointer) {
                    scrub(y);
                }
            });
        }
        private void scrub(float y) {
            float n = getHeight() <= 0 ? 0 : y / getHeight();
            int k = Math.round(Math.max(0, Math.min(1, n)) * SEGMENTS);
            game.world.inputThrottle = k / (double) SEGMENTS;
        }
        private void ensureSprites() {
            if (loadedFrom == game.runtimeSprites) return;
            for (int i = 0; i < SEGMENTS; i++) {
                if (seg[i] != null) { seg[i].dispose(); seg[i] = null; }
            }
            track = null;
            loadedFrom = game.runtimeSprites;
            if (loadedFrom == null) return;
            track = loadedFrom.find("ThrottleControl.png");
            for (int i = 0; i < SEGMENTS; i++) {
                // r="y" sprites are un-rotated here so no segment is sideways
                seg[i] = loadedFrom.extractUnrotated("ThrottleLevel" + (i + 1) + ".png");
            }
        }
        @Override
        public void draw(com.badlogic.gdx.graphics.g2d.Batch batch, float parentAlpha) {
            ensureSprites();
            int lit = throttleLevel();
            if (track != null && seg[0] != null) {
                // textured bar: track frame + 10 stacked segment sprites
                batch.setColor(Color.WHITE);
                batch.draw(track, getX(), getY(), getWidth(), getHeight());
                float slotH = getHeight() / SEGMENTS;
                for (int i = 0; i < SEGMENTS; i++) {
                    Texture t = seg[i];
                    if (t == null) continue;
                    // fit the sprite inside its slot, keep its aspect ratio,
                    // bottom-aligned within the slot and centered horizontally
                    float dw = getWidth() * 0.86f;
                    float dh = dw * t.getHeight() / t.getWidth();
                    float maxH = slotH * 0.86f;
                    if (dh > maxH) { dh = maxH; dw = dh * t.getWidth() / t.getHeight(); }
                    float sx = getX() + (getWidth() - dw) / 2f;
                    float sy = getY() + i * slotH + (slotH - dh) / 2f;
                    if (i < lit) batch.setColor(0.30f, 1.00f, 0.35f, 1f); // lit: green
                    else batch.setColor(0.42f, 0.10f, 0.10f, 1f);         // empty: dark red
                    batch.draw(t, sx, sy, dw, dh);
                }
                batch.setColor(Color.WHITE);
                return;
            }
            // fallback: flat procedural segments (atlas unavailable)
            float gap = 4f;
            float h = (getHeight() - gap * (SEGMENTS - 1)) / SEGMENTS;
            for (int i = 0; i < SEGMENTS; i++) {
                if (i < lit) batch.setColor(0.25f, 0.95f, 0.35f, 1f);
                else batch.setColor(0.42f, 0.10f, 0.10f, 1f);
                batch.draw(starTex, getX(), getY() + i * (h + gap), getWidth(), h);
            }
            batch.setColor(Color.WHITE);
        }
    }

    /** Entering map view re-centers on the active ship (mapInit=false triggers auto-fit in renderMap). */
    public void toggleMap() {
        mapMode = !mapMode;
        if (mapMode) {
            mapInit = false;
            lastAnchorBody = null; // re-anchor the camera follow, no jump
            orbitTimer = Float.MAX_VALUE; // force an immediate re-propagation
        } else if (frameList != null) {
            toggleFrameList(); // never leave the anchor list open over the flight view
        }
    }

    /** g = mu/r² of body b on the ship at p (sort key for the anchor list). */
    private static double gOn(Vec2d p, Planet b) {
        double dx = b.pos.x - p.x, dy = b.pos.y - p.y;
        return b.mu() / (dx * dx + dy * dy);
    }

    /**
     * Open/close the map anchor list (round 17): every massive body, sorted
     * by the CURRENT gravity it exerts on the active ship (strongest first),
     * plus an "Auto" entry (the round-15 dominant-body behavior, index -1).
     * Selecting an entry collapses the list and forces an immediate
     * re-propagation so the orbit line switches frame instantly; tapping the
     * FRAME button again or tapping the map collapses without changes.
     */
    private void toggleFrameList() {
        if (frameList != null) {
            frameList.remove();
            frameList = null;
            return;
        }
        final java.util.List<Planet> bodies = new java.util.ArrayList<>();
        for (Planet p : game.world.planets) {
            if (p.mu() > 0) bodies.add(p);
        }
        final Vec2d sp = game.world.active != null ? game.world.active.getUniversePos() : null;
        if (sp != null) {
            bodies.sort((a, b) -> Double.compare(gOn(sp, b), gOn(sp, a)));
        }
        frameList = new Table();
        TextButton auto = big(new TextButton("Auto (dominant)", game.ui.skin), 1.3f);
        auto.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { selectAnchor(-1, "Auto"); }
        });
        frameList.add(auto).width(460).height(104).pad(2).row();
        for (final Planet p : bodies) {
            final int idx = game.world.planets.indexOf(p);
            TextButton b = big(new TextButton(p.name, game.ui.skin), 1.3f);
            b.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) { selectAnchor(idx, p.name); }
            });
            frameList.add(b).width(460).height(104).pad(2).row();
        }
        frameList.pack();
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        frameList.setPosition(sw - frameList.getWidth() - 6,
                Math.max(6, sh - 390 - frameList.getHeight())); // under the top-right bar
        stage.addActor(frameList);
    }

    private void selectAnchor(int idx, String label) {
        anchorIndex = idx;
        frameBtn.setText("FRAME:" + label);
        orbitTimer = Float.MAX_VALUE; // re-propagate NOW with the new frame
        if (frameList != null) toggleFrameList(); // collapse
    }

    /**
     * Menu drawer (task D1): opened by the top-left MENU button, slides out
     * from the left edge. Holds the SWITCH SHIP list (the round-20 item 6
     * logic, distance-sorted, GameWorld.setActive on pick) plus a MAIN MENU
     * entry that saves and returns to the menu screen. Tapping MENU again or
     * tapping the map slides it back out.
     */
    private void toggleMenuDrawer() {
        if (menuDrawer != null) {
            final Table closing = menuDrawer;
            menuDrawer = null;
            closing.addAction(Actions.sequence(
                    Actions.moveTo(-closing.getWidth() - 8, closing.getY(), 0.18f),
                    Actions.removeActor()));
            return;
        }
        menuDrawer = new Table();
        menuDrawer.setBackground(game.ui.tinted(new Color(0.10f, 0.11f, 0.16f, 0.92f)));
        Label ver = new Label("v" + game.version, game.ui.skin);
        ver.setColor(new Color(0.55f, 0.60f, 0.72f, 1f));
        menuDrawer.add(ver).pad(4).row();
        Label header = new Label("SWITCH SHIP", game.ui.skin);
        menuDrawer.add(header).pad(6).row();
        final java.util.List<Ship> others = new java.util.ArrayList<>();
        for (Ship s : game.world.ships) {
            if (s != game.world.active && !s.parts.isEmpty()) others.add(s);
        }
        final Vec2d ap = game.world.active != null ? game.world.active.getUniversePos() : null;
        if (ap != null) {
            others.sort((a, b) -> Double.compare(
                    a.getUniversePos().dist(ap), b.getUniversePos().dist(ap)));
        }
        if (others.isEmpty()) {
            menuDrawer.add(new Label("No other ships", game.ui.skin)).pad(4).row();
        }
        for (final Ship s : others) {
            double d = ap != null ? s.getUniversePos().dist(ap) : 0;
            TextButton b = big(new TextButton(s.name + "  " + fmt(d), game.ui.skin), 1.4f);
            b.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) { selectShip(s); }
            });
            menuDrawer.add(b).width(560).height(104).pad(2).row();
        }
        TextButton mainMenu = big(new TextButton("MAIN MENU", game.ui.skin));
        mainMenu.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.world.save();
                game.setScreen(new MenuScreen(game));
            }
        });
        menuDrawer.add(mainMenu).width(560).height(104).pad(6).row();
        menuDrawer.pack();
        // start off-screen left, slide in below the MENU/MAP column
        float sh = Gdx.graphics.getHeight();
        float dy = Math.max(6, sh - 400 - menuDrawer.getHeight());
        menuDrawer.setPosition(-menuDrawer.getWidth() - 8, dy);
        stage.addActor(menuDrawer);
        menuDrawer.addAction(Actions.moveTo(6, dy, 0.18f));
    }

    private void selectShip(Ship s) {
        game.world.setActive(s);
        camPan.setZero();            // flight camera snaps onto the new ship
        mapInit = false;             // map view re-fits on it too
        orbitTimer = Float.MAX_VALUE; // orbit line re-propagates NOW
        // task D4: a target pointing at the newly active ship is meaningless
        if (mapTargetShip == s) mapTargetShip = null;
        stageLabel.setText("Controlling " + s.name);
        if (menuDrawer != null) toggleMenuDrawer(); // slide the drawer back
    }

    /**
     * Map auto-fit (round 13): frame the predicted trajectory's bounding box
     * (in draw-anchored coords), so even a short ballistic hop is visible on
     * first open; falls back to framing the current planet when there is no
     * prediction. Clamped to [2e4, 1.2e12] m.
     */
    private void autoFitMap() {
        double need = 0;
        // predictor.anchor already reflects the selected frame (round 17):
        // compute() is called with anchorIndex, which it resolves to the
        // explicit body or the automatic dominant one
        if (predictor.count > 1 && predictor.anchor >= 0) {
            Planet a0 = game.world.planets.get(predictor.anchor);
            double bx = a0.pos.x;
            double by = a0.pos.y;
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i < predictor.count; i++) {
                double dx = predictor.xs[i] - predictor.fx[i] + bx;
                double dy = predictor.ys[i] - predictor.fy[i] + by;
                if (dx < minX) minX = dx;
                if (dx > maxX) maxX = dx;
                if (dy < minY) minY = dy;
                if (dy > maxY) maxY = dy;
            }
            double aspect = (double) Gdx.graphics.getWidth() / Gdx.graphics.getHeight();
            need = Math.max(maxY - minY, (maxX - minX) / aspect) * 1.3;
        }
        if (need <= 0) {
            Planet cp = game.world.currentPlanet();
            need = Math.max(cp != null ? cp.radius * 4 : 0, 200000);
        }
        mapCam.viewportHeight = (float) Math.max(20000, Math.min(1.2e12, need));
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(float delta) {
        // turn buttons/keys slew the steering target heading (~45 deg/s; a tap ≈ 2°)
        if (slewDir != 0) {
            setSteerTarget(
                    game.world.getTargetHeading() + slewDir * Math.toRadians(45) * delta);
        }
        float fd = Math.min(delta, 1f / 20f);
        game.world.update(fd);
        // SteeringIO backstop: the world's PI controller may itself re-prime
        // the target heading (spawn, ship switch) — mirror it every frame,
        // but ONLY while the ring is active: a deactivated ring keeps its
        // cleared target (task 1), the mirror must not resurrect it
        if (SteeringIO.ringActive) {
            SteeringIO.targetHeadingRad = game.world.getTargetHeading();
        }
        // particle FX advance in simulated time (freeze while paused, warp-aware)
        lastSimDt = game.world.paused ? 0f : fd * game.world.warp;
        FlameFx.update(lastSimDt);

        Gdx.gl.glClearColor(0.02f, 0.03f, 0.07f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (mapMode) {
            renderMap();
            // item 7: the navigation ring stays visible in map view
            drawSteeringRing();
        } else {
            renderFlight();
            drawSteeringRing();
        }

        updateTelemetry();
        stage.act(delta);
        stage.draw();
    }

    /** test hook: set the flight camera zoom (meters of viewport height). */
    public void setFlightZoom(float viewportHeight) {
        cam.viewportHeight = viewportHeight;
        updateCamViewport();
    }

    /** test hook: current flight viewport height (NaN check in the zoom hammer). */
    public float flightZoomForTest() { return cam.viewportHeight; }

    /** test hooks: pooled flame particle counts (Item 2 cap assertion). */
    public int flameParticlesForTest() { return FlameFx.activeCount(); }
    public int flameParticlesMaxForTest() { return FlameFx.maxActiveEver(); }

    private void updateCamViewport() {
        float aspect = (float) Gdx.graphics.getWidth() / Gdx.graphics.getHeight();
        cam.viewportWidth = cam.viewportHeight * aspect;
        cam.update();
        mapCam.viewportWidth = mapCam.viewportHeight * aspect;
        mapCam.update();
    }

    private void renderFlight() {
        // camera follows active ship COM (which is near origin) + two-finger pan offset
        cam.position.set(camPan.x, camPan.y, 0);
        cam.update();

        drawStars();

        Vec2d shipPos = game.world.active != null ? game.world.active.getUniversePos() : game.world.origin;

        // planets: atmosphere glow + body circle
        game.batch.setProjectionMatrix(cam.combined);
        game.batch.begin();
        for (Planet p : game.world.planets) {
            double dx = p.pos.x - game.world.origin.x;
            double dy = p.pos.y - game.world.origin.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            // skip if far off-screen
            float halfView = Math.max(cam.viewportWidth, cam.viewportHeight) / 2f;
            double outer = p.radius + Math.max(0, p.maxHeight) + Math.max(0, p.atmoHeight);
            if (dist - outer > halfView * 3) continue;
            if (p.hasAtmosphere()) {
                float r = (float) (p.radius + p.atmoHeight);
                Color c = p.mapColor;
                game.batch.setColor(0.55f + c.r * 0.4f, 0.65f + c.g * 0.3f, 1f, 0.5f);
                game.batch.draw(atmoTex, (float) dx - r, (float) dy - r, r * 2, r * 2);
                game.batch.setColor(1, 1, 1, 1);
            }
        }
        game.batch.end();

        game.shapes.setProjectionMatrix(cam.combined);
        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Planet p : game.world.planets) {
            double dx = p.pos.x - game.world.origin.x;
            double dy = p.pos.y - game.world.origin.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            float halfView = Math.max(cam.viewportWidth, cam.viewportHeight) / 2f;
            double outer = p.radius + Math.max(0, p.maxHeight) + Math.max(0, p.atmoHeight);
            if (dist - outer > halfView * 3) continue;
            if (p == game.world.sun) {
                game.shapes.setColor(1f, 0.9f, 0.4f, 1f);
            } else {
                Color cc = p.crustColor;
                game.shapes.setColor(cc.r, cc.g, cc.b, 1f);
            }
            game.shapes.circle((float) dx, (float) dy, (float) p.radius, 96);
        }
        game.shapes.end();

        game.world.terrain.render(cam);

        // ships (the active one is drawn even while parked on super-warp rails)
        game.batch.begin();
        for (Ship s : game.world.ships) {
            if (s.onRails && s != game.world.active) continue;
            drawShip(s);
        }
        game.batch.end();

        drawFlames();
        drawShockCones();

        // selected-part highlight (task D3): the selected part's sprite is
        // tinted light blue directly in drawShip — no more blue circle

        drawTankLevels();

        // item 6: aerodynamic drag resultant (CoP + total vector) behind DRAG toggle
        drawDragOverlay();

        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Ship s : game.world.ships) {
            if (s == game.world.active) continue;
            Vec2d p = s.getUniversePos();
            double dx = p.x - game.world.origin.x;
            double dy = p.y - game.world.origin.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 30000) {
                game.shapes.setColor(1f, 0.6f, 0.2f, 1f);
                game.shapes.circle((float) dx, (float) dy, cam.viewportHeight * 0.012f, 12);
            }
        }
        game.shapes.end();
    }

    /**
     * Item 5 (round 9): per-tank live level overlay — a bright fill rising
     * from the tank bottom plus a subtle dark empty area, rotated with the
     * part body. Liquid fuel = cyan, electric (battery) = green-yellow, solid
     * (SRB) = orange. Cheap guards: tanks only, and only when a tank spans at
     * least ~24 px on screen (zoomed-in enough to read).
     */
    private void drawTankLevels() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        float screenH = Gdx.graphics.getHeight();
        game.shapes.setProjectionMatrix(cam.combined);
        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Ship s : game.world.ships) {
            if (s.onRails) continue;
            for (Part p : s.parts) {
                if (p.body == null || p.getFuelCapacity() <= 0) continue;
                float spanPx = p.type.height / cam.viewportHeight * screenH;
                if (spanPx < 24) continue;
                double frac = p.getFuel() / p.getFuelCapacity();
                if (frac < 0) frac = 0;
                if (frac > 1) frac = 1;
                float w2 = p.type.width / 2f * 0.16f; // thin gauge strip (round 11 item 9)
                float h2 = p.type.height / 2f;
                Vector2 c = p.body.getPosition();
                float a = p.body.getAngle();
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                float fillY = -h2 + p.type.height * (float) frac;
                // empty area above the fill line: subtle dark
                levelQuad(c, cos, sin, -w2, fillY, w2, h2, 0f, 0f, 0f, 0.30f);
                // fill below the line: bright, color by fuel type
                int ft = p.getFuelType();
                if (ft == 2) levelQuad(c, cos, sin, -w2, -h2, w2, fillY, 0.75f, 1f, 0.30f, 0.55f);
                else if (ft == 3) levelQuad(c, cos, sin, -w2, -h2, w2, fillY, 1f, 0.60f, 0.20f, 0.55f);
                else levelQuad(c, cos, sin, -w2, -h2, w2, fillY, 0.15f, 0.85f, 1f, 0.55f);
            }
        }
        game.shapes.end();
    }

    /**
     * Item 6: aggregate the per-part aerodynamic drag (½ρv²·CdA·dragExposure —
     * the same law GameWorld.applyEnvironmentForces integrates) of the active
     * ship into a center-of-pressure point plus a total force vector, and draw
     * an arrow at the CoP scaled to the magnitude with a numeric label.
     */
    private void drawDragOverlay() {
        if (!dragOverlay) return;
        Ship s = game.world.active;
        if (s == null || s.onRails) return;
        double fx = 0, fy = 0;   // total drag force (physics frame direction)
        double mx = 0, my = 0;   // force-weighted position moment (CoP)
        double fSum = 0;
        for (Part p : s.parts) {
            if (p.body == null || !p.body.isActive()) continue;
            Vector2 bp = p.body.getPosition();
            double ux = game.world.origin.x + bp.x;
            double uy = game.world.origin.y + bp.y;
            // nearest body by surface distance (same rule as the physics pass)
            Planet np = null;
            double bestAlt = Double.MAX_VALUE;
            for (Planet q : game.world.planets) {
                double dx = ux - q.pos.x, dy = uy - q.pos.y;
                double d = Math.sqrt(dx * dx + dy * dy) - q.radius;
                if (d < bestAlt) { bestAlt = d; np = q; }
            }
            if (np == null || !np.hasAtmosphere()) continue;
            // round 14: honor the player-editable density law (mod/physics.lua)
            double rho = game.world.densityAt(ux, uy);
            if (rho <= 1e-9) continue;
            Vector2 v = p.body.getLinearVelocity();
            // wind-relative velocity in the universe frame (planet rotation ignored)
            double rvx = game.world.frameVel.x + s.originVel.x + v.x - np.vel.x;
            double rvy = game.world.frameVel.y + s.originVel.y + v.y - np.vel.y;
            double speed2 = rvx * rvx + rvy * rvy;
            if (speed2 <= 0.01) continue;
            double speed = Math.sqrt(speed2);
            double cd = !Double.isNaN(p.dragCd) ? p.dragCd : Math.max(0.0, 0.75 + p.type.drag);
            double area = !Double.isNaN(p.dragArea) ? p.dragArea : p.type.width;
            double fmag = 0.5 * rho * speed2 * cd * area * p.dragExposure;
            fx += -fmag * rvx / speed;
            fy += -fmag * rvy / speed;
            mx += bp.x * fmag;
            my += bp.y * fmag;
            fSum += fmag;
        }
        double fTot = Math.hypot(fx, fy);
        if (fSum <= 0 || fTot <= 1e-6) return;
        float copX = (float) (mx / fSum), copY = (float) (my / fSum);
        float dirX = (float) (fx / fTot), dirY = (float) (fy / fTot);

        // log-scaled arrow length so 0.1 kN and 500 kN both read on screen
        float len = cam.viewportHeight * 0.18f
                * (float) Math.max(0.12, Math.min(1, Math.log10(1 + fTot / 2000.0) / 1.5));
        float tipX = copX + dirX * len, tipY = copY + dirY * len;
        float barb = len * 0.22f;
        double ba = Math.atan2(dirY, dirX);
        float b1x = (float) (tipX - Math.cos(ba - 0.45) * barb), b1y = (float) (tipY - Math.sin(ba - 0.45) * barb);
        float b2x = (float) (tipX - Math.cos(ba + 0.45) * barb), b2y = (float) (tipY - Math.sin(ba + 0.45) * barb);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        game.shapes.setProjectionMatrix(cam.combined);
        game.shapes.begin(ShapeRenderer.ShapeType.Line);
        game.shapes.setColor(1f, 0.62f, 0.15f, 0.95f);
        game.shapes.line(copX, copY, tipX, tipY);
        game.shapes.line(tipX, tipY, b1x, b1y);
        game.shapes.line(tipX, tipY, b2x, b2y);
        // center-of-pressure marker
        game.shapes.circle(copX, copY, cam.viewportHeight * 0.012f, 16);
        game.shapes.end();

        // numeric label at the arrow tip — drawn in screen space (the same
        // ortho as the ring readouts): readable at every zoom level
        tmp3.set(tipX, tipY, 0);
        cam.project(tmp3);
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        game.batch.setProjectionMatrix(ringMat.setToOrtho2D(0, 0, sw, sh));
        game.batch.begin();
        game.font.getData().setScale(BS);
        game.font.setColor(1f, 0.72f, 0.3f, 1f);
        String label = fTot >= 1e6 ? String.format("%.2f MN", fTot / 1e6)
                : fTot >= 1e3 ? String.format("%.1f kN", fTot / 1e3)
                : String.format("%.0f N", fTot);
        game.font.draw(game.batch, label, tmp3.x + 12, tmp3.y - 6);
        game.font.getData().setScale(game.ui.fontScale);
        game.font.setColor(Color.WHITE);
        game.batch.end();
    }

    /** Rotated quad in a part's local frame ((x1,y1)=bottom-left, (x2,y2)=top-right), 2 triangles. */
    private void levelQuad(Vector2 c, float cos, float sin,
                           float x1, float y1, float x2, float y2,
                           float r, float g, float b, float a) {
        game.shapes.setColor(r, g, b, a);
        float ax = c.x + x1 * cos - y1 * sin, ay = c.y + x1 * sin + y1 * cos;
        float bx = c.x + x2 * cos - y1 * sin, by = c.y + x2 * sin + y1 * cos;
        float cx = c.x + x2 * cos - y2 * sin, cy = c.y + x2 * sin + y2 * cos;
        float dx = c.x + x1 * cos - y2 * sin, dy = c.y + x1 * sin + y2 * cos;
        game.shapes.triangle(ax, ay, bx, by, cx, cy);
        game.shapes.triangle(ax, ay, cx, cy, dx, dy);
    }

    private void drawShip(Ship s) {
        for (Part p : s.parts) {
            if (p.body == null) continue;
            TextureRegion r = game.shipSprites.find(p.type.sprite);
            Vector2 pos = p.body.getPosition();
            // wheels: the visible tire is the tireBody — spin the sprite with
            // it (the axle/part body itself never rotates while rolling)
            float angleDeg = (float) Math.toDegrees(
                    p.tireBody != null ? p.tireBody.getAngle() : p.body.getAngle());
            if (r != null) {
                // task D3: the tap-selected part glows light blue (tint)
                boolean sel = p == selectedPart;
                if (sel) game.batch.setColor(0.60f, 0.85f, 1f, 1f);
                game.batch.draw(r, pos.x - p.type.width / 2f, pos.y - p.type.height / 2f,
                        p.type.width / 2f, p.type.height / 2f, p.type.width, p.type.height,
                        p.flippedX ? -1f : 1f, p.flippedY ? -1f : 1f, angleDeg);
                if (sel) game.batch.setColor(Color.WHITE);
            }
            // engine flames are drawn procedurally in drawFlames() after batch.end()
            // parachute canopy when deployed
            if ("parachute".equals(p.type.type) && p.deployed) {
                TextureRegion cr = game.shipSprites.find("Parachute.png");
                if (cr != null) {
                    Vector2 top = p.body.getWorldPoint(tmp2.set(0, p.type.height / 2f));
                    float w = 22f, h = 22f;
                    game.batch.draw(cr, top.x - w / 2f, top.y, w / 2f, 0, w, h,
                            p.flippedX ? -1f : 1f, p.flippedY ? -1f : 1f, angleDeg);
                }
            }
        }
    }

    /** Engine flames: drawn by mod/flame.lua when present; built-in default otherwise. */
    private void drawFlames() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        game.shapes.setProjectionMatrix(cam.combined);
        boolean lua = FlameScript.begin(lastSimDt);
        if (!lua) {
            drawFlamesBuiltin();
            FlameFx.render(game.batch);
            return;
        }
        for (Ship s : game.world.ships) {
            if (s.onRails) continue;
            // ship-level airflow state (round 28): plume bending + Mach data
            double[] flow = airflow(s);
            for (Part p : s.parts) {
                if (p.body == null || p.type.engine == null || p.flameLevel <= 0.01f) continue;
                float lvl = Math.min(1f, p.flameLevel);
                Vector2 nozzle = p.body.getWorldPoint(tmp2.set(0, -p.type.height / 2f));
                float ang = p.body.getAngle() + (float) Math.toRadians(p.flameGimbalDeg);
                // thrust pushes along (-sin, cos); the plume exits the nozzle the opposite way
                float dx = (float) Math.sin(ang), dy = -(float) Math.cos(ang);
                float nozzleW = p.type.width * 0.3f * p.type.engine.size;
                // ambient atmosphere at the nozzle (universe coords) drives
                // Mach diamonds / plume expansion in mod/flame.lua
                double ux = game.world.origin.x + nozzle.x;
                double uy = game.world.origin.y + nozzle.y;
                FlameScript.drawPart(nozzle.x, nozzle.y, dx, dy, ang, nozzleW, lvl,
                        p.type.engine.size, p.type.height, game.world.time, p.type.engine.fuelType,
                        game.world.pressureAt(ux, uy), game.world.densityAt(ux, uy),
                        System.identityHashCode(p),
                        flow[0], flow[1], flow[2], flow[3]);
            }
        }
        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        FlameScript.flush(game.shapes);
        game.shapes.end();
        // textured pass: script sprites (core glow / Mach diamonds) + pooled
        // exhaust particles; both render additively with their own batch scope
        FlameScript.flushSprites(game.batch);
        FlameFx.render(game.batch);
    }

    /**
     * Ship airflow state (round 28): {mach, windX, windY, relSpeed} — wind is
     * the ONCOMING-flow unit vector (opposite the atmosphere-relative
     * velocity), relSpeed in physics length-units/s, Mach against a constant
     * 340 u/s sound speed (physics.lua has no temperature model). All zeros
     * outside an atmosphere.
     */
    private static final double SOUND_SPEED = 340.0;
    private final double[] flowTmp = new double[4];

    private double[] airflow(Ship s) {
        flowTmp[0] = flowTmp[1] = flowTmp[2] = flowTmp[3] = 0;
        Vec2d uv = s.getUniverseVel();
        Vec2d up = s.getUniversePos();
        Planet np = game.world.nearestPlanetTo(up.x, up.y);
        if (np == null || !np.hasAtmosphere()) return flowTmp;
        double rvx = uv.x - np.vel.x, rvy = uv.y - np.vel.y;
        double sp = Math.hypot(rvx, rvy);
        if (sp < 1e-6) return flowTmp;
        flowTmp[0] = sp / SOUND_SPEED;
        flowTmp[1] = -rvx / sp;
        flowTmp[2] = -rvy / sp;
        flowTmp[3] = sp;
        return flowTmp;
    }

    /**
     * Mach / vapor cone (round 28 v2): when the ship is supersonic in enough
     * air, EVERY windward-exposed edge (Ship.windwardEdges) gets a shock —
     * drawn procedurally by mod/flame.lua drawShock (layered cone gradient,
     * oblique vs bow shock, shimmer stripes); a plain built-in cone per edge
     * is the fallback. Direction convention: upwind = the direction the
     * airflow comes FROM (= the ship's atmosphere-relative velocity
     * direction); the cone opens downstream from the windward tip.
     */
    private void drawShockCones() {
        boolean lua = FlameScript.begin(lastSimDt) && FlameScript.hasShock();
        boolean anyLua = false, anyBuiltin = false;
        for (Ship s : game.world.ships) {
            if (s.onRails) continue;
            double[] f = airflow(s);
            double mach = f[0];
            if (mach <= 1.02) continue;
            Vec2d up = s.getUniversePos();
            double pressure = game.world.pressureAt(up.x, up.y);
            if (pressure <= 0.003) continue; // effectively vacuum: no shock
            // upwind: where the airflow comes from = ship's motion direction
            float ux = (float) -f[1], uy = (float) -f[2];
            java.util.List<Ship.WindwardEdge> edges = s.windwardEdges(ux, uy);
            if (edges.isEmpty()) continue;
            if (lua) {
                double density = game.world.densityAt(up.x, up.y);
                for (Ship.WindwardEdge e : edges) {
                    FlameScript.drawShock(e.x, e.y, e.half, e.sharp, mach, ux, uy, f[3],
                            pressure, density, game.world.time,
                            System.identityHashCode(e.part != null ? e.part : e));
                }
                anyLua = true;
            } else {
                if (!anyBuiltin) {
                    anyBuiltin = true;
                    Gdx.gl.glEnable(GL20.GL_BLEND);
                    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                    game.shapes.setProjectionMatrix(cam.combined);
                    game.shapes.begin(ShapeRenderer.ShapeType.Filled);
                }
                double mu = Math.asin(1.0 / mach);
                float tan = (float) Math.tan(mu);
                float aM = (float) Math.min(1, (mach - 1.0) / 0.5);
                float aP = (float) Math.min(1, pressure / 0.25);
                float a = 0.16f * aM * aP;
                if (a <= 0.004f) continue;
                float dx = -ux, dy = -uy;                    // downstream
                float px = -dy, py = dx;                     // perpendicular
                for (Ship.WindwardEdge e : edges) {
                    float half = Math.max(0.5f, e.half);
                    float len = Math.min(half / tan * 1.5f + half * 2f, half * 10f + 30f);
                    float spread = len * tan;
                    game.shapes.setColor(0.85f, 0.92f, 1f, a);
                    game.shapes.triangle(e.x, e.y,
                            e.x + dx * len + px * spread, e.y + dy * len + py * spread,
                            e.x + dx * len * 0.45f, e.y + dy * len * 0.45f);
                    game.shapes.triangle(e.x, e.y,
                            e.x + dx * len - px * spread, e.y + dy * len - py * spread,
                            e.x + dx * len * 0.45f, e.y + dy * len * 0.45f);
                }
            }
        }
        if (anyBuiltin) game.shapes.end();
        if (anyLua) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            game.shapes.setProjectionMatrix(cam.combined);
            game.shapes.begin(ShapeRenderer.ShapeType.Filled);
            FlameScript.flush(game.shapes);
            game.shapes.end();
            FlameScript.flushSprites(game.batch);
        }
    }

    /** Built-in 3-layer plume (fallback when mod/flame.lua is missing or broken). */
    private void drawFlamesBuiltin() {        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Ship s : game.world.ships) {
            if (s.onRails) continue;
            for (Part p : s.parts) {
                if (p.body == null || p.type.engine == null || p.flameLevel <= 0.01f) continue;
                boolean ion = p.type.engine.fuelType == 2;
                float lvl = Math.min(1f, p.flameLevel);
                Vector2 nozzle = p.body.getWorldPoint(tmp2.set(0, -p.type.height / 2f));
                float ang = p.body.getAngle() + (float) Math.toRadians(p.flameGimbalDeg);
                // thrust pushes along (-sin, cos); the plume exits the nozzle the opposite way
                float dx = (float) Math.sin(ang), dy = -(float) Math.cos(ang);
                // nozzle width scales with the engine's size stat (Blasto 170 = 0.96 m)
                float nozzleW = p.type.width * 0.3f * p.type.engine.size;
                // full-throttle plume = 3.2x the visible engine height (~19 m on a
                // Blasto 170, ~6 m on a Tiny 21); throttle and jitter keep it alive
                float len = p.type.height * (1.0f + 2.2f * lvl) * (ion ? 0.8f : 1f)
                        * (0.85f + 0.3f * (float) Math.random());
                float half = nozzleW * 0.5f * (0.85f + 0.3f * (float) Math.random());
                if (ion) {
                    flameCone(nozzle, dx, dy, len, half, 1.0f, 2.5f, 0.45f, 0.70f, 1f, 0.20f);
                    flameCone(nozzle, dx, dy, len, half, 0.8f, 1.7f, 0.55f, 0.80f, 1f, 0.55f);
                    flameCone(nozzle, dx, dy, len, half, 0.5f, 1.0f, 0.90f, 0.97f, 1f, 0.85f);
                } else {
                    flameCone(nozzle, dx, dy, len, half, 1.0f, 2.5f, 0.40f, 0.60f, 1f, 0.18f);
                    flameCone(nozzle, dx, dy, len, half, 0.8f, 1.7f, 1f, 0.55f, 0.15f, 0.85f);
                    flameCone(nozzle, dx, dy, len, half, 0.5f, 1.0f, 1f, 0.95f, 0.80f, 0.95f);
                }
            }
        }
        game.shapes.end();
    }

    /** One flame layer: triangle with its apex at the nozzle, widening toward the plume end. */
    private void flameCone(Vector2 nozzle, float dx, float dy, float len, float half,
                           float lenF, float widF, float r, float g, float b, float a) {
        float cx = nozzle.x + dx * len * lenF, cy = nozzle.y + dy * len * lenF;
        float px = -dy * half * widF, py = dx * half * widF;
        game.shapes.setColor(r, g, b, a);
        game.shapes.triangle(nozzle.x, nozzle.y, cx + px, cy + py, cx - px, cy - py);
    }

    /**
     * SimpleRockets-style steering ring at screen center (item 4): ring,
     * current heading marker (white tick), target heading marker (green tick),
     * and an error arc between them (yellow). Dragging on/near the ring sets
     * the target heading; the PI controller steers the ship toward it.
     */
    private void drawSteeringRing() {
        if (game.world.active == null) return;
        // round 20: the ring is ALWAYS visible (landed included). INACTIVE
        // (SteeringIO.ringActive == false): a dim semi-transparent gray ring
        // with just the white current-heading tick — no green target marker,
        // no error arc, no velocity vector. ACTIVE: the full steering style.
        boolean steering = SteeringIO.ringActive;
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        ringX = w / 2f;
        ringY = h / 2f;
        ringR = Math.min(w, h) * 0.26f;

        game.shapes.setProjectionMatrix(ringMat.setToOrtho2D(0, 0, w, h));
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        // sandbox 2x: thicker ring lines (best effort — some GLES drivers cap
        // glLineWidth at 1; the doubled markers below still carry the upscale)
        Gdx.gl.glLineWidth(2f * BS); // sandbox 2x ring thickness

        double cur = game.world.currentHeading();
        double tgt = game.world.getTargetHeading();

        if (!steering) {
            // inactive: gray ring + white current-heading tick only
            game.shapes.begin(ShapeRenderer.ShapeType.Line);
            game.shapes.setColor(0.7f, 0.7f, 0.72f, 0.22f);
            game.shapes.circle(ringX, ringY, ringR, 72);
            game.shapes.setColor(1f, 1f, 1f, 0.55f);
            game.shapes.line(ringX + (ringPtX(cur) - ringX) * 0.82f, ringY + (ringPtY(cur) - ringY) * 0.82f,
                    ringPtX(cur), ringPtY(cur));
            game.shapes.end();
            game.shapes.begin(ShapeRenderer.ShapeType.Filled);
            game.shapes.setColor(1f, 1f, 1f, 0.30f);
            game.shapes.circle(ringX, ringY, 4f, 12);
            game.shapes.setColor(1f, 1f, 1f, 0.6f);
            game.shapes.circle(ringPtX(cur), ringPtY(cur), 6f, 12);
            game.shapes.end();
            Gdx.gl.glLineWidth(1f);
            return;
        }

        // item 3: ship velocity vectors on the ring. Task D6 (revised):
        // the planet-relative velocity (cyan) is ALWAYS shown; when a map
        // target is selected the target-relative velocity (pink) is drawn
        // ALONGSIDE it — blue and pink arrows coexist.
        Ship actShip = game.world.active;
        Vec2d sv = actShip.getUniverseVel();
        Planet vcp = game.world.currentPlanet();
        double pvx = sv.x - (vcp != null ? vcp.vel.x : 0);
        double pvy = sv.y - (vcp != null ? vcp.vel.y : 0);
        double pSpd = Math.hypot(pvx, pvy);
        double pHead = Math.atan2(-pvx, pvy); // ring heading convention
        boolean relToTarget = mapTargetShip != null || mapTargetPlanet != null;
        double tvx = 0, tvy = 0;
        if (mapTargetShip != null) {
            Vec2d tv = mapTargetShip.getUniverseVel();
            tvx = sv.x - tv.x; tvy = sv.y - tv.y;
        } else if (mapTargetPlanet != null) {
            tvx = sv.x - mapTargetPlanet.vel.x; tvy = sv.y - mapTargetPlanet.vel.y;
        }
        double tSpd = Math.hypot(tvx, tvy);
        double tHead = Math.atan2(-tvx, tvy);

        game.shapes.begin(ShapeRenderer.ShapeType.Line);
        game.shapes.setColor(1f, 1f, 1f, 0.30f);
        game.shapes.circle(ringX, ringY, ringR, 72);
        // error arc from current to target (shortest way around)
        double err = tgt - cur;
        err = (err + Math.PI * 3) % (Math.PI * 2) - Math.PI;
        int segs = 28;
        double prevA = cur;
        float prevX = ringPtX(prevA), prevY = ringPtY(prevA);
        game.shapes.setColor(1f, 0.85f, 0.2f, 0.75f);
        for (int i = 1; i <= segs; i++) {
            double a = cur + err * i / segs;
            float x = ringPtX(a), y = ringPtY(a);
            game.shapes.line(prevX, prevY, x, y);
            prevX = x; prevY = y;
        }
        // current heading marker (white radial tick)
        game.shapes.setColor(1f, 1f, 1f, 0.9f);
        game.shapes.line(ringX + (ringPtX(cur) - ringX) * 0.82f, ringY + (ringPtY(cur) - ringY) * 0.82f,
                ringPtX(cur), ringPtY(cur));
        // target heading marker (green tick, slightly outside)
        game.shapes.setColor(0.3f, 1f, 0.45f, 0.95f);
        game.shapes.line(ringPtX(tgt), ringPtY(tgt),
                ringX + (ringPtX(tgt) - ringX) * 1.14f, ringY + (ringPtY(tgt) - ringY) * 1.14f);
        // velocity ticks: cyan = planet-relative (always), pink = target-relative
        if (pSpd > 0.5) {
            game.shapes.setColor(0.35f, 0.85f, 1f, 0.95f);
            game.shapes.line(ringX + (ringPtX(pHead) - ringX) * 0.90f, ringY + (ringPtY(pHead) - ringY) * 0.90f,
                    ringX + (ringPtX(pHead) - ringX) * 1.05f, ringY + (ringPtY(pHead) - ringY) * 1.05f);
        }
        if (relToTarget && tSpd > 0.5) {
            game.shapes.setColor(1f, 0.45f, 0.80f, 0.95f);
            game.shapes.line(ringX + (ringPtX(tHead) - ringX) * 0.90f, ringY + (ringPtY(tHead) - ringY) * 0.90f,
                    ringX + (ringPtX(tHead) - ringX) * 1.05f, ringY + (ringPtY(tHead) - ringY) * 1.05f);
        }
        game.shapes.end();

        // center cross
        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        game.shapes.setColor(1f, 1f, 1f, 0.5f);
        game.shapes.circle(ringX, ringY, 4f, 12);
        game.shapes.setColor(0.3f, 1f, 0.45f, 0.95f);
        game.shapes.circle(ringX + (ringPtX(tgt) - ringX) * 1.14f, ringY + (ringPtY(tgt) - ringY) * 1.14f, 8f, 12);
        game.shapes.setColor(1f, 1f, 1f, 0.95f);
        game.shapes.circle(ringPtX(cur), ringPtY(cur), 6f, 12);
        // velocity arrowheads on the ring — item 3 (cyan + pink, D6 revised)
        if (pSpd > 0.5) velArrow(pHead, 0.35f, 0.85f, 1f);
        if (relToTarget && tSpd > 0.5) velArrow(tHead, 1f, 0.45f, 0.80f);
        game.shapes.end();
        Gdx.gl.glLineWidth(1f);

        // numeric speed readouts outside the ring: cyan planet-relative at
        // its heading, pink target-relative slightly further out (D6 revised)
        boolean anyLabel = pSpd > 0.5 || (relToTarget && tSpd > 0.5);
        if (anyLabel) {
            game.batch.setProjectionMatrix(ringMat);
            game.batch.begin();
            game.font.getData().setScale(BS);
            if (pSpd > 0.5) {
                game.font.setColor(0.35f, 0.85f, 1f, 0.95f);
                float tx = ringX + (float) -Math.sin(pHead) * ringR * 1.22f;
                float ty = ringY + (float) Math.cos(pHead) * ringR * 1.22f;
                game.font.draw(game.batch, fmt(pSpd) + " m/s", tx, ty);
            }
            if (relToTarget && tSpd > 0.5) {
                game.font.setColor(1f, 0.45f, 0.80f, 0.95f);
                float tx = ringX + (float) -Math.sin(tHead) * ringR * 1.42f;
                float ty = ringY + (float) Math.cos(tHead) * ringR * 1.42f;
                game.font.draw(game.batch, fmt(tSpd) + " m/s", tx, ty);
            }
            game.font.getData().setScale(game.ui.fontScale);
            game.font.setColor(Color.WHITE);
            game.batch.end();
        }
    }

    /** Velocity arrowhead on the ring at the given heading, in the given color. */
    private void velArrow(double vHead, float r, float g, float b) {
        float bx = ringPtX(vHead), by = ringPtY(vHead);
        float ox = (bx - ringX) / ringR, oy = (by - ringY) / ringR; // unit outward
        float pxu = -oy, pyu = ox;                                  // unit tangent
        game.shapes.setColor(r, g, b, 0.95f);
        game.shapes.triangle(bx + ox * 24, by + oy * 24,
                bx - pxu * 12, by - pyu * 12,
                bx + pxu * 12, by + pyu * 12);
    }

    /** Ring point for a heading angle: nose dir is (-sinθ, cosθ), screen is y-up here. */
    private float ringPtX(double heading) { return ringX + (float) -Math.sin(heading) * ringR; }
    private float ringPtY(double heading) { return ringY + (float) Math.cos(heading) * ringR; }

    private void drawStars() {
        game.batch.getProjectionMatrix().setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        game.batch.begin();
        float px = -cam.position.x * 0.02f, py = -cam.position.y * 0.02f;
        for (float[] s : stars) {
            float x = (s[0] * Gdx.graphics.getWidth() + px) % Gdx.graphics.getWidth();
            float y = (s[1] * Gdx.graphics.getHeight() + py) % Gdx.graphics.getHeight();
            if (x < 0) x += Gdx.graphics.getWidth();
            if (y < 0) y += Gdx.graphics.getHeight();
            game.batch.setColor(1, 1, 1, s[2]);
            game.batch.draw(starTex, x, y, 2, 2);
        }
        game.batch.setColor(1, 1, 1, 1);
        game.batch.end();
    }

    // ---------------------------------------------------------------- map view

    private void renderMap() {
        Vec2d shipPos = game.world.active != null ? game.world.active.getUniversePos() : game.world.origin;
        // re-propagate at 15 Hz (round 18: was 4 Hz — users found the line
        // too stale; the re-anchor to live planet positions still happens
        // EVERY frame in drawOrbitPrediction, so the line is 60 Hz smooth)
        if (game.world.active != null) {
            orbitTimer += Gdx.graphics.getDeltaTime();
            if (orbitTimer > ORBIT_INTERVAL || predictor.count == 0) {
                orbitTimer = 0;
                predictor.compute(game.world, game.world.active, anchorIndex);
                // task 5: the tapped target ship's own orbit, same cadence/frame
                if (mapTargetShip != null && !mapTargetShip.parts.isEmpty()) {
                    targetPredictor.compute(game.world, mapTargetShip, anchorIndex);
                } else {
                    targetPredictor.count = 0;
                }
            }
        }
        // auto-fit on first open: center on the active ship, framed on the prediction
        if (!mapInit) {
            mapInit = true;
            mapCX = shipPos.x;
            mapCY = shipPos.y;
            autoFitMap();
            updateCamViewport();
        }
        // camera follows the selected anchor body (round 17): the user's
        // pan/pinch gestures keep editing mapCX/mapCY directly, we just add
        // the anchor body's frame-to-frame movement, so the net effect is
        // camera = body position + user offset. A body SWITCH records the
        // new reference without moving the camera (no jump).
        Planet fb = predictor.anchor >= 0 ? game.world.planets.get(predictor.anchor) : null;
        if (fb != null) {
            if (fb == lastAnchorBody) {
                mapCX += fb.pos.x - lastAnchorX;
                mapCY += fb.pos.y - lastAnchorY;
            }
            lastAnchorBody = fb;
            lastAnchorX = fb.pos.x;
            lastAnchorY = fb.pos.y;
        }
        // Round 18 jitter fix: the map camera sits at the ORIGIN and every
        // world-space draw subtracts the double-precision center (mapCX,
        // mapCY) BEFORE the float conversion. Universe coords (~1e10 m) in
        // float32 quantize to ~1 km — a float camera position plus float
        // vertices made every drawn thing (line, planets, labels) hop in
        // ~km steps. All deltas below are viewport-scale doubles -> floats.
        mapCam.position.set(0, 0, 0);
        mapCam.update();

        drawStars();

        game.shapes.setProjectionMatrix(mapCam.combined);
        float lw = mapCam.viewportHeight / 400f;

        // planet orbit rings + bodies
        for (Planet p : game.world.planets) {
            if (p.parent != null) {
                game.shapes.begin(ShapeRenderer.ShapeType.Line);
                game.shapes.setColor(0.3f, 0.35f, 0.5f, 0.8f);
                // sample ellipse in parent frame via the same Kepler solution over one period
                drawOrbitPath(p);
                game.shapes.end();
            }
        }
        // planet bodies (user request: simple & smooth): ONE translucent
        // filled circle per planet at the true radius — no outline ring, no
        // terrain polyline. Segment count adapts to the on-screen radius so
        // the circle stays perfectly round at ANY zoom (≈3 px per segment,
        // clamped [48, 720]). Positions relative to the double map center
        // (camera at origin, round 18).
        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        float pxPerMeter = Gdx.graphics.getHeight() / mapCam.viewportHeight;
        for (Planet p : game.world.planets) {
            Color c = p.mapColor;
            game.shapes.setColor(c.r, c.g, c.b, 0.35f);
            int segs = (int) Math.max(48, Math.min(720,
                    2 * Math.PI * p.radius * pxPerMeter / 3f));
            game.shapes.circle((float) (p.pos.x - mapCX), (float) (p.pos.y - mapCY),
                    (float) p.radius, segs);
        }
        game.shapes.end();


        // ship orbit prediction + markers
        if (game.world.active != null) {
            drawOrbitPrediction();
            drawTargetOrbit(); // task 5: gray orbit of the tapped target ship
            // round 14 fix: drawOrbitPrediction switches ShapeRenderer to the
            // SCREEN-space ortho — restore the map camera or the ship arrows
            // below are drawn at universe coords in pixel space (invisible).
            game.shapes.setProjectionMatrix(mapCam.combined);
        }
        game.shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Ship s : game.world.ships) {
            Vec2d sp = s.getUniversePos();
            float r = mapCam.viewportHeight * 0.01f;
            if (s == game.world.active) game.shapes.setColor(0.4f, 1f, 0.5f, 1f);
            else game.shapes.setColor(1f, 0.7f, 0.2f, 1f);
            // round 17: the arrow points along the ship's ATTITUDE (nose
            // direction, the control part's body angle — an inertial-frame
            // angle, so the map can use it directly). Nose dir convention
            // matches the steering ring: (-sinθ, cosθ). Ships on rails have
            // no bodies — fall back to the old velocity-relative direction.
            Part ref = s.controlPart();
            float dirx, diry;
            if (ref != null && ref.body != null) {
                double hd = ref.body.getAngle();
                dirx = (float) -Math.sin(hd);
                diry = (float) Math.cos(hd);
            } else {
                Planet cp = game.world.currentPlanet();
                Vec2d svl = s.getUniverseVel();
                double rvx = svl.x - (cp != null ? cp.vel.x : 0);
                double rvy = svl.y - (cp != null ? cp.vel.y : 0);
                dirx = 0; diry = 1;
                double sp2 = rvx * rvx + rvy * rvy;
                if (sp2 > 0.25) {
                    double inv = 1 / Math.sqrt(sp2);
                    dirx = (float) (rvx * inv);
                    diry = (float) (rvy * inv);
                }
            }
            float perx = -diry, pery = dirx;
            // relative to the double map center (camera at origin, round 18)
            float rx = (float) (sp.x - mapCX), ry = (float) (sp.y - mapCY);
            game.shapes.triangle(rx + dirx * r * 1.5f, ry + diry * r * 1.5f,
                    rx - dirx * r * 0.9f + perx * r, ry - diry * r * 0.9f + pery * r,
                    rx - dirx * r * 0.9f - perx * r, ry - diry * r * 0.9f - pery * r);
        }
        game.shapes.end();

        // planet + ship labels in SCREEN space (round 15): world-space font
        // scaling made them enormous/pixelated when zoomed out to the system
        // (250000x warp screenshots). Fixed pixel size at every zoom.
        double msw = Gdx.graphics.getWidth(), msh = Gdx.graphics.getHeight();
        game.batch.setProjectionMatrix(ringMat.setToOrtho2D(0, 0, (float) msw, (float) msh));
        game.batch.begin();
        game.font.getData().setScale(BS);
        game.font.setColor(1f, 1f, 1f, 0.85f);
        for (Planet p : game.world.planets) {
            // skip labels for bodies too small to see at this zoom
            if (p.radius / mapCam.viewportHeight < 0.0025) continue;
            float sx = (float) ((p.pos.x + p.radius - mapCX) / mapCam.viewportWidth * msw + msw / 2);
            float sy = (float) ((p.pos.y - mapCY) / mapCam.viewportHeight * msh + msh / 2);
            if (sx < -200 || sx > msw + 200 || sy < -50 || sy > msh + 50) continue;
            game.font.draw(game.batch, p.name, sx + 6, sy + 4);
        }
        game.font.setColor(0.6f, 1f, 0.7f, 0.9f);
        for (Ship s : game.world.ships) {
            Vec2d sp = s.getUniversePos();
            float sx = (float) ((sp.x - mapCX) / mapCam.viewportWidth * msw + msw / 2);
            float sy = (float) ((sp.y - mapCY) / mapCam.viewportHeight * msh + msh / 2);
            if (sx < -200 || sx > msw + 200 || sy < -50 || sy > msh + 50) continue;
            game.font.draw(game.batch, s.name, sx + 10, sy - 6);
        }
        game.font.getData().setScale(game.ui.fontScale);
        game.font.setColor(Color.WHITE);
        game.batch.end();

        drawMapTargetMarker(); // task D4: "C"/"X" closest-approach marker
    }

    /** Universe position of a planet at absolute time t (Kepler rails, parents chained). */
    private void planetPosAt(Planet p, double t, double[] out) {
        if (p.parent == null) { out[0] = 0; out[1] = 0; return; }
        planetPosAt(p.parent, t, out);
        double[] rel = orbitRelAt(p, t);
        out[0] += rel[0];
        out[1] += rel[1];
    }

    /**
     * Task D4: with a map target selected (tapped ship/planet), find the point
     * of the predicted orbit closest to the target and mark it — a circle
     * labelled "C"; when the closest gap is under 500 m the marker turns into
     * a purple "X" circle. Planet targets move along their Kepler rails during
     * the prediction (position evaluated per-point at ts[i]); ship targets
     * are approximated by their current position. All math in double against
     * (mapCX, mapCY), floats only in screen space (round-18 rule).
     */
    private void drawMapTargetMarker() {
        if (mapTargetShip == null && mapTargetPlanet == null) return;
        if (predictor.count < 1) return;
        double[] tpos = new double[2];
        double bestD = Double.MAX_VALUE;
        double bestX = 0, bestY = 0;
        for (int i = 0; i < predictor.count; i++) {
            if (mapTargetPlanet != null) {
                planetPosAt(mapTargetPlanet, predictor.ts[i], tpos);
            } else if (targetPredictor.count >= 2) {
                // moving ship anchor: interpolated onto the active timestamps
                tpos[0] = lerpTargetX(predictor.ts[i]);
                tpos[1] = lerpTargetY(predictor.ts[i]);
            } else {
                Vec2d tp = mapTargetShip.getUniversePos();
                tpos[0] = tp.x; tpos[1] = tp.y;
            }
            double dx = predictor.xs[i] - tpos[0], dy = predictor.ys[i] - tpos[1];
            double d = Math.sqrt(dx * dx + dy * dy);
            if (mapTargetPlanet != null) d -= mapTargetPlanet.radius; // gap to the surface
            if (d < bestD) { bestD = d; bestX = predictor.xs[i]; bestY = predictor.ys[i]; }
        }
        double sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        float sx = (float) ((bestX - mapCX) / mapCam.viewportWidth * sw + sw / 2);
        float sy = (float) ((bestY - mapCY) / mapCam.viewportHeight * sh + sh / 2);
        boolean close = bestD < 500;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        game.shapes.setProjectionMatrix(ringMat.setToOrtho2D(0, 0, (float) sw, (float) sh));
        game.shapes.begin(ShapeRenderer.ShapeType.Line);
        if (close) game.shapes.setColor(0.75f, 0.35f, 1f, 0.95f); // purple
        else game.shapes.setColor(0.55f, 0.95f, 1f, 0.95f);        // light cyan
        game.shapes.circle(sx, sy, 34f, 32);
        game.shapes.end();
        game.batch.setProjectionMatrix(ringMat);
        game.batch.begin();
        game.font.getData().setScale(BS);
        if (close) game.font.setColor(0.75f, 0.35f, 1f, 1f);
        else game.font.setColor(0.55f, 0.95f, 1f, 1f);
        // center the letter in the circle (glyph ~20x28 px at sandbox scale)
        game.font.draw(game.batch, close ? "X" : "C", sx - 10, sy + 14);
        game.font.getData().setScale(game.ui.fontScale);
        game.font.setColor(Color.WHITE);
        game.batch.end();
    }

    private void drawOrbitPath(Planet p) {
        // sample one full period using Kepler's equation at N time steps;
        // positions relative to the double map center (camera at origin, round 18)
        double muP = p.parent.mu();
        double n = Math.sqrt(muP / (p.a * p.a * p.a));
        double period = 2 * Math.PI / n;
        int N = 96;
        float prevX = 0, prevY = 0;
        for (int i = 0; i <= N; i++) {
            double t = game.world.time + period * i / N;
            // compute planet position relative to parent at time t (reuse rails math via a fresh solve)
            double[] rel = orbitRelAt(p, t);
            float x = (float) (p.parent.pos.x + rel[0] - mapCX);
            float y = (float) (p.parent.pos.y + rel[1] - mapCY);
            if (i > 0) game.shapes.line(prevX, prevY, x, y);
            prevX = x;
            prevY = y;
        }
    }

    /** position relative to parent at absolute time t (mirrors Planet.localPosVel). */
    private double[] orbitRelAt(Planet p, double t) {
        double muP = p.parent.mu();
        double n = Math.sqrt(muP / (p.a * p.a * p.a));
        double M = n * t + p.v0;
        if (!p.prograde) M = -M;
        // round 14: wrap M to [-pi, pi] — Newton from E=M diverges for large
        // M (big world.time after long warps) and high eccentricity, folding
        // the drawn orbit/trajectory back on itself.
        M = (M + Math.PI) % (2 * Math.PI);
        if (M < 0) M += 2 * Math.PI;
        M -= Math.PI;
        double E = M + p.e * Math.sin(M);
        for (int i = 0; i < 12; i++) E = E - (E - p.e * Math.sin(E) - M) / (1 - p.e * Math.cos(E));
        double xp = p.a * (Math.cos(E) - p.e);
        double yp = p.a * Math.sqrt(1 - p.e * p.e) * Math.sin(E);
        double cw = Math.cos(p.w), sw = Math.sin(p.w);
        return new double[]{xp * cw - yp * sw, xp * sw + yp * cw};
    }

    /**
     * Item 10 / round 13 item 1: render the numerically propagated trajectory
     * in TWO segments — a solid near-term line (first ~30% of the points)
     * followed by a long-term line whose alpha fades per segment from 0.95 to
     * zero at the tail. Decimated to <= 2000 GL points.
     *
     * Round 13 fixes:
     *  - re-anchored EVERY rendered frame: the offset-chained polyline
     *    (xs-fx+off, continuous by construction) is shifted by how far run 0's
     *    body moved since the propagation started, so the line tracks the
     *    planet at 60 Hz with no 2 Hz jump;
     *  - world->screen projection is computed in DOUBLE precision against the
     *    double map center and emitted as floats only in screen space — no
     *    float32 jitter/gaps at universe coords ~1e10 m;
     *  - frame-boundary holes are gone: the run offsets chain across dominant-
     *    body transitions, so the polyline is continuous end to end.
     */
    private void drawOrbitPrediction() {
        int n = predictor.count;
        if (n < 2 || predictor.anchor < 0) return;
        // ONE frame for the whole polyline (round 15), the body picked via
        // the anchor list (round 17) — predictor.anchor resolves the
        // explicit selection or the automatic dominant body. The raw
        // inertial path is translated into that body's CURRENT frame;
        // continuous by construction within a frame, a whole-line
        // translation on switches (expected).
        Planet a0 = game.world.planets.get(predictor.anchor);
        double baseX = a0.pos.x, baseY = a0.pos.y;
        int stride = Math.max(1, (n + 1999) / 2000);
        int drawn = (n + stride - 1) / stride;
        if (drawn < 2) return;
        int solid = Math.max(1, (int) (drawn * 0.3));
        double sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        game.shapes.setProjectionMatrix(ringMat.setToOrtho2D(0, 0, (float) sw, (float) sh));
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        game.shapes.begin(ShapeRenderer.ShapeType.Line);
        game.shapes.setColor(0.4f, 0.9f, 0.5f, 0.95f);
        float prevX = 0, prevY = 0;
        for (int i = 0; i < drawn; i++) {
            int idx = Math.min(i * stride, n - 1);
            // anchor-relative world position (doubles end to end)
            double wx = predictor.xs[idx] - predictor.fx[idx] + baseX;
            double wy = predictor.ys[idx] - predictor.fy[idx] + baseY;
            float sx = (float) ((wx - mapCX) / mapCam.viewportWidth * sw + sw / 2);
            float sy = (float) ((wy - mapCY) / mapCam.viewportHeight * sh + sh / 2);
            if (i > 0) {
                if (i > solid) {
                    // long-term: per-segment linear alpha fade to zero at the tail
                    float f2 = (i - solid) / (float) (drawn - solid);
                    game.shapes.setColor(0.4f, 0.9f, 0.5f, 0.95f * (1f - f2));
                }
                game.shapes.line(prevX, prevY, sx, sy);
            }
            prevX = sx; prevY = sy;
        }
        game.shapes.end();
    }

    /**
     * Task 5 (revised semantics): the gray line is the ACTIVE ship's predicted
     * trajectory RE-ANCHORED to the target ship — i.e. the active ship's
     * motion relative to the target, exactly like the planet anchor-frame
     * switch but with the target ship as the anchor:
     *   gray[i] = activePath(ts[i]) − targetPath(ts[i]) + targetShip.posNow
     * The active path comes from the main predictor (its green line is drawn
     * unchanged in the planet frame); the target path comes from
     * targetPredictor (filled in renderMap at the same 15 Hz cadence), and
     * because the two propagators use different adaptive step schedules the
     * target position is LINEARLY INTERPOLATED onto the active path's
     * timestamps. A target with no propagable path (landed/empty) acts as a
     * static anchor at its current position. Same double-precision screen
     * projection rules as drawOrbitPrediction. OrbitPredictor unchanged.
     */
    private void drawTargetOrbit() {
        if (mapTargetShip == null) return;
        int n = predictor.count;
        if (n < 2) return;
        Vec2d anchorNow = mapTargetShip.getUniversePos();
        tLerpHint = 0; // ts walk is ascending within one draw pass
        int stride = Math.max(1, (n + 1999) / 2000);
        int drawn = (n + stride - 1) / stride;
        if (drawn < 2) return;
        double sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        game.shapes.setProjectionMatrix(ringMat.setToOrtho2D(0, 0, (float) sw, (float) sh));
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        game.shapes.begin(ShapeRenderer.ShapeType.Line);
        game.shapes.setColor(0.62f, 0.62f, 0.68f, 0.8f);
        float prevX = 0, prevY = 0;
        for (int i = 0; i < drawn; i++) {
            int idx = Math.min(i * stride, n - 1);
            // target ship position at this point's time (moving anchor)
            double tax, tay;
            if (targetPredictor.count >= 2) {
                tax = lerpTargetX(predictor.ts[idx]);
                tay = lerpTargetY(predictor.ts[idx]);
            } else {
                tax = anchorNow.x; tay = anchorNow.y;
            }
            double wx = predictor.xs[idx] - tax + anchorNow.x;
            double wy = predictor.ys[idx] - tay + anchorNow.y;
            float sx = (float) ((wx - mapCX) / mapCam.viewportWidth * sw + sw / 2);
            float sy = (float) ((wy - mapCY) / mapCam.viewportHeight * sh + sh / 2);
            if (i > 0) game.shapes.line(prevX, prevY, sx, sy);
            prevX = sx; prevY = sy;
        }
        game.shapes.end();
    }

    // target-path interpolation scratch (drawTargetOrbit walks ts ascending)
    private int tLerpHint;

    /** Linear interpolation over targetPredictor's (ts, xs | ys) at time t. */
    private double lerpTargetX(double t) { return lerpTarget(t, true); }
    private double lerpTargetY(double t) { return lerpTarget(t, false); }
    private double lerpTarget(double t, boolean x) {
        int m = targetPredictor.count;
        double[] vs = x ? targetPredictor.xs : targetPredictor.ys;
        double[] ts = targetPredictor.ts;
        if (t <= ts[0]) return vs[0];
        if (t >= ts[m - 1]) return vs[m - 1];
        int lo = Math.min(Math.max(tLerpHint, 0), m - 2);
        if (t < ts[lo]) lo = 0;
        while (lo < m - 2 && ts[lo + 1] < t) lo++;
        tLerpHint = lo;
        double f = (t - ts[lo]) / (ts[lo + 1] - ts[lo]);
        return vs[lo] + (vs[lo + 1] - vs[lo]) * f;
    }

    // ---------------------------------------------------------------- telemetry

    private void updateTelemetry() {
        refreshWarpLabel();
        Ship s = game.world.active;
        if (s == null) {
            telemetry.setText("No active ship");
            return;
        }
        Vec2d sp = s.getUniversePos();
        Vec2d sv = s.getUniverseVel();
        double alt = game.world.altitudeAt(sp.x, sp.y);
        Planet cp = game.world.currentPlanet();
        double speed = cp != null
                ? Math.hypot(sv.x - cp.vel.x, sv.y - cp.vel.y) // surface-relative
                : sv.len();
        double fuel = s.fuelTotal(0);
        double mono = s.fuelTotal(1);
        double elec = s.fuelTotal(2);
        telemetry.setText(
                "ALT " + fmt(alt) + " m   SPD " + fmt(speed) + " m/s\n" +
                "BODY " + (cp != null ? cp.name : "-") + (s.landed ? "  [landed]" : "") + "\n" +
                "FUEL " + fmt(fuel) + "  MONO " + fmt(mono) + "  BATT " + fmt(elec) + "\n" +
                "THR " + (int) (game.world.inputThrottle * 100) + "%   WARP " + game.world.warp + "x" +
                (game.world.paused ? "   [PAUSED]" : "") +
                (mapMode ? "   [MAP]" : ""));

        // item 5 (round 9): a tap-selected tank/SRB/battery shows its live
        // numeric level in the selection readout
        if (selectedPart != null && selectedPart.body != null
                && selectedPart.getFuelCapacity() > 0
                && s.parts.contains(selectedPart)) {
            int ft = selectedPart.getFuelType();
            String unit = ft == 2 ? "CHARGE" : ft == 3 ? "SOLID" : "FUEL";
            stageLabel.setText("Selected " + selectedPart.type.name
                    + (selectedPart.group > 0 ? " [group " + selectedPart.group + "]" : "")
                    + "  —  " + unit + " " + String.format("%.0f / %.0f",
                    selectedPart.getFuel(), selectedPart.getFuelCapacity()));
        }
    }

    /** Diagnostic: current selection/status line (smoke tests). */
    public String stageLabelForTest() { return stageLabel.getText().toString(); }

    private static String fmt(double v) {
        if (Math.abs(v) >= 1e6) return String.format("%.2fM", v / 1e6);
        if (Math.abs(v) >= 1e3) return String.format("%.1fk", v / 1e3);
        return String.format("%.0f", v);
    }

    @Override
    public void resize(int w, int h) {
        if (stage != null) stage.getViewport().update(w, h, true);
        updateCamViewport();
    }

    @Override
    public void dispose() {
        if (stage != null) stage.dispose();
        starTex.dispose();
        atmoTex.dispose();
    }
}
