package gdsc.smlm.fitting.nonlinear;

import java.util.Arrays;

import gdsc.core.utils.DoubleEquality;
import gdsc.smlm.fitting.FisherInformationMatrix;
import gdsc.smlm.fitting.FitStatus;
import gdsc.smlm.fitting.linear.EJMLLinearSolver;
import gdsc.smlm.fitting.nonlinear.gradient.GradientCalculator;
import gdsc.smlm.fitting.nonlinear.gradient.GradientCalculatorFactory;
import gdsc.smlm.fitting.nonlinear.stop.ErrorStoppingCriteria;
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
 * 
 * This is an adaption of the C-code contained in the CcpNmr Analysis Program:
 *   CCPN website (http://www.ccpn.ac.uk/). 
 * The CCPN code was based on Numerical Recipes. 
 *---------------------------------------------------------------------------*/

/**
 * Uses Levenberg-Marquardt method to fit a nonlinear model with coefficients (a) for a
 * set of data points (x, y).
 */
public class NonLinearFit extends BaseFunctionSolver
{
	protected static final int SUM_OF_SQUARES_BEST = 0;
	protected static final int SUM_OF_SQUARES_OLD = 1;
	protected static final int SUM_OF_SQUARES_NEW = 2;

	protected EJMLLinearSolver solver = new EJMLLinearSolver();
	protected GradientCalculator calculator;
	protected StoppingCriteria sc;

	protected double[] beta = new double[0];
	protected double[] da;
	protected double[] ap = new double[0];

	protected double[][] covar;
	protected double[][] alpha;
	protected double initialLambda = 0.01;
	protected double lambda;
	protected double[] sumOfSquaresWorking;

	protected double initialResidualSumOfSquares;

	private boolean mle = false;

	/**
	 * Default constructor
	 * 
	 * @param func
	 *            The function to fit
	 */
	public NonLinearFit(NonLinearFunction func)
	{
		this(func, null);
	}

	/**
	 * Default constructor
	 * 
	 * @param func
	 *            The function to fit
	 * @param sc
	 *            The stopping criteria
	 */
	public NonLinearFit(NonLinearFunction func, StoppingCriteria sc)
	{
		this(func, sc, 3, 1e-10);
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
	public NonLinearFit(NonLinearFunction func, StoppingCriteria sc, int significantDigits, double maxAbsoluteError)
	{
		super(func);
		init(sc, significantDigits, maxAbsoluteError);
	}

	private void init(StoppingCriteria sc, int significantDigits, double maxAbsoluteError)
	{
		setStoppingCriteria(sc);
		solver.setEqual(new DoubleEquality(significantDigits, maxAbsoluteError));
	}

	protected boolean nonLinearModel(int n, double[] y, double[] a, boolean initialStage)
	{
		// The NonLinearFunction evaluates a function with parameters a but only computes the gradient
		// for m <= a.length parameters. The parameters can be accessed using the gradientIndices() method.  

		final int[] gradientIndices = f.gradientIndices();
		final int m = gradientIndices.length;

		if (initialStage)
		{
			lambda = initialLambda;
			for (int j = a.length; j-- > 0;)
				ap[j] = a[j];
			sumOfSquaresWorking[SUM_OF_SQUARES_BEST] = calculator.findLinearised(n, y, a, alpha, beta, f);
			initialResidualSumOfSquares = sumOfSquaresWorking[SUM_OF_SQUARES_BEST];
			if (calculator.isNaNGradients())
			{
				//System.out.println("Bad initial gradients");
				return false;
			}
		}

		// Set previous using the current best fit result we have
		sumOfSquaresWorking[SUM_OF_SQUARES_OLD] = sumOfSquaresWorking[SUM_OF_SQUARES_BEST];

		// Solve the gradient equation A x = b:
		// A = Hessian matrix (alpha)
		// x = Parameter shift (output da) 
		// b = Gradient vector (beta)
		if (!solve(a, m))
			return false;

		// Update the parameters. Ensure to use the gradient indices to update the correct parameters
		updateFitParameters(a, gradientIndices, m, da, ap);

		sumOfSquaresWorking[SUM_OF_SQUARES_NEW] = calculator.findLinearised(n, y, ap, covar, da, f);

		if (calculator.isNaNGradients())
		{
			//System.out.println("Bad working gradients");
			return false; // Stop now
			//lambda *= 10.0; // Allow to continue
		}
		else if (sumOfSquaresWorking[SUM_OF_SQUARES_NEW] < sumOfSquaresWorking[SUM_OF_SQUARES_OLD])
		{
			accepted(a, ap, m);
		}
		else
		{
			increaseLambda();
		}

		return true;
	}

	/**
	 * Called when there was a successful improvement of the fit. The lambda parameter should be reduced.
	 *
	 * @param a
	 *            The old fit parameters
	 * @param ap
	 *            The new fit parameters
	 * @param m
	 *            the number of fitted parameters (matches gradient indicies length)
	 */
	protected void accepted(double[] a, double[] ap, int m)
	{
		decreaseLambda();

		for (int i = 0; i < m; i++)
			for (int j = m; j-- > 0;)
				alpha[i][j] = covar[i][j];

		for (int j = m; j-- > 0;)
		{
			beta[j] = da[j];
		}
		for (int j = a.length; j-- > 0;)
		{
			a[j] = ap[j];
		}
		sumOfSquaresWorking[SUM_OF_SQUARES_BEST] = sumOfSquaresWorking[SUM_OF_SQUARES_NEW];
	}

	protected void decreaseLambda()
	{
		lambda *= 0.1;
	}

	protected void increaseLambda()
	{
		lambda *= 10.0;
	}

	/**
	 * Solve the gradient equation A x = b: *
	 * 
	 * <pre>
	 * A = Hessian matrix (alpha)
	 * x = Parameter shift (output da)
	 * b = Gradient vector (beta)
	 * </pre>
	 * 
	 * The Hessian and gradient parameter from the current best scoring parameter set are assumed to be in alpha and
	 * beta. The lambda parameter is used to weight the diagonal of the Hessian.
	 *
	 * @param a
	 *            the current fit parameters
	 * @param m
	 *            the number of fit parameters
	 * @return true, if successful
	 */
	protected boolean solve(double[] a, final int m)
	{
		createLinearProblem(m);
		return solve(covar, da);
	}

	/**
	 * Creates the linear problem.
	 * <p>
	 * The Hessian and gradient parameter from the current best scoring parameter set are assumed to be in alpha and
	 * beta. These are copied into the working variables covar and da. The lambda parameter is used to weight the
	 * diagonal of the Hessian.
	 *
	 * @param m
	 *            the number of fit parameters
	 */
	protected void createLinearProblem(final int m)
	{
		for (int i = m; i-- > 0;)
		{
			da[i] = beta[i];
			for (int j = m; j-- > 0;)
				covar[i][j] = alpha[i][j];
			covar[i][i] *= (1 + lambda);
		}
	}

	/**
	 * Solves (one) linear equation, a x = b
	 * <p>
	 * On input have a[n][n], b[n]. On output b replaced by x[n].
	 * <p>
	 * Note: Any zero elements in b are not solved.
	 * 
	 * @return False if the equation is singular (no solution)
	 */
	protected boolean solve(double[][] a, double[] b)
	{
		// If the gradient vector is very small set to zero so that this is ignored.

		// TODO - At what level should gradients be ignored (i.e. the parameter has no effect?).
		// Note that analysis on a test dataset showed no difference in results. Those that are caught 
		// for bad gradients must therefore go on to fail on peak filtering criteria. At least this
		// gives the option of not filtering.
		for (int i = b.length; i-- > 0;)
			if (Math.abs(b[i]) < 1e-16)
				b[i] = 0;

		// TODO
		// Q. Do we need a better qr decomposition that uses the largest Eigen column first. 
		// There is a version from Apache commons math.
		// We could assess the magnitude of each value in the gradient vector and rearrange.

		return solver.solveWithZeros(a, b);
	}

	/**
	 * Update the fit parameters. Note that not all parameters are fit and therefore the gradients indices are used to
	 * map the fit parameters to the parameters array.
	 * <p>
	 * This method can be overridden to provide bounded update to the parameters.
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
	 */
	protected void updateFitParameters(double[] a, int[] gradientIndices, int m, double[] da, double[] ap)
	{
		for (int j = m; j-- > 0;)
			ap[gradientIndices[j]] = a[gradientIndices[j]] + da[j];
	}

	private FitStatus doFit(int n, double[] y, double[] y_fit, double[] a, double[] a_dev, double[] error,
			StoppingCriteria sc, double noise)
	{
		final int[] gradientIndices = f.gradientIndices();

		sc.initialise(a);
		if (!nonLinearModel(n, y, a, true))
			return (calculator.isNaNGradients()) ? FitStatus.INVALID_GRADIENTS_IN_NON_LINEAR_MODEL
					: FitStatus.SINGULAR_NON_LINEAR_MODEL;
		sc.evaluate(sumOfSquaresWorking[SUM_OF_SQUARES_OLD], sumOfSquaresWorking[SUM_OF_SQUARES_NEW], a);

		while (sc.areNotSatisfied())
		{
			if (!nonLinearModel(n, y, a, false))
				return (calculator.isNaNGradients()) ? FitStatus.INVALID_GRADIENTS_IN_NON_LINEAR_MODEL
						: FitStatus.SINGULAR_NON_LINEAR_MODEL;

			sc.evaluate(sumOfSquaresWorking[SUM_OF_SQUARES_OLD], sumOfSquaresWorking[SUM_OF_SQUARES_NEW], a);
		}

		if (!sc.areAchieved())
		{
			if (sc.getIteration() >= sc.getMaximumIterations())
				return FitStatus.TOO_MANY_ITERATIONS;
			return FitStatus.FAILED_TO_CONVERGE;
		}

		if (a_dev != null)
		{
			if (!computeDeviations(a_dev))
			{
				// Matrix inversion failed. In order to return a solution 
				// return the reciprocal of the diagonal of the Fisher information 
				// for a loose bound on the limit 
				final double[] I = calculator.fisherInformationDiagonal(n, a, f);
				Arrays.fill(a_dev, 0);
				for (int i = gradientIndices.length; i-- > 0;)
					a_dev[gradientIndices[i]] = FisherInformationMatrix.reciprocalSqrt(I[i]);
			}
		}

		value = sumOfSquaresWorking[SUM_OF_SQUARES_BEST];

		// Compute fitted data points
		if (y_fit != null)
		{
			for (int i = 0; i < n; i++)
				y_fit[i] = f.eval(i);
		}

		// Weighted SS is not the correct sum-of-squares.
		// The MLE did not calculate the sum-of-squares.
		residualSumOfSquares = (mle || f.canComputeWeights()) ? computeSS(y, y_fit, n) : value;

		error[0] = getError(residualSumOfSquares, noise, n, gradientIndices.length);

		return FitStatus.OK;
	}

	/**
	 * Compute the parameter deviations using the covariance matrix of the solution
	 *
	 * @param a_dev
	 *            the a dev
	 * @return true, if successful
	 */
	private boolean computeDeviations(double[] a_dev)
	{
		// This is used to calculate the parameter covariance matrix.
		// Solve the gradient matrix corresponding to the best Chi-squared 
		// stored in alpha and beta. 
		// Do not use the solve() method as this sets beta to zero for small values
		// thus preventing inversion.
		if (!solver.solveWithZeros(alpha, beta))
			return false;

		if (!solver.invert(covar))
			return false;

		setDeviations(a_dev, covar);

		return true;
	}

	private double computeSS(double[] y, double[] y_fit, int n)
	{
		double ss = 0;
		if (y_fit != null)
		{
			// Compute using the output fit data
			for (int i = 0; i < n; i++)
			{
				final double residual = y[i] - y_fit[i];
				ss += residual * residual;
			}
		}
		else
		{
			// Compute again using the function. Not very expensive as we do not need the gradients.
			// Note: We could change fitting to always store the current fit data. 
			for (int i = 0; i < n; i++)
			{
				final double residual = y[i] - f.eval(i);
				ss += residual * residual;
			}
		}
		return ss;
	}

	/**
	 * Uses Levenberg-Marquardt method to fit a nonlinear model with coefficients (a) for a
	 * set of data points (x, y).
	 * <p>
	 * It is assumed that the data points x[i] corresponding to y[i] are consecutive integers from zero.
	 * 
	 * @param n
	 *            The number of points to fit, n <= y.length (allows input data y to be used as a buffer)
	 * @param y
	 *            Set of n data points to fit (input)
	 * @param y_fit
	 *            Fitted data points (output)
	 * @param a
	 *            Set of m coefficients (input/output)
	 * @param a_dev
	 *            Standard deviation of the set of m coefficients (output)
	 * @param error
	 *            Output parameter. The Mean-Squared Error (MSE) for the fit if noise is 0. If noise is provided then
	 *            this will be applied to create a reduced chi-square measure.
	 * @param noise
	 *            Estimate of the noise in the individual measurements
	 * @return The fit status
	 */
	public FitStatus computeFit(final int n, double[] y, final double[] y_fit, final double[] a, final double[] a_dev,
			final double[] error, final double noise)
	{
		final int nparams = f.gradientIndices().length;

		// Create dynamically for the parameter sizes
		calculator = GradientCalculatorFactory.newCalculator(nparams, mle);

		// Initialise storage. 
		// Note that covar and da are passed to EJMLLinerSolver and so must be the correct size. 
		beta = new double[nparams];
		da = new double[nparams];
		covar = new double[nparams][nparams];
		alpha = new double[nparams][nparams];
		ap = new double[a.length];

		// Store the { best, previous, new } sum-of-squares values 
		sumOfSquaresWorking = new double[3];

		if (mle)
			// We must have positive data
			y = ensurePositive(n, y);

		final FitStatus result = doFit(n, y, y_fit, a, a_dev, error, sc, noise);
		this.evaluations = this.iterations = sc.getIteration();

		return result;
	}

	private static double[] ensurePositive(int n, double[] y)
	{
		if (hasNegatives(n, y))
		{
			final double[] y2 = new double[n];
			for (int i = n; i-- > 0;)
			{
				if (y[i] < 0)
					y2[i] = 0;
				else
					y2[i] = y[i];
			}
			y = y2;
		}
		return y;
	}

	private static boolean hasNegatives(int n, final double[] y)
	{
		for (int i = n; i-- > 0;)
			if (y[i] < 0)
				return true;
		return false;
	}

	/**
	 * Used for debugging
	 * 
	 * @param format
	 * @param o
	 */
	void printf(String format, Object... o)
	{
		System.out.printf(format, o);
	}

	/**
	 * @param initialLambda
	 *            the initial lambda for the Levenberg-Marquardt fitting routine
	 */
	public void setInitialLambda(double initialLambda)
	{
		this.initialLambda = initialLambda;
	}

	/**
	 * @return the initialLambda
	 */
	public double getInitialLambda()
	{
		return initialLambda;
	}

	/**
	 * @return the initialResidualSumOfSquares
	 */
	public double getInitialResidualSumOfSquares()
	{
		return initialResidualSumOfSquares;
	}

	/**
	 * Set the non-linear function for the {@link #fit(int, double[], double[], double[], double[], double[], double)}
	 * method
	 * 
	 * @param sc
	 */
	public void setNonLinearFunction(NonLinearFunction func)
	{
		if (func != null)
			this.f = func;
	}

	/**
	 * Set the stopping criteria for the {@link #fit(int, double[], double[], double[], double[], double[], double)}
	 * method
	 * 
	 * @param sc
	 */
	public void setStoppingCriteria(StoppingCriteria sc)
	{
		if (sc == null)
			sc = new ErrorStoppingCriteria();
		this.sc = sc;
	}

	/**
	 * Checks if set to perform Maximum Likelihood Estimation assuming Poisson model.
	 *
	 * @return true if is set to perform MLE
	 */
	public boolean isMLE()
	{
		return mle;
	}

	/**
	 * Sets to true to perform Maximum Likelihood Estimation assuming Poisson model.
	 * <p>
	 * This modifies the standard LVM as described in Laurence & Chromy (2010) Efficient maximum likelihood estimator.
	 * Nature Methods 7, 338-339. The input data must be Poisson distributed for this to be relevant.
	 *
	 * @param mle
	 *            true to perform Maximum Likelihood Estimation
	 */
	public void setMLE(boolean mle)
	{
		this.mle = mle;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.fitting.nonlinear.BaseFunctionSolver#computeValue(int, double[], double[], double[])
	 */
	@Override
	public boolean computeValue(int n, double[] y, double[] y_fit, double[] a)
	{
		final int nparams = f.gradientIndices().length;

		// Create dynamically for the parameter sizes
		calculator = GradientCalculatorFactory.newCalculator(nparams, mle);

		if (mle)
			// We must have positive data
			y = ensurePositive(n, y);

		boolean requireResiduals = mle || f.canComputeWeights();

		if (requireResiduals)
			if (y_fit == null || y_fit.length < n)
				y_fit = new double[n];

		value = calculator.findLinearised(n, y, y_fit, a, f);

		// Weighted SS is not the correct sum-of-squares.
		// The MLE did not calculate the sum-of-squares.
		residualSumOfSquares = (requireResiduals) ? computeSS(y, y_fit, n) : value;

		return true;
	}
}
