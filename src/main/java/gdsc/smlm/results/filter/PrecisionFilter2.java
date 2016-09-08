package gdsc.smlm.results.filter;

import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

import gdsc.smlm.results.MemoryPeakResults;

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

import gdsc.smlm.results.PeakResult;

/**
 * Filter results using a precision threshold. Calculates the precision using the true fitted background if a bias is
 * provided.
 */
public class PrecisionFilter2 extends MultiPathFilter implements IMultiFilter
{
	@XStreamAsAttribute
	final double precision;
	@XStreamOmitField
	double variance;
	@XStreamOmitField
	double nmPerPixel = 100;
	@XStreamOmitField
	boolean emCCD = true;
	@XStreamOmitField
	double bias = -1;
	@XStreamOmitField
	double gain = 1;

	public PrecisionFilter2(double precision)
	{
		this.precision = Math.max(0, precision);
	}

	@Override
	protected String generateName()
	{
		return "Precision2 " + precision;
	}

	@Override
	protected String generateType()
	{
		return "Precision2";
	}

	@Override
	public void setup(MemoryPeakResults peakResults)
	{
		variance = Filter.getDUpperSquaredLimit(precision);
		nmPerPixel = peakResults.getNmPerPixel();
		gain = peakResults.getGain();
		emCCD = peakResults.isEMCCD();
		bias = -1;
		if (peakResults.getCalibration() != null)
		{
			bias = peakResults.getCalibration().bias;
		}
	}

	@Override
	public boolean accept(PeakResult peak)
	{
		final double s = nmPerPixel * peak.getSD();
		final double N = peak.getSignal();
		if (bias != -1)
		{
			// Use the estimated background for the peak
			return PeakResult.getVarianceX(nmPerPixel, s, N / gain,
					Math.max(0, peak.getBackground() - bias) / gain, emCCD) <= variance;
		}
		// Use the background noise to estimate precision 
		return PeakResult.getVariance(nmPerPixel, s, N / gain, peak.getNoise() / gain, emCCD) <= variance;
	}

	@Override
	public boolean accept(PreprocessedPeakResult peak)
	{
		// Note: There is currently no distinction between estimating variance 
		// using the local background or the background noise since the variance is precomputed
		return peak.getLocationVariance() <= variance;
	}

	@Override
	public double getNumericalValue()
	{
		return precision;
	}

	@Override
	public String getNumericalValueName()
	{
		return "Precision";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getDescription()
	 */
	@Override
	public String getDescription()
	{
		return "Filter results using an upper precision threshold (uses fitted background to set noise).";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getNumberOfParameters()
	 */
	@Override
	public int getNumberOfParameters()
	{
		return 1;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getParameterValue(int)
	 */
	@Override
	public double getParameterValue(int index)
	{
		checkIndex(index);
		return precision;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getParameterName(int)
	 */
	@Override
	public String getParameterName(int index)
	{
		checkIndex(index);
		return "Precision";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#adjustParameter(int, double)
	 */
	@Override
	public Filter adjustParameter(int index, double delta)
	{
		checkIndex(index);
		return new PrecisionFilter2(updateParameter(precision, delta, PrecisionFilter.DEFAULT_RANGE));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#create(double[])
	 */
	@Override
	public Filter create(double... parameters)
	{
		return new PrecisionFilter2(parameters[0]);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#weakestParameters(double[])
	 */
	@Override
	public void weakestParameters(double[] parameters)
	{
		setMax(parameters, 0, precision);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ga.Chromosome#length()
	 */
	public int length()
	{
		return 1;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#upperLimit()
	 */
	@Override
	public double[] upperLimit()
	{
		return new double[] { PrecisionFilter.UPPER_LIMIT };
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ga.Chromosome#sequence()
	 */
	public double[] sequence()
	{
		return new double[] { precision };
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ga.Chromosome#mutationStepRange()
	 */
	public double[] mutationStepRange()
	{
		return new double[] { PrecisionFilter.DEFAULT_RANGE };
	}
	
	public double getSignal()
	{
		return 0;
	}

	public double getSNR()
	{
		return 0;
	}

	public double getMinWidth()
	{
		return 0;
	}

	public double getMaxWidth()
	{
		return 0;
	}

	public double getShift()
	{
		return 0;
	}

	public double getEShift()
	{
		return 0;
	}

	public double getPrecision()
	{
		return precision;
	}

	public boolean isPrecisionUsesLocalBackground()
	{
		return true;
	}
}