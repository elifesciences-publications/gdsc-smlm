package gdsc.smlm.function;

import java.util.Arrays;

import org.apache.commons.math3.special.Gamma;
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
 * This is a wrapper for any function to compute the negative log-likelihood assuming a Poisson distribution:<br/>
 * f(x) = l(x) - k * ln(l(x)) + log(k!)<br/>
 * Where:<br/>
 * l(x) is the function (expected) value<br/>
 * k is the observed value
 * <p>
 * The negative log-likelihood (and gradient) can be evaluated over the entire set of observed values or for a chosen
 * observed value.
 * <p>
 * To allow a likelihood to be computed when the function predicts negative count data, the function prediction is set
 * to Double.MIN_VALUE. This can be disabled. 
 * <p>
 * The class can handle non-integer observed data. In this case the PMF is approximated as:
 * 
 * <pre>
 * PMF(l,k) = C * e^-l * l^x / gamma(k+1)
 * with:
 * l = the function (expected) value
 * gamma = the gamma function
 * C = a normalising constant.
 * </pre>
 * 
 * The normalising constant is used to ensure the PMF sums to 1. However it is omitted in this implementation for speed.
 * The PMF sums to approximately 1 for l>=4.
 */
public class PoissonLikelihoodWrapper extends LikelihoodWrapper
{
	private static double[] logFactorial;
	private final boolean integerData;
	private final double sumLogFactorialK;
	
	private boolean allowNegativExpectedValues = true;

	/** All long-representable factorials */
	static final long[] FACTORIALS = new long[] { 1l, 1l, 2l, 6l, 24l, 120l, 720l, 5040l, 40320l, 362880l, 3628800l,
			39916800l, 479001600l, 6227020800l, 87178291200l, 1307674368000l, 20922789888000l, 355687428096000l,
			6402373705728000l, 121645100408832000l, 2432902008176640000l };

	static
	{
		logFactorial = new double[FACTORIALS.length];
		for (int k = 0; k < FACTORIALS.length; k++)
			logFactorial[k] = Math.log(FACTORIALS[k]);
	}

	private static boolean initialiseFactorial(double[] data)
	{
		int max = 0;
		for (double d : data)
		{
			final int i = (int) d;
			if (i != d || d < 0)
				return false;
			if (max < i)
				max = i;
		}

		if (logFactorial.length <= max)
			populate(max);
		return true;
	}

	private static synchronized void populate(int n)
	{
		if (logFactorial.length <= n)
		{
			int k = logFactorial.length - 1;
			double logSum = logFactorial[k];

			logFactorial = Arrays.copyOf(logFactorial, n + 1);
			while (k < n)
			{
				k++;
				logSum += Math.log(k);
				logFactorial[k] = logSum;
			}
		}
	}

	/**
	 * Initialise the function.
	 * <p>
	 * The input parameters must be the full parameters for the non-linear function. Only those parameters with gradient
	 * indices should be passed in to the functions to obtain the value (and gradient).
	 * 
	 * @param f
	 *            The function to be used to calculated the expected values
	 * @param a
	 *            The initial parameters for the function
	 * @param k
	 *            The observed values
	 * @param n
	 *            The number of observed values
	 * @throws IllegalArgumentException
	 *             if the input observed values are not integers
	 */
	public PoissonLikelihoodWrapper(NonLinearFunction f, double[] a, double[] k, int n)
	{
		super(f, a, k, n);
		// Initialise the factorial table to the correct size
		integerData = initialiseFactorial(k);
		// Pre-compute the sum over the data
		double sum = 0;
		if (integerData)
		{
			for (double d : k)
				sum += logFactorial[(int) d];
		}
		else
		{
			for (double d : k)
				sum += logFactorial(d);
		}
		sumLogFactorialK = sum;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.LikelihoodWrapper#computeLikelihood()
	 */
	public double computeLikelihood()
	{
		// Compute the negative log-likelihood to be minimised
		// f(x) = l(x) - k * ln(l(x)) + log(k!)
		double ll = 0;
		for (int i = 0; i < n; i++)
		{
			double l = f.eval(i);

			// Check for zero and return the worst likelihood score
			if (l <= 0)
			{
				if (allowNegativExpectedValues)
					l = Double.MIN_VALUE;
				else
					// Since ln(0) -> -Infinity
					return Double.POSITIVE_INFINITY;
			}

			final double k = data[i];
			ll += l - k * Math.log(l);
		}
		return ll + sumLogFactorialK;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.LikelihoodWrapper#computeLikelihood(double[])
	 */
	public double computeLikelihood(double[] gradient)
	{
		// Compute the negative log-likelihood to be minimised
		// f(x) = l(x) - k * ln(l(x)) + log(k!)
		// 
		// Since (k * ln(l(x)))' = (k * ln(l(x))') * l'(x)
		//                       = (k / l(x)) * l'(x)

		// f'(x) = l'(x) - (k/l(x) * l'(x))
		// f'(x) = l'(x) * (1 - k/l(x))

		double ll = 0;
		for (int j = 0; j < nVariables; j++)
			gradient[j] = 0;
		double[] dl_da = new double[nVariables];
		for (int i = 0; i < n; i++)
		{
			double l = f.eval(i, dl_da);

			final double k = data[i];

			// Check for zero and return the worst likelihood score
			if (l <= 0)
			{
				if (allowNegativExpectedValues)
					l = Double.MIN_VALUE;
				else
					// Since ln(0) -> -Infinity
					return Double.POSITIVE_INFINITY;
			}
			ll += l - k * Math.log(l);

			// Continue to work out the gradient since this does not involve logs.
			// Note: if l==0 then we get divide by zero and a NaN value
			final double factor = 1 - k / l;
			for (int j = 0; j < gradient.length; j++)
			{
				//gradient[j] += dl_da[j] - (dl_da[j] * k / l);
				//gradient[j] += dl_da[j] * (1 - k / l);
				gradient[j] += dl_da[j] * factor;
			}
		}
		return ll + sumLogFactorialK;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.LikelihoodWrapper#computeLikelihood(int)
	 */
	public double computeLikelihood(int i)
	{
		double l = f.eval(i);

		// Check for zero and return the worst likelihood score
		if (l <= 0)
		{
			if (allowNegativExpectedValues)
				l = Double.MIN_VALUE;
			else
				// Since ln(0) -> -Infinity
				return Double.POSITIVE_INFINITY;
		}

		final double k = data[i];
		return l - k * Math.log(l) + ((integerData) ? logFactorial[(int) k] : logFactorial(k));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.LikelihoodWrapper#computeLikelihood(double[], int)
	 */
	public double computeLikelihood(double[] gradient, int i)
	{
		for (int j = 0; j < nVariables; j++)
			gradient[j] = 0;
		double[] dl_da = new double[nVariables];
		double l = f.eval(i, dl_da);

		// Check for zero and return the worst likelihood score
		if (l <= 0)
		{
			if (allowNegativExpectedValues)
				l = Double.MIN_VALUE;
			else
				// Since ln(0) -> -Infinity
				return Double.POSITIVE_INFINITY;
		}

		final double k = data[i];
		final double factor = 1 - k / l;
		for (int j = 0; j < gradient.length; j++)
		{
			//gradient[j] = dl_da[j] - (dl_da[j] * k / l);
			//gradient[j] = dl_da[j] * (1 - k / l);
			gradient[j] = dl_da[j] * factor;
		}
		return l - k * Math.log(l) + ((integerData) ? logFactorial[(int) k] : logFactorial(k));
	}

	private static double logFactorial(double k)
	{
		if (k <= 1)
			return 0;
		return Gamma.logGamma(k + 1);
	}

	/**
	 * Compute the negative log likelihood.
	 *
	 * @param l
	 *            the mean of the Poisson distribution (lambda)
	 * @param k
	 *            the observed count
	 * @return the negative log likelihood
	 */
	public static double negativeLogLikelihood(double l, double k)
	{
		final boolean integerData = (int) k == k;
		if (integerData)
		{
			if (logFactorial.length <= k)
				populate((int) k);
		}
		return l - k * Math.log(l) + ((integerData) ? logFactorial[(int) k] : logFactorial(k));
	}

	/**
	 * Compute the likelihood.
	 *
	 * @param l
	 *            the mean of the Poisson distribution (lambda)
	 * @param k
	 *            the observed count
	 * @return the likelihood
	 */
	public static double likelihood(double l, double k)
	{
		final double nll = negativeLogLikelihood(l, k);
		return FastMath.exp(-nll);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.LikelihoodWrapper#canComputeGradient()
	 */
	@Override
	public boolean canComputeGradient()
	{
		return true;
	}

	/**
	 * Set to true if negative expected values are allowed. In this case the expected value is set to Double.MIN_VALUE and the effect on the gradient is undefined.
	 *
	 * @return true, if negative expected values are allowed
	 */
	public boolean isAllowNegativeExpectedValues()
	{
		return allowNegativExpectedValues;
	}

	/**
	 * Set to true if negative expected values are allowed. In this case the expected value is set to Double.MIN_VALUE and the effect on the gradient is undefined.
	 *
	 * @param allowNegativeExpectedValues true, if negative expected values are allowed
	 */
	public void setAllowNegativeExpectedValues(boolean allowNegativeExpectedValues)
	{
		this.allowNegativExpectedValues = allowNegativeExpectedValues;
	}
}