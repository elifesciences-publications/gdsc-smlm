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
package gdsc.smlm.results.filter;

import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

import gdsc.smlm.data.config.ConfigurationException;
import gdsc.smlm.results.Gaussian2DPeakResultCalculator;
import gdsc.smlm.results.Gaussian2DPeakResultHelper;
import gdsc.smlm.results.MemoryPeakResults;


import gdsc.smlm.results.PeakResult;

/**
 * Filter results using a precision threshold. Calculates the precision using the true fitted background if a bias is
 * provided.
 */
public class PrecisionFilter2 extends DirectFilter implements IMultiFilter
{
	@XStreamAsAttribute
	final double precision;
	@XStreamOmitField
	double variance;
	@XStreamOmitField
	boolean useBackground = false;
	@XStreamOmitField
	private Gaussian2DPeakResultCalculator calculator;

	public PrecisionFilter2(double precision)
	{
		this.precision = Math.max(0, precision);
	}

	@Override
	public void setup(MemoryPeakResults peakResults)
	{
		try
		{
			calculator = Gaussian2DPeakResultHelper.create(peakResults.getPSF(), peakResults.getCalibration(),
					Gaussian2DPeakResultHelper.LSE_PRECISION_X);
			useBackground = true;
		}
		catch (ConfigurationException e)
		{
			calculator = Gaussian2DPeakResultHelper.create(peakResults.getPSF(), peakResults.getCalibration(),
					Gaussian2DPeakResultHelper.LSE_PRECISION);
			useBackground = false;
		}
		variance = Filter.getDUpperSquaredLimit(precision);
	}

	@Override
	public boolean accept(PeakResult peak)
	{
		if (useBackground)
		{
			// Use the estimated background for the peak
			return calculator.getLSEPrecision(peak.getParameters()) <= variance;
		}
		// Use the background noise to estimate precision 
		return calculator.getLSEPrecision(peak.getParameters(), peak.getNoise()) <= variance;
	}

	public int getValidationFlags()
	{
		return V_LOCATION_VARIANCE2;
	}

	@Override
	public int validate(final PreprocessedPeakResult peak)
	{
		if (peak.getLocationVariance2() > variance)
			return V_LOCATION_VARIANCE2;
		return 0;
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
	 * @see gdsc.smlm.results.filter.Filter#getParameterValueInternal(int)
	 */
	@Override
	protected double getParameterValueInternal(int index)
	{
		return precision;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getParameterIncrement(int)
	 */
	@Override
	public double getParameterIncrement(int index)
	{
		checkIndex(index);
		return PrecisionFilter.DEFAULT_INCREMENT;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getParameterType(int)
	 */
	@Override
	public ParameterType getParameterType(int index)
	{
		checkIndex(index);
		return ParameterType.PRECISION2;
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
	 * @see gdsc.smlm.results.filter.DirectFilter#lowerBoundOrientation(int)
	 */
	@Override
	public int lowerBoundOrientation(int index)
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

	public PrecisionType getPrecisionType()
	{
		return PrecisionType.ESTIMATE_USING_LOCAL_BACKGROUND;
	}

	public double getMinZ()
	{
		return 0;
	}

	public double getMaxZ()
	{
		return 0;
	}
}
