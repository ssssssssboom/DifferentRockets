package com.differentrockets.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.differentrockets.game.Attach;
import com.differentrockets.game.DRGame;
import com.differentrockets.game.PartList;
import com.differentrockets.game.PartType;
import com.differentrockets.game.Planet;
import com.differentrockets.game.ShipDesign;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rocket build editor: part palette, drag-and-drop assembly with attach-point
 * snapping, 90-degree rotation, deletion, stage list, ship save/load, Launch.
 */
public class EditorScreen extends ScreenAdapter {

    private final DRGame game;
    private ShipDesign design = new ShipDesign();
    private String shipName = "Untitled";

    // scaled editor chrome constants (task C1: ~1.35x the original 64px bar)
    private static final int TOP_H = 88;
    private static final int BTN_W = 190;
    private static final int BTN_H = 88;
    private static final int ROW_H = 76;

    private Stage stage;
    private OrthographicCamera cam;
    private final InputAdapter editorInput = new EditorInput();
    private Table canvasArea;         // the only stage region treated as canvas by EditorInput
    private final List<PaletteItem> paletteItems = new ArrayList<>();
    private ScrollPane paletteScroll; // palette list; scrolling is suspended mid drag-out
    private PartType dragOutType;     // non-null while a palette drag-out gesture is live
    private int dragOutPointer = -1;
    private float dragScrX, dragScrY; // last known screen pos of the drag-out finger

    // drawers (task C2): 0 = none, 1 = menu, 2 = add part, 3 = stages
    private int openDrawer = 0;
    private float drawerW;
    private Table drawerMenu, drawerParts, drawerStages;
    private Table menuShipList;       // saved-ship buttons inside the menu drawer
    private Table stageListTable;     // stage sections inside the stages drawer
    private TextField nameField;      // rename field inside the menu drawer
    private Label nameLabel;          // ship name in the top bar
    private TextButton delButton;     // floating delete button (selection only)
    // stage-drawer drag targets: header actors parallel to their stage numbers
    private final List<Actor> stageHeaders = new ArrayList<>();
    private final List<Integer> stageHeaderNums = new ArrayList<>();
    // stage-drawer part rows (parallel lists) for RAW drag-to-assign (issue 2):
    // the ScrollPane steals vertical drags mid-gesture in this gdx version (same
    // class of bug as the palette drag-out), so row drags are intercepted raw,
    // in front of the stage, exactly like palette drag-outs.
    private final List<Actor> stageRows = new ArrayList<>();
    private final List<Integer> stageRowParts = new ArrayList<>();
    private int stageRowCandidate = -1;  // row hit at touch-down, not yet dragging
    private int stageRowDrag = -1;       // row actively being dragged (index into stageRows)

    // build-operation history (task C3): JSON snapshots of the design
    private final List<String> undoStack = new ArrayList<>();
    private final List<String> redoStack = new ArrayList<>();
    private static final int HISTORY_MAX = 60;

    // dragging state
    private PartType placing;         // palette part being placed
    private int dragIndex = -1;       // existing part being dragged
    private float dragX, dragY;       // current ghost position (world)
    private int dragRot;              // rotation of ghost
    private boolean panning;
    private float panLastX, panLastY;
    // two-finger gesture state (item 2): A = first finger, B = second finger
    private int touchPtrA = -1, touchPtrB = -1;
    private float gpaX, gpaY, gpbX, gpbY;
    private Label statusLabel;
    private Table overlay;            // modal overlays (launch picker)

    // activation groups (item 6a): multi-select + group assignment
    private final Set<Integer> selected = new HashSet<>();
    private int downIndex = -1;       // part under touch-down (tap=select, drag=move)
    private float downScrX, downScrY;
    private boolean dragMoved;

    public EditorScreen(DRGame game, ShipDesign existing) {
        this.game = game;
        if (existing != null) this.design = existing;
        else restoreAutosave(); // task C6: resume the last build session
        // every new rocket starts with a command pod
        if (this.design.parts.isEmpty()) {
            this.design.parts.add(new ShipDesign.DesignPart("pod-1", 0, 0, 0));
            this.design.autoStage();
        }
    }

    // ------------------------------------------------------------ autosave (C6)

    private void autosave() {
        try {
            com.badlogic.gdx.files.FileHandle dir = Gdx.files.local("save");
            dir.mkdirs();
            dir.child("editor_autosave.json").writeString(design.toJson(), false);
            dir.child("editor_autosave.name").writeString(shipName, false);
        } catch (Exception e) {
            Gdx.app.log("editor", "autosave failed: " + e.getMessage());
        }
    }

    private void restoreAutosave() {
        try {
            com.badlogic.gdx.files.FileHandle f = Gdx.files.local("save/editor_autosave.json");
            if (!f.exists()) return;
            ShipDesign d = ShipDesign.fromJson(f.readString());
            if (d.parts.isEmpty()) return;
            this.design.copyFrom(d);
            com.badlogic.gdx.files.FileHandle n = Gdx.files.local("save/editor_autosave.name");
            if (n.exists()) {
                String nm = n.readString().trim();
                if (!nm.isEmpty()) this.shipName = nm;
            }
        } catch (Exception e) {
            Gdx.app.log("editor", "autosave restore failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ undo/redo (C3)

    /** Snapshot the design BEFORE a mutating build operation. */
    private void pushHistory() {
        undoStack.add(design.toJson());
        if (undoStack.size() > HISTORY_MAX) undoStack.remove(0);
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) { status("Nothing to undo"); return; }
        redoStack.add(design.toJson());
        applySnapshot(undoStack.remove(undoStack.size() - 1));
        status("Undo (" + undoStack.size() + " more)");
    }

    private void redo() {
        if (redoStack.isEmpty()) { status("Nothing to redo"); return; }
        undoStack.add(design.toJson());
        applySnapshot(redoStack.remove(redoStack.size() - 1));
        status("Redo");
    }

    private void applySnapshot(String json) {
        try {
            design.copyFrom(ShipDesign.fromJson(json));
        } catch (Exception e) {
            status("History restore failed: " + e.getMessage());
            return;
        }
        selected.clear();
        rebuildStageList();
        updateDelButton();
    }

    public ShipDesign getDesign() { return design; }

    // ---- smoke-test hooks (item 5 tap verification) ----
    public PartType getPlacing() { return placing; }
    public boolean isSelected(int index) { return selected.contains(index); }
    public void cancelPlacing() { placing = null; }

    private int[] actorScreenPos(Actor a) {
        if (a == null || a.getStage() == null) return null;
        com.badlogic.gdx.math.Vector2 v = a.localToStageCoordinates(
                new com.badlogic.gdx.math.Vector2(a.getWidth() / 2f, a.getHeight() / 2f));
        v = stage.stageToScreenCoordinates(v);
        return new int[]{Math.round(v.x), Math.round(v.y)};
    }
    public int[] paletteItemScreenPos(int i) {
        return i < paletteItems.size() ? actorScreenPos(paletteItems.get(i)) : null;
    }
    public int[] groupButtonScreenPos(int g) {
        // group buttons moved into the stages drawer (task C5); use the header row
        for (int i = 0; i < stageHeaderNums.size(); i++) {
            if (stageHeaderNums.get(i) == g) return actorScreenPos(stageHeaders.get(i));
        }
        return null;
    }
    public int[] partScreenPos(int designIndex) {
        if (designIndex >= design.parts.size()) return null;
        ShipDesign.DesignPart dp = design.parts.get(designIndex);
        Vector3 v3 = new Vector3(dp.x, dp.y, 0);
        cam.project(v3); // libGDX project() yields y-UP; input handlers want y-DOWN
        return new int[]{Math.round(v3.x), Math.round(Gdx.graphics.getHeight() - v3.y)};
    }
    /** Diagnostic for tap routing: which stage actor and which part a screen point maps to. */
    public String hitInfo(int sx, int sy) {
        Actor a = stage.hit(sx, Gdx.graphics.getHeight() - sy, true);
        Vector2 w = screenToWorld(sx, sy);
        int idx = partAt(w);
        return "stageHit=" + (a == null ? "null" : a.getClass().getSimpleName()
                + (a == canvasArea ? "(canvas)" : ""))
                + " world=(" + String.format("%.2f", w.x) + "," + String.format("%.2f", w.y) + ")"
                + " partAt=" + idx;
    }

    @Override
    public void show() {
        cam = new OrthographicCamera();
        cam.viewportHeight = 40;
        cam.viewportWidth = 40f * Gdx.graphics.getWidth() / Gdx.graphics.getHeight();
        // full-screen canvas (drawers slide over it): center the rocket
        cam.position.set(0, -3, 0);
        cam.update();

        stage = new Stage(new ScreenViewport());
        buildChrome();
        rebuildStageList();

        InputMultiplexer mux = new InputMultiplexer();
        // Palette drag-out (item 4) is intercepted RAW, before the stage: the
        // ScrollPane steals/cancels Scene2D touch focus mid-drag (and capture
        // listeners don't fire for focus-routed events in this gdx version),
        // so neither the row's nor stage-level listeners can complete the
        // gesture reliably. This processor only consumes events once a
        // horizontal drag-out has actually started; taps and vertical scroll
        // gestures flow to the stage untouched.
        mux.addProcessor(dragOutInterceptor);
        mux.addProcessor(stage);
        mux.addProcessor(editorInput);
        Gdx.input.setInputProcessor(mux);
    }

    private final DragOutInterceptor dragOutInterceptor = new DragOutInterceptor();

    /**
     * Palette drag-out interceptor: sits in FRONT of the stage and only consumes
     * events once a horizontal drag-out has actually started. POINTER-LIFECYCLE
     * CRITICAL (item 8): the palette row took Scene2D touch focus at touch-down;
     * once this interceptor owns the gesture it swallows the release touchUp, so
     * beginDragOut() must stage.cancelTouchFocus() — otherwise the stale focus
     * fires a phantom row touchUp on the NEXT gesture (arming a ghost
     * placement) AND the stage swallows that touchUp, so EditorInput never
     * finishes its own gesture (leaked touchPtrA turns every later gesture into
     * a ghost two-finger op). That cascade was the round-8
     * "input dead after drag-out" bug.
     */
    private class DragOutInterceptor extends InputAdapter {
        private PartType candidate;
        private int downX, downY;

        void reset() {
            candidate = null;
            stageRowCandidate = -1;
            endStageRowDragVisual();
            stageRowDrag = -1;
        }

        @Override public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (pointer != 0 || dragOutType != null || stageRowDrag != -1) return false;
            candidate = paletteRowAt(screenX, screenY);
            stageRowCandidate = stageRowAt(screenX, screenY);
            downX = screenX; downY = screenY;
            return false; // never consume the press: tap/scroll need it
        }

        @Override public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (pointer != 0) return false;
            if (stageRowDrag != -1) {
                return true; // ours now: the ScrollPane/stage must not steal this drag
            }
            if (dragOutType != null) {
                dragScrX = screenX; dragScrY = screenY;
                return true; // ours now: the pane/stage must not see this drag
            }
            if (stageRowCandidate != -1) {
                // any direction counts: assigning to another STAGE is mostly a
                // VERTICAL drag, which the ScrollPane would steal from a scene2d
                // listener (issue 2)
                if (Math.hypot(screenX - downX, screenY - downY) > 14) {
                    stageRowDrag = stageRowCandidate;
                    stageRowCandidate = -1;
                    // the stage focused the row at touch-down; drop that focus so
                    // the pane cannot cancel us and no phantom row tap fires later
                    stage.cancelTouchFocus();
                    Actor row = stageRows.get(stageRowDrag);
                    row.setColor(1f, 1f, 0.6f, 1f); // drag feedback
                    int part = stageRowParts.get(stageRowDrag);
                    PartType t = part < design.parts.size() ? PartList.get(design.parts.get(part).typeId) : null;
                    status("Drag " + (t != null ? t.name : "part") + " onto a STAGE header");
                    return true;
                }
            }
            if (candidate != null) {
                float dx = screenX - downX, dy = screenY - downY;
                if (Math.abs(dx) > 14 && Math.abs(dx) > Math.abs(dy)) {
                    PartType t = candidate;
                    candidate = null;
                    beginDragOut(t, pointer, screenX, screenY);
                    return true;
                }
            }
            return false;
        }

        @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            candidate = null;
            stageRowCandidate = -1;
            if (pointer != 0) return false;
            if (stageRowDrag != -1) {
                finishStageRowDrag(screenX, screenY);
                return true; // release consumed: stage never sees it
            }
            if (dragOutType == null) return false;
            finishDragOut(screenX, screenY);
            return true; // release consumed: stage never sees it
        }

        @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
            candidate = null;
            stageRowCandidate = -1;
            if (stageRowDrag != -1) {
                endStageRowDragVisual();
                stageRowDrag = -1;
                return true;
            }
            if (dragOutType != null) {
                dragOutType = null;
                placing = null;
                return true;
            }
            return false;
        }
    }

    /** Index (into stageRows) of the stage-drawer part row under a screen point. */
    private int stageRowAt(float screenX, float screenY) {
        if (openDrawer != 3) return -1;
        float stageY = Gdx.graphics.getHeight() - screenY;
        com.badlogic.gdx.math.Vector2 tmpA = new com.badlogic.gdx.math.Vector2();
        com.badlogic.gdx.math.Vector2 tmpB = new com.badlogic.gdx.math.Vector2();
        for (int i = 0; i < stageRows.size(); i++) {
            Actor row = stageRows.get(i);
            if (row.getStage() == null) continue;
            row.localToStageCoordinates(tmpA.set(0, 0));
            row.localToStageCoordinates(tmpB.set(row.getWidth(), row.getHeight()));
            if (screenX >= tmpA.x && screenX <= tmpB.x && stageY >= tmpA.y && stageY <= tmpB.y) {
                return i;
            }
        }
        return -1;
    }

    private void endStageRowDragVisual() {
        if (stageRowDrag != -1 && stageRowDrag < stageRows.size()) {
            stageRows.get(stageRowDrag).setColor(1f, 1f, 1f, 1f);
        }
    }

    /** Drop a dragged stage-drawer row: assign its part to the header under the finger. */
    private void finishStageRowDrag(float screenX, float screenY) {
        int rowIdx = stageRowDrag;
        endStageRowDragVisual();
        stageRowDrag = -1;
        stage.cancelTouchFocus(); // defensive (same class of leak as item 8)
        if (rowIdx >= stageRowParts.size()) return;
        int part = stageRowParts.get(rowIdx);
        if (part >= design.parts.size()) return;
        com.badlogic.gdx.math.Vector2 sp = stage.screenToStageCoordinates(
                new com.badlogic.gdx.math.Vector2(screenX, Gdx.graphics.getHeight() - screenY));
        Integer target = stageHeaderAt(sp.x, sp.y);
        PartType t = PartList.get(design.parts.get(part).typeId);
        if (target == null) {
            status("Drop cancelled — release over a STAGE header");
            return;
        }
        if (design.parts.get(part).group == target) {
            status("Already in " + (target == 0 ? "Unassigned" : "STAGE " + target));
            return;
        }
        pushHistory();
        design.parts.get(part).group = target;
        status("Moved " + (t != null ? t.name : "part")
                + (target == 0 ? " to Unassigned" : " into STAGE " + target));
        rebuildStageList();
    }

    /** The palette row (if any) under a screen point, for drag-out interception. */
    private PartType paletteRowAt(float screenX, float screenY) {
        if (openDrawer != 2) return null; // palette only lives in the Add-Part drawer (C2/C4)
        float stageY = Gdx.graphics.getHeight() - screenY; // stage coords are y-up
        com.badlogic.gdx.math.Vector2 tmpA = new com.badlogic.gdx.math.Vector2();
        com.badlogic.gdx.math.Vector2 tmpB = new com.badlogic.gdx.math.Vector2();
        for (PaletteItem item : paletteItems) {
            item.localToStageCoordinates(tmpA.set(0, 0));
            item.localToStageCoordinates(tmpB.set(item.getWidth(), item.getHeight()));
            if (screenX >= tmpA.x && screenX <= tmpB.x && stageY >= tmpA.y && stageY <= tmpB.y) {
                return item.partType;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ UI chrome

    private void buildChrome() {
        drawerW = Gdx.graphics.getWidth() * 0.44f;

        // --- full-screen canvas layer (bottom-most; the only region treated as canvas) ---
        canvasArea = new Table();
        canvasArea.setFillParent(true);
        stage.addActor(canvasArea);

        // --- drawers (task C2): slide in from the left, under the top bar ---
        drawerMenu = buildMenuDrawer();
        drawerParts = buildPartsDrawer();
        drawerStages = buildStagesDrawer();
        for (Table d : new Table[]{drawerMenu, drawerParts, drawerStages}) {
            d.setSize(drawerW, Gdx.graphics.getHeight());
            d.setPosition(-drawerW - 8, 0);
            stage.addActor(d);
        }

        // --- top bar: ship name / Rotate / LAUNCH ---
        nameLabel = new Label(shipName, game.ui.skin);
        TextButton rotate = new TextButton("Rotate (R)", game.ui.skin);
        rotate.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { rotateGhost(); }
        });
        TextButton launch = new TextButton("LAUNCH >>", game.ui.skin);
        launch.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { showLaunchPicker(); }
        });
        Table bar = new Table();
        bar.setBackground(game.ui.tinted(new Color(0.09f, 0.1f, 0.15f, 0.95f)));
        bar.add(nameLabel).expandX().left().padLeft(16).height(TOP_H);
        bar.add(rotate).width(230).height(TOP_H - 14).pad(7);
        bar.add(launch).width(240).height(TOP_H - 14).pad(7);
        Table top = new Table();
        top.setFillParent(true);
        top.top();
        top.setTouchable(Touchable.childrenOnly); // container must not swallow canvas taps
        top.add(bar).fillX();
        stage.addActor(top);

        // --- bottom-left drawer buttons (above the drawers in z-order) ---
        Table btns = new Table();
        btns.setFillParent(true);
        btns.bottom().left();
        btns.setTouchable(Touchable.childrenOnly);
        TextButton bMenu = new TextButton("Menu", game.ui.skin);
        bMenu.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { toggleDrawer(1); }
        });
        TextButton bParts = new TextButton("Add Part", game.ui.skin);
        bParts.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { toggleDrawer(2); }
        });
        TextButton bStages = new TextButton("Stages", game.ui.skin);
        bStages.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { toggleDrawer(3); }
        });
        btns.add(bMenu).width(BTN_W).height(BTN_H).pad(6).row();
        btns.add(bParts).width(BTN_W).height(BTN_H).pad(6).row();
        btns.add(bStages).width(BTN_W).height(BTN_H).pad(6).padBottom(130).row();
        stage.addActor(btns);

        // --- floating delete button (bottom-right, visible with a selection) ---
        delButton = new TextButton("DEL", game.ui.skin);
        delButton.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { deleteSelected(); }
        });
        Table delWrap = new Table();
        delWrap.setFillParent(true);
        delWrap.bottom().right();
        delWrap.setTouchable(Touchable.childrenOnly);
        delWrap.add(delButton).width(BTN_W).height(BTN_H).pad(10).padBottom(130);
        stage.addActor(delWrap);

        // --- bottom status bar ---
        statusLabel = new Label("Tap [Add Part] for parts. Tap part = select; drag = move; DEL deletes.",
                game.ui.skin);
        statusLabel.setColor(new Color(0.7f, 0.75f, 0.85f, 1f));
        statusLabel.setWrap(true);
        Table bbar = new Table();
        bbar.setBackground(game.ui.tinted(new Color(0.09f, 0.1f, 0.15f, 0.95f)));
        bbar.add(statusLabel).width(Gdx.graphics.getWidth() - 24).left().padLeft(12).padTop(6).padBottom(6);
        Table bottom = new Table();
        bottom.setFillParent(true);
        bottom.bottom();
        bottom.setTouchable(Touchable.childrenOnly);
        bottom.add(bbar).fillX();
        stage.addActor(bottom);

        rebuildStageList();
        updateDelButton();
    }

    /** Common drawer shell: dark panel, content starts below the top bar. */
    private Table drawerShell() {
        Table d = new Table();
        d.setBackground(game.ui.tinted(new Color(0.09f, 0.1f, 0.15f, 0.97f)));
        d.top();
        return d;
    }

    private void toggleDrawer(int which) {
        if (openDrawer == which) { closeDrawers(); return; }
        openDrawer = which;
        if (which == 1) rebuildMenuList();
        if (which == 3) rebuildStageList();
        slide(drawerMenu, which == 1);
        slide(drawerParts, which == 2);
        slide(drawerStages, which == 3);
    }

    private void closeDrawers() {
        openDrawer = 0;
        slide(drawerMenu, false);
        slide(drawerParts, false);
        slide(drawerStages, false);
    }

    /** Re-open the Add-Part drawer (issue 3: rapid consecutive placement). */
    private void openPartsDrawer() {
        openDrawer = 2;
        slide(drawerMenu, false);
        slide(drawerParts, true);
        slide(drawerStages, false);
    }

    private void slide(Table d, boolean open) {
        if (d == null) return;
        d.clearActions();
        d.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.moveTo(
                open ? 0 : -drawerW - 8, 0, 0.22f,
                com.badlogic.gdx.math.Interpolation.pow2Out));
    }

    // ------------------------------------------------------------ menu drawer (C3)

    private Table buildMenuDrawer() {
        Table d = drawerShell();
        Table content = new Table();
        content.top();
        content.add(new Label("MENU", game.ui.skin)).pad(10).row();
        addMenuButton(content, "Save Ship", new Runnable() { public void run() { saveShip(); rebuildMenuList(); } });
        content.add(new Label("Ship name:", game.ui.skin)).left().padLeft(10).padTop(8).row();
        nameField = new TextField(shipName, game.ui.skin);
        content.add(nameField).fillX().height(ROW_H).pad(6).row();
        addMenuButton(content, "Apply Name", new Runnable() { public void run() {
            shipName = nameField.getText();
            nameLabel.setText(shipName);
            status("Renamed to " + shipName);
        } });
        addMenuButton(content, "Undo", new Runnable() { public void run() { undo(); } });
        addMenuButton(content, "Redo", new Runnable() { public void run() { redo(); } });
        addMenuButton(content, "New Ship", new Runnable() { public void run() { newShip(); } });
        addMenuButton(content, "Share Ship", new Runnable() { public void run() {
            status("Share Ship: not implemented yet"); // placeholder (C3)
        } });
        addMenuButton(content, "Exit to Menu", new Runnable() { public void run() {
            game.setScreen(new MenuScreen(game));
        } });
        content.add(new Label("OPEN SHIP:", game.ui.skin)).pad(10).row();
        menuShipList = new Table();
        content.add(menuShipList).fillX().row();
        ScrollPane sp = new ScrollPane(content, game.ui.skin);
        sp.setFadeScrollBars(false);
        d.add(sp).expand().fill().padTop(TOP_H + 8).padBottom(330).padLeft(6).padRight(6);
        return d;
    }

    private void addMenuButton(Table content, String label, final Runnable action) {
        TextButton b = new TextButton(label, game.ui.skin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { action.run(); }
        });
        content.add(b).fillX().height(ROW_H).pad(5).row();
    }

    /** Saved-ship buttons inside the menu drawer (replaces the old Load dialog). */
    private void rebuildMenuList() {
        if (menuShipList == null) return;
        menuShipList.clear();
        com.badlogic.gdx.files.FileHandle dir = Gdx.files.local("save/ships");
        boolean any = false;
        if (dir.exists()) {
            for (final com.badlogic.gdx.files.FileHandle f : dir.list(".json")) {
                any = true;
                TextButton b = new TextButton(f.nameWithoutExtension(), game.ui.skin);
                b.addListener(new ClickListener() {
                    @Override public void clicked(InputEvent e, float x, float y) { loadShip(f); }
                });
                menuShipList.add(b).fillX().height(ROW_H).pad(4).row();
            }
        }
        if (!any) menuShipList.add(new Label("(no saved ships)", game.ui.skin)).pad(8).row();
    }

    private void loadShip(com.badlogic.gdx.files.FileHandle f) {
        try {
            pushHistory();
            design.copyFrom(ShipDesign.fromJson(f.readString()));
            shipName = f.nameWithoutExtension();
            if (nameField != null) nameField.setText(shipName);
            if (nameLabel != null) nameLabel.setText(shipName);
            selected.clear();
            rebuildStageList();
            updateDelButton();
            status("Loaded " + shipName);
        } catch (Exception ex) {
            status("Load failed: " + ex.getMessage());
        }
    }

    private void newShip() {
        pushHistory();
        design.clear();
        design.parts.add(new ShipDesign.DesignPart("pod-1", 0, 0, 0));
        design.autoStage();
        shipName = "Untitled";
        if (nameField != null) nameField.setText(shipName);
        if (nameLabel != null) nameLabel.setText(shipName);
        selected.clear();
        rebuildStageList();
        updateDelButton();
        status("New ship");
    }

    // ------------------------------------------------------------ parts drawer (C4)

    private Table buildPartsDrawer() {
        Table d = drawerShell();
        Table paletteCol = new Table();
        paletteCol.top();
        paletteItems.clear();
        for (PartType t : PartList.palette()) {
            PaletteItem item = new PaletteItem(t);
            paletteItems.add(item);
            // no fixed height: rows size to their (possibly wrapped) text and the
            // row actor fills the whole cell -> the ENTIRE row is touchable
            paletteCol.add(item).expandX().fillX().pad(3).row();
        }
        ScrollPane scroll = new ScrollPane(paletteCol, game.ui.skin);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false); // vertical drag only
        paletteScroll = scroll;
        d.add(scroll).expand().fill().padTop(TOP_H + 8).padBottom(330);
        return d;
    }

    // ------------------------------------------------------------ stages drawer (C5)

    private Table buildStagesDrawer() {
        Table d = drawerShell();
        stageListTable = new Table();
        stageListTable.top();
        ScrollPane sp = new ScrollPane(stageListTable, game.ui.skin);
        sp.setFadeScrollBars(false);
        sp.setScrollingDisabled(true, false); // issue 1: never scroll sideways
        d.add(sp).expand().fill().padTop(TOP_H + 8).padBottom(330).padLeft(6).padRight(6);
        return d;
    }

    /** Truncate with an ellipsis so the rendered text fits maxW px (issue 1). */
    private String fitText(String s, float maxW) {
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout =
                new com.badlogic.gdx.graphics.g2d.GlyphLayout();
        layout.setText(game.font, s);
        if (layout.width <= maxW) return s;
        String ell = "..";
        while (s.length() > 1) {
            s = s.substring(0, s.length() - 1);
            layout.setText(game.font, s + ell);
            if (layout.width <= maxW) return s + ell;
        }
        return s;
    }

    /** Width budget for stage-drawer content rows (drawer minus pads/scrollbar). */
    private float stageRowWidth() { return drawerW - 12 - 24; }

    /**
     * Rebuild the stages drawer content: one section per activation group
     * (STAGE 1..8) plus Unassigned (group 0). Tap a section header to highlight
     * that group's parts on the canvas; drag a part row onto another header to
     * move the part into that STAGE (Part.group = stage number, 0 = unassigned).
     */
    private void rebuildStageList() {
        if (stageListTable == null) return;
        stageListTable.clear();
        stageHeaders.clear();
        stageHeaderNums.clear();
        stageRows.clear();
        stageRowParts.clear();
        stageListTable.add(new Label("STAGES", game.ui.skin)).pad(8).left().row();
        Label hint = new Label("Tap header = highlight; drag a part row onto a header = assign",
                game.ui.skin);
        hint.setWrap(true);
        stageListTable.add(hint).width(stageRowWidth()).pad(4).row();
        for (int g = 1; g <= 8; g++) addStageSection(g);
        addStageSection(0);
    }

    private void addStageSection(final int g) {
        int count = 0;
        for (ShipDesign.DesignPart dp : design.parts) if (dp.group == g) count++;
        Table h = new Table();
        h.setBackground(game.ui.tinted(new Color(0.14f, 0.16f, 0.24f, 1f)));
        TextButton hb = new TextButton(
                fitText((g == 0 ? "Unassigned" : "STAGE " + g) + "  (" + count + ")",
                        stageRowWidth() - 120 - 8), game.ui.skin);
        hb.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { selectGroup(g); }
        });
        TextButton asg = new TextButton("< Sel", game.ui.skin);
        asg.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { assignSelected(g); }
        });
        h.add(hb).width(stageRowWidth() - 120 - 4).height(ROW_H).pad(2);
        h.add(asg).width(120).height(ROW_H).pad(2);
        stageListTable.add(h).width(stageRowWidth()).pad(3).row();
        stageHeaders.add(h);
        stageHeaderNums.add(g);
        for (int i = 0; i < design.parts.size(); i++) {
            if (design.parts.get(i).group != g) continue;
            stageListTable.add(partStageRow(i)).width(stageRowWidth() - 14).height(ROW_H).pad(2)
                    .padLeft(14).row();
        }
    }

    /**
     * A part row inside a stage section. Tap = highlight the part on the canvas.
     * Drag-to-assign is handled RAW by DragOutInterceptor (issue 2): the
     * ScrollPane steals vertical drags from scene2d listeners in this gdx
     * version, so an in-row drag listener can never complete the gesture.
     */
    private Table partStageRow(final int idx) {
        PartType t = PartList.get(design.parts.get(idx).typeId);
        final Table row = new Table();
        row.setBackground(game.ui.tinted(new Color(0.18f, 0.2f, 0.28f, 1f)));
        Label name = new Label(fitText(t != null ? t.name : "?", stageRowWidth() - 14 - 20),
                game.ui.skin);
        row.add(name).expandX().left().padLeft(10);
        row.addListener(new InputListener() {
            private float downX, downY;
            private boolean moved;
            @Override public boolean touchDown(InputEvent e, float x, float y, int pointer, int button) {
                downX = x; downY = y;
                moved = false;
                return true;
            }
            @Override public void touchDragged(InputEvent e, float x, float y, int pointer) {
                if (Math.hypot(x - downX, y - downY) > 14) moved = true;
            }
            @Override public void touchUp(InputEvent e, float x, float y, int pointer, int button) {
                // taps only: drags are owned (and consumed) by the raw interceptor
                if (moved || e.isCancelled()) return;
                selected.clear();
                if (idx < design.parts.size()) selected.add(idx);
                updateDelButton();
                status("Selected " + (t != null ? t.name : "part"));
            }
        });
        stageRows.add(row);
        stageRowParts.add(idx);
        return row;
    }

    /** Stage number whose header contains the stage-space point, or null. */
    private Integer stageHeaderAt(float sx, float sy) {
        com.badlogic.gdx.math.Vector2 a = new com.badlogic.gdx.math.Vector2();
        com.badlogic.gdx.math.Vector2 b = new com.badlogic.gdx.math.Vector2();
        for (int i = 0; i < stageHeaders.size(); i++) {
            Actor h = stageHeaders.get(i);
            h.localToStageCoordinates(a.set(0, 0));
            h.localToStageCoordinates(b.set(h.getWidth(), h.getHeight()));
            if (sx >= a.x && sx <= b.x && sy >= a.y && sy <= b.y) return stageHeaderNums.get(i);
        }
        return null;
    }

    /** Highlight every part of one activation group on the canvas (C5). */
    private void selectGroup(int g) {
        selected.clear();
        for (int i = 0; i < design.parts.size(); i++) {
            if (design.parts.get(i).group == g) selected.add(i);
        }
        updateDelButton();
        status(g == 0 ? selected.size() + " unassigned part(s) highlighted"
                : "STAGE " + g + ": " + selected.size() + " part(s) highlighted");
    }

    /** Assign the current canvas selection to a STAGE (0 = unassigned). */
    private void assignSelected(int g) {
        if (selected.isEmpty()) { status("Select parts on the canvas first"); return; }
        pushHistory();
        for (int i : selected) {
            if (i < design.parts.size()) design.parts.get(i).group = g;
        }
        status(g == 0 ? "Unassigned " + selected.size() + " part(s)"
                : "Assigned " + selected.size() + " part(s) to STAGE " + g);
        rebuildStageList();
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        pushHistory();
        // touch-friendly delete (right-click does not exist on phones)
        List<Integer> idx = new ArrayList<>(selected);
        idx.sort(java.util.Collections.reverseOrder());
        for (int i : idx) design.parts.remove(i);
        selected.clear();
        design.autoStage();
        rebuildStageList();
        updateDelButton();
        status("Deleted " + idx.size() + " parts");
    }

    /** Show/hide the floating DEL button with the selection state. */
    private void updateDelButton() {
        if (delButton == null) return;
        delButton.setVisible(!selected.isEmpty());
        delButton.setText("DEL (" + selected.size() + ")");
    }

    /** Modal overlay that swallows taps so they never leak to the canvas below. */
    private Table newOverlay() {
        Table o = new Table();
        o.setFillParent(true);
        o.setBackground(game.ui.tinted(new Color(0f, 0f, 0f, 0.6f)));
        o.addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent e, float x, float y, int pointer, int button) {
                return true; // modal: consume everything outside the dialog box too
            }
        });
        return o;
    }

    // ------------------------------------------------------------ modal overlays

    private void closeOverlay() {
        if (overlay != null) {
            overlay.remove();
            overlay = null;
        }
    }

    private void showLaunchPicker() {
        closeOverlay();
        overlay = newOverlay();
        Table box = new Table();
        box.setBackground(game.ui.tinted(new Color(0.12f, 0.14f, 0.2f, 1f)));
        box.pad(20);
        box.add(new Label("Select launch planet", game.ui.skin)).pad(10).row();
        List<Planet> flat = new ArrayList<>();
        game.world.sun.flatten(flat);
        for (Planet p : flat) {
            if (!p.launchEnabled) continue;
            TextButton b = new TextButton(p.name + "  (g=" + String.format("%.1f", p.gravity) + ")", game.ui.skin);
            b.addListener(new ClickListener() {
                @Override public void clicked(InputEvent e, float x, float y) {
                    launch(p);
                }
            });
            box.add(b).width(420).height(64).pad(5).row();
        }
        TextButton cancel = new TextButton("Cancel", game.ui.skin);
        cancel.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { closeOverlay(); }
        });
        box.add(cancel).width(420).height(56).pad(10).row();
        overlay.add(box);
        stage.addActor(overlay);
    }

    private void saveShip() {
        try {
            com.badlogic.gdx.files.FileHandle dir = Gdx.files.local("save/ships");
            dir.mkdirs();
            dir.child(shipName.replaceAll("[^a-zA-Z0-9_ -]", "_") + ".json").writeString(design.toJson(), false);
            status("Saved " + shipName);
        } catch (Exception e) {
            status("Save failed: " + e.getMessage());
        }
    }

    private void status(String s) {
        statusLabel.setText(s);
    }

    // ------------------------------------------------------------ launch

    private void launch(Planet planet) {
        design.autoStage();
        autosave(); // C6: keep the build for the next editor visit
        game.world.launchShip(design, planet);
        game.setScreen(new SandboxScreen(game));
    }

    // ------------------------------------------------------------ editing logic

    private Vector3 tmp3 = new Vector3();

    private Vector2 screenToWorld(float sx, float sy) {
        tmp3.set(sx, sy, 0);
        cam.unproject(tmp3);
        return new Vector2(tmp3.x, tmp3.y);
    }

    private int partAt(Vector2 w) {
        // generous pick radius (item 6): max(2 m, 48 px in world units);
        // nearest center among all hit parts wins (stable under overlap)
        float tol = Math.max(2f, 48f / Gdx.graphics.getHeight() * cam.viewportHeight);
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = design.parts.size() - 1; i >= 0; i--) {
            ShipDesign.DesignPart dp = design.parts.get(i);
            PartType t = PartList.get(dp.typeId);
            if (t == null) continue;
            // box test in part frame
            float dx = w.x - dp.x, dy = w.y - dp.y;
            float c = (float) Math.cos(-dp.rot * Math.PI / 2), s = (float) Math.sin(-dp.rot * Math.PI / 2);
            float lx = dx * c - dy * s, ly = dx * s + dy * c;
            if (Math.abs(lx) <= t.width / 2f + tol && Math.abs(ly) <= t.height / 2f + tol) {
                float d = (float) Math.hypot(dx, dy);
                if (d < bestD) { bestD = d; best = i; }
            }
        }
        return best;
    }

    /** Try to snap ghost position to attach points of other parts. Returns snapped position. */
    private Vector2 snap(float px, float py, int rot, PartType type, int ignoreIndex) {
        Vector2 best = new Vector2(px, py);
        float bestD = 2.2f; // snap radius (m)
        // edge-aware snapping (round 11 item 5): an edge attach point accepts
        // contact anywhere along its side segment, so the ghost slides along
        // the mating edge and aligns at the closest contact pair.
        Vector2 ma = new Vector2(), mb = new Vector2();
        Vector2 oa = new Vector2(), ob = new Vector2();
        Vector2 cm = new Vector2(), co = new Vector2();
        Vector2 qn = new Vector2();
        for (int i = 0; i < design.parts.size(); i++) {
            if (i == ignoreIndex) continue;
            ShipDesign.DesignPart dp = design.parts.get(i);
            PartType t = PartList.get(dp.typeId);
            if (t == null) continue;
            for (PartType.AttachPoint apM : type.attach) {
                attachWorldSeg(type, px, py, rot, apM, ma, mb);
                for (PartType.AttachPoint apO : t.attach) {
                    attachWorldSeg(t, dp.x, dp.y, dp.rot, apO, oa, ob);
                    float d = Attach.closestBetweenSegments(ma, mb, oa, ob, cm, co);
                    if (d < bestD) {
                        bestD = d;
                        float nx = px + (co.x - cm.x), ny = py + (co.y - cm.y);
                        // edge snap quantization: when the winning contact
                        // involves an edge-type attach point, the ghost's free
                        // slide along the edge locks to the 0.25 m grid;
                        // center-type pairs keep the exact contact position.
                        if (apO.edge != PartType.AttachPoint.EDGE_NONE) {
                            best.set(Attach.quantizeAlongSegment(nx, ny, oa, ob, qn));
                        } else if (apM.edge != PartType.AttachPoint.EDGE_NONE) {
                            best.set(Attach.quantizeAlongSegment(nx, ny, ma, mb, qn));
                        } else {
                            best.set(nx, ny);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Design-space world segment of one attach point of a part at (px,py,rot). */
    private static void attachWorldSeg(PartType t, float px, float py, int rot,
                                       PartType.AttachPoint ap, Vector2 outA, Vector2 outB) {
        Attach.localSegment(t, ap, outA, outB);
        float c = (float) Math.cos(rot * Math.PI / 2), s = (float) Math.sin(rot * Math.PI / 2);
        float ax = outA.x * c - outA.y * s, ay = outA.x * s + outA.y * c;
        float bx = outB.x * c - outB.y * s, by = outB.x * s + outB.y * c;
        outA.set(px + ax, py + ay);
        outB.set(px + bx, py + by);
    }

    /** Smoke-test hook (round 11 item 5): snap as if dragging typeId to (px,py). */
    public Vector2 snapForTest(float px, float py, int rot, String typeId, int ignoreIndex) {
        PartType t = PartList.get(typeId);
        return t == null ? new Vector2(px, py) : snap(px, py, rot, t, ignoreIndex);
    }

    private List<Vector2> attachWorld(PartType t, float px, float py, int rot) {
        List<Vector2> out = new ArrayList<>();
        float c = (float) Math.cos(rot * Math.PI / 2), s = (float) Math.sin(rot * Math.PI / 2);
        for (PartType.AttachPoint ap : t.attach) {
            float lx = ap.x * c - ap.y * s;
            float ly = ap.x * s + ap.y * c;
            out.add(new Vector2(px + lx, py + ly));
        }
        return out;
    }

    private void rotateGhost() {
        if (placing != null && !placing.disableEditorRotation) {
            dragRot = (dragRot + 1) % 4;
            status("Rotation: " + (dragRot * 90) + " deg");
            return;
        }
        if (dragIndex >= 0) {
            ShipDesign.DesignPart dp = design.parts.get(dragIndex);
            PartType t = PartList.get(dp.typeId);
            if (t != null && !t.disableEditorRotation) {
                dragRot = (dragRot + 1) % 4;
                status("Rotation: " + (dragRot * 90) + " deg");
            }
            return;
        }
        // item 9 (round-9 bug): Rotate did nothing for TAP-SELECTED placed
        // parts — the old code only knew the placing ghost and the mid-drag
        // part, so a tap-selection was a dead target. Rotate all selected.
        if (!selected.isEmpty()) {
            pushHistory();
            int n = 0;
            for (int idx : selected) {
                if (idx < 0 || idx >= design.parts.size()) continue;
                ShipDesign.DesignPart dp = design.parts.get(idx);
                PartType t = PartList.get(dp.typeId);
                if (t != null && !t.disableEditorRotation) {
                    dp.rot = (dp.rot + 1) % 4;
                    n++;
                }
            }
            status(n > 0 ? "Rotated " + n + " selected part(s)"
                    : "Selected part(s) cannot be rotated");
        }
    }

    /** Diagnostic: ghost/drag rotation steps (smoke tests). */
    public int dragRotForTest() { return dragRot; }

    private class EditorInput extends InputAdapter {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            // a palette drag-out owns pointer 0 — a second finger must not
            // place a ghost or start a pan underneath it (item 8)
            if (dragOutType != null) return false;
            if (touchPtrA != -1) {
                // second finger -> two-finger camera gesture (item 2): cancel
                // any single-finger op (part drag / pan) without finishing it.
                // Finger A's position comes from OUR event log (gpaX/gpaY,
                // maintained at every A event) — never Gdx.input.getX(pointer),
                // which is unreliable for synthetic events and for pointers the
                // back-end has already forgotten (item 8, same class as the
                // dead-pointer pinch crash).
                if (touchPtrB != -1) return false; // ignore 3rd finger
                touchPtrB = pointer;
                gpbX = screenX; gpbY = screenY;
                panning = false;
                downIndex = -1; dragIndex = -1; dragMoved = false;
                return true;
            }
            // Canvas-or-chrome decision via the stage itself (no coordinate
            // guessing): any stage actor other than the transparent canvas area
            // (top bar, palette sheet, group bar, overlays) owns the tap.
            Actor hit = stage.hit(screenX, Gdx.graphics.getHeight() - screenY, true);
            if (hit != null && hit != canvasArea) return false;
            boolean handled = firstFingerDown(screenX, screenY, button);
            if (handled) { touchPtrA = pointer; gpaX = screenX; gpaY = screenY; }
            return handled;
        }

        /** Single-finger canvas touch (part drag/tap-select/pan/place/delete). */
        private boolean firstFingerDown(int screenX, int screenY, int button) {
            Vector2 w = screenToWorld(screenX, screenY);
            if (button == Input.Buttons.RIGHT) {
                if (placing != null) { placing = null; return true; }
                int idx = partAt(w);
                if (idx >= 0) {
                    pushHistory();
                    design.parts.remove(idx);
                    selected.clear();
                    updateDelButton();
                    design.autoStage();
                    rebuildStageList();
                    return true;
                }
                return false;
            }
            if (placing != null) {
                pushHistory();
                Vector2 snapped = snap(w.x, w.y, dragRot, placing, -1);
                design.parts.add(new ShipDesign.DesignPart(placing.id, snapped.x, snapped.y, dragRot));
                selected.clear();
                updateDelButton();
                design.autoStage();
                rebuildStageList();
                if (!Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)) {
                    placing = null;
                    openPartsDrawer(); // issue 3: ready for the next part right away
                }
                return true;
            }
            int idx = partAt(w);
            if (idx >= 0) {
                // could become a drag (move) or stay a tap (toggle selection)
                downIndex = idx;
                downScrX = screenX;
                downScrY = screenY;
                dragMoved = false;
                return true;
            }
            panning = true;
            panLastX = screenX;
            panLastY = screenY;
            downScrX = screenX;
            downScrY = screenY;
            return true;
        }

        /** Two-finger camera gesture: midpoint pans, pinch ratio zooms (item 2). */
        private void pinchDrag(float x, float y, boolean movedIsA) {
            float nAX = movedIsA ? x : gpaX, nAY = movedIsA ? y : gpaY;
            float nBX = movedIsA ? gpbX : x, nBY = movedIsA ? gpbY : y;
            float midX = (nAX + nBX) / 2f, midY = (nAY + nBY) / 2f;
            float pmX = (gpaX + gpbX) / 2f, pmY = (gpaY + gpbY) / 2f;
            double prevDist = Math.hypot(gpaX - gpbX, gpaY - gpbY);
            double dist = Math.hypot(nAX - nBX, nAY - nBY);
            // pan by midpoint delta (unprojected, exact)
            tmp3.set(pmX, pmY, 0); cam.unproject(tmp3);
            float w1x = tmp3.x, w1y = tmp3.y;
            tmp3.set(midX, midY, 0); cam.unproject(tmp3);
            cam.position.sub(tmp3.x - w1x, tmp3.y - w1y, 0);
            // pinch zoom anchored at the midpoint
            if (prevDist > 10 && dist > 10) {
                float factor = (float) (prevDist / dist);
                float newH = Math.max(5, Math.min(200, cam.viewportHeight * factor));
                if (Float.isFinite(newH) && newH > 0 && newH != cam.viewportHeight) {
                    tmp3.set(midX, midY, 0); cam.unproject(tmp3);
                    float ax = tmp3.x, ay = tmp3.y;
                    cam.viewportHeight = newH;
                    cam.viewportWidth = newH * Gdx.graphics.getWidth() / Gdx.graphics.getHeight();
                    cam.update();
                    tmp3.set(midX, midY, 0); cam.unproject(tmp3);
                    cam.position.x += ax - tmp3.x;
                    cam.position.y += ay - tmp3.y;
                }
            }
            cam.update();
            gpaX = nAX; gpaY = nAY; gpbX = nBX; gpbY = nBY;
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (touchPtrB != -1) {
                if (pointer == touchPtrA || pointer == touchPtrB) {
                    pinchDrag(screenX, screenY, pointer == touchPtrA);
                    return true;
                }
                return false;
            }
            // single-finger op: keep our own per-finger position log current
            // (used instead of Gdx.input.getX(pointer) everywhere — item 8)
            if (pointer == touchPtrA) { gpaX = screenX; gpaY = screenY; }
            if (pointer != touchPtrA) return false;
            if (downIndex >= 0 && !dragMoved
                    && Math.hypot(screenX - downScrX, screenY - downScrY) > 12) {
                // promote to a drag-move: snapshot BEFORE the first mutation (undo)
                pushHistory();
                dragMoved = true;
                dragIndex = downIndex;
                dragRot = design.parts.get(dragIndex).rot;
            }
            if (dragMoved && dragIndex >= 0) {
                Vector2 w = screenToWorld(screenX, screenY);
                PartType t = PartList.get(design.parts.get(dragIndex).typeId);
                Vector2 snapped = snap(w.x, w.y, dragRot, t, dragIndex);
                dragX = snapped.x;
                dragY = snapped.y;
                design.parts.get(dragIndex).x = dragX;
                design.parts.get(dragIndex).y = dragY;
                design.parts.get(dragIndex).rot = dragRot;
                return true;
            }
            if (panning) {
                float scale = cam.viewportHeight / Gdx.graphics.getHeight();
                cam.position.sub((screenX - panLastX) * scale, -(screenY - panLastY) * scale, 0);
                cam.update();
                panLastX = screenX;
                panLastY = screenY;
                return true;
            }
            return false;
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            if (pointer == touchPtrB) {
                touchPtrB = -1;
                // re-anchor the remaining finger from OUR position log (the
                // back-end's per-pointer query is not trustworthy — item 8)
                if (touchPtrA >= 0) {
                    panLastX = gpaX; panLastY = gpaY;
                }
                return true;
            }
            if (pointer != touchPtrA) return false;
            if (touchPtrB != -1) {
                // primary lifted while second finger still down: promote it
                touchPtrA = touchPtrB;
                touchPtrB = -1;
                gpaX = gpbX;
                gpaY = gpbY;
                panLastX = gpaX; panLastY = gpaY;
                panning = false; downIndex = -1; dragIndex = -1; dragMoved = false;
                return true;
            }
            touchPtrA = -1;
            if (downIndex >= 0) {
                if (!dragMoved) {
                    // tap: toggle selection for group assignment / deletion
                    if (!selected.remove(downIndex)) selected.add(downIndex);
                    status(selected.isEmpty() ? "Tap [Add Part] for parts. Tap part = select; drag = move; DEL deletes."
                            : selected.size() + " selected — open [Stages] to assign a group");
                    updateDelButton();
                } else {
                    design.autoStage();
                    rebuildStageList();
                }
                downIndex = -1;
                dragIndex = -1;
                dragMoved = false;
                return true;
            }
            if (panning) {
                panning = false;
                if (Math.hypot(screenX - downScrX, screenY - downScrY) < 12 && !selected.isEmpty()) {
                    // tapped empty space: clear selection
                    selected.clear();
                    updateDelButton();
                }
                return false;
            }
            return false;
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            cam.viewportHeight = Math.max(5, Math.min(200, cam.viewportHeight * (amountY > 0 ? 1.1f : 0.9f)));
            cam.viewportWidth = cam.viewportHeight * Gdx.graphics.getWidth() / Gdx.graphics.getHeight();
            cam.update();
            return true;
        }

        /** Gesture interrupted by the OS/back-end: drop ALL transient state. */
        @Override
        public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
            touchPtrA = -1;
            touchPtrB = -1;
            panning = false;
            downIndex = -1;
            dragIndex = -1;
            dragMoved = false;
            return false;
        }

        @Override
        public boolean keyDown(int keycode) {
            if (keycode == Input.Keys.R) { rotateGhost(); return true; }
            if (keycode == Input.Keys.ESCAPE) {
                if (overlay != null) closeOverlay();
                else if (placing != null) placing = null;
                else if (openDrawer != 0) closeDrawers();
                else game.setScreen(new MenuScreen(game));
                return true;
            }
            if (keycode == Input.Keys.DEL || keycode == Input.Keys.BACKSPACE) {
                deleteSelected();
                return true;
            }
            return false;
        }
    }

    public void selectPart(PartType t) {
        placing = t;
        dragRot = 0;
        closeDrawers(); // free the canvas for placement
        status("Placing: " + t.name + " (R = rotate, right-click = cancel, shift = keep placing)");
    }

    /** Diagnostic: palette row bounds in screen coords (smoke tests). */
    public String actorBoundsInfo(int i) {
        if (i >= paletteItems.size()) return "?";
        Actor a = paletteItems.get(i);
        com.badlogic.gdx.math.Vector2 bl = a.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(0, 0));
        com.badlogic.gdx.math.Vector2 tr = a.localToStageCoordinates(
                new com.badlogic.gdx.math.Vector2(a.getWidth(), a.getHeight()));
        bl = stage.stageToScreenCoordinates(bl);
        tr = stage.stageToScreenCoordinates(tr);
        return "(" + Math.round(bl.x) + "," + Math.round(tr.y) + ")-(" + Math.round(tr.x) + "," + Math.round(bl.y) + ")";
    }

    /** Palette drag-out (item 4): a palette row was pulled sideways — arm the ghost. */    void beginDragOut(PartType t, int pointer, float screenX, float screenY) {
        // The raw interceptor owns the gesture from here on and will swallow the
        // release touchUp — cancel the stage's touch focus (palette row /
        // ScrollPane) NOW or the stale focus fires a phantom row touchUp on the
        // next gesture and the stage swallows that touchUp, leaking
        // EditorInput's touchPtrA (item 8).
        stage.cancelTouchFocus();
        placing = t;
        dragRot = 0;
        dragOutType = t;
        dragScrX = screenX;
        dragScrY = screenY;
        closeDrawers(); // free the canvas for the drop
        status("Drag onto the canvas and release to place " + t.name);
    }

    /** Diagnostic: current status line text (smoke tests). */
    public String lastStatus() { return statusLabel.getText().toString(); }

    /** Diagnostics for input-lifecycle smoke tests. */
    public float camXForTest() { return cam.position.x; }
    public float camYForTest() { return cam.position.y; }
    public float zoomForTest() { return cam.viewportHeight; }

    /**
     * Diagnostic: first screen point that is guaranteed canvas — no chrome and
     * no part under it (smoke tests, robust to any camera state).
     */
    public int[] emptyCanvasPointForTest() {
        for (int y = 180; y <= 700; y += 40) {
            for (int x = 240; x <= 520; x += 40) {
                Actor a = stage.hit(x, Gdx.graphics.getHeight() - y, true);
                if (a != null && a != canvasArea) continue;
                if (partAt(screenToWorld(x, y)) < 0) return new int[]{x, y};
            }
        }
        return null;
    }

    /**
     * Palette drag-out release (screen coords of the finger): over the canvas
     * (right of the palette column) -> place at the snapped position; back over
     * the palette -> cancel.
     */
    void finishDragOut(float screenX, float screenY) {
        dragOutType = null;
        // defensive: nothing should hold stage focus after a drag-out (a second
        // finger on the palette mid-drag could have re-armed one)
        stage.cancelTouchFocus();
        if (placing == null) return;
        if (screenX > drawerW) {
            pushHistory();
            Vector2 w = screenToWorld(screenX, screenY);
            Vector2 snapped = snap(w.x, w.y, dragRot, placing, -1);
            design.parts.add(new ShipDesign.DesignPart(placing.id, snapped.x, snapped.y, dragRot));
            selected.clear();
            updateDelButton();
            design.autoStage();
            rebuildStageList();
            status("Placed " + placing.name + " — drag another from the list, or tap a row");
            placing = null;
            openPartsDrawer(); // issue 3: ready for the next part right away
        } else {
            status("Cancelled");
        }
        placing = null;
    }

    // ------------------------------------------------------------ palette item widget

    private class PaletteItem extends Table {
        final PartType partType;

        PaletteItem(PartType t) {
            partType = t;
            setBackground(game.ui.tinted(new Color(0.14f, 0.16f, 0.22f, 1f)));
            float textW = drawerW - 24; // drawer width minus pads
            Label name = new Label(t.name, game.ui.skin);
            name.setFontScale(2.0f); // item 3: 2x, wrapped to stay inside the panel
            name.setWrap(true);
            Label info = new Label(t.type + "  " + String.format("%.2f t", t.massTons), game.ui.skin);
            info.setFontScale(1.5f);
            info.setWrap(true);
            info.setColor(new Color(0.6f, 0.65f, 0.75f, 1f));
            add(name).width(textW).left().padLeft(8).padTop(8).row();
            add(info).width(textW).left().padLeft(8).padBottom(8);
            // The whole row is the touch target (item 3). Tap = arm click-to-place.
            // Horizontal drag-out is intercepted RAW upstream (see show(): the
            // InputMultiplexer processor in front of the stage) because the
            // ScrollPane steals/cancels Scene2D touch focus mid-drag; vertical
            // drags are left to the pane and never arm anything (movement guard).
            addListener(new InputListener() {
                private float downX, downY;
                private float downScrollY;
                private boolean moved;
                @Override public boolean touchDown(InputEvent e, float x, float y, int pointer, int button) {
                    downX = x; downY = y;
                    downScrollY = paletteScroll != null ? paletteScroll.getScrollY() : 0f;
                    moved = false;
                    return true;
                }
                @Override public void touchDragged(InputEvent e, float x, float y, int pointer) {
                    if (Math.hypot(x - downX, y - downY) > 14) moved = true;
                }
                @Override public void touchUp(InputEvent e, float x, float y, int pointer, int button) {
                    // The ScrollPane steals vertical drags without ever routing
                    // touchDragged/cancel to this row (observed: moved=false,
                    // cancelled=false after a 180 px scroll), so also treat any
                    // pane scroll since touch-down as "this was a scroll, not a tap".
                    boolean paneScrolled = paletteScroll != null
                            && Math.abs(paletteScroll.getScrollY() - downScrollY) > 2f;
                    if (!moved && !paneScrolled && !e.isCancelled()) {
                        selectPart(t); // simple tap: arm click-to-place (fallback)
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------ render

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.07f, 0.08f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // grid
        game.shapes.setProjectionMatrix(cam.combined);
        game.shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line);
        game.shapes.setColor(0.16f, 0.18f, 0.24f, 1f);
        float gx = (float) Math.floor(cam.position.x - cam.viewportWidth / 2 - 1);
        float gy = (float) Math.floor(cam.position.y - cam.viewportHeight / 2 - 1);
        for (float x = gx; x < cam.position.x + cam.viewportWidth / 2 + 2; x += 2) {
            game.shapes.line(x, cam.position.y - cam.viewportHeight / 2 - 2, x, cam.position.y + cam.viewportHeight / 2 + 2);
        }
        for (float y = gy; y < cam.position.y + cam.viewportHeight / 2 + 2; y += 2) {
            game.shapes.line(cam.position.x - cam.viewportWidth / 2 - 2, y, cam.position.x + cam.viewportWidth / 2 + 2, y);
        }
        game.shapes.end();

        // parts
        game.batch.setProjectionMatrix(cam.combined);
        game.batch.begin();
        for (ShipDesign.DesignPart dp : design.parts) {
            drawPart(dp.typeId, dp.x, dp.y, dp.rot, 1f);
        }
        // ghost
        if (placing != null) {
            Vector2 w = screenToWorld(Gdx.input.getX(), Gdx.input.getY());
            Vector2 snapped = snap(w.x, w.y, dragRot, placing, -1);
            drawPart(placing.id, snapped.x, snapped.y, dragRot, 0.6f);
            // attach markers
            game.batch.end();
            game.shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
            game.shapes.setColor(0.3f, 0.9f, 0.4f, 0.9f);
            for (Vector2 p : attachWorld(placing, snapped.x, snapped.y, dragRot)) {
                game.shapes.circle(p.x, p.y, 0.25f, 8);
            }
            for (ShipDesign.DesignPart dp : design.parts) {
                PartType t = PartList.get(dp.typeId);
                if (t == null) continue;
                for (Vector2 p : attachWorld(t, dp.x, dp.y, dp.rot)) {
                    game.shapes.circle(p.x, p.y, 0.18f, 8);
                }
            }
            game.shapes.end();
            game.batch.begin();
        }
        game.batch.end();

        // selection outlines + activation-group badges (item 6a)
        if (!selected.isEmpty() || hasAnyGroup()) {
            game.shapes.setProjectionMatrix(cam.combined);
            game.shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line);
            game.shapes.setColor(0.3f, 0.9f, 1f, 0.95f);
            for (int i : selected) {
                if (i >= design.parts.size()) continue;
                ShipDesign.DesignPart dp = design.parts.get(i);
                PartType t = PartList.get(dp.typeId);
                if (t == null) continue;
                float hw = t.width / 2f + 0.3f, hh = t.height / 2f + 0.3f;
                game.shapes.rect(dp.x - hw, dp.y - hh, hw * 2, hh * 2);
            }
            game.shapes.end();
            game.shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
            for (int i = 0; i < design.parts.size(); i++) {
                ShipDesign.DesignPart dp = design.parts.get(i);
                if (dp.group <= 0) continue;
                PartType t = PartList.get(dp.typeId);
                if (t == null) continue;
                game.shapes.setColor(0.2f, 0.55f, 1f, 0.95f);
                game.shapes.circle(dp.x + t.width / 2f, dp.y + t.height / 2f, 0.55f, 12);
            }
            game.shapes.end();
            // badge digits (constant on-screen size: scale follows zoom)
            game.batch.setProjectionMatrix(cam.combined);
            game.batch.begin();
            game.font.getData().setScale(cam.viewportHeight / 40f * 0.07f);
            for (int i = 0; i < design.parts.size(); i++) {
                ShipDesign.DesignPart dp = design.parts.get(i);
                if (dp.group <= 0) continue;
                PartType t = PartList.get(dp.typeId);
                if (t == null) continue;
                game.font.draw(game.batch, String.valueOf(dp.group),
                        dp.x + t.width / 2f - 0.28f * cam.viewportHeight / 40f,
                        dp.y + t.height / 2f + 0.34f * cam.viewportHeight / 40f);
            }
            game.font.getData().setScale(game.ui.fontScale);
            game.batch.end();
        }

        stage.act(delta);
        stage.draw();
    }

    private boolean hasAnyGroup() {
        for (ShipDesign.DesignPart dp : design.parts) if (dp.group > 0) return true;
        return false;
    }

    private void drawPart(String typeId, float x, float y, int rot, float alpha) {
        PartType t = PartList.get(typeId);
        if (t == null) return;
        TextureRegion r = game.shipSprites.find(t.sprite);
        game.batch.setColor(1, 1, 1, alpha);
        if (r != null) {
            game.batch.draw(r, x - t.width / 2f, y - t.height / 2f,
                    t.width / 2f, t.height / 2f, t.width, t.height,
                    1f, 1f, rot * 90f);
        } else {
            game.batch.end();
            game.shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line);
            game.shapes.setColor(0.8f, 0.5f, 0.3f, alpha);
            game.shapes.rect(x - t.width / 2f, y - t.height / 2f, t.width, t.height);
            game.shapes.end();
            game.batch.begin();
        }
        game.batch.setColor(1, 1, 1, 1);
    }

    @Override
    public void resize(int w, int h) {
        if (stage != null) stage.getViewport().update(w, h, true);
        if (cam != null) {
            cam.viewportWidth = cam.viewportHeight * w / h;
            cam.update();
        }
        // keep the drawers glued to the left edge at the new size
        drawerW = w * 0.44f;
        if (drawerMenu != null) {
            Table[] ds = {drawerMenu, drawerParts, drawerStages};
            for (int i = 0; i < ds.length; i++) {
                ds[i].setSize(drawerW, h);
                ds[i].clearActions();
                ds[i].setPosition(openDrawer == i + 1 ? 0 : -drawerW - 8, 0);
            }
        }
    }

    @Override
    public void dispose() {
        if (stage != null) stage.dispose();
    }

    /**
     * Leaving the screen mid-gesture must not leak any pointer state into the
     * next show() (item 8): armed drag-out, candidate row, tracked fingers,
     * pan/drag indices — everything transient dies here.
     */
    @Override
    public void hide() {
        autosave(); // C6: persist the in-progress build on exit
        dragOutInterceptor.reset();
        dragOutType = null;
        placing = null;
        touchPtrA = -1;
        touchPtrB = -1;
        panning = false;
        downIndex = -1;
        dragIndex = -1;
        dragMoved = false;
        if (stage != null) stage.cancelTouchFocus();
    }
}
