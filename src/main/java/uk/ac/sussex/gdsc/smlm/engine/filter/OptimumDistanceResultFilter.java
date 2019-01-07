/*-
 * #%L
 * Genome Damage and Stability Centre SMLM ImageJ Plugins
 *
 * Software for single molecule localisation microscopy (SMLM)
 * %%
 * Copyright (C) 2011 - 2019 Alex Herbert
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package uk.ac.sussex.gdsc.smlm.engine.filter;

import uk.ac.sussex.gdsc.smlm.fitting.FitResult;
import uk.ac.sussex.gdsc.smlm.results.PeakResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Filter the results using the distance to a set of coordinates. Positions must be within the
 * distance threshold. Fitted peaks are selected first and in the event of multiple results the peak
 * with the strongest signal is selected. Otherwise failed starting positions are selected and in
 * the event of multiple results the closest position will be chosen.
 *
 * @deprecated Filtering of the results is no longer supported
 */
@Deprecated
public class OptimumDistanceResultFilter extends ResultFilter {
  private final FitResult[] bestFitResults;
  private final int[] bestIndices;
  private final float[] bestD2;
  private final float[] bestSignal;
  private final PeakResult[] bestPeakResults;

  /**
   * Instantiates a new optimum distance result filter.
   *
   * @param filter the filter
   * @param d the d
   * @param nMaxima the n maxima
   */
  public OptimumDistanceResultFilter(List<float[]> filter, float d, int nMaxima) {
    super(filter, d, nMaxima);
    bestFitResults = new FitResult[filter.size()];
    bestIndices = new int[filter.size()];
    bestD2 = new float[filter.size()];
    Arrays.fill(bestD2, d2);
    bestSignal = new float[filter.size()];
    bestPeakResults = new PeakResult[filter.size()];
  }

  /** {@inheritDoc} */
  @Override
  public void filter(FitResult fitResult, int maxIndex, PeakResult... results) {
    for (final PeakResult r : results) {
      if (r == null) {
        continue;
      }
      for (int i = 0; i < filter.size(); i++) {
        final float[] coord = filter.get(i);
        final float dx = r.getXPosition() - coord[0];
        final float dy = r.getYPosition() - coord[1];
        // Only check if within the distance threshold
        if (dx * dx + dy * dy < d2) {
          // Then filter by signal strength
          final float s = r.getIntensity();
          if (s < bestSignal[i]) {
            continue;
          }
          bestFitResults[i] = fitResult;
          bestIndices[i] = maxIndex;
          bestSignal[i] = s;
          bestPeakResults[i] = r;
        }
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public void filter(FitResult fitResult, int maxIndex, float x, float y) {
    for (int i = 0; i < filter.size(); i++) {
      // Skip if there is a peak result for this target coordinate
      if (bestPeakResults[i] != null) {
        continue;
      }
      final float[] coord = filter.get(i);
      final float dx = x - coord[0];
      final float dy = y - coord[1];
      final float dd = dx * dx + dy * dy;
      // Check if this starting position is the closest
      if (dd < bestD2[i]) {
        bestFitResults[i] = fitResult;
        bestIndices[i] = maxIndex;
        bestD2[i] = dd;
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public void finalise() {
    // Note that there could be the same result allocated to two target positions
    // so find the unique results
    final int[] uniqueIndices = new int[bestIndices.length];
    int unique = 0;
    for (int i = 0; i < bestIndices.length; i++) {
      if (bestFitResults[i] == null) {
        continue;
      }
      boolean found = false;
      for (int j = unique; j-- > 0;) {
        if (bestIndices[uniqueIndices[j]] == bestIndices[i]) {
          found = true;
          break;
        }
      }
      if (!found) {
        uniqueIndices[unique++] = i;
      }
    }

    // The fit results and the indices must match so preserve the same order
    filteredCount = unique;
    filteredFitResults = new FitResult[unique];
    filteredIndices = new int[unique];
    for (int i = 0; i < unique; i++) {
      filteredFitResults[i] = bestFitResults[uniqueIndices[i]];
      filteredIndices[i] = bestIndices[uniqueIndices[i]];
    }

    // The peak results can be in any order so use a set to find the unique results
    if (unique > 0) {
      final TreeSet<PeakResult> set = new TreeSet<>();
      for (final PeakResult r : bestPeakResults) {
        if (r != null) {
          set.add(r);
        }
      }

      peakResults = new ArrayList<>(set.size());
      peakResults.addAll(set);
    } else {
      peakResults = new ArrayList<>();
    }
  }
}
