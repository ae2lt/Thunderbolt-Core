package com.moakiee.thunderbolt.ae2.timewheel;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.moakiee.thunderbolt.ae2.api.crafting.CraftingPatternDelegates;
import com.moakiee.thunderbolt.ae2.crafting.ExecuteLoopPattern;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.thunderbolt.core.planner.Sat;
import it.unimi.dsi.fastutil.objects.Object2LongMap.Entry;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

final class LoopSeedLedgerBook {
   private static final String TAG_LEDGERS = "loopSeedLedger";
   private static final String TAG_CONSUMER_ID = "consumerId";
   private static final String TAG_GROUP_IDS = "groupIds";
   private static final String TAG_GROUP_ID = "groupId";
   private static final String TAG_ENTRIES = "entries";
   private static final String TAG_NEGATIVE = "negative";
   private static final String TAG_DEBTS = "variantDebts";
   private static final String TAG_PLANNED = "planned";
   private static final String TAG_VARIANT_RULES = "variantRules";
   private static final String TAG_RULE_EXACT = "exact";
   private static final String TAG_RULE_FUZZY = "fuzzyIdentities";
   private static final String TAG_BUNDLE_UNITS = "bundleUnits";
   private static final String TAG_SINGLE_SEED_CONSUMER = "singleSeedConsumer";
   private final Map<UUID, Map<AEKey, Long>> ledgers = new HashMap<>();
   private final Map<UUID, Set<UUID>> consumerGroups = new HashMap<>();
   private final Map<UUID, Map<AEKey, ExecuteLoopPattern.SeedVariantRule>> variantRules = new HashMap<>();
   private final Map<UUID, Map<AEKey, Long>> consumerBundleUnits = new HashMap<>();
   private final Set<UUID> singleSeedConsumers = new LinkedHashSet<>();
   private final Map<UUID, Map<AEKey, Long>> hostBootstrapRequirements = new HashMap<>();
   private final Map<UUID, Map<AEKey, Map<AEKey, Long>>> variantDebts = new HashMap<>();
   private final Map<AEKey, BigInteger> totalReserved = new HashMap<>();

   void initialize(Iterable<ExecuteLoopPattern> patterns) {
      this.clear();
      LinkedHashMap<UUID, Map<AEKey, Long>> dedicatedInitial = new LinkedHashMap<>();
      LinkedHashMap<UUID, Map<UUID, Map<AEKey, Long>>> sharedInitialByGroup = new LinkedHashMap<>();
      if (patterns != null) {
         for (ExecuteLoopPattern pattern : patterns) {
            if (pattern != null) {
               this.registerConsumer(pattern);
               Map<AEKey, Long> account = pattern.hasSingleSeedInputPerMember()
                  ? sharedInitialByGroup.computeIfAbsent(pattern.reusableSeedGroupId(), ignored -> new LinkedHashMap<>())
                     .computeIfAbsent(pattern.seedConsumerId(), ignored -> new LinkedHashMap<>())
                  : dedicatedInitial.computeIfAbsent(pattern.seedConsumerId(), ignored -> new LinkedHashMap<>());

               for (Entry<AEKey> seed : pattern.initialSeed()) {
                  if (seed.getLongValue() > 0L) {
                     account.merge((AEKey)seed.getKey(), seed.getLongValue(), Math::max);
                     this.hostBootstrapRequirements
                        .computeIfAbsent(pattern.seedConsumerId(), ignored -> new LinkedHashMap<>())
                        .merge((AEKey)seed.getKey(), seed.getLongValue(), Math::max);
                  }
               }
            }
         }
      }

      for (java.util.Map.Entry<UUID, Map<AEKey, Long>> account : dedicatedInitial.entrySet()) {
         for (java.util.Map.Entry<AEKey, Long> seedx : account.getValue().entrySet()) {
            this.adjust(account.getKey(), seedx.getKey(), seedx.getValue());
         }
      }

      LinkedHashMap<AEKey, Long> sharedMaximum = new LinkedHashMap<>();

      for (Map<UUID, Map<AEKey, Long>> group : sharedInitialByGroup.values()) {
         LinkedHashMap<AEKey, Long> groupTotal = new LinkedHashMap<>();

         for (Map<AEKey, Long> consumer : group.values()) {
            for (java.util.Map.Entry<AEKey, Long> seedx : consumer.entrySet()) {
               groupTotal.merge(seedx.getKey(), seedx.getValue(), Sat::add);
            }
         }

         for (java.util.Map.Entry<AEKey, Long> seedx : groupTotal.entrySet()) {
            sharedMaximum.merge(seedx.getKey(), seedx.getValue(), Math::max);
         }
      }

      for (java.util.Map.Entry<AEKey, Long> seedx : sharedMaximum.entrySet()) {
         this.adjust(ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID, seedx.getKey(), seedx.getValue());
      }
   }

   void initializeAccounts(Map<UUID, Map<AEKey, Long>> accounts) {
      this.clear();
      if (accounts != null) {
         for (java.util.Map.Entry<UUID, Map<AEKey, Long>> account : accounts.entrySet()) {
            if (account.getKey() != null && account.getValue() != null) {
               for (java.util.Map.Entry<AEKey, Long> seed : account.getValue().entrySet()) {
                  if (seed.getKey() != null && seed.getValue() != null && seed.getValue() > 0L) {
                     this.adjust(account.getKey(), seed.getKey(), seed.getValue());
                  }
               }
            }
         }
      }
   }

   void registerConsumers(Iterable<ExecuteLoopPattern> patterns) {
      if (patterns != null) {
         for (ExecuteLoopPattern pattern : patterns) {
            if (pattern != null) {
               this.registerConsumer(pattern);
            }
         }
      }
   }

   private void registerConsumer(ExecuteLoopPattern pattern) {
      this.consumerGroups.computeIfAbsent(pattern.seedConsumerId(), ignored -> new LinkedHashSet<>()).add(pattern.reusableSeedGroupId());
      if (pattern.hasSingleSeedInputPerMember()) {
         this.singleSeedConsumers.add(pattern.seedConsumerId());
      }

      Map<AEKey, ExecuteLoopPattern.SeedVariantRule> rules = this.variantRules.computeIfAbsent(pattern.seedConsumerId(), ignored -> new LinkedHashMap<>());

      for (Entry<AEKey> input : pattern.inputSeed()) {
         ExecuteLoopPattern.SeedVariantRule rule = pattern.seedVariantRule((AEKey)input.getKey());
         rules.merge((AEKey)input.getKey(), rule, ExecuteLoopPattern.SeedVariantRule::merge);
         this.registerBundleUnits(pattern.seedConsumerId(), (AEKey)input.getKey(), input.getLongValue());
      }

      for (UUID target : pattern.outputSeedCredits().keySet()) {
         this.consumerGroups.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(pattern.reusableSeedGroupId());
      }

      for (UUID target : pattern.sharedOutputSeedCredits().keySet()) {
         this.consumerGroups.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(pattern.reusableSeedGroupId());
      }
   }

   Map<AEKey, Long> reservationView(@Nullable UUID ownConsumer, Predicate<AEKey> allowedOwnSeedInput) {
      return this.reservationView(ownConsumer, allowedOwnSeedInput, false);
   }

   Map<AEKey, Long> reservationView(@Nullable final UUID ownConsumer, Predicate<AEKey> allowedOwnSeedInput, final boolean mayUseSharedSeed) {
      final Predicate<AEKey> allowed = allowedOwnSeedInput != null ? allowedOwnSeedInput : ignored -> false;
      return new AbstractMap<AEKey, Long>() {
         public Long get(Object key) {
            if (key instanceof AEKey aeKey) {
               long reserved = LoopSeedLedgerBook.this.totalReserved(aeKey);
               if (reserved <= 0L) {
                  return null;
               } else {
                  if (ownConsumer != null && allowed.test(aeKey)) {
                     reserved = Math.max(0L, reserved - Math.max(0L, LoopSeedLedgerBook.this.balance(ownConsumer, aeKey)));
                     if (mayUseSharedSeed) {
                        reserved = Math.max(0L, reserved - Math.max(0L, LoopSeedLedgerBook.this.balance(ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID, aeKey)));
                     }
                  }

                  return reserved > 0L ? reserved : null;
               }
            } else {
               return null;
            }
         }

         @Override
         public boolean containsKey(Object key) {
            return this.get(key) != null;
         }

         @Override
         public boolean isEmpty() {
            if (LoopSeedLedgerBook.this.totalReserved.isEmpty()) {
               return true;
            } else {
               for (AEKey key : LoopSeedLedgerBook.this.totalReserved.keySet()) {
                  if (this.get(key) != null) {
                     return false;
                  }
               }

               return true;
            }
         }

         @Override
         public Set<java.util.Map.Entry<AEKey, Long>> entrySet() {
            LinkedHashSet<java.util.Map.Entry<AEKey, Long>> entries = new LinkedHashSet<>();

            for (AEKey key : LoopSeedLedgerBook.this.totalReserved.keySet()) {
               Long value = this.get(key);
               if (value != null) {
                  entries.add(Map.entry(key, value));
               }
            }

            return Set.copyOf(entries);
         }
      };
   }

   boolean hasReservations() {
      return !this.totalReserved.isEmpty();
   }

   boolean canRouteActualSeedUses(ExecuteLoopPattern pattern, @Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputUses) {
      if (pattern != null && actualInputUses != null) {
         KeyCounter mappedInputs = new KeyCounter();

         for (ExecuteLoopPattern.ActualSeedUse use : actualInputUses) {
            mappedInputs.add(use.planned(), use.amount());
         }

         KeyCounter plannedSeed = pattern.inputSeed();

         for (Entry<AEKey> planned : plannedSeed) {
            if (mappedInputs.get((AEKey)planned.getKey()) != planned.getLongValue()) {
               return false;
            }
         }

         for (Entry<AEKey> mapped : mappedInputs) {
            if (mapped.getLongValue() != plannedSeed.get((AEKey)mapped.getKey())) {
               return false;
            }
         }

         return this.planRemainderRoutes(pattern, 1L, actualInputUses) != null;
      } else {
         return true;
      }
   }

   Map<UUID, KeyCounter> recordDispatch(
      ExecuteLoopPattern pattern, long copies, boolean sharedBatch, @Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputUses
   ) {
      if (pattern != null && copies > 0L) {
         this.registerConsumer(pattern);
         long scale = sharedBatch ? 1L : copies;
         if (actualInputUses != null) {
            for (ExecuteLoopPattern.ActualSeedUse use : actualInputUses) {
               this.debit(pattern, use.planned(), use.actual(), use.amount());
            }
         } else {
            for (Entry<AEKey> input : pattern.inputSeed()) {
               long amount = Sat.mul(input.getLongValue(), scale);
               if (amount > 0L) {
                  this.debit(pattern, (AEKey)input.getKey(), (AEKey)input.getKey(), amount);
               }
            }
         }

         LoopSeedLedgerBook.RemainderRouting routing = this.planRemainderRoutes(pattern, scale, actualInputUses);
         if (routing == null) {
            throw new IllegalStateException("actual reusable remainder cannot reach its fixed consumer");
         } else {
            Map<UUID, KeyCounter> remainderCredits = this.allocatePhysicalRemainderCredits(routing, actualInputUses, true);

            for (LoopSeedLedgerBook.CreditQuota quota : routing.quotas) {
               if (quota.remaining > 0L) {
                  this.routeCredit(quota, quota.planned, quota.remaining);
               }
            }

            return remainderCredits;
         }
      } else {
         return Map.of();
      }
   }

   Map<UUID, KeyCounter> previewRemainderCredits(
      ExecuteLoopPattern pattern, long copies, boolean sharedBatch, @Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputUses
   ) {
      if (pattern != null && copies > 0L) {
         this.registerConsumer(pattern);
         long scale = sharedBatch ? 1L : copies;
         LoopSeedLedgerBook.RemainderRouting routing = this.planRemainderRoutes(pattern, scale, actualInputUses);
         if (routing == null) {
            throw new IllegalStateException("actual reusable remainder cannot reach its fixed consumer");
         } else {
            return this.allocatePhysicalRemainderCredits(routing, actualInputUses, false);
         }
      } else {
         return Map.of();
      }
   }

   private Map<UUID, KeyCounter> allocatePhysicalRemainderCredits(
      LoopSeedLedgerBook.RemainderRouting routing, @Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputUses, boolean apply
   ) {
      LinkedHashMap<UUID, KeyCounter> remainderCredits = new LinkedHashMap<>();

      for (LoopSeedLedgerBook.RemainderAllocation allocation : routing.allocations) {
         if (apply) {
            this.routeCredit(allocation.quota, allocation.actual, allocation.amount);
         }

         allocation.quota.remaining = allocation.quota.remaining - allocation.amount;
         recordPhysicalRemainderCredit(remainderCredits, allocation.quota, allocation.amount);
      }

      Map<AEKey, Long> unchangedRemainders = deterministicUnchangedRemainderCapacity(actualInputUses);

      for (LoopSeedLedgerBook.CreditQuota quota : routing.quotas) {
         if (quota.remaining > 0L) {
            long available = unchangedRemainders.getOrDefault(quota.planned, 0L);
            long amount = Math.min(quota.remaining, available);
            if (amount > 0L) {
               if (apply) {
                  this.routeCredit(quota, quota.planned, amount);
               }

               quota.remaining -= amount;
               if (amount == available) {
                  unchangedRemainders.remove(quota.planned);
               } else {
                  unchangedRemainders.put(quota.planned, available - amount);
               }

               recordPhysicalRemainderCredit(remainderCredits, quota, amount);
            }
         }
      }

      return Map.copyOf(remainderCredits);
   }

   private static void recordPhysicalRemainderCredit(Map<UUID, KeyCounter> result, LoopSeedLedgerBook.CreditQuota quota, long amount) {
      if (amount > 0L) {
         UUID runtimeConsumer = quota.shared ? ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID : quota.beneficiary;
         result.computeIfAbsent(runtimeConsumer, ignored -> new KeyCounter()).add(quota.planned, amount);
      }
   }

   void recordDispatch(UUID consumer, KeyCounter inputSeed, Map<UUID, KeyCounter> outputCredits, long scale) {
      if (consumer != null && scale > 0L) {
         if (inputSeed != null) {
            for (Entry<AEKey> input : inputSeed) {
               long amount = Sat.mul(input.getLongValue(), scale);
               if (amount > 0L) {
                  this.adjust(consumer, (AEKey)input.getKey(), -amount);
               }
            }
         }

         if (outputCredits != null) {
            for (java.util.Map.Entry<UUID, KeyCounter> target : outputCredits.entrySet()) {
               if (target.getKey() != null && target.getValue() != null) {
                  for (Entry<AEKey> output : target.getValue()) {
                     long amount = Sat.mul(output.getLongValue(), scale);
                     if (amount > 0L) {
                        this.creditConsumer(target.getKey(), (AEKey)output.getKey(), (AEKey)output.getKey(), amount);
                     }
                  }
               }
            }
         }
      }
   }

   private void debit(ExecuteLoopPattern pattern, AEKey planned, AEKey actual, long amount) {
      if (pattern != null && planned != null && actual != null && amount > 0L) {
         UUID consumer = pattern.seedConsumerId();
         long remaining = amount;
         long owned = Math.min(amount, Math.max(0L, this.balance(consumer, actual)));
         if (owned > 0L) {
            this.adjust(consumer, actual, -owned);
            remaining = amount - owned;
         }

         if (remaining > 0L && pattern.hasSingleSeedInputPerMember()) {
            long shared = Math.min(remaining, Math.max(0L, this.balance(ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID, actual)));
            if (shared > 0L) {
               this.adjust(ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID, actual, -shared);
               remaining -= shared;
            }
         }

         if (remaining > 0L) {
            this.adjust(consumer, actual, -remaining);
            this.addVariantDebt(consumer, planned, actual, remaining);
         }
      }
   }

   void rekeyAvailable(UUID consumer, AEKey expected, AEKey actual, long amount) {
      if (consumer != null && expected != null && actual != null && !expected.equals(actual) && amount > 0L) {
         long moved = Math.min(amount, Math.max(0L, this.balance(consumer, expected)));
         if (moved > 0L) {
            this.adjust(consumer, expected, -moved);
            this.adjust(consumer, actual, moved);
         }
      }
   }

   KeyCounter assignHostVariantsForGroup(UUID group, boolean sharedPool, AEKey expected, KeyCounter offered) {
      KeyCounter accepted = new KeyCounter();
      if (group != null && expected != null && offered != null && !offered.isEmpty()) {
         ArrayList<GenericStack> variants = new ArrayList<>();

         for (Entry<AEKey> entry : offered) {
            if (entry.getLongValue() > 0L) {
               variants.add(new GenericStack((AEKey)entry.getKey(), entry.getLongValue()));
            }
         }

         if (variants.isEmpty()) {
            return accepted;
         } else {
            ArrayList<UUID> consumers = new ArrayList<>();

            for (UUID consumer : this.consumerGroups.keySet()) {
               if (this.consumerGroups.getOrDefault(consumer, Set.of()).contains(group) && (!sharedPool || this.singleSeedConsumers.contains(consumer))) {
                  long capacity = this.hostBootstrapRequirements.getOrDefault(consumer, Map.of()).getOrDefault(expected, 0L);
                  if (capacity > 0L) {
                     consumers.add(consumer);
                  }
               }
            }

            consumers.sort(UUID::compareTo);
            if (consumers.isEmpty()) {
               return accepted;
            } else {
               long[] supply = new long[variants.size()];

               for (int i = 0; i < variants.size(); i++) {
                  supply[i] = variants.get(i).amount();
               }

               long[] capacity = new long[consumers.size()];

               for (int i = 0; i < consumers.size(); i++) {
                  capacity[i] = this.hostBootstrapRequirements.getOrDefault(consumers.get(i), Map.of()).getOrDefault(expected, 0L);
               }

               LoopSeedLedgerBook.CapacityMatch match = matchCapacitiesPartially(supply, capacity, (variantIndexx, consumerIndexx) -> {
                  AEKey actual = variants.get(variantIndexx).what();
                  return this.acceptsVariant(consumers.get(consumerIndexx), expected, actual);
               });
               if (match == null) {
                  return accepted;
               } else {
                  if (!this.hostAssignmentsUseWholeBundles(expected, variants, consumers, match.flows)) {
                     match = matchCapacitiesPartially(supply, capacity, (variantIndexx, consumerIndexx) -> expected.equals(variants.get(variantIndexx).what()));
                     if (match == null) {
                        return accepted;
                     }
                  }

                  for (int variantIndex = 0; variantIndex < variants.size(); variantIndex++) {
                     long acceptedVariant = 0L;

                     for (int consumerIndex = 0; consumerIndex < consumers.size(); consumerIndex++) {
                        long amount = match.flows[variantIndex][consumerIndex];
                        if (amount > 0L) {
                           UUID consumerx = consumers.get(consumerIndex);
                           if (sharedPool) {
                              if (!expected.equals(variants.get(variantIndex).what())) {
                                 this.adjust(ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID, expected, -amount);
                                 this.adjust(consumerx, variants.get(variantIndex).what(), amount);
                              }
                           } else {
                              this.adjust(consumerx, expected, -amount);
                              this.adjust(consumerx, variants.get(variantIndex).what(), amount);
                           }

                           Map<AEKey, Long> bootstrap = this.hostBootstrapRequirements.get(consumerx);
                           long left = bootstrap.getOrDefault(expected, 0L) - amount;
                           if (left > 0L) {
                              bootstrap.put(expected, left);
                           } else {
                              bootstrap.remove(expected);
                           }

                           acceptedVariant = Sat.add(acceptedVariant, amount);
                        }
                     }

                     if (acceptedVariant > 0L) {
                        accepted.add(variants.get(variantIndex).what(), acceptedVariant);
                     }
                  }

                  return accepted;
               }
            }
         }
      } else {
         return accepted;
      }
   }

   void clear() {
      this.ledgers.clear();
      this.consumerGroups.clear();
      this.variantRules.clear();
      this.consumerBundleUnits.clear();
      this.singleSeedConsumers.clear();
      this.hostBootstrapRequirements.clear();
      this.variantDebts.clear();
      this.totalReserved.clear();
   }

   void readFromNBT(CompoundTag data, Provider registries) {
      this.clear();
      ListTag accountTags = data.getList("loopSeedLedger", 10);

      for (int i = 0; i < accountTags.size(); i++) {
         CompoundTag accountTag = accountTags.getCompound(i);
         if (accountTag.hasUUID("consumerId")) {
            UUID consumer = accountTag.getUUID("consumerId");
            if (accountTag.getBoolean("singleSeedConsumer")) {
               this.singleSeedConsumers.add(consumer);
            }

            ListTag groups = accountTag.getList("groupIds", 10);

            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
               CompoundTag groupTag = groups.getCompound(groupIndex);
               if (groupTag.hasUUID("groupId")) {
                  this.consumerGroups.computeIfAbsent(consumer, ignored -> new LinkedHashSet<>()).add(groupTag.getUUID("groupId"));
               }
            }

            ListTag entries = accountTag.getList("entries", 10);

            for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
               CompoundTag entryTag = entries.getCompound(entryIndex);
               GenericStack stack = GenericStack.readTag(entryTag);
               if (stack != null && stack.amount() > 0L) {
                  this.adjust(consumer, stack.what(), entryTag.getBoolean("negative") ? -stack.amount() : stack.amount());
               }
            }

            ListTag debts = accountTag.getList("variantDebts", 10);

            for (int debtIndex = 0; debtIndex < debts.size(); debtIndex++) {
               CompoundTag debtTag = debts.getCompound(debtIndex);
               GenericStack actual = GenericStack.readTag(debtTag);
               GenericStack planned = GenericStack.readTag(debtTag.getCompound("planned"));
               if (actual != null && planned != null && actual.amount() > 0L) {
                  this.addVariantDebt(consumer, planned.what(), actual.what(), actual.amount());
               }
            }

            ListTag rules = accountTag.getList("variantRules", 10);

            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
               CompoundTag ruleTag = rules.getCompound(ruleIndex);
               GenericStack planned = GenericStack.readTag(ruleTag.getCompound("planned"));
               if (planned != null) {
                  Set<AEKey> exact = readRuleKeys(ruleTag, "exact", registries);
                  Set<AEKey> fuzzy = readRuleKeys(ruleTag, "fuzzyIdentities", registries);
                  this.variantRules
                     .computeIfAbsent(consumer, ignored -> new LinkedHashMap<>())
                     .merge(planned.what(), new ExecuteLoopPattern.SeedVariantRule(exact, fuzzy), ExecuteLoopPattern.SeedVariantRule::merge);
                  if (ruleTag.contains("bundleUnits", 4)) {
                     this.registerBundleUnits(consumer, planned.what(), ruleTag.getLong("bundleUnits"));
                  }
               }
            }
         }
      }
   }

   void writeToNBT(CompoundTag data, Provider registries) {
      if (this.ledgers.isEmpty()
         && this.variantDebts.isEmpty()
         && this.variantRules.isEmpty()
         && this.consumerBundleUnits.isEmpty()
         && this.consumerGroups.isEmpty()
         && this.singleSeedConsumers.isEmpty()) {
         data.remove("loopSeedLedger");
      } else {
         ListTag accountTags = new ListTag();
         LinkedHashSet<UUID> consumerIds = new LinkedHashSet<>(this.ledgers.keySet());
         consumerIds.addAll(this.variantDebts.keySet());
         consumerIds.addAll(this.variantRules.keySet());
         consumerIds.addAll(this.consumerBundleUnits.keySet());
         consumerIds.addAll(this.consumerGroups.keySet());
         consumerIds.addAll(this.singleSeedConsumers);
         ArrayList<UUID> consumers = new ArrayList<>(consumerIds);
         consumers.sort(UUID::compareTo);

         for (UUID consumer : consumers) {
            Map<AEKey, Long> entries = this.ledgers.getOrDefault(consumer, Map.of());
            CompoundTag accountTag = new CompoundTag();
            accountTag.putUUID("consumerId", consumer);
            if (this.singleSeedConsumers.contains(consumer)) {
               accountTag.putBoolean("singleSeedConsumer", true);
            }

            ListTag groupTags = new ListTag();
            ArrayList<UUID> groups = new ArrayList<>(this.consumerGroups.getOrDefault(consumer, Set.of()));
            groups.sort(UUID::compareTo);

            for (UUID group : groups) {
               CompoundTag groupTag = new CompoundTag();
               groupTag.putUUID("groupId", group);
               groupTags.add(groupTag);
            }

            if (!groupTags.isEmpty()) {
               accountTag.put("groupIds", groupTags);
            }

            ListTag entryTags = new ListTag();

            for (java.util.Map.Entry<AEKey, Long> entry : entries.entrySet()) {
               if (entry.getValue() != 0L) {
                  long magnitude = entry.getValue() == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(entry.getValue());
                  CompoundTag entryTag = GenericStack.writeTag(new GenericStack(entry.getKey(), magnitude));
                  if (entry.getValue() < 0L) {
                     entryTag.putBoolean("negative", true);
                  }

                  entryTags.add(entryTag);
               }
            }

            if (!entryTags.isEmpty()) {
               accountTag.put("entries", entryTags);
            }

            ListTag debtTags = new ListTag();
            Map<AEKey, Map<AEKey, Long>> byPlanned = this.variantDebts.getOrDefault(consumer, Map.of());

            for (java.util.Map.Entry<AEKey, Map<AEKey, Long>> planned : byPlanned.entrySet()) {
               for (java.util.Map.Entry<AEKey, Long> actual : planned.getValue().entrySet()) {
                  if (actual.getValue() > 0L) {
                     CompoundTag debtTag = GenericStack.writeTag(new GenericStack(actual.getKey(), actual.getValue()));
                     debtTag.put("planned", GenericStack.writeTag(new GenericStack(planned.getKey(), 1L)));
                     debtTags.add(debtTag);
                  }
               }
            }

            if (!debtTags.isEmpty()) {
               accountTag.put("variantDebts", debtTags);
            }

            ListTag ruleTags = new ListTag();
            LinkedHashSet<AEKey> plannedRuleKeys = new LinkedHashSet<>();
            plannedRuleKeys.addAll(this.variantRules.getOrDefault(consumer, Map.of()).keySet());
            plannedRuleKeys.addAll(this.consumerBundleUnits.getOrDefault(consumer, Map.of()).keySet());

            for (AEKey planned : plannedRuleKeys) {
               ExecuteLoopPattern.SeedVariantRule rule = this.variantRules
                  .getOrDefault(consumer, Map.of())
                  .getOrDefault(planned, new ExecuteLoopPattern.SeedVariantRule(Set.of(planned), Set.of()));
               CompoundTag ruleTag = new CompoundTag();
               ruleTag.put("planned", GenericStack.writeTag(new GenericStack(planned, 1L)));
               writeRuleKeys(ruleTag, "exact", rule.exactVariants(), registries);
               writeRuleKeys(ruleTag, "fuzzyIdentities", rule.fuzzyIdentities(), registries);
               long bundleUnits = this.consumerBundleUnits.getOrDefault(consumer, Map.of()).getOrDefault(planned, 0L);
               if (this.consumerBundleUnits.getOrDefault(consumer, Map.of()).containsKey(planned)) {
                  ruleTag.putLong("bundleUnits", bundleUnits);
               }

               ruleTags.add(ruleTag);
            }

            if (!ruleTags.isEmpty()) {
               accountTag.put("variantRules", ruleTags);
            }

            if (!entryTags.isEmpty() || !debtTags.isEmpty() || !ruleTags.isEmpty() || !groupTags.isEmpty() || this.singleSeedConsumers.contains(consumer)) {
               accountTags.add(accountTag);
            }
         }

         if (accountTags.isEmpty()) {
            data.remove("loopSeedLedger");
         } else {
            data.put("loopSeedLedger", accountTags);
         }
      }
   }

   long balance(UUID consumer, AEKey key) {
      Map<AEKey, Long> ledger = this.ledgers.get(consumer);
      return ledger != null ? ledger.getOrDefault(key, 0L) : 0L;
   }

   long totalReserved(AEKey key) {
      BigInteger value = this.totalReserved.get(key);
      if (value != null && value.signum() > 0) {
         return value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : value.longValue();
      } else {
         return 0L;
      }
   }

   Map<AEKey, Long> positiveSnapshot() {
      HashMap<AEKey, Long> result = new HashMap<>();

      for (AEKey key : this.totalReserved.keySet()) {
         long amount = this.totalReserved(key);
         if (amount > 0L) {
            result.put(key, amount);
         }
      }

      return Map.copyOf(result);
   }

   private void adjust(UUID consumer, AEKey key, long delta) {
      if (consumer != null && key != null && delta != 0L) {
         Map<AEKey, Long> ledger = this.ledgers.computeIfAbsent(consumer, ignored -> new LinkedHashMap<>());
         long oldValue = ledger.getOrDefault(key, 0L);
         long newValue = saturatingSignedAdd(oldValue, delta);
         if (newValue == 0L) {
            ledger.remove(key);
         } else {
            ledger.put(key, newValue);
         }

         if (ledger.isEmpty()) {
            this.ledgers.remove(consumer);
         }

         long oldPositive = Math.max(0L, oldValue);
         long newPositive = Math.max(0L, newValue);
         BigInteger exact = this.totalReserved.getOrDefault(key, BigInteger.ZERO);
         if (newPositive > oldPositive) {
            exact = exact.add(BigInteger.valueOf(newPositive - oldPositive));
         } else if (oldPositive > newPositive) {
            exact = exact.subtract(BigInteger.valueOf(oldPositive - newPositive));
         }

         if (exact.signum() <= 0) {
            this.totalReserved.remove(key);
         } else {
            this.totalReserved.put(key, exact);
         }
      }
   }

   private List<LoopSeedLedgerBook.CreditQuota> creditQuotas(ExecuteLoopPattern pattern, long scale) {
      ArrayList<LoopSeedLedgerBook.CreditQuota> result = new ArrayList<>();
      addCreditQuotas(result, pattern.outputSeedCredits(), scale, false);
      addCreditQuotas(result, pattern.sharedOutputSeedCredits(), scale, true);
      return result;
   }

   private static void addCreditQuotas(List<LoopSeedLedgerBook.CreditQuota> result, Map<UUID, KeyCounter> credits, long scale, boolean shared) {
      for (java.util.Map.Entry<UUID, KeyCounter> target : credits.entrySet()) {
         for (Entry<AEKey> output : target.getValue()) {
            long amount = Sat.mul(output.getLongValue(), scale);
            if (amount > 0L) {
               result.add(new LoopSeedLedgerBook.CreditQuota(target.getKey(), (AEKey)output.getKey(), amount, shared));
            }
         }
      }
   }

   private void routeCredit(LoopSeedLedgerBook.CreditQuota quota, AEKey physical, long amount) {
      if (quota.shared) {
         this.creditShared(quota.beneficiary, quota.planned, physical, amount);
      } else {
         this.creditConsumer(quota.beneficiary, quota.planned, physical, amount);
      }
   }

   private void creditConsumer(UUID consumer, AEKey planned, AEKey physical, long amount) {
      if (consumer != null && planned != null && physical != null && amount > 0L) {
         long remaining = this.repayVariantDebt(consumer, planned, amount);
         if (remaining > 0L) {
            this.adjust(consumer, physical, remaining);
         }
      }
   }

   private void creditShared(UUID beneficiary, AEKey planned, AEKey physical, long amount) {
      if (beneficiary != null && planned != null && physical != null && amount > 0L) {
         long remaining = this.repayVariantDebt(beneficiary, planned, amount);
         if (remaining > 0L) {
            if (planned.equals(physical)) {
               this.adjust(ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID, physical, remaining);
            } else {
               this.adjust(beneficiary, physical, remaining);
            }
         }
      }
   }

   private void addVariantDebt(UUID consumer, AEKey planned, AEKey actual, long amount) {
      if (consumer != null && planned != null && actual != null && amount > 0L) {
         this.variantDebts
            .computeIfAbsent(consumer, ignored -> new LinkedHashMap<>())
            .computeIfAbsent(planned, ignored -> new LinkedHashMap<>())
            .merge(actual, amount, Sat::add);
      }
   }

   private long repayVariantDebt(UUID consumer, AEKey planned, long amount) {
      if (consumer != null && planned != null && amount > 0L) {
         Map<AEKey, Map<AEKey, Long>> byPlanned = this.variantDebts.get(consumer);
         if (byPlanned == null) {
            return amount;
         } else {
            Map<AEKey, Long> byActual = byPlanned.get(planned);
            if (byActual == null) {
               return amount;
            } else {
               long remaining = amount;
               Iterator<java.util.Map.Entry<AEKey, Long>> iterator = byActual.entrySet().iterator();

               while (iterator.hasNext() && remaining > 0L) {
                  java.util.Map.Entry<AEKey, Long> debt = iterator.next();
                  long repaid = Math.min(remaining, debt.getValue());
                  if (repaid > 0L) {
                     this.adjust(consumer, debt.getKey(), repaid);
                     remaining -= repaid;
                     long left = debt.getValue() - repaid;
                     if (left <= 0L) {
                        iterator.remove();
                     } else {
                        debt.setValue(left);
                     }
                  }
               }

               if (byActual.isEmpty()) {
                  byPlanned.remove(planned);
               }

               if (byPlanned.isEmpty()) {
                  this.variantDebts.remove(consumer);
               }

               return remaining;
            }
         }
      } else {
         return Math.max(0L, amount);
      }
   }

   private boolean acceptsVariant(UUID consumer, AEKey planned, AEKey actual) {
      if (planned == null || actual == null) {
         return false;
      } else if (planned.equals(actual)) {
         return true;
      } else if (this.variantDebt(consumer, planned, actual) > 0L) {
         return true;
      } else {
         ExecuteLoopPattern.SeedVariantRule persisted = this.variantRules.getOrDefault(consumer, Map.of()).get(planned);
         return persisted != null && persisted.accepts(actual);
      }
   }

   boolean acceptsReturnedVariant(UUID account, AEKey planned, AEKey actual) {
      if (account == null || planned == null || actual == null) {
         return false;
      } else {
         return account.equals(ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID)
            ? planned.equals(actual)
            : this.balance(account, planned) <= 0L || this.acceptsVariant(account, planned, actual);
      }
   }

   boolean acceptsLateBoundVariantCredit(UUID consumer, AEKey planned) {
      return this.bundleUnits(consumer, planned) == 1L;
   }

   private static Set<AEKey> readRuleKeys(CompoundTag owner, String name, Provider registries) {
      LinkedHashSet<AEKey> result = new LinkedHashSet<>();
      ListTag tags = owner.getList(name, 10);

      for (int i = 0; i < tags.size(); i++) {
         GenericStack stack = GenericStack.readTag(tags.getCompound(i));
         if (stack != null) {
            result.add(stack.what());
         }
      }

      return Set.copyOf(result);
   }

   private static void writeRuleKeys(CompoundTag owner, String name, Set<AEKey> keys, Provider registries) {
      if (!keys.isEmpty()) {
         ListTag tags = new ListTag();

         for (AEKey key : keys) {
            tags.add(GenericStack.writeTag(new GenericStack(key, 1L)));
         }

         owner.put(name, tags);
      }
   }

   private long variantDebt(UUID consumer, AEKey planned, AEKey actual) {
      Map<AEKey, Map<AEKey, Long>> byPlanned = this.variantDebts.get(consumer);
      if (byPlanned == null) {
         return 0L;
      } else {
         Map<AEKey, Long> byActual = byPlanned.get(planned);
         return byActual != null ? byActual.getOrDefault(actual, 0L) : 0L;
      }
   }

   private void registerBundleUnits(UUID consumer, AEKey planned, long units) {
      if (consumer != null && planned != null && units >= 0L) {
         Map<AEKey, Long> byPlanned = this.consumerBundleUnits.computeIfAbsent(consumer, ignored -> new LinkedHashMap<>());
         Long previous = byPlanned.get(planned);
         if (previous == null) {
            byPlanned.put(planned, units);
         } else if (previous != units) {
            byPlanned.put(planned, 0L);
         }
      }
   }

   private long bundleUnits(UUID consumer, AEKey planned) {
      return this.consumerBundleUnits.getOrDefault(consumer, Map.of()).getOrDefault(planned, 0L);
   }

   @Nullable
   private LoopSeedLedgerBook.RemainderRouting planRemainderRoutes(
      ExecuteLoopPattern pattern, long scale, @Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputUses
   ) {
      List<LoopSeedLedgerBook.CreditQuota> quotas = this.creditQuotas(pattern, scale);
      LinkedHashMap<AEKey, Long> quotaByPlanned = new LinkedHashMap<>();

      for (LoopSeedLedgerBook.CreditQuota quota : quotas) {
         quotaByPlanned.merge(quota.planned, Long.valueOf(quota.remaining), Sat::add);
      }

      Map<AEKey, Long> exactOutputByPlanned = deterministicExactOutputCapacity(pattern, scale, actualInputUses);
      ArrayList<LoopSeedLedgerBook.RemainderDemand> variants = new ArrayList<>();
      if (actualInputUses != null) {
         for (ExecuteLoopPattern.ActualSeedUse use : actualInputUses) {
            if (use.plannedRemainder() != null && quotaByPlanned.getOrDefault(use.plannedRemainder(), 0L) > 0L) {
               if (use.actualRemainder() == null) {
                  return null;
               }

               if (!use.plannedRemainder().equals(use.actualRemainder())) {
                  if (use.remainderAmount() <= 0L) {
                     return null;
                  }

                  variants.add(new LoopSeedLedgerBook.RemainderDemand(use.plannedRemainder(), use.actualRemainder(), use.remainderAmount()));
               }
            }
         }
      }

      if (variants.isEmpty()) {
         return new LoopSeedLedgerBook.RemainderRouting(quotas, List.of());
      } else {
         long[] supply = new long[variants.size()];

         for (int i = 0; i < variants.size(); i++) {
            supply[i] = variants.get(i).amount;
         }

         long[] capacity = new long[quotas.size()];

         for (int i = 0; i < quotas.size(); i++) {
            capacity[i] = quotas.get(i).remaining;
         }

         LoopSeedLedgerBook.CapacityMatch match = matchCapacitiesPartially(supply, capacity, (variantIndexx, quotaIndexx) -> {
            LoopSeedLedgerBook.RemainderDemand variant = variants.get(variantIndexx);
            LoopSeedLedgerBook.CreditQuota quota = quotas.get(quotaIndexx);
            return quota.planned.equals(variant.planned) && this.acceptsVariant(quota.beneficiary, quota.planned, variant.actual);
         });
         if (match == null) {
            return null;
         } else {
            LinkedHashMap<AEKey, Long> requiredChangedByPlanned = new LinkedHashMap<>();

            for (AEKey planned : quotaByPlanned.keySet()) {
               long variantSupply = 0L;
               long matched = 0L;

               for (int variantIndex = 0; variantIndex < variants.size(); variantIndex++) {
                  if (planned.equals(variants.get(variantIndex).planned)) {
                     variantSupply = Sat.add(variantSupply, supply[variantIndex]);

                     for (int quotaIndex = 0; quotaIndex < quotas.size(); quotaIndex++) {
                        if (planned.equals(quotas.get(quotaIndex).planned)) {
                           matched = Sat.add(matched, match.flows[variantIndex][quotaIndex]);
                        }
                     }
                  }
               }

               long exactCapacity = exactOutputByPlanned.getOrDefault(planned, 0L);
               long uncoveredQuota = Math.max(0L, quotaByPlanned.get(planned) - exactCapacity);
               long required = Math.min(variantSupply, uncoveredQuota);
               if (matched < required) {
                  return null;
               }

               if (required > 0L) {
                  requiredChangedByPlanned.put(planned, Long.valueOf(required));
               }
            }

            ArrayList<LoopSeedLedgerBook.RemainderAllocation> allocations = new ArrayList<>();

            for (int variantIndexx = 0; variantIndexx < variants.size(); variantIndexx++) {
               AEKey planned = variants.get(variantIndexx).planned;
               long requiredx = requiredChangedByPlanned.getOrDefault(planned, 0L);
               if (requiredx > 0L) {
                  for (int quotaIndexx = 0; quotaIndexx < quotas.size(); quotaIndexx++) {
                     long amount = Math.min(requiredx, match.flows[variantIndexx][quotaIndexx]);
                     if (amount > 0L) {
                        allocations.add(new LoopSeedLedgerBook.RemainderAllocation(quotas.get(quotaIndexx), variants.get(variantIndexx).actual, amount));
                        requiredx -= amount;
                        requiredChangedByPlanned.put(planned, Long.valueOf(requiredx));
                     }
                  }
               }
            }

            return !this.usesWholeConsumerBundles(allocations) ? null : new LoopSeedLedgerBook.RemainderRouting(quotas, List.copyOf(allocations));
         }
      }
   }

   private boolean usesWholeConsumerBundles(List<LoopSeedLedgerBook.RemainderAllocation> allocations) {
      LinkedHashMap<LoopSeedLedgerBook.ConcreteBundle, Long> amounts = new LinkedHashMap<>();

      for (LoopSeedLedgerBook.RemainderAllocation allocation : allocations) {
         UUID consumer = allocation.quota.shared ? ExecuteLoopPattern.SHARED_SEED_ACCOUNT_ID : allocation.quota.beneficiary;
         amounts.merge(new LoopSeedLedgerBook.ConcreteBundle(consumer, allocation.quota.planned, allocation.actual), Long.valueOf(allocation.amount), Sat::add);
      }

      for (java.util.Map.Entry<LoopSeedLedgerBook.ConcreteBundle, Long> allocation : amounts.entrySet()) {
         long bundle = this.bundleUnits(allocation.getKey().consumer, allocation.getKey().planned);
         if (bundle <= 0L || allocation.getValue() % bundle != 0L) {
            return false;
         }
      }

      return true;
   }

   private boolean hostAssignmentsUseWholeBundles(AEKey planned, List<GenericStack> variants, List<UUID> consumers, long[][] flows) {
      LinkedHashMap<LoopSeedLedgerBook.ConcreteBundle, Long> amounts = new LinkedHashMap<>();

      for (int variantIndex = 0; variantIndex < variants.size(); variantIndex++) {
         AEKey actual = variants.get(variantIndex).what();
         if (!planned.equals(actual)) {
            for (int consumerIndex = 0; consumerIndex < consumers.size(); consumerIndex++) {
               long amount = flows[variantIndex][consumerIndex];
               if (amount > 0L) {
                  amounts.merge(new LoopSeedLedgerBook.ConcreteBundle(consumers.get(consumerIndex), planned, actual), Long.valueOf(amount), Sat::add);
               }
            }
         }
      }

      for (java.util.Map.Entry<LoopSeedLedgerBook.ConcreteBundle, Long> allocation : amounts.entrySet()) {
         long bundle = this.bundleUnits(allocation.getKey().consumer, allocation.getKey().planned);
         if (bundle <= 0L || allocation.getValue() % bundle != 0L) {
            return false;
         }
      }

      return true;
   }

   private static Map<AEKey, Long> deterministicExactOutputCapacity(
      ExecuteLoopPattern pattern, long scale, @Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputUses
   ) {
      LinkedHashMap<AEKey, Long> result = new LinkedHashMap<>();
      OverloadedProviderOnlyPatternDetails overload = CraftingPatternDelegates.forProviderLookup(pattern) instanceof OverloadedProviderOnlyPatternDetails details
         ? details
         : null;
      GenericStack[] outputs = pattern.getOutputs();

      for (int slot = 0; slot < outputs.length; slot++) {
         GenericStack output = outputs[slot];
         if (output.what() != null && output.amount() > 0L && (overload == null || !overload.isFuzzyOutput(slot))) {
            result.merge(output.what(), Long.valueOf(Sat.mul(output.amount(), scale)), Sat::add);
         }
      }

      if (actualInputUses != null) {
         for (ExecuteLoopPattern.ActualSeedUse use : actualInputUses) {
            if (use.plannedRemainder() != null && use.plannedRemainder().equals(use.actualRemainder()) && use.remainderAmount() > 0L) {
               result.merge(use.plannedRemainder(), Long.valueOf(use.remainderAmount()), Sat::add);
            }
         }
      }

      return result;
   }

   private static Map<AEKey, Long> deterministicUnchangedRemainderCapacity(@Nullable List<ExecuteLoopPattern.ActualSeedUse> actualInputUses) {
      if (actualInputUses != null && !actualInputUses.isEmpty()) {
         LinkedHashMap<AEKey, Long> result = new LinkedHashMap<>();

         for (ExecuteLoopPattern.ActualSeedUse use : actualInputUses) {
            if (use.plannedRemainder() != null && use.plannedRemainder().equals(use.actualRemainder()) && use.remainderAmount() > 0L) {
               result.merge(use.plannedRemainder(), Long.valueOf(use.remainderAmount()), Sat::add);
            }
         }

         return result;
      } else {
         return Map.of();
      }
   }

   @Nullable
   private static LoopSeedLedgerBook.CapacityMatch matchCapacitiesPartially(long[] supply, long[] capacity, BiPredicate<Integer, Integer> allowed) {
      int supplyCount = supply.length;
      int capacityCount = capacity.length;
      int source = 0;
      int firstSupply = 1;
      int firstCapacity = firstSupply + supplyCount;
      int sink = firstCapacity + capacityCount;
      List<LoopSeedLedgerBook.FlowEdge>[] graph = new List[sink + 1];

      for (int i = 0; i < graph.length; i++) {
         graph[i] = new ArrayList<>();
      }

      LoopSeedLedgerBook.FlowEdge[] sourceEdges = new LoopSeedLedgerBook.FlowEdge[supplyCount];
      LoopSeedLedgerBook.FlowEdge[][] assignmentEdges = new LoopSeedLedgerBook.FlowEdge[supplyCount][capacityCount];

      for (int i = 0; i < supplyCount; i++) {
         if (supply[i] < 0L) {
            return null;
         }

         sourceEdges[i] = addFlowEdge(graph, source, firstSupply + i, supply[i]);
      }

      for (int j = 0; j < capacityCount; j++) {
         if (capacity[j] < 0L) {
            return null;
         }

         addFlowEdge(graph, firstCapacity + j, sink, capacity[j]);
      }

      for (int i = 0; i < supplyCount; i++) {
         for (int j = 0; j < capacityCount; j++) {
            if (allowed.test(i, j)) {
               assignmentEdges[i][j] = addFlowEdge(graph, firstSupply + i, firstCapacity + j, Long.MAX_VALUE);
            }
         }
      }

      int[] level = new int[graph.length];

      while (buildFlowLevels(graph, source, sink, level)) {
         int[] next = new int[graph.length];

         while (pushFlow(graph, source, sink, Long.MAX_VALUE, level, next) > 0L) {
         }
      }

      boolean complete = true;

      for (LoopSeedLedgerBook.FlowEdge edge : sourceEdges) {
         complete &= edge.capacity == 0L;
      }

      long[][] result = new long[supplyCount][capacityCount];

      for (int i = 0; i < supplyCount; i++) {
         for (int jx = 0; jx < capacityCount; jx++) {
            LoopSeedLedgerBook.FlowEdge edge = assignmentEdges[i][jx];
            if (edge != null) {
               result[i][jx] = edge.reverse.capacity;
            }
         }
      }

      return new LoopSeedLedgerBook.CapacityMatch(result, complete);
   }

   private static LoopSeedLedgerBook.FlowEdge addFlowEdge(List<LoopSeedLedgerBook.FlowEdge>[] graph, int from, int to, long capacity) {
      LoopSeedLedgerBook.FlowEdge forward = new LoopSeedLedgerBook.FlowEdge(to, capacity);
      LoopSeedLedgerBook.FlowEdge reverse = new LoopSeedLedgerBook.FlowEdge(from, 0L);
      forward.reverse = reverse;
      reverse.reverse = forward;
      graph[from].add(forward);
      graph[to].add(reverse);
      return forward;
   }

   private static boolean buildFlowLevels(List<LoopSeedLedgerBook.FlowEdge>[] graph, int source, int sink, int[] level) {
      Arrays.fill(level, -1);
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      level[source] = 0;
      queue.add(source);

      while (!queue.isEmpty()) {
         int node = queue.removeFirst();

         for (LoopSeedLedgerBook.FlowEdge edge : graph[node]) {
            if (edge.capacity > 0L && level[edge.to] < 0) {
               level[edge.to] = level[node] + 1;
               queue.addLast(edge.to);
            }
         }
      }

      return level[sink] >= 0;
   }

   private static long pushFlow(List<LoopSeedLedgerBook.FlowEdge>[] graph, int node, int sink, long offered, int[] level, int[] next) {
      if (node == sink) {
         return offered;
      } else {
         while (next[node] < graph[node].size()) {
            LoopSeedLedgerBook.FlowEdge edge = graph[node].get(next[node]);
            if (edge.capacity > 0L && level[edge.to] == level[node] + 1) {
               long sent = pushFlow(graph, edge.to, sink, Math.min(offered, edge.capacity), level, next);
               if (sent > 0L) {
                  edge.capacity -= sent;
                  edge.reverse.capacity += sent;
                  return sent;
               }
            }

            next[node]++;
         }

         return 0L;
      }
   }

   private static long saturatingSignedAdd(long left, long right) {
      if (right > 0L && left > Long.MAX_VALUE - right) {
         return Long.MAX_VALUE;
      } else {
         return right >= 0L || right != Long.MIN_VALUE && left >= -9223372036854775807L - right ? left + right : -9223372036854775807L;
      }
   }

   private static record CapacityMatch(long[][] flows, boolean complete) {
   }

   private static record ConcreteBundle(UUID consumer, AEKey planned, AEKey actual) {
   }

   private static final class CreditQuota {
      private final UUID beneficiary;
      private final AEKey planned;
      private final boolean shared;
      private long remaining;

      private CreditQuota(UUID beneficiary, AEKey planned, long remaining, boolean shared) {
         this.beneficiary = beneficiary;
         this.planned = planned;
         this.remaining = remaining;
         this.shared = shared;
      }
   }

   private static final class FlowEdge {
      private final int to;
      private long capacity;
      private LoopSeedLedgerBook.FlowEdge reverse;

      private FlowEdge(int to, long capacity) {
         this.to = to;
         this.capacity = capacity;
      }
   }

   private static record RemainderAllocation(LoopSeedLedgerBook.CreditQuota quota, AEKey actual, long amount) {
   }

   private static record RemainderDemand(AEKey planned, AEKey actual, long amount) {
   }

   private static record RemainderRouting(List<LoopSeedLedgerBook.CreditQuota> quotas, List<LoopSeedLedgerBook.RemainderAllocation> allocations) {
   }
}
