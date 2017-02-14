package gdsc.smlm.fitting.nonlinear.gradient;

import gdsc.smlm.function.NonLinearFunction;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2013 Alex Herbert
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
 * and the gradient vector of the function's partial first derivatives with respect to the parameters.
 * This is used within the Levenberg-Marquardt method to fit a nonlinear model with coefficients (a) for a
 * set of data points (x, y).
 */
public class GradientCalculator
{
	public final int nparams;
	private boolean badGradients;

	/**
	 * @param nparams
	 *            The number of gradient parameters
	 */
	public GradientCalculator(final int nparams)
	{
		this.nparams = nparams;
	}

	/**
	 * Evaluate the function and compute the sum-of-squares and the curvature matrix.
	 * <p>
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * 
	 * @param x
	 *            n observations
	 * @param y
	 *            Data to fit
	 * @param a
	 *            Set of m coefficients
	 * @param alpha
	 *            the Hessian curvature matrix (size m*m)
	 * @param beta
	 *            the gradient vector of the function's partial first derivatives with respect to the parameters (size
	 *            m)
	 * @param func
	 *            Non-linear fitting function
	 * @return The sum-of-squares value for the fit
	 */
	public double findLinearised(final int[] x, final double[] y, final double[] a, final double[][] alpha,
			final double[] beta, final NonLinearFunction func)
	{
		double ssx = 0;
		final double[] dy_da = new double[a.length];

		for (int i = 0; i < nparams; i++)
		{
			beta[i] = 0;
			for (int j = 0; j <= i; j++)
				alpha[i][j] = 0;
		}

		func.initialise(a);

		if (func.canComputeWeights())
		{
			final double[] w = new double[1];
			for (int i = 0; i < x.length; i++)
			{
				final double dy = y[i] - func.eval(x[i], dy_da, w);
				final double weight = getWeight(w[0]);

				// Compute:
				// - the Hessian matrix (the square matrix of second-order partial derivatives of a function; 
				//   that is, it describes the local curvature of a function of many variables.)
				// - the gradient vector of the function's partial first derivatives with respect to the parameters

				for (int j = 0; j < nparams; j++)
				{
					final double wgt = dy_da[j] * weight;

					for (int k = 0; k <= j; k++)
						alpha[j][k] += wgt * dy_da[k];

					beta[j] += wgt * dy;
				}

				ssx += dy * dy * weight;
			}
		}
		else
		{
			for (int i = 0; i < x.length; i++)
			{
				final double dy = y[i] - func.eval(x[i], dy_da);

				// Compute:
				// - the Hessian matrix (the square matrix of second-order partial derivatives of a function; 
				//   that is, it describes the local curvature of a function of many variables.)
				// - the gradient vector of the function's partial first derivatives with respect to the parameters

				for (int j = 0; j < nparams; j++)
				{
					final double wgt = dy_da[j];

					for (int k = 0; k <= j; k++)
						alpha[j][k] += wgt * dy_da[k];

					beta[j] += wgt * dy;
				}

				ssx += dy * dy;
			}
		}

		// Generate symmetric matrix
		for (int i = 0; i < nparams - 1; i++)
			for (int j = i + 1; j < nparams; j++)
				alpha[i][j] = alpha[j][i];

		return checkGradients(alpha, beta, nparams, ssx);
	}

	/**
	 * Evaluate the function and compute the sum-of-squares and the curvature matrix.
	 * <p>
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * <p>
	 * Allows disabling the use of gradients. The output alpha and beta will be reduced in size by the number of indices
	 * 
	 * @param x
	 *            n observations
	 * @param y
	 *            Data to fit
	 * @param a
	 *            Set of m coefficients
	 * @param alpha
	 *            the Hessian curvature matrix (size m*m)
	 * @param beta
	 *            the gradient vector of the function's partial first derivatives with respect to the parameters (size
	 *            m)
	 * @param func
	 *            Non-linear fitting function
	 * @param ignore
	 *            An array of size beta.length. Set the index to true to ignore the gradients.
	 * @return The sum-of-squares value for the fit
	 */
	public double findLinearised(final int[] x, final double[] y, final double[] a, final double[][] alpha,
			final double[] beta, final NonLinearFunction func, boolean[] ignore)
	{
		double ssx = 0;
		final double[] dy_da = new double[a.length];

		for (int i = 0; i < nparams; i++)
		{
			beta[i] = 0;
			for (int j = 0; j <= i; j++)
				alpha[i][j] = 0;
		}

		func.initialise(a);

		int[] indices = new int[nparams];
		int nnparams = 0;
		for (int j = 0; j < nparams; j++)
		{
			if (ignore[j])
				continue;
			indices[nnparams++] = j;
		}

		if (func.canComputeWeights())
		{
			final double[] w = new double[1];
			for (int i = 0; i < x.length; i++)
			{
				final double dy = y[i] - func.eval(x[i], dy_da, w);
				final double weight = getWeight(w[0]);

				// Compute:
				// - the Hessian matrix (the square matrix of second-order partial derivatives of a function; 
				//   that is, it describes the local curvature of a function of many variables.)
				// - the gradient vector of the function's partial first derivatives with respect to the parameters

				for (int j = 0; j < nnparams; j++)
				{
					final double wgt = dy_da[indices[j]] * weight;

					for (int k = 0; k <= j; k++)
						alpha[j][k] += wgt * dy_da[indices[k]];

					beta[j] += wgt * dy;
				}

				ssx += dy * dy * weight;
			}
		}
		else
		{
			for (int i = 0; i < x.length; i++)
			{
				final double dy = y[i] - func.eval(x[i], dy_da);

				// Compute:
				// - the Hessian matrix (the square matrix of second-order partial derivatives of a function; 
				//   that is, it describes the local curvature of a function of many variables.)
				// - the gradient vector of the function's partial first derivatives with respect to the parameters

				for (int j = 0; j < nnparams; j++)
				{
					final double wgt = dy_da[indices[j]];

					for (int k = 0; k <= j; k++)
						alpha[j][k] += wgt * dy_da[indices[k]];

					beta[j] += wgt * dy;
				}

				ssx += dy * dy;
			}
		}

		// Generate symmetric matrix
		for (int i = 0; i < nparams - 1; i++)
			for (int j = i + 1; j < nparams; j++)
				alpha[i][j] = alpha[j][i];

		return checkGradients(alpha, beta, nparams, ssx);
	}

	/**
	 * Evaluate the function and compute the sum-of-squares
	 * 
	 * @param x
	 *            n observations
	 * @param y
	 *            The data
	 * @param y_fit
	 *            The function data
	 * @param a
	 *            Set of m coefficients
	 * @param func
	 *            Non-linear fitting function
	 * @return The sum-of-squares value for the fit
	 */
	public double findLinearised(final int[] x, final double[] y, double[] y_fit, final double[] a,
			final NonLinearFunction func)
	{
		double ssx = 0;

		func.initialise(a);

		if (y_fit == null || y_fit.length < x.length)
		{
			if (func.canComputeWeights())
			{
				final double[] w = new double[1];
				for (int i = 0; i < x.length; i++)
				{
					final double dy = y[i] - func.evalw(x[i], w);
					final double weight = getWeight(w[0]);
					ssx += dy * dy * weight;
				}
			}
			else
			{
				for (int i = 0; i < x.length; i++)
				{
					final double dy = y[i] - func.eval(x[i]);
					ssx += dy * dy;
				}
			}
		}
		else
		{
			if (func.canComputeWeights())
			{
				final double[] w = new double[1];
				for (int i = 0; i < x.length; i++)
				{
					y_fit[i] = func.evalw(x[i], w);
					final double dy = y[i] - y_fit[i];
					final double weight = getWeight(w[0]);
					ssx += dy * dy * weight;
				}
			}
			else
			{
				for (int i = 0; i < x.length; i++)
				{
					y_fit[i] = func.eval(x[i]);
					final double dy = y[i] - y_fit[i];
					ssx += dy * dy;
				}
			}
		}

		return ssx;
	}

	/**
	 * Evaluate the function and compute the sum-of-squares and the curvature matrix.
	 * Assumes the n observations (x) are sequential integers from 0.
	 * <p>
	 * If the function supports weights then these will be used to compute the SS and curvature matrix.
	 * <p>
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * 
	 * @param n
	 *            The number of data points
	 * @param y
	 *            Data to fit
	 * @param a
	 *            Set of m coefficients
	 * @param alpha
	 *            the Hessian curvature matrix (size m*m)
	 * @param beta
	 *            the gradient vector of the function's partial first derivatives with respect to the parameters (size
	 *            m)
	 * @param func
	 *            Non-linear fitting function
	 * @see {@link gdsc.smlm.function.NonLinearFunction#eval(int, double[])},
	 * @see {@link gdsc.smlm.function.NonLinearFunction#eval(int, double[], double[])},
	 * @see {@link gdsc.smlm.function.NonLinearFunction#canComputeWeights()}
	 * @return The sum-of-squares value for the fit.
	 */
	public double findLinearised(final int n, final double[] y, final double[] a, final double[][] alpha,
			final double[] beta, final NonLinearFunction func)
	{
		double ssx = 0;
		final double[] dy_da = new double[a.length];

		for (int i = 0; i < nparams; i++)
		{
			beta[i] = 0;
			for (int j = 0; j <= i; j++)
				alpha[i][j] = 0;
		}

		func.initialise(a);

		if (func.canComputeWeights())
		{
			final double[] w = new double[1];
			for (int i = 0; i < n; i++)
			{
				final double dy = y[i] - func.eval(i, dy_da, w);
				final double weight = getWeight(w[0]);

				// Compute:
				// - the Hessian matrix (the square matrix of second-order partial derivatives of a function; 
				//   that is, it describes the local curvature of a function of many variables.)
				// - the gradient vector of the function's partial first derivatives with respect to the parameters

				for (int j = 0; j < nparams; j++)
				{
					final double wgt = dy_da[j] * weight;

					for (int k = 0; k <= j; k++)
						alpha[j][k] += wgt * dy_da[k];

					beta[j] += wgt * dy;
				}

				ssx += dy * dy * weight;
			}
		}
		else
		{
			for (int i = 0; i < n; i++)
			{
				final double dy = y[i] - func.eval(i, dy_da);

				// Compute:
				// - the Hessian matrix (the square matrix of second-order partial derivatives of a function; 
				//   that is, it describes the local curvature of a function of many variables.)
				// - the gradient vector of the function's partial first derivatives with respect to the parameters

				for (int j = 0; j < nparams; j++)
				{
					final double wgt = dy_da[j];

					for (int k = 0; k <= j; k++)
						alpha[j][k] += wgt * dy_da[k];

					beta[j] += wgt * dy;
				}

				ssx += dy * dy;
			}
		}

		// Generate symmetric matrix
		for (int i = 0; i < nparams - 1; i++)
			for (int j = i + 1; j < nparams; j++)
				alpha[i][j] = alpha[j][i];

		return checkGradients(alpha, beta, nparams, ssx);
	}

	/**
	 * Evaluate the function and compute the sum-of-squares and the curvature matrix.
	 * Assumes the n observations (x) are sequential integers from 0.
	 * <p>
	 * If the function supports weights then these will be used to compute the SS and curvature matrix.
	 * <p>
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * <p>
	 * Allows disabling the use of gradients. The output alpha and beta will be reduced in size by the number of indices
	 * that were disabled. The remaining positions will be zero filled.
	 *
	 * @param n
	 *            The number of data points
	 * @param y
	 *            Data to fit
	 * @param a
	 *            Set of m coefficients
	 * @param alpha
	 *            the Hessian curvature matrix (size m*m)
	 * @param beta
	 *            the gradient vector of the function's partial first derivatives with respect to the parameters (size
	 *            m)
	 * @param func
	 *            Non-linear fitting function
	 * @param ignore
	 *            An array of size beta.length. Set the index to true to ignore the gradients.
	 * @return The sum-of-squares value for the fit.
	 * @see {@link gdsc.smlm.function.NonLinearFunction#eval(int, double[])},
	 * @see {@link gdsc.smlm.function.NonLinearFunction#eval(int, double[], double[])},
	 * @see {@link gdsc.smlm.function.NonLinearFunction#canComputeWeights()}
	 */
	public double findLinearised(final int n, final double[] y, final double[] a, final double[][] alpha,
			final double[] beta, final NonLinearFunction func, boolean[] ignore)
	{
		double ssx = 0;
		final double[] dy_da = new double[a.length];

		for (int i = 0; i < nparams; i++)
		{
			beta[i] = 0;
			for (int j = 0; j <= i; j++)
				alpha[i][j] = 0;
		}

		func.initialise(a);

		int[] indices = new int[nparams];
		int nnparams = 0;
		for (int j = 0; j < nparams; j++)
		{
			if (ignore[j])
				continue;
			indices[nnparams++] = j;
		}
		
		if (func.canComputeWeights())
		{
			final double[] w = new double[1];
			for (int i = 0; i < n; i++)
			{
				final double dy = y[i] - func.eval(i, dy_da, w);
				final double weight = getWeight(w[0]);

				// Compute:
				// - the Hessian matrix (the square matrix of second-order partial derivatives of a function; 
				//   that is, it describes the local curvature of a function of many variables.)
				// - the gradient vector of the function's partial first derivatives with respect to the parameters

				for (int j = 0; j < nnparams; j++)
				{
					final double wgt = dy_da[indices[j]] * weight;

					for (int k = 0; k <= j; k++)
						alpha[j][k] += wgt * dy_da[indices[k]];

					beta[j] += wgt * dy;
				}

				ssx += dy * dy * weight;
			}
		}
		else
		{
			for (int i = 0; i < n; i++)
			{
				final double dy = y[i] - func.eval(i, dy_da);

				// Compute:
				// - the Hessian matrix (the square matrix of second-order partial derivatives of a function; 
				//   that is, it describes the local curvature of a function of many variables.)
				// - the gradient vector of the function's partial first derivatives with respect to the parameters

				for (int j = 0; j < nnparams; j++)
				{
					final double wgt = dy_da[indices[j]];

					for (int k = 0; k <= j; k++)
						alpha[j][k] += wgt * dy_da[indices[k]];

					beta[j] += wgt * dy;
				}

				ssx += dy * dy;
			}
		}

		// Generate symmetric matrix
		for (int i = 0; i < nparams - 1; i++)
			for (int j = i + 1; j < nparams; j++)
				alpha[i][j] = alpha[j][i];

		return checkGradients(alpha, beta, nparams, ssx);
	}

	/**
	 * Evaluate the function and compute the sum-of-squares
	 * 
	 * @param n
	 *            The number of data points
	 * @param y
	 *            The data
	 * @param y_fit
	 *            The function data
	 * @param a
	 *            Set of m coefficients
	 * @param func
	 *            Non-linear fitting function
	 * @return The sum-of-squares value for the fit
	 */
	public double findLinearised(final int n, final double[] y, double[] y_fit, final double[] a,
			final NonLinearFunction func)
	{
		double ssx = 0;

		func.initialise(a);

		if (y_fit == null || y_fit.length < n)
		{
			if (func.canComputeWeights())
			{
				final double[] w = new double[1];
				for (int i = 0; i < n; i++)
				{
					final double dy = y[i] - func.evalw(i, w);
					final double weight = getWeight(w[0]);
					ssx += dy * dy * weight;
				}
			}
			else
			{
				for (int i = 0; i < n; i++)
				{
					final double dy = y[i] - func.eval(i);
					ssx += dy * dy;
				}
			}
		}
		else
		{
			if (func.canComputeWeights())
			{
				final double[] w = new double[1];
				for (int i = 0; i < n; i++)
				{
					y_fit[i] = func.evalw(i, w);
					final double dy = y[i] - y_fit[i];
					final double weight = getWeight(w[0]);
					ssx += dy * dy * weight;
				}
			}
			else
			{
				for (int i = 0; i < n; i++)
				{
					y_fit[i] = func.eval(i);
					final double dy = y[i] - y_fit[i];
					ssx += dy * dy;
				}
			}
		}

		return ssx;
	}

	/**
	 * Get the weight factor using the computed weight
	 * <p>
	 * Check if the weight is below 1 and set to 1 to avoid excessive weights.
	 * 
	 * @param w
	 *            The computed weight
	 * @return The weight factor
	 */
	protected double getWeight(final double w)
	{
		// TODO - Check if there is a better way to smooth the weights rather than just truncating them at 1
		return (w < 1) ? 1 : 1.0 / w;
	}

	protected double checkGradients(final double[][] alpha, final double[] beta, int nparams, final double ssx)
	{
		badGradients = checkIsNaN(alpha, beta, nparams);
		return ssx;
	}

	private boolean checkIsNaN(final double[][] alpha, final double[] beta, final int nparams)
	{
		for (int i = 0; i < nparams; i++)
		{
			if (Double.isNaN(beta[i]))
				return true;
			for (int j = 0; j <= i; j++)
				if (Double.isNaN(alpha[i][j]))
					return true;
		}

		return false;
	}

	protected void checkGradients(double[][] alpha, int nparams)
	{
		badGradients = checkIsNaN(alpha, nparams);
	}

	private boolean checkIsNaN(final double[][] alpha, final int nparams)
	{
		for (int i = 0; i < nparams; i++)
		{
			for (int j = 0; j <= i; j++)
				if (Double.isNaN(alpha[i][j]))
					return true;
		}

		return false;
	}

	protected void checkGradients(final double[] beta, final int nparams)
	{
		badGradients = checkIsNaN(beta, nparams);
	}

	private boolean checkIsNaN(final double[] beta, final int nparams)
	{
		for (int i = 0; i < nparams; i++)
		{
			if (Double.isNaN(beta[i]))
				return true;
		}

		return false;
	}

	/**
	 * @return True if the last calculation produced gradients with NaN values
	 */
	public boolean isNaNGradients()
	{
		return badGradients;
	}

	/**
	 * Compute Fisher's Information Matrix (I).
	 * 
	 * <pre>
	 * Iab = sum(i) (dYi da) * (dYi db) / Yi
	 * </pre>
	 * 
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * 
	 * @param x
	 *            n observations
	 * @param a
	 *            Set of m coefficients
	 * @param func
	 *            Non-linear fitting function
	 * @return I
	 */
	public double[][] fisherInformationMatrix(int[] x, final double[] a, final NonLinearFunction func)
	{
		return fisherInformationMatrix(x.length, a, func);
	}

	/**
	 * Compute Fisher's Information Matrix (I).
	 * 
	 * <pre>
	 * Iab = sum(i) (dYi da) * (dYi db) / Yi
	 * </pre>
	 * 
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * 
	 * @param n
	 *            The number of data points
	 * @param a
	 *            Set of m coefficients
	 * @param func
	 *            Non-linear fitting function
	 * @return I
	 */
	public double[][] fisherInformationMatrix(final int n, final double[] a, final NonLinearFunction func)
	{
		final double[] dy_da = new double[a.length];

		final double[][] alpha = new double[nparams][nparams];

		func.initialise(a);

		for (int i = 0; i < n; i++)
		{
			final double yi = 1.0 / func.eval(i, dy_da);

			for (int j = 0; j < nparams; j++)
			{
				final double dy_db = dy_da[j] * yi;

				for (int k = 0; k <= j; k++)
					alpha[j][k] += dy_db * dy_da[k];
			}
		}

		// Generate symmetric matrix
		for (int i = 0; i < nparams - 1; i++)
			for (int j = i + 1; j < nparams; j++)
				alpha[i][j] = alpha[j][i];

		checkGradients(alpha, nparams);
		return alpha;
	}

	/**
	 * Compute the central diagonal of Fisher's Information Matrix (I).
	 * 
	 * <pre>
	 * Iaa = sum(i) (dYi da) * (dYi da) / Yi
	 * </pre>
	 * 
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * 
	 * @param x
	 *            n observations
	 * @param a
	 *            Set of m coefficients
	 * @param func
	 *            Non-linear fitting function
	 * @return Iaa
	 */
	public double[] fisherInformationDiagonal(final int[] x, final double[] a, final NonLinearFunction func)
	{
		return fisherInformationDiagonal(x.length, a, func);
	}

	/**
	 * Compute the central diagonal of Fisher's Information Matrix (I).
	 * 
	 * <pre>
	 * Iaa = sum(i) (dYi da) * (dYi da) / Yi
	 * </pre>
	 * 
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * 
	 * @param n
	 *            The number of data points
	 * @param a
	 *            Set of m coefficients
	 * @param func
	 *            Non-linear fitting function
	 * @return Iaa
	 */
	public double[] fisherInformationDiagonal(final int n, final double[] a, final NonLinearFunction func)
	{
		final double[] dy_da = new double[a.length];

		final double[] alpha = new double[nparams];

		func.initialise(a);

		for (int i = 0; i < n; i++)
		{
			final double yi = 1.0 / func.eval(i, dy_da);
			for (int j = 0; j < nparams; j++)
			{
				alpha[j] += dy_da[j] * dy_da[j] * yi;
			}
		}

		checkGradients(alpha, nparams);
		return alpha;
	}

	/**
	 * Evaluate the function and compute the sum-of-squares and the gradient with respect to the model
	 * parameters.
	 * <p>
	 * A call to {@link #isNaNGradients()} will indicate if the gradients were invalid.
	 * 
	 * @param x
	 *            n observations
	 * @param y
	 *            Data to fit
	 * @param a
	 *            Set of m coefficients
	 * @param df_da
	 *            the gradient vector of the function's partial first derivatives with respect to the parameters (size
	 *            m)
	 * @param func
	 *            Non-linear fitting function
	 * @return The sum-of-squares value for the fit
	 */
	public double evaluate(final int[] x, final double[] y, final double[] a, final double[] df_da,
			final NonLinearFunction func)
	{
		double ssx = 0;
		final double[] dy_da = new double[nparams];

		zero(df_da);

		func.initialise(a);

		for (int i = 0; i < x.length; i++)
		{
			final double dy = y[i] - func.eval(x[i], dy_da);

			// Compute:
			// - the gradient vector of the function's partial first derivatives with respect to the parameters

			for (int j = 0; j < nparams; j++)
			{
				df_da[j] += dy_da[j] * dy;
			}

			ssx += dy * dy;
		}

		checkGradients(df_da, nparams);
		
		// Apply a factor of -2 to the gradients:
		// See Numerical Recipes in C++, 2nd Ed. Equation 15.5.6 for Nonlinear Models 
		for (int j = 0; j < nparams; j++)
			df_da[j] *= -2;

		return ssx;
	}

	/**
	 * Zero the working region of the input matrix alpha and vector beta
	 *
	 * @param beta
	 *            the beta
	 */
	protected void zero(final double[] beta)
	{
		for (int i = 0; i < nparams; i++)
		{
			beta[i] = 0;
		}
	}
}
