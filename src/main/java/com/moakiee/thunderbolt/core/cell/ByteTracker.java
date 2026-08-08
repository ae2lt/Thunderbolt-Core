package com.moakiee.thunderbolt.core.cell;

import appeng.api.stacks.AEKeyType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.function.IntSupplier;

public final class ByteTracker {
   private final Object2LongOpenHashMap<AEKeyType> keyTypeRemainders = new Object2LongOpenHashMap();
   private final Object2IntOpenHashMap<AEKeyType> keyTypeCounts = new Object2IntOpenHashMap();
   private long usedBytesLo;
   private long usedBytesHi;
   private long capacityLo;
   private long capacityHi;
   private int bytesPerType;
   private int maxTypes;
   private final IntSupplier totalTypesGetter;

   public ByteTracker(IntSupplier totalTypesGetter) {
      this.totalTypesGetter = totalTypesGetter;
   }

   public void configure(int bytesPerType, int maxTypes, long capacityLo, long capacityHi) {
      this.bytesPerType = bytesPerType;
      this.maxTypes = maxTypes;
      this.capacityLo = capacityLo;
      this.capacityHi = capacityHi;
   }

   public long computeMaxInsertable(AEKeyType type, boolean isNewKey) {
      int apb = type.getAmountPerByte();
      if (this.capacityHi == this.usedBytesHi) {
         return this.capacityLo <= this.usedBytesLo ? 0L : this.maxFromFreeBytes(this.capacityLo - this.usedBytesLo, apb, type, isNewKey);
      } else {
         return this.capacityHi < this.usedBytesHi ? 0L : Long.MAX_VALUE;
      }
   }

   private long maxFromFreeBytes(long freeBytes, int apb, AEKeyType type, boolean isNewKey) {
      int totalTypes = this.totalTypesGetter.getAsInt();
      if (isNewKey) {
         if (totalTypes >= this.maxTypes) {
            return 0L;
         }

         if (freeBytes < (long)this.bytesPerType) {
            return 0L;
         }

         freeBytes -= (long)this.bytesPerType;
      }

      long r = this.keyTypeRemainders.getLong(type);
      long freeInPartial = r > 0L ? (long)apb - r : 0L;
      if (freeBytes > Long.MAX_VALUE / (long)apb) {
         return Long.MAX_VALUE;
      } else {
         long result = freeBytes * (long)apb + freeInPartial;
         return result < 0L ? Long.MAX_VALUE : result;
      }
   }

   public void onInsert(AEKeyType type, long amount, boolean isNewKey) {
      int apb = type.getAmountPerByte();
      long oldR = this.keyTypeRemainders.getLong(type);
      long aMod = amount % (long)apb;
      long combined = oldR + aMod;
      long extra = combined >= (long)apb ? 1L : 0L;
      long newR = extra > 0L ? combined - (long)apb : combined;
      this.keyTypeRemainders.put(type, newR);
      long byteDelta = amount / (long)apb + extra + (newR > 0L ? 1L : 0L) - (oldR > 0L ? 1L : 0L);
      if (isNewKey) {
         byteDelta += (long)this.bytesPerType;
         this.keyTypeCounts.addTo(type, 1);
      }

      this.usedBytesLo += byteDelta;
      if (this.usedBytesLo < 0L) {
         this.usedBytesLo &= Long.MAX_VALUE;
         this.usedBytesHi++;
      }
   }

   public void onExtract(AEKeyType type, long amount, boolean keyRemoved) {
      int apb = type.getAmountPerByte();
      long oldR = this.keyTypeRemainders.getLong(type);
      long aMod = amount % (long)apb;
      long newR;
      long extra;
      if (oldR >= aMod) {
         newR = oldR - aMod;
         extra = 0L;
      } else {
         newR = oldR + (long)apb - aMod;
         extra = 1L;
      }

      long bytesFreed = amount / (long)apb + extra + (oldR > 0L ? 1L : 0L) - (newR > 0L ? 1L : 0L);
      if (keyRemoved) {
         bytesFreed += (long)this.bytesPerType;
         int countBefore = this.keyTypeCounts.addTo(type, -1);
         if (countBefore <= 1) {
            this.keyTypeCounts.removeInt(type);
            this.keyTypeRemainders.removeLong(type);
         } else {
            this.keyTypeRemainders.put(type, newR);
         }
      } else {
         this.keyTypeRemainders.put(type, newR);
      }

      this.usedBytesLo -= bytesFreed;
      if (this.usedBytesLo < 0L) {
         this.usedBytesLo &= Long.MAX_VALUE;
         this.usedBytesHi--;
      }
   }

   public long getUsedBytes() {
      return DualLong126.cap(this.usedBytesHi, this.usedBytesLo);
   }

   public long getUsedBytesHi() {
      return this.usedBytesHi;
   }

   public long getUsedBytesLo() {
      return this.usedBytesLo;
   }

   public boolean isFull() {
      return this.capacityHi == this.usedBytesHi ? this.capacityLo <= this.usedBytesLo : this.capacityHi < this.usedBytesHi;
   }

   public boolean isTypeFull() {
      return this.totalTypesGetter.getAsInt() >= this.maxTypes;
   }

   int trackedTypeEntries() {
      return this.keyTypeCounts.size() + this.keyTypeRemainders.size();
   }

   public void rebuild(Object2LongOpenHashMap<AEKeyType> ktLo, Object2LongOpenHashMap<AEKeyType> ktHi, Object2IntOpenHashMap<AEKeyType> ktCounts, int totalKeys) {
      this.keyTypeRemainders.clear();
      this.keyTypeCounts.clear();
      this.usedBytesLo = 0L;
      this.usedBytesHi = 0L;
      this.keyTypeCounts.putAll(ktCounts);
      long[] divBuf = new long[2];

      for (ObjectIterator var6 = ktLo.keySet().iterator(); var6.hasNext(); this.usedBytesHi = this.usedBytesHi + divBuf[0]) {
         AEKeyType kt = (AEKeyType)var6.next();
         long tLo = ktLo.getLong(kt);
         long tHi = ktHi.getLong(kt);
         int apb = kt.getAmountPerByte();
         long remainder = tHi == 0L ? tLo % (long)apb : DualLong126.mod126(tHi, tLo, apb);
         this.keyTypeRemainders.put(kt, remainder);
         DualLong126.ceilDiv126(tHi, tLo, apb, divBuf);
         this.usedBytesLo = this.usedBytesLo + divBuf[1];
         if (this.usedBytesLo < 0L) {
            this.usedBytesLo &= Long.MAX_VALUE;
            this.usedBytesHi++;
         }
      }

      this.usedBytesLo = this.usedBytesLo + (long)totalKeys * (long)this.bytesPerType;
      if (this.usedBytesLo < 0L) {
         this.usedBytesLo &= Long.MAX_VALUE;
         this.usedBytesHi++;
      }
   }
}
