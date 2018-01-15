package gdsc.smlm.results.filter;

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
 * Used to pass a new shift value to a filter
 */
public class ShiftFilterSetupData implements FilterSetupData
{
	public final double shift;

	/**
	 * Instantiates a new shift filter setup data.
	 *
	 * @param shift the shift
	 */
	public ShiftFilterSetupData(double shift)
	{
		this.shift = Math.max(0, shift);
	}
}
