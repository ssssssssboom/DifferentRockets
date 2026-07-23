package com.differentrockets.android;

import android.app.AlertDialog;
import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.differentrockets.game.DRGame;
import com.differentrockets.util.Res;

public class AndroidLauncher extends AndroidApplication {

    private static final String TAG = "DifferentRockets";

    private DRGame game;
    private AlertDialog permDialog;
    private boolean userDeclinedThisSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration cfg = new AndroidApplicationConfiguration();
        cfg.useAccelerometer = false;
        cfg.useCompass = false;
        cfg.useWakelock = true;
        logStorageState("onCreate");
        if (!probeStorage("onCreate")) {
            requestStorageAccess(true);
        }
        game = new DRGame();
        initialize(game, cfg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the Settings grant screen (or any resume): re-probe the
        // external resource dir. If storage just became available, switch all
        // resource loading to it WITHOUT requiring an app restart.
        try {
            logStorageState("onResume");
            boolean ok = probeStorage("onResume");
            if (!ok && !userDeclinedThisSession) {
                requestStorageAccess(false);
            }
            if (game != null && Res.refresh()) {
                if (permDialog != null) {
                    permDialog.dismiss();
                    permDialog = null;
                }
                game.reloadResources();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "resource refresh failed", t);
        }
    }

    /** Log every relevant storage fact (for adb logcat diagnosis). */
    private void logStorageState(String where) {
        try {
            String ext = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            boolean manager = android.os.Build.VERSION.SDK_INT >= 30
                    && android.os.Environment.isExternalStorageManager();
            boolean legacyWrite = android.os.Build.VERSION.SDK_INT < 30
                    && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            android.util.Log.i(TAG, "[storage] " + where
                    + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + " extRoot=" + ext
                    + " isExternalStorageManager=" + manager
                    + " writePermissionGranted=" + legacyWrite
                    + " targetSdk=29");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "[storage] state log failed", t);
        }
    }

    /**
     * Try to actually create the player dir and write a probe file.
     * Returns true when the shared-storage root is really writable.
     */
    private boolean probeStorage(String where) {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "DifferentRocket");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File probe = new java.io.File(dir, ".probe");
            boolean wrote;
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(probe)) {
                fos.write(1);
                wrote = true;
            } catch (Throwable t) {
                wrote = false;
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            android.util.Log.i(TAG, "[storage] probe " + where + " at "
                    + dir.getAbsolutePath() + " -> " + (wrote ? "WRITABLE" : "FAILED"));
            return wrote;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "[storage] probe " + where + " threw", t);
            return false;
        }
    }

    /**
     * Ask for the permission that matches this API level, and show a
     * non-cancelable Chinese dialog explaining why file access is required.
     * API <= 29: runtime WRITE_EXTERNAL_STORAGE. API 30+: All-files-access
     * Settings intent.
     */
    private void requestStorageAccess(boolean fromOnCreate) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    showPermissionDialog(fromOnCreate);
                }
            } else if (android.os.Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    android.util.Log.i(TAG, "[storage] requesting WRITE_EXTERNAL_STORAGE (runtime)");
                    requestPermissions(new String[]{
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "[storage] permission request failed", t);
        }
    }

    private void showPermissionDialog(boolean fireIntentImmediately) {
        if (permDialog != null && permDialog.isShowing()) return;
        android.util.Log.i(TAG, "[storage] showing All-files-access grant dialog");
        Runnable openSettings = () -> {
            try {
                android.content.Intent i = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
                android.util.Log.i(TAG, "[storage] opened All-files-access Settings page");
            } catch (Throwable t) {
                android.util.Log.w(TAG, "[storage] could not open Settings page", t);
            }
        };
        if (fireIntentImmediately) openSettings.run();
        permDialog = new AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage("DifferentRockets 需要“所有文件访问”权限，才能在\n"
                        + "/storage/emulated/0/DifferentRocket/\n"
                        + "创建玩家资源目录（贴图、零件与 Lua 脚本都可在其中修改）。\n\n"
                        + "请在下一个页面中允许 DifferentRockets 访问所有文件。"
                        + "授权后返回游戏会自动生效，无需重启。")
                .setCancelable(false)
                .setPositiveButton("去授权", (d, w) -> openSettings.run())
                .setNegativeButton("暂不（使用内置资源）", (d, w) -> {
                    userDeclinedThisSession = true;
                    android.util.Log.i(TAG, "[storage] user declined for this session — built-in assets only");
                })
                .create();
        try {
            permDialog.show();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "[storage] dialog show failed", t);
        }
    }
}
