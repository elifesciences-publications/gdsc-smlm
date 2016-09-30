package gdsc.smlm.fitting.nonlinear;

import java.util.Arrays;

import gdsc.smlm.fitting.FitStatus;
import gdsc.smlm.function.NonLinearFunction;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2016 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This is an adaption of the C-code contained in the CcpNmr Analysis Program:
 *   CCPN website (http://www.ccpn.ac.uk/). 
 * The CCPN code was based on Numerical Recipes. 
 *---------------------------------------------------------------------------*/

/**
 * Uses Levenberg-Marquardt method to fit a nonlinear model with coefficients (a) for a
 * set of data points (x, y).
 * <p>
 * Support bounded parameters using a hard-stop limit.
 * <p>
 * Support parameter clamping to prevent large parameter shifts.
 */
public class BoundedNonLinearFit extends NonLinearFit
{
	private boolean isLower = false, isUpper = false;
	private double[] lower, upper;
	private boolean[] atBounds;
	private int atBoundsCount;
	private boolean isClamped = false;
	private boolean nonLocalSearch = false;
	private double localSearch = 0;
	private double[] clampInitial, clamp;
	private int[] dir;
	private boolean dynamicClamp = false;

	/**
	 * Default constructor
	 * 
	 * @param func
	 *            The function to fit
	 */
	public BoundedNonLinearFit(NonLinearFunction func)
	{
		super(func, null);
	}

	/**
	 * Default constructor
	 * 
	 * @param func
	 *            The function to fit
	 * @param sc
	 *            The stopping criteria
	 */
	public BoundedNonLinearFit(NonLinearFunction func, StoppingCriteria sc)
	{
		super(func, sc);
	}

	/**
	 * Default constructor
	 * 
	 * @param func
	 *            The function to fit
	 * @param sc
	 *            The stopping criteria
	 * @param significantDigits
	 *            Validate the Levenberg-Marquardt fit solution to the specified number of significant digits
	 * @param maxAbsoluteError
	 *            Validate the Levenberg-Marquardt fit solution using the specified maximum absolute error
	 */
	public BoundedNonLinearFit(NonLinearFunction func, StoppingCriteria sc, int significantDigits,
			double maxAbsoluteError)
	{
		super(func, sc, significantDigits, maxAbsoluteError);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.fitting.nonlinear.NonLinearFit#solve(int)
	 */
	protected boolean solve(final int m)
	{
		if (!super.solve(m))
		{
			// If using a bounded LVM is there a chance that the gradient against the bounds will 
			// be very large and effect the linear decomposition of the matrix? Keep a record each 
			// iteration of what values are set to the bounds. If decomposition fails try again but set 
			// the bounded params to zero (these are ignored by the solver), thus skipping these params for 
			// this iteration.

			if (atBoundsCount != 0)
			{
				// Extract the data we require
				for (int i = m; i-- > 0;)
				{
					da[i] = (atBounds[i]) ? 0 : beta[i];
					for (int j = m; j-- > 0;)
						covar[i][j] = alpha[i][j];
					covar[i][i] *= (1 + lambda);
				}

				// This handles the case when the entire set of params have been excluded
				return solve(covar, da);
			}
			else
			{
				return false;
			}
		}
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.fitting.nonlinear.NonLinearFit#updateFitParameters(double[], int[], int, double[], double[])
	 */
	@Override
	protected boolean updateFitParameters(double[] a, int[] gradientIndices, int m, double[] da, double[] ap)
	{
		boolean truncated = false;
		nonLocalSearch = false;

		if (isClamped)
		{
			for (int j = m; j-- > 0;)
			{
				if (clamp[j] == 0)
				{
					// Use the update parameter directly
					ap[gradientIndices[j]] = a[gradientIndices[j]] + da[j];
				}
				else
				{
					// This parameter is clamped
					ap[gradientIndices[j]] = a[gradientIndices[j]] + da[j] / clamp(da[j], j);
					truncated = true;
				}
			}
			truncated |= applyBounds(ap, gradientIndices);

			// If using clamping should we can optionally only update lambda if we 
			// are close to the correct solution.
			if (localSearch != 0)
				nonLocalSearch = checkForNonLocalSearch(a, gradientIndices, m, ap);
		}
		else
		{
			for (int j = m; j-- > 0;)
			{
				// Use the update parameter directly
				ap[gradientIndices[j]] = a[gradientIndices[j]] + da[j];
			}
			truncated |= applyBounds(ap, gradientIndices);
		}
		return truncated;
	}

	/**
	 * Produce the clamping value.
	 * <p>
	 * See Stetson PB (1987) DAOPHOT: A compute program for crowded-field stellar photometry. Publ Astrom Soc Pac
	 * 99:191-222. pp207-208
	 *
	 * @param u
	 *            the update parameter
	 * @param k
	 *            the parameter index
	 * @return the clamping value
	 */
	private double clamp(double u, int k)
	{
		if (u == 0)
			// Nothing to clamp. Returning here means the direction sign is not changed.
			return 1;

		if (dynamicClamp)
		{
			// If the sign has changed then reduce the clamp factor
			final int sign = (u > 0) ? 1 : -1;

			// This addition overcomes the issue when the direction vector is new (i.e. zero filled)
			if (sign + dir[k] == 0)
			{
				// Note: By reducing the size of the clamping factor we are restricting the movement
				clamp[k] *= 0.5;
			}
			dir[k] = sign;
		}

		// Denominator for clamping function
		return 1 + (Math.abs(u) / clamp[k]);
	}

	/**
	 * Check the parameter updates are within the local search parameter relative to the initial clamp values
	 * 
	 * @param a
	 *            the current fit parameters
	 * @param gradientIndices
	 *            the gradient indices (maps the fit parameter index to the parameter array)
	 * @param m
	 *            the number of fit parameters
	 * @param da
	 *            the parameter shift
	 * @param ap
	 *            the new fit parameters
	 * @return True if the search is non-local
	 */
	private boolean checkForNonLocalSearch(double[] a, int[] gradientIndices, int m, double[] ap)
	{
		// Check each update
		for (int j = m; j-- > 0;)
		{
			if (localSearch * Math.abs(ap[gradientIndices[j]] - a[gradientIndices[j]]) > clampInitial[j])
				return true;
		}
		return false;
	}

	@Override
	protected void reduceLambda()
	{
		if (nonLocalSearch)
		{
			// Ignore... ?
			return;
		}
		super.reduceLambda();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.fitting.nonlinear.NonLinearFit#computeFit(int, double[], double[], double[], double[], double[],
	 * double)
	 */
	@Override
	public FitStatus computeFit(int n, double[] y, double[] y_fit, double[] a, double[] a_dev, double[] error,
			double noise)
	{
		// Initialise for clamping
		if (isClamped)
			// Prevent the clamping value being destroyed by dynamic updates
			clamp = (dynamicClamp) ? Arrays.copyOf(clampInitial, f.gradientIndices().length) : clampInitial;
		return super.computeFit(n, y, y_fit, a, a_dev, error, noise);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.fitting.nonlinear.BaseFunctionSolver#isBounded()
	 */
	@Override
	public boolean isBounded()
	{
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.fitting.nonlinear.BaseFunctionSolver#isConstrained()
	 */
	@Override
	public boolean isConstrained()
	{
		return false;
	}

	/**
	 * @see gdsc.smlm.fitting.nonlinear.BaseFunctionSolver#setBounds(double[], double[])
	 * @throws IllegalArgumentException
	 *             If the lower bound is above the upper bound
	 */
	@Override
	public void setBounds(double[] lowerB, double[] upperB)
	{
		// Extract the bounds for the parameters we are fitting
		final int[] indices = f.gradientIndices();

		if (lowerB == null)
		{
			lower = null;
		}
		else
		{
			lower = new double[indices.length];
			for (int i = 0; i < indices.length; i++)
			{
				lower[i] = lowerB[indices[i]];
			}
		}
		if (upperB == null)
		{
			upper = null;
		}
		else
		{
			upper = new double[indices.length];
			for (int i = 0; i < indices.length; i++)
			{
				upper[i] = upperB[indices[i]];
			}
		}
		isLower = checkArray(lower, Double.NEGATIVE_INFINITY);
		isUpper = checkArray(upper, Double.POSITIVE_INFINITY);
		// Check that the upper bound is above the lower bound
		if (isUpper && isLower)
		{
			for (int i = 0; i < lower.length; i++)
				if (lower[i] > upper[i])
					throw new IllegalArgumentException(
							"Lower bound is above upper bound: " + lower[i] + " > " + upper[i]);
		}
		// Create an array to store the indices that were at the bounds
		atBoundsCount = 0;
		if ((isUpper || isLower) && (atBounds == null || atBounds.length < indices.length))
			atBounds = new boolean[indices.length];
	}

	/**
	 * Check if the array contains anything other than value.
	 *
	 * @param array
	 *            the array
	 * @param value
	 *            the value
	 * @return True if the array has another value
	 */
	private static boolean checkArray(double[] array, double value)
	{
		if (array == null)
			return false;
		for (int i = 0; i < array.length; i++)
			if (value != array[i])
				return true;
		return false;
	}

	/**
	 * Check the point falls within the configured bounds truncating if necessary.
	 *
	 * @param point
	 *            the point
	 * @return true if truncated
	 */
	private boolean applyBounds(double[] point, int[] gradientIndices)
	{
		if (isUpper)
		{
			for (int i = 0; i < gradientIndices.length; i++)
				if (point[gradientIndices[i]] > upper[i])
				{
					atBounds[i] = true;
					point[gradientIndices[i]] = upper[i];
				}

			if (isLower)
			{
				for (int i = 0; i < gradientIndices.length; i++)
					if (point[gradientIndices[i]] < lower[i])
					{
						atBounds[i] = true;
						point[gradientIndices[i]] = lower[i];
					}
			}
			return countAtBounds(gradientIndices.length) != 0;
		}
		else if (isLower)
		{
			for (int i = 0; i < gradientIndices.length; i++)
				if (point[gradientIndices[i]] < lower[i])
				{
					atBounds[i] = true;
					point[gradientIndices[i]] = lower[i];
				}
			return countAtBounds(gradientIndices.length) != 0;
		}
		return false;
	}

	private int countAtBounds(final int m)
	{
		atBoundsCount = 0;
		for (int i = m; i-- > 0;)
			if (atBounds[i])
				atBoundsCount++;
		return atBoundsCount;
	}

	/**
	 * Sets the parameter specific clamp values. This is the maximum permissible update to the parameter.
	 * <p>
	 * See Stetson PB (1987) DAOPHOT: A compute program for crowded-field stellar photometry. Publ Astrom Soc Pac
	 * 99:191-222.
	 * <p>
	 * Warning: If the function is changed then the clamp values may require updating. However setting a new function
	 * does not set the clamp values to null to allow caching when the clamp values are unchanged.
	 *
	 * @param clampValues
	 *            the new clamp values
	 */
	public void setClampValues(double[] clampValues)
	{
		// Extract the bounds for the parameters we are fitting
		final int[] indices = f.gradientIndices();

		if (clampValues == null)
		{
			clampInitial = null;
		}
		else
		{
			clampInitial = new double[indices.length];
			for (int i = 0; i < indices.length; i++)
			{
				final double v = clampValues[indices[i]];
				if (Double.isNaN(v) || Double.isInfinite(v))
					continue;
				clampInitial[i] = Math.abs(v);
			}
		}
		isClamped = checkArray(clampInitial, 0);
		if (isClamped && (dir == null || dir.length < clampInitial.length))
			dir = new int[clampInitial.length];
	}

	/**
	 * Checks if is dynamic clamping. The clamping factor will be reduced by a factor of 2 when the direction changes.
	 *
	 * @return true, if is dynamic clamping
	 */
	public boolean isDynamicClamp()
	{
		return dynamicClamp;
	}

	/**
	 * Set to true to reduce the clamp factor by a factor of when the direction changes.
	 *
	 * @param dynamicClamp
	 *            the new dynamic clamp
	 */
	public void setDynamicClamp(boolean dynamicClamp)
	{
		this.dynamicClamp = dynamicClamp;
	}

	/**
	 * @return the local search parameter
	 */
	public double getLocalSearch()
	{
		return localSearch;
	}

	/**
	 * When using clamping, if [update * local search parameter] > [initial clamp value] then the search is deemed to be
	 * non-local and lambda is not updated. This preserves the steepest descent search from the previous step.
	 * <p>
	 * Set to zero to disable.
	 * 
	 * @param localSearch
	 *            the local search parameter
	 */
	public void setLocalSearch(double localSearch)
	{
		this.localSearch = localSearch;
	}

	/**
	 * Warning: If the function is changed then the clamp values may require updating. However setting a new function
	 * does not set the clamp values to null to allow caching when the clamp values are unchanged, e.g. evaluation of a
	 * different function in the same parameter space.
	 * <p>
	 * Setting a new function removes the current bounds.
	 * 
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.fitting.nonlinear.NonLinearFit#setNonLinearFunction(gdsc.smlm.function.NonLinearFunction)
	 */
	@Override
	public void setNonLinearFunction(NonLinearFunction func)
	{
		// Do not do this to allow caching
		//setClampValues(null);

		setBounds(null, null);

		super.setNonLinearFunction(func);
	}
}
