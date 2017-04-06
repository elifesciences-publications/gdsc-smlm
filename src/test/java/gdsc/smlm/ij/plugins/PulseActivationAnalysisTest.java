package gdsc.smlm.ij.plugins;

import java.util.Iterator;

import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;
import org.apache.commons.math3.util.CombinatoricsUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test the PulseActivationAnalysis unmixing functions
 */
public class PulseActivationAnalysisTest
{
	@Test
	public void canLinearlyUnmix2Channels()
	{
		for (int n = 0; n <= 2; n++)
			for (int m = 0; m <= 2; m++)
				canLinearlyUnmix2Channels(n, m);
	}

	private void canLinearlyUnmix2Channels(int n, int m)
	{
		RandomGenerator r = new Well19937c(30051977);
		try
		{
			for (int loop = 0; loop < 10; loop++)
			{
				// A rough mix of each channel
				double[] d = create(2, r, 100, 100);

				// Crosstalk should be below 50%
				double[] c = create(2, r, 0, 0.5);

				// Enumerate
				Iterator<int[]> it = CombinatoricsUtils.combinationsIterator(2, n);
				while (it.hasNext())
				{
					final int[] channels = it.next();
					double[] dd = new double[2];
					for (int i : channels)
						dd[i] = d[i];

					Iterator<int[]> it2 = CombinatoricsUtils.combinationsIterator(2, m);
					while (it2.hasNext())
					{
						final int[] crosstalk = it2.next();
						double[] cc = new double[2];
						for (int i : crosstalk)
							cc[i] = c[i];

						canLinearlyUnmix2Channels(dd[0], dd[1], cc[0], cc[1]);
					}
				}
			}
		}
		catch (AssertionError e)
		{
			throw new AssertionError(String.format("channels=%d, crosstalk=%d", n, m), e);
		}
	}

	private double[] create(int size, RandomGenerator r, double min, double range)
	{
		double[] d = new double[size];
		for (int i = 0; i < size; i++)
			d[i] = min + r.nextDouble() * range;
		return d;
	}

	private void canLinearlyUnmix2Channels(double d1, double d2, double C21, double C12)
	{
		// Solving:
		// D1 = d1 + C21 * d2
		// D2 = d2 + C12 * d1

		double D1 = d1 + C21 * d2;
		double D2 = d2 + C12 * d1;

		double[] d = PulseActivationAnalysis.unmix(D1, D2, C21, C12);

		//System.out.printf("d1 %f = %f, d2 %f = %f (C=%f,%f)\n", d1, d[0], d2, d[1], C21, C12);
		Assert.assertEquals("d1", d1, d[0], delta(d1));
		Assert.assertEquals("d2", d2, d[1], delta(d2));
	}

	private double delta(double d)
	{
		d *= 1e-10;
		// Set a limit where we are close enough. This mainly applies when d is zero.
		if (d < 1e-8)
			return 1e-8;
		return d;
	}

	@Test
	public void canLinearlyUnmix3Channels()
	{
		for (int n = 0; n <= 3; n++)
			for (int m = 0; m <= 6; m++)
				canLinearlyUnmix3Channels(n, m);
	}

	private void canLinearlyUnmix3Channels(int n, int m)
	{
		RandomGenerator r = new Well19937c(30051977);
		try
		{
			for (int loop = 0; loop < 10; loop++)
			{
				// A rough mix of each channel
				double[] d = create(6, r, 100, 100);

				// Total crosstalk per channel should be below 50%
				double[] c = create(6, r, 0, 0.25);

				// Enumerate
				Iterator<int[]> it = CombinatoricsUtils.combinationsIterator(3, n);
				while (it.hasNext())
				{
					final int[] channels = it.next();
					double[] dd = new double[3];
					for (int i : channels)
						dd[i] = d[i];

					Iterator<int[]> it2 = CombinatoricsUtils.combinationsIterator(6, m);
					while (it2.hasNext())
					{
						final int[] crosstalk = it2.next();
						double[] cc = new double[6];
						for (int i : crosstalk)
							cc[i] = c[i];

						canLinearlyUnmix3Channels(dd[0], dd[1], dd[2], cc[0], cc[1], cc[2], cc[3], cc[4], cc[5]);
					}
				}
			}
		}
		catch (AssertionError e)
		{
			throw new AssertionError(String.format("channels=%d, crosstalk=%d", n, m), e);
		}
	}

	public void canLinearlyUnmix3Channels(double d1, double d2, double d3, double C21, double C31, double C12,
			double C32, double C13, double C23)
	{
		// Solving:
		// D1 = d1 + C21 * d2 + C31 * d3
		// D2 = d2 + C12 * d1 + C32 * d3
		// D3 = d3 + C13 * d1 + C23 * d2

		double D1 = d1 + C21 * d2 + C31 * d3;
		double D2 = d2 + C12 * d1 + C32 * d3;
		double D3 = d3 + C13 * d1 + C23 * d2;

		double[] d = PulseActivationAnalysis.unmix(D1, D2, D3, C21, C31, C12, C32, C13, C23);

		//System.out.printf("d1 %f = %f, d2 %f = %f, d3 %f = %f (C=%f,%f,%f,%f,%f)\n", d1, d[0], d2, d[1], d3, d[2], C21, C31, C12, C32, C13, C23);
		Assert.assertEquals("d1", d1, d[0], delta(d1));
		Assert.assertEquals("d2", d2, d[1], delta(d2));
		Assert.assertEquals("d3", d3, d[2], delta(d3));
	}
}