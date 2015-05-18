package gdsc.smlm.results.filter;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2013 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import java.util.Arrays;

import gdsc.smlm.results.MemoryPeakResults;

/**
 * Filter results using the combination of two filters
 */
public abstract class CombinedFilter extends Filter
{
	protected Filter filter1, filter2;

	public CombinedFilter(Filter filter1, Filter filter2)
	{
		this.filter1 = filter1;
		this.filter2 = filter2;
	}

	@Override
	protected String generateName()
	{
		StringBuilder sb = new StringBuilder();
		addText(sb, filter1, filter1.getName());
		sb.append(" ").append(getOperator()).append(" ");
		addText(sb, filter2, filter2.getName());
		return sb.toString();
	}

	private void addText(StringBuilder sb, Filter f, String text)
	{
		if (f instanceof CombinedFilter)
			sb.append("(");
		sb.append(text);
		if (f instanceof CombinedFilter)
			sb.append(")");
	}

	@Override
	protected String generateType()
	{
		StringBuilder sb = new StringBuilder();
		addText(sb, filter1, filter1.getType());
		sb.append(" ").append(getOperator()).append(" ");
		addText(sb, filter2, filter2.getType());
		return sb.toString();
	}

	/**
	 * Get the string representation of the operator used to combine the two filters. This is used in the filter name.
	 * 
	 * @return The operator
	 */
	protected abstract String getOperator();

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#setup(gdsc.smlm.results.MemoryPeakResults)
	 */
	@Override
	public void setup(MemoryPeakResults peakResults)
	{
		filter1.setup(peakResults);
		filter2.setup(peakResults);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#end()
	 */
	@Override
	public void end()
	{
		filter1.end();
		filter2.end();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getNumericalValue()
	 */
	@Override
	public double getNumericalValue()
	{
		return filter1.getNumericalValue();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getNumericalValueName()
	 */
	@Override
	public String getNumericalValueName()
	{
		return filter1.getNumericalValueName();
	}

	@Override
	public int getNumberOfParameters()
	{
		return filter1.getNumberOfParameters() + filter2.getNumberOfParameters();
	}

	@Override
	public double getParameterValue(int index)
	{
		checkIndex(index);
		if (index < filter1.getNumberOfParameters())
			return filter1.getParameterValue(index);
		return filter2.getParameterValue(index - filter1.getNumberOfParameters());
	}

	@Override
	public String getParameterName(int index)
	{
		checkIndex(index);
		if (index < filter1.getNumberOfParameters())
			return filter1.getParameterName(index);
		return filter2.getParameterName(index - filter1.getNumberOfParameters());
	}

	@Override
	public Filter adjustParameter(int index, double delta)
	{
		checkIndex(index);
		Filter f1 = filter1;
		Filter f2 = filter2;
		if (index < filter1.getNumberOfParameters())
			f1 = filter1.adjustParameter(index, delta);
		else
			f2 = filter2.adjustParameter(index - filter1.getNumberOfParameters(), delta);
		return createFilter(f1, f2);
	}

	/**
	 * Create a new combined filter from the two input filters
	 * 
	 * @param f1
	 * @param f2
	 * @return
	 */
	protected abstract Filter createFilter(Filter f1, Filter f2);

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#create(double[])
	 */
	@Override
	public Filter create(double... parameters)
	{
		double[] p1 = Arrays.copyOf(parameters, filter1.getNumberOfParameters());
		double[] p2 = Arrays.copyOfRange(parameters, filter1.getNumberOfParameters(), parameters.length);
		return createFilter(filter1.create(p1), filter2.create(p2));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#weakestParameters(double[])
	 */
	@Override
	public void weakestParameters(double[] parameters)
	{
		double[] p1 = Arrays.copyOf(parameters, filter1.getNumberOfParameters());
		double[] p2 = Arrays.copyOfRange(parameters, filter1.getNumberOfParameters(), parameters.length);
		filter1.weakestParameters(p1);
		filter2.weakestParameters(p2);
		System.arraycopy(p1, 0, parameters, 0, p1.length);
		System.arraycopy(p2, 0, parameters, p1.length, p2.length);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#subsetWithFailCount()
	 */
	@Override
	public boolean subsetWithFailCount()
	{
		return filter1.subsetWithFailCount() && filter2.subsetWithFailCount();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ga.Chromosome#length()
	 */
	public int length()
	{
		return filter1.length() + filter2.length();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#lowerLimit()
	 */
	@Override
	public double[] lowerLimit()
	{
		double[] l1 = filter1.lowerLimit();
		double[] l2 = filter2.lowerLimit();
		if (l1 == null && l2 == null)
			return null;
		return combine(getLowerLimit(filter1, l1), getLowerLimit(filter2, l2));
	}

	private double[] getLowerLimit(Filter filter, double[] lower)
	{
		if (lower == null)
		{
			// Default to zero on the lower so no need to fill
			lower = new double[filter.length()];
		}
		return lower;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#upperLimit()
	 */
	@Override
	public double[] upperLimit()
	{
		double[] u1 = filter1.upperLimit();
		double[] u2 = filter2.upperLimit();
		if (u1 == null && u2 == null)
			return null;
		return combine(getUpperLimit(filter1, u1), getUpperLimit(filter2, u2));
	}

	private double[] getUpperLimit(Filter filter, double[] upper)
	{
		if (upper == null)
		{
			upper = new double[filter.length()];
			Arrays.fill(upper, Double.POSITIVE_INFINITY);
		}
		return upper;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ga.Chromosome#sequence()
	 */
	public double[] sequence()
	{
		return combine(filter1.sequence(), filter2.sequence());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ga.Chromosome#mutationStepRange()
	 */
	public double[] mutationStepRange()
	{
		return combine(filter1.mutationStepRange(), filter2.mutationStepRange());
	}

	private static double[] combine(double[] s1, double[] s2)
	{
		double[] s = new double[s1.length + s2.length];
		System.arraycopy(s1, 0, s, 0, s1.length);
		System.arraycopy(s2, 0, s, s1.length, s2.length);
		return s;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getChromosomeParameters()
	 */
	public int[] getChromosomeParameters()
	{
		int[] s1 = filter1.getChromosomeParameters();
		int[] s2 = filter2.getChromosomeParameters();
		int[] s = new int[s1.length + s2.length];
		System.arraycopy(s1, 0, s, 0, s1.length);
		// Copy the next array but offset the index by the number of parameters in filter 1
		// so that getParameterName(int) works OK
		final int n1 = filter1.getNumberOfParameters();
		for (int i = 0, j = s1.length; i < s2.length; i++, j++)
			s[j] = s2[i] + n1;
		return s;
	}
}