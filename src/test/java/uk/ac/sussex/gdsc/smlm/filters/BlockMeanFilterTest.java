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

import java.util.ArrayList;
import java.util.Arrays;

import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.junit.Test;
import org.junit.internal.ArrayComparisonFailure;

import uk.ac.sussex.gdsc.core.utils.FloatEquality;
import uk.ac.sussex.gdsc.test.TestLog;
import uk.ac.sussex.gdsc.test.TestSettings;
import uk.ac.sussex.gdsc.test.junit4.TestAssume;

@SuppressWarnings({ "javadoc" })
public class BlockMeanFilterTest extends AbstractFilterTest
{
	private final int InternalITER3 = 500;
	private final int InternalITER = 50;
	private final int ITER3 = 200;
	private final int ITER = 20;

	/**
	 * Do a simple and stupid mean filter.
	 *
	 * @param data
	 *            the data
	 * @param maxx
	 *            the maxx
	 * @param maxy
	 *            the maxy
	 * @param boxSize
	 *            the box size
	 */
	public static void mean(float[] data, int maxx, int maxy, float boxSize)
	{
		if (boxSize <= 0)
			return;

		final int n = (int) Math.ceil(boxSize);
		final int size = 2 * n + 1;
		final float[] weight = new float[size];
		Arrays.fill(weight, 1);
		if (boxSize != n)
			weight[0] = weight[weight.length - 1] = boxSize - (n - 1);

		double norm = 0;
		for (int yy = 0; yy < size; yy++)
			for (int xx = 0; xx < size; xx++)
				norm += weight[yy] * weight[xx];
		norm = 1.0 / norm;

		final float[] out = new float[data.length];

		final int[] oy = new int[size];
		final int[] ox = new int[size];

		for (int y = 0; y < maxy; y++)
		{
			// Cache offset
			for (int yy = 0; yy < size; yy++)
			{
				int yyy = y + yy - n;
				if (yyy < 0)
					yyy = 0;
				else if (yyy >= maxy)
					yyy = maxy - 1;
				oy[yy] = yyy * maxx;
			}

			for (int x = 0; x < maxx; x++)
			{
				// Cache offset
				for (int xx = 0; xx < size; xx++)
				{
					int xxx = x + xx - n;
					if (xxx < 0)
						xxx = 0;
					else if (xxx >= maxx)
						xxx = maxx - 1;
					ox[xx] = xxx;
				}

				double sum = 0;
				for (int yy = 0; yy < size; yy++)
				{
					//int yyy = y + yy - n;
					//if (yyy < 0)
					//	yyy = 0;
					//else if (yyy >= maxy)
					//	yyy = maxy - 1;

					final int index = oy[yy];
					final float wy = weight[yy];
					for (int xx = 0; xx < size; xx++)
						sum += data[index + ox[xx]] * wy * weight[xx];
				}
				out[y * maxx + x] = (float) (sum * norm);
			}
		}
		System.arraycopy(out, 0, data, 0, out.length);
	}

	/**
	 * Do a simple and stupid mean filter with weights.
	 *
	 * @param data
	 *            the data
	 * @param w
	 *            the weights
	 * @param maxx
	 *            the maxx
	 * @param maxy
	 *            the maxy
	 * @param boxSize
	 *            the box size
	 */
	public static void weightedMean(float[] data, float[] w, int maxx, int maxy, float boxSize)
	{
		if (boxSize <= 0)
			return;

		final int n = (int) Math.ceil(boxSize);
		final int size = 2 * n + 1;
		final float[] weight = new float[size];
		Arrays.fill(weight, 1);
		if (boxSize != n)
			weight[0] = weight[weight.length - 1] = boxSize - (n - 1);

		final float[] out = new float[data.length];

		for (int y = 0; y < maxy; y++)
			for (int x = 0; x < maxx; x++)
			{
				double sum = 0, norm = 0;
				for (int yy = 0; yy < size; yy++)
				{
					int yyy = y + yy - n;
					if (yyy < 0)
						yyy = 0;
					if (yyy >= maxy)
						yyy = maxy - 1;
					for (int xx = 0; xx < size; xx++)
					{
						int xxx = x + xx - n;
						if (xxx < 0)
							xxx = 0;
						if (xxx >= maxx)
							xxx = maxx - 1;
						final int index = yyy * maxx + xxx;
						final double w2 = w[index] * weight[yy] * weight[xx];
						sum += data[index] * w2;
						norm += w2;
					}
				}
				out[y * maxx + x] = (float) (sum / norm);
			}
		System.arraycopy(out, 0, data, 0, out.length);
	}

	/**
	 * Used to test the filter methods calculate the correct result
	 */
	private abstract class BlockMeanDataFilter extends DataFilter
	{
		public BlockMeanDataFilter(String name, boolean isInterpolated)
		{
			super(name, isInterpolated);
		}

		BlockMeanFilter f = new BlockMeanFilter();

		@Override
		public void setWeights(float[] w, int width, int height)
		{
			f.setWeights(w, width, height);
		}
	}

	private static void meanIsCorrect(float[] data, int width, int height, float boxSize, boolean internal,
			BlockMeanDataFilter filter) throws ArrayComparisonFailure
	{
		final float[] data1 = data.clone();
		final float[] data2 = data.clone();
		final FloatEquality eq = new FloatEquality(1e-3f, 1e-10f);

		mean(data1, width, height, boxSize);
		if (internal)
		{
			filter.filterInternal(data2, width, height, boxSize);
			floatArrayEquals(eq, data1, data2, width, height, boxSize, "Internal arrays do not match: [%dx%d] @ %.1f",
					width, height, boxSize);
		}
		else
		{
			filter.filter(data2, width, height, boxSize);
			floatArrayEquals(eq, data1, data2, width, height, 0, "Arrays do not match: [%dx%d] @ %.1f", width, height,
					boxSize);
		}
	}

	private static void weightedMeanIsCorrect(float[] data, float[] w, int width, int height, float boxSize, boolean internal,
			BlockMeanDataFilter filter) throws ArrayComparisonFailure
	{
		final float[] data1 = data.clone();
		final float[] data2 = data.clone();
		final FloatEquality eq = new FloatEquality(1e-3f, 1e-10f);

		weightedMean(data1, w, width, height, boxSize);

		//// Check the weights do not alter the image mean
		//double u1 = Maths.sum(data) / data.length;
		//double u2 = Maths.sum(data1) / data.length;
		//System.out.printf("[%dx%d] @ %.1f : %g => %g  (%g)\n", width, height, boxSize, u1, u2,
		//		DoubleEquality.relativeError(u1, u2));

		if (internal)
		{
			filter.filterInternal(data2, width, height, boxSize);
			floatArrayEquals(eq, data1, data2, width, height, boxSize, "Internal arrays do not match: [%dx%d] @ %.1f",
					width, height, boxSize);
		}
		else
		{
			filter.filter(data2, width, height, boxSize);
			floatArrayEquals(eq, data1, data2, width, height, 0, "Arrays do not match: [%dx%d] @ %.1f", width, height,
					boxSize);
		}
	}

	private static void checkIsCorrect(BlockMeanDataFilter filter)
	{
		final RandomGenerator rg = TestSettings.getRandomGenerator();
		final ExponentialDistribution ed = new ExponentialDistribution(rg, 57,
				ExponentialDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);

		for (final int width : primes)
			for (final int height : primes)
			{
				final float[] data = createData(rg, width, height);

				filter.f.setWeights(null, 0, 0);
				for (final float boxSize : boxSizes)
					for (final boolean internal : checkInternal)
					{
						meanIsCorrect(data, width, height, boxSize, internal, filter);
						if (filter.isInterpolated)
						{
							meanIsCorrect(data, width, height, boxSize - 0.3f, internal, filter);
							meanIsCorrect(data, width, height, boxSize - 0.6f, internal, filter);
						}
					}

				// Uniform weights
				final float[] w = new float[width * height];
				Arrays.fill(w, 1);
				filter.f.setWeights(w, width, height);
				for (final float boxSize : boxSizes)
					for (final boolean internal : checkInternal)
					{
						weightedMeanIsCorrect(data, w, width, height, boxSize, internal, filter);
						if (filter.isInterpolated)
						{
							weightedMeanIsCorrect(data, w, width, height, boxSize - 0.3f, internal, filter);
							weightedMeanIsCorrect(data, w, width, height, boxSize - 0.6f, internal, filter);
						}
					}

				// Weights simulating the variance of sCMOS pixels
				for (int i = 0; i < w.length; i++)
					w[i] = (float) (1.0 / Math.max(0.01, ed.sample()));

				filter.f.setWeights(w, width, height);
				for (final float boxSize : boxSizes)
					for (final boolean internal : checkInternal)
					{
						weightedMeanIsCorrect(data, w, width, height, boxSize, internal, filter);
						if (filter.isInterpolated)
						{
							weightedMeanIsCorrect(data, w, width, height, boxSize - 0.3f, internal, filter);
							weightedMeanIsCorrect(data, w, width, height, boxSize - 0.6f, internal, filter);
						}
					}
			}
	}

	@Test
	public void blockFilterIsCorrect()
	{
		final BlockMeanDataFilter filter = new BlockMeanDataFilter("block", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.blockFilter(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.blockFilterInternal(data, width, height, boxSize);
			}
		};
		checkIsCorrect(filter);
	}

	@Test
	public void stripedBlockFilterIsCorrect()
	{
		final BlockMeanDataFilter filter = new BlockMeanDataFilter("stripedBlock", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterInternal(data, width, height, boxSize);
			}
		};
		checkIsCorrect(filter);
	}

	@Test
	public void rollingBlockFilterIsCorrect()
	{
		final BlockMeanDataFilter filter = new BlockMeanDataFilter("rollingBlock", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.rollingBlockFilter(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.rollingBlockFilterInternal(data, width, height, (int) boxSize);
			}
		};
		checkIsCorrect(filter);
	}

	private void speedTest(BlockMeanDataFilter fast, BlockMeanDataFilter slow)
	{
		speedTest(fast, slow, boxSizes);
	}

	private void speedTest(BlockMeanDataFilter fast, BlockMeanDataFilter slow, int[] testBoxSizes)
	{
		TestAssume.assumeSpeedTest();

		ArrayList<float[]> dataSet = getSpeedData(ITER3);

		final ArrayList<Long> fastTimes = new ArrayList<>();

		final float[] boxSizes = new float[testBoxSizes.length];
		final float offset = (fast.isInterpolated && slow.isInterpolated) ? 0.3f : 0;
		for (int i = 0; i < boxSizes.length; i++)
			boxSizes[i] = testBoxSizes[i] - offset;

		// Initialise
		for (final float boxSize : boxSizes)
		{
			fast.filter(dataSet.get(0).clone(), speedPrimes[0], speedPrimes[0], boxSize);
			slow.filter(dataSet.get(0).clone(), speedPrimes[0], speedPrimes[0], boxSize);
		}

		for (final float boxSize : boxSizes)
		{
			final int iter = (boxSize == 1) ? ITER3 : ITER;
			for (final int width : speedPrimes)
				for (final int height : speedPrimes)
				{
					dataSet = getSpeedData(iter);

					long time = System.nanoTime();
					for (final float[] data : dataSet)
						fast.filter(data, width, height, boxSize);
					time = System.nanoTime() - time;
					fastTimes.add(time);
				}
		}

		long slowTotal = 0, fastTotal = 0;
		int index = 0;
		for (final float boxSize : boxSizes)
		{
			final int iter = (boxSize == 1) ? ITER3 : ITER;
			long boxSlowTotal = 0, boxFastTotal = 0;
			for (final int width : speedPrimes)
				for (final int height : speedPrimes)
				{
					dataSet = getSpeedData(iter);

					long time = System.nanoTime();
					for (final float[] data : dataSet)
						slow.filter(data, width, height, boxSize);
					time = System.nanoTime() - time;

					final long fastTime = fastTimes.get(index++);
					slowTotal += time;
					fastTotal += fastTime;
					boxSlowTotal += time;
					boxFastTotal += fastTime;
					if (debug)
						System.out.printf("%s [%dx%d] @ %.1f : %d => %s %d = %.2fx\n", slow.name, width, height,
								boxSize, time, fast.name, fastTime, speedUpFactor(time, fastTime));
				}
			//if (debug)
			TestLog.logSpeedTestStageResult(boxFastTotal < boxSlowTotal, "%s %.1f : %d => %s %d = %.2fx\n",
					slow.name, boxSize, boxSlowTotal, fast.name, boxFastTotal,
					speedUpFactor(boxSlowTotal, boxFastTotal));
		}
		TestLog.logSpeedTestResult(fastTotal < slowTotal, "%s %d => %s %d = %.2fx\n", slow.name, slowTotal,
				fast.name, fastTotal, speedUpFactor(slowTotal, fastTotal));
	}

	private void speedTestInternal(BlockMeanDataFilter fast, BlockMeanDataFilter slow)
	{
		speedTestInternal(fast, slow, boxSizes);
	}

	private void speedTestInternal(BlockMeanDataFilter fast, BlockMeanDataFilter slow, int[] testBoxSizes)
	{
		TestAssume.assumeSpeedTest();

		ArrayList<float[]> dataSet = getSpeedData(InternalITER3);

		final ArrayList<Long> fastTimes = new ArrayList<>();

		final float[] boxSizes = new float[testBoxSizes.length];
		final float offset = (fast.isInterpolated && slow.isInterpolated) ? 0.3f : 0;
		for (int i = 0; i < boxSizes.length; i++)
			boxSizes[i] = testBoxSizes[i] - offset;

		// Initialise
		for (final float boxSize : boxSizes)
		{
			fast.filterInternal(floatClone(dataSet.get(0)), speedPrimes[0], speedPrimes[0], boxSize);
			slow.filterInternal(floatClone(dataSet.get(0)), speedPrimes[0], speedPrimes[0], boxSize);
		}

		for (final float boxSize : boxSizes)
		{
			final int iter = (boxSize == 1) ? InternalITER3 : InternalITER;
			for (final int width : speedPrimes)
				for (final int height : speedPrimes)
				{
					dataSet = getSpeedData(iter);

					long time = System.nanoTime();
					for (final float[] data : dataSet)
						fast.filterInternal(data, width, height, boxSize);
					time = System.nanoTime() - time;
					fastTimes.add(time);
				}
		}

		long slowTotal = 0, fastTotal = 0;
		int index = 0;
		for (final float boxSize : boxSizes)
		{
			final int iter = (boxSize == 1) ? InternalITER3 : InternalITER;
			long boxSlowTotal = 0, boxFastTotal = 0;
			for (final int width : speedPrimes)
				for (final int height : speedPrimes)
				{
					dataSet = getSpeedData(iter);

					long time = System.nanoTime();
					for (final float[] data : dataSet)
						slow.filterInternal(data, width, height, boxSize);
					time = System.nanoTime() - time;

					final long fastTime = fastTimes.get(index++);
					slowTotal += time;
					fastTotal += fastTime;
					boxSlowTotal += time;
					boxFastTotal += fastTime;
					if (debug)
						System.out.printf("Internal %s [%dx%d] @ %.1f : %d => %s %d = %.2fx\n", slow.name, width,
								height, boxSize, time, fast.name, fastTime, speedUpFactor(time, fastTime));
				}
			//if (debug)
			TestLog.logSpeedTestStageResult(boxFastTotal < boxSlowTotal,
					"Internal %s %.1f : %d => %s %d = %.2fx\n", slow.name, boxSize, boxSlowTotal, fast.name,
					boxFastTotal, speedUpFactor(boxSlowTotal, boxFastTotal));
		}
		TestLog.logSpeedTestResult(fastTotal < slowTotal, "Internal %s %d => %s %d = %.2fx\n", slow.name,
				slowTotal, fast.name, fastTotal, speedUpFactor(slowTotal, fastTotal));
	}

	@Test
	public void stripedBlockIsFasterThanBlock()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("block", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.blockFilter(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.blockFilterInternal(data, width, height, (int) boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("stripedBlock", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterInternal(data, width, height, (int) boxSize);
			}
		};

		speedTest(fast, slow);
		speedTestInternal(fast, slow);
	}

	@Test
	public void interpolatedStripedBlockIsFasterThanBlock()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("block", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.blockFilter(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.blockFilterInternal(data, width, height, boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("stripedBlock", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterInternal(data, width, height, boxSize);
			}
		};

		speedTest(fast, slow);
		speedTestInternal(fast, slow);
	}

	@Test
	public void rollingBlockIsFasterThanBlock()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("block", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.blockFilter(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.blockFilterInternal(data, width, height, (int) boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("rollingBlock", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.rollingBlockFilter(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.rollingBlockFilterInternal(data, width, height, (int) boxSize);
			}
		};

		speedTest(fast, slow);
		speedTestInternal(fast, slow);
	}

	@Test
	public void rollingBlockIsFasterThanStripedBlock()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("stripedBlock", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterInternal(data, width, height, (int) boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("rollingBlock", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.rollingBlockFilter(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.rollingBlockFilterInternal(data, width, height, (int) boxSize);
			}
		};

		speedTest(fast, slow);
		speedTestInternal(fast, slow);
	}

	@Test
	public void stripedBlock3x3IsFasterThanStripedBlockNxN()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("stripedBlockNxN", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxN(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxNInternal(data, width, height, (int) boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("stripedBlock3x3", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter3x3(data, width, height);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter3x3Internal(data, width, height);
			}
		};

		final int[] testBoxSizes = new int[] { 1 };
		speedTest(fast, slow, testBoxSizes);
		speedTestInternal(fast, slow, testBoxSizes);
	}

	@Test
	public void interpolatedStripedBlock3x3IsFasterThanStripedBlockNxN()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("stripedBlockNxN", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxN(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxNInternal(data, width, height, boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("stripedBlock3x3", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter3x3(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter3x3Internal(data, width, height, boxSize);
			}
		};

		final int[] testBoxSizes = new int[] { 1 };
		speedTest(fast, slow, testBoxSizes);
		speedTestInternal(fast, slow, testBoxSizes);
	}

	@Test
	public void stripedBlock5x5IsFasterThanStripedBlockNxN()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("stripedBlockNxN", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxN(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxNInternal(data, width, height, (int) boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("stripedBlock5x5", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter5x5(data, width, height);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter5x5Internal(data, width, height);
			}
		};

		final int[] testBoxSizes = new int[] { 2 };
		speedTest(fast, slow, testBoxSizes);
		speedTestInternal(fast, slow, testBoxSizes);
	}

	@Test
	public void interpolatedStripedBlock5x5IsFasterThanStripedBlockNxN()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("stripedBlockNxN", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxN(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxNInternal(data, width, height, boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("stripedBlock5x5", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter5x5(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter5x5Internal(data, width, height, boxSize);
			}
		};

		final int[] testBoxSizes = new int[] { 2 };
		speedTest(fast, slow, testBoxSizes);
		speedTestInternal(fast, slow, testBoxSizes);
	}

	@Test
	public void stripedBlock7x7IsFasterThanStripedBlockNxN()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("stripedBlockNxN", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxN(data, width, height, (int) boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxNInternal(data, width, height, (int) boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("stripedBlock7x7", false)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter7x7(data, width, height);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter7x7Internal(data, width, height);
			}
		};

		final int[] testBoxSizes = new int[] { 3 };
		speedTest(fast, slow, testBoxSizes);
		speedTestInternal(fast, slow, testBoxSizes);
	}

	@Test
	public void interpolatedStripedBlock7x7IsFasterThanStripedBlockNxN()
	{
		final BlockMeanDataFilter slow = new BlockMeanDataFilter("stripedBlockNxN", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxN(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilterNxNInternal(data, width, height, boxSize);
			}
		};
		final BlockMeanDataFilter fast = new BlockMeanDataFilter("stripedBlock7x7", true)
		{
			@Override
			public void filter(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter7x7(data, width, height, boxSize);
			}

			@Override
			public void filterInternal(float[] data, int width, int height, float boxSize)
			{
				f.stripedBlockFilter7x7Internal(data, width, height, boxSize);
			}
		};

		final int[] testBoxSizes = new int[] { 3 };
		speedTest(fast, slow, testBoxSizes);
		speedTestInternal(fast, slow, testBoxSizes);
	}
}