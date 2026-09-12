package io.github.retropad.game;

import io.github.retropad.PadConfig;
import io.github.retropad.RetroPad;
import io.github.retropad.input.GamepadState;
import io.github.retropad.input.PadAxis;
import io.github.retropad.input.PadButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.player.EntityPlayerSP;
import net.minecraft.client.util.GameSettings;
import net.minecraft.client.util.KeyBinding;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Drives the player from the pad while no screen is open.
 *
 * <p>Actions run through the game's own key bindings so that attacking, using items and the
 * rest take the paths the game already has, and respect whatever the player rebound them to.
 * Only analog movement and look are applied directly, because a keyboard cannot express them.
 *
 * <p>The pad is read once per rendered frame, but the bindings are written once per game tick,
 * from {@link #applyForTick()}. That split matters. A key binding carries two pieces of state:
 * {@code pressed}, which the game re-syncs from real hardware and therefore clears out from
 * under a synthesised hold, and {@code pressTime}, a counter that {@code isPressed()} drains
 * and that {@code setKeyBindState} never touches. Writing both at the head of the tick that
 * consumes them is the only placement where neither can be missed or clobbered.
 */
public final class PadGameplayInput {

    /** Degrees of yaw per second at full stick deflection and sensitivity 1.0. */
    private static final float LOOK_DEGREES_PER_SECOND = 190.0F;
    /** How many ticks a tapped action is held down for. Two, so that both the press-counter
     *  consumers and the edge-detecting ones see it. */
    private static final int TAP_TICKS = 2;

    /** Analog movement, read back by the MovementInput mixin. */
    private float moveForward;
    private float moveStrafe;
    private boolean jump;
    private boolean sneak;
    private boolean active;

    /** Smoothed look input, kept between frames so the camera does not step. */
    private float lookX;
    private float lookY;

    /** Bindings the pad is holding down right now. */
    private final Set<KeyBinding> heldBindings =
            Collections.newSetFromMap(new IdentityHashMap<KeyBinding, Boolean>());
    /** Bindings tapped by the pad, with the number of ticks they still stay down. */
    private final Map<KeyBinding, Integer> pendingTaps = new IdentityHashMap<>();
    /** Holds that began since the last tick, and so still owe the game a counted press. */
    private final Set<KeyBinding> freshHolds =
            Collections.newSetFromMap(new IdentityHashMap<KeyBinding, Boolean>());

    public boolean isActive() {
        return this.active;
    }

    public float getMoveForward() {
        return this.moveForward;
    }

    public float getMoveStrafe() {
        return this.moveStrafe;
    }

    public boolean isJumping() {
        return this.jump;
    }

    public boolean isSneaking() {
        return this.sneak;
    }

    /**
     * @param deltaSeconds real time since the previous frame. The render hook hands out the
     *     partial-tick phase, which sweeps 0 to 1 every tick and is not a duration — using it
     *     as one made the camera lurch in time with the tick boundary.
     */
    public void update(Minecraft mc, GamepadState state, float deltaSeconds) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !mc.inGameHasFocus) {
            this.releaseAll();
            return;
        }
        PadConfig config = RetroPad.CONFIG;
        GameSettings settings = mc.gameSettings;

        this.applyMovement(state);
        this.applyLook(player, state, config, deltaSeconds);

        this.hold(settings.keyBindAttack, state.isHeld(PadButton.R2));
        this.hold(settings.keyBindUseItem, state.isHeld(PadButton.L2));
        this.hold(settings.keyBindSprint, state.isHeld(PadButton.L3));
        // Zoom is a held key, not a tap: it lasts as long as the d-pad is pressed, the way
        // holding C does.
        this.hold(settings.keyBindZoom, state.isHeld(PadButton.DPAD_DOWN));
        this.hold(settings.keyBindPlayerList, state.isHeld(PadButton.SELECT));

        if (state.isPressed(PadButton.NORTH)) {
            this.tap(settings.keyBindInventory);
        }
        if (state.isPressed(PadButton.WEST)) {
            this.tap(settings.keyBindDrop);
        }
        if (state.isPressed(PadButton.R3)) {
            this.tap(settings.keyBindThirdPerson);
        }
        if (state.isPressed(PadButton.DPAD_UP)) {
            this.tap(settings.keyBindChat);
        }
        if (state.isPressed(PadButton.DPAD_LEFT)) {
            this.tap(settings.keyBindPickBlock);
        }
        if (state.isPressed(PadButton.DPAD_RIGHT)) {
            // What F1 does. Worth a button of its own on a pad: the prompt row and the HUD are
            // exactly what is in the way of a screenshot or a look at the world.
            settings.hideGUI = !settings.hideGUI;
        }

        // Hotbar on the shoulder buttons. changeCurrentItem takes a scroll-wheel delta, where
        // a positive value walks the hotbar left.
        long now = System.currentTimeMillis();
        if (state.isPressedOrRepeated(PadButton.L1, now)) {
            player.inventory.changeCurrentItem(1);
        }
        if (state.isPressedOrRepeated(PadButton.R1, now)) {
            player.inventory.changeCurrentItem(-1);
        }

        // There is no key binding for the pause menu; open it the way Escape would.
        if (state.isPressed(PadButton.START)) {
            this.releaseAll();
            mc.displayGuiScreen(new GuiIngameMenu());
        }

        this.active = true;
    }

    /**
     * Writes the pad's actions into the key bindings. Called at the head of the game tick,
     * immediately before the tick reads them.
     */
    public void applyForTick() {
        this.assertHeldBindings();

        if (!this.freshHolds.isEmpty()) {
            for (KeyBinding binding : this.freshHolds) {
                // A real mouse press bumps this counter as well as setting the flag, and the
                // tick drains it in a `while (isPressed())` loop. Setting only the flag left
                // that loop starved, so the first click of a hold never happened through it.
                binding.pressTime++;
            }
            this.freshHolds.clear();
        }

        Iterator<Map.Entry<KeyBinding, Integer>> taps = this.pendingTaps.entrySet().iterator();
        while (taps.hasNext()) {
            Map.Entry<KeyBinding, Integer> tap = taps.next();
            KeyBinding binding = tap.getKey();
            int ticksLeft = tap.getValue();
            if (ticksLeft == TAP_TICKS) {
                binding.pressed = true;
                // The counter behind isPressed(), which is what opens the inventory, drops an
                // item or opens chat. setKeyBindState leaves it alone, so bump it here.
                binding.pressTime++;
            }
            ticksLeft--;
            if (ticksLeft <= 0) {
                if (!this.heldBindings.contains(binding)) {
                    binding.pressed = false;
                }
                taps.remove();
            } else {
                tap.setValue(ticksLeft);
            }
        }
    }

    /**
     * Just the held actions, with no tap bookkeeping. Safe to call more than once per tick,
     * which the mixin does so that the flags are fresh at each point the tick reads them.
     */
    public void assertHeldBindings() {
        for (KeyBinding binding : this.heldBindings) {
            binding.pressed = true;
        }
    }

    private void hold(KeyBinding binding, boolean down) {
        if (binding == null) {
            return;
        }
        if (down) {
            if (this.heldBindings.add(binding)) {
                this.freshHolds.add(binding);
            }
        } else if (this.heldBindings.remove(binding) && !this.pendingTaps.containsKey(binding)) {
            binding.pressed = false;
            this.freshHolds.remove(binding);
        }
    }

    /** True when the pad is the reason a binding is down. */
    public boolean isHolding(KeyBinding binding) {
        return binding != null && this.heldBindings.contains(binding);
    }

    private void tap(KeyBinding binding) {
        if (binding != null) {
            this.pendingTaps.put(binding, TAP_TICKS);
        }
    }

    private void applyMovement(GamepadState state) {
        float x = state.getAxis(PadAxis.LEFT_X);
        float y = state.getAxis(PadAxis.LEFT_Y);
        // The game treats forward as +1 and the stick reports up as negative.
        this.moveForward = -y;
        this.moveStrafe = -x;
        this.jump = state.isHeld(PadButton.SOUTH);
        this.sneak = state.isHeld(PadButton.EAST);
    }

    private void applyLook(EntityPlayerSP player, GamepadState state, PadConfig config, float deltaSeconds) {
        float targetX = signedSquare(state.getAxis(PadAxis.RIGHT_X));
        float targetY = signedSquare(state.getAxis(PadAxis.RIGHT_Y));

        // A first-order filter on the stick reading. The pad is sampled once per frame at
        // whatever rate the game happens to render, and raw samples jitter by a few percent;
        // easing towards the target turns that into a steady sweep without adding real lag.
        float blend = Math.min(1.0F, deltaSeconds * (1.0F + 24.0F * (1.0F - config.lookSmoothing)));
        this.lookX += (targetX - this.lookX) * blend;
        this.lookY += (targetY - this.lookY) * blend;

        if (Math.abs(this.lookX) < 0.0005F && Math.abs(this.lookY) < 0.0005F) {
            this.lookX = 0.0F;
            this.lookY = 0.0F;
            return;
        }

        float scale = config.lookSensitivity * LOOK_DEGREES_PER_SECOND * deltaSeconds;
        player.rotationYaw += this.lookX * scale;
        player.rotationPitch += this.lookY * scale * (config.invertLookY ? -1.0F : 1.0F);
        if (player.rotationPitch < -90.0F) {
            player.rotationPitch = -90.0F;
        } else if (player.rotationPitch > 90.0F) {
            player.rotationPitch = 90.0F;
        }
        // Collapse the interpolation window the way the game's own mouse handling does,
        // otherwise the renderer blends towards an angle that has already moved on.
        player.prevRotationYaw = player.rotationYaw;
        player.prevRotationPitch = player.rotationPitch;
    }

    private static float signedSquare(float value) {
        return value * Math.abs(value);
    }

    /** Lets go of everything this mod is holding — when a screen opens, or focus is lost. */
    public void releaseAll() {
        for (KeyBinding binding : this.heldBindings) {
            binding.pressed = false;
        }
        this.heldBindings.clear();
        this.freshHolds.clear();
        for (KeyBinding binding : this.pendingTaps.keySet()) {
            binding.pressed = false;
        }
        this.pendingTaps.clear();

        this.moveForward = 0.0F;
        this.moveStrafe = 0.0F;
        this.jump = false;
        this.sneak = false;
        this.lookX = 0.0F;
        this.lookY = 0.0F;
        this.active = false;
    }

    /** Names of the bindings the pad is holding, for the debug overlay. */
    public String describeHeldBindings() {
        if (this.heldBindings.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (KeyBinding binding : this.heldBindings) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(binding.keyDescription).append(binding.pressed ? "" : "(cleared!)");
        }
        return builder.toString();
    }
}
