package com.differentrockets.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.differentrockets.game.DRGame;

public class DesktopLauncher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("DifferentRockets");
        cfg.setWindowedMode(1080, 1920); // ~phone aspect so UI proportions match
        cfg.setForegroundFPS(60);
        new Lwjgl3Application(new DRGame(), cfg);
    }
}
