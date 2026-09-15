package dev.servereer.machineconstruct.gui;

/**
 * What a GUI slot does (DESIGN.md §14). INPUT accepts items the recipe consumes;
 * OUTPUT is take-only (results land here); PROGRESS renders the live status bar;
 * DECOR is a locked, non-interactive icon (or empty). Every click on anything
 * but INPUT/OUTPUT is a no-op — the basis of the un-glitchable menu.
 */
public enum SlotRole {
    INPUT, OUTPUT, FUEL, PROGRESS, BURN, INFO, UPGRADE, TIER, SELL, DECOR
}
