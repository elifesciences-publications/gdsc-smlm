package gdsc.smlm.fitting;

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

import gdsc.smlm.utils.Maths;
import gdsc.smlm.utils.Sort;
import gdsc.smlm.utils.logging.Logger;
import gdsc.smlm.utils.logging.NullLogger;

import java.util.Arrays;

import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.analysis.MultivariateMatrixFunction;
import org.apache.commons.math3.analysis.MultivariateVectorFunction;
import org.apache.commons.math3.exception.ConvergenceException;
import org.apache.commons.math3.exception.TooManyEvaluationsException;
import org.apache.commons.math3.exception.TooManyIterationsException;
import org.apache.commons.math3.optim.ConvergenceChecker;
import org.apache.commons.math3.optim.InitialGuess;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.OptimizationData;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.PointVectorValuePair;
import org.apache.commons.math3.optim.SimpleBounds;
import org.apache.commons.math3.optim.SimpleValueChecker;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.CMAESOptimizer;
import org.apache.commons.math3.optim.nonlinear.vector.ModelFunction;
import org.apache.commons.math3.optim.nonlinear.vector.ModelFunctionJacobian;
import org.apache.commons.math3.optim.nonlinear.vector.Target;
import org.apache.commons.math3.optim.nonlinear.vector.Weight;
import org.apache.commons.math3.optim.nonlinear.vector.jacobian.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;
import org.apache.commons.math3.util.FastMath;

/**
 * Perform curve fitting on a cumulative histogram of the mean-squared displacement (MSD) per second to calculate the
 * diffusion coefficient of molecules (in um^2/s). The MSD is also known as the Jump Distance, i.e. how far a molecule
 * moves when being tracked.
 */
public class JumpDistanceAnalysis
{
	public interface CurveLogger
	{
		/**
		 * Get the number of points to use for the curve between the minimum and maximum exclusive. The size of the
		 * curve arrays will be this value plus 1 (to include the maximum).
		 * 
		 * @return The number of points to use for the curve between the minimum and maximum exclusive
		 */
		int getNumberOfCurvePoints();

		/**
		 * Called with the best fit curve using a single population
		 * 
		 * @param curve
		 */
		void saveSinglePopulationCurve(double[][] curve);

		/**
		 * Called with the best fit curve using a mixed population
		 * 
		 * @param curve
		 */
		void saveMixedPopulationCurve(double[][] curve);
	}

	private final Logger logger;
	private CurveLogger curveLogger;
	private int fitRestarts = 3;
	private double minFraction = 0.1;
	private double minDifference = 2;
	private int maxN = 10;

	public JumpDistanceAnalysis()
	{
		this(null);
	}

	/**
	 * @param logger
	 *            Used to write status messages on the fitting
	 */
	public JumpDistanceAnalysis(Logger logger)
	{
		if (logger == null)
			logger = new NullLogger();
		this.logger = logger;
	}

	/**
	 * Fit the jump distances using a fit to a cumulative histogram.
	 * <p>
	 * The histogram is fit repeatedly using a mixed population model with increasing number of different molecules.
	 * Results are sorted by the diffusion coefficient descending. This process is stopped when: the information
	 * criterion does not improve; the fraction of one of the populations is below the min fraction; the difference
	 * between two consecutive diffusion coefficients is below the min difference.
	 * <p>
	 * The number of populations must be obtained from the size of the D/fractions arrays.
	 * 
	 * @param jumpDistances
	 *            The jump distances (in um^2/s)
	 * @return Array containing: { D (um^2/s), Fractions }. Can be null if no fit was made.
	 */
	public double[][] fitJumpDistances(double... jumpDistances)
	{
		if (jumpDistances == null || jumpDistances.length == 0)
			return null;
		final double meanJumpDistance = Maths.sum(jumpDistances) / jumpDistances.length;
		double[][] jdHistogram = cumulativeHistogram(jumpDistances);
		return fitJumpDistanceHistogram(meanJumpDistance, jdHistogram);
	}

	/**
	 * Fit the jump distance histogram using a cumulative sum as detailed in ...
	 * TODO - Get the reference to the Klennerman paper.
	 * <p>
	 * The histogram is fit repeatedly using a mixed population model with increasing number of different molecules.
	 * Results are sorted by the diffusion coefficient descending. This process is stopped when: the information
	 * criterion does not improve; the fraction of one of the populations is below the min fraction; the difference
	 * between two consecutive diffusion coefficients is below the min difference.
	 * <p>
	 * The number of populations must be obtained from the size of the D/fractions arrays.
	 * 
	 * @param jumpDistances
	 *            The mean jump distance (in um^2/s)
	 * @param jdHistogram
	 *            The cumulative jump distance histogram. X-axis is um^2/s, Y-axis is cumulative probability. Must be
	 *            monototic ascending.
	 * @return Array containing: { D (um^2/s), Fractions }. Can be null if no fit was made.
	 */
	public double[][] fitJumpDistanceHistogram(double meanJumpDistance, double[][] jdHistogram)
	{
		int n = 0;
		double[] SS = new double[maxN];
		Arrays.fill(SS, -1);
		double[] ic = new double[maxN];
		Arrays.fill(ic, Double.POSITIVE_INFINITY);
		double[][] coefficients = new double[maxN][];
		double[][] fractions = new double[maxN][];
		double[][] fitParams = new double[maxN][];
		double bestIC = Double.POSITIVE_INFINITY;
		int best = -1;

		// Guess the D
		final double estimatedD = meanJumpDistance / 4;
		logger.info("Estimated D = %s um^2/s", Maths.rounded(estimatedD, 4));

		// Fit using a single population model
		LevenbergMarquardtOptimizer optimizer = new LevenbergMarquardtOptimizer();
		try
		{
			final JumpDistanceFunction function = new JumpDistanceFunction(jdHistogram[0], jdHistogram[1], estimatedD);
			PointVectorValuePair lvmSolution = optimizer.optimize(new MaxIter(3000), new MaxEval(Integer.MAX_VALUE),
					new ModelFunctionJacobian(new MultivariateMatrixFunction()
					{
						public double[][] value(double[] point) throws IllegalArgumentException
						{
							return function.jacobian(point);
						}
					}), new ModelFunction(function), new Target(function.getY()), new Weight(function.getWeights()),
					new InitialGuess(function.guess()));

			fitParams[n] = lvmSolution.getPointRef();
			SS[n] = calculateSumOfSquares(function.getY(), lvmSolution.getValueRef());
			ic[n] = Maths.getInformationCriterion(SS[n], function.x.length, 1);
			coefficients[n] = fitParams[n];
			fractions[n] = new double[] { 1 };

			logger.info("Fit Jump distance (N=%d) : D = %s um^2/s, SS = %f, IC = %s (%d evaluations)", n + 1,
					Maths.rounded(fitParams[n][0], 4), SS[n], Maths.rounded(ic[n], 4), optimizer.getEvaluations());

			bestIC = ic[n];
			best = 0;

			saveFitCurve(function, fitParams[n], jdHistogram, false);
		}
		catch (TooManyIterationsException e)
		{
			logger.info("Failed to fit : Too many iterations (%d)", optimizer.getIterations());
		}
		catch (ConvergenceException e)
		{
			logger.info("Failed to fit : %s", e.getMessage());
		}

		n++;

		// Fit using a mixed population model. 
		// Vary n from 2 to N. Stop when the fit fails or the fit is worse.
		int bestMulti = -1;
		double bestMultiIC = Double.POSITIVE_INFINITY;
		while (n < maxN)
		{
			// Uses a weighted sum of n exponential functions, each function models a fraction of the particles.
			// An LVM fit cannot restrict the parameters so the fractions do not go below zero.
			// Use the CMEASOptimizer which supports bounded fitting.

			MixedJumpDistanceFunctionMultivariate mixedFunction = new MixedJumpDistanceFunctionMultivariate(
					jdHistogram[0], jdHistogram[1], estimatedD, n + 1);

			double[] lB = mixedFunction.getLowerBounds();
			double[] uB = mixedFunction.getUpperBounds();
			SimpleBounds bounds = new SimpleBounds(lB, uB);

			int maxIterations = 2000;
			double stopFitness = 0; //Double.NEGATIVE_INFINITY;
			boolean isActiveCMA = true;
			int diagonalOnly = 20;
			int checkFeasableCount = 1;
			RandomGenerator random = new Well19937c();
			boolean generateStatistics = false;
			ConvergenceChecker<PointValuePair> checker = new SimpleValueChecker(1e-6, 1e-10);
			// The sigma determines the search range for the variables. It should be 1/3 of the initial search region.
			double[] s = new double[lB.length];
			for (int i = 0; i < s.length; i++)
				s[i] = (uB[i] - lB[i]) / 3;
			OptimizationData sigma = new CMAESOptimizer.Sigma(s);
			OptimizationData popSize = new CMAESOptimizer.PopulationSize((int) (4 + Math.floor(3 * Math
					.log(mixedFunction.x.length))));

			// Iterate this for stability in the initial guess
			CMAESOptimizer opt = new CMAESOptimizer(maxIterations, stopFitness, isActiveCMA, diagonalOnly,
					checkFeasableCount, random, generateStatistics, checker);

			int evaluations = 0;
			PointValuePair constrainedSolution = null;

			for (int i = 0; i <= fitRestarts; i++)
			{
				// Try from the initial guess
				try
				{
					PointValuePair solution = opt.optimize(new InitialGuess(mixedFunction.guess()),
							new ObjectiveFunction(mixedFunction), GoalType.MINIMIZE, bounds, sigma, popSize,
							new MaxIter(maxIterations), new MaxEval(maxIterations * 2));
					if (constrainedSolution == null || solution.getValue() < constrainedSolution.getValue())
					{
						evaluations = opt.getEvaluations();
						constrainedSolution = solution;
						logger.debug("[%da] Fit Jump distance (N=%d) : SS = %f (%d evaluations)", i, n + 1,
								solution.getValue(), evaluations);
					}
				}
				catch (TooManyEvaluationsException e)
				{
				}

				if (constrainedSolution == null)
					continue;

				// Try from the current optimum
				try
				{
					PointValuePair solution = opt.optimize(new InitialGuess(constrainedSolution.getPointRef()),
							new ObjectiveFunction(mixedFunction), GoalType.MINIMIZE, bounds, sigma, popSize,
							new MaxIter(maxIterations), new MaxEval(maxIterations * 2));
					if (constrainedSolution == null || solution.getValue() < constrainedSolution.getValue())
					{
						evaluations = opt.getEvaluations();
						constrainedSolution = solution;
						logger.debug("[%db] Fit Jump distance (N=%d) : SS = %f (%d evaluations)", i, n + 1,
								solution.getValue(), evaluations);
					}
				}
				catch (TooManyEvaluationsException e)
				{
				}
			}

			if (constrainedSolution == null)
			{
				logger.info("Failed to fit N=%d", n + 1);
				break;
			}

			fitParams[n] = constrainedSolution.getPointRef();
			SS[n] = constrainedSolution.getValue();

			// TODO - Try a bounded BFGS optimiser

			// Try and improve using a LVM fit
			final MixedJumpDistanceFunctionGradient mixedFunctionGradient = new MixedJumpDistanceFunctionGradient(
					jdHistogram[0], jdHistogram[1], estimatedD, n + 1);

			PointVectorValuePair lvmSolution;
			try
			{
				lvmSolution = optimizer.optimize(new MaxIter(3000), new MaxEval(Integer.MAX_VALUE),
						new ModelFunctionJacobian(new MultivariateMatrixFunction()
						{
							public double[][] value(double[] point) throws IllegalArgumentException
							{
								return mixedFunctionGradient.jacobian(point);
							}
						}), new ModelFunction(mixedFunctionGradient), new Target(mixedFunctionGradient.getY()),
						new Weight(mixedFunctionGradient.getWeights()), new InitialGuess(fitParams[n]));
				double ss = calculateSumOfSquares(mixedFunctionGradient.getY(), lvmSolution.getValue());
				// All fitted parameters must be above zero
				if (ss < SS[n] && Maths.min(lvmSolution.getPoint()) > 0)
				{
					logger.info("  Re-fitting improved the SS from %s to %s (-%s%%)", Maths.rounded(SS[n], 4),
							Maths.rounded(ss, 4), Maths.rounded(100 * (SS[n] - ss) / SS[n], 4));
					fitParams[n] = lvmSolution.getPoint();
					SS[n] = ss;
					evaluations += optimizer.getEvaluations();
				}
			}
			catch (TooManyIterationsException e)
			{
				logger.error("Failed to re-fit : Too many evaluations (%d)", optimizer.getEvaluations());
			}
			catch (ConvergenceException e)
			{
				logger.error("Failed to re-fit : %s", e.getMessage());
			}

			// Since the fractions must sum to one we subtract 1 degree of freedom from the number of parameters
			ic[n] = Maths.getInformationCriterion(SS[n], mixedFunction.x.length, fitParams[n].length - 1);

			double[] d = new double[n + 1];
			double[] f = new double[n + 1];
			double sum = 0;
			for (int i = 0; i < d.length; i++)
			{
				f[i] = fitParams[n][i * 2];
				sum += f[i];
				d[i] = fitParams[n][i * 2 + 1];
			}
			for (int i = 0; i < f.length; i++)
				f[i] /= sum;
			// Sort by coefficient size
			sort(d, f);
			coefficients[n] = d;
			fractions[n] = f;

			logger.info("Fit Jump distance (N=%d) : D = %s um^2/s (%s), SS = %f, IC = %s (%d evaluations)", n + 1,
					format(d), format(f), SS[n], Maths.rounded(ic[n], 4), evaluations);

			boolean valid = true;
			for (int i = 0; i < f.length; i++)
			{
				// Check the fit has fractions above the minimum fraction
				if (f[i] < minFraction)
				{
					logger.debug("Fraction is less than the minimum fraction: %s < %s", Maths.rounded(f[i]),
							Maths.rounded(minFraction));
					valid = false;
					break;
				}
				// Check the coefficients are different
				if (i + 1 < f.length && d[i] / d[i + 1] < minDifference)
				{
					logger.debug("Coefficients are not different: %s / %s = %s < %s", Maths.rounded(d[i]),
							Maths.rounded(d[i + 1]), Maths.rounded(d[i] / d[i + 1]), Maths.rounded(minDifference));
					valid = false;
					break;
				}
			}

			if (!valid)
				break;

			// Store the best model
			if (bestIC > ic[n])
			{
				bestIC = ic[n];
				best = n;
			}

			// Store the best multi model
			if (bestMultiIC < ic[n])
			{
				break;
			}

			bestMultiIC = ic[n];

			n++;
		}

		// Add the best fit to the plot and return the parameters.
		if (bestMulti > -1)
		{
			Function function = new MixedJumpDistanceFunctionMultivariate(jdHistogram[0], jdHistogram[1], 0,
					bestMulti + 1);
			saveFitCurve(function, fitParams[bestMulti], jdHistogram, true);
		}

		if (best > -1)
		{
			logger.info("Best fit achieved using %d population%s: D = %s um^2/s, Fractions = %s", best + 1,
					(best == 0) ? "" : "s", format(coefficients[best]), format(fractions[best]));
		}

		return (best > -1) ? new double[][] { coefficients[best], fractions[best] } : null;
	}

	private String format(double[] jumpD)
	{
		if (jumpD == null || jumpD.length == 0)
			return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < jumpD.length; i++)
		{
			if (i != 0)
				sb.append(", ");
			sb.append(Maths.rounded(jumpD[i], 4));
		}
		return sb.toString();
	}

	private void sort(double[] d, double[] f)
	{
		// Sort by coefficient size
		int[] indices = new int[f.length];
		for (int i = 0; i < f.length; i++)
		{
			indices[i] = i;
		}
		Sort.sort(indices, d);
		double[] d2 = Arrays.copyOf(d, d.length);
		double[] f2 = Arrays.copyOf(f, f.length);
		for (int i = 0; i < f.length; i++)
		{
			d[i] = d2[indices[i]];
			f[i] = f2[indices[i]];
		}
	}

	private void saveFitCurve(Function function, double[] params, double[][] jdHistogram, boolean mixedPopulation)
	{
		if (curveLogger == null)
			return;
		final int nPoints = curveLogger.getNumberOfCurvePoints();
		if (nPoints <= 1)
			return;
		final double max = jdHistogram[0][jdHistogram[0].length - 1];
		final double interval = max / nPoints;
		final double[] x = new double[nPoints + 1];
		final double[] y = new double[nPoints + 1];

		for (int i = 0; i < nPoints; i++)
		{
			x[i] = i * interval;
			y[i] = function.evaluate(x[i], params);
		}
		x[nPoints] = max;
		y[nPoints] = function.evaluate(max, params);

		if (mixedPopulation)
			curveLogger.saveMixedPopulationCurve(new double[][] { x, y });
		else
			curveLogger.saveSinglePopulationCurve(new double[][] { x, y });
	}

	private double calculateSumOfSquares(double[] obs, double[] exp)
	{
		double ss = 0;
		for (int i = 0; i < obs.length; i++)
			ss += (obs[i] - exp[i]) * (obs[i] - exp[i]);
		return ss;
	}

	public abstract class Function
	{
		double[] x, y;
		double estimatedD;

		public Function(double[] x, double[] y, double estimatedD)
		{
			this.x = x;
			this.y = y;
			this.estimatedD = estimatedD;
		}

		/**
		 * @return An estimate for the parameters
		 */
		public abstract double[] guess();

		public double[] getWeights()
		{
			double[] w = new double[x.length];
			Arrays.fill(w, 1);
			return w;
		}

		public double[] getX()
		{
			return x;
		}

		public double[] getY()
		{
			return y;
		}

		public abstract double evaluate(double x, double[] parameters);

		public double[][] jacobian(double[] variables)
		{
			double[][] jacobian = new double[x.length][variables.length];

			final double delta = 0.001;
			double[] d = new double[variables.length];
			double[][] variables2 = new double[variables.length][];
			for (int i = 0; i < variables.length; i++)
			{
				d[i] = delta * Math.abs(variables[i]); // Should the delta be changed for each parameter ?
				variables2[i] = Arrays.copyOf(variables, variables.length);
				variables2[i][i] += d[i];
			}
			for (int i = 0; i < jacobian.length; ++i)
			{
				double value = evaluate(x[i], variables);
				for (int j = 0; j < variables.length; j++)
				{
					double value2 = evaluate(x[i], variables2[j]);
					jacobian[i][j] = (value2 - value) / d[j];
				}
			}
			return jacobian;
		}
	}

	public class JumpDistanceFunction extends Function implements MultivariateVectorFunction
	{
		public JumpDistanceFunction(double[] x, double[] y, double estimatedD)
		{
			super(x, y, estimatedD);
		}

		// Adapted from http://commons.apache.org/proper/commons-math/userguide/optimization.html

		public double[] guess()
		{
			return new double[] { estimatedD };
		}

		public double evaluate(double x, double[] params)
		{
			return 1 - FastMath.exp(-x / (4 * params[0]));
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.apache.commons.math3.analysis.MultivariateVectorFunction#value(double[])
		 */
		public double[] value(double[] variables)
		{
			double[] values = new double[x.length];
			final double fourD = 4 * variables[0];
			for (int i = 0; i < values.length; i++)
			{
				values[i] = 1 - FastMath.exp(-x[i] / fourD);
			}
			return values;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see gdsc.smlm.ij.plugins.TraceDiffusion.Function#jacobian(double[])
		 */
		public double[][] jacobian(double[] variables)
		{
			// Compute the gradients using calculus differentiation:
			// y = 1 - a
			// a = exp(b)
			// b = -x / 4D
			//
			// y' = -a'
			// a' = exp(b) * b'
			// b' = -1 * -x / 4D^2 = x / 4D^2
			// y' = -exp(b) * x / 4D^2
			//    = -a * -b / D
			//    = a * b / D
			//    = exp(b) * b / D

			final double d = variables[0];
			final double fourD = 4 * d;
			double[][] jacobian = new double[x.length][variables.length];

			for (int i = 0; i < jacobian.length; ++i)
			{
				final double b = -x[i] / fourD;
				jacobian[i][0] = FastMath.exp(b) * b / d;
			}

			//// Check numerically ...
			//double[][] jacobian2 = super.jacobian(variables);
			//for (int i = 0; i < jacobian.length; i++)
			//{
			//	System.out.printf("dD = %g : %g = %g\n", jacobian[i][0], jacobian2[i][0],
			//			DoubleEquality.relativeError(jacobian[i][0], jacobian2[i][0]));
			//}

			return jacobian;
		}
	}

	public class MixedJumpDistanceFunction extends Function
	{
		int n;

		public MixedJumpDistanceFunction(double[] x, double[] y, double estimatedD, int n)
		{
			super(x, y, estimatedD);
			this.n = n;
		}

		public double[] guess()
		{
			// Store the fraction and then the diffusion coefficient.
			// Q. Should this be modified to set one fraction to always be 1? 
			// Having an actual parameter for fitting will allow the optimisation engine to 
			// adjust the fraction for its diffusion coefficient relative to the others.
			double[] guess = new double[n * 2];
			double d = estimatedD;
			for (int i = 0; i < n; i++)
			{
				// Fraction are all equal
				guess[i * 2] = 1;
				// Diffusion coefficient gets smaller for each fraction
				guess[i * 2 + 1] = d;
				d *= 0.1;
			}
			return guess;
		}

		public double[] getUpperBounds()
		{
			double[] bounds = new double[n * 2];
			for (int i = 0; i < n; i++)
			{
				// Fraction guess is 1 so set the upper limit as 10
				bounds[i * 2] = 10;
				// Diffusion coefficient could be 10x the estimated
				bounds[i * 2 + 1] = estimatedD * 10;
			}
			return bounds;
		}

		public double[] getLowerBounds()
		{
			return new double[n * 2];
		}

		public double evaluate(double x, double[] params)
		{
			double sum = 0;
			double total = 0;
			for (int i = 0; i < n; i++)
			{
				final double f = params[i * 2];
				sum += f * FastMath.exp(-x / (4 * params[i * 2 + 1]));
				total += f;
			}
			return 1 - sum / total;
		}

		public double[] getValue(double[] variables)
		{
			double total = 0;
			for (int i = 0; i < n; i++)
			{
				total += variables[i * 2];
			}

			final double[] fourD = new double[n];
			final double[] f = new double[n];
			for (int i = 0; i < n; i++)
			{
				f[i] = variables[i * 2] / total;
				fourD[i] = 4 * variables[i * 2 + 1];
			}

			double[] values = new double[x.length];
			for (int i = 0; i < values.length; i++)
			{
				double sum = 0;
				for (int j = 0; j < n; j++)
				{
					sum += f[j] * FastMath.exp(-x[i] / (fourD[j]));
				}
				values[i] = 1 - sum;
			}
			return values;
		}
	}

	public class MixedJumpDistanceFunctionGradient extends MixedJumpDistanceFunction implements
			MultivariateVectorFunction
	{
		public MixedJumpDistanceFunctionGradient(double[] x, double[] y, double estimatedD, int n)
		{
			super(x, y, estimatedD, n);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.apache.commons.math3.analysis.MultivariateVectorFunction#value(double[])
		 */
		public double[] value(double[] point) throws IllegalArgumentException
		{
			return getValue(point);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see gdsc.smlm.ij.plugins.TraceDiffusion.Function#jacobian(double[])
		 */
		public double[][] jacobian(double[] variables)
		{
			// Compute the gradients using calculus differentiation:
			// y = 1 - sum(a)
			// The sum is over n components of the following function
			// a = f * exp(b)
			// b = -x / 4D
			// Each function contributes a fraction f:
			// f = fj / sum_j(f)

			// The gradient is the sum of the individual gradients. The diffusion coefficient is only 
			// used per component. The fraction is used in all, either with the fraction as the 
			// numerator (A) or part of the denominator (B) 
			// E.G. 
			// f(A) = A / (A+B+C)
			// Quotient rule: f = g / h => f' = (g'h - gh') / h^2
			// f'(A) = ((A+B+C) - A) / (A+B+C)^2
			//       = (B+C) / (A+B+C)^2
			//       = (sum(f) - f) / sum(f)^2
			// f'(B) = -A / (A+B+C)^2
			//       = -f / sum(f)^2

			// Differentiate with respect to D is easier:
			// y' = -a'
			// a' = f * exp(b) * b'
			// b' = -1 * -x / 4D^2 = x / 4D^2
			// y' = f * -exp(b) * x / 4D^2
			//    = f * -a * -b / D
			//    = f * a * b / D
			//    = f * exp(b) * b / D

			final double[] fourD = new double[n];
			final double[] f = new double[n];
			double total = 0;
			for (int i = 0; i < n; i++)
			{
				f[i] = variables[i * 2];
				fourD[i] = 4 * variables[i * 2 + 1];
				total += f[i];
			}

			final double[] fraction = new double[n];
			final double[] total_f = new double[n];
			final double[] f_total = new double[n];
			for (int i = 0; i < n; i++)
			{
				fraction[i] = f[i] / total;
				// Because we use y = 1 - sum(a) all coefficients are inverted
				total_f[i] = -1 * (total - f[i]) / (total * total);
				f_total[i] = -1 * -f[i] / (total * total);
			}

			double[][] jacobian = new double[x.length][variables.length];

			double[] b = new double[n];
			for (int i = 0; i < x.length; ++i)
			{
				for (int j = 0; j < n; j++)
					b[j] = -x[i] / fourD[j];

				for (int j = 0; j < n; j++)
				{
					// Gradient for the diffusion coefficient
					jacobian[i][j * 2 + 1] = fraction[j] * FastMath.exp(b[j]) * b[j] / variables[j * 2 + 1];

					// Gradient for the fraction f
					jacobian[i][j * 2] = total_f[j] * FastMath.exp(b[j]);
					for (int k = 0; k < n; k++)
					{
						if (j == k)
							continue;
						jacobian[i][j * 2] += f_total[k] * FastMath.exp(b[k]);
					}
				}
			}

			//// Check numerically ...
			//double[][] jacobian2 = super.jacobian(variables);
			//for (int i = 0; i < jacobian.length; i++)
			//{
			//	StringBuilder sb = new StringBuilder();
			//	for (int j = 0; j < jacobian[i].length; j++)
			//	{
			//		sb.append(" d").append(j).append(" = ").append(jacobian[i][j]).append(" : ")
			//				.append(jacobian2[i][j]).append(" = ")
			//				.append(DoubleEquality.relativeError(jacobian[i][j], jacobian2[i][j]));
			//	}
			//	System.out.println(sb.toString());
			//}

			return jacobian;
		}
	}

	public class MixedJumpDistanceFunctionMultivariate extends MixedJumpDistanceFunction implements
			MultivariateFunction
	{
		public MixedJumpDistanceFunctionMultivariate(double[] x, double[] y, double estimatedD, int n)
		{
			super(x, y, estimatedD, n);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.apache.commons.math3.analysis.MultivariateFunction#value(double[])
		 */
		public double value(double[] parameters)
		{
			double[] obs = getValue(parameters);

			// Optimise the sum of squares
			double ss = 0;
			for (int i = x.length; i-- > 0;)
			{
				double dx = y[i] - obs[i];
				ss += dx * dx;
			}
			return ss;
		}
	}

	/**
	 * @return The number restarts for fitting
	 */
	public int getFitRestarts()
	{
		return fitRestarts;
	}

	/**
	 * @param fitRestarts
	 *            The number restarts for fitting
	 */
	public void setFitRestarts(int fitRestarts)
	{
		this.fitRestarts = fitRestarts;
	}

	/**
	 * @return the min fraction for each population in a mixed population
	 */
	public double getMinFraction()
	{
		return minFraction;
	}

	/**
	 * @param minFraction
	 *            the min fraction for each population in a mixed population
	 */
	public void setMinFraction(double minFraction)
	{
		this.minFraction = minFraction;
	}

	/**
	 * @return the min difference between diffusion coefficients in a mixed population
	 */
	public double getMinDifference()
	{
		return minDifference;
	}

	/**
	 * @param minDifference
	 *            the min difference between diffusion coefficients in a mixed population
	 */
	public void setMinDifference(double minDifference)
	{
		this.minDifference = minDifference;
	}

	/**
	 * @return the maximum number of different molecules to fit in a mixed population model
	 */
	public int getN()
	{
		return maxN;
	}

	/**
	 * @param n
	 *            the maximum number of different molecules to fit in a mixed population model
	 */
	public void setN(int n)
	{
		if (n < 1)
			n = 1;
		maxN = n;
	}

	/**
	 * @param the
	 *            curve logger used to store the best fit curves
	 */
	public void setCurveLogger(CurveLogger curveLogger)
	{
		this.curveLogger = curveLogger;
	}

	/**
	 * Get the cumulative jump distance histogram given a set of jump distance values
	 * 
	 * @param values
	 *            The jump distances
	 * @return The JD cumulative histogram as two arrays: { MSD, CumulativeProbability }
	 */
	public static double[][] cumulativeHistogram(double[] values)
	{
		return Maths.cumulativeHistogram(values, true);
	}
}
