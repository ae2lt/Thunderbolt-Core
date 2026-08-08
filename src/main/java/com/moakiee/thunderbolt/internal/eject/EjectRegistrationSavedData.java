package com.moakiee.thunderbolt.internal.eject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

public final class EjectRegistrationSavedData extends SavedData {
   private static final String DATA_NAME = "thunderbolt_eject_registrations";
   private static final String LEGACY_DATA_NAME = "ae2lt_eject_registrations";
   private static final String TAG_ENTRIES = "Entries";
   private static final String TAG_LEGACY_MIGRATION_COMPLETE = "LegacyMigrationComplete";
   private static final String TAG_I_DIM = "IDim";
   private static final String TAG_I_POS = "IPos";
   private static final String TAG_I_FACE = "IFace";
   private static final String TAG_P_DIM = "PDim";
   private static final String TAG_P_POS = "PPos";
   private final List<EjectRegistrationSavedData.PersistentRegistration> entries = new ArrayList<>();
   private boolean legacyMigrationComplete;

   public static EjectRegistrationSavedData get(MinecraftServer server) {
      return server.overworld().getDataStorage().computeIfAbsent(
         EjectRegistrationSavedData::load, EjectRegistrationSavedData::new, "thunderbolt_eject_registrations"
      );
   }

   public void migrateLegacyIfNeeded(MinecraftServer server) {
      if (!this.legacyMigrationComplete) {
         EjectRegistrationSavedData legacy = server.overworld().getDataStorage().computeIfAbsent(
            EjectRegistrationSavedData::load, EjectRegistrationSavedData::new, "ae2lt_eject_registrations"
         );

         for (EjectRegistrationSavedData.PersistentRegistration registration : legacy.entries) {
            if (!this.entries.contains(registration)) {
               this.entries.add(registration);
            }
         }

         this.legacyMigrationComplete = true;
         this.setDirty();
      }
   }

   public List<EjectRegistrationSavedData.PersistentRegistration> getAll() {
      return Collections.unmodifiableList(this.entries);
   }

   public void add(EjectRegistrationSavedData.PersistentRegistration registration) {
      this.entries.add(registration);
      this.setDirty();
   }

   public void removeByIntercept(ResourceKey<Level> dimension, BlockPos pos, Direction face) {
      long packedPos = pos.asLong();
      if (this.entries
         .removeIf(entry -> entry.interceptDimension().equals(dimension) && entry.interceptPos().asLong() == packedPos && entry.interceptFace() == face)) {
         this.setDirty();
      }
   }

   public void removeByHost(ResourceKey<Level> dimension, BlockPos pos) {
      if (this.entries.removeIf(entry -> entry.hostDimension().equals(dimension) && entry.hostPos().equals(pos))) {
         this.setDirty();
      }
   }

   public CompoundTag save(CompoundTag tag) {
      ListTag list = new ListTag();

      for (EjectRegistrationSavedData.PersistentRegistration entry : this.entries) {
         CompoundTag encoded = new CompoundTag();
         encoded.putString("IDim", entry.interceptDimension().location().toString());
         encoded.putLong("IPos", entry.interceptPos().asLong());
         encoded.putInt("IFace", entry.interceptFace().get3DDataValue());
         encoded.putString("PDim", entry.hostDimension().location().toString());
         encoded.putLong("PPos", entry.hostPos().asLong());
         list.add(encoded);
      }

      tag.put("Entries", list);
      tag.putBoolean("LegacyMigrationComplete", this.legacyMigrationComplete);
      return tag;
   }

   static EjectRegistrationSavedData load(CompoundTag tag) {
      EjectRegistrationSavedData data = new EjectRegistrationSavedData();
      data.legacyMigrationComplete = tag.getBoolean("LegacyMigrationComplete");
      if (!tag.contains("Entries", 9)) {
         return data;
      } else {
         ListTag list = tag.getList("Entries", 10);

         for (int i = 0; i < list.size(); i++) {
            CompoundTag encoded = list.getCompound(i);
            data.entries
               .add(
                  new EjectRegistrationSavedData.PersistentRegistration(
                     dimension(encoded.getString("IDim")),
                     BlockPos.of(encoded.getLong("IPos")),
                     Direction.from3DDataValue(encoded.getInt("IFace")),
                     dimension(encoded.getString("PDim")),
                     BlockPos.of(encoded.getLong("PPos"))
                  )
               );
         }

         return data;
      }
   }

   private static ResourceKey<Level> dimension(String id) {
      return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
   }

   public static record PersistentRegistration(
      ResourceKey<Level> interceptDimension, BlockPos interceptPos, Direction interceptFace, ResourceKey<Level> hostDimension, BlockPos hostPos
   ) {
   }
}
