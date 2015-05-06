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

import gdsc.smlm.results.MemoryPeakResults;
import gdsc.smlm.results.PeakResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

/**
 * Filter results using multiple thresholds: Signal, SNR, width, coordinate shift and precision.
 * <p>
 * Any results with the strict limits are included. Any results outside the weak limits are excluded. Any results
 * between the strict and weak limits are included only if they can be traced through time, optionally via other
 * candidates, to a valid result.
 */
public class MultiHysteresisFilter extends HysteresisFilter
{
	@XStreamAsAttribute
	final double strictSignal;
	@XStreamAsAttribute
	final float strictSnr;
	@XStreamAsAttribute
	final double strictMinWidth;
	@XStreamAsAttribute
	final double strictMaxWidth;
	@XStreamAsAttribute
	final double strictShift;
	@XStreamAsAttribute
	final double strictPrecision;
	@XStreamAsAttribute
	final double rangeSignal;
	@XStreamAsAttribute
	final float rangeSnr;
	@XStreamAsAttribute
	final double rangeMinWidth;
	@XStreamAsAttribute
	final double rangeMaxWidth;
	@XStreamAsAttribute
	final double rangeShift;
	@XStreamAsAttribute
	final double rangePrecision;

	@XStreamOmitField
	float strictSignalThreshold;
	@XStreamOmitField
	float weakSignalThreshold;
	@XStreamOmitField
	float weakSnr;
	@XStreamOmitField
	float strictMinSigmaThreshold;
	@XStreamOmitField
	float weakMinSigmaThreshold;
	@XStreamOmitField
	float strictMaxSigmaThreshold;
	@XStreamOmitField
	float weakMaxSigmaThreshold;
	@XStreamOmitField
	float strictOffset;
	@XStreamOmitField
	float weakOffset;
	@XStreamOmitField
	double strictVariance;
	@XStreamOmitField
	double weakVariance;

	@XStreamOmitField
	double nmPerPixel = 100;
	@XStreamOmitField
	boolean emCCD = true;
	@XStreamOmitField
	double gain = 1;

	public MultiHysteresisFilter(double searchDistance, int searchDistanceMode, double timeThreshold,
			int timeThresholdMode, double strictSignal, double rangeSignal, float strictSnr, float rangeSnr,
			double strictMinWidth, double rangeMinWidth, double strictMaxWidth, double rangeMaxWidth,
			double strictShift, double rangeShift, double strictPrecision, double rangePrecision)
	{
		super(searchDistance, searchDistanceMode, timeThreshold, timeThresholdMode);
		this.strictSignal = Math.max(0, strictSignal);
		this.rangeSignal = Math.max(0, rangeSignal);
		this.strictSnr = Math.max(0, strictSnr);
		this.rangeSnr = Math.max(0, rangeSnr);
		this.strictMinWidth = Math.max(0, strictMinWidth);
		this.rangeMinWidth = Math.max(0, rangeMinWidth);
		this.strictMaxWidth = Math.max(0, strictMaxWidth);
		this.rangeMaxWidth = Math.max(0, rangeMaxWidth);
		this.strictShift = Math.max(0, strictShift);
		this.rangeShift = Math.max(0, rangeShift);
		this.strictPrecision = Math.max(0, strictPrecision);
		this.rangePrecision = Math.max(0, rangePrecision);
	}

	@Override
	protected String generateName()
	{
		return String
				.format("Multi Hysteresis: Signal=%.1f-%.1f, SNR=%.1f-%.1f, MinWidth=%.2f-%.2f, MaxWidth=%.2f+%.2f, Shift=%.2f+%.2f, Precision=%.1f+%.1f (%s)",
						strictSignal, rangeSignal, strictSnr, rangeSnr, strictMinWidth, rangeMinWidth, strictMaxWidth,
						rangeMaxWidth, strictShift, rangeShift, strictPrecision, rangePrecision, getTraceParameters());
	}

	@Override
	protected String generateType()
	{
		return "Multi Hysteresis";
	}

	@Override
	public void setup(MemoryPeakResults peakResults)
	{
		// Calibration
		nmPerPixel = peakResults.getNmPerPixel();
		gain = peakResults.getGain();
		emCCD = peakResults.isEMCCD();

		// Set the signal limit using the gain
		strictSignalThreshold = (float) (strictSignal * gain);
		weakSignalThreshold = (float) ((strictSignal - rangeSignal) * gain);

		weakSnr = strictSnr - rangeSnr;

		// Set the width limit
		strictMinSigmaThreshold = weakMinSigmaThreshold = 0;
		strictMaxSigmaThreshold = weakMaxSigmaThreshold = Float.POSITIVE_INFINITY;
		// Set the shift limit
		strictOffset = weakOffset = Float.POSITIVE_INFINITY;

		Pattern pattern = Pattern.compile("initialSD0>([\\d\\.]+)");
		Matcher match = pattern.matcher(peakResults.getConfiguration());
		if (match.find())
		{
			double s = Double.parseDouble(match.group(1));
			strictMinSigmaThreshold = (float) (s * strictMinWidth);
			strictMaxSigmaThreshold = (float) (s * strictMaxWidth);
			weakMinSigmaThreshold = (float) (s * (strictMinWidth - rangeMinWidth));
			weakMaxSigmaThreshold = (float) (s * (strictMaxWidth + rangeMaxWidth));

			strictOffset = (float) (s * strictShift);
			weakOffset = (float) (s * (strictShift + rangeShift));
		}

		// Configure the precision limit
		strictVariance = PrecisionFilter.getVarianceLimit(strictPrecision);
		weakVariance = PrecisionFilter.getVarianceLimit(strictPrecision + rangePrecision);

		super.setup(peakResults);
	}

	@Override
	protected PeakStatus getStatus(PeakResult result)
	{
		// Check weak thresholds
		if (result.getSignal() < weakSignalThreshold)
			return PeakStatus.REJECT;
		final float snr = SNRFilter.getSNR(result);
		if (snr < weakSnr)
			return PeakStatus.REJECT;
		final float sd = result.getSD();
		if (sd < weakMinSigmaThreshold || sd > weakMaxSigmaThreshold)
			return PeakStatus.REJECT;
		if (Math.abs(result.getXPosition()) > weakOffset || Math.abs(result.getYPosition()) > weakOffset)
			return PeakStatus.REJECT;
		final double variance = result.getVariance(nmPerPixel, gain, emCCD);
		if (variance > weakVariance)
			return PeakStatus.REJECT;

		// Check the strict thresholds
		if (result.getSignal() < strictSignalThreshold)
			return PeakStatus.CANDIDATE;
		if (snr < strictSnr)
			return PeakStatus.CANDIDATE;
		if (sd < strictMinSigmaThreshold || sd > strictMaxSigmaThreshold)
			return PeakStatus.CANDIDATE;
		if (Math.abs(result.getXPosition()) > strictOffset || Math.abs(result.getYPosition()) > strictOffset)
			return PeakStatus.CANDIDATE;
		if (variance > strictVariance)
			return PeakStatus.CANDIDATE;
		
		return PeakStatus.OK;
	}

	@Override
	public double getNumericalValue()
	{
		return strictSnr;
	}

	@Override
	public String getNumericalValueName()
	{
		return "SNR +" + rangeSnr;
	}

	@Override
	public String getDescription()
	{
		return "Filter results using a multiple thresholds: Signal, SNR, width, shift, precision. Any results within the " +
				"strict limits are included. Any results outside the weak limits are excluded. " +
				super.getDescription();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#getNumberOfParameters()
	 */
	@Override
	public int getNumberOfParameters()
	{
		return 12 + super.getNumberOfParameters();
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
		if (index < super.getNumberOfParameters())
		{
			return super.getParameterValue(index);
		}
		index -= super.getNumberOfParameters();
		switch (index)
		{
			case 0:
				return strictSignal;
			case 1:
				return rangeSignal;
			case 2:
				return strictSnr;
			case 3:
				return rangeSnr;
			case 4:
				return strictMinWidth;
			case 5:
				return rangeMinWidth;
			case 6:
				return strictMaxWidth;
			case 7:
				return rangeMaxWidth;
			case 8:
				return strictShift;
			case 9:
				return rangeShift;
			case 10:
				return strictPrecision;
			default:
				return rangePrecision;
		}
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
		if (index < super.getNumberOfParameters())
		{
			return super.getParameterName(index);
		}
		index -= super.getNumberOfParameters();
		switch (index)
		{
			case 0:
				return "Strict Signal";
			case 1:
				return "Range Signal";
			case 2:
				return "Strict SNR";
			case 3:
				return "Range SNR";
			case 4:
				return "Strict Min Width";
			case 5:
				return "Range Min Width";
			case 6:
				return "Strict Max Width";
			case 7:
				return "Range Max Width";
			case 8:
				return "Strict Shift";
			case 9:
				return "Range Shift";
			case 10:
				return "Strict Precision";
			default:
				return "Range Precision";
		}
	}

	static double[] defaultRange = new double[]{
		0,
		0,
		0,
		0,
		SignalFilter.DEFAULT_RANGE,
		SignalFilter.DEFAULT_RANGE,
		SNRFilter.DEFAULT_RANGE,
		SNRFilter.DEFAULT_RANGE,
		WidthFilter2.DEFAULT_MIN_RANGE,
		WidthFilter2.DEFAULT_MIN_RANGE,
		WidthFilter.DEFAULT_RANGE,
		WidthFilter.DEFAULT_RANGE,
		ShiftFilter.DEFAULT_RANGE,
		ShiftFilter.DEFAULT_RANGE,
		PrecisionFilter.DEFAULT_RANGE,		
		PrecisionFilter.DEFAULT_RANGE		
	};
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#adjustParameter(int, double)
	 */
	@Override
	public Filter adjustParameter(int index, double delta)
	{
		checkIndex(index);
		// No adjustment of the mode parameters
		if (index == 1 || index == 3)
			return this;
		double[] parameters = new double[] { searchDistance, searchDistanceMode, timeThreshold, timeThresholdMode,
				strictSignal, rangeSignal, strictSnr, rangeSnr, strictMinWidth, rangeMinWidth, strictMaxWidth,
				rangeMaxWidth, strictShift, rangeShift, strictPrecision, rangePrecision };
		if (index == 0)
			parameters[0] = updateParameter(parameters[0], delta, getDefaultSearchRange());
		else if (index == 2)
			parameters[2] = updateParameter(parameters[2], delta, getDefaultTimeRange());
		else
			parameters[index] = updateParameter(parameters[index], delta, defaultRange[index]);
		return create(parameters);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#create(double[])
	 */
	@Override
	public Filter create(double... parameters)
	{
		return new MultiHysteresisFilter(parameters[0], (int) parameters[1], parameters[2], (int) parameters[3],
				parameters[4], parameters[5], (float) parameters[6], (float) parameters[7], parameters[8],
				parameters[9], parameters[10], parameters[11], parameters[12], parameters[13], parameters[14],
				parameters[15]);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.Filter#weakestParameters(double[])
	 */
	@Override
	public void weakestParameters(double[] parameters)
	{
		super.weakestParameters(parameters);
		
		// Hysteresis filters require all the potential candidates, so disable hysteresis above the candidate threshold  
		setMin(parameters, 4, strictSignal - rangeSignal);
		parameters[5] = 0;
		setMin(parameters, 6, strictSnr - rangeSnr);
		parameters[7] = 0;
		setMin(parameters, 8, strictMinWidth - rangeMinWidth);
		parameters[9] = 0;
		setMax(parameters, 10, strictMaxWidth + rangeMaxWidth);
		parameters[11] = 0;
		setMax(parameters, 12, strictShift + rangeShift);
		parameters[13] = 0;
		setMax(parameters, 14, strictPrecision + rangePrecision);
		parameters[15] = 0;
	}
}