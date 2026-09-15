package dev.servereer.machineconstruct.machine;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A processing recipe (DESIGN.md §5): a set of input items consumed to produce a
 * set of output items over {@code timeTicks}. Items are matched by material +
 * amount (vanilla; no NBT in v1). The machine reads/writes its anchor chest as
 * the I/O buffer, so a recipe needs no slot layout yet.
 */
public final class Recipe {

    private final List<ItemStack> inputs;
    private final List<ItemStack> outputs;
    private final int timeTicks;

    public Recipe(List<ItemStack> inputs, List<ItemStack> outputs, int timeTicks) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.timeTicks = Math.max(1, timeTicks);
    }

    public List<ItemStack> inputs() { return inputs; }
    public List<ItemStack> outputs() { return outputs; }
    public int timeTicks() { return timeTicks; }
}
