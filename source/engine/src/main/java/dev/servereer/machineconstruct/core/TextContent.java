package dev.servereer.machineconstruct.core;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;

/** A {@code text_display}'s payload — MiniMessage text + line width / background / opacity. */
public final class TextContent implements DisplayContent {

    private static final int META_TEXT = 23;
    private static final int META_LINE_WIDTH = 24;
    private static final int META_BACKGROUND = 25;
    private static final int META_OPACITY = 26;
    private static final int META_FLAGS = 27;

    private final String template;   // the authored MiniMessage (may hold %placeholders%)
    private final String current;    // what is displayed right now (resolved), for change detection
    private final Component text;
    private final int lineWidth;
    private final int background;   // ARGB; 0 = transparent
    private final byte opacity;     // -1 = 255 (opaque)
    private final byte flags;       // 0x01 shadow, 0x02 see-through, 0x04 default-bg

    public TextContent(String miniMessage, int lineWidth, int background, int opacity, byte flags) {
        this.template = miniMessage == null ? "" : miniMessage;
        this.current = this.template;
        this.text = MiniMessage.miniMessage().deserialize(this.template);
        this.lineWidth = lineWidth;
        this.background = background;
        this.opacity = (byte) opacity;
        this.flags = flags;
    }

    /** The authored text, before any placeholder resolution. */
    public String template() { return template; }

    /** The text currently displayed (after the last resolve). */
    public String current() { return current; }

    /** True if the text carries {@code %placeholders%} that a live refresh should resolve. */
    public boolean isLive() { return template.indexOf('%') >= 0 && template.indexOf('%') != template.lastIndexOf('%'); }

    /** The same text with a different wrap width / alignment (flags bits 0x08 left, 0x10 right). */
    public TextContent withLayout(int newLineWidth, String align) {
        byte f = (byte) (flags & ~0x18);
        if ("left".equalsIgnoreCase(align)) f |= 0x08; else if ("right".equalsIgnoreCase(align)) f |= 0x10;
        TextContent c = new TextContent(current, newLineWidth, background, opacity, f, template);
        return c;
    }
    public int lineWidth() { return lineWidth; }
    public String align() { return (flags & 0x08) != 0 ? "left" : (flags & 0x10) != 0 ? "right" : "center"; }

    /** The same styling with a different (resolved) text. */
    public TextContent withText(String resolved) {
        return new TextContent(resolved, lineWidth, background, opacity, flags, template);
    }

    private TextContent(String resolved, int lineWidth, int background, byte opacity, byte flags, String template) {
        this.template = template;
        this.current = resolved == null ? "" : resolved;
        this.text = MiniMessage.miniMessage().deserialize(this.current);
        this.lineWidth = lineWidth;
        this.background = background;
        this.opacity = opacity;
        this.flags = flags;
    }

    @Override
    public boolean centerAnchored() {
        return true;   // text displays render centered on their position
    }

    @Override
    public EntityType entityType() {
        return EntityTypes.TEXT_DISPLAY;
    }

    @Override
    public void appendMeta(List<EntityData<?>> data) {
        data.add(new EntityData<>(META_TEXT, EntityDataTypes.ADV_COMPONENT, text));
        data.add(new EntityData<>(META_LINE_WIDTH, EntityDataTypes.INT, lineWidth));
        data.add(new EntityData<>(META_BACKGROUND, EntityDataTypes.INT, background));
        data.add(new EntityData<>(META_OPACITY, EntityDataTypes.BYTE, opacity));
        data.add(new EntityData<>(META_FLAGS, EntityDataTypes.BYTE, flags));
    }
}
