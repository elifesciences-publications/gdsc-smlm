package gdsc.smlm.results;

import gdsc.core.data.DataException;

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
 * Specifies a peak fitting result that cannot be modified.
 * <p>
 * Any method that modifies the result will throw a data exception.
 */
public class ImmutablePeakResult extends AttributePeakResult
{
	private boolean built = false;

	/**
	 * Instantiates a new peak result.
	 *
	 * @param peakResult
	 *            the peak result
	 * @throws IllegalArgumentException
	 *             if the parameters are invalid
	 */
	public ImmutablePeakResult(PeakResult peakResult) throws IllegalArgumentException
	{
		super(peakResult);
		built = true;
	}

	@Override
	public void setBackground(float b)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setSignal(float s)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setXPosition(float x)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setYPosition(float y)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setZPosition(float z)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setFrame(int frame)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setOrigX(int origX)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setOrigY(int origY)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setOrigValue(float origValue)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setError(double error)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setNoise(float noise)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	public void setId(int id)
	{
		if (built)
			throw new DataException("This result is immutable");
		super.setId(id);
	}

	@Override
	public void setEndFrame(int endFrame)
	{
		if (built)
			throw new DataException("This result is immutable");
		super.setEndFrame(endFrame);
	}

	@Override
	public void setPrecision(double precision)
	{
		if (built)
			throw new DataException("This result is immutable");
		super.setPrecision(precision);
	}
	
	@Override
	public void setParameter(int i, float value)
	{
		throw new DataException("This result is immutable");
	}
	
	@Override
	public void setParameterDeviation(int i, float value)
	{
		throw new DataException("This result is immutable");
	}
	
	@Override
	public void clearHasEndFrame()
	{
		throw new DataException("This result is immutable");
	}
	
	@Override
	public void clearHasId()
	{
		throw new DataException("This result is immutable");
	}
	
	@Override
	public void clearHasPrecision()
	{
		throw new DataException("This result is immutable");
	}	

	/**
	 * Gets a copy of the parameters.
	 *
	 * @return the parameters
	 */
	@Override
	public float[] getParameters()
	{
		return super.getParameters().clone();
	}

	/**
	 * Gets a copy of the parameter deviations.
	 *
	 * @return the parameter deviations
	 */
	@Override
	public float[] getParameterDeviations()
	{
		return (super.getParameterDeviations() == null) ? null : super.getParameterDeviations().clone();
	}

	@Override
	void resizeParameters(int length)
	{
		throw new DataException("This result is immutable");
	}

	@Override
	void resizeParameterDeviations(int length)
	{
		throw new DataException("This result is immutable");
	}
}