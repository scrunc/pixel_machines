package dev.servereer.machineconstruct.machine;

/**
 * A machine's processing state (DESIGN.md §5). Doubles as the animation trigger
 * name (lower-cased) so {@code working:} / {@code blocked:} layers fire from the
 * same source of truth: IDLE = nothing to do, WORKING = crafting a recipe,
 * BLOCKED = finished but the output has nowhere to go.
 */
public enum MachineState {
    IDLE, WORKING, BLOCKED, NO_FUEL
}
