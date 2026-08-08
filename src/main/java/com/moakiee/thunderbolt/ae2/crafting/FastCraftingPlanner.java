package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.thunderbolt.ae2.timewheel.ReusableSeedPattern;
import com.moakiee.thunderbolt.core.planner.BoundedCombinations;
import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftInput;
import com.moakiee.thunderbolt.core.planner.CraftOutput;
import com.moakiee.thunderbolt.core.planner.CraftPattern;
import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.CraftPlannerV2;
import com.moakiee.thunderbolt.core.planner.DurabilityChain;
import com.moakiee.thunderbolt.core.planner.PlanningResult;
import com.moakiee.thunderbolt.core.planner.ReusableStockFallback;
import com.moakiee.thunderbolt.core.planner.ReusableStockSource;
import com.moakiee.thunderbolt.core.planner.ReusableStockUsageKey;
import com.moakiee.thunderbolt.core.planner.Sat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class FastCraftingPlanner {
   static final long FUZZY_NONCYCLE_STEPS = 64L;
   static final long FUZZY_CYCLE_STEPS = 8192L;

   private FastCraftingPlanner() {
   }

   public static FastCraftingPlanner.FastAttempt tryAttempt(
      ICraftingService craftingService, CraftingSimulationState networkInv, Level level, AEKey output, long amount, boolean simulate
   ) {
      return tryAttempt(craftingService, networkInv, level, output, amount, simulate, null);
   }

   public static FastCraftingPlanner.FastAttempt tryAttempt(
      ICraftingService craftingService,
      CraftingSimulationState networkInv,
      Level level,
      AEKey output,
      long amount,
      boolean simulate,
      @Nullable ReservedStockCraftingRequester reservedStock
   ) {
      if (amount <= 0L) {
         return FastCraftingPlanner.FastAttempt.decline();
      } else {
         ChildCraftingSimulationState snapshot = new ChildCraftingSimulationState(networkInv);
         ChildCraftingSimulationState reusableSeedSnapshot = new ChildCraftingSimulationState(networkInv);
         snapshot.ignore(output);
         Set<Item> conservativeDurabilityItems = new HashSet<>();
         long graphBuildStarted = System.nanoTime();

         FastCraftingPlanner.GraphBuild graphBuild;
         Set<Item> conflicts;
         do {
            graphBuild = new FastCraftingPlanner.GraphBuild();
            conflicts = buildGraph(
               craftingService,
               snapshot,
               reusableSeedSnapshot,
               level,
               output,
               graphBuild.builder,
               graphBuild.multiplePaths,
               graphBuild.durability,
               graphBuild.patternSources,
               graphBuild.emittable,
               conservativeDurabilityItems,
               reservedStock
            );
         } while (!conflicts.isEmpty() && conservativeDurabilityItems.addAll(conflicts));

         CraftGraph<AEKey> graph = graphBuild.builder.build();
         FastPlanningWatchdog.recordGraphBuild(System.nanoTime() - graphBuildStarted);
         PlanningResult<AEKey> planning = CraftPlannerV2.planDetailed(graph, output, amount);
         FastPlanningWatchdog.record(planning.diagnostics());
         CraftPlan<AEKey> plan = planning.plan();
         if (!plan.supported()) {
            return FastCraftingPlanner.FastAttempt.decline();
         } else {
            boolean multi = graphBuild.multiplePaths[0];
            if (plan.feasible() || noNonEmittableMissing(plan, graphBuild.emittable)) {
               return FastCraftingPlanner.FastAttempt.handled(
                  toAe2Plan(output, amount, plan, multi, false, graphBuild.durability, graphBuild.patternSources, graphBuild.emittable, snapshot, reservedStock),
                  plan.usedReusableStock()
               );
            } else {
               return !simulate
                  ? FastCraftingPlanner.FastAttempt.infeasible(
                     toAe2Plan(
                        output, amount, plan, multi, true, graphBuild.durability, graphBuild.patternSources, graphBuild.emittable, snapshot, reservedStock
                     ),
                     plan.usedReusableStock()
                  )
                  : FastCraftingPlanner.FastAttempt.handled(
                     toAe2Plan(
                        output, amount, plan, multi, true, graphBuild.durability, graphBuild.patternSources, graphBuild.emittable, snapshot, reservedStock
                     ),
                     plan.usedReusableStock()
                  );
            }
         }
      }
   }

   private static boolean noNonEmittableMissing(CraftPlan<AEKey> plan, Set<AEKey> emittable) {
      for (AEKey k : plan.missing().keySet()) {
         if (!emittable.contains(k)) {
            return false;
         }
      }

      return true;
   }

   private static boolean sameItem(AEKey a, AEKey b) {
      if (a instanceof AEItemKey ai && b instanceof AEItemKey bi && ai.getItem() == bi.getItem()) {
         return true;
      }

      return false;
   }

   private static Set<Item> buildGraph(
      ICraftingService craftingService,
      ChildCraftingSimulationState snapshot,
      ChildCraftingSimulationState reusableSeedSnapshot,
      Level level,
      AEKey root,
      CraftGraph.Builder<AEKey> builder,
      boolean[] multiplePaths,
      Map<AEKey, DurabilityChain<AEKey>> durability,
      Map<AEKey, Set<IPatternDetails>> patternSources,
      Set<AEKey> emittable,
      Set<Item> conservativeDurabilityItems,
      @Nullable ReservedStockCraftingRequester reservedStock
   ) {
      Set<AEKey> seen = new HashSet<>();
      Deque<AEKey> queue = new ArrayDeque<>();
      Map<AEKey, Long> availability = new HashMap<>();
      Map<AEKey, Long> supplementalSelfSeedStock = new HashMap<>();
      Set<AEKey> itemUnitKeys = new HashSet<>();
      Map<AEKey, DurabilityChain<AEKey>> linkOwner = new HashMap<>();
      Set<Item> durabilityConflicts = new HashSet<>();
      Map<AEKey, List<AEKey>> craftableVariantCache = new HashMap<>();
      seen.add(root);
      queue.add(root);

      while (!queue.isEmpty()) {
         AEKey key = queue.poll();
         DurabilityChain<AEKey> carrier = durability.get(key);
         long outputScale = 1L;
         if (carrier != null) {
            outputScale = carrier.n();
         } else {
            long available = usableStock(snapshot, key, reservedStock);
            if (available > 0L) {
               builder.stock(key, available);
            }

            itemUnitKeys.add(key);
         }

         if (craftingService.canEmitFor(key)) {
            emittable.add(key);
            builder.stock(key, 2305843009213693951L);
         } else {
            Collection<IPatternDetails> patterns = craftingService.getCraftingFor(key);
            int registeredForKey = 0;

            for (IPatternDetails details : patterns) {
               GenericStack primaryStack = details.getPrimaryOutput();
               if (primaryStack != null && key.equals(primaryStack.what())) {
                  patternSources.computeIfAbsent(key, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(details);
                  GenericStack[] outputs = details.getOutputs();
                  GenericStack primary = null;
                  List<CraftOutput<AEKey>> byproducts = new ArrayList<>(Math.max(0, outputs.length - 1));

                  for (GenericStack out : outputs) {
                     if (primary == null && key.equals(out.what())) {
                        primary = out;
                     } else {
                        byproducts.add(CraftOutput.of(out.what(), out.amount()));
                     }
                  }

                  if (primary != null) {
                     if (details instanceof ReusableSeedPattern seeded) {
                        ReusableStockSource source = seeded.reusableStockSource();
                        Map<AEKey, Long> physicalVariants = seeded.availableReusableSeedSnapshot();

                        for (Entry<AEKey, Long> entry : physicalVariants.entrySet()) {
                           if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                              builder.reusableStock(source.storageScope(), entry.getKey(), entry.getValue());
                           }
                        }

                        for (Entry<AEKey, Long> requirement : seeded.totalReusableSeedRequirements().entrySet()) {
                           if (requirement.getKey() != null && requirement.getValue() != null && requirement.getValue() > 0L) {
                              ArrayList<AEKey> acceptedVariants = new ArrayList<>();

                              for (AEKey actual : physicalVariants.keySet()) {
                                 if (actual != null && seeded.acceptsReusableSeedVariant(requirement.getKey(), actual)) {
                                    acceptedVariants.add(actual);
                                 }
                              }

                              builder.reusableStockRoute(source, requirement.getKey(), acceptedVariants);
                           }
                        }

                        long selfSeedRequired = seeded.totalReusableSeedRequirements().getOrDefault(key, 0L);
                        if (selfSeedRequired > 0L) {
                           long supplemental = ReusableStockFallback.supplementalSelfSeedStock(
                              selfSeedRequired, usableStock(reusableSeedSnapshot, key, reservedStock), usableStock(snapshot, key, reservedStock)
                           );
                           long previous = supplementalSelfSeedStock.getOrDefault(key, 0L);
                           if (supplemental > previous) {
                              builder.stock(key, supplemental - previous);
                              supplementalSelfSeedStock.put(key, supplemental);
                           }
                        }
                     }

                     IInput[] inputs = details.getInputs();
                     OverloadedProviderOnlyPatternDetails overloadView = details instanceof OverloadedProviderOnlyPatternDetails op ? op : null;
                     List<List<FastCraftingPlanner.SlotChoice>> slotOptions = new ArrayList<>(inputs.length);
                     long combos = 1L;
                     boolean patternUnsatisfiable = false;

                     for (int slot = 0; slot < inputs.length; slot++) {
                        IInput in = inputs[slot];
                        FastCraftingPlanner.ChainLookup lookup = durabilityChain(
                           in, craftingService, snapshot, level, builder, durability, linkOwner, itemUnitKeys, conservativeDurabilityItems, reservedStock
                        );
                        if (lookup.conflict()) {
                           durabilityConflicts.add(lookup.conflictItem());
                           patternUnsatisfiable = true;
                           break;
                        }

                        DurabilityChain<AEKey> chain = lookup.chain();
                        if (chain != null) {
                           long usesPerFiring = Sat.mul(Math.max(1L, in.getPossibleInputs()[0].amount()), Math.max(1L, in.getMultiplier()));
                           slotOptions.add(List.of(new FastCraftingPlanner.SlotChoice(List.of(CraftInput.of(chain.carrier(), usesPerFiring)))));
                        } else {
                           boolean idOnly = overloadView != null && overloadView.isFuzzyInput(slot);
                           List<GenericStack> templates = idOnlyTemplates(in, idOnly, snapshot, craftingService, craftableVariantCache);
                           templates = addConservativeDurabilityTemplates(in, templates, craftingService, snapshot, level, conservativeDurabilityItems);
                           List<CraftInput<AEKey>> opts = new ArrayList<>(templates.size());

                           for (GenericStack template : templates) {
                              AEKey inputKey = template.what();
                              if (linkOwner.containsKey(inputKey)) {
                                 DurabilityChain<AEKey> owner = linkOwner.get(inputKey);
                                 if (owner.carrier() instanceof AEItemKey carrierKey) {
                                    durabilityConflicts.add(carrierKey.getItem());
                                 }

                                 patternUnsatisfiable = true;
                                 break;
                              }

                              itemUnitKeys.add(inputKey);
                              AEKey var50 = in.getRemainingKey(inputKey);
                              AEKey remaining = var50 instanceof AEKey ? var50 : null;
                              if (remaining == null) {
                                 opts.add(CraftInput.of(inputKey, template.amount()));
                              } else if (remaining.equals(inputKey)) {
                                 if (details instanceof ReusableSeedPattern seeded && seeded.totalReusableSeedRequirements().getOrDefault(inputKey, 0L) > 0L) {
                                    opts.add(CraftInput.returnedFrom(inputKey, template.amount(), seeded.reusableStockSource()));
                                    continue;
                                 }

                                 opts.add(CraftInput.returned(inputKey, template.amount()));
                              } else if (sameItem(inputKey, remaining)) {
                                 if (inputKey instanceof AEItemKey itemKey && conservativeDurabilityItems.contains(itemKey.getItem())) {
                                    opts.add(CraftInput.of(inputKey, template.amount()));
                                 }
                              } else {
                                 opts.add(CraftInput.consumedReturning(inputKey, template.amount(), remaining));
                              }
                           }

                           if (patternUnsatisfiable) {
                              break;
                           }

                           if (opts.isEmpty()) {
                              patternUnsatisfiable = true;
                              break;
                           }

                           if (opts.size() > 1) {
                              for (CraftInput<AEKey> o : opts) {
                                 availability.computeIfAbsent(o.key(), k -> usableStock(snapshot, k, reservedStock));
                              }

                              opts.sort(Comparator.<CraftInput<AEKey>>comparingLong(ox -> availability.get(ox.key())).reversed());
                           }

                           List<FastCraftingPlanner.SlotChoice> choices = expandSlotChoices(opts, in.getMultiplier(), availability);
                           slotOptions.add(choices);
                           combos = Sat.mul(combos, (long)choices.size());
                        }
                     }

                     if (!patternUnsatisfiable) {
                        if (combos > 1L) {
                           multiplePaths[0] = true;
                        }

                        long outAmount = Sat.mul(primary.amount(), outputScale);
                        emitBestCombinations(builder, seen, queue, key, outAmount, byproducts, slotOptions, details);
                        registeredForKey++;
                     }
                  }
               }
            }

            if (registeredForKey > 1) {
               multiplePaths[0] = true;
            }
         }
      }

      return durabilityConflicts;
   }

   private static List<GenericStack> idOnlyTemplates(
      IInput in, boolean idOnly, ChildCraftingSimulationState snapshot, ICraftingService craftingService, Map<AEKey, List<AEKey>> craftableVariantCache
   ) {
      GenericStack[] possible = in.getPossibleInputs();
      if (!idOnly) {
         return Arrays.asList(possible);
      } else {
         LinkedHashMap<AEKey, GenericStack> byKey = new LinkedHashMap<>();

         for (GenericStack template : possible) {
            byKey.putIfAbsent(template.what(), template);

            for (AEKey fuzzy : snapshot.findFuzzyTemplates(template.what())) {
               byKey.putIfAbsent(fuzzy, new GenericStack(fuzzy, template.amount()));
            }

            for (AEKey craftable : craftableSameIdVariants(craftingService, template.what(), craftableVariantCache)) {
               byKey.putIfAbsent(craftable, new GenericStack(craftable, template.amount()));
            }
         }

         return new ArrayList<>(byKey.values());
      }
   }

   private static List<AEKey> craftableSameIdVariants(ICraftingService craftingService, AEKey template, Map<AEKey, List<AEKey>> cache) {
      return cache.computeIfAbsent(template, t -> List.copyOf(craftingService.getCraftables(k -> k.getType() == t.getType() && k.getId().equals(t.getId()))));
   }

   private static List<GenericStack> addConservativeDurabilityTemplates(
      IInput in,
      List<GenericStack> base,
      ICraftingService craftingService,
      ChildCraftingSimulationState snapshot,
      Level level,
      Set<Item> conservativeDurabilityItems
   ) {
      if (conservativeDurabilityItems.isEmpty()) {
         return base;
      } else {
         Map<AEKey, GenericStack> byKey = new LinkedHashMap<>();

         for (GenericStack template : base) {
            byKey.putIfAbsent(template.what(), template);
         }

         for (GenericStack possible : in.getPossibleInputs()) {
            AEKey item = possible.what();
            if (item instanceof AEItemKey) {
               AEItemKey template = (AEItemKey)item;
               if (conservativeDurabilityItems.contains(template.getItem())) {
                  Item itemx = template.getItem();
                  AEKey variant = craftingService.getFuzzyCraftable(template, k -> in.isValid(k, level));
                  if (variant instanceof AEItemKey) {
                     AEItemKey craftable = (AEItemKey)variant;
                     if (craftable.getItem() == itemx) {
                        byKey.putIfAbsent(craftable, new GenericStack(craftable, possible.amount()));
                     }
                  }

                  for (AEKey variantx : snapshot.findFuzzyTemplates(template)) {
                     if (variantx instanceof AEItemKey) {
                        AEItemKey itemVariant = (AEItemKey)variantx;
                        if (itemVariant.getItem() == itemx && in.isValid(variantx, level)) {
                           byKey.putIfAbsent(variantx, new GenericStack(variantx, possible.amount()));
                        }
                     }
                  }
               }
            }
         }

         return new ArrayList<>(byKey.values());
      }
   }

   private static FastCraftingPlanner.ChainLookup durabilityChain(
      IInput in,
      ICraftingService craftingService,
      ChildCraftingSimulationState snapshot,
      Level level,
      CraftGraph.Builder<AEKey> builder,
      Map<AEKey, DurabilityChain<AEKey>> registry,
      Map<AEKey, DurabilityChain<AEKey>> linkOwner,
      Set<AEKey> itemUnitKeys,
      Set<Item> conservativeDurabilityItems,
      @Nullable ReservedStockCraftingRequester reservedStock
   ) {
      GenericStack[] possible = in.getPossibleInputs();
      if (possible.length != 0 && possible[0].what() instanceof AEItemKey template) {
         Item var20 = template.getItem();
         if (conservativeDurabilityItems.contains(var20)) {
            return FastCraftingPlanner.ChainLookup.NONE;
         } else {
            AEItemKey full = fullestAnchor(in, craftingService, snapshot, level, template, var20);
            AEKey remaining = in.getRemainingKey(full);
            if (full.equals(remaining)) {
               return FastCraftingPlanner.ChainLookup.NONE;
            } else {
               AEItemKey var10000;
               label75: {
                  if (remaining instanceof AEItemKey next && next.getItem() == var20) {
                     var10000 = next;
                     break label75;
                  }

                  var10000 = null;
               }

               AEKey step = var10000;
               DurabilityChain<AEKey> owner = linkOwner.get(full);
               if (owner != null) {
                  if (step == null) {
                     return FastCraftingPlanner.ChainLookup.NONE;
                  } else {
                     List<AEKey> links = owner.links();
                     int idx = links.indexOf(full);
                     AEKey chainNext = idx >= 0 && idx + 1 < links.size() ? links.get(idx + 1) : null;
                     return step.equals(chainNext) ? new FastCraftingPlanner.ChainLookup(owner, null) : FastCraftingPlanner.ChainLookup.conflict(var20);
                  }
               } else if (step == null) {
                  return FastCraftingPlanner.ChainLookup.NONE;
               } else {
                  DurabilityChain<AEKey> chain = DurabilityChain.build(full, k -> {
                     if (in.getRemainingKey(k) instanceof AEItemKey next && next.getItem() == var20) {
                        return next;
                     }

                     return null;
                  }, k -> usableStock(snapshot, k, reservedStock), 8192L);
                  if (chain == null) {
                     return FastCraftingPlanner.ChainLookup.NONE;
                  } else {
                     for (AEKey link : chain.links()) {
                        if (itemUnitKeys.contains(link) || linkOwner.containsKey(link)) {
                           return FastCraftingPlanner.ChainLookup.conflict(var20);
                        }
                     }

                     registry.put(chain.carrier(), chain);

                     for (AEKey linkx : chain.links()) {
                        linkOwner.put(linkx, chain);
                     }

                     builder.stock(full, chain.totalUses());
                     return new FastCraftingPlanner.ChainLookup(chain, null);
                  }
               }
            }
         }
      } else {
         return FastCraftingPlanner.ChainLookup.NONE;
      }
   }

   private static long usableStock(ChildCraftingSimulationState snapshot, AEKey key, @Nullable ReservedStockCraftingRequester reservedStock) {
      long actual = Math.max(0L, snapshot.extract(key, Long.MAX_VALUE, Actionable.SIMULATE));
      if (reservedStock == null) {
         return actual;
      } else if (reservedStock.groupsSecondaryVariants(key)) {
         LinkedHashMap<AEKey, Long> group = new LinkedHashMap<>();
         group.put(key, Long.valueOf(actual));

         for (AEKey variant : snapshot.findFuzzyTemplates(key)) {
            if (key.dropSecondary().equals(variant.dropSecondary())) {
               long amount = Math.max(0L, snapshot.extract(variant, Long.MAX_VALUE, Actionable.SIMULATE));
               group.put(variant, Long.valueOf(amount));
            }
         }

         return Math.max(0L, Math.min(actual, reservedStock.usablePreexistingStock(key, actual, Map.copyOf(group))));
      } else {
         return Math.max(0L, Math.min(actual, reservedStock.usablePreexistingStock(key, actual)));
      }
   }

   private static AEItemKey fullestAnchor(
      IInput in, ICraftingService craftingService, ChildCraftingSimulationState snapshot, Level level, AEItemKey template, Item item
   ) {
      if (craftingService.getFuzzyCraftable(template, k -> in.isValid(k, level)) instanceof AEItemKey craftable && craftable.getItem() == item) {
         return craftable;
      }

      AEItemKey anchor = template;
      long best = downwardLength(in, template, item);

      for (AEKey variant : snapshot.findFuzzyTemplates(template)) {
         if (variant instanceof AEItemKey) {
            AEItemKey ik = (AEItemKey)variant;
            if (ik.getItem() == item && !ik.equals(anchor)) {
               long len = downwardLength(in, ik, item);
               if (len > best) {
                  best = len;
                  anchor = ik;
               }
            }
         }
      }

      return anchor;
   }

   private static long downwardLength(IInput in, AEItemKey from, Item item) {
      long len = 1L;
      Set<AEKey> guard = new HashSet<>();
      AEKey cur = from;
      guard.add(from);

      while (in.getRemainingKey(cur) instanceof AEItemKey next && next.getItem() == item && guard.add(next)) {
         cur = next;
         if (++len > 8192L) {
            break;
         }
      }

      return len;
   }

   private static void emitBestCombinations(
      CraftGraph.Builder<AEKey> builder,
      Set<AEKey> seen,
      Deque<AEKey> queue,
      AEKey key,
      long outputAmount,
      List<CraftOutput<AEKey>> byproducts,
      List<List<FastCraftingPlanner.SlotChoice>> slotOptions,
      IPatternDetails source
   ) {
      for (List<FastCraftingPlanner.SlotChoice> selectedSlots : BoundedCombinations.bestFirst(slotOptions, 64)) {
         List<CraftInput<AEKey>> coreInputs = new ArrayList<>();

         for (FastCraftingPlanner.SlotChoice selected : selectedSlots) {
            coreInputs.addAll(selected.inputs());
         }

         List<CraftOutput<AEKey>> combo = byproducts;

         for (CraftInput<AEKey> opt : coreInputs) {
            if (seen.add(opt.key())) {
               queue.add(opt.key());
            }

            if (opt.remainder() != null) {
               if (combo == byproducts) {
                  combo = new ArrayList<>(byproducts);
               }

               combo.add(CraftOutput.of(opt.remainder(), opt.amount()));
               if (seen.add(opt.remainder())) {
                  queue.add(opt.remainder());
               }
            }
         }

         builder.pattern(new CraftPattern<>(key, outputAmount, coreInputs, combo, source));
      }
   }

   private static List<FastCraftingPlanner.SlotChoice> expandSlotChoices(List<CraftInput<AEKey>> options, long multiplier, Map<AEKey, Long> availability) {
      long units = Math.max(1L, multiplier);
      if (options.size() == 1) {
         return List.of(new FastCraftingPlanner.SlotChoice(List.of(scaleInput(options.get(0), units))));
      } else {
         int limit = 64;
         List<long[]> allocations = new ArrayList<>(limit);

         for (int i = 0; i < options.size() && allocations.size() < limit; i++) {
            long[] counts = new long[options.size()];
            counts[i] = units;
            allocations.add(counts);
         }

         enumerateMixedAllocations(options.size(), 0, units, new long[options.size()], allocations, limit);
         List<FastCraftingPlanner.SlotChoice> choices = new ArrayList<>(allocations.size());

         for (long[] allocation : allocations) {
            List<CraftInput<AEKey>> inputs = new ArrayList<>();

            for (int i = 0; i < allocation.length; i++) {
               if (allocation[i] > 0L) {
                  inputs.add(scaleInput(options.get(i), allocation[i]));
               }
            }

            choices.add(new FastCraftingPlanner.SlotChoice(inputs));
         }

         choices.sort(Comparator.<FastCraftingPlanner.SlotChoice>comparingLong(choice -> immediatelySupportedFirings(choice, availability)).reversed());
         return choices;
      }
   }

   private static void enumerateMixedAllocations(int optionCount, int index, long remaining, long[] counts, List<long[]> out, int limit) {
      if (out.size() < limit) {
         if (index == optionCount - 1) {
            counts[index] = remaining;
            int nonZero = 0;

            for (long count : counts) {
               if (count > 0L) {
                  nonZero++;
               }
            }

            if (nonZero > 1) {
               out.add(Arrays.copyOf(counts, counts.length));
            }
         } else {
            for (long countx = remaining; countx >= 0L && out.size() < limit; countx--) {
               counts[index] = countx;
               enumerateMixedAllocations(optionCount, index + 1, remaining - countx, counts, out, limit);
               if (countx == 0L) {
                  break;
               }
            }

            counts[index] = 0L;
         }
      }
   }

   private static CraftInput<AEKey> scaleInput(CraftInput<AEKey> input, long units) {
      return new CraftInput<>(input.key(), Sat.mul(input.amount(), units), input.returned(), input.uses(), input.remainder(), input.reusableStockSource());
   }

   private static long immediatelySupportedFirings(FastCraftingPlanner.SlotChoice choice, Map<AEKey, Long> availability) {
      long supported = 2305843009213693951L;

      for (CraftInput<AEKey> input : choice.inputs()) {
         supported = Math.min(supported, input.firingsFrom(availability.getOrDefault(input.key(), 0L)));
      }

      return supported;
   }

   private static CraftingPlan toAe2Plan(
      AEKey output,
      long amount,
      CraftPlan<AEKey> plan,
      boolean multiplePaths,
      boolean simulation,
      Map<AEKey, DurabilityChain<AEKey>> durability,
      Map<AEKey, Set<IPatternDetails>> patternSources,
      Set<AEKey> emittable,
      ChildCraftingSimulationState snapshot,
      @Nullable ReservedStockCraftingRequester reservedStock
   ) {
      Map<IPatternDetails, Long> patternTimes = new HashMap<>();

      for (Entry<CraftPattern<AEKey>, Long> e : plan.firings().entrySet()) {
         patternTimes.merge((IPatternDetails)e.getKey().source(), e.getValue(), Sat::add);
      }

      KeyCounter usedItems = new KeyCounter();
      KeyCounter emittedItems = new KeyCounter();

      for (Entry<AEKey, Long> e : plan.usedStock().entrySet()) {
         if (emittable.contains(e.getKey())) {
            emittedItems.add(e.getKey(), e.getValue());
         } else {
            DurabilityChain<AEKey> chain = durability.get(e.getKey());
            if (chain == null) {
               usedItems.add(e.getKey(), e.getValue());
            } else {
               chain.chargeFromStock(e.getValue(), usedItems::add);
            }
         }
      }

      chargeAvailableFuzzyStock(plan, usedItems, snapshot, reservedStock);
      KeyCounter missingItems = new KeyCounter();

      for (Entry<AEKey, Long> ex : plan.missing().entrySet()) {
         if (emittable.contains(ex.getKey())) {
            emittedItems.add(ex.getKey(), ex.getValue());
         } else {
            DurabilityChain<AEKey> chain = durability.get(ex.getKey());
            if (chain == null) {
               missingItems.add(ex.getKey(), ex.getValue());
            } else {
               missingItems.add(chain.carrier(), Sat.ceilDiv(ex.getValue(), chain.n()));
            }
         }
      }

      long bytes = computeBytes(plan, durability, patternSources);
      return new CraftingPlan(new GenericStack(output, amount), bytes, simulation, multiplePaths, usedItems, emittedItems, missingItems, patternTimes);
   }

   private static void chargeAvailableFuzzyStock(
      CraftPlan<AEKey> plan, KeyCounter usedItems, ChildCraftingSimulationState snapshot, @Nullable ReservedStockCraftingRequester reservedStock
   ) {
      Map<IPatternDetails, Long> sourceFirings = new HashMap<>();

      for (Entry<CraftPattern<AEKey>, Long> entry : plan.firings().entrySet()) {
         sourceFirings.merge((IPatternDetails)entry.getKey().source(), entry.getValue(), Sat::add);
      }

      for (Entry<IPatternDetails, Long> sourceEntry : sourceFirings.entrySet()) {
         long times = sourceEntry.getValue();

         for (IInput slot : sourceEntry.getKey().getInputs()) {
            GenericStack[] possible = slot.getPossibleInputs();
            if (possible.length > 1) {
               long remainingUnits = Sat.mul(times, Math.max(1L, slot.getMultiplier()));

               for (GenericStack option : possible) {
                  long unitAmount = Math.max(1L, option.amount());
                  remainingUnits = Math.max(0L, remainingUnits - usedItems.get(option.what()) / unitAmount);
               }

               for (GenericStack option : possible) {
                  if (remainingUnits <= 0L) {
                     break;
                  }

                  long unitAmount = Math.max(1L, option.amount());
                  long alreadyUsed = usedItems.get(option.what());
                  long availableStock = usableStock(snapshot, option.what(), reservedStock);
                  long unusedStock = Math.max(0L, availableStock - alreadyUsed);
                  long extraUnits = Math.min(remainingUnits, unusedStock / unitAmount);
                  if (extraUnits > 0L) {
                     usedItems.add(option.what(), Sat.mul(extraUnits, unitAmount));
                     remainingUnits -= extraUnits;
                  }
               }
            }
         }
      }
   }

   private static long computeBytes(CraftPlan<AEKey> plan, Map<AEKey, DurabilityChain<AEKey>> durability, Map<AEKey, Set<IPatternDetails>> patternSources) {
      double bytes = 0.0;

      for (Entry<AEKey, Long> e : plan.grossDemand().entrySet()) {
         int amountPerByte = Math.max(1, e.getKey().getType().getAmountPerByte());
         bytes += (double)e.getValue().longValue() / (double)amountPerByte * 8.0;
      }

      for (long times : plan.firings().values()) {
         bytes += (double)times;
      }

      for (Entry<CraftPattern<AEKey>, Long> entry : plan.firings().entrySet()) {
         long times = entry.getValue();

         for (CraftInput<AEKey> input : entry.getKey().inputs()) {
            if (input.returned() || input.remainder() != null || durability.containsKey(input.key())) {
               bytes += (double)Sat.mul(times, input.amount());
            }
         }
      }

      long nodeCount = (long)plan.grossDemand().size() - fuzzyNodeReduction(plan);
      nodeCount = Sat.add(nodeCount, unusedAlternativeNodeCount(plan, patternSources));
      bytes += 8.0 * (double)Math.max(0L, nodeCount);
      return (long)Math.ceil(bytes);
   }

   private static long unusedAlternativeNodeCount(CraftPlan<AEKey> plan, Map<AEKey, Set<IPatternDetails>> patternSources) {
      Map<AEKey, Set<IPatternDetails>> selectedByOutput = new HashMap<>();

      for (Entry<CraftPattern<AEKey>, Long> entry : plan.firings().entrySet()) {
         if (entry.getValue() > 0L) {
            CraftPattern<AEKey> pattern = entry.getKey();
            selectedByOutput.computeIfAbsent(pattern.output(), ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
               .add((IPatternDetails)pattern.source());
         }
      }

      long extra = 0L;

      for (Entry<AEKey, Set<IPatternDetails>> entryx : selectedByOutput.entrySet()) {
         Set<IPatternDetails> selected = entryx.getValue();

         for (IPatternDetails source : patternSources.getOrDefault(entryx.getKey(), Set.of())) {
            if (!selected.contains(source)) {
               extra = Sat.add(extra, (long)source.getInputs().length);
            }
         }
      }

      return extra;
   }

   private static long fuzzyNodeReduction(CraftPlan<AEKey> plan) {
      long reduction = 0L;
      Set<IPatternDetails> visited = Collections.newSetFromMap(new IdentityHashMap<>());

      for (CraftPattern<AEKey> pattern : plan.firings().keySet()) {
         IPatternDetails source = (IPatternDetails)pattern.source();
         if (visited.add(source)) {
            for (IInput slot : source.getInputs()) {
               Set<AEKey> present = new HashSet<>();

               for (GenericStack possible : slot.getPossibleInputs()) {
                  if (plan.grossDemand().containsKey(possible.what())) {
                     present.add(possible.what());
                  }
               }

               reduction += Math.max(0L, (long)present.size() - 1L);
            }
         }
      }

      return reduction;
   }

   private static record ChainLookup(@Nullable DurabilityChain<AEKey> chain, @Nullable Item conflictItem) {
      static final FastCraftingPlanner.ChainLookup NONE = new FastCraftingPlanner.ChainLookup(null, null);

      static FastCraftingPlanner.ChainLookup conflict(Item item) {
         return new FastCraftingPlanner.ChainLookup(null, item);
      }

      boolean conflict() {
         return this.conflictItem != null;
      }
   }

   public static record FastAttempt(
      boolean handled, @Nullable CraftingPlan plan, @Nullable CraftingPlan simulationFallback, Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock
   ) {
      public FastAttempt(
         boolean handled, @Nullable CraftingPlan plan, @Nullable CraftingPlan simulationFallback, Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock
      ) {
         usedReusableStock = Map.copyOf(usedReusableStock);
         this.handled = handled;
         this.plan = plan;
         this.simulationFallback = simulationFallback;
         this.usedReusableStock = usedReusableStock;
      }

      static FastCraftingPlanner.FastAttempt decline() {
         return new FastCraftingPlanner.FastAttempt(false, null, null, Map.of());
      }

      static FastCraftingPlanner.FastAttempt handled(CraftingPlan plan, Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
         return new FastCraftingPlanner.FastAttempt(true, plan, null, usedReusableStock);
      }

      static FastCraftingPlanner.FastAttempt infeasible(CraftingPlan simulationFallback, Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
         return new FastCraftingPlanner.FastAttempt(true, null, simulationFallback, usedReusableStock);
      }
   }

   private static final class GraphBuild {
      private final CraftGraph.Builder<AEKey> builder = CraftGraph.builder();
      private final boolean[] multiplePaths = new boolean[]{false};
      private final Map<AEKey, DurabilityChain<AEKey>> durability = new HashMap<>();
      private final Map<AEKey, Set<IPatternDetails>> patternSources = new HashMap<>();
      private final Set<AEKey> emittable = new HashSet<>();
   }

   private static record SlotChoice(List<CraftInput<AEKey>> inputs) {
      private SlotChoice(List<CraftInput<AEKey>> inputs) {
         inputs = List.copyOf(inputs);
         this.inputs = inputs;
      }
   }
}
