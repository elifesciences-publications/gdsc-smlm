package gdsc.smlm.data.config;

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
 * Exception to throw if configuration is invalid
 */
public class ConfigurationException extends DataException
{
	private static final long serialVersionUID = 1048790767265799169L;

	public ConfigurationException()
	{
		super();
	}

	public ConfigurationException(String message)
	{
		super(message);
	}

	public ConfigurationException(String message, Throwable cause)
	{
		super(message, cause);
	}

	public ConfigurationException(Throwable cause)
	{
		super(cause);
	}
}
