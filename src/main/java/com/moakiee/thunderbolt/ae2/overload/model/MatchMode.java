package com.moakiee.thunderbolt.ae2.overload.model;

public enum MatchMode {
   STRICT,
   ID_ONLY;

   public boolean ignoresComponents() {
      return this == ID_ONLY;
   }
}
