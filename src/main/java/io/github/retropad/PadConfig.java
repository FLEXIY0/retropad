package io.github.retropad;

import com.fox2code.foxloader.config.ConfigEntry;
import io.github.retropad.input.PadLayout;

/** Everything tweakable, persisted by FoxLoader to config/retropad.cfg. */
public final class PadConfig {

    /** What the button glyphs and the default mapping should assume. */
    public enum ControllerType {
        AUTO,
        XBOX,
        PLAYSTATION,
        GENERIC
    }

    @ConfigEntry(configName = "Enable gamepad")
    public boolean enabled = true;

    @ConfigEntry(configName = "Controller type",
            configComment = "AUTO picks Xbox or PlayStation from the device name.")
    public ControllerType controllerType = ControllerType.AUTO;

    @ConfigEntry(configName = "Device index",
            configComment = "-1 picks the first device that looks like a gamepad.",
            lowerBounds = -1, upperBounds = 15)
    public int deviceIndex = -1;

    @ConfigEntry(configName = "Swap trigger axis",
            configComment = "Xbox pads put both triggers on one axis; flip this if LT and RT are reversed.")
    public boolean swapTriggerAxis = false;

    @ConfigEntry(configName = "Stick deadzone", lowerBounds = 0.0, upperBounds = 0.9)
    public float stickDeadzone = 0.20F;

    @ConfigEntry(configName = "Trigger deadzone", lowerBounds = 0.0, upperBounds = 0.9)
    public float triggerDeadzone = 0.10F;

    @ConfigEntry(configName = "Look sensitivity", lowerBounds = 0.1, upperBounds = 5.0)
    public float lookSensitivity = 1.6F;

    @ConfigEntry(configName = "Look smoothing",
            configComment = "0 follows the stick instantly, 1 is heavily damped.",
            lowerBounds = 0.0, upperBounds = 1.0)
    public float lookSmoothing = 0.35F;

    @ConfigEntry(configName = "Invert look Y")
    public boolean invertLookY = false;

    @ConfigEntry(configName = "Cursor speed in menus", lowerBounds = 0.1, upperBounds = 5.0)
    public float cursorSpeed = 1.0F;

    @ConfigEntry(configName = "Show button glyphs")
    public boolean showGlyphs = true;

    @ConfigEntry(configName = "Crosshair pointer",
            configComment = "Hides the system cursor in menus and draws a crosshair instead.")
    public boolean padCursor = true;

    @ConfigEntry(configName = "Debug overlay",
            configComment = "Shows live stick, button and key-binding state on screen.")
    public boolean debugOverlay = false;

    @ConfigEntry(configName = "Rumble")
    public boolean rumble = true;

    @ConfigEntry(configName = "Rumble strength", lowerBounds = 0.0, upperBounds = 1.0)
    public float rumbleStrength = 0.85F;

    @ConfigEntry(configName = "Community link",
            configComment = "Where the button on the title screen goes.")
    public String communityUrl = "https://discord.gg/69MCawuuEC";

    @ConfigEntry(configName = "Start typing on the wheel",
            configComment = "The quick eight-way wheel rather than the grid of keys. "
                    + "Select switches between them while typing.")
    public boolean keyboardWheel = true;

    @ConfigEntry(configName = "Hide pointer in menus",
            configComment = "Menus are driven by the highlight, so the pointer is only in the way. "
                    + "Inventories keep theirs.")
    public boolean hideCursorInMenus = true;

    @ConfigEntry(configName = "Step back for the mouse",
            configComment = "Touching the mouse hides the pad interface until the pad is used again.")
    public boolean yieldToMouse = true;

    @ConfigEntry(configName = "Console-style inventory",
            configComment = "Replaces the inventory and crafting screens with the gamepad-friendly layout.")
    public boolean legacyInventory = true;

    public PadLayout.Family resolveForcedFamily() {
        switch (this.controllerType) {
            case XBOX:        return PadLayout.Family.XBOX;
            case PLAYSTATION: return PadLayout.Family.PLAYSTATION;
            case GENERIC:     return PadLayout.Family.GENERIC;
            case AUTO:
            default:          return null;
        }
    }
}
