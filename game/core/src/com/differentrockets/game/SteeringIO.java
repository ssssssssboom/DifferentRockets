package com.differentrockets.game;
/** Shared steering input state written by the sandbox UI and read by steering/engine control. */
public class SteeringIO {
    public static volatile boolean ringActive = false;
    public static volatile int buttonTurn = 0; // -1 = left, 0 = none, +1 = right (held)
    public static volatile double targetHeadingRad = 0;
    public static boolean hasTarget() { return ringActive; }

    // ---- RCS pad (sandbox UI <-> control.lua / wheel lua contract) ----
    // held-booleans: true while the corresponding RCS pad button is pressed.
    // "<" (holdLeft) and "ROT L" (rotLeft) BOTH also drive buttonTurn so
    // non-RCS parts (engine gimbals / wheel torque) respond exactly as before;
    // UP/DOWN carry no buttonTurn (no effect on non-RCS parts).
    public static volatile boolean holdUp = false;
    public static volatile boolean holdDown = false;
    public static volatile boolean holdLeft = false;
    public static volatile boolean holdRight = false;
    public static volatile boolean rotLeft = false;
    public static volatile boolean rotRight = false;
    /** RCS master switch (sandbox RCS button); the pad layout follows it. */
    public static volatile boolean rcsEnabled = false;
}
