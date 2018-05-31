package gdsc.smlm.function;

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
 * Wraps a value function to add a pre-computed offset to the value during the forEach procedure
 */
public class OffsetValueFunction extends PrecomputedValueFunction implements ValueFunction, ValueProcedure, NamedFunction
{
	protected final ValueFunction f;
	protected int i;
	protected ValueProcedure procedure;

	/**
	 * Instantiates a new offset value function.
	 *
	 * @param f
	 *            the function
	 * @param values
	 *            the precomputed values
	 * @throws IllegalArgumentException
	 *             if the values length does not match the function size
	 */
	protected OffsetValueFunction(ValueFunction f, double[] values)
	{
		super(values);
		if (f.size() != values.length)
			throw new IllegalArgumentException("Length of precomputed values must match function size");
		this.f = f;
	}

	/**
	 * Instantiates a new offset value function by combining the current precomputed values with more precomputed
	 * values. This is used internally and so no checks are made on the size of values arrays (which must match).
	 *
	 * @param pre
	 *            the pre-computed function
	 * @param values2
	 *            the second set of precomputed values
	 */
	protected OffsetValueFunction(OffsetValueFunction pre, double[] values2)
	{
		// Clone the values as they will be modified
		super(values2.clone());
		this.f = pre.f;
		final int n = f.size();
		final double[] values1 = pre.values;
		for (int i = 0; i < n; i++)
			values[i] += values1[i];
	}

	public ValueFunction getValueFunction()
	{
		return f;
	}

	public void initialise0(double[] a)
	{
		f.initialise0(a);
	}

	public void forEach(ValueProcedure procedure)
	{
		this.procedure = procedure;
		i = 0;
		f.forEach(this);
	}

	public void execute(double value)
	{
		procedure.execute(value + values[i++]);
	}

	/**
	 * Wrap a function with pre-computed values.
	 *
	 * @param func
	 *            the function
	 * @param b
	 *            Baseline pre-computed y-values
	 * @return the wrapped function (or the original if pre-computed values are null or wrong length)
	 */
	public static ValueFunction wrapValueFunction(final ValueFunction func, final double[] b)
	{
		if (b != null && b.length == func.size())
		{
			// Avoid multiple wrapping
			if (func instanceof OffsetValueFunction)
			{
				return new OffsetValueFunction((OffsetValueFunction) func, b);
			}
			return new OffsetValueFunction(func, b);
		}
		return func;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.function.NamedFunction#getParameterName(int)
	 */
	public String getParameterName(int i)
	{
		if (f instanceof NamedFunction)
		{
			return ((NamedFunction) f).getParameterName(i);
		}
		return "Unknown";
	}
}