package dev.servereer.machineconstruct.grinder.econ;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * A reflection-based bridge to Vault's economy service — no compile-time
 * dependency on Vault (mirrors how protection support is event-probed, ADR-style:
 * soft, optional). If Vault (or a provider) isn't present, {@link #available()}
 * is false and deposits/withdrawals are no-ops the caller can detect.
 */
public final class EconomyBridge {

    private Object economy;            // net.milkbowl.vault.economy.Economy
    private Method depositMethod;      // depositPlayer(OfflinePlayer, double) → EconomyResponse
    private Method withdrawMethod;     // withdrawPlayer(OfflinePlayer, double)
    private Method hasMethod;          // has(OfflinePlayer, double) → boolean

    public EconomyBridge() {
        try {
            Class<?> econClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(econClass);
            if (rsp == null) return;
            economy = rsp.getProvider();
            depositMethod = econClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            withdrawMethod = econClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            hasMethod = econClass.getMethod("has", OfflinePlayer.class, double.class);
        } catch (Throwable ignored) {
            economy = null;   // Vault absent or API shape unexpected → unavailable
        }
    }

    public boolean available() { return economy != null; }

    public boolean has(OfflinePlayer p, double amount) {
        if (economy == null) return false;
        try { return (boolean) hasMethod.invoke(economy, p, amount); }
        catch (Throwable t) { return false; }
    }

    /** Deposit money; returns true on success. */
    public boolean deposit(OfflinePlayer p, double amount) {
        if (economy == null || amount <= 0) return false;
        try { depositMethod.invoke(economy, p, amount); return true; }
        catch (Throwable t) { return false; }
    }

    /** Withdraw money; returns true on success (call {@link #has} first). */
    public boolean withdraw(OfflinePlayer p, double amount) {
        if (economy == null || amount <= 0) return false;
        try { withdrawMethod.invoke(economy, p, amount); return true; }
        catch (Throwable t) { return false; }
    }
}
