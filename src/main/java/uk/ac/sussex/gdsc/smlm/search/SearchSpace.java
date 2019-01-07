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

package uk.ac.sussex.gdsc.smlm.search;

import uk.ac.sussex.gdsc.core.logging.TrackProgress;

import org.apache.commons.math3.random.HaltonSequenceGenerator;
import org.apache.commons.math3.random.RandomVectorGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Search a range of parameter space using a window divided into increments.
 */
public class SearchSpace {
  /** Used to ignore rounding values. */
  private static NonRoundingDimension nonRoundingDimension = new NonRoundingDimension();

  private int iteration;
  private double[][] searchSpace;
  private double[][] seed;

  private double[][] scoredSearchSpace;
  private final ArrayList<String> scoredSearchSpaceHash = new ArrayList<>();
  private final HashSet<String> coveredSpace = new HashSet<>();
  private final StringBuilder sb = new StringBuilder();

  // This introduces a dependency on another uk.ac.sussex.gdsc.smlm package
  private TrackProgress tracker;

  /**
   * The refinement mode for the range search.
   *
   * <p>At each stage the refinement search enumerates a range for each dimension using the current
   * interval. The optimum from this search space can be refined before the dimension range is
   * reduced.
   */
  public enum RefinementMode {
    /**
     * No refinement before reducing the range.
     */
    NONE,
    /**
     * Enumerate each dimension in turn up to the next increment in either direction. This is
     * iterated until the optimum converges and then the range is reduced.
     */
    SINGLE_DIMENSION,
    /**
     * Enumerate all dimensions torgther up to the next increment in either direction. This is
     * performed once and then the range is reduced.
     */
    MULTI_DIMENSION
  }

  private RefinementMode searchMode = RefinementMode.SINGLE_DIMENSION;

  /**
   * Clone the search dimensions.
   *
   * @param dimensions the search dimensions
   * @return the clone
   */
  public static SearchDimension[] clone(SearchDimension[] dimensions) {
    if (dimensions == null) {
      return null;
    }
    final SearchDimension[] dimensions2 = new SearchDimension[dimensions.length];
    for (int i = 0; i < dimensions.length; i++) {
      dimensions2[i] = dimensions[i].clone();
    }
    return dimensions2;
  }

  /**
   * Search the configured search space until convergence of the optimum.
   *
   * <p>At each iteration the search will enumerate all points in the configured search space and
   * find the optimum. If at the bounds of the range in any dimension then the range is re-centred
   * and the process repeated. During repeats only those points that have not yet been evaluated
   * will be passed to the score function. If not at the bounds then the range is re-centred and the
   * width of the range reduced.
   *
   * <p>If a seed population was provided then the first step is to re-centre to the optimum of the
   * seed population and the range refined/reduced as per the refinement mode parameter.
   *
   * <p>The process iterates until the range cannot be reduced in size, or convergence is reached.
   * The input dimensions are modified during the search. Use the clone(SearchDimension[]) method to
   * create a copy.
   *
   * @param <T> the type of comparable score
   * @param dimensions the dimensions
   * @param scoreFunction the score function
   * @param checker the checker
   * @param refinementMode The refinement mode for the range search.
   * @return The optimum (or null)
   */
  public <T extends Comparable<T>> SearchResult<T> search(SearchDimension[] dimensions,
      ScoreFunction<T> scoreFunction, ConvergenceChecker<T> checker,
      RefinementMode refinementMode) {
    if (dimensions == null || dimensions.length == 0) {
      throw new IllegalArgumentException("Dimensions must not be empty");
    }
    if (scoreFunction == null) {
      throw new IllegalArgumentException("Score function is null");
    }

    reset();

    // Find the best individual
    SearchResult<T> current = findSeedOptimum(dimensions, scoreFunction);
    if (current == null) {
      current = findOptimum(dimensions, scoreFunction, null);
    }
    SearchResult<T> previous = null;

    boolean converged = false;
    while (!converged) {
      iteration++;
      previous = current;

      if (!updateSearchSpace(dimensions, current, refinementMode)) {
        break;
      }

      // Find the optimum and check convergence
      current = findOptimum(dimensions, scoreFunction, current);
      if (current == null) {
        break;
      }
      if (checker != null) {
        converged = checker.converged(previous, current);
      }
    }
    if (tracker != null) {
      tracker.status("Converged [%d]", iteration);
    }

    // Free memory
    scoredSearchSpace = null;
    scoredSearchSpaceHash.clear();
    coveredSpace.clear();

    return current;
  }

  private void reset() {
    iteration = 0;
    sb.setLength(0);
    searchSpace = null;
    scoredSearchSpace = null;
    scoredSearchSpaceHash.clear();
    coveredSpace.clear();
    searchMode = RefinementMode.NONE;
  }

  /**
   * Find the optimum in the configured search space.
   *
   * @param <T> the type of comparable score
   * @param dimensions the dimensions
   * @param scoreFunction the score function
   * @return The optimum (or null)
   */
  public <T extends Comparable<T>> SearchResult<T> findOptimum(SearchDimension[] dimensions,
      ScoreFunction<T> scoreFunction) {
    if (dimensions == null || dimensions.length == 0) {
      throw new IllegalArgumentException("Dimensions must not be empty");
    }
    if (scoreFunction == null) {
      throw new IllegalArgumentException("Score function is null");
    }

    reset();

    // Find the best individual
    final SearchResult<T> current = findOptimum(dimensions, scoreFunction, null);

    return current;
  }

  /**
   * Find the optimum in the seed population.
   *
   * @param <T> the type of comparable score
   * @param scoreFunction the score function
   * @return the new optimum
   */
  private <T extends Comparable<T>> SearchResult<T> findSeedOptimum(SearchDimension[] dimensions,
      ScoreFunction<T> scoreFunction) {
    if (!seedToSearchSpace(dimensions)) {
      return null;
    }

    start("Find Seed Optimum");

    scoredSearchSpace = searchSpace;
    scoredSearchSpaceHash.clear();

    final SearchResult<T> optimum = scoreFunction.findOptimum(scoredSearchSpace);

    // Re-centre on the seed
    if (optimum != null) {
      final double[] p = optimum.getPoint();
      for (int i = 0; i < dimensions.length; i++) {
        dimensions[i].setCentre(p[i]);
        // In-case the seed was not on the min interval grid
        // Should not happen as we now map the seed to the min interval
        // p[i] = dimensions[i].getCentre();
      }
    }

    end();
    return optimum;
  }

  private boolean seedToSearchSpace(Dimension[] dimensions) {
    if (seed == null) {
      return false;
    }

    // Get the limits of active dimensions
    final int[] indices = new int[dimensions.length];
    int size = 0;
    final double[] min = new double[dimensions.length];
    final double[] max = new double[dimensions.length];
    for (int i = 0; i < dimensions.length; i++) {
      if (dimensions[i].isActive()) {
        min[size] = dimensions[i].getMin();
        max[size] = dimensions[i].getMax();
        indices[size++] = i;
        if (!dimensions[i].canRound()) {
          dimensions[i] = nonRoundingDimension;
        }
      }
    }

    for (final double[] p : seed) {
      // Check the seed has the correct dimensions
      if (p == null || p.length != indices.length) {
        return false;
      }
      // Check the data is within the limits for active dimensions
      for (int j = 0; j < size; j++) {
        // Round to dimension interval
        final int k = indices[j];
        final double value = dimensions[k].round(p[k]);
        if (value < min[j]) {
          return false;
        } else if (value > max[j]) {
          return false;
        }
        p[k] = value;
      }
    }

    searchSpace = seed;
    return true;

  }

  /**
   * Find the optimum. Create the search space using the current dimensions. Score any new point
   * that has not previously been scored. Compare the result with the current optimum and return the
   * best.
   *
   * @param <T> the type of comparable score
   * @param scoreFunction the score function
   * @param current the current optimum
   * @return the new optimum
   */
  private <T extends Comparable<T>> SearchResult<T> findOptimum(SearchDimension[] dimensions,
      ScoreFunction<T> scoreFunction, SearchResult<T> current) {
    if (!createSearchSpace(dimensions, current)) {
      return null;
    }

    start("Find Optimum");

    scoredSearchSpace = searchSpace;
    scoredSearchSpaceHash.clear();

    if (!coveredSpace.isEmpty()) {
      // Check we do not recompute scores
      scoredSearchSpace = new double[searchSpace.length][];
      scoredSearchSpaceHash.ensureCapacity(searchSpace.length);
      int size = 0;
      for (int i = 0; i < searchSpace.length; i++) {
        final String hash = generateHashString(searchSpace[i]);
        if (!coveredSpace.contains(hash)) {
          scoredSearchSpace[size++] = searchSpace[i];
          scoredSearchSpaceHash.add(hash);
        }
      }
      if (size == 0) {
        end();
        // We have scored everything already so return the current best
        return current;
      }

      // System.out.printf("Scoring %d / %d\n", size, searchSpace.length);
      scoredSearchSpace = Arrays.copyOf(scoredSearchSpace, size);
    }

    final SearchResult<T> optimum = scoreFunction.findOptimum(scoredSearchSpace);
    if (optimum != null) {
      // System.out.printf("Optimum = %s\n", optimum.score);
      // if (current != null)
      // System.out.printf("Current = %s\n", current.score);
      // Replace if better
      if (optimum.compareTo(current) < 0) {
        current = optimum;
        // if (searchMode == REFINE)
        // System.out.printf("Refine improved = %s\n", current.score);
      }
    }

    end();
    return current;
  }

  /**
   * Generate hash string.
   *
   * @param values the values
   * @return the string
   */
  private String generateHashString(double[] values) {
    for (int i = 0; i < values.length; i++) {
      sb.append(values[i]);
    }
    final String hash = sb.toString();
    sb.setLength(0);
    return hash;
  }

  /**
   * Creates the search space.
   *
   * @param <T> the type of comparable score
   * @param current the current
   * @return true, if successful
   */
  private <T extends Comparable<T>> boolean createSearchSpace(SearchDimension[] dimensions,
      SearchResult<T> current) {
    start("Create Search Space");
    if (searchMode == RefinementMode.SINGLE_DIMENSION && current != null) {
      searchSpace = createRefineSpace(dimensions, current.getPoint());
    } else if (searchMode == RefinementMode.MULTI_DIMENSION && current != null) {
      searchSpace = createBoundedSearchSpace(dimensions, current.getPoint());
    } else {
      // Enumerate
      searchSpace = createSearchSpace(dimensions);
    }
    end();
    return searchSpace != null;
  }

  /**
   * Creates the refine space.
   *
   * @param dimensions the dimensions
   * @param point the point
   * @return the double[][]
   */
  public static double[][] createRefineSpace(SearchDimension[] dimensions, double[] point) {
    final double[][] dimensionValues = new double[dimensions.length][];
    for (int i = 0; i < dimensions.length; i++) {
      final double[] values = dimensions[i].values();
      // Find the range values either side of the point value
      final double v = point[i];
      double min = v;
      double max = v;
      for (int j = 0; j < values.length; j++) {
        if (values[j] < v) {
          min = values[j];
        } else if (values[j] > v) {
          max = values[j];
          break;
        }
      }
      // Create a sequence from min to max.
      // Add option to configure the number of steps?
      dimensionValues[i] = dimensions[i].enumerate(min, max, 100);
    }
    return createRefineSpace(dimensionValues, point);
  }

  /**
   * Creates the refine space.
   *
   * @param dimensionValues the dimension values
   * @param point the point
   * @return the double[][]
   */
  private static double[][] createRefineSpace(double[][] dimensionValues, double[] point) {
    // Get the values
    int combinations = 0;
    for (int i = 0; i < dimensionValues.length; i++) {
      combinations += dimensionValues[i].length;
    }

    // This will be a list of points enumerating the entire range
    // of the dimensions
    final double[][] searchSpace = new double[combinations][];

    // Start with the min value in each dimension
    int n = 0;
    // Enumerate each dimension
    for (int i = 0; i < dimensionValues.length; i++) {
      // Values to iterate over for this dimension
      final double[] v1 = dimensionValues[i];

      for (int k = 0; k < v1.length; k++) {
        // Create a new point with an updated value for this dimension
        final double[] v3 = point.clone();
        v3[i] = v1[k];
        searchSpace[n++] = v3;
      }
    }

    return searchSpace;
  }

  /**
   * Creates the bounded search space.
   *
   * @param dimensions the dimensions
   * @param point the point
   * @return the double[][]
   */
  public static double[][] createBoundedSearchSpace(SearchDimension[] dimensions, double[] point) {
    final double[][] dimensionValues = new double[dimensions.length][];
    for (int i = 0; i < dimensions.length; i++) {
      final double[] values = dimensions[i].values();
      // Find the range values either side of the point value
      final double v = point[i];
      double min = v;
      double max = v;
      for (int j = 0; j < values.length; j++) {
        if (values[j] < v) {
          min = values[j];
        } else if (values[j] > v) {
          max = values[j];
          break;
        }
      }
      // Create a sequence from min to max
      dimensionValues[i] = dimensions[i].enumerate(min, max);
    }
    return createSearchSpace(dimensionValues);
  }

  /**
   * Creates the search space.
   *
   * @param dimensions the dimensions
   * @return the double[][]
   */
  public static double[][] createSearchSpace(SearchDimension[] dimensions) {
    // Get the values
    final double[][] dimensionValues = new double[dimensions.length][];
    for (int i = 0; i < dimensions.length; i++) {
      dimensionValues[i] = dimensions[i].values();
    }
    // System.out.printf(" [%d] %.3f-%.3f", i, dimensions[i].getLower(), dimensions[i].getUpper());
    // System.out.println();
    return createSearchSpace(dimensionValues);
  }

  /**
   * Count the number of combinations if enumerating the search space.
   *
   * @param dimensions the dimensions
   * @return the number of combinations
   */
  public static long countCombinations(SearchDimension[] dimensions) {
    long combinations = 1;
    for (int i = 0; i < dimensions.length; i++) {
      combinations *= dimensions[i].values().length;
    }
    return combinations;
  }

  /**
   * Creates the search space.
   *
   * @param dimensionValues the dimension values
   * @return the double[][]
   */
  private static double[][] createSearchSpace(double[][] dimensionValues) {
    // Get the values
    int combinations = 1;
    final double[] v = new double[dimensionValues.length];
    for (int i = 0; i < dimensionValues.length; i++) {
      combinations *= dimensionValues[i].length;
      v[i] = dimensionValues[i][0];
    }

    // This will be a list of points enumerating the entire range
    // of the dimensions
    final double[][] searchSpace = new double[combinations][];

    // Start with the min value in each dimension
    searchSpace[0] = v;
    int n = 1;
    try {
      // Enumerate each dimension
      for (int i = 0; i < dimensionValues.length; i++) {
        // The number of current points
        final int size = n;

        // Values to iterate over for this dimension
        final double[] v1 = dimensionValues[i];

        // For all the current points
        for (int j = 0; j < size; j++) {
          // The point values
          final double[] v2 = searchSpace[j];

          // We started with the min value for the dimension
          // so go from index 1 upwards
          for (int k = 1; k < v1.length; k++) {
            // Create a new point with an updated value for this dimension
            final double[] v3 = v2.clone();
            v3[i] = v1[k];
            searchSpace[n++] = v3;
          }
        }
      }
    } catch (final ArrayIndexOutOfBoundsException ex) {
      // Return false
      ex.printStackTrace();
      n = -1;
    }

    return (n == combinations) ? searchSpace : null;
  }

  /**
   * Update search space. Re-centre the dimension on the current optimum. If the current optimum is
   * at the bounds of the dimensions then check if the bounds change when re-centring. If the bound
   * change then the search must continue with the current dimension ranges. If the bounds do not
   * change then the dimension ranges can be reduced.
   *
   * @param current the current optimum
   * @return true, if successful
   */
  private boolean updateSearchSpace(SearchDimension[] dimensions, SearchResult<?> current,
      RefinementMode refinementMode) {
    if (current == null) {
      return false;
    }

    start("Update search space");
    boolean changed = false;

    final double[] p = current.getPoint();
    // System.out.printf("[%d] Before:", iteration);
    // for (int i = 0; i < dimensions.length; i++)
    // {
    // if (dimensions[i].isActive())
    // System.out.printf(" %.2f-%.2f (%.2f)", dimensions[i].getLower(), dimensions[i].getUpper(),
    // p[i]);
    // }
    // System.out.println();

    if (searchMode != RefinementMode.NONE) {
      // During refinement we will not repeat the same point if the optimum moves
      coveredSpace.clear();

      // Move to the centre using the current optimum
      for (int i = 0; i < dimensions.length; i++) {
        if (p[i] != dimensions[i].getCentre()) {
          changed = true;
          dimensions[i].setCentre(p[i]);
        }
      }

      if (searchMode == RefinementMode.SINGLE_DIMENSION) {
        if (!changed) {
          // Switch back to enumeration of a smaller range if
          // the refinement has not changed the centre
          searchMode = RefinementMode.NONE;
          changed = reduceRange(dimensions);
        }
      } else if (searchMode == RefinementMode.MULTI_DIMENSION) {
        // We enumerated all the points around the optimum so
        // just switch back to enumeration of a smaller range
        searchMode = RefinementMode.NONE;
        changed = reduceRange(dimensions);
      }
    } else {
      // searchMode == ENUMERATION

      // Process each dimension
      for (int i = 0; i < dimensions.length; i++) {
        // Check if at the bounds of the dimension values
        final boolean atBounds = dimensions[i].isAtBounds(p[i]);

        // Only if at the bounds then move the centre.
        // (There is no point moving the bounds if not currently at the limits).
        if (atBounds) {
          // Get the current bounds
          final double[] values = dimensions[i].values();

          // Move to the centre using the current optimum
          dimensions[i].setCentre(p[i]);

          // Check if the bounds have changed due to the move
          if (changed(values, dimensions[i].values())) {
            changed = true;
          }
        }
      }

      if (changed) {
        // If changed then we stick to the current range.
        // Store all the scored search space so we do not recompute it.
        if (scoredSearchSpaceHash.isEmpty()) {
          // Compute the hash for each item scored
          for (int i = 0; i < scoredSearchSpace.length; i++) {
            coveredSpace.add(generateHashString(scoredSearchSpace[i]));
          }
        } else {
          // Hash was already computed
          for (int i = 0; i < scoredSearchSpaceHash.size(); i++) {
            coveredSpace.add(scoredSearchSpaceHash.get(i));
          }
          scoredSearchSpaceHash.clear();
        }
      } else {
        // No changes at the current range.
        // We can reduce/refine the search space.

        // Move to the centre using the current optimum
        for (int i = 0; i < dimensions.length; i++) {
          dimensions[i].setCentre(p[i]);
        }

        // Clear the memory of the space that has been searched
        // (as the search space is about to be altered so the values may not overlap).
        coveredSpace.clear();

        // Optionally switch to refinement
        if (refinementMode != RefinementMode.NONE) {
          searchMode = refinementMode;
          changed = true;
        } else {
          changed = reduceRange(dimensions);
        }
      }
    }

    end();

    // System.out.printf("[%d] After:", iteration);
    // for (int i = 0; i < dimensions.length; i++)
    // {
    // if (dimensions[i].isActive())
    // System.out.printf(" %.2f-%.2f (%.2f)", dimensions[i].getLower(), dimensions[i].getUpper(),
    // p[i]);
    // }
    // System.out.println();

    return changed;
  }

  /**
   * Reduce range.
   *
   * @param dimensions the dimensions
   * @return true, if successful
   */
  private static boolean reduceRange(SearchDimension[] dimensions) {
    // First check if the range can be reduced.
    // If not then return false as nothing can be changed.
    boolean reduced = false;
    for (int i = 0; i < dimensions.length; i++) {
      if (dimensions[i].canReduce()) {
        reduced = true;
        break;
      }
    }
    if (reduced) {
      // Reduce the range
      for (int i = 0; i < dimensions.length; i++) {
        dimensions[i].reduce();
      }
    }
    return reduced;
  }

  /**
   * Check if the two arrays are different.
   *
   * @param v1 the v 1
   * @param v2 the v 2
   * @return true, if different
   */
  private static boolean changed(double[] v1, double[] v2) {
    if (v1.length != v2.length) {
      return true;
    }
    for (int i = 0; i < v1.length; i++) {
      if (v1[i] != v2[i]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Gets the tracker.
   *
   * @return the tracker.
   */
  public TrackProgress getTracker() {
    return tracker;
  }

  /**
   * Set a tracker to allow the progress to be followed.
   *
   * @param tracker the tracker to set
   */
  public void setTracker(TrackProgress tracker) {
    this.tracker = tracker;
  }

  /**
   * Get the iteration. The iteration is increased each time the population grows as part of the
   * [grow, evaluate, select] cycle.
   *
   * @return the iteration
   */
  public int getIteration() {
    return iteration;
  }

  /**
   * Send a start message to the tracker.
   *
   * @param stage The stage that has started
   */
  private void start(String stage) {
    if (tracker != null) {
      tracker.status(stage + " [%d]", iteration);
      tracker.progress(0);
    }
  }

  /**
   * Send an end signal to the tracker.
   */
  private void end() {
    if (tracker != null) {
      tracker.progress(1);
    }
  }

  /**
   * Gets the current search space. This is the space that was most recently evaluated.
   *
   * @return the search space
   */
  public double[][] getSearchSpace() {
    return searchSpace;
  }

  /**
   * Search the configured search space until convergence of the optimum.
   *
   * <p>At each iteration the search will randomly sample points in the configured search space and
   * score them. The top fraction of the results is used to redefine the search space (with
   * padding). Note: The range cannot fall below the settings defined by the min increment and
   * nIntervals for each of the input dimensions.
   *
   * <p>The process iterates until convergence is reached. The input dimensions are used to define
   * the min/max and the current lower/upper bounds of the range. The dimensions are not modified.
   *
   * @param <T> the type of comparable score
   * @param dimensions the dimensions
   * @param scoreFunction the score function
   * @param checker the checker
   * @param samples the samples
   * @param fraction the fraction
   * @param padding the padding
   * @return The optimum (or null)
   */
  public <T extends Comparable<T>> SearchResult<T> enrichmentSearch(Dimension[] dimensions,
      FullScoreFunction<T> scoreFunction, ConvergenceChecker<T> checker, int samples,
      double fraction, double padding) {
    if (dimensions == null || dimensions.length == 0) {
      throw new IllegalArgumentException("Dimensions must not be empty");
    }
    if (scoreFunction == null) {
      throw new IllegalArgumentException("Score function is null");
    }
    if (fraction <= 0 || fraction >= 1) {
      throw new IllegalArgumentException("Fraction must be between 0 and 1");
    }
    if (samples < 1) {
      throw new IllegalArgumentException("Samples must be strictly positive");
    }
    if (padding < 0) {
      throw new IllegalArgumentException("Padding must be positive");
    }

    reset();

    // Keep the same generator throughout
    final HaltonSequenceGenerator[] generator = new HaltonSequenceGenerator[1];

    // Find the best individual
    SearchResult<T>[] scores = scoreSeed(dimensions, scoreFunction, samples, fraction, generator);
    if (scores == null) {
      scores = score(dimensions, scoreFunction, samples, fraction, generator);
    }
    if (scores == null) {
      return null;
    }

    SearchResult<T> current = scores[0];
    SearchResult<T> previous = null;

    boolean converged = false;
    while (!converged) {
      iteration++;
      previous = current;

      dimensions = updateSearchSpace(dimensions, current, scores, padding);
      if (dimensions == null) {
        break;
      }

      // Find the optimum and check convergence
      scores = score(dimensions, scoreFunction, samples, fraction, generator);
      if (scores == null) {
        break;
      }

      final SearchResult<T> optimum = scores[0];
      if (optimum.compareTo(current) < 0) {
        current = optimum;
      }
      if (checker != null) {
        converged = checker.converged(previous, current);
      }
    }
    if (tracker != null) {
      tracker.status("Converged [%d]", iteration);
    }

    return current;
  }

  /**
   * Score the seed population and return the top fraction.
   *
   * @param <T> the type of comparable score
   * @param dimensions the dimensions
   * @param scoreFunction the score function
   * @param samples the samples
   * @param fraction the fraction
   * @param generator the generator
   * @return the score results
   */
  private <T extends Comparable<T>> SearchResult<T>[] scoreSeed(Dimension[] dimensions,
      FullScoreFunction<T> scoreFunction, int samples, double fraction,
      HaltonSequenceGenerator[] generator) {
    if (!seedToSearchSpace(dimensions)) {
      return null;
    }

    // Pad search space with more samples
    final int remaining = samples - searchSpace.length;
    if (remaining > 0) {
      final double[][] sample = sample(dimensions, remaining, generator);
      final ArrayList<double[]> merged = new ArrayList<>(sample.length + searchSpace.length);
      merged.addAll(Arrays.asList(searchSpace));
      merged.addAll(Arrays.asList(sample));
      searchSpace = merged.toArray(new double[merged.size()][]);
    }

    // Score
    final SearchResult<T>[] scores = scoreFunction.score(searchSpace);

    // Get the top fraction
    final int size = (int) Math.ceil(samples * fraction);

    return scoreFunction.cut(scores, size);
  }

  /**
   * Score random samples from the search space and return the top fraction.
   *
   * @param <T> the type of comparable score
   * @param dimensions the dimensions
   * @param scoreFunction the score function
   * @param samples the samples
   * @param fraction the fraction
   * @param generator the generator
   * @return the score results
   */
  private <T extends Comparable<T>> SearchResult<T>[] score(Dimension[] dimensions,
      FullScoreFunction<T> scoreFunction, int samples, double fraction,
      HaltonSequenceGenerator[] generator) {
    searchSpace = sample(dimensions, samples, generator);

    // Score
    final SearchResult<T>[] scores = scoreFunction.score(searchSpace);

    // Get the top fraction
    final int size = (int) Math.ceil(scores.length * fraction);

    return scoreFunction.cut(scores, size);
  }

  /**
   * Sample uniformly from the given dimensions. The lower and upper bounds of active dimensions are
   * used to define the search space. The min interval of each dimension is respected.
   *
   * <p>If the input generator array is null, the first element is null, or the vector length is the
   * wrong size then a new HaltonSequenceGenerator is used. Input an array of length 1 to obtain the
   * default generator allowing calling the function again to generate a different search space.
   *
   * @param dimensions the dimensions
   * @param samples the samples
   * @param generator the generator
   * @return the sample
   */
  public static double[][] sample(Dimension[] dimensions, int samples,
      RandomVectorGenerator[] generator) {
    if (samples <= 0) {
      return null;
    }
    // Count the number of active dimensions
    final int[] indices = new int[dimensions.length];
    final Dimension[] dimensions2 = new Dimension[dimensions.length];
    int size = 0;
    final double[] centre = new double[dimensions.length];
    final double[] lower = new double[dimensions.length];
    final double[] range = new double[dimensions.length];
    boolean requireRounding = false;
    for (int i = 0; i < dimensions.length; i++) {
      centre[i] = dimensions[i].getCentre();
      if (dimensions[i].isActive()) {
        lower[size] = dimensions[i].getLower();
        range[size] = (dimensions[i].getUpper() - lower[size]);
        if (dimensions[i].canRound()) {
          requireRounding = true;
        } else {
          // Ignore rounding functionality
          dimensions[i] = nonRoundingDimension;
        }
        dimensions2[size] = dimensions[i];
        indices[size++] = i;
      }
    }

    if (requireRounding) {
      return sampleWithRounding(samples, generator, indices, dimensions2, size, centre, lower,
          range);
    }

    return sampleWithoutRounding(samples, generator, indices, size, centre, lower, range);
  }

  /**
   * Sample with rounding. The input dimensions are used purely for rounding. The limits of the
   * range have been precomputed.
   *
   * @param samples the samples
   * @param generator the generator
   * @param indices the indices
   * @param dimensions the dimensions
   * @param size the size
   * @param centre the centre
   * @param lower the lower
   * @param range the range
   * @return the sample
   */
  private static double[][] sampleWithRounding(int samples, RandomVectorGenerator[] generator,
      final int[] indices, final Dimension[] dimensions, int size, final double[] centre,
      final double[] lower, final double[] range) {
    final RandomVectorGenerator g = createGenerator(generator, size);

    // Generate random points
    final double[][] searchSpace = new double[samples][];
    for (int i = 0; i < samples; i++) {
      final double[] r = g.nextVector();
      final double[] p = centre.clone();
      for (int j = 0; j < size; j++) {
        // Ensure the min interval is respected
        p[indices[j]] = dimensions[j].round(lower[j] + r[j] * range[j]);
      }
      searchSpace[i] = p;
    }
    return searchSpace;
  }

  private static RandomVectorGenerator createGenerator(RandomVectorGenerator[] generator,
      int size) {
    // Create the generator
    if (generator == null) {
      generator = new RandomVectorGenerator[1];
    }
    RandomVectorGenerator g = generator[0];
    if (g == null || g.nextVector().length != size) {
      generator[0] = g = new HaltonSequenceGenerator(size);
    }
    return g;
  }

  /**
   * Sample uniformly from the given dimensions. The lower and upper bounds of active dimensions are
   * used to define the search space. The min interval of each dimension is not respected, i.e the
   * coordinates are not rounded.
   *
   * <p>If the input generator array is null, the first element is null, or the vector length is the
   * wrong size then a new HaltonSequenceGenerator is used. Input an array of length 1 to obtain the
   * default generator allowing calling the function again to generate a different search space.
   *
   * @param dimensions the dimensions
   * @param samples the samples
   * @param generator the generator
   * @return the sample
   */
  public static double[][] sampleWithoutRounding(Dimension[] dimensions, int samples,
      RandomVectorGenerator[] generator) {
    if (samples <= 0) {
      return null;
    }
    // Count the number of active dimensions
    final int[] indices = new int[dimensions.length];
    final Dimension[] dimensions2 = new Dimension[dimensions.length];
    int size = 0;
    final double[] centre = new double[dimensions.length];
    final double[] lower = new double[dimensions.length];
    final double[] range = new double[dimensions.length];
    for (int i = 0; i < dimensions.length; i++) {
      centre[i] = dimensions[i].getCentre();
      if (dimensions[i].isActive()) {
        lower[size] = dimensions[i].getLower();
        range[size] = (dimensions[i].getUpper() - lower[size]);
        dimensions2[size] = dimensions[i];
        indices[size++] = i;
      }
    }

    return sampleWithoutRounding(samples, generator, indices, size, centre, lower, range);
  }

  private static double[][] sampleWithoutRounding(int samples, RandomVectorGenerator[] generator,
      final int[] indices, int size, final double[] centre, final double[] lower,
      final double[] range) {
    final RandomVectorGenerator g = createGenerator(generator, size);

    // Generate random points
    final double[][] searchSpace = new double[samples][];
    for (int i = 0; i < samples; i++) {
      final double[] r = g.nextVector();
      final double[] p = centre.clone();
      for (int j = 0; j < size; j++) {
        // Ensure the min interval is respected
        p[indices[j]] = lower[j] + r[j] * range[j];
      }
      searchSpace[i] = p;
    }
    return searchSpace;
  }

  /**
   * Update search space.
   *
   * @param <T> the type of comparable score
   * @param dimensions the dimensions
   * @param current the current
   * @param scores the scores
   * @param padding the padding
   * @return the new dimensions
   */
  private static <T extends Comparable<T>> Dimension[] updateSearchSpace(Dimension[] dimensions,
      SearchResult<T> current, SearchResult<T>[] scores, double padding) {
    // Find the limits in the current scores
    final double[] lower = current.getPoint().clone();
    final double[] upper = current.getPoint().clone();
    for (int i = 0; i < scores.length; i++) {
      final double[] point = scores[i].getPoint();
      for (int j = 0; j < lower.length; j++) {
        if (lower[j] > point[j]) {
          lower[j] = point[j];
        }
        if (upper[j] < point[j]) {
          upper[j] = point[j];
        }
      }
    }

    // Pad the range
    if (padding > 0) {
      for (int j = 0; j < lower.length; j++) {
        final double range = padding * (upper[j] - lower[j]);
        lower[j] -= range;
        upper[j] += range;
      }
    }

    // Create new dimensions
    for (int j = 0; j < lower.length; j++) {
      dimensions[j] = dimensions[j].create(lower[j], upper[j]);
    }

    return dimensions;
  }

  /**
   * Seed the search with an initial population. This is used to determine the initial optimum and
   * centre the search in the search space.
   *
   * <p>Note that the parameters in the seed are mapped to the min interval of the configured
   * dimensions by rounding.
   *
   * @param seed the seed
   */
  public void seed(double[][] seed) {
    this.seed = seed;
  }
}
