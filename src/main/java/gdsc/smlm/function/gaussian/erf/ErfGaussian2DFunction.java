package gdsc.smlm.function.gaussian.erf;

import gdsc.smlm.function.Gradient1Procedure;
import gdsc.smlm.function.Gradient2Function;
import gdsc.smlm.function.ValueProcedure;
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
 * Abstract base class for an 2-dimensional Gaussian function for a configured number of peaks.
 * <p>
 * The function will calculate the value of the Gaussian and evaluate the gradient of a set of parameters. The class can
 * specify which of the following parameters the function will evaluate:<br/>
 * background, signal, z-depth, position0, position1, sd0, sd1
 * <p>
 * The class provides an index of the position in the parameter array where the parameter is expected.
 */
public abstract class ErfGaussian2DFunction extends Gaussian2DFunction implements Gradient2Function
{
	public static final int Z_POSITION = 2;

	protected final static double ONE_OVER_ROOT2 = 1.0 / Math.sqrt(2);
	protected final static double ONE_OVER_ROOT2PI = 1.0 / Math.sqrt(2 * Math.PI);

	protected final byte derivativeOrder;

	// Required for the PSF
	protected final double[] deltaEx, deltaEy;
	protected double tB, tI;

	// Required for the first gradients
	protected double[] du_dtx, du_dty, du_dtsx, du_dtsy;

	// Required for the second gradients
	protected double[] d2u_dtx2, d2u_dty2, d2u_dtsx2, d2u_dtsy2;

	/**
	 * Instantiates a new erf gaussian 2D function.
	 *
	 * @param maxx
	 *            The maximum x value of the 2-dimensional data (used to unpack a linear index into coordinates)
	 * @param maxy
	 *            The maximum y value of the 2-dimensional data (used to unpack a linear index into coordinates)
	 * @param derivativeOrder
	 *            Set to the order of partial derivatives required
	 */
	public ErfGaussian2DFunction(int maxx, int maxy, int derivativeOrder)
	{
		super(maxx, maxy);
		this.derivativeOrder = (byte) derivativeOrder;
		deltaEx = new double[this.maxx];
		deltaEy = new double[this.maxy];
		createArrays();
	}

	protected abstract void createArrays();

	@Override
	public int getDerivativeOrder()
	{
		return derivativeOrder;
	}

	@Override
	public boolean isOverhead(int derivativeOrder)
	{
		return derivativeOrder < this.derivativeOrder;
	}

	// Force implementation by making abstract
	@Override
	abstract public ErfGaussian2DFunction create(int derivativeOrder);

	/**
	 * Copy the function.
	 *
	 * @return a copy
	 */
	@Override
	abstract public ErfGaussian2DFunction copy();
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.gaussian.Gaussian2DFunction#getShapeName()
	 */
	@Override
	protected String getShapeName()
	{
		// The shape parameter is used for the z-position
		return "Z";
	}

	/**
	 * Evaluates an 2-dimensional Gaussian function for a single peak.
	 * 
	 * @param i
	 *            Input predictor
	 * @return The Gaussian value
	 * 
	 * @see gdsc.fitting.function.NonLinearFunction#eval(int)
	 */
	public double eval(final int i)
	{
		// Unpack the predictor into the dimensions
		final int y = i / maxx;
		final int x = i % maxx;

		return tB + tI * deltaEx[x] * deltaEy[y];
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
	public abstract double eval(final int i, final double[] duda, final double[] d2uda2);
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.GradientFunction#forEach(gdsc.smlm.function.GradientFunction.ValueProcedure)
	 */
	public void forEach(ValueProcedure procedure)
	{
		for (int y = 0; y < maxy; y++)
		{
			final double tI_deltaEy = tI * deltaEy[y];
			for (int x = 0; x < maxx; x++)
			{
				procedure.execute(tB + tI_deltaEy * deltaEx[x]);
			}
		}
	}

	// Force implementation
	@Override
	public abstract int getNumberOfGradients();

	// Force implementation
	@Override
	public abstract void forEach(Gradient1Procedure procedure);
}
