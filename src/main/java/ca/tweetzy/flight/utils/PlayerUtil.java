/*
 * Flight
 * Copyright 2022 Kiran Hart
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ca.tweetzy.flight.utils;

import ca.tweetzy.flight.comp.enums.CompMaterial;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang.ArrayUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Set;

/**
 * Date Created: April 07 2022
 * Time Created: 2:46 p.m.
 *
 * @author Kiran Hart
 */
@UtilityClass
public final class PlayerUtil {

    /**
     * Gives an item to a player, or drops on the ground if their inventory is full
     *
     * @param player The player to give the item to
     * @param item   The ItemStack to give or drop
     */
    public void giveItem(@NonNull final Player player, @NonNull final ItemStack item) {
        if (player.getInventory().firstEmpty() == -1) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        } else {
            player.getInventory().addItem(item);
        }
    }

    /**
     * Returns true if the player's hand is empty.
     *
     * @param player The player to check the hand of.
     *
     * @return true if empty
     */
    public boolean isHandEmpty(@NonNull final Player player) {
        return getHand(player).getType() == CompMaterial.AIR.parseMaterial();
    }

    /**
     * Returns the item in the player's hand, or null if the player is not holding anything.
     *
     * @param player The player to get the item from.
     *
     * @return The item in the player's hand.
     */
    public ItemStack getHand(@NonNull final Player player) {
        return player.getInventory().getItemInMainHand();
    }

    /**
     * It gets the highest number from a permission node
     *
     * @param player     The player to check the permission for.
     * @param permission The permission to check.
     * @param def        The default value to return if no permission is found.
     *
     * @return The highest number in the permission.
     */
    public int getNumberPermission(@NonNull final Player player, @NonNull final String permission, final int def) {
        final Set<PermissionAttachmentInfo> permissions = player.getEffectivePermissions();

        boolean set = false;
        int highest = 0;

        for (PermissionAttachmentInfo info : permissions) {
            final String perm = info.getPermission();

            if (!perm.startsWith(permission)) {
                continue;
            }

            final int index = perm.lastIndexOf('.');

            if (index == -1 || index == perm.length()) {
                continue;
            }

            String numStr = perm.substring(perm.lastIndexOf('.') + 1);

            if (numStr.equals("*")) {
                return def;

            }

            if (!numStr.matches("\\d+")) {
                continue;
            }

            final int number = Integer.parseInt(numStr);

            if (number >= highest) {
                highest = number;
                set = true;
            }
        }

        return set ? highest : def;
    }

    /**
     * If the player's inventory is empty, return true, otherwise return false.
     *
     * @param player The player to check the inventory of.
     *
     * @return A boolean value.
     */
    public boolean isInventoryEmpty(@NonNull final Player player) {
        final ItemStack[] everything = (ItemStack[]) ArrayUtils.addAll(player.getInventory().getContents(), player.getInventory().getArmorContents());

        for (final ItemStack i : everything) {
            if (i != null && i.getType() != Material.AIR) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get the total amount of an item in the player's inventory
     *
     * @param player is the player being checked
     * @param stack  is the item you want to find
     *
     * @return the total count of the item(s)
     */
    public int getItemCountInPlayerInventory(@NonNull final Player player, ItemStack stack) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.isSimilar(stack)) continue;
            total += item.getAmount();
        }
        return total;
    }

    /**
     * Removes a set amount of a specific item from the player inventory
     *
     * @param player is the player you want to remove the item from
     * @param stack  is the item that you want to remove
     * @param amount is the amount of items you want to remove.
     */
    public void removeSpecificItemQuantityFromPlayer(@NonNull final Player player, @NonNull final ItemStack stack, int amount) {
        int i = amount;
        for (int j = 0; j < player.getInventory().getSize(); j++) {
            ItemStack item = player.getInventory().getItem(j);
            if (item == null) continue;
            if (!item.isSimilar(stack)) continue;

            if (i >= item.getAmount()) {
                player.getInventory().clear(j);
                i -= item.getAmount();
            } else if (i > 0) {
                item.setAmount(item.getAmount() - i);
                i = 0;
            } else {
                break;
            }
        }
    }
}

