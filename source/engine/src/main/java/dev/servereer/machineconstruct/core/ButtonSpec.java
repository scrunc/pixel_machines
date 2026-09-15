package dev.servereer.machineconstruct.core;

/**
 * A model part that behaves like a physical BUTTON: it glows for the viewer looking at it,
 * sinks in when clicked, and runs an {@code action}. Authored on any block part:
 * <pre>
 * week_btn:
 *   block: "${rivet}"
 *   offset: [...]; scale: [0.30, 0.06, 0.04]
 *   button:
 *     action: "state:set:weekly"     # state:next | state:prev | state:set:<name> | refresh |
 *                                    # command:<as player> | console:<cmd> | message:<MiniMessage>
 *     hover: "#35e0d0"               # outline glow colour while the viewer aims at it
 *     lit: sea_lantern               # block shown while lit_when matches the panel's state
 *     lit_when: weekly
 *     depth: 0.03                    # how far it sinks (along the part's +Z, i.e. into the face)
 *     label: week_lbl                # part(s) that sink with it (its caption); defaults to lbl_X for a btn_X part
 * </pre>
 */
public record ButtonSpec(String action, int hoverArgb, DisplayContent normal, DisplayContent lit, String litWhen, double depth, String label) {
    public boolean hasLit() { return lit != null && litWhen != null; }
}
