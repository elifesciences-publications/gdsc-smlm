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
package uk.ac.sussex.gdsc.smlm.function;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.integration.SimpsonIntegrator;
import org.apache.commons.math3.analysis.integration.UnivariateIntegrator;
import org.apache.commons.math3.random.RandomGenerator;
import org.junit.Assert;
import org.junit.Test;

import uk.ac.sussex.gdsc.core.utils.DoubleEquality;
import uk.ac.sussex.gdsc.core.utils.StoredDataStatistics;
import uk.ac.sussex.gdsc.smlm.function.PoissonGammaGaussianFunction.ConvolutionMode;
import uk.ac.sussex.gdsc.test.TestLog;
import uk.ac.sussex.gdsc.test.TestSettings;
import uk.ac.sussex.gdsc.test.junit4.TestAssert;
import uk.ac.sussex.gdsc.test.junit4.TestAssume;

@SuppressWarnings({ "javadoc" })
public class PoissonGammaGaussianFunctionTest
{
	// Noise is in Counts and gain is total gain.
	// This makes more sense when testing as the
	// PoissonGammaGaussianFunction accepts 1/gain and noise as parameters.

	// TODO Fix these test conditions

	// Poisson-Gamma convolution sums to above 1 at lower gain.
	// due to the Dirac delta function, i.e. the Poisson-Gamma convolution
	// is a PDF and the Dirac delta is a PMF from the Poisson PMF at c=0.
	// This summing on the integer intervals (for a PMF) is invalid.
	// Store the expected sum at different gain below 10 for testing.
	static double[] pgSum = new double[11];
	static
	{
		// Compute the sum at expected photons around 1. This produces
		// the highest sum as the contribution from the Poisson-Gamma to c=0
		// will be the greatest.
		// These are rounded up to 3 d.p. provide a safer upper bound.
		final boolean compute = false;
		if (compute)
		{
			final MathContext mc = new MathContext(4, RoundingMode.UP);

			for (int g = 1; g <= 10; g++)
			{
				double max = 0;
				final int steps = 10;
				for (int i = 0; i <= steps; i++)
				{
					final double e = 0.5 * i / steps;
					// Compute half for the first interval
					double sum = PoissonGammaFunction.poissonGammaN(0, e, g) * 0.5 + PoissonGammaFunction.dirac(e);
					for (int c = 1;; c++)
					{
						final double p = PoissonGammaFunction.poissonGamma(c, e, g);
						sum += p;
						if (p / sum < 1e-6)
							break;
					}
					max = Math.max(max, sum);
				}
				pgSum[g] = max;
				final BigDecimal bd = new BigDecimal(max);
				System.out.printf("pgSum[%d] = %.3f;\n", g, bd.round(mc).doubleValue());
			}
		}

		//		pgSum[1] = 1.200;
		//		pgSum[2] = 1.096;
		//		pgSum[3] = 1.064;
		//		pgSum[4] = 1.047;
		//		pgSum[5] = 1.038;
		//		pgSum[6] = 1.032;
		//		pgSum[7] = 1.027;
		//		pgSum[8] = 1.024;
		//		pgSum[9] = 1.021;
		//		pgSum[10] = 1.019;

		pgSum[1] = 1.019;
		pgSum[2] = 1.005;
		pgSum[3] = 1.003;
		pgSum[4] = 1.002;
		pgSum[5] = 1.001;
		pgSum[6] = 1.001;
		pgSum[7] = 1.001;
		pgSum[8] = 1.001;
		pgSum[9] = 1.001;
		pgSum[10] = 1.001;
	}

	double[] photons = { 0, 0.25, 0.5, 1, 2, 4, 10, 100 };
	double[] highPhotons = { 5000 };
	double[] lowPhotons = { 1e-2, /* 1e-4, */ 1e-6 };
	double[] noise = { 3, 10 }; // in counts
	double[] lowNoise = { 0.3, 1 }; // in counts
	double[] totalGain = { 6.5, 45 };

	@Test
	public void cumulativeGaussianProbabilityIsCorrect()
	{
		for (final double s : noise)
			for (final double g : totalGain)
				cumulativeGaussianProbabilityIsCorrect(s, g);
	}

	private static void cumulativeGaussianProbabilityIsCorrect(double s, double g)
	{
		// Read noise should be in proportion to the camera gain
		final PoissonGammaGaussianFunction f = new PoissonGammaGaussianFunction(1 / g, s);
		final double range = 5 * s;
		final int upper = (int) Math.ceil(range);
		final int lower = (int) Math.floor(-range);
		final SimpsonIntegrator in = new SimpsonIntegrator(1e-4, 1e-8, 3, 32);
		final UnivariateFunction uf = new UnivariateFunction()
		{
			@Override
			public double value(double x)
			{
				return f.gaussianPDF(x);
			}
		};
		for (int u = lower; u <= upper; u++)
		{
			final double ux = u + 0.5;
			final double lx = u - 0.5;
			final double e = in.integrate(20000, uf, lx, ux);
			final double o = f.gaussianCDF(ux) - f.gaussianCDF(lx);
			final double o2 = f.gaussianCDF(lx, ux);
			Assert.assertEquals(e, o, e * 0.1);
			Assert.assertEquals(o, o2, o * 1e-6);
		}
	}

	// The Poisson-Gamma has a delta function at c=0. This causes problems
	// if not correctly integrated.
	// Some modes create a PMF, others a PDF so handle appropriately.

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePMFIntegrationAsPMF()
	{
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PMF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePMFIntegrationAsPDF()
	{
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PMF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePDFIntegrationAsPMF()
	{
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePDFIntegrationAsPDF()
	{
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithApproximationAtGainAbove10AsPDF()
	{
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
				{
					if (g < 10)
						continue;
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.APPROXIMATION, false);
				}
	}

	@Test
	public void cumulativeProbabilityIsOneWithApproximationAtGainAbove10AsPMF()
	{
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
				{
					if (g < 10)
						continue;
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.APPROXIMATION, true);
				}
	}

	@Test
	public void cumulativeProbabilityIsOneWithSimpsonIntegrationAsPDF()
	{
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.SIMPSON_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithSimpsonIntegrationAsPMF()
	{
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.SIMPSON_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithLegendreGaussIntegrationAsPDF()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.LEGENDRE_GAUSS_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithLegendreGaussIntegrationAsPMF()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.LEGENDRE_GAUSS_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePMFIntegrationAsPMFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PMF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePMFIntegrationAsPDFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PMF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePDFIntegrationAsPMFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePDFIntegrationAsPDFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithApproximationAtGainAbove10AsPDFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
				{
					if (g < 10)
						continue;
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.APPROXIMATION, false);
				}
	}

	// The approximation is not meant to be used as a PMF
	@Test(expected = AssertionError.class)
	public void cumulativeProbabilityIsNotOneWithApproximationAtGainAbove10AsPMFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
				{
					if (g < 10)
						continue;
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.APPROXIMATION, true);
				}
	}

	@Test
	public void cumulativeProbabilityIsOneWithSimpsonIntegrationAsPDFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.SIMPSON_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithSimpsonIntegrationAsPMFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.SIMPSON_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithLegendreGaussIntegrationAsPDFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.LEGENDRE_GAUSS_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithLegendreGaussIntegrationAsPMFAtLowNoise()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : photons)
			for (final double s : lowNoise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.LEGENDRE_GAUSS_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePMFIntegrationAsPMFAtHighPhotons()
	{
		TestAssume.assumeMediumComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PMF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePMFIntegrationAsPDFAtHighPhotons()
	{
		TestAssume.assumeMediumComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PMF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePDFIntegrationAsPMFAtHighPhotons()
	{
		TestAssume.assumeMediumComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePDFIntegrationAsPDFAtHighPhotons()
	{
		TestAssume.assumeMediumComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithApproximationAsPDFAtHighPhotons()
	{
		TestAssume.assumeMediumComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.APPROXIMATION, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithApproximationAsPMFAtHighPhotons()
	{
		TestAssume.assumeMediumComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
				{
					if (g < 10)
						continue;
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.APPROXIMATION, true);
				}
	}

	@Test
	public void cumulativeProbabilityIsOneWithSimpsonIntegrationAsPDFAtHighPhotons()
	{
		TestAssume.assumeMediumComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.SIMPSON_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithSimpsonIntegrationAsPMFAtHighPhotons()
	{
		TestAssume.assumeMediumComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.SIMPSON_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithLegendreGaussIntegrationAsPDFAtHighPhotons()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.LEGENDRE_GAUSS_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithLegendreGaussIntegrationAsPMFAtHighPhotons()
	{
		TestAssume.assumeHighComplexity();
		for (final double p : highPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.LEGENDRE_GAUSS_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePMFIntegrationAsPMFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PMF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePMFIntegrationAsPDFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PMF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePDFIntegrationAsPMFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithDiscretePDFIntegrationAsPDFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.DISCRETE_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithApproximationAtGainAbove10AsPDFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
				{
					if (g < 10)
						continue;
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.APPROXIMATION, false);
				}
	}

	@Test
	public void cumulativeProbabilityIsOneWithApproximationAtGainAbove10AsPMFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
				{
					if (g < 10)
						continue;
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.APPROXIMATION, true);
				}
	}

	@Test
	public void cumulativeProbabilityIsOneWithSimpsonIntegrationAsPDFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.SIMPSON_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithSimpsonIntegrationAsPMFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.SIMPSON_PDF, true);
	}

	@Test
	public void cumulativeProbabilityIsOneWithLegendreGaussIntegrationAsPDFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.LEGENDRE_GAUSS_PDF, false);
	}

	@Test
	public void cumulativeProbabilityIsOneWithLegendreGaussIntegrationAsPMFAtLowPhotons()
	{
		for (final double p : lowPhotons)
			for (final double s : noise)
				for (final double g : totalGain)
					cumulativeProbabilityIsOne(p, s, g, ConvolutionMode.LEGENDRE_GAUSS_PDF, true);
	}

	@Test
	public void discretePDFCloselyMatchesPMFIntegration()
	{
		final double[] e = closelyMatchesPMFIntegration(0.34, ConvolutionMode.DISCRETE_PDF);
		TestLog.debug("Discrete integration max error : rel = %g : abs = %g\n", e[0], e[1]);
	}

	@Test
	public void discretePMFCloselyMatchesPMFIntegration()
	{
		final double[] e = closelyMatchesPMFIntegration(0.22, ConvolutionMode.DISCRETE_PMF);
		TestLog.debug("Discrete integration max error : rel = %g : abs = %g\n", e[0], e[1]);
	}

	@Test
	public void approximationCloselyMatchesPMFIntegration()
	{
		final double[] e = closelyMatchesPMFIntegration(0.22, ConvolutionMode.APPROXIMATION);
		TestLog.debug("Approximation max error : rel = %g : abs = %g\n", e[0], e[1]);
	}

	@Test
	public void legedreGaussPDFMatchesPMFIntegration()
	{
		final double[] e = closelyMatchesPMFIntegration(0.03, ConvolutionMode.LEGENDRE_GAUSS_PDF);
		TestLog.debug("Simpson integration max error : rel = %g : abs = %g\n", e[0], e[1]);
	}

	// Speed order is roughly: Approx, Simpson, Discrete PDF, Legendre, Discrete PMF
	// The most accurate over most settings p<<1e-5, p=1, p>>10 is the Simpson.

	@Test
	public void approximationFasterThanSimpsonIntegration()
	{
		fasterThan(ConvolutionMode.SIMPSON_PDF, ConvolutionMode.APPROXIMATION);
	}

	@Test
	public void simpsonIntegrationFasterThanDiscretePDFIntegration()
	{
		fasterThan(ConvolutionMode.DISCRETE_PDF, ConvolutionMode.SIMPSON_PDF);
	}

	@Test
	public void simpsonIntegrationFasterThanLegendreGaussIntegration()
	{
		fasterThan(ConvolutionMode.LEGENDRE_GAUSS_PDF, ConvolutionMode.SIMPSON_PDF);
	}

	@Test
	public void discretePDFIntegrationFasterThanDiscretePMFIntegration()
	{
		fasterThan(ConvolutionMode.DISCRETE_PMF, ConvolutionMode.DISCRETE_PDF);
	}

	private static void cumulativeProbabilityIsOne(final double mu, final double s, final double g,
			ConvolutionMode convolutionMode, boolean pmfMode)
	{
		final double p = cumulativeProbability(mu, s, g, convolutionMode, pmfMode);
		TestLog.info("%s : mu=%f, s=%f, g=%f, p=%f\n", getName(convolutionMode), mu, s, g, p);

		// Poisson-Gamma convolution approximation does not sum to 1 at lower gain
		// so account for this during the test.
		final double delta = 0.02;
		double upper = 1 + delta;
		final double lower = 1 - delta;
		if (g < 10)
			upper = pgSum[(int) g] + delta;

		if (p < lower || p > upper)
			TestAssert.fail("mu=%f, s=%f, g=%f, p=%g", mu, s, g, p);
	}

	private static double cumulativeProbability(final double mu, final double s, final double g,
			ConvolutionMode convolutionMode, boolean pmfMode)
	{
		final PoissonGammaGaussianFunction f = new PoissonGammaGaussianFunction(1 / g, s);
		f.setConvolutionMode(convolutionMode);
		f.setPmfMode(pmfMode);
		f.setMinimumProbability(0);
		double p = 0;
		int min = 1;
		int max = 0;

		// Evaluate an initial range.
		// Gaussian should have >99% within +/- 3s
		// Poisson will have mean mu with a variance mu.
		// At large mu it is approximately normal so use 3 sqrt(mu) for the range added to the mean
		if (mu > 0)
		{
			final int[] range = PoissonGaussianFunctionTest.getRange(g, mu, s);
			min = range[0];
			max = range[1];
			for (int x = min; x <= max; x++)
			{
				final double pp = f.likelihood(x, mu);
				//TestLog.debug("x=%d, p=%g\n", x, pp);
				p += pp;
			}
			//if (p > 1.01)
			//	Assert.fail("P > 1: " + p);
		}

		// We have most of the probability density.
		// Now keep evaluating up and down until no difference
		final double changeTolerance = 1e-6;
		for (int x = min - 1;; x--)
		{
			min = x;
			final double pp = f.likelihood(x, mu);
			//TestLog.debug("x=%d, p=%g\n", x, pp);
			p += pp;
			if (pp / p < changeTolerance)
				break;
		}
		for (int x = max + 1;; x++)
		{
			max = x;
			final double pp = f.likelihood(x, mu);
			//TestLog.debug("x=%d, p=%g\n", x, pp);
			p += pp;
			if (pp / p < changeTolerance)
				break;
		}

		// This is a simple integral. Compute the full integral if necessary.
		if (!pmfMode && (p < 0.98 || p > 1.02))
		{
			// Do a formal integration
			final UnivariateIntegrator in = new SimpsonIntegrator(1e-6, 1e-6, 4,
					SimpsonIntegrator.SIMPSON_MAX_ITERATIONS_COUNT);
			final double pp = in.integrate(Integer.MAX_VALUE, new UnivariateFunction()
			{
				@Override
				public double value(double x)
				{
					return f.likelihood(x, mu);
				}
			}, min, max);
			TestLog.debug("%s : mu=%f, rn=%f, cg=%f, s=%f, g=%f, p=%g => %g\n", getName(convolutionMode), mu, s, g,
					s, g, p, pp);
			p = pp;
		}

		return p;
	}

	private double[] closelyMatchesPMFIntegration(double error, ConvolutionMode convolutionMode)
	{
		//DoubleEquality eq = new DoubleEquality(error, 1e-7);
		final double[] maxError = new double[2];
		for (final double s : noise)
			for (final double g : totalGain)
			{
				if (g < 10)
					continue;

				// This is the reference for a PMF-type result
				final PoissonGammaGaussianFunction f1 = new PoissonGammaGaussianFunction(1 / g, s);
				f1.setConvolutionMode(ConvolutionMode.SIMPSON_PDF);
				f1.setPmfMode(true);
				f1.setMinimumProbability(0);

				final PoissonGammaGaussianFunction f2 = new PoissonGammaGaussianFunction(1 / g, s);
				f2.setConvolutionMode(convolutionMode);
				f2.setPmfMode(true);
				f2.setMinimumProbability(0);

				for (final double p : photons)
				{
					final double pg = p * g;
					final double min = pg * 0.5 - 5 * s;
					final double max = 2 * pg;
					for (double x = min; x < max; x += 1)
					{
						final double p1 = f1.likelihood(x, p);
						final double p2 = f2.likelihood(x, p);

						final double relativeError = DoubleEquality.relativeError(p1, p2);
						final double absError = Math.abs(p1 - p2);
						final boolean equal = relativeError <= error; //eq.almostEqualRelativeOrAbsolute(p1, p2);
						if (!equal)
						{
							// Ignore small probabilities
							if (p1 < 1e-3)
								continue;

							TestAssert.fail("s=%g, g=%g, p=%g, x=%g: %g != %g (%g)", s, g, p, x, p1, p2, relativeError);
						}
						if (maxError[0] < relativeError)
							maxError[0] = relativeError;
						if (maxError[1] < absError)
							maxError[1] = absError;
					}
				}
			}
		return maxError;
	}

	private void fasterThan(ConvolutionMode slow, ConvolutionMode fast)
	{
		TestAssume.assumeSpeedTest();

		// Realistic EM-CCD parameters for speed test
		final double s = 7.16;
		final double g = 39.1;

		final PoissonGammaGaussianFunction f1 = new PoissonGammaGaussianFunction(1 / g, s);
		f1.setConvolutionMode(slow);

		final PoissonGammaGaussianFunction f2 = new PoissonGammaGaussianFunction(1 / g, s);
		f2.setConvolutionMode(fast);

		final RandomGenerator rg = TestSettings.getRandomGenerator();

		// Generate realistic data from the probability mass function
		final double[][] samples = new double[photons.length][];
		for (int j = 0; j < photons.length; j++)
		{
			final int start = (int) (4 * -s);
			int u = start;
			final StoredDataStatistics stats = new StoredDataStatistics();
			while (stats.getSum() < 0.995)
			{
				final double p = f1.likelihood(u, photons[j]);
				stats.add(p);
				if (u > 10 && p / stats.getSum() < 1e-6)
					break;
				u++;
			}

			// Generate cumulative probability
			final double[] data = stats.getValues();
			for (int i = 1; i < data.length; i++)
				data[i] += data[i - 1];

			// Sample
			final double[] sample = new double[1000];
			for (int i = 0; i < sample.length; i++)
			{
				final double p = rg.nextDouble();
				int x = 0;
				while (x < data.length && data[x] < p)
					x++;
				sample[i] = x;
			}
			samples[j] = sample;
		}

		// Warm-up
		run(f1, samples, photons);
		run(f2, samples, photons);

		long t1 = 0;
		for (int i = 0; i < 5; i++)
			t1 += run(f1, samples, photons);

		long t2 = 0;
		for (int i = 0; i < 5; i++)
			t2 += run(f2, samples, photons);

		TestLog.logSpeedTestResult(t1 > t2, "%s  %d -> %s  %d = %fx\n", getName(f1), t1, getName(f2), t2,
				(double) t1 / t2);
	}

	private static long run(PoissonGammaGaussianFunction f, double[][] samples, double[] photons)
	{
		final long start = System.nanoTime();
		for (int j = 0; j < photons.length; j++)
		{
			final double p = photons[j];
			for (final double x : samples[j])
				f.likelihood(x, p);
		}
		return System.nanoTime() - start;
	}

	private static String getName(PoissonGammaGaussianFunction f)
	{
		return getName(f.getConvolutionMode());
	}

	private static String getName(ConvolutionMode convolutionMode)
	{
		return convolutionMode.toString();
	}
}