package com.moakiee.thunderbolt;

import com.moakiee.thunderbolt.core.util.FastWildcardMatcher;
import java.util.Collection;

public final class CoreConfig {
   private static volatile int channelsPerController = 128;
   private static volatile CoreConfig.BatchCopyLimitRules batchCopyLimitRules = new CoreConfig.BatchCopyLimitRules(0L, FastWildcardMatcher.empty());

   public static int channelsPerController() {
      return channelsPerController;
   }

   public static void setChannelsPerController(int value) {
      channelsPerController = value;
   }

   public static CoreConfig.BatchCopyLimitRules batchCopyLimitRules() {
      return batchCopyLimitRules;
   }

   public static synchronized void setBatchCopyLimitedBlocks(Collection<? extends String> patterns) {
      CoreConfig.BatchCopyLimitRules current = batchCopyLimitRules;
      batchCopyLimitRules = new CoreConfig.BatchCopyLimitRules(current.version() + 1L, FastWildcardMatcher.compile(patterns));
   }

   private CoreConfig() {
   }

   public static record BatchCopyLimitRules(long version, FastWildcardMatcher matcher) {
      public static final int MATCHED_MAX_COPIES = 1024;

      public boolean matches(String blockId) {
         return this.matcher.matches(blockId);
      }

      public long limit(String blockId) {
         return this.matches(blockId) ? 1024L : Long.MAX_VALUE;
      }
   }
}
