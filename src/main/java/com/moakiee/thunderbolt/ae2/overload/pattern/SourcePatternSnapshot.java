package com.moakiee.thunderbolt.ae2.overload.pattern;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class SourcePatternSnapshot {
   private static final String TAG_ITEM = "Item";
   private static final String TAG_STACK = "Stack";
   private static final String TAG_CUSTOM_DATA = "CustomData";
   private final ResourceLocation itemId;
   @Nullable
   private final CompoundTag serializedStackTag;
   @Nullable
   private final CompoundTag customDataTag;
   @Nullable
   private String cachedFingerprint;

   public SourcePatternSnapshot(ResourceLocation itemId, @Nullable CompoundTag serializedStackTag, @Nullable CompoundTag customDataTag) {
      this.itemId = Objects.requireNonNull(itemId, "itemId");
      this.serializedStackTag = serializedStackTag == null ? null : serializedStackTag.copy();
      this.customDataTag = customDataTag == null ? null : customDataTag.copy();
   }

   public static SourcePatternSnapshot fromItemStack(ItemStack stack, Provider registries) {
      Objects.requireNonNull(stack, "stack");
      Objects.requireNonNull(registries, "registries");
      if (stack.isEmpty()) {
         throw new IllegalArgumentException("source pattern stack must not be empty");
      } else {
         ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
         CompoundTag stackTag = stack.save(new CompoundTag());
         return new SourcePatternSnapshot(itemId, stackTag, null);
      }
   }

   public ResourceLocation itemId() {
      return this.itemId;
   }

   public String fingerprint() {
      String cached = this.cachedFingerprint;
      if (cached != null) {
         return cached;
      } else {
         String computed = this.computeFingerprint();
         this.cachedFingerprint = computed;
         return computed;
      }
   }

   private String computeFingerprint() {
      CompoundTag identity = this.toTag();
      if (identity.contains("Stack", 10)) {
         CompoundTag stack = identity.getCompound("Stack");
         stack.remove("count");
         stack.remove("Count");
      }

      String canonical = canonicalCopy(identity).toString();

      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException var4) {
         throw new IllegalStateException("SHA-256 is unavailable", var4);
      }
   }

   @Nullable
   public CompoundTag customDataTag() {
      return this.customDataTag == null ? null : this.customDataTag.copy();
   }

   public ItemStack toItemStack(Provider registries) {
      Objects.requireNonNull(registries, "registries");
      if (this.serializedStackTag != null && !this.serializedStackTag.isEmpty()) {
         return ItemStack.of(this.serializedStackTag.copy());
      } else {
         Item item = (Item)BuiltInRegistries.ITEM.get(this.itemId);
         ItemStack stack = new ItemStack(item);
         if (this.customDataTag != null && !this.customDataTag.isEmpty()) {
            // 1.20.1 stores the equivalent custom payload in the legacy item tag.
            stack.getOrCreateTag().put("CustomData", this.customDataTag.copy());
         }

         return stack;
      }
   }

   public CompoundTag toTag() {
      CompoundTag tag = new CompoundTag();
      tag.putString("Item", this.itemId.toString());
      if (this.serializedStackTag != null && !this.serializedStackTag.isEmpty()) {
         tag.put("Stack", this.serializedStackTag.copy());
      } else if (this.customDataTag != null && !this.customDataTag.isEmpty()) {
         tag.put("CustomData", this.customDataTag.copy());
      }

      return tag;
   }

   public static SourcePatternSnapshot fromTag(CompoundTag tag) {
      ResourceLocation itemId;
      if (tag.contains("Item", 8)) {
         itemId = ResourceLocation.parse(tag.getString("Item"));
      } else {
         if (!tag.contains("Stack", 10)) {
            throw new IllegalArgumentException("source pattern snapshot is missing an item id");
         }

         itemId = ResourceLocation.parse(tag.getCompound("Stack").getString("id"));
      }

      CompoundTag serializedStack = null;
      if (tag.contains("Stack", 10)) {
         serializedStack = tag.getCompound("Stack").copy();
      }

      CompoundTag customData = null;
      if (tag.contains("CustomData", 10)) {
         customData = tag.getCompound("CustomData").copy();
      }

      return new SourcePatternSnapshot(itemId, serializedStack, customData);
   }

   private static Tag canonicalCopy(Tag source) {
      if (source instanceof CompoundTag compound) {
         CompoundTag result = new CompoundTag();
         compound.getAllKeys().stream().sorted().forEach(key -> {
            Tag valuex = compound.get(key);
            if (valuex != null) {
               result.put(key, canonicalCopy(valuex));
            }
         });
         return result;
      } else if (!(source instanceof ListTag list)) {
         return source.copy();
      } else {
         ListTag result = new ListTag();

         for (Tag value : list) {
            result.add(canonicalCopy(value));
         }

         return result;
      }
   }
}
