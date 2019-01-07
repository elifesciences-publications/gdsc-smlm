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

package uk.ac.sussex.gdsc.smlm.ga;

import org.apache.commons.math3.random.RandomDataGenerator;

/**
 * Mutates the sequence by selecting random positions and random shifts.
 *
 * @param <T> the generic type
 */
public class SimpleMutator<T extends Comparable<T>> extends Randomiser implements Mutator<T> {
  /** The fraction of the sequence positions to mutate on average. */
  final double fraction;

  private boolean override;
  private double[] stepSize;
  private double[] lower;
  private double[] upper;
  private int[] positions;
  private int positionsCount;

  /**
   * Instantiates a new simple mutator.
   *
   * @param random the random data generator
   * @param fraction The fraction of the sequence positions to mutate on average
   */
  public SimpleMutator(RandomDataGenerator random, double fraction) {
    super(random);
    this.fraction = fraction;
  }

  /**
   * Override the mutation parameters that are obtained from the Chromosome interface. The arrays
   * must match the fixed size of the Chromosome sequences to be mutated.
   *
   * <p>All settings are overridden even if null arrays are passed for some arguments.
   *
   * @param stepSize The mutation step size (must not be null)
   * @param lower The lower limit for the sequence positions (can be null)
   * @param upper The upper limit for the sequence positions (can be null)
   * @throws IllegalArgumentException if the input limit arrays are of different lengths to the step
   *         size
   */
  public void overrideChromosomeSettings(double[] stepSize, double[] lower, double[] upper) {
    if (stepSize == null) {
      throw new IllegalArgumentException("Step size must not be null");
    }
    if (lower != null && lower.length != stepSize.length) {
      throw new IllegalArgumentException("Lower limit must be the same length as the step size");
    }
    if (upper != null && upper.length != stepSize.length) {
      throw new IllegalArgumentException("Upper limit must be the same length as the step size");
    }

    this.stepSize = stepSize;
    this.lower = lower;
    this.upper = upper;
    getStepPositions(stepSize);
    override = true; // (stepSize != null || lower != null || upper != null);
  }

  /**
   * Determine the positions that have a step size greater than zero, i.e. can be mutated.
   *
   * @param step The step sizes for each sequence position
   */
  private void getStepPositions(double[] step) {
    positions = new int[step.length];
    positionsCount = 0;
    for (int i = 0; i < step.length; i++) {
      if (step[i] > 0) {
        positions[positionsCount++] = i;
      }
    }
  }

  /**
   * Mutates the chromosome to form a new sequence.
   *
   * <p>The number of positions are chosen from a Poisson distribution with an average using a
   * fraction of the total positions. The positions are then chosen randomly. Note that the same
   * position may be chosen multiple times. The random shifts for each mutation are taken from a
   * Gaussian using the chromosome mutation step range as the standard deviation. Set step size to
   * zero for no mutation at a position.
   */
  @Override
  public Chromosome<T> mutate(Chromosome<T> chromosome) {
    final double[] sequence = chromosome.sequence().clone();

    final double mean = fraction * chromosome.length();
    if (mean > 0) {
      int count = (int) random.nextPoisson(mean);
      final double[] step;
      final double[] min;
      final double[] max;
      // Only override if the length is correct
      if (override && stepSize.length == chromosome.length()) {
        step = stepSize;
        min = lower;
        max = upper;
      } else {
        step = chromosome.mutationStepRange();
        min = chromosome.lowerLimit();
        max = chromosome.upperLimit();
        getStepPositions(step);
      }

      if (positionsCount == 0) {
        return chromosome.newChromosome(sequence);
      }

      while (count-- > 0) {
        final int i = positions[random.getRandomGenerator().nextInt(positionsCount)];

        sequence[i] = random.nextGaussian(sequence[i], step[i]);
        // Check limits
        if (min != null) {
          if (sequence[i] < min[i]) {
            sequence[i] = min[i];
          }
        }
        if (max != null) {
          if (sequence[i] > max[i]) {
            sequence[i] = max[i];
          }
        }
      }
    }

    return chromosome.newChromosome(sequence);
  }
}
