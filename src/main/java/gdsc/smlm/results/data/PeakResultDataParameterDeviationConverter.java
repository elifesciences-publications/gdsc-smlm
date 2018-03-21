package gdsc.smlm.results.data;

import gdsc.core.data.utils.Converter;
import gdsc.smlm.results.PeakResult;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2018 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

/**
 * Gets a parameter data value from a result.
 */
public class PeakResultDataParameterDeviationConverter extends PeakResultDataFloatConverter
{
	/** The parameter index. */
	public final int index;

	/**
	 * Instantiates a new peak result parameter value.
	 *
	 * @param converter
	 *            the converter
	 * @param index
	 *            the index
	 */
	public PeakResultDataParameterDeviationConverter(Converter converter, int index)
	{
		super(converter);
		this.index = index;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultData#getValue(gdsc.smlm.results.PeakResult)
	 */
	public Float getValue(PeakResult result)
	{
		return converter.convert(result.getParameterDeviation(index));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultData#getValueName()
	 */
	public String getValueName()
	{
		return PeakResult.getParameterName(index) + " Deviation";
	}
}