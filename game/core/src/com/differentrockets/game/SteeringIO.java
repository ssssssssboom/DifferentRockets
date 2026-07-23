package com.differentrockets.game;
/** Shared steering input state written by the sandbox UI and read by steering/engine control. */
public class SteeringIO {
    public static volatile boolean ringActive = false;
    public static volatile int buttonTurn = 0; // -1 = left, 0 = none, +1 = right (held)
    public static volatile double targetHeadingRad = 0;
    public static boolean hasTarget() { return ringActive; }
}
