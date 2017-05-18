package gdsc.smlm.fitting.nonlinear.gradient;

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
 * Create a gradient procedure.
 */
public class LSQLVMGradientProcedureMatrixFactory extends BaseLSQLVMGradientProcedureFactory
{
	/**
	 * Create a new gradient procedure
	 * 
	 * @param y
	 *            Data to fit
	 * @param b
	 *            Baseline pre-computed y-values
	 * @param func
	 *            Gradient function
	 * @return the gradient procedure
	 */
	public static LSQLVMGradientProcedureMatrix create(final double[] y, final double[] b, final Gradient1Function func)
	{
		switch (func.getNumberOfGradients())
		{
			case 5:
				return new LSQLVMGradientProcedureMatrix5(y, b, func);
			case 4:
				return new LSQLVMGradientProcedureMatrix4(y, b, func);
			case 6:
				return new LSQLVMGradientProcedureMatrix6(y, b, func);

			default:
				return new LSQLVMGradientProcedureMatrix(y, b, func);
		}
	}

	// Instance method for testing
	BaseLSQLVMGradientProcedure createProcedure(final double[] y, final double[] b, final Gradient1Function func)
	{
		return create(y, b, func);
	}
}
