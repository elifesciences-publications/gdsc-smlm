package gdsc.smlm.function.gaussian.erf;

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
{
	public static final int Z_POSITION = 2;

	protected final boolean noGradients;

	/**
	 * Instantiates a new erf gaussian 2D function.
	 *
	 * @param maxx
	 *            The maximum x value of the 2-dimensional data (used to unpack a linear index into coordinates)
	 * @param maxy
	 *            The maximum y value of the 2-dimensional data (used to unpack a linear index into coordinates)
	 * @param noGradients
	 *            Set to true if the gradients are not required
	 */
	public ErfGaussian2DFunction(int maxx, int maxy, boolean noGradients)
	{
		super(maxx, maxy);
		this.noGradients = noGradients;
	}

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

	// TODO - Add function support for computing the second derivatives directly in a Newton-Raphson method

}