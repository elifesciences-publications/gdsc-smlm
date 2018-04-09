package gdsc.smlm.function;

import org.apache.commons.math3.util.FastMath;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2014 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

/**
 * Implements the probability density function for a Poisson-Gaussian Mixture. The Gaussian is assumed to have mean of
 * zero. If no mean (zero or below) is provided for the Poisson distribution then the probability density function
 * matches that of the Gaussian.
 * <p>
 * The implementation uses the saddle-point approximation described in Snyder, et al (1995) Compensation for readout
 * noise in CCD images. J.Opt. Soc. Am. 12, 272-283. The method is adapted from the C source code provided in the
 * appendix.
 * <p>
 * The likelihood function is designed to model on-chip amplification of a EMCCD/CCD/sCMOS camera which captures a
 * Poisson process of emitted light, converted to electrons on the camera chip, amplified by a gain and then read with
 * Gaussian noise.
 */
public class PoissonGaussianFunction implements LikelihoodFunction
{
	/**
	 * The inverse of the on-chip gain multiplication factor
	 */
	final double alpha;

	private static final double EPSILON = 1e-4; // 1e-6
	static final double NORMALISATION = 1 / Math.sqrt(2 * Math.PI);
	static final double LOG_NORMALISATION = Math.log(NORMALISATION);

	/**
	 * Number of Picard iterations to use
	 */
	private static final int NUM_PICARD = 3;

	private boolean usePicardApproximation = false;
	private final double mu;
	private final double sigmasquared;
	private final boolean noPoisson;

	private final double probabilityNormalisation;
	private final double logNormalisation;

	/**
	 * @param alpha
	 *            The inverse of the on-chip gain multiplication factor
	 * @param mu
	 *            The mean of the Poisson distribution at readout
	 * @param sigmasquared
	 *            The variance of the Gaussian distribution at readout (must be positive)
	 */
	private PoissonGaussianFunction(double alpha, double mu, double sigmasquared)
	{
		if (sigmasquared <= 0)
			throw new IllegalArgumentException("Gaussian variance must be strictly positive");
		alpha = Math.abs(alpha);
		noPoisson = (mu <= 0);

		// Apply gain to the mean and readout standard deviation. 
		// This compresses the probability distribution by alpha. Thus we can compute the
		// probability using a Poisson or Poisson-Gaussian mixture and then compress the
		// output probability so the cumulative probability is 1 over the uncompressed range.
		mu *= alpha;
		sigmasquared *= (alpha * alpha);

		//System.out.printf("mu=%f s^2=%f\n", mu, sigmasquared);

		this.alpha = alpha;
		this.mu = mu;
		this.sigmasquared = sigmasquared;

		probabilityNormalisation = ((noPoisson) ? getProbabilityNormalisation(sigmasquared) : 1) * alpha;

		logNormalisation = ((noPoisson) ? getLogNormalisation(sigmasquared)
				// Note that this uses the LOG_NORMALISATION (not zero) as the logProbability function 
				// uses the getPseudoLikelihood function. It would be zero if the static logProbability
				// was called.
				: LOG_NORMALISATION) + Math.log(alpha);
	}

	/**
	 * @param alpha
	 *            The inverse of the on-chip gain multiplication factor
	 * @param mu
	 *            The mean of the Poisson distribution at readout
	 * @param s
	 *            The standard deviation of the Gaussian distribution at readout
	 * @throws IllegalArgumentException
	 *             if the mean or variance is zero or below
	 */
	public static PoissonGaussianFunction createWithStandardDeviation(final double alpha, final double mu,
			final double s)
	{
		return new PoissonGaussianFunction(alpha, mu, s * s);
	}

	/**
	 * @param alpha
	 *            The inverse of the on-chip gain multiplication factor
	 * @param mu
	 *            The mean of the Poisson distribution at readout
	 * @param var
	 *            The variance of the Gaussian distribution at readout (must be positive)
	 * @throws IllegalArgumentException
	 *             if the mean or variance is zero or below
	 */
	public static PoissonGaussianFunction createWithVariance(final double alpha, final double mu, final double var)
	{
		return new PoissonGaussianFunction(alpha, mu, var);
	}

	/**
	 * Get the probability of observation x
	 * 
	 * @param x
	 *            The observation value (after gain)
	 * @return The probability
	 */
	public double probability(double x)
	{
		return probability(x, mu);
	}

	/**
	 * Get the probability of observation x
	 * <p>
	 * Note the normalisation will be based on the mu used in the constructor of the function. So it may be wrong if the
	 * mu was below zero on construction and is above zero now, or vice-versa.
	 * 
	 * @param x
	 *            The observation value (after gain)
	 * @param mu
	 *            The mean of the Poisson distribution (before gain)
	 * @return The probability
	 */
	public double probability(double x, double mu)
	{
		// convert to photons
		x *= alpha;

		return (noPoisson) ? FastMath.exp(-0.5 * x * x / sigmasquared) * probabilityNormalisation
				: getProbability(x, mu, sigmasquared, usePicardApproximation) * probabilityNormalisation;
	}

	/**
	 * Get the log(p) of observation x
	 * 
	 * @param x
	 *            The observation value
	 * @return The log of the probability
	 */
	public double logProbability(double x)
	{
		return logProbability(x, mu);
	}

	/**
	 * Get the log(p) of observation x
	 * <p>
	 * Note the normalisation will be based on the mu used in the constructor of the function. So it may be wrong if the
	 * mu was below zero on construction and is above zero now, or vice-versa.
	 * 
	 * @param x
	 *            The observation value
	 * @param mu
	 *            The mean of the Poisson distribution
	 * @return The log of the probability
	 */
	public double logProbability(double x, double mu)
	{
		// convert to photons
		x *= alpha;

		return (noPoisson) ? (-0.5 * x * x / sigmasquared) + logNormalisation
				: getPseudoLikelihood(x, mu, sigmasquared, usePicardApproximation) + logNormalisation;
	}

	/**
	 * Get the probability of observation x
	 * 
	 * @param x
	 *            The observation value
	 * @param mu
	 *            The mean of the Poisson distribution (if zero or below the probability is that of the Gaussian)
	 * @param sigmasquared
	 *            The variance of the Gaussian distribution (must be positive)
	 * @param usePicardApproximation
	 *            Use the Picard approximation for the initial saddle point. The default is Pade.
	 * @return The probability
	 */
	public static double probability(final double x, final double mu, final double sigmasquared,
			final boolean usePicardApproximation)
	{
		// If no Poisson mean then just use the Gaussian
		if (mu <= 0)
			return FastMath.exp(-0.5 * x * x / sigmasquared) * getProbabilityNormalisation(sigmasquared);
		return getProbability(x, mu, sigmasquared, usePicardApproximation);
	}

	/**
	 * Gets the probability normalisation for the Gaussian distribution so the cumulative probability is 1.
	 *
	 * @param sigmasquared
	 *            the sigma squared
	 * @return the probability normalisation
	 */
	static double getProbabilityNormalisation(double sigmasquared)
	{
		return NORMALISATION / Math.sqrt(sigmasquared);
	}

	/**
	 * Get the probability of observation x
	 * 
	 * @param x
	 *            The observation value
	 * @param mu
	 *            The mean of the Poisson distribution (must be positive)
	 * @param sigmasquared
	 *            The variance of the Gaussian distribution (must be positive)
	 * @param usePicardApproximation
	 *            Use the Picard approximation for the initial saddle point. The default is Pade.
	 * @return The probability
	 */
	private static double getProbability(final double x, final double mu, final double sigmasquared,
			final boolean usePicardApproximation)
	{
		double saddlepoint = (usePicardApproximation) ? picard(x, mu, sigmasquared) : pade(x, mu, sigmasquared);
		saddlepoint = newton_iteration(x, mu, sigmasquared, saddlepoint);
		final double logP = sp_approx(x, mu, sigmasquared, saddlepoint);
		return FastMath.exp(logP) * NORMALISATION;
	}

	/**
	 * Get the log(p) of observation x
	 * 
	 * @param x
	 *            The observation value
	 * @param mu
	 *            The mean of the Poisson distribution (if zero or below the probability is that of the Gaussian)
	 * @param sigmasquared
	 *            The variance of the Gaussian distribution (must be positive)
	 * @param usePicardApproximation
	 *            Use the Picard approximation for the initial saddle point. The default is Pade.
	 * @return The log of the probability
	 */
	public static double logProbability(final double x, final double mu, final double sigmasquared,
			final boolean usePicardApproximation)
	{
		// If no Poisson mean then just use the Gaussian
		if (mu <= 0)
			return (-0.5 * x * x / sigmasquared) + getLogNormalisation(sigmasquared);

		return getPseudoLikelihood(x, mu, sigmasquared, usePicardApproximation) + LOG_NORMALISATION;
	}

	/**
	 * Gets the log probability normalisation for the Gaussian distribution.
	 *
	 * @param sigmasquared
	 *            the sigma squared
	 * @return the log probability normalisation
	 */
	static double getLogNormalisation(double sigmasquared)
	{
		return LOG_NORMALISATION - Math.log(sigmasquared) * 0.5;
	}

	/**
	 * Get a pseudo-likelihood of observation x.
	 * <p>
	 * This is equivalent to the {@link #logProbability(double)} without the normalisation of the probability density
	 * function to 1. It differs by a constant value of -log(1 / sqrt(2 * PI)). This function is suitable for use as the
	 * likelihood function in maximum likelihood estimation since all values will differ by the same constant but will
	 * evaluate faster.
	 * 
	 * @param x
	 *            The observation value
	 * @param mu
	 *            The mean of the Poisson distribution (if zero or below the probability is that of the Gaussian)
	 * @param sigmasquared
	 *            The variance of the Gaussian distribution (must be positive)
	 * @param usePicardApproximation
	 *            Use the Picard approximation for the initial saddle point. The default is Pade.
	 * @return The probability
	 */
	public static double pseudoLikelihood(final double x, final double mu, final double sigmasquared,
			final boolean usePicardApproximation)
	{
		// If no Poisson mean then just use the Gaussian
		if (mu <= 0)
			return (-0.5 * x * x / sigmasquared);

		return getPseudoLikelihood(x, mu, sigmasquared, usePicardApproximation);
	}

	/**
	 * Get a pseudo-likelihood of observation x.
	 * <p>
	 * This is equivalent to the {@link #logProbability(double)} without the normalisation of the probability density
	 * function to 1. It differs by a constant value of -log(1 / sqrt(2 * PI)). This function is suitable for use as the
	 * likelihood function in maximum likelihood estimation since all values will differ by the same constant but will
	 * evaluate faster.
	 * 
	 * @param x
	 *            The observation value
	 * @param mu
	 *            The mean of the Poisson distribution (must be positive)
	 * @param sigmasquared
	 *            The variance of the Gaussian distribution (must be positive)
	 * @param usePicardApproximation
	 *            Use the Picard approximation for the initial saddle point. The default is Pade.
	 * @return The probability
	 */
	private static double getPseudoLikelihood(final double x, final double mu, final double sigmasquared,
			final boolean usePicardApproximation)
	{
		double saddlepoint = (usePicardApproximation) ? picard(x, mu, sigmasquared) : pade(x, mu, sigmasquared);
		saddlepoint = newton_iteration(x, mu, sigmasquared, saddlepoint);
		return sp_approx(x, mu, sigmasquared, saddlepoint);
	}

	/**
	 * Return the initial saddle point estimated by the Pade approximation
	 * 
	 * @param x
	 * @param mu
	 * @param sigmasquared
	 * @return The saddle point
	 */
	static double pade(final double x, final double mu, final double sigmasquared)
	{
		final double bterm = x - 2 * sigmasquared - mu;

		// Original code
		//return -Math.log(0.5 * (bterm + Math.sqrt(bterm * bterm + 4 * mu * (2 * sigmasquared + x))) / mu);

		// Check for negative sqrt
		final double argument_to_sqrt = bterm * bterm + 4 * mu * (2 * sigmasquared + x);
		// This check is needed if x is very negative
		if (argument_to_sqrt < 0)
			// Revert to Taylor approximation
			return (mu - x) / (mu + sigmasquared);

		// Check for negative log
		final double argument_to_log = 0.5 * (bterm + Math.sqrt(argument_to_sqrt)) / mu;
		if (argument_to_log <= 0)
			// Revert to Taylor approximation
			return (mu - x) / (mu + sigmasquared);
		return -Math.log(argument_to_log);
	}

	/**
	 * Return the initial saddle point estimated by the Picard approximation
	 * 
	 * @param x
	 * @param mu
	 * @param sigmasquared
	 * @return The saddle point
	 */
	static double picard(final double x, final double mu, final double sigmasquared)
	{
		// Use Taylor approximation to obtain the starting point for Picard iteration
		final double taylor = (mu - x) / (mu + sigmasquared);
		double saddlepoint = taylor;
		for (int i = 0; i < NUM_PICARD; i++)
		{
			final double argument_to_log = mu / (x + sigmasquared * saddlepoint);
			if (argument_to_log <= 0)
				// Break out of loop if argument to log goes negative
				return taylor;
			saddlepoint = Math.log(argument_to_log);
		}
		return saddlepoint;
	}

	/**
	 * Returns the saddlepoint found by Newton iteration for a given x, mu, sigmasquared and an initial estimate of the
	 * saddle point (found with either the Pade or Picard approach)
	 * 
	 * @param x
	 * @param mu
	 * @param sigmasquared
	 * @param initial_saddlepoint
	 * @return The saddle point
	 */
	static double newton_iteration(final double x, final double mu, final double sigmasquared,
			final double initial_saddlepoint)
	{
		double change = 0;
		double saddlepoint = initial_saddlepoint;

		// Original code can infinite loop
		//		do
		//		{
		//			final double mu_exp_minus_s = mu * FastMath.exp(-saddlepoint);
		//			change = (x + sigmasquared * saddlepoint - mu_exp_minus_s) / (sigmasquared + mu_exp_minus_s);
		//			saddlepoint -= change;
		//		} while (FastMath.abs(change) > EPSILON * FastMath.abs(saddlepoint));
		//		return saddlepoint;

		// Limit the number of iterations
		for (int i = 0; i < 20; i++)
		{
			final double mu_exp_minus_s = mu * FastMath.exp(-saddlepoint);
			change = (x + sigmasquared * saddlepoint - mu_exp_minus_s) / (sigmasquared + mu_exp_minus_s);
			saddlepoint -= change;
			if (FastMath.abs(change) <= EPSILON * FastMath.abs(saddlepoint))
				return saddlepoint;
		}

		// This happens when we cannot converge
		//System.out.printf("No Newton convergence: x=%f, mu=%f, s2=%f, %s -> %s : logP=%f, p=%f\n", x, mu, sigmasquared,
		//		initial_saddlepoint, saddlepoint, sp_approx(x, mu, sigmasquared, initial_saddlepoint),
		//		FastMath.exp(sp_approx(x, mu, sigmasquared, initial_saddlepoint)) * NORMALISATION);

		return saddlepoint; //initial_saddlepoint;
	}

	/**
	 * Return the saddlepoint approximation to the log of p(x,mu,sigmasquared) given the saddle point found by the
	 * Newton iteration. Remember the sqrt(2*PI) factor has been left out.
	 * 
	 * @param x
	 * @param mu
	 * @param sigmasquared
	 * @param saddlepoint
	 * @return The saddlepoint approximation
	 */
	static double sp_approx(final double x, final double mu, final double sigmasquared, final double saddlepoint)
	{
		final double mu_exp_minus_s = mu * FastMath.exp(-saddlepoint);
		final double phi2 = sigmasquared + mu_exp_minus_s;
		return -mu + (saddlepoint * (x + 0.5 * sigmasquared * saddlepoint)) + mu_exp_minus_s - 0.5 * Math.log(phi2);
	}

	/**
	 * Return if using the Picard approximation for the initial saddle point
	 * 
	 * @return True if using the Picard approximation
	 */
	public boolean isUsePicardApproximation()
	{
		return usePicardApproximation;
	}

	/**
	 * Specify whether to use the Picard approximation for the initial saddle point. The alternative is Pade.
	 * 
	 * @param usePicardApproximation
	 *            True to use the Picard approximation
	 */
	public void setUsePicardApproximation(boolean usePicardApproximation)
	{
		this.usePicardApproximation = usePicardApproximation;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.LikelihoodFunction#likelihood(double, double)
	 */
	public double likelihood(double o, double e)
	{
		// convert to photons
		o *= alpha;
		e *= alpha;
		return probability(o, e, sigmasquared, usePicardApproximation) * alpha;
	}
}