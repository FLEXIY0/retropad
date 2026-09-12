package io.github.retropad;

import com.fox2code.foxevents.EventHandler;
import com.fox2code.foxloader.client.gui.GuiButtonCallback;
import com.fox2code.foxloader.event.client.CameraAndRenderUpdatedEvent;
import com.fox2code.foxloader.event.client.GuiScreenInitEvent;
import com.fox2code.foxloader.loader.Mod;
import io.github.retropad.craft.CraftExecutor;
import io.github.retropad.craft.PadCraftingOverlay;
import io.github.retropad.game.PadGameplayInput;
import io.github.retropad.game.PadScreenInput;
import io.github.retropad.gui.PadCursor;
import io.github.retropad.gui.GuiButtonCommunity;
import io.github.retropad.gui.PadHintBar;
import io.github.retropad.input.GamepadManager;
import io.github.retropad.input.PadInputMode;
import io.github.retropad.input.PadRumble;
import io.github.retropad.mixins.GuiButtonAccessor;
import io.github.retropad.input.GamepadState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import java.util.List;
import org.slf4j.Logger;

/**
 * Entry point. Polls the gamepad once per rendered frame and dispatches it to whichever
 * consumer owns input right now: the open screen, or the world.
 */
public final class RetroPad extends Mod {

    public static final PadConfig CONFIG = new PadConfig();

    /** Longest frame time that still counts as one frame, in seconds. A stall — a chunk load,
     *  a window drag — must not fling the camera when the game resumes. */
    private static final float MAX_FRAME_SECONDS = 0.1F;

    /** Assigned in {@link #onPreInit()}; do not touch before the mod is constructed. */
    public static Logger LOGGER;

    private static RetroPad instance;

    private final GamepadManager gamepad = new GamepadManager();

    /** The health the player had last frame; {@code MIN_VALUE} when there is no player. */
    /**
     * Four points — two hearts — which is the exact number at which the game starts shaking the
     * hearts on the HUD. Feeling it in the hands at the same moment it appears on screen is the
     * point: one thing happening, said twice.
     */
    private static final int LOW_HEALTH = 4;
    /** The beat at two hearts, and how much faster it gets with each one lost after that. */
    private static final long HEARTBEAT_INTERVAL_MS = 1150L;
    private static final long HEARTBEAT_QUICKEN_MS = 150L;
    private static final long HEARTBEAT_GAP_MS = 170L;
    /** How hard it beats at two hearts, and how much harder per heart below that. */
    private static final float HEARTBEAT_BASE = 0.46F;
    private static final float HEARTBEAT_PER_POINT = 0.18F;

    /** One breath, and how long it takes to draw the next one. */
    private static final long BREATH_INTERVAL_MS = 900L;
    private static final long BREATH_GAP_MS = 260L;

    private int lastHealth = Integer.MIN_VALUE;
    private long nextHeartbeatAt;
    private final PadGameplayInput gameplayInput = new PadGameplayInput();
    private final PadScreenInput screenInput = new PadScreenInput();

    private long lastFrameNanos;

    public RetroPad() {
        instance = this;
    }

    public static RetroPad getInstance() {
        return instance;
    }

    public static GamepadManager gamepad() {
        return instance == null ? null : instance.gamepad;
    }

    public static PadScreenInput screenInput() {
        return instance == null ? null : instance.screenInput;
    }

    public static PadGameplayInput gameplayInput() {
        return instance == null ? null : instance.gameplayInput;
    }

    /** True when a pad is connected and enabled — the flag every mixin checks first. */
    public static boolean isPadActive() {
        return instance != null && CONFIG.enabled && instance.gamepad.isConnected();
    }

    @Override
    public void onPreInit() {
        LOGGER = this.getSlf4jLogger();
        this.setConfigObject(CONFIG);
    }

    @Override
    public void onInit() {
        // Load the classes this mod rewrites now rather than when the first world does.
        //
        // A mixin is only applied when its target is first loaded, and these three are loaded
        // deep inside the game — the renderers when an item is first drawn, the HUD when a world
        // opens. Left alone, a mistake in a redirect would surface as a crash on entering a
        // world instead of a complaint at startup. Loading them without initialising runs the
        // transformer, which is the part that can fail, but not their static setup.
        ClassLoader loader = this.getClass().getClassLoader();
        for (String rewritten : new String[]{
                "net.minecraft.client.renderer.entity.RenderItem",
                "net.minecraft.client.renderer.world.RenderBlocks",
                "net.minecraft.client.gui.GuiIngame",
                "net.minecraft.client.player.PlayerController"}) {
            try {
                Class.forName(rewritten, false, loader);
            } catch (Throwable throwable) {
                LOGGER.warn(rewritten + " could not be prepared, so part of the pad interface "
                        + "will be missing: " + throwable);
            }
        }
    }

    /**
     * Puts a Crafting button on every screen that has a crafting grid, so the menu is reachable
     * with a mouse too and not only from the pad's shoulder button.
     */
    @EventHandler
    public void onInitGui(GuiScreenInitEvent event) {
        GuiScreen screen = event.getGuiScreen();
        if (screen instanceof GuiMainMenu) {
            // Under Ko-fi, on the same 21 pixel pitch the other four keep.
            event.getControlList().add(new GuiButtonCommunity(1, 85));
        }
        liftControlsAboveHints(screen, event.getControlList());
        if (!(screen instanceof GuiContainer)) {
            return;
        }
        final GuiContainer container = (GuiContainer) screen;
        if (CraftExecutor.resultSlot(container.inventorySlots) == null) {
            return;
        }
        event.getControlList().add(new GuiButtonCallback(600, 4, 4, 64, 20, "Crafting", new Runnable() {
            @Override
            public void run() {
                PadCraftingOverlay.get().open(container);
            }
        }));
    }

    /**
     * Fires once per rendered frame, which is what analog look needs; the 20 Hz tick would
     * make the camera visibly steppy.
     *
     * <p>The event hands out the partial-tick phase, not a frame duration, so the real elapsed
     * time is measured here instead.
     */
    @EventHandler
    public void onFrame(CameraAndRenderUpdatedEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        long nanos = System.nanoTime();
        float deltaSeconds = this.lastFrameNanos == 0L
                ? 0.0F : (nanos - this.lastFrameNanos) / 1_000_000_000.0F;
        this.lastFrameNanos = nanos;
        if (deltaSeconds > MAX_FRAME_SECONDS) {
            deltaSeconds = MAX_FRAME_SECONDS;
        }

        long now = System.currentTimeMillis();
        boolean wasConnected = this.gamepad.isConnected();
        this.gamepad.update(CONFIG, now);
        PadRumble.update(now);
        if (!wasConnected && this.gamepad.isConnected()) {
            // A pad that has just been found says so in the hand holding it.
            PadRumble.play(PadRumble.Effect.CONNECT);
        }
        if (!isPadActive()) {
            PadRumble.stop();
            this.screenInput.onScreenClosed();
            PadCursor.restore();
            return;
        }

        this.watchForDamage(mc);
        this.watchForEffort(mc);

        GamepadState state = this.gamepad.getState();
        PadInputMode.update(state);
        if (mc.currentScreen != null) {
            this.gameplayInput.releaseAll();
            this.screenInput.update(mc, state, now, deltaSeconds);
        } else {
            this.screenInput.onScreenClosed();
            this.gameplayInput.update(mc, state, deltaSeconds);
        }
    }

    /**
     * Moves a screen's buttons up out of the prompt band.
     *
     * <p>Screens put their last row of buttons against the bottom edge, which is exactly where
     * the band goes, so on the world list the prompts landed across Rename, Delete and Cancel.
     * Drawing the band on top hid them; drawing it elsewhere would put it somewhere arbitrary.
     * Moving the buttons is the only answer that leaves both readable — and it is done here,
     * when the screen lays itself out, so that what is clicked and what is drawn stay the same
     * thing. A screen re-lays itself out on every resize, so the shift is never applied twice.
     */
    private static void liftControlsAboveHints(GuiScreen screen, List<?> controls) {
        if (screen == null || controls == null || !isPadActive() || !CONFIG.showGlyphs) {
            return;
        }
        float limit = screen.height - PadHintBar.reservedHeight();
        float lowest = 0.0F;
        for (Object control : controls) {
            if (!(control instanceof GuiButton)) {
                continue;
            }
            GuiButton button = (GuiButton) control;
            lowest = Math.max(lowest,
                    button.yPosition + ((GuiButtonAccessor) button).retropad$getHeight());
        }
        if (lowest <= limit) {
            return;
        }
        int lift = (int) Math.ceil(lowest - limit);
        for (Object control : controls) {
            if (control instanceof GuiButton) {
                ((GuiButton) control).yPosition -= lift;
            }
        }
    }

    /**
     * Turns a drop in health into a thump.
     *
     * <p>Watched here rather than hooked: every way of losing health — a mob, a fall, drowning,
     * poison — passes through the same number, so one comparison covers all of them and there is
     * no damage path left without feedback.
     */
    private void watchForDamage(Minecraft mc) {
        if (mc.thePlayer == null) {
            this.lastHealth = Integer.MIN_VALUE;
            return;
        }
        int health = mc.thePlayer.health;
        if (this.lastHealth != Integer.MIN_VALUE && health < this.lastHealth) {
            // A graze and a fall down a ravine are not the same thump.
            int lost = this.lastHealth - health;
            PadRumble.play(PadRumble.Effect.HURT, 0.45F + lost * 0.11F);
        }
        this.lastHealth = health;
    }

    /**
     * The slower things the body would feel: chewing through a meal, and your own heartbeat
     * once there is little of it left to lose.
     */
    private void watchForEffort(Minecraft mc) {
        if (mc.thePlayer == null) {
            return;
        }
        try {
            if (mc.thePlayer.isUsingItem()) {
                PadRumble.pulse(PadRumble.Effect.USE, 130L);
            }
        } catch (Throwable ignored) {
            // An item that cannot say whether it is in use simply does not buzz.
        }
        this.watchForBreath(mc);

        long now = System.currentTimeMillis();
        int health = mc.thePlayer.health;
        if (health > 0 && health <= LOW_HEALTH && now >= this.nextHeartbeatAt) {
            // Every point below two hearts beats harder and sooner, so the last heart is a
            // hammering and the second-to-last is only a knock.
            int below = LOW_HEALTH - health;
            this.nextHeartbeatAt = now + Math.max(450L,
                    HEARTBEAT_INTERVAL_MS - below * HEARTBEAT_QUICKEN_MS);
            PadRumble.playTwice(PadRumble.Effect.HEARTBEAT, HEARTBEAT_GAP_MS,
                    HEARTBEAT_BASE + below * HEARTBEAT_PER_POINT);
        }
    }

    /**
     * The wind knocked out of you.
     *
     * <p>ReIndev already knows the state — {@code isExhausted} is what greys the sprint bar and
     * refuses to let you run — so this is the same fact in the hands: two heavy pulls, a pause,
     * two more, until the breath comes back. Low frequency only; a buzz would read as a machine
     * rather than as a body.
     */
    private void watchForBreath(Minecraft mc) {
        try {
            if (mc.thePlayer.isExhausted()) {
                PadRumble.pulseTwice(PadRumble.Effect.BREATH, BREATH_INTERVAL_MS, BREATH_GAP_MS);
            }
        } catch (Throwable ignored) {
            // A version without that state simply does not breathe.
        }
    }
}
