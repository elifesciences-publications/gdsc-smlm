package gdsc.smlm.function.gaussian.erf;

import gdsc.smlm.function.ExtendedGradient2Function;
import gdsc.smlm.function.Gradient1Procedure;
import gdsc.smlm.function.Gradient2Function;
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
public abstract class ErfGaussian2DFunction extends Gaussian2DFunction
		implements Gradient2Function, ExtendedGradient2Function
{
	public static final int Z_POSITION = 2;

	protected final static double ONE_OVER_ROOT2 = 1.0 / Math.sqrt(2);
	protected final static double ONE_OVER_ROOT2PI = 1.0 / Math.sqrt(2 * Math.PI);

	// Required for the PSF
	protected double[] deltaEx, deltaEy;
	protected double tB;

	// Required for the first gradients
	protected double[] du_dtx, du_dty, du_dtsx, du_dtsy;

	// Required for the second gradients
	protected double[] d2u_dtx2, d2u_dty2, d2u_dtsx2, d2u_dtsy2;

	/**
	 * Instantiates a new erf gaussian 2D function.
	 *
	 * @param nPeaks
	 *            The number of peaks
	 * @param maxx
	 *            The maximum x value of the 2-dimensional data (used to unpack a linear index into coordinates)
	 * @param maxy
	 *            The maximum y value of the 2-dimensional data (used to unpack a linear index into coordinates)
	 */
	public ErfGaussian2DFunction(int nPeaks, int maxx, int maxy)
	{
		super(maxx, maxy);
		deltaEx = new double[nPeaks * this.maxx];
		deltaEy = new double[nPeaks * this.maxy];
	}

	/**
	 * Creates the arrays needed to compute the first-order partial derivatives.
	 */
	protected void create1Arrays()
	{
		if (du_dtx != null)
			return;
		du_dtx = new double[deltaEx.length];
		du_dty = new double[deltaEy.length];
		du_dtsx = new double[deltaEx.length];
		du_dtsy = new double[deltaEy.length];
	}

	/**
	 * Creates the arrays needed to compute the first and second order partial derivatives.
	 */
	protected void create2Arrays()
	{
		if (d2u_dtx2 != null)
			return;
		d2u_dtx2 = new double[deltaEx.length];
		d2u_dty2 = new double[deltaEy.length];
		d2u_dtsx2 = new double[deltaEx.length];
		d2u_dtsy2 = new double[deltaEy.length];
		create1Arrays();
	}

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
	 * @param duda
	 *            Partial first gradient of function with respect to each coefficient
	 * @param d2uda2
	 *            Partial second gradient of function with respect to each coefficient
	 * @return The predicted value
	 */
	public abstract double eval(final int i, final double[] duda, final double[] d2uda2);

	// Force new implementation from the base Gaussian2DFunction
	@Override
	public abstract void forEach(Gradient1Procedure procedure);

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.NonLinearFunction#initialise(double[])
	 */
	public void initialise(double[] a)
	{
		// The base Gaussian2DFunction does all the work in NonLinearFunction#initialise(double[]).
		// The ERF Gaussian2DFunction does all the work in Gradient1Function.initialise1(double[])  
		initialise1(a);
	}

	// Force new implementation from the base Gaussian2DFunction
	@Override
	public abstract void initialise0(double[] a);

	// Force new implementation from the base Gaussian2DFunction
	@Override
	public abstract void initialise1(double[] a);

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.ExtendedGradient2Function#initialiseExtended2(double[])
	 */
	public void initialiseExtended2(double[] a)
	{
		initialise2(a);
	}
}
