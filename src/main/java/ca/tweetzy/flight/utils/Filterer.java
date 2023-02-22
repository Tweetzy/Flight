/*
 * Flight
 * Copyright 2023 Kiran Hart
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

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public final class Filterer {

    /**
     * Returns true if the phrase matches the item name, pluralized item name, singularized item name, item lore, or item enchantments.
     *
     * @param phrase The phrase to search for
     * @param stack  The itemstack to search for
     *
     * @return A boolean value.
     */
    public boolean searchByItemInfo(@NonNull final String phrase, @NonNull final ItemStack stack) {
        return match(phrase, ItemUtil.getStackName(stack)) ||
                match(phrase, Inflector.getInstance().pluralize(stack.getType().name())) ||
                match(phrase, Inflector.getInstance().singularize(stack.getType().name())) ||
                match(phrase, ItemUtil.getItemLore(stack)) ||
                match(phrase, ItemUtil.getItemEnchantments(stack));
    }

    /**
     * Used to match patterns
     *
     * @param pattern  is the keyword being searched for
     * @param sentence is the sentence you're checking
     *
     * @return whether the keyword is found
     */
    public boolean match(String pattern, String sentence) {
        final Pattern patt = Pattern.compile(ChatColor.stripColor(pattern), Pattern.CASE_INSENSITIVE);
        final Matcher matcher = patt.matcher(sentence);
        return matcher.find();
    }

    /**
     * @param pattern is the keyword that you're currently searching for
     * @param lines   is the lines being checked for the keyword
     *
     * @return whether the keyword was found in any of the lines provided
     */
    public boolean match(String pattern, List<String> lines) {
        for (String line : lines) {
            if (match(pattern, line)) {
                return true;
            }
        }
        return false;
    }
}
