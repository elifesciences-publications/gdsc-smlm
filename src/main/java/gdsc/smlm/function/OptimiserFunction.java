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
package gdsc.smlm.function;


import java.util.Arrays;

import gnu.trove.list.array.TDoubleArrayList;

/**
 * Allow optimisation using Apache Commons Math 3 Optimiser
 */
public abstract class OptimiserFunction
{
	protected TDoubleArrayList x = null;
	protected TDoubleArrayList y = null;

	public void addPoint(double x, double y)
	{
		if (this.x == null)
		{
			this.x = new TDoubleArrayList();
			this.y = new TDoubleArrayList();
		}
		this.x.add(x);
		this.y.add(y);
	}

	public void addData(float[] x, float[] y)
	{
		this.x = new TDoubleArrayList();
		this.y = new TDoubleArrayList();
		for (int i = 0; i < x.length; i++)
		{
			this.x.add((double) x[i]);
			this.y.add((double) y[i]);
		}
	}

	public void addData(double[] x, double[] y)
	{
		this.x = new TDoubleArrayList();
		this.y = new TDoubleArrayList();
		for (int i = 0; i < x.length; i++)
		{
			this.x.add(x[i]);
			this.y.add(y[i]);
		}
	}

	public double[] getX()
	{
		return x.toArray();
	}

	public double[] getY()
	{
		return y.toArray();
	}

	public double[] getWeights()
	{
		double[] w = new double[y.size()];
		Arrays.fill(w, 1);
		return w;
	}

	public int size()
	{
		return x.size();
	}
}
