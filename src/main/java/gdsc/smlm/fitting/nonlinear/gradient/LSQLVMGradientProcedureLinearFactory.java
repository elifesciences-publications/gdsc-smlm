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
public class LSQLVMGradientProcedureLinearFactory extends BaseLSQLVMGradientProcedureFactory
{
	/**
	 * Create a new gradient calculator
	 * 
	 * @param y
	 *            Data to fit
	 * @param func
	 *            Gradient function
	 * @return the gradient procedure
	 */
	public static LSQLVMGradientProcedureLinear create(final double[] y, final Gradient1Function func)
	{
		switch (func.getNumberOfGradients())
		{
			case 5:
				return new LSQLVMGradientProcedureLinear5(y, func);
			case 4:
				return new LSQLVMGradientProcedureLinear4(y, func);
			case 6:
				return new LSQLVMGradientProcedureLinear6(y, func);

			default:
				return new LSQLVMGradientProcedureLinear(y, func);
		}
	}

	// Instance method for testing
	BaseLSQLVMGradientProcedure createProcedure(final double[] y, final Gradient1Function func)
	{
		return create(y, func);
	}
}
