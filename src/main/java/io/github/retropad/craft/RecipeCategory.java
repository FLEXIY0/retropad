package io.github.retropad.craft;

import net.minecraft.common.item.Item;
import net.minecraft.common.item.ItemStack;

import java.util.Locale;

/**
 * The tabs down the side of the crafting menu.
 *
 * <p>ReIndev has no notion of a creative tab or any other grouping to borrow, so recipes are
 * sorted by what their result *is*: first by the item class the game already uses to give an
 * item its behaviour, then — for blocks, which are all one class — by name. Name matching is a
 * heuristic and will misfile the occasional block; it is the price of not maintaining a list of
 * several hundred item ids that would rot with every ReIndev update.
 */
public enum RecipeCategory {

    BUILDING("Building"),
    DECORATION("Decoration"),
    TOOLS("Tools"),
    COMBAT("Combat"),
    REDSTONE("Redstone"),
    TRANSPORT("Transport"),
    FOOD("Food"),
    MISC("Misc");

    public static final RecipeCategory[] VALUES = values();

    private final String label;

    RecipeCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return this.label;
    }

    /** Words that put a block in a category regardless of its class. */
    private static final String[] REDSTONE_WORDS = {
            "redstone", "lever", "button", "pressure", "piston", "dispenser", "repeater",
            "diode", "note", "detector", "tripwire", "observer"};
    private static final String[] TRANSPORT_WORDS = {
            "rail", "minecart", "boat", "saddle"};
    private static final String[] DECORATION_WORDS = {
            "torch", "flower", "rose", "sapling", "painting", "sign", "carpet", "glass",
            "wool", "cake", "bed", "chest", "crate", "ladder", "fence", "door", "trapdoor",
            "banner", "pot", "bookshelf", "jack"};

    public static RecipeCategory of(ItemStack output) {
        if (output == null) {
            return MISC;
        }
        Item item = output.getItem();
        if (item == null) {
            return MISC;
        }

        // Class first: an item's class is what the game itself uses to decide its behaviour,
        // so it is the most reliable signal available. Matching by simple name up the
        // hierarchy avoids importing a dozen classes for a handful of string comparisons.
        if (isKindOf(item, "ItemToolSword") || isKindOf(item, "ItemBow") || isKindOf(item, "ItemArmor")) {
            return COMBAT;
        }
        if (isKindOf(item, "ItemTool") || isKindOf(item, "ItemBucketBase") || isKindOf(item, "ItemShears")) {
            return TOOLS;
        }
        if (isKindOf(item, "ItemFood") || isKindOf(item, "ItemBowlSoup") || isKindOf(item, "ItemSeeds")) {
            return FOOD;
        }
        if (isKindOf(item, "ItemMinecart") || isKindOf(item, "ItemBoat")) {
            return TRANSPORT;
        }

        String name = displayName(output).toLowerCase(Locale.ROOT);
        if (containsAny(name, REDSTONE_WORDS)) {
            return REDSTONE;
        }
        if (containsAny(name, TRANSPORT_WORDS)) {
            return TRANSPORT;
        }
        if (containsAny(name, DECORATION_WORDS)) {
            return DECORATION;
        }
        return isKindOf(item, "ItemBlock") ? BUILDING : MISC;
    }

    private static String displayName(ItemStack stack) {
        try {
            String name = stack.getDisplayName();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            // Some items build their name from world state and throw on a menu screen.
            return "";
        }
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKindOf(Object object, String simpleClassName) {
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (type.getSimpleName().equals(simpleClassName)) {
                return true;
            }
        }
        return false;
    }
}
