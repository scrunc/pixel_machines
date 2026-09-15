package dev.servereer.machineconstruct.model;

/**
 * Binds a model part's displayed item to the machine's live state (DESIGN.md
 * §9/§10) — the "show the material being processed" feature, kept 0-hardcoded:
 * the part is authored in YAML, only its item is dynamic. Written as the
 * {@code item:} value {@code <input>} / {@code <output>} (alias {@code <processing>}/
 * {@code <result>}). Resolved each frame in {@code ModelNode.contentAt}.
 */
public enum ItemBinding {
    NONE, INPUT, OUTPUT;

    public static ItemBinding from(String value) {
        if (value == null) return NONE;
        return switch (value.trim().toLowerCase()) {
            case "<input>", "<processing>" -> INPUT;
            case "<output>", "<result>" -> OUTPUT;
            default -> NONE;
        };
    }
}
