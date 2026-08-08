package com.moakiee.thunderbolt.core.craft;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.core.AELog;
import com.moakiee.thunderbolt.ae2.batch.SharedBatchInputPattern;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public final class CraftingCore implements Sweepable {
   public static final int FLUSH_INTERVAL_TICKS = 5;
   private static final String NBT_PENDING = "pending";
   private static final String NBT_NEXT_FLUSH = "nextFlush";
   private static final String NBT_COPIES = "n";
   private static final String NBT_OUTPUTS = "out";
   private static final String NBT_KEY = "k";
   private static final String NBT_AMOUNT = "v";
   private final CraftingCoreHost host;
   private final CopyAssembler assembler;
   private final CraftingCoreRegistry registry;
   private final PendingBatch pending = new PendingBatch();
   private final Map<IPatternDetails, Map<CraftingCore.InputSignature, CraftingCore.CachedAssembly>> assemblyCache = new IdentityHashMap<>();
   private long threadsInFlight;
   private long nextFlushTick = Long.MIN_VALUE;

   public CraftingCore(CraftingCoreHost host, CopyAssembler assembler, CraftingCoreRegistry registry) {
      this.host = host;
      this.assembler = assembler;
      this.registry = registry;
   }

   public long pushBatch(IPatternDetails details, KeyCounter[] oneCopyTemplate, long copies) {
      if (copies <= 0L || oneCopyTemplate == null) {
         return 0L;
      } else if (!(details instanceof IMolecularAssemblerSupportedPattern)) {
         return 0L;
      } else {
         long now = this.host.getGameTime();
         this.flushIfDue(now);
         long accepted = this.appendableCopies(copies);
         if (accepted <= 0L) {
            return 0L;
         } else {
            CopyAssembler.AssembledCopy assembled;
            try {
               assembled = this.assembleOneCopyCached(details, oneCopyTemplate);
            } catch (Throwable var15) {
               AELog.warn("[ae2lt] batch crafting core assemble failed for %s; dropping %d copies. %s", new Object[]{details, copies, var15});
               return 0L;
            }

            if (assembled != null && assembled.output() != null && assembled.outputCount() > 0L) {
               long sharedOutput = details instanceof SharedBatchInputPattern shared
                  ? Math.min(assembled.outputCount(), Math.max(0L, shared.sharedBatchOutputAmount(assembled.output())))
                  : 0L;
               boolean wasEmpty = this.threadsInFlight == 0L;
               accumulate(this.pending, assembled.output(), saturatedAdd(sharedOutput, saturatedMultiply(assembled.outputCount() - sharedOutput, accepted)));
               if (assembled.remainders() != null) {
                  for (CopyAssembler.Stack remainder : assembled.remainders()) {
                     if (remainder != null) {
                        accumulate(this.pending, remainder.key(), saturatedMultiply(remainder.count(), accepted));
                     }
                  }
               }

               if (assembled.sharedRemainders() != null) {
                  for (CopyAssembler.Stack remainderx : assembled.sharedRemainders()) {
                     if (remainderx != null) {
                        accumulate(this.pending, remainderx.key(), remainderx.count());
                     }
                  }
               }

               this.pending.copies = saturatedAdd(this.pending.copies, accepted);
               this.threadsInFlight = saturatedAdd(this.threadsInFlight, accepted);
               if (wasEmpty) {
                  this.nextFlushTick = nextBoundaryAfter(now);
               }

               this.registry.markActive(this);
               return accepted;
            } else {
               return 0L;
            }
         }
      }
   }

   private CopyAssembler.AssembledCopy assembleOneCopyCached(IPatternDetails details, KeyCounter[] oneCopyTemplate) {
      CraftingCore.InputSignature signature = CraftingCore.InputSignature.capture(oneCopyTemplate);
      Map<CraftingCore.InputSignature, CraftingCore.CachedAssembly> byInput = this.assemblyCache.computeIfAbsent(details, ignored -> new HashMap<>());
      CraftingCore.CachedAssembly cached = byInput.get(signature);
      if (cached != null) {
         return cached.cacheable() ? cached.assembled() : this.assembler.assembleOneCopy(details, oneCopyTemplate);
      } else {
         CopyAssembler.AssembledCopy assembled = this.assembler.assembleOneCopy(details, oneCopyTemplate);
         byInput.put(
            signature, isExpectedAssembly(details, assembled) ? CraftingCore.CachedAssembly.cacheable(assembled) : CraftingCore.CachedAssembly.uncacheable()
         );
         return assembled;
      }
   }

   private static boolean isExpectedAssembly(IPatternDetails details, CopyAssembler.AssembledCopy assembled) {
      if (assembled != null && assembled.output() != null && assembled.outputCount() > 0L) {
         for (GenericStack output : details.getOutputs()) {
            if (assembled.output().equals(output.what()) && assembled.outputCount() == output.amount()) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public long liveThreads() {
      this.flushIfDue(this.host.getGameTime());
      return this.threadsInFlight;
   }

   public long threadsInFlight() {
      return this.threadsInFlight;
   }

   @Override
   public boolean sweepTick() {
      if (this.host.isRemoved()) {
         this.drainAll(true);
         return false;
      } else {
         this.flushIfDue(this.host.getGameTime());
         return this.threadsInFlight > 0L;
      }
   }

   public void drainAll(boolean forceSpawn) {
      if (this.drainPending(forceSpawn)) {
         this.nextFlushTick = Long.MIN_VALUE;
      } else {
         this.nextFlushTick = nextBoundaryAfter(this.host.getGameTime());
      }
   }

   public void suspend() {
      this.registry.markInactive(this);
      this.reset();
      this.assemblyCache.clear();
   }

   public void writeTo(CompoundTag tag, Provider registries) {
      if (this.threadsInFlight > 0L) {
         CompoundTag pendingTag = new CompoundTag();
         pendingTag.putLong("n", this.pending.copies);
         pendingTag.putLong("nextFlush", this.nextFlushTick);
         ListTag outputs = writeOutputs(this.pending, registries);
         if (!outputs.isEmpty()) {
            pendingTag.put("out", outputs);
            tag.put("pending", pendingTag);
         }
      }
   }

   public void readFrom(CompoundTag tag, Provider registries) {
      this.registry.markInactive(this);
      this.reset();
      if (tag.contains("pending", 10)) {
         CompoundTag pendingTag = tag.getCompound("pending");
         this.readBatch(pendingTag, registries);
         long restoredNextFlush = pendingTag.getLong("nextFlush");
         if (this.threadsInFlight > 0L) {
            long now = this.host.getGameTime();
            this.nextFlushTick = restoredNextFlush > now ? restoredNextFlush : nextBoundaryAfter(now);
            this.registry.markActive(this);
         }
      }
   }

   private void flushIfDue(long now) {
      if (this.threadsInFlight > 0L && now >= this.nextFlushTick) {
         this.nextFlushTick = this.drainPending(false) ? Long.MIN_VALUE : nextBoundaryAfter(now);
      }
   }

   private boolean drainPending(boolean forceSpawn) {
      if (this.pending.copies <= 0L) {
         return true;
      } else if (forceSpawn) {
         ObjectIterator var11 = this.pending.outputs.object2LongEntrySet().iterator();

         while (var11.hasNext()) {
            Entry<AEKey> entry = (Entry<AEKey>)var11.next();
            if (entry.getLongValue() > 0L) {
               this.host.spawnToWorld((AEKey)entry.getKey(), entry.getLongValue());
            }
         }

         this.releasePending();
         return true;
      } else if (!this.host.isConnected()) {
         return false;
      } else {
         boolean anyLeft = false;
         ObjectIterator<Entry<AEKey>> iter = this.pending.outputs.object2LongEntrySet().fastIterator();

         while (iter.hasNext()) {
            Entry<AEKey> entry = (Entry<AEKey>)iter.next();
            long amount = entry.getLongValue();
            if (amount <= 0L) {
               iter.remove();
            } else {
               long inserted = this.host.insertToNetwork((AEKey)entry.getKey(), amount);
               long leftover = amount - inserted;
               if (leftover > 0L) {
                  entry.setValue(leftover);
                  anyLeft = true;
               } else {
                  iter.remove();
               }
            }
         }

         if (!anyLeft) {
            this.releasePending();
            return true;
         } else {
            return false;
         }
      }
   }

   private void releasePending() {
      this.pending.outputs.clear();
      this.pending.copies = 0L;
      this.threadsInFlight = 0L;
   }

   private void reset() {
      this.pending.outputs.clear();
      this.pending.copies = 0L;
      this.threadsInFlight = 0L;
      this.nextFlushTick = Long.MIN_VALUE;
   }

   private long appendableCopies(long requested) {
      long globalSpace = Long.MAX_VALUE - this.threadsInFlight;
      return Math.min(requested, globalSpace);
   }

   private static long nextBoundaryAfter(long now) {
      long delta = (long)(5 - Math.floorMod(now, 5));
      return saturatedAdd(now, delta);
   }

   private static ListTag writeOutputs(PendingBatch batch, Provider registries) {
      ListTag outputs = new ListTag();
      ObjectIterator var3 = batch.outputs.object2LongEntrySet().iterator();

      while (var3.hasNext()) {
         Entry<AEKey> entry = (Entry<AEKey>)var3.next();
         if (entry.getKey() != null && entry.getLongValue() > 0L) {
            CompoundTag outputTag = new CompoundTag();
            outputTag.put("k", ((AEKey)entry.getKey()).toTagGeneric());
            outputTag.putLong("v", entry.getLongValue());
            outputs.add(outputTag);
         }
      }

      return outputs;
   }

   private void readBatch(CompoundTag batchTag, Provider registries) {
      long copies = batchTag.getLong("n");
      if (copies > 0L) {
         boolean restoredOutput = false;
         ListTag outputs = batchTag.getList("out", 10);

         for (int i = 0; i < outputs.size(); i++) {
            CompoundTag outputTag = outputs.getCompound(i);
            long amount = outputTag.getLong("v");
            if (amount > 0L) {
               AEKey key = AEKey.fromTagGeneric(outputTag.getCompound("k"));
               if (key != null) {
                  accumulate(this.pending, key, amount);
                  restoredOutput = true;
               }
            }
         }

         if (restoredOutput) {
            this.pending.copies = saturatedAdd(this.pending.copies, copies);
            this.threadsInFlight = saturatedAdd(this.threadsInFlight, copies);
         }
      }
   }

   private static long saturatedMultiply(long amount, long copies) {
      if (amount <= 0L || copies <= 0L) {
         return 0L;
      } else {
         return amount > Long.MAX_VALUE / copies ? Long.MAX_VALUE : amount * copies;
      }
   }

   private static long saturatedAdd(long left, long right) {
      if (right <= 0L) {
         return left;
      } else {
         return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
      }
   }

   private static void accumulate(PendingBatch batch, AEKey key, long amount) {
      if (key != null && amount > 0L) {
         batch.outputs.put(key, saturatedAdd(batch.outputs.getLong(key), amount));
      }
   }

   private static record CachedAssembly(boolean cacheable, CopyAssembler.AssembledCopy assembled) {
      private static CraftingCore.CachedAssembly cacheable(CopyAssembler.AssembledCopy assembled) {
         List<CopyAssembler.Stack> remainders = assembled.remainders() != null ? List.copyOf(assembled.remainders()) : List.of();
         List<CopyAssembler.Stack> sharedRemainders = assembled.sharedRemainders() != null ? List.copyOf(assembled.sharedRemainders()) : List.of();
         return new CraftingCore.CachedAssembly(
            true, new CopyAssembler.AssembledCopy(assembled.output(), assembled.outputCount(), remainders, sharedRemainders)
         );
      }

      private static CraftingCore.CachedAssembly uncacheable() {
         return new CraftingCore.CachedAssembly(false, null);
      }
   }

   private static record InputEntry(AEKey key, long amount) {
   }

   private static record InputSignature(List<List<CraftingCore.InputEntry>> slots) {
      private static CraftingCore.InputSignature capture(KeyCounter[] inputs) {
         ArrayList<List<CraftingCore.InputEntry>> slots = new ArrayList<>(inputs.length);

         for (KeyCounter input : inputs) {
            ArrayList<CraftingCore.InputEntry> entries = new ArrayList<>();
            if (input != null) {
               for (Entry<AEKey> entry : input) {
                  if (entry.getKey() != null && entry.getLongValue() > 0L) {
                     entries.add(new CraftingCore.InputEntry((AEKey)entry.getKey(), entry.getLongValue()));
                  }
               }
            }

            entries.sort(Comparator.<CraftingCore.InputEntry>comparingInt(entryx -> entryx.key().hashCode()).thenComparing(entryx -> entryx.key().toString()));
            slots.add(List.copyOf(entries));
         }

         return new CraftingCore.InputSignature(List.copyOf(slots));
      }
   }
}
