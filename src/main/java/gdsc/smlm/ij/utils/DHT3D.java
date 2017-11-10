package gdsc.smlm.ij.utils;

import org.jtransforms.dht.FloatDHT_3D;

import ij.ImageStack;
import ij.process.FHT2;
import pl.edu.icm.jlargearrays.LargeArray;

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
 * Wrapper to compute the discrete Hartley transform on 3D data. This uses the JTransforms library.
 */
public class DHT3D extends Image3D
{
	private boolean isFrequencyDomain;
	private final FloatDHT_3D dht;

	/**
	 * Instantiates a new 3D discrete Hartley transform
	 *
	 * @param stack
	 *            the stack
	 * @throws IllegalArgumentException
	 *             If any dimension is less than 2, or if the combined dimensions is too large for an array
	 */
	public DHT3D(ImageStack stack) throws IllegalArgumentException
	{
		super(stack);
		LargeArray.setMaxSizeOf32bitArray(MAX_SIZE_OF_32_BIT_ARRAY);
		dht = new FloatDHT_3D(ns, nr, nc);
	}

	/**
	 * Instantiates a new 3D discrete Hartley transform.
	 *
	 * @param nc
	 *            the number of columns
	 * @param nr
	 *            the number of rows
	 * @param ns
	 *            the number of slices
	 * @param data
	 *            the data
	 * @param isFrequencyDomain
	 *            Set to true if in the frequency domain
	 * @throws IllegalArgumentException
	 *             If any dimension is less than 2, or if the data is not the correct length
	 */
	public DHT3D(int nc, int nr, int ns, float[] data, boolean isFrequencyDomain) throws IllegalArgumentException
	{
		super(nc, nr, ns, data);
		LargeArray.setMaxSizeOf32bitArray(MAX_SIZE_OF_32_BIT_ARRAY);
		dht = new FloatDHT_3D(ns, nr, nc);
		this.isFrequencyDomain = isFrequencyDomain;
	}

	/**
	 * Instantiates a new 3D discrete Hartley transform.
	 *
	 * @param nc
	 *            the number of columns
	 * @param nr
	 *            the number of rows
	 * @param ns
	 *            the number of slices
	 * @param nr_by_nc
	 *            the number of rows multiplied by the number of columns
	 * @param data
	 *            the data
	 * @param isFrequencyDomain
	 *            the is frequency domain
	 * @param dht
	 *            the dht
	 */
	private DHT3D(int nc, int nr, int ns, int nr_by_nc, float[] data, boolean isFrequencyDomain, FloatDHT_3D dht)
	{
		super(nc, nr, ns, nr_by_nc, data);
		this.isFrequencyDomain = isFrequencyDomain;
		this.dht = dht; // This can be reused across objects
	}

	/**
	 * Return a copy of the 3D discrete Hartley transform.
	 *
	 * @return the copy
	 */
	@Override
	public DHT3D copy()
	{
		return new DHT3D(nc, nr, ns, nr_by_nc, data.clone(), isFrequencyDomain, dht);
	}

	/**
	 * Performs a forward transform, converting this image into the frequency domain.
	 * 
	 * @throws IllegalArgumentException
	 *             If already in the frequency domain
	 */
	public void transform() throws IllegalArgumentException
	{
		if (isFrequencyDomain)
			throw new IllegalArgumentException("Already frequency domain DHT");
		dht.forward(data);
		isFrequencyDomain = true;
	}

	/**
	 * Performs an inverse transform, converting this image into the space domain.
	 *
	 * @throws IllegalArgumentException
	 *             If already in the space domain
	 */
	public void inverseTransform() throws IllegalArgumentException
	{
		if (!isFrequencyDomain)
			throw new IllegalArgumentException("Already space domain DHT");
		dht.inverse(data, true);
		isFrequencyDomain = false;
	}

	/**
	 * Checks if is frequency domain.
	 *
	 * @return true, if is frequency domain
	 */
	public boolean isFrequencyDomain()
	{
		return isFrequencyDomain;
	}

	/**
	 * Returns the image resulting from the point by point Hartley multiplication
	 * of this image and the specified image. Both images are assumed to be in
	 * the frequency domain. Multiplication in the frequency domain is equivalent
	 * to convolution in the space domain.
	 *
	 * @param dht
	 *            the dht
	 * @return the result
	 * @throws IllegalArgumentException
	 *             if the dht is not the same dimensions
	 */
	public DHT3D multiply(DHT3D dht) throws IllegalArgumentException
	{
		return multiply(dht, null);
	}

	/**
	 * Returns the image resulting from the point by point Hartley multiplication
	 * of this image and the specified image. Both images are assumed to be in
	 * the frequency domain. Multiplication in the frequency domain is equivalent
	 * to convolution in the space domain.
	 *
	 * @param dht
	 *            the dht
	 * @param tmp
	 *            the tmp buffer to use for the result
	 * @return the result
	 * @throws IllegalArgumentException
	 *             if the dht is not the same dimensions
	 */
	public DHT3D multiply(DHT3D dht, float[] tmp) throws IllegalArgumentException
	{
		checkDHT(dht);

		float[] h1 = this.data;
		float[] h2 = dht.data;
		if (tmp == null || tmp.length != h1.length)
			tmp = new float[h1.length];

		for (int s = 0, ns_m_s = 0, i = 0; s < ns; s++, ns_m_s = ns - s)
		{
			for (int r = 0, nr_m_r = 0; r < nr; r++, nr_m_r = nr - r)
			{
				for (int c = 0, nc_m_c = 0; c < nc; c++, nc_m_c = nc - c, i++)
				{
					// This is actually doing for 3D data stored as x[slices][rows][columns]
					// https://en.wikipedia.org/wiki/Discrete_Hartley_transform
					//h2e = (h2[s][r][c] + h2[Ns-s][Nr-r][Nr-c]) / 2;
					//h2o = (h2[s][r][c] - h2[Ns-s][Nr-r][Nr-c]) / 2;
					//tmp[s][r][c] = (float) (h1[s][r][c] * h2e + h1[Ns-s][Nr-r][Nc-c] * h2o);
					int j = ns_m_s * nr_by_nc + nr_m_r * nc + nc_m_c;
					double h2e = (h2[i] + h2[j]) / 2;
					double h2o = (h2[i] - h2[j]) / 2;
					tmp[i] = (float) (h1[i] * h2e + h1[j] * h2o);
				}
			}
		}

		return new DHT3D(nc, nr, ns, nr_by_nc, tmp, true, this.dht);
	}

	/**
	 * Returns the image resulting from the point by point Hartley conjugate
	 * multiplication of this image and the specified image. Both images are
	 * assumed to be in the frequency domain. Conjugate multiplication in
	 * the frequency domain is equivalent to correlation in the space domain.
	 *
	 * @param dht
	 *            the dht
	 * @return the result
	 * @throws IllegalArgumentException
	 *             if the dht is not the same dimensions
	 */
	public DHT3D conjugateMultiply(DHT3D dht) throws IllegalArgumentException
	{
		return conjugateMultiply(dht, null);
	}

	/**
	 * Returns the image resulting from the point by point Hartley conjugate
	 * multiplication of this image and the specified image. Both images are
	 * assumed to be in the frequency domain. Conjugate multiplication in
	 * the frequency domain is equivalent to correlation in the space domain.
	 *
	 * @param dht
	 *            the dht
	 * @param tmp
	 *            the tmp buffer to use for the result
	 * @return the result
	 * @throws IllegalArgumentException
	 *             if the dht is not the same dimensions
	 */
	public DHT3D conjugateMultiply(DHT3D dht, float[] tmp) throws IllegalArgumentException
	{
		checkDHT(dht);

		float[] h1 = this.data;
		float[] h2 = dht.data;
		if (tmp == null || tmp.length != h1.length)
			tmp = new float[h1.length];

		for (int s = 0, ns_m_s = 0, i = 0; s < ns; s++, ns_m_s = ns - s)
		{
			for (int r = 0, nr_m_r = 0; r < nr; r++, nr_m_r = nr - r)
			{
				for (int c = 0, nc_m_c = 0; c < nc; c++, nc_m_c = nc - c, i++)
				{
					int j = ns_m_s * nr_by_nc + nr_m_r * nc + nc_m_c;
					double h2e = (h2[i] + h2[j]) / 2;
					double h2o = (h2[i] - h2[j]) / 2;
					// As per multiply but reverse the addition sign for the conjugate  
					tmp[i] = (float) (h1[i] * h2e - h1[j] * h2o);
				}
			}
		}

		return new DHT3D(nc, nr, ns, nr_by_nc, tmp, true, this.dht);
	}

	/**
	 * Returns the image resulting from the point by point Hartley division
	 * of this image by the specified image. Both images are assumed to be in
	 * the frequency domain. Division in the frequency domain is equivalent
	 * to deconvolution in the space domain.
	 *
	 * @param dht
	 *            the dht
	 * @return the result
	 * @throws IllegalArgumentException
	 *             if the dht is not the same dimensions or in the frequency domain
	 */
	public DHT3D divide(DHT3D dht) throws IllegalArgumentException
	{
		return divide(dht, null);
	}

	/**
	 * Returns the image resulting from the point by point Hartley division
	 * of this image by the specified image. Both images are assumed to be in
	 * the frequency domain. Division in the frequency domain is equivalent
	 * to deconvolution in the space domain.
	 *
	 * @param dht
	 *            the dht
	 * @param tmp
	 *            the tmp buffer to use for the result
	 * @return the result
	 * @throws IllegalArgumentException
	 *             if the dht is not the same dimensions or in the frequency domain
	 */
	public DHT3D divide(DHT3D dht, float[] tmp) throws IllegalArgumentException
	{
		checkDHT(dht);

		float[] h1 = this.data;
		float[] h2 = dht.data;
		if (tmp == null || tmp.length != h1.length)
			tmp = new float[h1.length];

		for (int s = 0, ns_m_s = 0, i = 0; s < ns; s++, ns_m_s = ns - s)
		{
			for (int r = 0, nr_m_r = 0; r < nr; r++, nr_m_r = nr - r)
			{
				for (int c = 0, nc_m_c = 0; c < nc; c++, nc_m_c = nc - c, i++)
				{
					// This is a copy of the divide operation in ij.process.FHT
					int j = ns_m_s * nr_by_nc + nr_m_r * nc + nc_m_c;
					double mag = h2[i] * h2[i] + h2[j] * h2[j];
					if (mag < 1e-20)
						mag = 1e-20;
					double h2e = (h2[i] + h2[j]);
					double h2o = (h2[i] - h2[j]);
					tmp[i] = (float) ((h1[i] * h2e - h1[j] * h2o) / mag);
				}
			}
		}

		return new DHT3D(nc, nr, ns, nr_by_nc, tmp, true, this.dht);
	}

	/**
	 * Check the DHT matches the dimensions of this DHT. Check both are in the frequency domain.
	 *
	 * @param dht
	 *            the dht
	 * @throws IllegalArgumentException
	 *             If multiplication is not possible
	 */
	private void checkDHT(DHT3D dht) throws IllegalArgumentException
	{
		if (dht.ns != ns || dht.nr != nr || dht.nc != nc)
			throw new IllegalArgumentException("Dimension mismatch");
		if (!dht.isFrequencyDomain || !isFrequencyDomain)
			throw new IllegalArgumentException("Require frequency domain DHT");
	}

	/**
	 * Converts this DHT to a discrete Fourier transform (DFT) and returns it as a two image pair. The image is assumed
	 * to be in the frequency domain.
	 *
	 * @param real
	 *            the buffer to use for the real component (can be null)
	 * @param imaginary
	 *            the buffer to use for the imaginary component (can be null)
	 * @return [real, imaginary]
	 * @throws IllegalArgumentException
	 *             if not in the frequency domain
	 * @see <A href=
	 *      "https://en.wikipedia.org/wiki/Hartley_transform#Relation_to_Fourier_transform">https://en.wikipedia.org/wiki/Hartley_transform#Relation_to_Fourier_transform</a>
	 */
	public Image3D[] toDFT(float[] real, float[] imaginary) throws IllegalArgumentException
	{
		if (!isFrequencyDomain)
			throw new IllegalArgumentException("Require frequency domain DHT");

		float[] h1 = this.data;
		if (real == null || real.length != h1.length)
			real = new float[h1.length];
		if (imaginary == null || imaginary.length != h1.length)
			imaginary = new float[h1.length];

		for (int s = 0, ns_m_s = 0, i = 0; s < ns; s++, ns_m_s = ns - s)
		{
			for (int r = 0, nr_m_r = 0; r < nr; r++, nr_m_r = nr - r)
			{
				for (int c = 0, nc_m_c = 0; c < nc; c++, nc_m_c = nc - c, i++)
				{
					// This is a copy of the getComplexTransform operation in ij.process.FHT
					int j = ns_m_s * nr_by_nc + nr_m_r * nc + nc_m_c;
					real[i] = (h1[i] + h1[j]) * 0.5f;
					imaginary[i] = (-h1[i] + h1[j]) * 0.5f;
				}
			}
		}

		return new Image3D[] { new Image3D(nc, nr, ns, nr_by_nc, real), new Image3D(nc, nr, ns, nr_by_nc, imaginary) };
	}

	/**
	 * Convert a discrete Fourier transform (DFT) to a DHT
	 *
	 * @param real
	 *            the real component
	 * @param imaginary
	 *            the imaginary component
	 * @param tmp
	 *            the tmp buffer to use for the result
	 * @return the DHT
	 * @throws IllegalArgumentException
	 *             If there is a dimension mismatch
	 */
	public static DHT3D fromDFT(Image3D real, Image3D imaginary, float[] tmp) throws IllegalArgumentException
	{
		if (real.ns != imaginary.ns || real.nr != imaginary.nr || real.nc != imaginary.nc)
			throw new IllegalArgumentException("Dimension mismatch");

		float[] re = real.getData();
		float[] im = imaginary.getData();
		if (tmp == null || tmp.length != re.length)
			tmp = new float[re.length];

		int nc = real.nc;
		int nr = real.nr;
		int ns = real.ns;
		int nr_by_nc = real.nr_by_nc;

		for (int s = 0, ns_m_s = 0, i = 0; s < ns; s++, ns_m_s = ns - s)
		{
			for (int r = 0, nr_m_r = 0; r < nr; r++, nr_m_r = nr - r)
			{
				for (int c = 0, nc_m_c = 0; c < nc; c++, nc_m_c = nc - c, i++)
				{
					int j = ns_m_s * nr_by_nc + nr_m_r * nc + nc_m_c;
					// Reverse the toDFT() method
					// re = (a+b)/2
					// im = (-a+b)/2
					// b = re + im
					// a = 2*re - b
					tmp[j] = re[i] + im[i];
					tmp[i] = 2 * re[i] - tmp[j];
				}
			}
		}

		return new DHT3D(nc, nr, ns, nr_by_nc, tmp, true, new FloatDHT_3D(ns, nr, nc));
	}

	/**
	 * Returns the absolute value (amplitude) of the Hartley transform. The image is assumed to be in
	 * the frequency domain.
	 *
	 * @param tmp
	 *            the tmp buffer to use for the result
	 * @return the result
	 * @throws IllegalArgumentException
	 *             if not in the frequency domain
	 */
	public Image3D getAbsoluteValue(float[] tmp) throws IllegalArgumentException
	{
		if (!isFrequencyDomain)
			throw new IllegalArgumentException("Require frequency domain DHT");

		float[] h1 = this.data;
		if (tmp == null || tmp.length != h1.length)
			tmp = new float[h1.length];

		for (int s = 0, ns_m_s = 0, i = 0; s < ns; s++, ns_m_s = ns - s)
		{
			for (int r = 0, nr_m_r = 0; r < nr; r++, nr_m_r = nr - r)
			{
				for (int c = 0, nc_m_c = 0; c < nc; c++, nc_m_c = nc - c, i++)
				{
					// This is a copy of the amplitude operation in ij.process.FHT
					int j = ns_m_s * nr_by_nc + nr_m_r * nc + nc_m_c;
					tmp[i] = (float) Math.sqrt(h1[i] * h1[i] + h1[j] * h1[j]);
				}
			}
		}

		return new Image3D(nc, nr, ns, nr_by_nc, tmp);
	}

	/**
	 * Swap octants so the power spectrum origin is at the centre of the image.
	 * 
	 * <pre>
	 * 1 +++ <=> 7 ---
	 * 2 -++ <=> 8 +--
	 * 3 --+ <=> 5 ++-
	 * 4 +-+ <=> 6 -+-
	 * </pre>
	 * 
	 * Requires even dimensions.
	 *
	 * @throws IllegalArgumentException
	 *             If not even dimensions
	 * @see https://en.m.wikipedia.org/wiki/Octant_(solid_geometry)
	 */
	public void swapOctants() throws IllegalArgumentException
	{
		swapOctants(this);
	}

	/**
	 * Swap octants of the specified image stack so the power spectrum origin is at the centre of the image.
	 * 
	 * <pre>
	 * 1 +++ <=> 7 ---
	 * 2 -++ <=> 8 +--
	 * 3 --+ <=> 5 ++-
	 * 4 +-+ <=> 6 -+-
	 * </pre>
	 * 
	 * Requires even dimensions.
	 *
	 * @param image
	 *            the image
	 * @throws IllegalArgumentException
	 *             If not even dimensions
	 * @see https://en.m.wikipedia.org/wiki/Octant_(solid_geometry)
	 */
	public static void swapOctants(Image3D image) throws IllegalArgumentException
	{
		int ns = image.ns;
		int nr = image.nr;
		int nc = image.nc;

		if ((ns & 1) == 1 || (nr & 1) == 1 || (nc & 1) == 1)
			throw new IllegalArgumentException("Require even dimensions");

		int ns_2 = ns / 2;
		int nr_2 = nr / 2;
		int nc_2 = nc / 2;

		float[] tmp = new float[nc];

		// For convenience we extract slices for swapping with the FHT2 routine
		int nr_by_nc = image.nr_by_nc;
		float[] data = image.data;
		float[] a = new float[nr_by_nc];
		float[] b = new float[nr_by_nc];

		for (int s = 0; s < ns_2; s++)
		{
			// Extract
			System.arraycopy(data, s * nr_by_nc, a, 0, nr_by_nc);
			System.arraycopy(data, (s + ns_2) * nr_by_nc, b, 0, nr_by_nc);

			//@formatter:off
			// We swap: 0 <=> nx_2, 0 <=> ny_2
			// 1 <=> 7 
			FHT2.swap(a, b, nc, nc_2,    0,    0, nr_2, nc_2, nr_2, tmp);
			// 2 <=> 8
			FHT2.swap(a, b, nc,    0,    0, nc_2, nr_2, nc_2, nr_2, tmp);
			// 3 <=> 5
			FHT2.swap(a, b, nc,    0, nr_2, nc_2,    0, nc_2, nr_2, tmp);
			// 4 <=> 6
			FHT2.swap(a, b, nc, nc_2, nr_2,    0,    0, nc_2, nr_2, tmp);
			//@formatter:on

			// Replace
			System.arraycopy(a, 0, data, s * nr_by_nc, nr_by_nc);
			System.arraycopy(b, 0, data, (s + ns_2) * nr_by_nc, nr_by_nc);
		}
	}

	/**
	 * Swap octants of the specified image stack so the power spectrum origin is at the centre of the image.
	 * 
	 * <pre>
	 * 1 +++ <=> 7 ---
	 * 2 -++ <=> 8 +--
	 * 3 --+ <=> 5 ++-
	 * 4 +-+ <=> 6 -+-
	 * </pre>
	 * 
	 * Requires even dimensions in a 32-bit float stack.
	 *
	 * @param stack
	 *            the stack
	 * @throws IllegalArgumentException
	 *             If not a float stack with even dimensions
	 * @see https://en.m.wikipedia.org/wiki/Octant_(solid_geometry)
	 */
	public static void swapOctants(ImageStack stack) throws IllegalArgumentException
	{
		if (stack.getBitDepth() != 32)
			throw new IllegalArgumentException("Require float stack");
		int ns = stack.getSize();
		int nr = stack.getHeight();
		int nc = stack.getWidth();
		if ((ns & 1) == 1 || (nr & 1) == 1 || (nc & 1) == 1)
			throw new IllegalArgumentException("Require even dimensions");

		int ns_2 = ns / 2;
		int nr_2 = nr / 2;
		int nc_2 = nc / 2;

		float[] tmp = new float[nc];

		for (int s = 0; s < ns_2; s++)
		{
			// slice index is 1-based
			float[] a = (float[]) stack.getPixels(1 + s);
			float[] b = (float[]) stack.getPixels(1 + s + ns_2);
			//@formatter:off
			// We swap: 0 <=> nx_2, 0 <=> ny_2
			// 1 <=> 7 
			FHT2.swap(a, b, nc, nc_2,    0,    0, nr_2, nc_2, nr_2, tmp);
			// 2 <=> 8
			FHT2.swap(a, b, nc,    0,    0, nc_2, nr_2, nc_2, nr_2, tmp);
			// 3 <=> 5
			FHT2.swap(a, b, nc,    0, nr_2, nc_2,    0, nc_2, nr_2, tmp);
			// 4 <=> 6
			FHT2.swap(a, b, nc, nc_2, nr_2,    0,    0, nc_2, nr_2, tmp);
			//@formatter:on
		}
	}
}