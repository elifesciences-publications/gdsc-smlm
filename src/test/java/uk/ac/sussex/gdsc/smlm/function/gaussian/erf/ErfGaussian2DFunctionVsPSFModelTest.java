/*-
 * #%L
 * Genome Damage and Stability Centre SMLM ImageJ Plugins
 *
 * Software for single molecule localisation microscopy (SMLM)
 * %%
 * Copyright (C) 2011 - 2018 Alex Herbert
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package uk.ac.sussex.gdsc.smlm.function.gaussian.erf;

import org.apache.commons.math3.random.RandomDataGenerator;
import org.junit.Test;

import uk.ac.sussex.gdsc.core.utils.DoubleEquality;
import uk.ac.sussex.gdsc.smlm.function.StandardValueProcedure;
import uk.ac.sussex.gdsc.smlm.function.gaussian.Gaussian2DFunction;
import uk.ac.sussex.gdsc.smlm.function.gaussian.GaussianFunctionFactory;
import uk.ac.sussex.gdsc.smlm.model.GaussianPSFModel;
import uk.ac.sussex.gdsc.test.TestSettings;
import uk.ac.sussex.gdsc.test.junit4.TestAssert;

@SuppressWarnings({ "javadoc" })
public class ErfGaussian2DFunctionVsPSFModelTest
{
	private final int width = 10;
	private final int height = 9;

	@Test
	public void computesSameAsPSFModel()
	{
		final RandomDataGenerator r = new RandomDataGenerator(TestSettings.getRandomGenerator());
		for (int i = 0; i < 10; i++)
			//@formatter:off
			computesSameAsPSFModel(
					r.nextUniform(50, 100),
					r.nextUniform((width-1)/2.0, (width+1)/2.0),
					r.nextUniform((height-1)/2.0, (height+1)/2.0),
					r.nextUniform(0.5, 2),
					r.nextUniform(0.5, 2));
			//@formatter:on
	}

	private void computesSameAsPSFModel(double sum, double x0, double x1, double s0, double s1)
	{
		final Gaussian2DFunction f = GaussianFunctionFactory.create2D(1, width, height,
				GaussianFunctionFactory.FIT_ERF_FREE_CIRCLE, null);
		final double[] a = new double[1 + Gaussian2DFunction.PARAMETERS_PER_PEAK];
		a[Gaussian2DFunction.SIGNAL] = sum;
		a[Gaussian2DFunction.X_POSITION] = x0;
		a[Gaussian2DFunction.Y_POSITION] = x1;
		a[Gaussian2DFunction.X_SD] = s0;
		a[Gaussian2DFunction.Y_SD] = s1;
		final double[] o = new StandardValueProcedure().getValues(f, a);

		final GaussianPSFModel m = new GaussianPSFModel(s0, s1);
		final double[] e = new double[o.length];
		// Note that the Gaussian2DFunction has 0,0 at the centre of a pixel.
		// The model has 0.5,0.5 at the centre so add an offset.
		m.create2D(e, width, height, sum, x0 + 0.5, x1 + 0.5, false);

		// Since the model only computes within +/- 5 sd only check for equality
		// when the model is not zero (and there is a reasonable amount of signal)

		for (int i = 0; i < e.length; i++)
			if (e[i] > 1e-2) // Only check where there is a reasonable amount of signal
			{
				final double error = DoubleEquality.relativeError(e[i], o[i]);
				// We expect a small error since the ErfGaussian2DFunction uses a
				// fast approximation of the Erf(..) (the error function). The PSFModel
				// uses the Apache commons implementation.
				if (error > 5e-4)
					TestAssert.fail("[%d] %s != %s  error = %f\n", i, Double.toString(e[i]), Double.toString(o[i]),
							error);
			}
	}
}