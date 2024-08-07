/*
 * Flight
 * Copyright 2022-2022 Kiran Hart
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

import de.tr7zw.changeme.nbtapi.NBT;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

@UtilityClass
public final class SerializeUtil {

    public String serializeLocation(final Location location) {
        if (location == null)
            return "";

        return String.format(
                "%s %f %f %f %f %f",
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    public Location deserializeLocation(final String raw) {
        final String[] split = raw.split(" ");
        return new Location(
                Bukkit.getWorld(split[0]),
                Double.parseDouble(split[1].replace(",", ".")),
                Double.parseDouble(split[2].replace(",", ".")),
                Double.parseDouble(split[3].replace(",", ".")),
                Float.parseFloat(split[4].replace(",", ".")),
                Float.parseFloat(split[5].replace(",", "."))
        );
    }

    public String encodeItem(@NonNull final ItemStack itemStack) {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("i", itemStack);
        return config.saveToString();
    }

    @SneakyThrows
    public ItemStack decodeItem(@NonNull final String string) {
        final YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(string);
        } catch (IllegalArgumentException | InvalidConfigurationException e) {
            return null;
        }
        return config.getItemStack("i", null);
    }

    public String itemToString(final ItemStack itemStack) {
        return NBT.itemStackToNBT(itemStack).toString();
    }

    public String itemsToString(final ItemStack... items) {
        return NBT.itemStackArrayToNBT(items).toString();
    }

    public ItemStack stringToItem(final String string) {
        return NBT.itemStackFromNBT(NBT.parseNBT(string));
    }

    public ItemStack[] stringToItems(final String string) {
        return NBT.itemStackArrayFromNBT(NBT.parseNBT(string));
    }
}
