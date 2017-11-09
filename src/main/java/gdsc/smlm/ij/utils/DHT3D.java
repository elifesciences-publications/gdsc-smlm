package gdsc.smlm.ij.utils;

import org.jtransforms.dht.FloatDHT_3D;

import ij.ImageStack;
import ij.process.FHT2;
import ij.process.ImageProcessor;
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
public class DHT3D
{
	/** The number of slices (max z). */
	public final int ns;
	/** The number of rows (max y). */
	public final int nr;
	/** The number of columns (max x). */
	public final int nc;

	/** The number of rows multiplied by the number of columns */
	private final int nr_by_nc;

	private boolean isFrequencyDomain;
	private final FloatDHT_3D dht;
	private final float[] data;

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
		ns = stack.getSize();
		nr = stack.getHeight();
		nc = stack.getWidth();

		long size = (long) ns * nr * nc;
		// Don't support using large arrays for simplicity
		if (size > LargeArray.getMaxSizeOf32bitArray())
			throw new IllegalArgumentException("3D data too large");

		dht = new FloatDHT_3D(ns, nr, nc);

		data = new float[(int) size];

		nr_by_nc = nr * nc;
		if (stack.getBitDepth() == 32)
		{
			for (int s = 0; s < ns; s++)
			{
				System.arraycopy(stack.getPixels(s + 1), 0, data, s * nr_by_nc, nr_by_nc);
			}
		}
		else
		{
			for (int s = 1, i = 0; s <= ns; s++)
			{
				ImageProcessor ip = stack.getProcessor(s);
				for (int j = 0; i < nr_by_nc; j++)
					data[i++] = ip.getf(j);
			}
		}
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
		long size = (long) ns * nr * nc;
		if (data == null || data.length != size)
			throw new IllegalArgumentException("Data is not correct length");
		dht = new FloatDHT_3D(ns, nr, nc);
		this.ns = ns;
		this.nr = nr;
		this.nc = nc;
		nr_by_nc = nr * nc;
		this.data = data;
		this.isFrequencyDomain = isFrequencyDomain;
	}

	/**
	 * Instantiates a new 3D discrete Hartley transform.
	 *
	 * @param ns
	 *            the number of slices
	 * @param nr
	 *            the number of rows
	 * @param nc
	 *            the number of columns
	 * @param nr_by_nc
	 *            the number of rows multiplied by the number of columns
	 * @param data
	 *            the data
	 * @param isFrequencyDomain
	 *            the is frequency domain
	 * @param dht
	 *            the dht
	 */
	private DHT3D(int ns, int nr, int nc, int nr_by_nc, float[] data, boolean isFrequencyDomain, FloatDHT_3D dht)
	{
		// No checks as this is used internally		
		this.ns = ns;
		this.nr = nr;
		this.nc = nc;
		this.nr_by_nc = nr_by_nc;
		this.data = data;
		this.isFrequencyDomain = isFrequencyDomain;
		this.dht = dht; // This can be reused across objects
	}

	/**
	 * Return a copy of the 3D discrete Hartley transform.
	 *
	 * @return the copy
	 */
	public DHT3D copy()
	{
		return new DHT3D(ns, nr, nc, nr_by_nc, data.clone(), isFrequencyDomain, dht);
	}

	/**
	 * Gets the data.
	 *
	 * @return the data
	 */
	public float[] getData()
	{
		return data;
	}

	/**
	 * Convert to an image stack.
	 *
	 * @return the image stack
	 */
	public ImageStack getImageStack()
	{
		ImageStack stack = new ImageStack(nc, nr);
		for (int s = 0; s < ns; s++)
		{
			float[] pixels = new float[nr_by_nc];
			System.arraycopy(data, s * nr_by_nc, pixels, 0, nr_by_nc);
			stack.addSlice(null, pixels);
		}
		return stack;
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

		return new DHT3D(ns, nr, nc, nr_by_nc, tmp, true, this.dht);
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

		return new DHT3D(ns, nr, nc, nr_by_nc, tmp, true, this.dht);
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

		return new DHT3D(ns, nr, nc, nr_by_nc, tmp, true, this.dht);
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
	 * Swap octants 1+7, 2+8, 4+6, 3+5 of the specified image stack
	 * so the power spectrum origin is at the centre of the image.
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
	public void swapOctants() throws IllegalArgumentException
	{
		if ((ns & 1) == 1 || (nr & 1) == 1 || (nc & 1) == 1)
			throw new IllegalArgumentException("Require even dimensions");

		int ns_2 = ns / 2;
		int nr_2 = nr / 2;
		int nc_2 = nc / 2;

		float[] tmp = new float[nc];

		// For convenience we extract slices for swapping
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
	 * Swap octants 1+7, 2+8, 4+6, 3+5 of the specified image stack
	 * so the power spectrum origin is at the centre of the image.
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

	/**
	 * Gets the xyz components of the index.
	 *
	 * @param i
	 *            the index
	 * @return the xyz components
	 * @throws IllegalArgumentException
	 *             if the index is not within the data
	 */
	public int[] getXYZ(int i) throws IllegalArgumentException
	{
		if (i < 0 || i >= data.length)
			throw new IllegalArgumentException("Index in not in the correct range: 0 <= i < " + data.length);
		int[] xyz = new int[3];
		xyz[2] = i / nr_by_nc;
		int j = i % nr_by_nc;
		xyz[1] = j / nc;
		xyz[0] = j % nc;
		return xyz;
	}

	/**
	 * Gets the xyz components of the index.
	 *
	 * @param i
	 *            the index
	 * @param xyz
	 *            the xyz components (must be an array of at least length 3)
	 * @throws IllegalArgumentException
	 *             if the index is not within the data
	 */
	public void getXYZ(int i, int[] xyz) throws IllegalArgumentException
	{
		if (i < 0 || i >= data.length)
			throw new IllegalArgumentException("Index in not in the correct range: 0 <= i < " + data.length);
		xyz[2] = i / nr_by_nc;
		int j = i % nr_by_nc;
		xyz[1] = j / nc;
		xyz[0] = j % nc;
	}

	/**
	 * Crop a sub-region of the data.
	 *
	 * @param x
	 *            the x index
	 * @param y
	 *            the y index
	 * @param z
	 *            the z index
	 * @param w
	 *            the width
	 * @param h
	 *            the height
	 * @param d
	 *            the depth
	 * @param region
	 *            the cropped data (will be reused if the correct size)
	 * @return the cropped data
	 * @throws IllegalArgumentException
	 *             if the region is not within the data
	 */
	public float[] crop(int x, int y, int z, int w, int h, int d, float[] region)
	{
		// Check the region range
		if (x < 0 || x + w >= nc || y < 0 || y + h >= nr || z < 0 || z + d >= ns)
			throw new IllegalArgumentException("Region not within the data");
		int size = d * h * w;
		if (region == null || region.length != size)
			region = new float[size];
		for (int s = 0, i = 0; s < d; s++, z++)
		{
			int base = z * nr_by_nc + y * nc + x;
			for (int r = 0; r < h; r++)
			{
				System.arraycopy(data, base, region, i, w);
				base += nc;
				i += w;
			}
		}
		return region;
	}

	/**
	 * Crop a sub-region of the data.
	 *
	 * @param x
	 *            the x index
	 * @param y
	 *            the y index
	 * @param z
	 *            the z index
	 * @param w
	 *            the width
	 * @param h
	 *            the height
	 * @param d
	 *            the depth
	 * @return the cropped data
	 * @throws IllegalArgumentException
	 *             if the region is not within the data
	 */
	public ImageStack crop(int x, int y, int z, int w, int h, int d)
	{
		// Check the region range
		if (x < 0 || x + w >= nc || y < 0 || y + h >= nr || z < 0 || z + d >= ns)
			throw new IllegalArgumentException("Region not within the data");
		int size = w * h;
		ImageStack stack = new ImageStack(w, h);
		for (int s = 0; s < d; s++, z++)
		{
			int base = z * nr_by_nc + y * nc + x;
			float[] region = new float[size];
			for (int r = 0, i = 0; r < h; r++)
			{
				System.arraycopy(data, base, region, i, w);
				base += nc;
				i += w;
			}
			stack.addSlice(null, region);
		}
		return stack;
	}
}