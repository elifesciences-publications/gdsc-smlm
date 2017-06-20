package gdsc.smlm.results;

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
 * Contains helper functions for working with peak results
 */
public class PeakResultHelper
{
	/**
	 * Convert the local background to an estimate of noise. Local background and noise are in ADU count units.
	 * <p>
	 * This assumes the local background is photon shot noise. The background is first converted to photons using the
	 * gain. The shot noise is taken assuming a Poisson distribution (thus the variance equals the number of photons).
	 * This is amplified by 2 if the data was taken on an EM-CCD camera. The square root is the noise in photons. This
	 * is converted back to ADUs using the gain. E.G.
	 * 
	 * <pre>
	 * return Math.sqrt((background / gain) * ((emCCD) ? 2 : 1)) * gain;
	 * </pre>
	 *
	 * @param background
	 *            the background
	 * @param gain
	 *            the gain
	 * @param emCCD
	 *            True if an emCCD camera
	 * @return the noise estimate
	 */
	public static double localBackgroundToNoise(double background, double gain, boolean emCCD)
	{
		if (background <= 0)
			return 0;
		return Math.sqrt((background / gain) * ((emCCD) ? 2 : 1)) * gain;
	}

	/**
	 * Convert the noise to local background. Local background and noise are in ADU count units.
	 * <p>
	 * This assumes the local background is photon shot noise. This is the opposite conversion to
	 * {@link #localBackgroundToNoise(double, double, boolean)}.
	 *
	 * @param noise
	 *            the noise
	 * @param gain
	 *            the gain
	 * @param emCCD
	 *            True if an emCCD camera
	 * @return the local background estimate
	 */
	public static double noiseToLocalBackground(double noise, double gain, boolean emCCD)
	{
		if (noise <= 0)
			return 0;
		noise /= gain;
		noise *= noise;
		if (emCCD)
			noise /= 2;
		return noise * gain;
	}
}