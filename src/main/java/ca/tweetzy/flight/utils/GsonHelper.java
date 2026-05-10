/*
 * Flight
 * Copyright 2022 Kiran Hart
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ca.tweetzy.flight.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.lang.reflect.Type;

/**
 * Wraps a configured {@link Gson} for quick serialization to JSON strings and deserialization back.
 * Use {@link #builder()} for custom options; {@link #getDefault()} for shared defaults; {@link #stringify(Object)}
 * and {@link #parse(String, Class)} for one-liners without holding an instance.
 */
public final class GsonHelper {

    private static final GsonHelper DEFAULT = builder().build();

    private final Gson gson;

    private GsonHelper(@NotNull Gson gson) {
        this.gson = gson;
    }

    /**
     * Shared default instance (plain {@link GsonBuilder#create()}).
     */
    @NotNull
    public static GsonHelper getDefault() {
        return DEFAULT;
    }

    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Serialize to JSON using the default helper (same as {@link #getDefault()}{@code .toJson(src)}).
     */
    @NotNull
    public static String stringify(@Nullable Object src) {
        return DEFAULT.toJson(src);
    }

    /**
     * Deserialize using the default helper and {@code classOfT}.
     */
    @Nullable
    public static <T> T parse(@Nullable String json, @NotNull Class<T> classOfT) {
        return DEFAULT.fromJson(json, classOfT);
    }

    /**
     * Deserialize using the default helper and a full {@link Type}.
     */
    @Nullable
    public static <T> T parse(@Nullable String json, @NotNull Type typeOfT) {
        return DEFAULT.fromJson(json, typeOfT);
    }

    /**
     * Deserialize using the default helper and a {@link TypeToken}.
     */
    @Nullable
    public static <T> T parse(@Nullable String json, @NotNull TypeToken<T> typeToken) {
        return DEFAULT.fromJson(json, typeToken);
    }

    @NotNull
    public String toJson(@Nullable Object src) {
        return gson.toJson(src);
    }

    @NotNull
    public String toJson(@Nullable JsonElement element) {
        return gson.toJson(element);
    }

    @Nullable
    public <T> T fromJson(@Nullable String json, @NotNull Class<T> classOfT) {
        if (json == null) {
            return null;
        }
        return gson.fromJson(json, classOfT);
    }

    @Nullable
    public <T> T fromJson(@Nullable String json, @NotNull Type typeOfT) {
        if (json == null) {
            return null;
        }
        return gson.fromJson(json, typeOfT);
    }

    @Nullable
    public <T> T fromJson(@Nullable String json, @NotNull TypeToken<T> typeToken) {
        if (json == null) {
            return null;
        }
        return gson.fromJson(json, typeToken.getType());
    }

    @Nullable
    public <T> T fromJson(@NotNull Reader reader, @NotNull Class<T> classOfT) {
        return gson.fromJson(reader, classOfT);
    }

    @Nullable
    public <T> T fromJson(@NotNull Reader reader, @NotNull Type typeOfT) {
        return gson.fromJson(reader, typeOfT);
    }

    @Nullable
    public JsonElement fromJsonToTree(@Nullable String json) {
        if (json == null) {
            return null;
        }
        return gson.fromJson(json, JsonElement.class);
    }

    @NotNull
    public <T> T fromJsonTree(@Nullable JsonElement json, @NotNull Class<T> classOfT) {
        return gson.fromJson(json, classOfT);
    }

    /**
     * Underlying Gson for advanced use ({@code JsonParser}, custom flows, etc.).
     */
    @NotNull
    public Gson gson() {
        return gson;
    }

    public static final class Builder {

        private final GsonBuilder delegate = new GsonBuilder();

        @NotNull
        public Builder prettyPrinting() {
            delegate.setPrettyPrinting();
            return this;
        }

        @NotNull
        public Builder serializeNulls() {
            delegate.serializeNulls();
            return this;
        }

        @NotNull
        public Builder disableHtmlEscaping() {
            delegate.disableHtmlEscaping();
            return this;
        }

        @NotNull
        public Builder setLenient() {
            delegate.setLenient();
            return this;
        }

        @NotNull
        public Builder setDateFormat(@NotNull String pattern) {
            delegate.setDateFormat(pattern);
            return this;
        }

        @NotNull
        public Builder registerTypeAdapter(@NotNull Type type, @NotNull Object typeAdapter) {
            delegate.registerTypeAdapter(type, typeAdapter);
            return this;
        }

        @NotNull
        public Builder registerTypeHierarchyAdapter(@NotNull Class<?> baseType, @NotNull Object typeAdapter) {
            delegate.registerTypeHierarchyAdapter(baseType, typeAdapter);
            return this;
        }

        /**
         * Access the raw {@link GsonBuilder} for options not wrapped here.
         */
        @NotNull
        public GsonBuilder unwrap() {
            return delegate;
        }

        @NotNull
        public GsonHelper build() {
            return new GsonHelper(delegate.create());
        }
    }
}
