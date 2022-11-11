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
package ca.tweetzy.flight.comp;

import ca.tweetzy.flight.comp.enums.CompMaterial;
import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>SkullUtils</b> - Apply skull texture from different sources.<br>
 * Skull Meta: https://hub.spigotmc.org/javadocs/spigot/org/bukkit/inventory/meta/SkullMeta.html
 * Mojang API: https://wiki.vg/Mojang_API
 * <p>
 * Some websites to get custom heads:
 * <ul>
 *     <li>https://minecraft-heads.com/</li>
 * </ul>
 *
 * @author Crypto Morin
 * @version 3.1.2
 * @see CompMaterial
 * @see ReflectionUtils
 */
public class SkullUtils {
    protected static final MethodHandle PROFILE_GETTER, PROFILE_SETTER;
    private static final String VALUE_PROPERTY = "{\"textures\":{\"SKIN\":{\"url\":\"";
    private static final boolean SUPPORTS_UUID = CompMaterial.supports(12);
    private static final String TEXTURES = "https://textures.minecraft.net/texture/";

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle profileSetter = null, profileGetter = null;

        try {
            Class<?> CraftMetaSkull = ReflectionUtils.getCraftClass("inventory.CraftMetaSkull");
            Field profile = CraftMetaSkull.getDeclaredField("profile");
            profile.setAccessible(true);
            profileGetter = lookup.unreflectGetter(profile);

            try {
                // https://github.com/CryptoMorin/XSeries/issues/169
                Method setProfile = CraftMetaSkull.getDeclaredMethod("setProfile", GameProfile.class);
                setProfile.setAccessible(true);
                profileSetter = lookup.unreflect(setProfile);
            } catch (NoSuchMethodException e) {
                profileSetter = lookup.unreflectSetter(profile);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }

        PROFILE_SETTER = profileSetter;
        PROFILE_GETTER = profileGetter;
    }

    @SuppressWarnings("deprecation")
    @Nonnull
    public static ItemStack getSkull(@Nonnull UUID id) {
        ItemStack head = CompMaterial.PLAYER_HEAD.parseItem();
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (SUPPORTS_UUID) meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
        else meta.setOwner(id.toString());

        head.setItemMeta(meta);
        return head;
    }

    @SuppressWarnings("deprecation")
    @Nonnull
    public static SkullMeta applySkin(@Nonnull ItemMeta head, @Nonnull OfflinePlayer identifier) {
        SkullMeta meta = (SkullMeta) head;
        if (SUPPORTS_UUID) {
            meta.setOwningPlayer(identifier);
        } else {
            meta.setOwner(identifier.getName());
        }
        return meta;
    }

    @Nonnull
    public static SkullMeta applySkin(@Nonnull ItemMeta head, @Nonnull UUID identifier) {
        return applySkin(head, Bukkit.getOfflinePlayer(identifier));
    }

    @SuppressWarnings("deprecation")
    @Nonnull
    public static SkullMeta applySkin(@Nonnull ItemMeta head, @Nonnull String identifier) {
        SkullMeta meta = (SkullMeta) head;
        if (isUsername(identifier)) return applySkin(head, Bukkit.getOfflinePlayer(identifier));
        if (identifier.contains("textures.minecraft.net")) return getValueFromTextures(meta, identifier);
        if (identifier.length() > 100 && isBase64(identifier)) return getSkullByValue(meta, identifier);
        return getTexturesFromUrlValue(meta, identifier);
    }

    @Nonnull
    protected static SkullMeta getSkullByValue(@Nonnull SkullMeta head, @Nonnull String value) {
        Validate.notEmpty(value, "Skull value cannot be null or empty");
        GameProfile profile = new GameProfile(UUID.randomUUID(), null);
        profile.getProperties().put("textures", new Property("textures", value));

        try {
            PROFILE_SETTER.invoke(head, profile);
        } catch (Throwable ex) {
            ex.printStackTrace();
        }

        return head;
    }

    @Nonnull
    private static SkullMeta getValueFromTextures(@Nonnull SkullMeta head, @Nonnull String url) {
        return getSkullByValue(head, encodeBase64(VALUE_PROPERTY + url + "\"}}}"));
    }

    @Nonnull
    private static SkullMeta getTexturesFromUrlValue(@Nonnull SkullMeta head, @Nonnull String urlValue) {
        return getValueFromTextures(head, TEXTURES + urlValue);
    }

    @Nonnull
    private static String encodeBase64(@Nonnull String str) {
        return Base64.getEncoder().encodeToString(str.getBytes());
    }

    /**
     * While RegEx is a little faster for small strings, this always checks strings with a length
     * greater than 100, so it'll perform a lot better.
     */
    private static boolean isBase64(@Nonnull String base64) {
        try {
            Base64.getDecoder().decode(base64);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        //return BASE64.matcher(base64).matches();
    }

    @Nullable
    public static String getSkinValue(@Nonnull ItemMeta skull) {
        Objects.requireNonNull(skull, "Skull ItemStack cannot be null");
        SkullMeta meta = (SkullMeta) skull;
        GameProfile profile = null;

        try {
            profile = (GameProfile) PROFILE_GETTER.invoke(meta);
        } catch (Throwable ex) {
            ex.printStackTrace();
        }

        if (profile != null && !profile.getProperties().get("textures").isEmpty()) {
            for (Property property : profile.getProperties().get("textures")) {
                if (!property.getValue().isEmpty()) return property.getValue();
            }
        }

        return null;
    }

    /**
     * https://help.minecraft.net/hc/en-us/articles/360034636712
     *
     * @param name the username to check.
     *
     * @return true if the string matches the Minecraft username rule, otherwise false.
     */
    private static boolean isUsername(@Nonnull String name) {
        int len = name.length();
        if (len < 3 || len > 16) return false;

        // For some reasons Apache's Lists.charactersOf is faster than character indexing for small strings.
        for (char ch : Lists.charactersOf(name)) {
            if (ch != '_' && !(ch >= 'A' && ch <= 'Z') && !(ch >= 'a' && ch <= 'z') && !(ch >= '0' && ch <= '9')) return false;
        }
        return true;
    }
}
