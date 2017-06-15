package gdsc.smlm.results.procedures;

import gdsc.smlm.data.config.SMLMSettings.DistanceUnit;
import gdsc.smlm.data.config.SMLMSettings.IntensityUnit;
import gdsc.smlm.results.MemoryPeakResults;
import gdsc.smlm.results.PeakResult;

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
 * Contains functionality to obtain the standard calibrated data for results.
 */
//@formatter:off
public class StandardResultProcedure extends UnitResultProcedure implements 
        BIXYResultProcedure, 
        BIXYZResultProcedure, 
        IResultProcedure, 
        IXYResultProcedure, 
        IXYZResultProcedure,
		TXYResultProcedure, 
		XYResultProcedure, 
		XYRResultProcedure, 
		XYZResultProcedure
//@formatter:on
{
	/** The frame. */
	public int[] frame;

	/** The background. */
	public float[] background;

	/** The intensity. */
	public float[] intensity;

	/** The x. */
	public float[] x;

	/** The y. */
	public float[] y;

	/** The z. */
	public float[] z;

	/** The peak results. */
	public PeakResult[] peakResults;

	/**
	 * Instantiates a new standard result procedure.
	 *
	 * @param results
	 *            the results
	 * @param distanceUnit
	 *            the distance unit
	 * @param intensityUnit
	 *            the intensity unit
	 */
	public StandardResultProcedure(MemoryPeakResults results, DistanceUnit distanceUnit, IntensityUnit intensityUnit)
	{
		super(results, distanceUnit, intensityUnit);
	}

	/**
	 * Instantiates a new standard result procedure.
	 *
	 * @param results
	 *            the results
	 * @param distanceUnit
	 *            the distance unit
	 * @param intensityUnit
	 *            the intensity unit
	 */
	public StandardResultProcedure(MemoryPeakResults results, IntensityUnit intensityUnit, DistanceUnit distanceUnit)
	{
		super(results, distanceUnit, intensityUnit);
	}

	/**
	 * Instantiates a new standard result procedure.
	 *
	 * @param results
	 *            the results
	 * @param distanceUnit
	 *            the distance unit
	 */
	public StandardResultProcedure(MemoryPeakResults results, DistanceUnit distanceUnit)
	{
		super(results, distanceUnit);
	}

	/**
	 * Instantiates a new standard result procedure.
	 *
	 * @param results
	 *            the results
	 * @param intensityUnit
	 *            the intensity unit
	 */
	public StandardResultProcedure(MemoryPeakResults results, IntensityUnit intensityUnit)
	{
		super(results, intensityUnit);
	}

	/**
	 * Instantiates a new standard result procedure.
	 *
	 * @param results
	 *            the results
	 */
	public StandardResultProcedure(MemoryPeakResults results)
	{
		super(results);
	}

	/**
	 * Gets the BIXY data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getBIXY()
	{
		i = 0;
		allocateB();
		allocateI();
		allocateX();
		allocateY();
		results.forEach(getIntensityUnit(), getDistanceUnit(), (BIXYResultProcedure) this);
	}

	public void executeBIXY(float background, float intensity, float x, float y)
	{
		this.background[i] = background;
		this.intensity[i] = intensity;
		this.x[i] = x;
		this.y[i] = y;
		i++;
	}

	/**
	 * Gets the BIXYZ data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getBIXYZ()
	{
		i = 0;
		allocateB();
		allocateI();
		allocateX();
		allocateY();
		allocateZ();
		results.forEach(getIntensityUnit(), getDistanceUnit(), (BIXYZResultProcedure) this);
	}

	public void executeBIXYZ(float background, float intensity, float x, float y, float z)
	{
		this.background[i] = background;
		this.intensity[i] = intensity;
		this.x[i] = x;
		this.y[i] = y;
		this.z[i] = z;
		i++;
	}

	/**
	 * Gets the I data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getI()
	{
		i = 0;
		allocateI();
		results.forEach(getIntensityUnit(), (IResultProcedure) this);
	}

	public void executeI(float intensity)
	{
		this.intensity[i] = intensity;
		i++;
	}

	/**
	 * Gets the IXY data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getIXY()
	{
		i = 0;
		allocateI();
		allocateX();
		allocateY();
		results.forEach(getIntensityUnit(), getDistanceUnit(), (IXYResultProcedure) this);
	}

	public void executeIXY(float intensity, float x, float y)
	{
		this.intensity[i] = intensity;
		this.x[i] = x;
		this.y[i] = y;
		i++;
	}

	/**
	 * Gets the IXYZ data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getIXYZ()
	{
		i = 0;
		allocateI();
		allocateX();
		allocateY();
		allocateZ();
		results.forEach(getIntensityUnit(), getDistanceUnit(), (IXYZResultProcedure) this);
	}

	public void executeIXYZ(float intensity, float x, float y, float z)
	{
		this.intensity[i] = intensity;
		this.x[i] = x;
		this.y[i] = y;
		this.z[i] = z;
		i++;
	}

	/**
	 * Gets the TXY data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getTXY()
	{
		i = 0;
		this.frame = allocate(this.frame);
		allocateX();
		allocateY();
		results.forEach(getDistanceUnit(), (TXYResultProcedure) this);
	}

	public void executeTXY(int frame, float x, float y)
	{
		this.frame[i] = frame;
		this.x[i] = x;
		this.y[i] = y;
		i++;
	}

	/**
	 * Gets the XY data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getXY()
	{
		i = 0;
		allocateX();
		allocateY();
		results.forEach(getDistanceUnit(), (XYResultProcedure) this);
	}

	public void executeXY(float x, float y)
	{
		this.x[i] = x;
		this.y[i] = y;
		i++;
	}

	/**
	 * Gets the XYR data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getXYR()
	{
		i = 0;
		allocateX();
		allocateY();
		allocateR();
		results.forEach(getDistanceUnit(), (XYRResultProcedure) this);
	}

	public void executeXYR(float x, float y, PeakResult result)
	{
		this.x[i] = x;
		this.y[i] = y;
		peakResults[i] = result;
		i++;
	}

	/**
	 * Gets the XYZ data in the configured units.
	 * 
	 * @throws ConversionException
	 *             if conversion to the required units is not possible
	 */
	public void getXYZ()
	{
		i = 0;
		allocateX();
		allocateY();
		allocateZ();
		results.forEach(getDistanceUnit(), (XYZResultProcedure) this);
	}

	public void executeXYZ(float x, float y, float z)
	{
		this.x[i] = x;
		this.y[i] = y;
		this.z[i] = z;
		i++;
	}

	private void allocateB()
	{
		this.background = allocate(this.background);
	}

	private void allocateI()
	{
		this.intensity = allocate(this.intensity);
	}

	private void allocateX()
	{
		this.x = allocate(this.x);
	}

	private void allocateY()
	{
		this.y = allocate(this.y);
	}

	private void allocateZ()
	{
		this.z = allocate(this.z);
	}

	private void allocateR()
	{
		this.peakResults = allocate(this.peakResults);
	}
}