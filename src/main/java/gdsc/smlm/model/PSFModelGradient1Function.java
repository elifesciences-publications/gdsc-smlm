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
package gdsc.smlm.model;

import gdsc.core.utils.SimpleArrayUtils;
import gdsc.smlm.function.Gradient1Function;
import gdsc.smlm.function.Gradient1Procedure;
import gdsc.smlm.function.NamedFunction;
import gdsc.smlm.function.ValueProcedure;

/**
 * A wrapper around a PSF Model for the Gradient1Function interface
 */
public class PSFModelGradient1Function implements Gradient1Function, NamedFunction
{
	private static final int[] gradientIndices = SimpleArrayUtils.newArray(5, 0, 1);
	private final PSFModel psf;
	private final int width;
	private final int height;

	private double[] a;

	public PSFModelGradient1Function(PSFModel psf, int width, int height)
	{
		if (psf == null)
			throw new NullPointerException("PSF is null");
		if (width < 1)
			throw new IllegalArgumentException("Width cannot be less than 1");
		if (height < 1)
			throw new IllegalArgumentException("Height cannot be less than 1");
		if ((double) width * height > Integer.MAX_VALUE)
			throw new IllegalArgumentException("width*height is too large");
		this.psf = psf;
		this.width = width;
		this.height = height;
	}

	public int size()
	{
		return width * height;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The parameters must be [background,intensity,x,y,z]
	 * 
	 * @see gdsc.smlm.function.ValueFunction#initialise0(double[])
	 */
	public void initialise0(double[] a)
	{
		this.a = a;
	}

	public void forEach(ValueProcedure procedure)
	{
		final double[] v = new double[size()];
		final double c = a[0];
		final double m = a[1];
		final double x0 = a[2];
		final double x1 = a[3];
		final double x2 = a[4];
		if (!psf.getValue(width, height, x0, x1, x2, v))
			throw new RuntimeException("Unable to compute value");
		for (int i = 0; i < v.length; i++)
			procedure.execute(c + m * v[i]);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The parameters must be [background,intensity,x,y,z]
	 * 
	 * @see gdsc.smlm.function.GradientFunction#initialise(double[])
	 */
	public void initialise(double[] a)
	{
		initialise0(a);
	}

	public int[] gradientIndices()
	{
		return gradientIndices;
	}

	public int getNumberOfGradients()
	{
		return 5;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The parameters must be [background,intensity,x,y,z]
	 * 
	 * @see gdsc.smlm.function.Gradient1Function#initialise1(double[])
	 */
	public void initialise1(double[] a)
	{
		initialise0(a);
	}

	public void forEach(Gradient1Procedure procedure)
	{
		final double[] v = new double[size()];
		final double[][] g = new double[v.length][];
		final double c = a[0];
		final double m = a[1];
		final double x0 = a[2];
		final double x1 = a[3];
		final double x2 = a[4];
		if (!psf.getValueAndGradient(width, height, x0, x1, x2, v, g))
			throw new RuntimeException("Unable to compute value and gradient");
		final double[] df_da = new double[5];
		df_da[0] = 1;
		for (int i = 0; i < v.length; i++)
		{
			df_da[1] = v[i];
			df_da[2] = m * g[i][0];
			df_da[3] = m * g[i][1];
			df_da[4] = m * g[i][2];
			procedure.execute(c + m * v[i], df_da);
		}
	}

	public String getParameterName(int i)
	{
		switch (i)
		{
			case 0:
				return "Background";
			case 1:
				return "Intensity";
			case 2:
				return "X";
			case 3:
				return "Y";
			case 4:
				return "Z";
			default:
				return "N/A";
		}
	}
}
