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
package uk.ac.sussex.gdsc.smlm.filters;

import java.util.Arrays;

import org.apache.commons.rng.UniformRandomProvider;
import org.junit.jupiter.api.Test;import uk.ac.sussex.gdsc.test.junit5.SeededTest;import uk.ac.sussex.gdsc.test.junit5.RandomSeed;import uk.ac.sussex.gdsc.test.junit5.SpeedTag;

import gnu.trove.list.array.TDoubleArrayList;
import uk.ac.sussex.gdsc.test.TestSettings;
import uk.ac.sussex.gdsc.test.junit5.ExtraAssertions;

@SuppressWarnings({ "javadoc" })
public abstract class WeightedSumFilterTest extends WeightedFilterTest
{
	@Test
	public void filterPerformsWeightedSumFiltering()
	{
		final DataFilter filter = createDataFilter();

		final UniformRandomProvider rg = TestSettings.getRandomGenerator(seed.getSeed());

		final float[] offsets = getOffsets(filter);
		final int[] boxSizes = getBoxSizes(filter);

		final TDoubleArrayList l1 = new TDoubleArrayList();

		for (final int width : primes)
			for (final int height : primes)
			{
				final float[] data = createData(width, height, rg);
				l1.reset();

				// Ones used for normalisation
				final float[] ones = new float[width * height];
				Arrays.fill(ones, 1f);

				// Uniform weights
				final float[] w1 = new float[width * height];
				Arrays.fill(w1, 0.5f);

				// Weights simulating the variance of sCMOS pixels
				final float[] w2 = new float[width * height];
				for (int i = 0; i < w2.length; i++)
					w2[i] = (float) (1.0 / Math.max(0.01, rg.nextGaussian() * 0.2 + 2));

				for (final int boxSize : boxSizes)
					for (final float offset : offsets)
						for (final boolean internal : checkInternal)
						{
							// For each pixel over the range around the pixel (vi).
							// Sum filter: sum(vi)
							// Weighted sum: sum(vi * wi) / mean(wi)
							// (This makes the output image have a similar mean)

							filter.setWeights(null, width, height);

							// Uniform weights
							testfilterPerformsWeightedFiltering(filter, width, height, data, w1, boxSize, offset,
									internal, ones);

							// Random weights.
							testfilterPerformsWeightedFiltering(filter, width, height, data, w2, boxSize, offset,
									internal, ones);
						}
			}
	}

	private static void testfilterPerformsWeightedFiltering(DataFilter filter, int width, int height, float[] data,
			float[] w, int boxSize, float offset, boolean internal, float[] ones) throws AssertionError
	{
		// The filter f(x) should compute:
		//    sum(vi * wi) / mean(wi)
		// where: mean(wi) = sum(wi) / sum(1)

		filter.setWeights(null, width, height);
		final float[] fWi = filter(w, width, height, boxSize - offset, internal, filter);
		final float[] f1 = filter(ones, width, height, boxSize - offset, internal, filter);
		final float[] e = data.clone();
		for (int i = 0; i < e.length; i++)
			e[i] = data[i] * w[i];
		final float[] fViWi = filter(e, width, height, boxSize - offset, internal, filter);
		for (int i = 0; i < e.length; i++)
			e[i] = fViWi[i] / (fWi[i] / f1[i]);

		filter.setWeights(w, width, height);
		final float[] o = filter(data, width, height, boxSize - offset, internal, filter);

		ExtraAssertions.assertArrayEqualsRelative(e, o, 1e-6, "%s : [%dx%d] @ %.1f [internal=%b]", filter.name, width,
				height, boxSize - offset, internal);
	}
}
