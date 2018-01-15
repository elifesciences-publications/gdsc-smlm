package gdsc.smlm.results.filter;

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

/**
 * Support direct filtering of PreprocessedPeakResult objects.
 * <p>
 * The decision to support for filtering as both a DirectFilter and Filter concurrently is left to the implementing
 * class. It is not a requirement.
 */
public interface IDirectFilter
{
	/**
	 * Validation flag for the signal in photons
	 */
	final static int V_PHOTONS = 0x000000001;

	/**
	 * Validation flag for the SNR
	 */
	final static int V_SNR = 0x000000002;

	/**
	 * Validation flag for the noise
	 */
	final static int V_NOISE = 0x000000004;

	/**
	 * Validation flag for the location variance
	 */
	final static int V_LOCATION_VARIANCE = 0x000000008;

	/**
	 * Validation flag for the location variance using the local background
	 */
	final static int V_LOCATION_VARIANCE2 = 0x000000010;

	/**
	 * Validation flag for the average peak standard deviation in the X and Y dimension
	 */
	final static int V_SD = 0x000000020;

	/**
	 * Validation flag for the background
	 */
	final static int V_BACKGROUND = 0x000000040;

	/**
	 * Validation flag for the amplitude
	 */
	final static int V_AMPLITUDE = 0x000000080;

	/**
	 * Validation flag for the angle (for an elliptical Gaussian peak)
	 */
	final static int V_ANGLE = 0x000000100;

	/**
	 * Validation flag for the x position
	 */
	final static int V_X = 0x000000200;

	/**
	 * Validation flag for the y position
	 */
	final static int V_Y = 0x000000400;

	/**
	 * Validation flag for the relative x position shift squared
	 */
	final static int V_X_RELATIVE_SHIFT = 0x000000800;

	/**
	 * Validation flag for the relative y position shift squared
	 */
	final static int V_Y_RELATIVE_SHIFT = 0x000001000;

	/**
	 * Validation flag for the x-dimension standard deviation
	 */
	final static int V_X_SD = 0x000002000;

	/**
	 * Validation flag for the y-dimension standard deviation
	 */
	final static int V_Y_SD = 0x000004000;

	/**
	 * Validation flag for the x-dimension width factor
	 */
	final static int V_X_SD_FACTOR = 0x000008000;

	/**
	 * Validation flag for the y-dimension width factor
	 */
	final static int V_Y_SD_FACTOR = 0x000010000;

	/**
	 * Validation flag for the location variance using the fitted x/y parameter Cramér-Rao lower bound
	 */
	final static int V_LOCATION_VARIANCE_CRLB = 0x000020000;

	/**
	 * Disable filtering using the width of the result
	 */
	final static int NO_WIDTH = 0x000000001;

	/**
	 * Disable filtering using the shift of the result
	 */
	final static int NO_SHIFT = 0x000000002;

	/**
	 * Called before the accept method is called for PreprocessedPeakResult
	 * <p>
	 * This should be called once to initialise the filter before processing a batch of results.
	 * 
	 * @see #validate(PreprocessedPeakResult)
	 */
	void setup();

	/**
	 * Called before the accept method is called for PreprocessedPeakResult. The flags can control the type of filtering
	 * requested. Filters are asked to respect the flags defined in this class.
	 * <p>
	 * This should be called once to initialise the filter before processing a batch of results.
	 * 
	 * @param flags
	 *            Flags used to control the filter
	 * @see #validate(PreprocessedPeakResult)
	 */
	void setup(final int flags);

	/**
	 * Called before the accept method is called for PreprocessedPeakResult. The filter data can control the
	 * type of filtering requested.
	 * <p>
	 * This should be called once to initialise the filter before processing a batch of results.
	 *
	 * @param flags
	 *            Flags used to control the filter
	 * @param filterSetupData
	 *            Data used to control the filter
	 * @see #validate(PreprocessedPeakResult)
	 */
	void setup(final FilterSetupData... filterSetupData);

	/**
	 * Filter the peak result.
	 * <p>
	 * Calls {@link #validate(PreprocessedPeakResult)} and stores the result. This can be obtained using
	 * {@link #getResult()}.
	 * 
	 * @param peak
	 *            The peak result
	 * @return true if the peak should be accepted
	 */
	boolean accept(final PreprocessedPeakResult peak);

	/**
	 * Filter the peak result.
	 * 
	 * @param peak
	 *            The peak result
	 * @return zero if the peak should be accepted, otherwise set to flags indicating the field that failed validation.
	 */
	int validate(final PreprocessedPeakResult peak);

	/**
	 * Return the type of filter. This should be a DirectFilter.
	 * 
	 * @return Should return DirectFilter
	 */
	FilterType getFilterType();

	/**
	 * Return the result flag generated during the last call to {@link #accept(PreprocessedPeakResult)}.
	 * 
	 * @return the validation result from the last call to {@link #accept(PreprocessedPeakResult)}
	 */
	int getResult();

	/**
	 * Copy this filter.
	 *
	 * @return the copy
	 */
	IDirectFilter copy();
}