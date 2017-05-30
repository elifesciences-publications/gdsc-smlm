package gdsc.smlm.function.gaussian.erf;

//import org.apache.commons.math3.special.Erf;
import org.apache.commons.math3.util.FastMath;

import gdsc.smlm.function.Erf;
import gdsc.smlm.function.ExtendedGradient2Procedure;
import gdsc.smlm.function.Gradient1Procedure;
import gdsc.smlm.function.Gradient2Procedure;
import gdsc.smlm.function.gaussian.Gaussian2DFunction;

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
 * Evaluates a 2-dimensional Gaussian function for a single peak.
 */
public class MultiFreeCircularErfGaussian2DFunction extends MultiErfGaussian2DFunction
{
	/**
	 * Constructor.
	 *
	 * @param nPeaks
	 *            The number of peaks
	 * @param maxx
	 *            The maximum x value of the 2-dimensional data (used to unpack a linear index into coordinates)
	 * @param maxy
	 *            The maximum y value of the 2-dimensional data (used to unpack a linear index into coordinates)
	 */
	public MultiFreeCircularErfGaussian2DFunction(int nPeaks, int maxx, int maxy)
	{
		super(nPeaks, maxx, maxy);
	}

	@Override
	protected int[] createGradientIndices()
	{
		return replicateGradientIndices(SingleFreeCircularErfGaussian2DFunction.gradientIndices);
	}

	@Override
	public ErfGaussian2DFunction copy()
	{
		return new MultiFreeCircularErfGaussian2DFunction(nPeaks, maxx, maxy);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.gaussian.Gaussian2DFunction#initialise0(double[])
	 */
	public void initialise0(double[] a)
	{
		tB = a[Gaussian2DFunction.BACKGROUND];

		for (int n = 0, i = 0; n < nPeaks; n++, i += 6)
		{
			tI[n] = a[i + Gaussian2DFunction.SIGNAL];
			// Pre-compute the offset by 0.5
			final double tx = a[i + Gaussian2DFunction.X_POSITION] + 0.5;
			final double ty = a[i + Gaussian2DFunction.Y_POSITION] + 0.5;
			final double tsx = a[i + Gaussian2DFunction.X_SD];
			final double tsy = a[i + Gaussian2DFunction.Y_SD];

			createDeltaETable(n, maxx, ONE_OVER_ROOT2 / tsx, deltaEx, tx);
			createDeltaETable(n, maxy, ONE_OVER_ROOT2 / tsy, deltaEy, ty);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.gaussian.Gaussian2DFunction#initialise1(double[])
	 */
	public void initialise1(double[] a)
	{
		create1Arrays();
		tB = a[Gaussian2DFunction.BACKGROUND];
		for (int n = 0, i = 0; n < nPeaks; n++, i += 6)
		{
			tI[n] = a[i + Gaussian2DFunction.SIGNAL];
			// Pre-compute the offset by 0.5
			final double tx = a[i + Gaussian2DFunction.X_POSITION] + 0.5;
			final double ty = a[i + Gaussian2DFunction.Y_POSITION] + 0.5;
			final double tsx = a[i + Gaussian2DFunction.X_SD];
			final double tsy = a[i + Gaussian2DFunction.Y_SD];

			// We can pre-compute part of the derivatives for position and sd in arrays 
			// since the Gaussian is XY separable
			createFirstOrderTables(n, maxx, tI[n], deltaEx, du_dtx, du_dtsx, tx, tsx);
			createFirstOrderTables(n, maxy, tI[n], deltaEy, du_dty, du_dtsy, ty, tsy);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.Gradient2Function#initialise2(double[])
	 */
	public void initialise2(double[] a)
	{
		create2Arrays();
		tB = a[Gaussian2DFunction.BACKGROUND];
		for (int n = 0, i = 0; n < nPeaks; n++, i += 6)
		{
			tI[n] = a[i + Gaussian2DFunction.SIGNAL];
			// Pre-compute the offset by 0.5
			final double tx = a[i + Gaussian2DFunction.X_POSITION] + 0.5;
			final double ty = a[i + Gaussian2DFunction.Y_POSITION] + 0.5;
			final double tsx = a[i + Gaussian2DFunction.X_SD];
			final double tsy = a[i + Gaussian2DFunction.Y_SD];

			// We can pre-compute part of the derivatives for position and sd in arrays 
			// since the Gaussian is XY separable
			createSecondOrderTables(n, maxx, tI[n], deltaEx, du_dtx, du_dtsx, d2u_dtx2, d2u_dtsx2, tx, tsx);
			createSecondOrderTables(n, maxy, tI[n], deltaEy, du_dty, du_dtsy, d2u_dty2, d2u_dtsy2, ty, tsy);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.gaussian.erf.ErfGaussian2DFunction#initialiseExtended2(double[])
	 */
	public void initialiseExtended2(double[] a)
	{
		createEx2Arrays();
		tB = a[Gaussian2DFunction.BACKGROUND];
		for (int n = 0, i = 0; n < nPeaks; n++, i += 6)
		{
			tI[n] = a[i + Gaussian2DFunction.SIGNAL];
			// Pre-compute the offset by 0.5
			final double tx = a[i + Gaussian2DFunction.X_POSITION] + 0.5;
			final double ty = a[i + Gaussian2DFunction.Y_POSITION] + 0.5;
			final double tsx = a[i + Gaussian2DFunction.X_SD];
			final double tsy = a[i + Gaussian2DFunction.Y_SD];

			// We can pre-compute part of the derivatives for position and sd in arrays 
			// since the Gaussian is XY separable
			createExSecondOrderTables(n, maxx, tI[n], deltaEx, du_dtx, du_dtsx, d2u_dtx2, d2u_dtsx2, d2deltaEx_dtsxdx,
					tx, tsx);
			createExSecondOrderTables(n, maxy, tI[n], deltaEy, du_dty, du_dtsy, d2u_dty2, d2u_dtsy2, d2deltaEy_dtsydy,
					ty, tsy);
		}
	}

	/**
	 * Creates the delta E array. This is the sum of the Gaussian function using the error function for each of the
	 * pixels from 0 to n.
	 *
	 * @param n
	 *            the peak number
	 * @param max
	 *            the maximum for the dimension
	 * @param one_sSqrt2
	 *            one over (s times sqrt(2))
	 * @param deltaE
	 *            the delta E for dimension 0 (difference between the error function at the start and end of each pixel)
	 * @param u
	 *            the mean of the Gaussian for dimension 0
	 */
	protected static void createDeltaETable(int n, int max, double one_sSqrt2, double[] deltaE, double u)
	{
		// For documentation see SingleFreeCircularErfGaussian2DFunction.createSecondOrderTables(...)

		double x_u_p12 = -u;
		double erf_x_minus = 0.5 * Erf.erf(x_u_p12 * one_sSqrt2);
		for (int i = 0, j = n * max; i < max; i++, j++)
		{
			x_u_p12 += 1.0;
			final double erf_x_plus = 0.5 * Erf.erf(x_u_p12 * one_sSqrt2);
			deltaE[j] = erf_x_plus - erf_x_minus;
			erf_x_minus = erf_x_plus;
		}
	}

	/**
	 * Creates the first order derivatives.
	 *
	 * @param n
	 *            the peak number
	 * @param max
	 *            the maximum for the dimension
	 * @param tI
	 *            the target intensity
	 * @param deltaE
	 *            the delta E for dimension 0 (difference between the error function at the start and end of each pixel)
	 * @param du_dx
	 *            the first order x derivative for dimension 0
	 * @param du_ds
	 *            the first order s derivative for dimension 0
	 * @param u
	 *            the mean of the Gaussian for dimension 0
	 * @param s
	 *            the standard deviation of the Gaussian for dimension 0
	 */
	protected static void createFirstOrderTables(int n, int max, double tI, double[] deltaE, double[] du_dx,
			double[] du_ds, double u, double s)
	{
		createFirstOrderTables(n, max, ONE_OVER_ROOT2 / s, 0.5 / (s * s), tI * ONE_OVER_ROOT2PI / s,
				tI * ONE_OVER_ROOT2PI / (s * s), deltaE, du_dx, du_ds, u);
	}

	/**
	 * Creates the first order derivatives.
	 *
	 * @param n
	 *            the peak number
	 * @param max
	 *            the maximum for the dimension
	 * @param one_sSqrt2
	 *            one over (s times sqrt(2))
	 * @param one_2ss
	 *            one over (2 * s^2)
	 * @param I_sSqrt2pi
	 *            the intensity over (s * sqrt(2*pi))
	 * @param I_ssSqrt2pi
	 *            the intensity over (s^2 * sqrt(2*pi))
	 * @param deltaE
	 *            the delta E for dimension 0 (difference between the error function at the start and end of each pixel)
	 * @param du_dx
	 *            the first order x derivative for dimension 0
	 * @param du_ds
	 *            the first order s derivative for dimension 0
	 * @param u
	 *            the mean of the Gaussian for dimension 0
	 */
	protected static void createFirstOrderTables(int n, int max, double one_sSqrt2, double one_2ss, double I_sSqrt2pi,
			double I_ssSqrt2pi, double[] deltaE, double[] du_dx, double[] du_ds, double u)
	{
		// For documentation see SingleFreeCircularErfGaussian2DFunction.createSecondOrderTables(...)

		double x_u_p12 = -u;
		double erf_x_minus = 0.5 * Erf.erf(x_u_p12 * one_sSqrt2);
		double exp_x_minus = FastMath.exp(-(x_u_p12 * x_u_p12 * one_2ss));
		for (int i = 0, j = n * max; i < max; i++, j++)
		{
			double x_u_m12 = x_u_p12;
			x_u_p12 += 1.0;
			final double erf_x_plus = 0.5 * Erf.erf(x_u_p12 * one_sSqrt2);
			deltaE[j] = erf_x_plus - erf_x_minus;
			erf_x_minus = erf_x_plus;

			final double exp_x_plus = FastMath.exp(-(x_u_p12 * x_u_p12 * one_2ss));
			du_dx[j] = I_sSqrt2pi * (exp_x_minus - exp_x_plus);
			// Compute: I0 * G21(xk)
			du_ds[j] = I_ssSqrt2pi * (x_u_m12 * exp_x_minus - x_u_p12 * exp_x_plus);

			exp_x_minus = exp_x_plus;
		}
	}

	/**
	 * Creates the first and second order derivatives.
	 *
	 * @param n
	 *            the peak number
	 * @param max
	 *            the maximum for the dimension
	 * @param tI
	 *            the target intensity
	 * @param deltaE
	 *            the delta E for dimension 0 (difference between the error function at the start and end of each pixel)
	 * @param du_dx
	 *            the first order x derivative for dimension 0
	 * @param du_ds
	 *            the first order s derivative for dimension 0
	 * @param d2u_dx2
	 *            the second order x derivative for dimension 0
	 * @param d2u_ds2
	 *            the second order s derivative for dimension 0
	 * @param u
	 *            the mean of the Gaussian for dimension 0
	 * @param s
	 *            the standard deviation of the Gaussian for dimension 0
	 */
	protected static void createSecondOrderTables(int n, int max, double tI, double[] deltaE, double[] du_dx,
			double[] du_ds, double[] d2u_dx2, double[] d2u_ds2, double u, double s)
	{
		final double ss = s * s;
		final double one_sSqrt2pi = ONE_OVER_ROOT2PI / s;
		final double one_ssSqrt2pi = ONE_OVER_ROOT2PI / ss;
		final double one_sssSqrt2pi = one_sSqrt2pi / ss;
		final double one_sssssSqrt2pi = one_sssSqrt2pi / ss;
		createSecondOrderTables(n, max, tI, ONE_OVER_ROOT2 / s, 0.5 / ss, tI * one_sSqrt2pi, tI * one_ssSqrt2pi,
				tI * one_sssSqrt2pi, ss, one_sssSqrt2pi, one_sssssSqrt2pi, deltaE, du_dx, du_ds, d2u_dx2, d2u_ds2, u);
	}

	/**
	 * Creates the first and second order derivatives.
	 *
	 * @param n
	 *            the peak number
	 * @param max
	 *            the maximum for the dimension
	 * @param tI
	 *            the target intensity
	 * @param one_sSqrt2
	 *            one over (s times sqrt(2))
	 * @param one_2ss
	 *            one over (2 * s^2)
	 * @param I_sSqrt2pi
	 *            the intensity over (s * sqrt(2*pi))
	 * @param I_ssSqrt2pi
	 *            the intensity over (s^2 * sqrt(2*pi))
	 * @param I_sssSqrt2pi
	 *            the intensity over (s^3 * sqrt(2*pi))
	 * @param ss
	 *            the standard deviation squared
	 * @param one_sssSqrt2pi
	 *            one over (s^3 * sqrt(2*pi))
	 * @param one_sssssSqrt2pi
	 *            one over (s^5 * sqrt(2*pi))
	 * @param deltaE
	 *            the delta E for dimension 0 (difference between the error function at the start and end of each pixel)
	 * @param du_dx
	 *            the first order x derivative for dimension 0
	 * @param du_ds
	 *            the first order s derivative for dimension 0
	 * @param d2u_dx2
	 *            the second order x derivative for dimension 0
	 * @param d2u_ds2
	 *            the second order s derivative for dimension 0
	 * @param u
	 *            the mean of the Gaussian for dimension 0
	 */
	protected static void createSecondOrderTables(int n, int max, double tI, double one_sSqrt2, double one_2ss,
			double I_sSqrt2pi, double I_ssSqrt2pi, double I_sssSqrt2pi, double ss, double one_sssSqrt2pi,
			double one_sssssSqrt2pi, double[] deltaE, double[] du_dx, double[] du_ds, double[] d2u_dx2,
			double[] d2u_ds2, double u)
	{
		// Note: The paper by Smith, et al computes the integral for the kth pixel centred at (x,y)
		// If x=u then the Erf will be evaluated at x-u+0.5 - x-u-0.5 => integral from -0.5 to 0.5.
		// This code sets the first pixel at (0,0).

		// All computations for pixel k (=(x,y)) that require the exponential can use x,y indices for the 
		// lower boundary value and x+1,y+1 indices for the upper value.

		// Working example of this in GraspJ source code:
		// https://github.com/isman7/graspj/blob/master/graspj/src/main/java/eu/brede/graspj/opencl/src/functions/
		// I have used the same notation for clarity

		// The first position:
		// Offset x by the position and get the pixel lower bound.
		// (x - u - 0.5) with x=0 and u offset by +0.5
		double x_u_p12 = -u;
		double erf_x_minus = 0.5 * Erf.erf(x_u_p12 * one_sSqrt2);
		double exp_x_minus = FastMath.exp(-(x_u_p12 * x_u_p12 * one_2ss));
		for (int i = 0, j = n * max; i < max; i++, j++)
		{
			double x_u_m12 = x_u_p12;
			x_u_p12 += 1.0;
			final double erf_x_plus = 0.5 * Erf.erf(x_u_p12 * one_sSqrt2);
			deltaE[j] = erf_x_plus - erf_x_minus;
			erf_x_minus = erf_x_plus;

			final double exp_x_plus = FastMath.exp(-(x_u_p12 * x_u_p12 * one_2ss));
			du_dx[j] = I_sSqrt2pi * (exp_x_minus - exp_x_plus);
			// Compute: I0 * G21(xk)
			final double pre2 = (x_u_m12 * exp_x_minus - x_u_p12 * exp_x_plus);
			du_ds[j] = I_ssSqrt2pi * pre2;

			// Second derivatives
			d2u_dx2[j] = I_sssSqrt2pi * pre2;

			// Compute G31(xk)
			final double G31 = one_sssSqrt2pi * pre2;

			// Compute G53(xk)
			x_u_m12 = x_u_m12 * x_u_m12 * x_u_m12;
			final double ux = x_u_p12 * x_u_p12 * x_u_p12;
			final double G53 = one_sssssSqrt2pi * (x_u_m12 * exp_x_minus - ux * exp_x_plus);
			d2u_ds2[j] = tI * (G53 - 2 * G31);

			exp_x_minus = exp_x_plus;
		}
	}

	/**
	 * Creates the first and second order derivatives.
	 *
	 * @param n
	 *            the peak number
	 * @param max
	 *            the maximum for the dimension
	 * @param tI
	 *            the target intensity
	 * @param deltaE
	 *            the delta E for dimension 0 (difference between the error function at the start and end of each pixel)
	 * @param du_dx
	 *            the first order x derivative for dimension 0
	 * @param du_ds
	 *            the first order s derivative for dimension 0
	 * @param d2u_dx2
	 *            the second order x derivative for dimension 0
	 * @param d2u_ds2
	 *            the second order s derivative for dimension 0
	 * @param d2deltaE_dsdx
	 *            the second order deltaE s,x derivative for dimension 0
	 * @param u
	 *            the mean of the Gaussian for dimension 0
	 * @param s
	 *            the standard deviation of the Gaussian for dimension 0
	 */
	protected static void createExSecondOrderTables(int n, int max, double tI, double[] deltaE, double[] du_dx,
			double[] du_ds, double[] d2u_dx2, double[] d2u_ds2, double[] d2deltaE_dsdx, double u, double s)
	{
		final double ss = s * s;
		final double one_sSqrt2pi = ONE_OVER_ROOT2PI / s;
		final double one_ssSqrt2pi = ONE_OVER_ROOT2PI / ss;
		final double one_sssSqrt2pi = one_sSqrt2pi / ss;
		final double one_sssssSqrt2pi = one_sssSqrt2pi / ss;
		createExSecondOrderTables(n, max, tI, ONE_OVER_ROOT2 / s, 0.5 / ss, tI * one_sSqrt2pi, tI * one_ssSqrt2pi,
				tI * one_sssSqrt2pi, ss, one_sssSqrt2pi, one_sssssSqrt2pi, deltaE, du_dx, du_ds, d2u_dx2, d2u_ds2,
				d2deltaE_dsdx, u);
	}

	/**
	 * Creates the first and second order derivatives.
	 *
	 * @param n
	 *            the peak number
	 * @param max
	 *            the maximum for the dimension
	 * @param tI
	 *            the target intensity
	 * @param one_sSqrt2
	 *            one over (s times sqrt(2))
	 * @param one_2ss
	 *            one over (2 * s^2)
	 * @param I_sSqrt2pi
	 *            the intensity over (s * sqrt(2*pi))
	 * @param I_ssSqrt2pi
	 *            the intensity over (s^2 * sqrt(2*pi))
	 * @param I_sssSqrt2pi
	 *            the intensity over (s^3 * sqrt(2*pi))
	 * @param ss
	 *            the standard deviation squared
	 * @param one_sssSqrt2pi
	 *            one over (s^3 * sqrt(2*pi))
	 * @param one_sssssSqrt2pi
	 *            one over (s^5 * sqrt(2*pi))
	 * @param deltaE
	 *            the delta E for dimension 0 (difference between the error function at the start and end of each pixel)
	 * @param du_dx
	 *            the first order x derivative for dimension 0
	 * @param du_ds
	 *            the first order s derivative for dimension 0
	 * @param d2u_dx2
	 *            the second order x derivative for dimension 0
	 * @param d2u_ds2
	 *            the second order s derivative for dimension 0
	 * @param d2deltaE_dsdx
	 *            the second order deltaE s,x derivative for dimension 0
	 * @param u
	 *            the mean of the Gaussian for dimension 0
	 */
	protected static void createExSecondOrderTables(int n, int max, double tI, double one_sSqrt2, double one_2ss,
			double I_sSqrt2pi, double I_ssSqrt2pi, double I_sssSqrt2pi, double ss, double one_sssSqrt2pi,
			double one_sssssSqrt2pi, double[] deltaE, double[] du_dx, double[] du_ds, double[] d2u_dx2,
			double[] d2u_ds2, double[] d2deltaE_dsdx, double u)
	{
		// Note: The paper by Smith, et al computes the integral for the kth pixel centred at (x,y)
		// If x=u then the Erf will be evaluated at x-u+0.5 - x-u-0.5 => integral from -0.5 to 0.5.
		// This code sets the first pixel at (0,0).

		// All computations for pixel k (=(x,y)) that require the exponential can use x,y indices for the 
		// lower boundary value and x+1,y+1 indices for the upper value.

		// Working example of this in GraspJ source code:
		// https://github.com/isman7/graspj/blob/master/graspj/src/main/java/eu/brede/graspj/opencl/src/functions/
		// I have used the same notation for clarity

		// The first position:
		// Offset x by the position and get the pixel lower bound.
		// (x - u - 0.5) with x=0 and u offset by +0.5
		double x_u_p12 = -u;
		double erf_x_minus = 0.5 * Erf.erf(x_u_p12 * one_sSqrt2);
		double exp_x_minus = FastMath.exp(-(x_u_p12 * x_u_p12 * one_2ss));
		for (int i = 0, j = n * max; i < max; i++, j++)
		{
			double x_u_m12 = x_u_p12;
			x_u_p12 += 1.0;
			final double erf_x_plus = 0.5 * Erf.erf(x_u_p12 * one_sSqrt2);
			deltaE[j] = erf_x_plus - erf_x_minus;
			erf_x_minus = erf_x_plus;

			final double exp_x_plus = FastMath.exp(-(x_u_p12 * x_u_p12 * one_2ss));
			du_dx[j] = I_sSqrt2pi * (exp_x_minus - exp_x_plus);
			// Compute: I0 * G21(xk)
			final double pre2 = (x_u_m12 * exp_x_minus - x_u_p12 * exp_x_plus);
			du_ds[j] = I_ssSqrt2pi * pre2;

			// Second derivatives
			d2u_dx2[j] = I_sssSqrt2pi * pre2;

			// Compute G31(xk)
			final double G31 = one_sssSqrt2pi * pre2;

			d2deltaE_dsdx[j] = I_ssSqrt2pi * (x_u_m12 * x_u_m12 * exp_x_minus / ss - exp_x_minus + exp_x_plus -
					x_u_p12 * x_u_p12 * exp_x_plus / ss);

			// Compute G53(xk)
			x_u_m12 = x_u_m12 * x_u_m12 * x_u_m12;
			final double ux = x_u_p12 * x_u_p12 * x_u_p12;
			final double G53 = one_sssssSqrt2pi * (x_u_m12 * exp_x_minus - ux * exp_x_plus);
			d2u_ds2[j] = tI * (G53 - 2 * G31);

			exp_x_minus = exp_x_plus;
		}
	}

	/**
	 * Evaluates an 2-dimensional Gaussian function for a single peak.
	 * 
	 * @param i
	 *            Input predictor
	 * @param duda
	 *            Partial gradient of function with respect to each coefficient
	 * @return The predicted value
	 * 
	 * @see gdsc.smlm.function.NonLinearFunction#eval(int, double[])
	 */
	public double eval(final int i, final double[] duda)
	{
		// Unpack the predictor into the dimensions
		int yy = i / maxx;
		int xx = i % maxx;

		// Return in order of Gaussian2DFunction.createGradientIndices().
		// Use pre-computed gradients
		duda[0] = 1.0;
		double I = tB;
		for (int n = 0, a = 1; n < nPeaks; n++, xx += maxx, yy += maxy)
		{
			duda[a] = deltaEx[xx] * deltaEy[yy];
			I += tI[n] * duda[a++];
			duda[a++] = du_dtx[xx] * deltaEy[yy];
			duda[a++] = du_dty[yy] * deltaEx[xx];
			duda[a++] = du_dtsx[xx] * deltaEy[yy];
			duda[a++] = du_dtsy[yy] * deltaEx[xx];
		}
		return I;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.gaussian.erf.ErfGaussian2DFunction#eval(int, double[], double[])
	 */
	public double eval(final int i, final double[] duda, final double[] d2uda2)
	{
		// Unpack the predictor into the dimensions
		int yy = i / maxx;
		int xx = i % maxx;

		// Return in order of Gaussian2DFunction.createGradientIndices().
		// Use pre-computed gradients
		duda[0] = 1.0;
		d2uda2[0] = 0;
		double I = tB;
		for (int n = 0, a = 1; n < nPeaks; n++, xx += maxx, yy += maxy)
		{
			duda[a] = deltaEx[xx] * deltaEy[yy];
			I += tI[n] * duda[a];
			d2uda2[a++] = 0;
			duda[a] = du_dtx[xx] * deltaEy[yy];
			d2uda2[a++] = d2u_dtx2[xx] * deltaEy[yy];
			duda[a] = du_dty[yy] * deltaEx[xx];
			d2uda2[a++] = d2u_dty2[yy] * deltaEx[xx];
			duda[a] = du_dtsx[xx] * deltaEy[yy];
			d2uda2[a++] = d2u_dtsx2[xx] * deltaEy[yy];
			duda[a] = du_dtsy[yy] * deltaEx[xx];
			d2uda2[a++] = d2u_dtsy2[yy] * deltaEx[xx];
		}
		return I;
	}

	@Override
	public boolean evaluatesBackground()
	{
		return true;
	}

	@Override
	public boolean evaluatesSignal()
	{
		return true;
	}

	@Override
	public boolean evaluatesShape()
	{
		return false;
	}

	@Override
	public boolean evaluatesPosition()
	{
		return true;
	}

	@Override
	public boolean evaluatesSD0()
	{
		return true;
	}

	@Override
	public boolean evaluatesSD1()
	{
		return true;
	}

	@Override
	public int getParametersPerPeak()
	{
		return 5;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.GradientFunction#forEach(gdsc.smlm.function.GradientFunction.Gradient1Procedure)
	 */
	public void forEach(Gradient1Procedure procedure)
	{
		final double[] duda = new double[getNumberOfGradients()];
		duda[0] = 1.0;
		// Note: This unrolling does not perform better in JUnit speed test
		//		// Unroll for the number of peaks
		//		if (nPeaks == 2)
		//		{
		//			for (int y = 0; y < maxy; y++)
		//			{
		//				for (int x = 0, xx = maxx, yy = maxy; x < maxx; x++, xx++, yy++)
		//				{
		//					duda[1] = deltaEx[x] * deltaEy[y];
		//					duda[2] = du_dtx[x] * deltaEy[y];
		//					duda[3] = du_dty[y] * deltaEx[x];
		//					duda[4] = du_dtsx[x] * deltaEy[y];
		//					duda[5] = du_dtsy[y] * deltaEx[x];
		//					duda[6] = deltaEx[xx] * deltaEy[yy];
		//					duda[7] = du_dtx[xx] * deltaEy[yy];
		//					duda[8] = du_dty[yy] * deltaEx[xx];
		//					duda[9] = du_dtsx[xx] * deltaEy[yy];
		//					duda[10] = du_dtsy[yy] * deltaEx[xx];
		//					procedure.execute(tB + tI[0] * duda[1] + tI[1] * duda[6], duda);
		//				}
		//			}
		//		}
		//		else
		//		{
		for (int y = 0; y < maxy; y++)
		{
			for (int x = 0; x < maxx; x++)
			{
				double I = tB;
				for (int n = 0, xx = x, yy = y, a = 1; n < nPeaks; n++, xx += maxx, yy += maxy)
				{
					duda[a] = deltaEx[xx] * deltaEy[yy];
					I += tI[n] * duda[a++];
					duda[a++] = du_dtx[xx] * deltaEy[yy];
					duda[a++] = du_dty[yy] * deltaEx[xx];
					duda[a++] = du_dtsx[xx] * deltaEy[yy];
					duda[a++] = du_dtsy[yy] * deltaEx[xx];
				}
				procedure.execute(I, duda);
			}
		}
		//		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.Gradient2Function#forEach(gdsc.smlm.function.Gradient2Procedure)
	 */
	public void forEach(Gradient2Procedure procedure)
	{
		final double[] duda = new double[getNumberOfGradients()];
		final double[] d2uda2 = new double[getNumberOfGradients()];
		duda[0] = 1.0;
		for (int y = 0; y < maxy; y++)
		{
			for (int x = 0; x < maxx; x++)
			{
				double I = tB;
				for (int n = 0, xx = x, yy = y, a = 1; n < nPeaks; n++, xx += maxx, yy += maxy)
				{
					duda[a] = deltaEx[xx] * deltaEy[yy];
					I += tI[n] * duda[a++];
					duda[a] = du_dtx[xx] * deltaEy[yy];
					d2uda2[a++] = d2u_dtx2[xx] * deltaEy[yy];
					duda[a] = du_dty[yy] * deltaEx[xx];
					d2uda2[a++] = d2u_dty2[yy] * deltaEx[xx];
					duda[a] = du_dtsx[xx] * deltaEy[yy];
					d2uda2[a++] = d2u_dtsx2[xx] * deltaEy[yy];
					duda[a] = du_dtsy[yy] * deltaEx[xx];
					d2uda2[a++] = d2u_dtsy2[yy] * deltaEx[xx];
				}
				procedure.execute(I, duda, d2uda2);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.ExtendedGradient2Function#forEach(gdsc.smlm.function.ExtendedGradient2Procedure)
	 */
	public void forEach(ExtendedGradient2Procedure procedure)
	{
		final int ng = getNumberOfGradients();
		final double[] duda = new double[ng];
		final double[] d2udadb = new double[ng * ng];
		duda[0] = 1.0;
		final double[] du_dtsx_tI = new double[du_dtsx.length];
		for (int x = 0; x < maxx; x++)
			for (int n = 0, xx = x; n < nPeaks; n++, xx += maxx)
				du_dtsx_tI[xx] = du_dtsx[xx] / tI[n];
		final double[] du_dty_tI = new double[nPeaks];
		final double[] du_dtsy_tI = new double[nPeaks];
		for (int y = 0; y < maxy; y++)
		{
			for (int n = 0, yy = y; n < nPeaks; n++, yy += maxy)
			{
				du_dty_tI[n] = du_dty[yy] / tI[n];
				du_dtsy_tI[n] = du_dtsy[yy] / tI[n];
			}
			for (int x = 0; x < maxx; x++)
			{
				double I = tB;
				for (int n = 0, xx = x, yy = y, a = 1; n < nPeaks; n++, xx += maxx, yy += maxy)
				{
					duda[a] = deltaEx[xx] * deltaEy[yy];
					I += tI[n] * duda[a];
					duda[a + 1] = du_dtx[xx] * deltaEy[yy];
					duda[a + 2] = du_dty[yy] * deltaEx[xx];
					duda[a + 3] = du_dtsx[xx] * deltaEy[yy];
					duda[a + 4] = du_dtsy[yy] * deltaEx[xx];

					// Compute all the partial second order derivatives
					final double tI = this.tI[n];

					// Background are all 0

					int k = a * ng + a;
					// Signal,X
					d2udadb[k + 1] = duda[a + 1] / tI;
					// Signal,Y
					d2udadb[k + 2] = duda[a + 2] / tI;
					// Signal,X SD
					d2udadb[k + 3] = duda[a + 3] / tI;
					// Signal,Y SD
					d2udadb[k + 4] = duda[a + 4] / tI;

					a += 5;

					int kk = k + ng;
					// X,Signal
					d2udadb[kk] = d2udadb[k + 1];
					// X,X
					d2udadb[kk + 1] = d2u_dtx2[xx] * deltaEy[yy];
					// X,Y
					d2udadb[kk + 2] = du_dtx[xx] * du_dty_tI[n];
					// X,X SD
					d2udadb[kk + 3] = deltaEy[yy] * d2deltaEx_dtsxdx[xx];
					// X,Y SD
					d2udadb[kk + 4] = du_dtx[xx] * du_dtsy_tI[n];

					int kkk = kk + ng;
					// Y,Signal
					d2udadb[kkk] = d2udadb[k + 2];
					// Y,X
					d2udadb[kkk + 1] = d2udadb[kk + 2];
					// Y,Y
					d2udadb[kkk + 2] = d2u_dty2[yy] * deltaEx[xx];
					// Y,X SD
					d2udadb[kkk + 3] = du_dty[yy] * du_dtsx_tI[xx];
					// Y,Y SD
					d2udadb[kkk + 4] = deltaEx[xx] * d2deltaEy_dtsydy[yy];

					int kkkk = kkk + ng;
					// X SD,Signal
					d2udadb[kkkk] = d2udadb[k + 3];
					// X SD,X
					d2udadb[kkkk + 1] = d2udadb[kk + 3];
					// X SD,Y
					d2udadb[kkkk + 2] = d2udadb[kkk + 3];
					// X SD,X SD
					d2udadb[kkkk + 3] = d2u_dtsx2[xx] * deltaEy[yy];
					// X SD,Y SD
					d2udadb[kkkk + 4] = du_dtsy[yy] * du_dtsx_tI[xx];

					int kkkkk = kkkk + ng;
					// Y SD,Signal
					d2udadb[kkkkk] = d2udadb[k + 4];
					// Y SD,X
					d2udadb[kkkkk + 1] = d2udadb[kk + 4];
					// Y SD,Y
					d2udadb[kkkkk + 2] = d2udadb[kkk + 4];
					// Y SD,X SD
					d2udadb[kkkkk + 3] = d2udadb[kkkk + 4];
					// Y SD,Y SD
					d2udadb[kkkkk + 4] = d2u_dtsy2[yy] * deltaEx[xx];

				}
				procedure.executeExtended(I, duda, d2udadb);
			}
		}
	}
}
