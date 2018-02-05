package gdsc.smlm.fitting.nonlinear.gradient;

import gdsc.smlm.function.FastLog;
import gdsc.smlm.function.Gradient1Function;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2017 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

/**
 * Calculates the scaled Hessian matrix (the square matrix of second-order partial derivatives of a function)
 * and the scaled gradient vector of the function's partial first derivatives with respect to the parameters.
 * This is used within the Levenberg-Marquardt method to fit a nonlinear model with coefficients (a) for a
 * set of data points (x, y).
 * <p>
 * This procedure computes a modified Chi-squared expression to perform Maximum Likelihood Estimation assuming Poisson
 * model. See Laurence & Chromy (2010) Efficient maximum likelihood estimator. Nature Methods 7, 338-339. The input data
 * must be Poisson distributed for this to be relevant.
 */
public class FastLogMLELVMGradientProcedure extends MLELVMGradientProcedure
{
	protected final FastLog fastLog;

	/**
	 * Instantiates a new fast log MLELVM gradient procedure.
	 *
	 * @param y
	 *            Data to fit (must be positive)
	 * @param func
	 *            Gradient function
	 * @param fastLog
	 *            the fast log
	 */
	public FastLogMLELVMGradientProcedure(final double[] y, final Gradient1Function func, FastLog fastLog)
	{
		super(y, func);
		if (fastLog == null)
			throw new IllegalArgumentException("FastLog must not be null");
		this.fastLog = fastLog;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.Gradient1Procedure#execute(double, double[])
	 */
	public void execute(double fi, double[] dfi_da)
	{
		++yi;
		// Function must produce a strictly positive output.
		// ---
		// The code provided in Laurence & Chromy (2010) Nature Methods 7, 338-339, SI
		// effectively ignores any function value below zero. This could lead to a 
		// situation where the best chisq value can be achieved by setting the output
		// function to produce 0 for all evaluations.
		// Optimally the function should be bounded to always produce a positive number.
		// ---
		if (fi > 0.0)
		{
			final double xi = y[yi];

			// We assume y[i] is positive but must handle zero
			if (xi > 0.0)
			{
				// We know fi & xi are positive so we can use fast log
				// (i.e. no check for NaN or negatives)
				// The edge case is that positive infinity will return the 
				// value of log(Double.MAX_VALUE).
				// -=-=-=-
				// Note: The fast log function computes a large relative error
				// when the input value is close to 1. Since this means the function
				// fi is close to the data xi it is likely to happen.
				// This method is not recommended and so no further inlined classes
				// have been created.
				// -=-=-=-
				value += (fi - xi - xi * fastLog.fastLog(fi / xi));
				//value += (fi - xi * (1 + fastLog.log(fi / xi)));
				final double xi_fi2 = xi / fi / fi;
				final double e = 1 - (xi / fi);
				for (int k = 0, i = 0; k < n; k++)
				{
					beta[k] -= e * dfi_da[k];
					final double w = dfi_da[k] * xi_fi2;
					for (int l = 0; l <= k; l++)
						alpha[i++] += w * dfi_da[l];
				}
			}
			else
			{
				value += fi;
				for (int k = 0; k < n; k++)
				{
					beta[k] -= dfi_da[k];
				}
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.ValueProcedure#execute(double)
	 */
	public void execute(double fi)
	{
		++yi;
		// Function must produce a strictly positive output.
		if (fi > 0.0)
		{
			final double xi = y[yi];

			// We assume y[i] is positive but must handle zero
			if (xi > 0.0)
			{
				value += (fi - xi - xi * fastLog.log(fi / xi));
				//value += (fi - xi * (1 + fastLog.log(fi / xi)));
			}
			else
			{
				value += fi;
			}
		}
	}
}
