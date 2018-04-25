package gdsc.smlm.function;

import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.integration.SimpsonIntegrator;
import org.apache.commons.math3.distribution.CustomPoissonDistribution;
import org.apache.commons.math3.exception.TooManyEvaluationsException;
import org.junit.Assert;
import org.junit.Test;

import gdsc.core.utils.DoubleEquality;
import gnu.trove.list.array.TDoubleArrayList;

@SuppressWarnings("unused")
public class PoissonFunctionTest
{
	static double[] gain = { 0.25, 0.5, 0.7, 1, 1.5, 1.7, 2, 2.2, 4, 8, 16 };
	static double[] photons = { 0.25, 0.5, 1, 2, 4, 10, 100, 1000 };

	@Test
	public void cumulativeProbabilityIsOne()
	{
		for (int j = 0; j < gain.length; j++)
			for (int i = 0; i < photons.length; i++)
			{
				int[] result = cumulativeProbabilityIsOne(gain[j], photons[i]);
				//System.out.printf("minRange[%d][%d] = %d;\n", j, i, result[0]);
				//System.out.printf("maxRange[%d][%d] = %d;\n", j, i, result[1]);
			}
	}

	private int[] cumulativeProbabilityIsOne(final double gain, final double mu)
	{
		final double o = mu;

		PoissonFunction f = new PoissonFunction(1.0 / gain);
		double p = 0;

		TDoubleArrayList values = new TDoubleArrayList();

		double maxp = 0;
		int maxc = 0;

		// Evaluate an initial range. 
		// Poisson will have mean mu with a variance mu. 
		// At large mu it is approximately normal so use 3 sqrt(mu) for the range added to the mean

		int[] range = getRange(gain, mu);
		int min = range[0];
		int max = range[1];
		for (int x = min; x <= max; x++)
		{
			final double pp = f.likelihood(x, o);
			//System.out.printf("x=%d, p=%f\n", x, pp);
			p += pp;
			values.add(pp);
			if (maxp < pp)
			{
				maxp = pp;
				maxc = x;
			}
		}
		if (p > 1.01)
			Assert.fail("P > 1: " + p);

		// We have most of the probability density. 
		// Now keep evaluating up and down until no difference
		final double changeTolerance = 1e-6;
		if (min > 0)
		{
			values.reverse();
			for (int x = min - 1; x >= 0; x--)
			{
				min = x;
				final double pp = f.likelihood(x, o);
				//System.out.printf("x=%d, p=%f\n", x, pp);
				p += pp;
				values.add(pp);
				if (maxp < pp)
				{
					maxp = pp;
					maxc = x;
				}
				if (pp == 0 || pp / p < changeTolerance)
					break;
			}
			values.reverse();
		}
		for (int x = max + 1;; x++)
		{
			max = x;
			final double pp = f.likelihood(x, o);
			//System.out.printf("x=%d, p=%f\n", x, pp);
			p += pp;
			values.add(pp);
			if (maxp < pp)
			{
				maxp = pp;
				maxc = x;
			}
			if (pp == 0 || pp / p < changeTolerance)
				break;
		}

		// Find the range for 99.5% of the sum
		double[] h = values.toArray();
		// Find cumulative
		for (int i = 1; i < h.length; i++)
		{
			h[i] += h[i - 1];
		}
		int minx = 0, maxx = h.length - 1;
		while (h[minx + 1] < 0.0025)
			minx++;
		while (h[maxx - 1] > 0.9975)
			maxx--;

		minx += min;
		maxx += min;

		System.out.printf("g=%f, mu=%f, o=%f, p=%f, min=%d, %f @ %d, max=%d\n", gain, mu, o, p, minx, maxp, maxc, maxx);
		return new int[] { minx, maxx };
	}

	static int[] getRange(final double gain, final double mu)
	{
		// Evaluate an initial range. 
		// Poisson will have mean mu with a variance mu. 
		// At large mu it is approximately normal so use 3 sqrt(mu) for the range added to the mean
		double range = Math.max(1, Math.sqrt(mu));
		int min = Math.max(0, (int) Math.floor(gain * (mu - 3 * range)));
		int max = (int) Math.ceil(gain * (mu + 3 * range));
		return new int[] { min, max };
	}

	@Test
	public void probabilityMatchesLogProbabilty()
	{
		for (int j = 0; j < gain.length; j++)
			for (int i = 0; i < photons.length; i++)
			{
				probabilityMatchesLogProbabilty(gain[j], photons[i]);
			}
	}

	private void probabilityMatchesLogProbabilty(final double gain, final double mu)
	{
		final double o = mu;

		PoissonFunction f = new PoissonFunction(1.0 / gain);
		double p = 0;

		int[] range = getRange(gain, mu);
		int min = range[0];
		int max = range[1];
		for (int x = min; x <= max; x++)
		{
			double v1 = Math.log(f.likelihood(x, o));
			double v2 = f.logLikelihood(x, o);

			Assert.assertEquals(String.format("g=%f, mu=%f, x=%d", gain, mu, x), v1, v2, Math.abs(v2) * 1e-8);
		}
	}

	@Test
	public void probabilityMatchesPoissonWithNoGain()
	{
		for (int i = 0; i < photons.length; i++)
		{
			probabilityMatchesPoissonWithNoGain(photons[i]);
		}
	}

	private void probabilityMatchesPoissonWithNoGain(final double mu)
	{
		final double o = mu;

		PoissonFunction f = new PoissonFunction(1.0);
		CustomPoissonDistribution pd = new CustomPoissonDistribution(null, mu);

		double p = 0;

		int[] range = getRange(1, mu);
		int min = range[0];
		int max = range[1];
		for (int x = min; x <= max; x++)
		{
			double v1 = f.likelihood(x, o);
			double v2 = pd.probability(x);

			Assert.assertEquals(String.format("mu=%f, x=%d", mu, x), v1, v2, Math.abs(v2) * 1e-8);
		}
	}
}
