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

package uk.ac.sussex.gdsc.smlm.function;

import org.apache.commons.math3.special.Gamma;
import org.apache.commons.math3.util.FastMath;

//@formatter:off
/**
 * This is a wrapper for any function to compute the negative log-likelihood assuming a Poisson
 * distribution:
 *
 * <pre>
 * f(x) = l(x) - k * ln(l(x)) + log(k!)
 * </pre>
 *
 * <p>Where:
 *
 * <ul>
 * <li>l(x) is the function (expected) value
 * <li>k is the observed value
 * </ul>
 *
 * <p>The negative log-likelihood (and gradient) can be evaluated over the entire set of observed
 * values or for a chosen observed value.
 *
 * <p>To allow a likelihood to be computed when the function predicts negative count data, the
 * function prediction is set to {@link Double#MIN_VALUE}. This can be disabled.
 *
 * <p>The class can handle non-integer observed data. In this case the PMF is approximated as:
 *
 * <pre>
 * PMF(l, k) = C * e ^ -l * l ^ x / gamma(k + 1)
 * </pre>
 *
 * <p>with:
 *
 * <ul>
 * <li>l = the function (expected) value
 * <li>gamma = the gamma function
 * <li>C = a normalising constant
 * </ul>
 *
 * <p>The normalising constant is used to ensure the PMF sums to 1. However it is omitted in this
 * implementation for speed. The PMF sums to approximately 1 for {@code l>=4}.
 */
//@formatter:on
public class PoissonLikelihoodWrapper extends LikelihoodWrapper {
  private final boolean integerData;
  private final double sumLogFactorialK;
  private final double alpha;
  private final double logAlpha;

  private boolean allowNegativeExpectedValues = true;

  private static boolean initialiseFactorial(double[] data) {
    int max = 0;
    for (final double d : data) {
      final int i = (int) d;
      if (i != d || d < 0) {
        return false;
      }
      if (max < i) {
        max = i;
      }
    }

    LogFactorial.increaseTableMaxN(max);
    return true;
  }

  /**
   * Initialise the function.
   *
   * <p>The input parameters must be the full parameters for the non-linear function. Only those
   * parameters with gradient indices should be passed in to the functions to obtain the value (and
   * gradient).
   *
   * @param function The function to be used to calculated the expected values
   * @param parameters The initial parameters for the function
   * @param data The observed values
   * @param dataSize The number of observed values
   * @param alpha Inverse gain of the EMCCD chip
   * @throws IllegalArgumentException if the input observed values are not integers
   */
  public PoissonLikelihoodWrapper(NonLinearFunction function, double[] parameters, double[] data,
      int dataSize, double alpha) {
    super(function, parameters, data, dataSize);
    this.alpha = Math.abs(alpha);
    logAlpha = Math.log(alpha);

    // Initialise the factorial table to the correct size
    integerData = (alpha == 1) && initialiseFactorial(data);
    // Pre-compute the sum over the data
    double sum = 0;
    if (integerData) {
      for (final double d : data) {
        sum += LogFactorial.logF((int) d);
      }
    } else {
      // Pre-apply gain
      for (int i = 0; i < dataSize; i++) {
        data[i] *= this.alpha;
        sum += LogFactorial.logF(data[i]);
      }

      // We subtract this as we are computing the negative log likelihood
      sum -= dataSize * logAlpha;
    }
    sumLogFactorialK = sum;
  }

  @Override
  public double computeLikelihood() {
    // Compute the negative log-likelihood to be minimised
    // f(x) = l(x) - k * ln(l(x)) + log(k!)
    double ll = 0;
    for (int i = 0; i < dataSize; i++) {
      // Function now computes expected poisson mean without gain
      double lx = function.eval(i); // * alpha;

      // Check for zero and return the worst likelihood score
      if (lx <= 0) {
        if (allowNegativeExpectedValues) {
          lx = Double.MIN_VALUE;
        } else {
          // Since ln(0) -> -Infinity
          return Double.POSITIVE_INFINITY;
        }
      }

      final double k = data[i];
      ll += lx - k * Math.log(lx);
    }
    return ll + sumLogFactorialK;
  }

  @Override
  public double computeLikelihood(double[] gradient) {
    // Compute the negative log-likelihood to be minimised
    // f(x) = l(x) - k * ln(l(x)) + log(k!)
    //
    // Since (k * ln(l(x)))' = (k * ln(l(x))') * l'(x)
    // = (k / l(x)) * l'(x)

    // f'(x) = l'(x) - (k/l(x) * l'(x))
    // f'(x) = l'(x) * (1 - k/l(x))

    double ll = 0;
    for (int j = 0; j < numberOfVariables; j++) {
      gradient[j] = 0;
    }
    final double[] dlda = new double[numberOfVariables];
    for (int i = 0; i < dataSize; i++) {
      // Function now computes expected poisson mean without gain
      double lx = function.eval(i, dlda); // * alpha;

      final double k = data[i];

      // Check for zero and return the worst likelihood score
      if (lx <= 0) {
        if (allowNegativeExpectedValues) {
          lx = Double.MIN_VALUE;
        } else {
          // Since ln(0) -> -Infinity
          return Double.POSITIVE_INFINITY;
        }
      }
      ll += lx - k * Math.log(lx);

      // Continue to work out the gradient since this does not involve logs.
      // Note: if l==0 then we get divide by zero and a NaN value.
      // Function now computes expected poisson mean without gain
      final double factor = (1 - k / lx); // * alpha;
      for (int j = 0; j < gradient.length; j++) {
        // gradient[j] += dl_da[j] - (dl_da[j] * k / l);
        // gradient[j] += dl_da[j] * (1 - k / l);
        gradient[j] += dlda[j] * factor;
      }
    }
    return ll + sumLogFactorialK;
  }

  @Override
  public double computeLikelihood(int index) {
    // Function now computes expected poisson mean without gain
    double lx = function.eval(index); // * alpha;

    // Check for zero and return the worst likelihood score
    if (lx <= 0) {
      if (allowNegativeExpectedValues) {
        lx = Double.MIN_VALUE;
      } else {
        // Since ln(0) -> -Infinity
        return Double.POSITIVE_INFINITY;
      }
    }

    final double k = data[index];
    // Function now computes expected poisson mean without gain
    return lx - k * Math.log(lx) + ((integerData) ? LogFactorial.logF((int) k) : logFactorial(k))
        - logAlpha;
  }

  @Override
  public double computeLikelihood(double[] gradient, int index) {
    for (int j = 0; j < numberOfVariables; j++) {
      gradient[j] = 0;
    }
    final double[] dlda = new double[numberOfVariables];
    // Function now computes expected poisson mean without gain
    double lx = function.eval(index, dlda); // * alpha;

    // Check for zero and return the worst likelihood score
    if (lx <= 0) {
      if (allowNegativeExpectedValues) {
        lx = Double.MIN_VALUE;
      } else {
        // Since ln(0) -> -Infinity
        return Double.POSITIVE_INFINITY;
      }
    }

    final double k = data[index];
    // Function now computes expected poisson mean without gain
    final double factor = (1 - k / lx); // * alpha;
    for (int j = 0; j < gradient.length; j++) {
      // gradient[j] = dl_da[j] - (dl_da[j] * k / l);
      // gradient[j] = dl_da[j] * (1 - k / l);
      gradient[j] = dlda[j] * factor;
    }

    // Function now computes expected poisson mean without gain
    // The probability = p * alpha
    // Log(probability) = log(p) + log(alpha)

    return lx - k * Math.log(lx) + ((integerData) ? LogFactorial.logF((int) k) : logFactorial(k))
        - logAlpha;
  }

  private static double logFactorial(double value) {
    if (value <= 1) {
      return 0;
    }
    return Gamma.logGamma(value + 1);
  }

  /**
   * Compute the negative log likelihood.
   *
   * @param mean the mean of the Poisson distribution (lambda)
   * @param count the observed count
   * @return the negative log likelihood
   */
  public static double negativeLogLikelihood(double mean, double count) {
    final boolean integerData = (int) count == count;
    if (integerData) {
      LogFactorial.increaseTableMaxN((int) count);
    }
    return mean - count * Math.log(mean)
        + ((integerData) ? LogFactorial.logF((int) count) : logFactorial(count));
  }

  /**
   * Compute the likelihood.
   *
   * @param mean the mean of the Poisson distribution (lambda)
   * @param count the observed count
   * @return the likelihood
   */
  public static double likelihood(double mean, double count) {
    final double nll = negativeLogLikelihood(mean, count);
    return FastMath.exp(-nll);
  }

  @Override
  public boolean canComputeGradient() {
    return true;
  }

  /**
   * Set to true if negative expected values are allowed. In this case the expected value is set to
   * Double.MIN_VALUE and the effect on the gradient is undefined.
   *
   * @return true, if negative expected values are allowed
   */
  public boolean isAllowNegativeExpectedValues() {
    return allowNegativeExpectedValues;
  }

  /**
   * Set to true if negative expected values are allowed. In this case the expected value is set to
   * Double.MIN_VALUE and the effect on the gradient is undefined.
   *
   * @param allowNegativeExpectedValues true, if negative expected values are allowed
   */
  public void setAllowNegativeExpectedValues(boolean allowNegativeExpectedValues) {
    this.allowNegativeExpectedValues = allowNegativeExpectedValues;
  }
}
