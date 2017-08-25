package gdsc.smlm.fitting;

import org.apache.commons.math3.random.RandomDataGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import gdsc.smlm.TestSettings;

public class BinomialFitterTest
{
	int[] N = new int[] { 2, 3, 4, 6, 8 };
	double[] P = new double[] { 0.3, 0.5, 0.7 };
	int TRIALS = 10;
	int FAILURES = (int) (0.3 * TRIALS);
	RandomGenerator randomGenerator = new Well19937c(System.currentTimeMillis() + System.identityHashCode(this));
	RandomDataGenerator dataGenerator = new RandomDataGenerator(randomGenerator);

	@Test
	public void canFitBinomialWithKnownNUsingLeastSquaresEstimator()
	{
		boolean zeroTruncated = false;
		boolean maximumLikelihood = false;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomial(n, p, zeroTruncated, maximumLikelihood, n, n);
			}
		}
	}

	@Test
	public void canFitBinomialWithKnownNUsingMaximumLikelihood()
	{
		boolean zeroTruncated = false;
		boolean maximumLikelihood = true;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomial(n, p, zeroTruncated, maximumLikelihood, n, n);
			}
		}
	}

	@Test
	public void canFitBinomialWithUnknownNUsingLeastSquaresEstimator()
	{
		boolean zeroTruncated = false;
		boolean maximumLikelihood = false;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomial(n, p, zeroTruncated, maximumLikelihood, 1, n);
			}
		}
	}

	@Test
	public void canFitBinomialWithUnknownNUsingMaximumLikelihood()
	{
		boolean zeroTruncated = false;
		boolean maximumLikelihood = true;

		// TODO - Sort out how to fit unknown N using MLE. 
		// The problem is that the model returns a p of zero when n>N and this results in a negative infinity likelihood

		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomial(n, p, zeroTruncated, maximumLikelihood, 1, n);
			}
		}
	}

	@Test
	public void canFitZeroTruncatedBinomialWithKnownNUsingLeastSquaresEstimator()
	{
		boolean zeroTruncated = true;
		boolean maximumLikelihood = false;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomial(n, p, zeroTruncated, maximumLikelihood, n, n);
			}
		}
	}

	@Test
	public void canFitZeroTruncatedBinomialWithKnownNUsingMaximumLikelihood()
	{
		boolean zeroTruncated = true;
		boolean maximumLikelihood = true;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomial(n, p, zeroTruncated, maximumLikelihood, n, n);
			}
		}
	}

	@Test
	public void canFitZeroTruncatedBinomialWithUnknownNUsingLeastSquaresEstimator()
	{
		boolean zeroTruncated = true;
		boolean maximumLikelihood = false;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomial(n, p, zeroTruncated, maximumLikelihood, 1, n);
			}
		}
	}

	@Test
	public void canFitZeroTruncatedBinomialWithUnknownNUsingMaximumLikelihood()
	{
		boolean zeroTruncated = true;
		boolean maximumLikelihood = false;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomial(n, p, zeroTruncated, maximumLikelihood, 1, n);
			}
		}
	}

	@Test
	public void sameFitBinomialWithKnownNUsing_LSE_Or_MLE()
	{
		boolean zeroTruncated = false;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomialUsing_LSE_Or_MLE(n, p, zeroTruncated, n, n);
			}
		}
	}

	@Test
	public void sameFitZeroTruncatedBinomialWithKnownNUsing_LSE_Or_MLE()
	{
		boolean zeroTruncated = true;
		for (int n : N)
		{
			for (double p : P)
			{
				fitBinomialUsing_LSE_Or_MLE(n, p, zeroTruncated, n, n);
			}
		}
	}

	private void fitBinomial(int n, double p, boolean zeroTruncated, boolean maximumLikelihood, int minN, int maxN)
	{
		Assume.assumeTrue("Skipped", TestSettings.RUN_FITTING_TESTS);
		
		BinomialFitter bf = new BinomialFitter(null);
		//BinomialFitter bf = new BinomialFitter(new ConsoleLogger());
		bf.setMaximumLikelihood(maximumLikelihood);

		log("Fitting (n=%d, p=%f)\n", n, p);
		int fail = 0;
		for (int i = 0; i < TRIALS; i++)
		{
			int[] data = createData(n, p, false);
			double[] fit = bf.fitBinomial(data, minN, maxN, zeroTruncated);
			int fittedN = (int) fit[0];
			double fittedP = fit[1];
			log("  Fitted (n=%d, p=%f)\n", fittedN, fittedP);
			try
			{
				Assert.assertEquals("Failed to fit n", n, fittedN);
				Assert.assertEquals("Failed to fit p", p, fittedP, 0.05);
			}
			catch (AssertionError e)
			{
				fail++;
				log("    " + e.getMessage() + "\n");
			}
		}
		if (fail > FAILURES)
		{
			String msg = String.format("Too many failures (n=%d, p=%f): %d", n, p, fail);
			Assert.assertTrue(msg, fail <= FAILURES);
		}
	}

	private void fitBinomialUsing_LSE_Or_MLE(int n, double p, boolean zeroTruncated, int minN, int maxN)
	{
		BinomialFitter bf = new BinomialFitter(null);
		//BinomialFitter bf = new BinomialFitter(new ConsoleLogger());

		log("Fitting (n=%d, p=%f)\n", n, p);
		int fail = 0;
		int c1 = 0;
		for (int i = 0; i < TRIALS; i++)
		{
			int[] data = createData(n, p, false);
			bf.setMaximumLikelihood(false);
			double[] fitLSE = bf.fitBinomial(data, minN, maxN, zeroTruncated);
			bf.setMaximumLikelihood(true);
			double[] fitMLE = bf.fitBinomial(data, minN, maxN, zeroTruncated);

			int n1 = (int) fitLSE[0];
			double p1 = fitLSE[1];
			int n2 = (int) fitMLE[0];
			double p2 = fitMLE[1];

			log("  Fitted LSE (n=%d, p=%f) == MLE (n=%d, p=%f)\n", n1, p1, n2, p2);

			try
			{
				Assert.assertEquals("Failed to match n", n1, n2);
				Assert.assertEquals("Failed to match p", p1, p2, 0.05);
			}
			catch (AssertionError e)
			{
				fail++;
				log("    " + e.getMessage() + "\n");
			}
			if (Math.abs(p1 - p) < Math.abs(p2 - p))
				c1++;
		}
		log("  Closest LSE %d, MLE %d\n", c1, TRIALS - c1);
		if (fail > FAILURES)
		{
			String msg = String.format("Too many failures (n=%d, p=%f): %d", n, p, fail);
			Assert.assertTrue(msg, fail <= FAILURES);
		}
	}

	private int[] createData(int n, double p, boolean zeroTruncated)
	{
		int[] data = new int[2000];
		if (zeroTruncated)
		{
			if (p <= 0)
				throw new RuntimeException("p must be positive");
			for (int i = 0; i < data.length; i++)
			{
				int count;
				do
				{
					count = dataGenerator.nextBinomial(n, p);
				} while (count == 0);
				data[i] = count;
			}
		}
		else
		{
			for (int i = 0; i < data.length; i++)
			{
				data[i] = dataGenerator.nextBinomial(n, p);
			}
		}
		return data;
	}

	void log(String format, Object... args)
	{
		System.out.printf(format, args);
	}
}
