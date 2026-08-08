package com.moakiee.thunderbolt.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import java.util.Map;

public interface PatternFiringExpander {
   Map<IPatternDetails, Long> expandPatternFirings(long var1);
}
