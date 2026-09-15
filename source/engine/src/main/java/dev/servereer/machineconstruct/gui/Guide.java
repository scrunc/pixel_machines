package dev.servereer.machineconstruct.gui;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;

/**
 * Player-facing help: a virtual written book opened by {@code /mc guide}. Plain-language
 * pages explaining the shared loop + each machine. Uses dark colours (book parchment is light).
 */
public final class Guide {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Guide() { }

    public static Book book() {
        return Book.book(MM.deserialize("<dark_aqua>Machine Guide"), MM.deserialize("Servereer"), List.of(
                page(
                        "<dark_aqua><bold>How machines work</bold>",
                        "",
                        "<black>1. <dark_gray>Right-click a machine's <black>chest<dark_gray> to open its menu.",
                        "<black>2. <dark_gray>Feed it items to <black>build / upgrade<dark_gray>.",
                        "<black>3. <dark_gray>It keeps working <black>even while you're offline<dark_gray>.",
                        "<black>4. <dark_gray>Open it again to <black>collect<dark_gray> what it made.",
                        "",
                        "<dark_gray>Break the chest to get the machine back."),
                page(
                        "<dark_green><bold>Factory District</bold>",
                        "",
                        "<dark_gray>A town of farms that runs itself.",
                        "<black>• <dark_gray>Click a slot, feed blocks to <black>build a farm<dark_gray>.",
                        "<black>• <dark_gray>Build a <black>House<dark_gray> for <black>workers<dark_gray>, then assign them to farms.",
                        "<black>• <dark_gray>Output piles into the <black>Quantum Vault<dark_gray> — withdraw from there.",
                        "",
                        "<dark_gray>More workers, richness, soil & boosters = more output."),
                page(
                        "<dark_red><bold>Dimensional Grinder</bold>",
                        "",
                        "<dark_gray>Install spawners; it grinds mobs while you're away.",
                        "<black>• <dark_gray>Loot collects in a <black>pool<dark_gray>.",
                        "<black>• <dark_gray><black>Collect<dark_gray> items, <black>claim XP<dark_gray> (repairs Mending gear), or <black>sell<dark_gray> the pool."),
                page(
                        "<dark_blue><bold>Interdimensional Quarry</bold>",
                        "",
                        "<dark_gray>Mines & fishes other dimensions on a timer.",
                        "<black>• <dark_gray>Yield fills the vault; <black>contracts<dark_gray> give bonus rewards.",
                        "<black>• <dark_gray>When a contract is ready, <black>click to claim<dark_gray> it."),
                page(
                        "<dark_purple><bold>Trading Hall</bold>",
                        "",
                        "<dark_gray>Your own shop villager.",
                        "<black>• <dark_gray>Owners stock goods & set it Public.",
                        "<black>• <dark_gray>Customers buy from their inventory; coins land in your <black>till<dark_gray> — Collect it.")
        ));
    }

    private static Component page(String... lines) {
        return MM.deserialize(String.join("\n", lines));
    }
}
