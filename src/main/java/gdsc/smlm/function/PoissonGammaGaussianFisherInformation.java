package gdsc.smlm.function;

import gdsc.core.math.NumberUtils;
import gdsc.core.utils.Maths;
import gdsc.smlm.utils.Convolution;
import gnu.trove.list.array.TDoubleArrayList;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2018 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

/**
 * Calculate the Fisher information for a Poisson-Gamma-Gaussian distribution.
 * <p>
 * Uses a modified form of the equation of Chao, et al (2013) Nature Methods, 10, 335-338, SI Eq S8.
 * <p>
 * Performs a convolution with a finite Gaussian kernel.
 */
public abstract class PoissonGammaGaussianFisherInformation extends BasePoissonFisherInformation
{
	/** The default minimum range for the Gaussian kernel (in units of SD). */
	public static final int DEFAULT_MIN_RANGE = 6;

	/** The default maximum range for the Gaussian kernel (in units of SD). */
	public static final int DEFAULT_MAX_RANGE = 38;

	/** The maximum range for the Gaussian kernel (in units of SD). */
	public static final int MAX_RANGE = 38;

	/** The default threshold for the relative probability. */
	public static final double DEFAULT_RELATIVE_PROBABILITY_THRESHOLD = 1e-5;

	/** The default threshold for the cumulative probability. */
	public static final double DEFAULT_CUMULATIVE_PROBABILITY = 1 - 1e-6;

	/**
	 * The default sampling of the Gaussian kernel. The kernel will be sampled at s/sampling,
	 * i.e. this is the number of samples to take per standard deviation unit.
	 */
	public static final int DEFAULT_SAMPLING = 4;

	/**
	 * The lowest value for the mean that can be computed. This is the lowest value where the reciprocal is not infinity
	 */
	public static final double MIN_MEAN = Double.longBitsToDouble(0x4000000000001L);

	/** The gain multiplication factor. */
	public final double m;

	/** The standard deviation of the Gaussian. */
	public final double s;

	/** The minimum range of the Gaussian kernel (in SD units). */
	private int minRange = DEFAULT_MIN_RANGE;

	/** The maximum range of the Gaussian kernel (in SD units). */
	private int maxRange = DEFAULT_MAX_RANGE;

	/** The scale of the kernel. */
	private final int scale;

	/** Working space to store the probabilities. */
	private TDoubleArrayList list1 = new TDoubleArrayList();
	/** Working space to store the gradient. */
	private TDoubleArrayList list2 = new TDoubleArrayList();

	/**
	 * The relative probability threshold of the Poisson-Gamma distribution that is used. Any probability less than this
	 * is ignored. This prevents using values that do not contribute to the sum.
	 */
	private double relativeProbabilityThreshold = DEFAULT_RELATIVE_PROBABILITY_THRESHOLD;

	/** The upper mean threshold for the switch to half the Poisson Fisher information. */
	private double upperMeanThreshold = 200;

	/** The cumulative probability of the partial gradient of the Poisson-Gamma distribution that is used. */
	private double cumulativeProbability = DEFAULT_CUMULATIVE_PROBABILITY;

	/** Set to true to use Simpson's 3/8 rule for cubic interpolation of the integral. */
	private boolean use38 = true;

	/** The step size (h) between values of the last integrated function. */
	private double h;

	/** The probability component of the last integrated function. */
	private double[] P;

	/** The gradient component of the last integrated function. */
	private double[] A;

	/** The start offset of the last integrated function. */
	private double offset;

	/** The Poisson mean of the last integrated function. */
	private double lastT;

	/**
	 * Instantiates a new poisson gamma gaussian fisher information.
	 *
	 * @param m
	 *            the gain multiplication factor
	 * @param s
	 *            the standard deviation of the Gaussian
	 * @throws IllegalArgumentException
	 *             If the standard deviation is not strictly positive
	 */
	public PoissonGammaGaussianFisherInformation(double m, double s) throws IllegalArgumentException
	{
		this(m, s, DEFAULT_SAMPLING);
	}

	/**
	 * Instantiates a new poisson gamma gaussian fisher information.
	 *
	 * @param m
	 *            the gain multiplication factor
	 * @param s
	 *            the standard deviation of the Gaussian
	 * @param sampling
	 *            The number of Gaussian samples to take per standard deviation
	 * @throws IllegalArgumentException
	 *             If the gain or standard deviation are not strictly positive
	 * @throws IllegalArgumentException
	 *             If the sampling is below 1
	 * @throws IllegalArgumentException
	 *             If the maximum kernel size after scaling is too large
	 */
	public PoissonGammaGaussianFisherInformation(double m, double s, double sampling) throws IllegalArgumentException
	{
		if (!(m > 0 && m <= Double.MAX_VALUE))
			throw new IllegalArgumentException("Gain multiplication factor must be strictly positive");
		if (!(s > 0 && s <= Double.MAX_VALUE))
			throw new IllegalArgumentException("Gaussian variance must be strictly positive");
		if (!(sampling >= 1 && sampling <= Double.MAX_VALUE))
			throw new IllegalArgumentException("Gaussian sampling must at least 1");
		if (s * MAX_RANGE < 0.5)
			throw new IllegalArgumentException("Gaussian range does not extend to convolve with the Poisson");

		double scale = Math.ceil(sampling / s);
		if (scale * s * MAX_RANGE > 1000000)
		{
			// Don't support excess scaling caused by small kernels
			throw new IllegalArgumentException("Maximum Gaussian kernel size too large: " + scale * s * MAX_RANGE);
		}

		this.m = m;
		this.s = s;
		this.scale = (int) scale;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The input parameter refers to the mean of the Poisson distribution.
	 * <p>
	 * The Fisher information is computed using the equation of Chao, et al (2013) Nature Methods, 10, 335-338, SI Eq
	 * S8. Note that that equation computes the noise coefficient relative to a Poisson, this computes the Fisher
	 * information. To get the noise coefficient multiply by the input parameter.
	 * 
	 * @see gdsc.smlm.function.FisherInformation#getFisherInformation(double)
	 */
	public double getFisherInformation(double t) throws IllegalArgumentException
	{
		final double I = getPoissonGammaGaussianI(t);

		// Check limits.

		// It should be worse than the Poisson Fisher information (upper limit).
		// Note a low Fisher information is worse as this is the amount of information
		// carried about the parameter.
		final double upper = 1.0 / t; // PoissonFisherInformation.getPoissonI(t);
		// When the mean is high then the lower bound should be half the 
		// Poisson Fisher information. Otherwise check against 0.
		final double lower = (t > 1) ? 0.5 * upper : 0;
		return Maths.clip(lower, upper, I);
	}

	@Override
	public double getAlpha(double t)
	{
		// Don't check against MIN_MEAN as this is done later if it is necessary
		// to compute the Fisher information.

		if (t > upperMeanThreshold)
		{
			// Use an approximation as half the Poisson Fisher information
			return 0.5;
		}

		return t * getFisherInformation(t);
	}

	/**
	 * Gets the Poisson-Gamma-Gaussian Fisher information.
	 * <p>
	 * The input parameter refers to the mean of the Poisson distribution.
	 * <p>
	 * The Fisher information is computed using the equation of Chao, et al (2013) Nature Methods, 10, 335-338, SI Eq
	 * S8. Note that that equation computes the noise coefficient relative to a Poisson, this computes the Fisher
	 * information. To get the noise coefficient multiply by the input parameter.
	 * <p>
	 * Note: This uses a convolution of an infinite integral over a finite range. It may under-estimate the information
	 * when the mean is large.
	 *
	 * @param t
	 *            the Poisson mean
	 * @return the Poisson Gaussian Fisher information
	 * @throws IllegalArgumentException
	 *             the illegal argument exception
	 */
	public double getPoissonGammaGaussianI(double t) throws IllegalArgumentException
	{
		if (t < MIN_MEAN)
		{
			throw new IllegalArgumentException("Poisson mean must be positive");
		}

		lastT = t;

		if (t > upperMeanThreshold)
		{
			// Use an approximation as half the Poisson Fisher information when the mean is large
			return 1.0 / (2 * t);
		}

		final double dirac = PoissonGammaFunction.dirac(t);

		// Special case where the start of the range will have no probability.
		// This occurs when mean > limit of exp(-p) => 746.
		if (dirac == 0)
		{
			// Use half the Poisson fisher information
			return 1.0 / (2 * t);
		}

		// This computes the convolution of a Poisson-Gamma PDF and a Gaussian PDF.
		// The value of this is p(z).

		// The Poisson-Gamma-Gaussian must be differentiated to compute the Fisher information:
		// Expected [ (d ln(p(z)) dv)^2 ]
		// = integral [ (1/p(z) .  d p(z) dv)^2 p(z) dz ]
		// = integral [  1/p(z) . (d p(z) dv)^2 dz ]

		// Gaussian standard deviation = s

		// Note: (fg)' => fg' + f'g
		// e^-v => -e^-v
		// (e^-v * g(v))' => e^-v * g'(v) - e^-v * g(v)
		// So for a probability distribution with a e^-v component:
		// p(z|v)  = e^-v * g(v)
		// p'(z|v) = e^-v * g'(v) - p(z|v)

		// -=-=-=-

		// This Fisher information is based on Chao, et al (2013) Nature Methods, 10, 335-338, SI.

		// Chao et al, Eq 4:
		// (Poisson Binomial Gaussian convolution)

		// Gradient of the Poisson Binomial is:

		// This component can be seen in Chao et al, Eq S8:

		// Substitute the Poisson Binomial Gaussian with Poisson Gamma Gaussian:

		// Gradient of the Poisson Gamma is:

		// Fisher information is:

		// -=-=-=-

		// We need the convolution of:
		// - the Poisson-Gamma with the Gaussian (P)
		// - the Poisson-Gamma partial gradient component with the Gaussian (A)
		// This is combined to a function A^2/P which is integrated.
		// Note that A & P should sum to 1 over the full range used for integration.
		// In practice a relative height threshold of the function can be used
		// to determine the range where it is significant.

		// Note that when the Poisson mean is low then the contribution from the 
		// Dirac delta function at 0 will be large. As the mean increases 
		// the Dirac will have little significance, the function is very smooth
		// and may have a large range. Thus convolution with an extended Gaussian kernel 
		// may not be necessary over the full range.
		// So the integral can be computed as [integral range1] + [integral range2]

		// Range 1:
		// The range around the Dirac delta function is computed using a full convolution
		// with the Gaussian.

		// The exponent provides a rough idea of the size of the mean
		int exp = NumberUtils.getSignedExponent(t);
		// As the mean reduces the Poisson distribution is more skewed 
		// and the extent of the kernel must change. Just increase the range
		// for the kernel for each power of 2 the number is below 1.
		int range1 = minRange;
		for (int e = exp; range1 < maxRange && e <= 0; e++, range1++)
			;

		// Range 2: 
		// The remaining range of the Poisson-Gamma sampled in step intervals up to 
		// the configured relative probability.

		// At high Poisson mean and amplification the mean is the Poisson mean * amplification.
		// This is an upper bound as it may be lower.
		double mean = t * m;

		// The function we are integrating is:  
		// E = integral [ (1/p(z) . d p(z) dv)^2 p(z) dz ]
		// Integration is done using all values of this function within a reasonable
		// relative size to the maximum.

		// Configure so that there is a maximum number of steps up to the mean (and similar after).
		// This limits the convolution size.
		// TODO - make this number of steps configurable? This value works for reasonable
		// input parameters. Too small a step size results in poor integration when t is low
		// (and the gain is low) since the mean is over-estimated.
		
		
		// TODO - fix the dual range convolution.
		
//		double step = mean / 128;
//		// The step must coincide with the end of range 1, 
//		// which is an integer factor of the standard deviation.
		int scale2 = 1;
		
//		if (step < s)
//		{
//			// This will occur when the Gaussian is wide compared to the width of the Poisson-Gamma.
//			// Upsample the Gaussian.
//			scale2 = getPow2Scale(s / step);
//		}
//		else
//		{
//			// This is when the Gaussian is narrow compared to the width of the Poisson-Gamma.
//			// Just sample every unit of standard deviation.
//			// TODO: There will be a point when convolution with a narrow Gaussian is expensive.
//			// For now assume that the width will be reasonable (e.g. >1) and a second
//			// convolution is always done.
//			scale2 = 1;
//		}
//
//		//System.out.printf("t=%g m=%g scale=%d scale2=%d\n", t, m, scale, scale2);
//
//		// Ensure the first scale is greater then the second
//		if (scale < scale2)
//			scale = scale2;

		//scale = scale2 = 128;
		//use38 = false;

		h = s / scale;
		double G;
		double[] dG_dp = new double[1];
		list1.resetQuick();
		list2.resetQuick();

		// Since convolution with the Gaussian should only be done for c>=0 use
		// only half the first value
		double c0factor = 0.5;

		// Compute the max fisher information for the unconvolved function.
		double max = 0;

		G = PoissonGammaFunction.poissonGammaPartial(0, t, m, dG_dp);
		final double p0 = (G - dirac) * c0factor;
		list1.add(p0);
		list2.add(dG_dp[0] * c0factor);

		// Simpson's sum of target function. This should integrate to 1.
		double sum = dG_dp[0];

		// The limit is set so that we have the full Poisson-Gamma function computed
		// when the Gaussian kernel no longer touches zero.
		int limit = (range1 + 1) * scale * 2;
		for (int i = 1; i <= limit; i++)
		{
			G = PoissonGammaFunction.poissonGammaPartial(h * i, t, m, dG_dp);
			double f = getF(G, dG_dp[0]);
			if (max < f)
				max = f;
			list1.add(G);
			list2.add(dG_dp[0]);
			sum += (i % 2 == 1) ? 4 * dG_dp[0] : 2 * dG_dp[0];
		}

		double endRange1 = limit * h;

		// If the range is past the range expected then do a single range.
		// TODO - make the threshold configurable
		boolean singleRange = true; //scale == scale2 || endRange1 > 5 * mean;
		if (singleRange)
		{
			// Continue until all the Fisher information has been achieved.
			// Compute at least 2 * mean before checking the relative size.
			final int checkI = (int) Math.ceil((2 * mean - endRange1) / h);
			final double target = cumulativeProbability / (h / 3);
			for (int i = limit + 1;; i++)
			{
				G = PoissonGammaFunction.poissonGammaPartial(h * i, t, m, dG_dp);
				if (G == 0)
					break;
				double f = getF(G, dG_dp[0]);
				if (max < f)
					max = f;
				list1.add(G);
				list2.add(dG_dp[0]);
				sum += (i % 2 == 1) ? 4 * dG_dp[0] : 2 * dG_dp[0];
				if (i > checkI && f / max < relativeProbabilityThreshold && sum > target)
					break;
			}
		}

		// Convolve with the Gaussian kernel
		double[] p = list1.toArray();
		double[] a = list2.toArray();

		//System.out.printf("t=%g  sum p=%s  single=%b  h=%g  sumA=%s\n", t, (Maths.sum(p)) * h + dirac, singleRange, h, sum * h / 3);

		// Should this convolve without the Dirac then add that afterwards.
		// This is how the Camera Model Analysis works. Perhaps there is
		// floating point error when the dirac is large.

		double[] g = getUnitGaussianKernel(scale, range1);
		p[0] += dirac;
		convolve(p, a, g, true);

		int maxi = (singleRange)
				// Do the entire range
				? P.length
				// For a dual range compute sum only up to where 
				// the Gaussian kernel no longer touches zero.
				// If the Gaussian is at p.length / 2 then it does not touch 0.
				// Add the kernel range too to compute the 
				// sum from -[Gaussian range] to +[Gaussian range] around zero.		
				: p.length / 2 + g.length / 2; // Separate additions as both are odd

		final double endRange1F;

		// We assume that the function values at the end are zero and so do not 
		// include them in the sum. Just alternate totals.
		double range1sum;
		if (use38)
		{
			// Simpson's 3/8 rule based on cubic interpolation has a lower error.
			// This computes the sum as:
			// 3h/8 * [ f(x0) + 3f(x1) + 3f(x2) + 2f(x3) + 3f(x4) + 3f(x5) + 2f(x6) + ... + f(xn) ]
			// The final step before maxi must sum 2. 
			final int end = (maxi - 1) % 3;
			double sum3 = 0, sum2 = 0;
			for (int i = 0; i < maxi; i++)
			{
				final double f = getF(P[i], A[i]);
				if (i % 3 == end)
					sum2 += f;
				else
					sum3 += f;
			}

			endRange1F = (singleRange) ? 0 : getF(P[maxi], A[maxi]);

			// Assume all values before the function are zero.
			range1sum = (h * 3.0 / 8) * (sum3 * 3 + sum2 * 2 + endRange1F);
		}
		else
		{
			// Simpson's rule.
			// This computes the sum as:
			// h/3 * [ f(x0) + 4f(x1) + 2f(x2) + 4f(x3) + 2f(x4) ... + 4f(xn-1) + f(xn) ]
			// The number of steps must be modulo 2. Assume all values before this are zero.
			// The final step before maxi must sum 2. 
			final int end = (maxi - 1) % 2;
			double sum4 = 0, sum2 = 0;
			for (int i = 0; i < maxi; i++)
			{
				final double f = getF(P[i], A[i]);
				if (i % 2 == end)
					sum4 += f;
				else
					sum2 += f;
			}

			endRange1F = (singleRange) ? 0 : getF(P[maxi], A[maxi]);

			range1sum = (h * 1.0 / 3) * (sum4 * 4 + sum2 * 2 + endRange1F);
		}

		//System.out.printf("sum A = %g\n", Maths.sum(A) * h);

		if (singleRange)
		{
			return range1sum - 1;
		}

		// XXX - For testing that the two convolutions join seemlessly
		//scale2 = scale;

		// The samples are taken at a factor of the standard deviation
		h = s / scale2;

		// The second scale is less than the first so reuse the values
		int interval = scale / scale2;
		list1.resetQuick();
		list2.resetQuick();
		list1.add(p0);
		list2.add(a[0]);
		// Simpson's sum of target function. This should integrate to 1.
		sum = a[0] / c0factor;
		for (int i = interval, j = 1; i < p.length; i += interval, j++)
		{
			list1.add(p[i]);
			list2.add(a[i]);
			sum += (j % 2 == 1) ? 4 * a[i] : 2 * a[i];
		}

		// For a dual range compute sum only from the range 1 limit
		// (this was p.length / 2 since the length was double for full convolution
		// with the Gaussian).
		//int mini = (p.length / 2) / interval;		
		int mini = list1.size() / 2;

		// Continue from the end of range 1 until all the Fisher information has been achieved.
		// Compute at least 2 * mean before checking the relative size.
		int checkI = (int) Math.ceil((2 * mean - endRange1) / h);
		final double target = cumulativeProbability / (h / 3);
		for (int i = 1;; i++)
		{
			G = PoissonGammaFunction.poissonGammaPartial(endRange1 + h * i, t, m, dG_dp);
			if (G == 0)
				break;
			double f = getF(G, dG_dp[0]);
			if (max < f)
				max = f;
			list1.add(G);
			list2.add(dG_dp[0]);
			sum += (i % 2 == 1) ? 4 * dG_dp[0] : 2 * dG_dp[0];
			if (i > checkI && f / max < relativeProbabilityThreshold && sum > target)
				break;
		}

		p = list1.toArray();
		a = list2.toArray();

		//System.out.printf("t=%g  sum p2=%g  endRange1=%g sumA=%s\n", t,
		//		(Maths.sum(p) + (1 - c0factor) * p0) * h + dirac, endRange1, sum * h / 3);

		g = getUnitGaussianKernel(scale2, minRange);
		p[0] += dirac;
		convolve(p, a, g, false);

		// Add the kernel size to get the point when new values occur.
		mini += g.length / 2;

		// Check that the endRange1F is the same as the start point.
		// When debugging set scale2 == scale. However this may still
		// be different if convolution used the FFT due to the different
		// size data. When scale2 != scale then it will be different
		// due to less accurate sampling of the convolution.
		//double startRange2F = getF(P[mini], A[mini]);
		//System.out.printf("%s == %s (%g)\n", endRange1F, startRange2F,
		//		gdsc.core.utils.DoubleEquality.relativeError(endRange1F, startRange2F));

		// We assume that the function values at the end are zero and so do not 
		// include them in the sum. Just alternate totals.
		double range2sum;
		if (use38)
		{
			// Simpson's 3/8 rule based on cubic interpolation has a lower error.
			// This computes the sum as:
			// 3h/8 * [ f(x0) + 3f(x1) + 3f(x2) + 2f(x3) + 3f(x4) + 3f(x5) + 2f(x6) + ... + f(xn) ]
			// The final step before maxi must sum 2. 
			double sum3 = 0, sum2 = 0;
			for (int i = mini + 1; i < P.length; i++)
			{
				final double f = getF(P[i], A[i]);
				if (i % 3 == 2)
					sum2 += f;
				else
					sum3 += f;
			}

			// Assume all values before the function are zero.
			range2sum = (h * 3.0 / 8) * (sum3 * 3 + sum2 * 2 + endRange1F);
		}
		else
		{
			// Simpson's rule.
			// This computes the sum as:
			// h/3 * [ f(x0) + 4f(x1) + 2f(x2) + 4f(x3) + 2f(x4) ... + 4f(xn-1) + f(xn) ]
			// The number of steps must be modulo 2. Assume all values before this are zero.
			// The final step before maxi must sum 2. 
			double sum4 = 0, sum2 = 0;
			for (int i = mini + 1; i < P.length; i++)
			{
				final double f = getF(P[i], A[i]);
				if (i % 2 == 0)
					sum4 += f;
				else
					sum2 += f;
			}

			range2sum = (h * 1.0 / 3) * (sum4 * 4 + sum2 * 2 + endRange1F);
		}

		//System.out.printf("s=%g t=%g x=%d-%d scale=%d   sum=%s  pI = %g   pgI = %g\n", s, t, minx, maxx, scale, sum,
		//		getPoissonI(t), 1 / (t + s * s));

		//System.out.printf("sum A = %g\n", Maths.sum(A) * h);

		return range1sum + range2sum - 1;
	}

	private void convolve(double[] p, double[] a, double[] g, boolean requireEdge)
	{
		// Convolution in the frequency domain may create negatives
		// due to edge wrap artifacts. This is classically reduced by using a 
		// edge window function. However this is undesirable as it 
		// will truncate the dirac delta function and high contribution
		// at c=0 when the Poisson mean is low.

		// When the entire range of the convolution is required use the spatial domain.
		// When only part of the range is required (since the range start has been 
		// computed, and the range end will asymptote to zero) use the FFT

		double[][] result = (requireEdge) ? Convolution.convolve(g, p, a) : Convolution.convolveFast(g, p, a);
		P = result[0];
		A = result[1];
		offset = g.length / 2 * -h;

		if (!requireEdge && Convolution.isFFT(g.length, p.length))
		{
			// Just remove values below the minimum expected.
			// If the Gaussian kernel is sufficiently large then 
			// edge artifacts should be limited to the region where
			// the actual values are very small.
			double minP = Maths.min(p) * g[0];
			double minA = Maths.min(a) * g[0];
			for (int i = 0; i < P.length; i++)
			{
				if (P[i] < minP)
					P[i] = 0;
				if (A[i] < minA)
					A[i] = 0;
			}
		}
	}

	/**
	 * Gets a value using the next integer power of 2. This is limited to a size of 128.
	 *
	 * @param s
	 *            the value
	 * @return the next power of 2
	 */
	protected static int getPow2Scale(double s)
	{
		double scale = Math.ceil(s);
		if (scale > 128)
			return 128;
		return Maths.nextPow2((int) scale);
	}

	/**
	 * Gets the gaussian kernel for convolution using a standard deviation of 1.
	 * The kernel will be sampled every (1 / scale).
	 * <p>
	 * This will only be called with a range of 1 to 38.
	 *
	 * @param sampling
	 *            the scale
	 * @param range
	 *            the range (in SD units)
	 * @return the gaussian kernel
	 */
	protected abstract double[] getUnitGaussianKernel(int scale, int range);

	private static double getF(double P, double A)
	{
		//		if (NumberUtils.isSubNormal(P))
		//			System.out.printf("Sub-normal: P=%s A=%s\n", P, A);
		if (P == 0)
			return 0;
		final double result = Maths.pow2(A) / P;
		//if (result > 1)
		//	System.out.printf("Strange: P=%s A=%s result=%s\n", P, A, result);
		return result;
	}

	/**
	 * Gets the fisher information function. This is the function that is integrated to get the Fisher information. This
	 * can be called after a called to {@link #getFisherInformation(double)}. It will return the last value of the
	 * function that was computed (before/after convolution with the Gaussian kernel).
	 * <p>
	 * The function should be smooth with zero at the upper end. The lower end may not be smooth due to the Dirac delta
	 * function at c=0.
	 *
	 * @param withGaussian
	 *            Set to true to return the function after convolution with the gaussian
	 * @return the fisher information function
	 */
	public double[][] getFisherInformationFunction(boolean withGaussian)
	{
		if (A == null)
			return null;

		double[] A, P;
		double offset;
		if (withGaussian)
		{
			P = this.P;
			A = this.A;
			offset = this.offset;
		}
		else
		{
			P = list1.toArray();
			A = list2.toArray();
			offset = 0;
			// Account for manipulation at c=0 for the convolution. Just recompute.
			double[] dG_dp = new double[1];
			P[0] = PoissonGammaFunction.poissonGammaPartial(0, lastT, m, dG_dp);
			A[0] = dG_dp[0];
		}
		double[] f = new double[A.length];
		double[] x = new double[A.length];
		for (int i = 0; i < A.length; i++)
		{
			x[i] = i * h + offset;
			f[i] = getF(P[i], A[i]);
		}
		return new double[][] { x, f };
	}

	/**
	 * Gets the min range of the Gaussian kernel in SD units.
	 * The Gaussian kernel is scaled dynamically depending on the shape of the Poisson distribution.
	 *
	 * @return the min range
	 */
	public int getMinRange()
	{
		return minRange;
	}

	/**
	 * Sets the min range of the Gaussian kernel in SD units.
	 * The Gaussian kernel is scaled dynamically depending on the shape of the Poisson distribution.
	 *
	 * @param minRange
	 *            the new min range
	 */
	public void setMinRange(int minRange)
	{
		this.minRange = checkRange(minRange);
	}

	private int checkRange(int range)
	{
		// Gaussian = Math.exp(-0.5 * x^2)
		// FastMath.exp(-746) == 0
		// => range for the Gaussian is sqrt(2*746) = 38.6
		return Maths.clip(1, MAX_RANGE, range);
	}

	/**
	 * Gets the max range of the Gaussian kernel in SD units.
	 * The Gaussian kernel is scaled dynamically depending on the shape of the Poisson distribution.
	 *
	 * @return the max range
	 */
	public int getMaxRange()
	{
		return maxRange;
	}

	/**
	 * Sets the max range of the Gaussian kernel in SD units.
	 * The Gaussian kernel is scaled dynamically depending on the shape of the Poisson distribution.
	 *
	 * @param maxRange
	 *            the new max range
	 */
	public void setMaxRange(int maxRange)
	{
		this.maxRange = checkRange(maxRange);
	}

	/**
	 * Gets the mean threshold for the switch to half the Poisson Fisher information.
	 * <p>
	 * When the mean is high then there is no Dirac delta contribution to the probability function. This occurs when
	 * exp(-mean) is zero, which is approximately 746. In practice there is not much difference when the mean is above
	 * 100.
	 *
	 * @return the mean threshold
	 */
	public double getMeanThreshold()
	{
		return upperMeanThreshold;
	}

	/**
	 * Sets the mean threshold for the switch to half the Poisson Fisher information.
	 * <p>
	 * When the mean is high then there is no Dirac delta contribution to the probability function. This occurs when
	 * exp(-mean) is zero, which is approximately 746. In practice there is not much difference when the mean is above
	 * 100.
	 *
	 * @param meanThreshold
	 *            the new mean threshold
	 */
	public void setMeanThreshold(double meanThreshold)
	{
		this.upperMeanThreshold = meanThreshold;
	}

	/**
	 * Gets the cumulative probability of the partial gradient of the Poisson-Gamma distribution that is used.
	 *
	 * @return the cumulative probability
	 */
	public double getCumulativeProbability()
	{
		return cumulativeProbability;
	}

	/**
	 * Sets the cumulative probability of the partial gradient of the Poisson-Gamma distribution that is used.
	 *
	 * @param cumulativeProbability
	 *            the new cumulative probability
	 */
	public void setCumulativeProbability(double cumulativeProbability)
	{
		if (!(cumulativeProbability > 0 && cumulativeProbability <= 1))
			throw new IllegalArgumentException("P must be in the range 0-1");
		this.cumulativeProbability = cumulativeProbability;
	}

	/**
	 * Gets the relative probability threshold of the Poisson-Gamma distribution that
	 * is used. Any probability less than this is ignored. This prevents using values
	 * that do not contribute to the sum.
	 *
	 * @return the relative probability threshold
	 */
	public double getRelativeProbabilityThreshold()
	{
		return relativeProbabilityThreshold;
	}

	/**
	 * Sets the relative probability threshold of the Poisson-Gamma distribution that
	 * is used. Any probability less than this is ignored. This prevents using values
	 * that do not contribute to the sum.
	 * <p>
	 * This must be in the range 0 to 0.5. Larger values would prevent using any
	 * slope of the Poisson-Gamma distribution. A value of zero requires all probability values to be used and may
	 * result in long computation times.
	 *
	 * @param relativeProbabilityThreshold
	 *            the new relative probability threshold
	 */
	public void setRelativeProbabilityThreshold(double relativeProbabilityThreshold)
	{
		if (!(relativeProbabilityThreshold >= 0 && relativeProbabilityThreshold <= 0.5))
			throw new IllegalArgumentException("P must be in the range 0-0.5");
		this.relativeProbabilityThreshold = relativeProbabilityThreshold;
	}

	/**
	 * If true, use Simpson's 3/8 rule for cubic interpolation of the integral. False uses Simpson's rule for
	 * quadratic interpolation.
	 *
	 * @return the use 38
	 */
	public boolean getUse38()
	{
		return use38;
	}

	/**
	 * Set to true to use Simpson's 3/8 rule for cubic interpolation of the integral. False uses Simpson's rule for
	 * quadratic interpolation.
	 *
	 * @param use38
	 *            the new use 38
	 */
	public void setUse38(boolean use38)
	{
		this.use38 = use38;
	}

	@Override
	protected void postClone()
	{
		list1 = new TDoubleArrayList();
		list2 = new TDoubleArrayList();
	}
}