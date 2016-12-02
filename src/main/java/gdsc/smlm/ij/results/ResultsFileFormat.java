package gdsc.smlm.ij.results;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2016 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

public enum ResultsFileFormat
{
	//@Formatter:off
	GDSC_TEXT {
		@Override public String getName()      { return "Text"; }
		@Override public String getExtension() { return "xls"; }
	},
	GDSC_BINARY{
		@Override public String getName()      { return "Binary"; }
		@Override public String getExtension() { return "bin"; }
	}, 
	TSF{
		@Override public String getName()      { return "Tagged Spot File"; }
		@Override public String getExtension() { return "tsf"; }
	}, 
	MALK{
		@Override public String getName()      { return "Molecular Accuracy Localisation Keep"; }
		@Override public String getExtension() { return "txt"; }
	};
	//@Formatter:on
	
	@Override
	public String toString()
	{
		return getName();
	}
	
	/**
	 * Gets the name.
	 *
	 * @return the name
	 */
	abstract public String getName();

	/**
	 * Gets the extension.
	 *
	 * @return the extension
	 */
	abstract public String getExtension();
}
