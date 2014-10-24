package gdsc.smlm.ij.results;

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

public enum ResultsTable
{
	NONE("None"), UNCALIBRATED("Uncalibrated"), CALIBRATED("Calibrated");

	private String name;

	private ResultsTable(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
