package gdsc.smlm.fitting.nonlinear.gradient;

import java.util.Arrays;

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
 * Calculates the Hessian matrix (the square matrix of second-order partial derivatives of a function)
 * and the scaled gradient vector of the function's partial first derivatives with respect to the parameters.
 * This is used within the Levenberg-Marquardt method to fit a nonlinear model with coefficients (a) for a
 * set of data points (x, y).
 * <p>
 * Note that the Hessian matrix is scaled by 1/2 and the gradient vector is scaled by -1/2 for convenience in solving
 * the non-linear model. See Numerical Recipes in C++, 2nd Ed. Equation 15.5.8 for Nonlinear Models.
 */
public class LSQLVMGradientProcedure extends BaseLSQLVMGradientProcedure
{
	/**
	 * Working space for the scaled Hessian curvature matrix (size n*n)
	 */
	protected final double[] alpha;

	/**
	 * @param y
	 *            Data to fit
	 * @param func
	 *            Gradient function
	 */
	public LSQLVMGradientProcedure(final double[] y, final Gradient1Function func)
	{
		super(y, func);
		alpha = new double[n * (n + 1) / 2];
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.Gradient1Procedure#execute(double, double[])
	 */
	public void execute(double value, double[] dy_da)
	{
		final double dy = y[yi++] - value;

		// Compute:
		// - the scaled Hessian matrix (the square matrix of second-order partial derivatives of a function; 
		//   that is, it describes the local curvature of a function of many variables.)
		// - the scaled gradient vector of the function's partial first derivatives with respect to the parameters

		for (int j = 0, i = 0; j < n; j++)
		{
			final double wgt = dy_da[j];

			for (int k = 0; k <= j; k++)
			{
				//System.out.printf("alpha[%d] += dy_da[%d] * dy_da[%d];\n", i, j, k);
				alpha[i++] += wgt * dy_da[k];
			}
			beta[j] += wgt * dy;
		}
		//if (true) throw new RuntimeException();

		this.value += dy * dy;
	}

	protected void initialiseGradient()
	{
		Arrays.fill(beta, 0);
		Arrays.fill(alpha, 0);
	}

	protected void finishGradient()
	{
		// Do nothing
	}

	protected boolean checkGradients()
	{
		for (int i = 0, len = beta.length; i < len; i++)
			if (Double.isNaN(beta[i]))
				return true;
		for (int i = 0, len = alpha.length; i < len; i++)
			if (Double.isNaN(alpha[i]))
				return true;
		return false;
	}

	@Override
	public void getAlphaMatrix(double[][] alpha)
	{
		GradientProcedureHelper.getMatrix(this.alpha, alpha, n);
	}

	@Override
	public void getAlphaLinear(double[] alpha)
	{
		GradientProcedureHelper.getMatrix(this.alpha, alpha, n);
	}
}
