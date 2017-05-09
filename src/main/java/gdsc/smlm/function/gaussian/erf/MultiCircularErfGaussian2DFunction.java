package gdsc.smlm.function.gaussian.erf;

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
public class MultiCircularErfGaussian2DFunction extends MultiFreeCircularErfGaussian2DFunction
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
	public MultiCircularErfGaussian2DFunction(int nPeaks, int maxx, int maxy)
	{
		super(nPeaks, maxx, maxy);
	}

	@Override
	protected int[] createGradientIndices()
	{
		return replicateGradientIndices(SingleCircularErfGaussian2DFunction.gradientIndices);
	}

	@Override
	public ErfGaussian2DFunction copy()
	{
		return new MultiCircularErfGaussian2DFunction(nPeaks, maxx, maxy);
	}

	public void initialise0(double[] a)
	{
		tB = a[Gaussian2DFunction.BACKGROUND];
		for (int n = 0, i = 0; n < nPeaks; n++, i += 6)
		{
			tI[n] = a[i + Gaussian2DFunction.SIGNAL];
			// Pre-compute the offset by 0.5
			final double tx = a[i + Gaussian2DFunction.X_POSITION] + 0.5;
			final double ty = a[i + Gaussian2DFunction.Y_POSITION] + 0.5;
			final double s = a[i + Gaussian2DFunction.X_SD];
			final double one_sSqrt2 = ONE_OVER_ROOT2 / s;

			createDeltaETable(n, maxx, one_sSqrt2, deltaEx, tx);
			createDeltaETable(n, maxy, one_sSqrt2, deltaEy, ty);
		}
	}

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
			final double s = a[i + Gaussian2DFunction.X_SD];

			// We can pre-compute part of the derivatives for position and sd in arrays 
			// since the Gaussian is XY separable
			final double one_sSqrt2 = ONE_OVER_ROOT2 / s;
			final double one_2ss = 0.5 / (s * s);
			final double I_sSqrt2pi = tI[n] * ONE_OVER_ROOT2PI / s;
			final double I_ssSqrt2pi = tI[n] * ONE_OVER_ROOT2PI / (s * s);

			// We can pre-compute part of the derivatives for position and sd in arrays 
			// since the Gaussian is XY separable
			createFirstOrderTables(n, maxx, one_sSqrt2, one_2ss, I_sSqrt2pi, I_ssSqrt2pi, deltaEx, du_dtx, du_dtsx, tx);
			createFirstOrderTables(n, maxy, one_sSqrt2, one_2ss, I_sSqrt2pi, I_ssSqrt2pi, deltaEy, du_dty, du_dtsy, ty);
		}
	}

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
			final double s = a[i + Gaussian2DFunction.X_SD];

			// We can pre-compute part of the derivatives for position and sd in arrays 
			// since the Gaussian is XY separable
			final double one_sSqrt2pi = ONE_OVER_ROOT2PI / s;
			final double ss = s * s;
			final double one_sSqrt2 = ONE_OVER_ROOT2 / s;
			final double one_2ss = 0.5 / ss;
			final double I_sSqrt2pi = tI[n] * ONE_OVER_ROOT2PI / s;
			final double I_ssSqrt2pi = tI[n] * ONE_OVER_ROOT2PI / ss;
			final double I_sssSqrt2pi = I_sSqrt2pi / ss;
			final double one_sssSqrt2pi = one_sSqrt2pi / ss;
			final double one_sssssSqrt2pi = one_sssSqrt2pi / ss;

			// We can pre-compute part of the derivatives for position and sd in arrays 
			// since the Gaussian is XY separable
			createSecondOrderTables(n, maxx, tI[n], one_sSqrt2, one_2ss, I_sSqrt2pi, I_ssSqrt2pi, I_sssSqrt2pi, ss,
					one_sssSqrt2pi, one_sssssSqrt2pi, deltaEx, du_dtx, du_dtsx, d2u_dtx2, d2u_dtsx2, tx);
			createSecondOrderTables(n, maxy, tI[n], one_sSqrt2, one_2ss, I_sSqrt2pi, I_ssSqrt2pi, I_sssSqrt2pi, ss,
					one_sssSqrt2pi, one_sssssSqrt2pi, deltaEy, du_dty, du_dtsy, d2u_dty2, d2u_dtsy2, ty);
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
			duda[a++] = du_dtsx[xx] * deltaEy[yy] + du_dtsy[yy] * deltaEx[xx];
		}
		return I;
	}

	/**
	 * Evaluates an 2-dimensional Gaussian function for a single peak.
	 * 
	 * @param i
	 *            Input predictor
	 * @param duda
	 *            Partial first gradient of function with respect to each coefficient
	 * @param d2uda2
	 *            Partial second gradient of function with respect to each coefficient
	 * @return The predicted value
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
			duda[a] = du_dtsx[xx] * deltaEy[yy] + du_dtsy[yy] * deltaEx[xx];
			//@formatter:off
			d2uda2[a++] = d2u_dtsx2[xx] * deltaEy[yy] + 
					      d2u_dtsy2[yy] * deltaEx[xx] +
					      2 * du_dtsx[xx] * du_dtsy[yy] / tI[n];
			//@formatter:on
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
		return false;
	}

	@Override
	public int getParametersPerPeak()
	{
		return 4;
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
					duda[a++] = du_dtsx[xx] * deltaEy[yy] + du_dtsy[yy] * deltaEx[xx];
				}
				procedure.execute(I, duda);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.GradientFunction#forEach(gdsc.smlm.function.GradientFunction.Gradient2Procedure)
	 */
	public void forEach(Gradient2Procedure procedure)
	{
		final double[] duda = new double[getNumberOfGradients()];
		final double[] d2uda2 = new double[getNumberOfGradients()];
		final double[] two_du_dtsy_tI = new double[nPeaks];
		duda[0] = 1.0;
		for (int y = 0; y < maxy; y++)
		{
			for (int n = 0, yy = y; n < nPeaks; n++, yy += maxy)
				two_du_dtsy_tI[n] = 2 * this.du_dtsy[yy] / tI[n];
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
					duda[a] = du_dtsx[xx] * deltaEy[yy] + du_dtsy[yy] * deltaEx[xx];
					//@formatter:off
    				d2uda2[a++] = d2u_dtsx2[xx] * deltaEy[yy] + 
    							  d2u_dtsy2[yy] * deltaEx[xx] + 
    						      du_dtsx[xx] * two_du_dtsy_tI[n];
    				//@formatter:on
				}
				procedure.execute(I, duda, d2uda2);
			}

		}
	}
}