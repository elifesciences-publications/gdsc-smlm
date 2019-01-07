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

/**
 * This is a wrapper for any function to compute the negative log-likelihood assuming a
 * Poisson-Gaussian distribution.
 *
 * <p>For each observed value the log-likelihood is computed from the Poisson-Gaussian distribution
 * (a Poisson convolved with a Gaussian). The mean of the Poisson distribution is set using the
 * expected value generated by the provided function. The standard deviation of the Gaussian is
 * fixed and set in the constructor. The mean of the Gaussian is assumed to be zero.
 *
 * <p>The negative log-likelihood can be evaluated over the entire set of observed values or for a
 * chosen observed value. The sum uses a non-normalised Poisson-Gaussian distribution for speed (see
 * {@link PoissonGaussianFunction#pseudoLikelihood(double, double, double, boolean)} ).
 */
public class PoissonGaussianLikelihoodWrapper extends LikelihoodWrapper {
  private final PoissonGaussianFunction2 p;
  private final boolean usePicard = false;

  /**
   * Initialise the function.
   *
   * <p>The input parameters must be the full parameters for the non-linear function. Only those
   * parameters with gradient indices should be passed in to the functions to obtain the value (and
   * gradient).
   *
   * @param f The function to be used to calculated the expected values
   * @param a The initial parameters for the function
   * @param k The observed values
   * @param n The number of observed values
   * @param alpha Inverse gain of the EMCCD chip
   * @param s The Gaussian standard deviation at readout
   */
  public PoissonGaussianLikelihoodWrapper(NonLinearFunction f, double[] a, double[] k, int n,
      double alpha, double s) {
    super(f, a, k, n);
    p = PoissonGaussianFunction2.createWithStandardDeviation(alpha, s);
    p.setUsePicardApproximation(usePicard);
  }

  /** {@inheritDoc} */
  @Override
  public double computeLikelihood() {
    // Compute the negative log-likelihood to be minimised
    double ll = 0;
    for (int i = 0; i < n; i++) {
      ll -= p.logLikelihood(data[i], f.eval(i));
    }
    return ll;
  }

  /** {@inheritDoc} */
  @Override
  public double computeLikelihood(int i) {
    return -p.logLikelihood(data[i], f.eval(i));
  }

  /** {@inheritDoc} */
  @Override
  public boolean canComputeGradient() {
    return false;
  }
}
