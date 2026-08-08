package com.moakiee.thunderbolt.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FastWildcardMatcher {
   private static final FastWildcardMatcher EMPTY = new FastWildcardMatcher(Set.of(), Set.of(), List.of(), false);
   private final Set<String> exact;
   private final Set<String> namespaceWildcards;
   private final List<FastWildcardMatcher.Glob> globs;
   private final boolean matchesEverything;

   private FastWildcardMatcher(Set<String> exact, Set<String> namespaceWildcards, List<FastWildcardMatcher.Glob> globs, boolean matchesEverything) {
      this.exact = exact;
      this.namespaceWildcards = namespaceWildcards;
      this.globs = globs;
      this.matchesEverything = matchesEverything;
   }

   public static FastWildcardMatcher empty() {
      return EMPTY;
   }

   public static FastWildcardMatcher compile(Collection<? extends String> patterns) {
      if (patterns != null && !patterns.isEmpty()) {
         HashSet<String> exact = new HashSet<>();
         HashSet<String> namespaces = new HashSet<>();
         ArrayList<FastWildcardMatcher.Glob> globs = new ArrayList<>();
         boolean all = false;

         for (String raw : patterns) {
            if (raw != null && isValidPattern(raw)) {
               String pattern = normalize(raw);
               if (!pattern.equals("*") && !pattern.equals("*:*")) {
                  int colon = pattern.indexOf(58);
                  if (colon > 0 && pattern.substring(colon + 1).equals("*") && !containsWildcard(pattern.substring(0, colon))) {
                     namespaces.add(pattern.substring(0, colon));
                  } else if (!containsWildcard(pattern)) {
                     exact.add(pattern);
                  } else {
                     globs.add(new FastWildcardMatcher.Glob(pattern));
                  }
               } else {
                  all = true;
               }
            }
         }

         return !all && exact.isEmpty() && namespaces.isEmpty() && globs.isEmpty()
            ? EMPTY
            : new FastWildcardMatcher(Set.copyOf(exact), Set.copyOf(namespaces), List.copyOf(globs), all);
      } else {
         return EMPTY;
      }
   }

   public boolean matches(String candidate) {
      if (candidate != null && !candidate.isBlank()) {
         String normalized = normalize(candidate);
         if (!this.matchesEverything && !this.exact.contains(normalized)) {
            int colon = normalized.indexOf(58);
            if (colon > 0 && this.namespaceWildcards.contains(normalized.substring(0, colon))) {
               return true;
            } else {
               for (FastWildcardMatcher.Glob glob : this.globs) {
                  if (glob.matches(normalized)) {
                     return true;
                  }
               }

               return false;
            }
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   public static boolean isValidPattern(Object value) {
      if (value instanceof String raw) {
         String pattern = normalize(raw);
         if (pattern.equals("*")) {
            return true;
         } else {
            int colon = pattern.indexOf(58);
            if (colon > 0 && colon != pattern.length() - 1 && colon == pattern.lastIndexOf(58)) {
               for (int i = 0; i < pattern.length(); i++) {
                  char c = pattern.charAt(i);
                  if (c != ':' && c != '*' && c != '?' && c != '.' && c != '_' && c != '-' && (c != '/' || i <= colon)) {
                     boolean lowercaseLetter = c >= 'a' && c <= 'z';
                     boolean digit = c >= '0' && c <= '9';
                     if (!lowercaseLetter && !digit) {
                        return false;
                     }
                  }
               }

               return true;
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private static boolean containsWildcard(String value) {
      return value.indexOf(42) >= 0 || value.indexOf(63) >= 0;
   }

   private static String normalize(String value) {
      return value.strip().toLowerCase(Locale.ROOT);
   }

   private static record Glob(String pattern) {
      private boolean matches(String value) {
         int patternIndex = 0;
         int valueIndex = 0;
         int lastStar = -1;
         int retryValueIndex = -1;

         while (valueIndex < value.length()) {
            if (patternIndex < this.pattern.length()) {
               char token = this.pattern.charAt(patternIndex);
               if (token == '?' || token == value.charAt(valueIndex)) {
                  patternIndex++;
                  valueIndex++;
                  continue;
               }

               if (token == '*') {
                  lastStar = patternIndex++;
                  retryValueIndex = valueIndex;
                  continue;
               }
            }

            if (lastStar < 0) {
               return false;
            }

            patternIndex = lastStar + 1;
            valueIndex = ++retryValueIndex;
         }

         while (patternIndex < this.pattern.length() && this.pattern.charAt(patternIndex) == '*') {
            patternIndex++;
         }

         return patternIndex == this.pattern.length();
      }
   }
}
