package gdsc.smlm.function;

import java.util.Arrays;

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
public abstract class PoissonGammaGaussianFisherInformation implements FisherInformation
{
	// TODO - change this to a cumulative probability so that the entire distribution is sampled.

	// TODO - Fix the computation when the mean is between 0.1 and 10. 
	// There is a strange dip. 
	// Perhaps the kernel sampling is incorrect.
	// Maybe the Poisson-Gamma changes shape in this range.

	public static final double DEFAULT_RELATIVE_PROBABILITY_THRESHOLD = 1e-5;

	public static final double DEFAULT_CUMULATIVE_PROBABILITY = 1 - 1e-6;

	/** The gain multiplication factor. */
	public final double m;

	/** The standard deviation of the Gaussian. */
	public final double s;

	/** The range of the Gaussian kernel (in SD units). */
	public final double range;

	/** The extent of the Gaussian kernel. This is the next SD unit interval when the kernel is zero. */
	private final int extent;

	/** Working space to store the probabilities. */
	private TDoubleArrayList list1 = new TDoubleArrayList();
	/** Working space to store the gradient. */
	private TDoubleArrayList list2 = new TDoubleArrayList();

	/**
	 * The relative probability threshold of the Poisson-Gamma distribution that is used. Any probability less than this
	 * is ignored. This prevents using values that do not contribute to the sum.
	 */
	private double relativeProbabilityThreshold = DEFAULT_RELATIVE_PROBABILITY_THRESHOLD;

	/** The mean threshold for the switch to half the Poisson Fisher information. */
	private double meanThreshold = 200;

	/** The cumulative probability of the Poisson-Gamma distribution that is used. */
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
		this(m, s, 6);
	}

	/**
	 * Instantiates a new poisson gamma gaussian fisher information.
	 *
	 * @param m
	 *            the gain multiplication factor
	 * @param s
	 *            the standard deviation of the Gaussian
	 * @param range
	 *            the range of the Gaussian kernel (in SD units). This is clipped to the range
	 *            1-38 to provide a meaningful convolution.
	 * @throws IllegalArgumentException
	 *             If the standard deviation is not strictly positive
	 */
	public PoissonGammaGaussianFisherInformation(double m, double s, double range) throws IllegalArgumentException
	{
		if (!(m > 0 && m <= Double.MAX_VALUE))
			throw new IllegalArgumentException("Gain multiplication factor must be strictly positive");
		if (!(s > 0 && s <= Double.MAX_VALUE))
			throw new IllegalArgumentException("Gaussian variance must be strictly positive");

		// Gaussian = Math.exp(-0.5 * x^2)
		// FastMath.exp(-746) == 0
		// => range for the Gaussian is sqrt(2*746) = 38.6

		if (Double.isNaN(range))
			throw new IllegalArgumentException("Gaussian range must not be NaN");
		range = Maths.clip(1, 38, range);

		this.m = m;
		this.s = s;
		this.range = range;

		// Compute the extent of the kernel. This is the first SD interval where the kernel is zero.
		int irange = (int) Math.ceil(range);
		if (irange == range)
			irange++;
		extent = irange;
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
		final double upper = 1.0 / t; // PoissonFisherInformation.getPoissonI(t);;
		return Math.min(upper, I);
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
		if (t <= 0)
		{
			throw new IllegalArgumentException("Poisson mean must be positive");
		}

		if (t > meanThreshold)
		{
			// Use an approximation as half the Poisson Fisher information when the mean is large
			return 1.0 / (2 * t);
		}

		// XXX - remove
		//relativeProbabilityThreshold = 1e-5;

		// This computes the convolution of a Poisson-Gamma PDF and a Gaussian PDF.
		// The value of this is p(z).

		// The Poisson-Gamma-Gaussian must be differentiated to compute the Fisher information:
		// Expected [ (d ln(p(z)) dv)^2 ]
		// = integral [ (1/p(z) .  d p(z) dv)^2 p(z) dz ]
		// = integral [  1/p(z) . (d p(z) dv)^2 dz ]

		// -=-=-=-
		// Q. Can this be sped up using the method of Chao et al to 
		// compute part of the gradient and then subtract 1 at the end. This is true
		// if the sum of the partial gradient is 1.
		// Chao et al method ...
		// -=-=-=-		

		// Gaussian standard deviation = s

		// Note: (fg)' => fg' + f'g
		// e^-v => -e^-v
		// (e^-v * g(v))' => e^-v * g'(v) - e^-v * g(v)
		// So for a probability distribution with a e^-v component:
		// p(z|v)  = e^-v * g(v)
		// p'(z|v) = e^-v * g'(v) - p(z|v)

		// Chao et al, Eq 4:
		// (Poisson Binomial Gaussian convolution)

		// Gradient is:

		// This component can be seen in Chao et al, Eq S8:

		// Substitute the Poisson Binomial Gaussian with Poisson Gamma Gaussian:

		// Gradient is:

		// Fisher information is:

		// p(z) = 1/sqrt(2pi)s sum_j=0:Inf  e^-v . v^j / j! . e^-1/2((z-j)/s)^2

		// This is the sum over j of the probability of Poisson(j) * probability of Gaussian(z-j) 

		// Note: (fg)' => f'g + fg'
		// e^-v => -e^-v
		// v^j => j.v^(j-1)
		// e^-v v^j / j! => e^-v v^(j-1) / (j-1)! - e^-v v^j / j!

		// d p(z) dv = 1/sqrt(2pi)s sum_j=1:Inf  e^-v . v^(j-1) / (j-1)! . e^-1/2((z-j)/s)^2 - 
		//                          sum_j=0:Inf  e^-v . v^j / j! . e^-1/2((z-j)/s)^2
		// Note: j=0 differentiates to -e^v since v^j / j! = 1. This removes j=0 from the first sum
		// but not the second.
		// Over the sum the second term adds up to become p(z) so:
		// d p(z) dv = (1/sqrt(2pi)s sum_j=1:Inf  e^-v . v^(j-1) / (j-1)! . e^-1/2((z-j)/s)^2 ) - p(z) 

		// Set the first term to A, the second to P:
		// d p(z) dv = A - P

		// E = integral [ (1/p(z) . d p(z) dv)^2 p(z) dz ]
		//   = integral [ (1/P    . (A - P))^2 * P ]
		//   = integral [ (1/P^2  . (A^2 - 2AP + p^2) * P ]
		//   = integral [ (A^2/P^2 - 2A/P + 1) * P ]
		//   = integral [  A^2/P   - 2A   + P ]
		//   = integral [A^2/P] - integral [2A] + integral [P]

		// Note that the integral of P==1.
		// Since the integral of A is just P offset by j-1, integral of A==1

		// E = integral [A^2/P] - 1

		// P(z) = 1/sqrt(2pi)s sum_j=0:Inf  e^-v . v^j / j!         . e^-1/2((z-j)/s)^2
		// A(z) = 1/sqrt(2pi)s sum_j=1:Inf  e^-v . v^(j-1) / (j-1)! . e^-1/2((z-j)/s)^2
		// A(z) = 1/sqrt(2pi)s sum_j=0:Inf  e^-v . v^j / j!         . e^-1/2((z-(j+1))/s)^2
		// A(z) = P(z-1)

		// -=-=-=-

		// We need the convolution of:
		// - the Poisson-Gamma with the Gaussian
		// - the Poisson-Gamma gradient component with the Gaussian

		// Note that when the Poisson mean is low then the contribution from the 
		// Dirac delta function at 0 will be large. As the mean increases 
		// the Dirac will have little significance, the Poisson-Gamma is very smooth
		// and may have a large range. Thus convolution with a small Gaussian kernel 
		// may not be necessary.
		// The target is to computes
		// = integral [  1/p(z) . (d p(z) dv)^2 dz ]
		// So the integral can be computed as [integral range1] + [integral range2]

		// Range 1:
		// The range around the Dirac delta function is computed using a full convolution
		// with Gaussian over-sampling.
		// The Dirac delta function = exp(-p)
		// The Poisson-Gamma at zero = exp(-p) * p / m
		// Total = exp(-p) * (1 + p/m)
		// Ratio = 1 : p/m
		// The ratio between them determines the significance of the Dirac contribution.
		// Use more upsampling when the Dirac is large. This should compute a scale of 2 
		// when the ratio is 1:1. The scale increases as the ratio increase. 
		final double dirac = PoissonGammaFunction.dirac(t);

		// Q. Should the scale be computed using the dirac as well. When the Dirac is small
		// then this part of the Poisson-Gamma curve is insignificant.

		// Q. Should the scale just be the maximum as it is a small convolution compared
		// to the rest of the function when the mean is high. When the mean is low this is
		// the most important part.

		int scale = getPow2Scale(2 * m / t); // = 2 / (t/m)
		scale = 128;

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
		// TODO - make this number of steps configurable
		double step = mean / 50;
		// The step must coincide with the end of range 1, 
		// which is an integer factor of the standard deviation.
		int scale2;
		if (step < s)
		{
			// This will occur when the Gaussian is wide compared to the width of the Poisson-Gamma.
			// Upsample the Gaussian.
			scale2 = getPow2Scale(1.0 / step);
		}
		else
		{
			// This is when the Gaussian is narrow compared to the width of the Poisson-Gamma.
			// Just sample every unit of standard deviation.
			// TODO: There will be a point when convolution with a narrow Gaussian is expensive.
			// For now assume that the width will be reasonable (e.g. >1) and a second
			// convolution is always done.
			scale2 = 1;
		}

		// Ensure the first scale is greater then the second
		if (scale < scale2)
			scale = scale2;

		//scale = scale2 = 128;
		//use38 = false;

		h = s / scale;
		double G;
		double[] dG_dp = new double[1];
		list1.resetQuick();
		list2.resetQuick();
		G = PoissonGammaFunction.poissonGammaPartial(0, t, m, dG_dp);
		// Since convolution with the Gaussian should only be done for c>=0 use
		// only half the first value
		double c0factor = 0.5;
		final double p0 = (G - dirac) * c0factor;
		list1.add(p0);
		list2.add(dG_dp[0] * c0factor);

		// Compute the max fisher information for the unconvolved function.
		double max = 0;
		// The limit is set so that we have the full Poisson-Gamma function computed
		// when the Gaussian kernel no longer touches zero.
		int limit = extent * scale * 2;
		for (int i = 1; i <= limit; i++)
		{
			G = PoissonGammaFunction.poissonGammaPartial(h * i, t, m, dG_dp);
			double f = getF(G, dG_dp[0]);
			if (max < f)
				max = f;
			list1.add(G);
			list2.add(dG_dp[0]);
		}

		double endRange1 = limit * h;

		// If the range is past the range expected then do a single range.
		// There is a zero in the Fisher Information at the mean (t*m) so ensure we compute
		// at least 2 * mean before checking the relative size.
		// TODO - make the threshold configurable
		boolean singleRange = scale == scale2 || endRange1 > 5 * mean || true;
		if (singleRange)
		{
			// Continue until all the Fisher information has been achieved.
			int checkI = (int) Math.ceil((2 * mean - endRange1) / h);
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
				if (i > checkI && f / max < relativeProbabilityThreshold)
					break;
			}
		}

		// Convolve with the Gaussian kernel
		double[] p = list1.toArray();
		double[] a = list2.toArray();

		System.out.printf("t=%g  sum p=%s  single=%b  h=%g\n", t, (Maths.sum(p) + (1 - c0factor) * p0) * h + dirac,
				singleRange, h);

		// Should this convolve without the Dirac then add that afterwards.
		// This is how the Camera Model Analysis works. Perhaps there is
		// floating point error when the dirac is large.

		double[] g = getUnitErfGaussianKernel(scale);
		double[][] result = Convolution.convolveFast(g, p, a);
		P = result[0];
		A = result[1];
		offset = g.length / 2 * -h;

		// Add convolution of Dirac
		g = getUnitGaussianKernel(scale);
		for (int i = 0; i < g.length; i++)
			P[i] += g[i] * dirac;

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

		if (singleRange)
		{
			//return dirac*dirac * range1sum - 1;
			//turn range1sum - 1;
			return range1sum;
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
		list2.add(dG_dp[0] * c0factor);
		for (int i = interval; i < p.length; i += interval)
		{
			list1.add(p[i]);
			list2.add(a[i]);
		}

		// For a dual range compute sum only from the range 1 limit
		// (this was p.length / 2 since the length was double for full convolution
		// with the Gaussian).
		//int mini = (p.length / 2) / interval;		
		int mini = list1.size() / 2;

		// Continue from the end of range 1 until all the Fisher information has been achieved.
		// There is a zero in the Fisher Information at the mean (t*m) so ensure we compute
		// at least 2 * mean before checking the relative size.
		int checkI = (int) Math.ceil((2 * mean - endRange1) / h);
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
			if (i > checkI && f / max < relativeProbabilityThreshold)
				break;
		}

		p = list1.toArray();
		a = list2.toArray();

		System.out.printf("t=%g  sum p2=%g  endRange1=%g\n", t, (Maths.sum(p) + (1 - c0factor) * p0) * h + dirac,
				endRange1);

		g = getUnitErfGaussianKernel(scale2);
		result = Convolution.convolveFast(g, p, a);
		P = result[0];
		A = result[1];
		offset = g.length / 2 * -h;

		// Add convolution of Dirac
		g = getUnitGaussianKernel(scale2);
		for (int i = 0; i < g.length; i++)
			P[i] += g[i] * dirac;

		// Add the kernel size to get the point when new values occur.
		mini += g.length / 2;

		// Check that the endRange1F is the same as the start point.
		// When debugging set scale2 == scale. However this may still
		// be different if convolution used the FFT due to the different
		// size data. When scale2 != scale then it will be different
		// due to less accurate sampling of the convolution.
		double range2sum = getF(P[mini], A[mini]);
		System.out.printf("%s == %s (%g)\n", endRange1F, range2sum,
				gdsc.core.utils.DoubleEquality.relativeError(endRange1F, range2sum));

		// We assume that the function values at the end are zero and so do not 
		// include them in the sum. Just alternate totals.
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

		return range1sum + range2sum;
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
	 * This will only be called with scales of power 2.
	 *
	 * @param scale
	 *            the scale
	 * @return the gaussian kernel
	 */
	protected abstract double[] getUnitGaussianKernel(int scale);

	/**
	 * Gets the gaussian kernel for convolution using a standard deviation of 1.
	 * The kernel will be sampled every (1 / scale).
	 * This will only be called with scales of power 2.
	 * <p>
	 * This kernel should be integrated over the range -1/scale to 1/scale for each
	 * sample point using the error function (Erf). If not available then {@link #getUnitGaussianKernel(int)} can be
	 * returned.
	 *
	 * @param scale
	 *            the scale
	 * @return the gaussian kernel
	 */
	protected abstract double[] getUnitErfGaussianKernel(int scale);

	private static double getF(double P, double A)
	{
		return (P == 0) ? 0 : Maths.pow2(A - P) / P;

		// Assuming that we subtract 1 from the sum at the end
		//turn (P == 0) ? 0 : Maths.pow2(A) / P;
	}

	/**
	 * Gets the fisher information function. This is the function that is integrated to get the Fisher information. This
	 * can be called after a called to {@link #getFisherInformation(double)}. It will return the last value of the
	 * function that was computed (before convolution with the Gaussian kernel).
	 * ,p>
	 * The function should be smooth with zero at the upper end. The lower end may not be smooth due to the Dirac delta
	 * function at c=0.
	 *
	 * @return the fisher information function
	 */
	public double[][] getFisherInformationFunction()
	{
		if (A == null)
			return null;
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
	 * Gets the Gaussian Fisher information for mean 0.
	 * Fisher information of Gaussian mean is 1/variance.
	 *
	 * @return the Gaussian Fisher information
	 */
	public double getGaussianI()
	{
		return 1.0 / (s * s);
	}

	/**
	 * Gets the mean threshold for the switch to half the Poisson Fisher information.
	 *
	 * @return the mean threshold
	 */
	public double getMeanThreshold()
	{
		return meanThreshold;
	}

	/**
	 * Sets the mean threshold for the switch to half the Poisson Fisher information.
	 *
	 * @param meanThreshold
	 *            the new mean threshold
	 */
	public void setMeanThreshold(double meanThreshold)
	{
		this.meanThreshold = meanThreshold;
	}

	/**
	 * Gets the cumulative probability of the Poisson-Gamma distribution that is used.
	 *
	 * @return the cumulative probability
	 */
	public double getCumulativeProbability()
	{
		return cumulativeProbability;
	}

	/**
	 * Sets the cumulative probability of the Poisson-Gamma distribution that is used.
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
}