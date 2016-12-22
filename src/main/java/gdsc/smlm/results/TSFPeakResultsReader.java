package gdsc.smlm.results;

import java.awt.Rectangle;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2016 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import gdsc.smlm.function.gaussian.Gaussian2DFunction;
import gdsc.smlm.tsf.TaggedSpotFile.FitMode;
import gdsc.smlm.tsf.TaggedSpotFile.IntensityUnits;
import gdsc.smlm.tsf.TaggedSpotFile.LocationUnits;
import gdsc.smlm.tsf.TaggedSpotFile.Spot;
import gdsc.smlm.tsf.TaggedSpotFile.SpotList;

/**
 * Reads the fit results from file using the Tagged Spot File (TSF) format.
 * <p>
 * Has only limited support for TSF in that only 1 channel, position, slice and fluorophore type can be read into a
 * dataset.
 * 
 * @author Alex Herbert
 */
public class TSFPeakResultsReader
{
	private String filename = null;
	private SpotList spotList = null;
	private boolean readHeader = false;
	private boolean isGDSC = false;

	private int channel = 1;
	private int slice = 1;
	private int position = 1;
	private int fluorophoreType = 1;

	public TSFPeakResultsReader(String filename)
	{
		this.filename = filename;
	}

	public SpotList readHeader()
	{
		if (readHeader)
			return spotList;
		readHeader = true;

		FileInputStream fi = null;
		try
		{
			fi = new FileInputStream(filename);
			DataInputStream di = new DataInputStream(fi);
			// the file has an initial 0, then the offset (as long)
			// to the position of spotList
			int magic = di.readInt();
			if (magic != 0)
			{
				throw new RuntimeException("Magic number is not 0 (required for a TSF file)");
			}
			if (fi.available() == 0)
			{
				throw new RuntimeException("Cannot read offset");
			}
			long offset = di.readLong();
			if (offset == 0)
			{
				throw new RuntimeException("Offset is 0, cannot find header data in this file");
			}
			fi.skip(offset);
			spotList = SpotList.parseDelimitedFrom(fi);
		}
		catch (Exception e)
		{
			System.err.println("Failed to read SpotList message");
			e.printStackTrace();
		}
		finally
		{
			if (fi != null)
			{
				try
				{
					fi.close();
				}
				catch (IOException e)
				{
				}
			}
		}

		// We can do special processing for a TSF file we created
		isGDSC = (spotList.getApplicationId() == TSFPeakResultsWriter.APPLICATION_ID);

		return spotList;
	}

	/**
	 * Checks if is a binary TSF file by attempting to read the SpotList header.
	 *
	 * @param filename
	 *            the filename
	 * @return true, if is a TSF file
	 */
	public static boolean isTSF(String filename)
	{
		FileInputStream fi = null;
		try
		{
			fi = new FileInputStream(filename);
			DataInputStream di = new DataInputStream(fi);
			// the file has an initial 0, then the offset (as long)
			// to the position of spotList
			int magic = di.readInt();
			if (magic != 0)
			{
				// Magic number should be zero
				return false;
			}
			if (fi.available() == 0)
			{
				// No more contents
				return false;
			}
			long offset = di.readLong();
			if (offset == 0)
			{
				// No offset record
				return false;
			}
			fi.skip(offset);
			SpotList spotList = SpotList.parseDelimitedFrom(fi);
			if (spotList != null)
				return true;
		}
		catch (Exception e)
		{
			// Fail
		}
		finally
		{
			if (fi != null)
			{
				try
				{
					fi.close();
				}
				catch (IOException e)
				{
				}
			}
		}

		return false;
	}

	/**
	 * Read the results from the TSF file into memory
	 * 
	 * @return The results set (or null if an error occurred)
	 */
	public MemoryPeakResults read()
	{
		readHeader();
		if (spotList == null)
			return null;

		MemoryPeakResults results = createResults();

		// Read the messages that contain the spot data
		FileInputStream fi = null;
		try
		{
			fi = new FileInputStream(filename);
			fi.skip(12); // size of int + size of long
		}
		catch (Exception e)
		{
			System.err.println("Failed to read open TSF file: " + filename);
			e.printStackTrace();
			if (fi != null)
			{
				try
				{
					fi.close();
				}
				catch (IOException ex)
				{
				}
			}
			return null;
		}

		LocationUnits locationUnits = spotList.getLocationUnits();
		boolean locationUnitsWarning = false;

		float locationConversion = 1;
		switch (locationUnits)
		{
			case PIXELS:
				break;
			case NM:
				locationConversion = 1 / getNmPerPixel(results.getCalibration());
				break;
			case UM:
				float nmPerPixel = getNmPerPixel(results.getCalibration());
				if (nmPerPixel != 1)
				{
					float umPerPixel = nmPerPixel / 1000;
					locationConversion = 1 / umPerPixel;
				}
				break;
			default:
				System.err.println("Unsupported location units conversion: " + locationUnits);
		}

		IntensityUnits intensityUnits = spotList.getIntensityUnits();
		boolean intensityUnitsWarning = false;

		float intensityConversion = 1;
		switch (intensityUnits)
		{
			case COUNTS:
				break;
			case PHOTONS:
				intensityConversion = getGain(results.getCalibration());
				break;
			default:
				System.err.println("Unsupported intensity units conversion: " + intensityUnits);
		}

		FitMode fitMode = FitMode.ONEAXIS;
		if (spotList.hasFitMode())
			fitMode = spotList.getFitMode();

		long expectedSpots = getExpectedSpots();
		try
		{
			long totalSpots = 0;
			while (fi.available() > 0 && (totalSpots < expectedSpots || expectedSpots == 0))
			{
				totalSpots++;

				Spot spot = Spot.parseDelimitedFrom(fi);

				// Only read the specified channel, position, slice and fluorophore type
				if (spot.getChannel() != channel)
					continue;
				if (spot.hasPos() && spot.getPos() != position)
					continue;
				if (spot.hasSlice() && spot.getSlice() != slice)
					continue;
				if (spot.hasFluorophoreType() && spot.getFluorophoreType() != fluorophoreType)
					continue;

				if (spot.hasLocationUnits() && !locationUnitsWarning && spot.getLocationUnits() != locationUnits)
				{
					System.err.println("Spot message has different location units, these will be ignored: " +
							spot.getLocationUnits());
					locationUnitsWarning = true;
				}
				if (spot.hasIntensityUnits() && !intensityUnitsWarning && spot.getIntensityUnits() != intensityUnits)
				{
					System.err.println("Spot message has different intensity units, these will be ignored: " +
							spot.getIntensityUnits());
					intensityUnitsWarning = true;
				}

				// Required fields
				int frame = spot.getFrame();

				float[] params = new float[7];
				params[Gaussian2DFunction.X_POSITION] = spot.getX() * locationConversion;
				params[Gaussian2DFunction.Y_POSITION] = spot.getY() * locationConversion;
				params[Gaussian2DFunction.SIGNAL] = spot.getIntensity() * intensityConversion;
				if (spot.hasBackground())
					// Q. What is there is a bias? We hope that the writer of the TSF file has removed the bias.
					params[Gaussian2DFunction.BACKGROUND] = spot.getBackground() * intensityConversion;

				// Support different Gaussian shapes
				if (fitMode == FitMode.ONEAXIS)
				{
					params[Gaussian2DFunction.X_SD] = params[Gaussian2DFunction.Y_SD] = spot.getWidth() /
							TSFPeakResultsWriter.SD_TO_FWHM_FACTOR;
				}
				else
				{
					if (!spot.hasA())
					{
						params[Gaussian2DFunction.X_SD] = params[Gaussian2DFunction.Y_SD] = spot.getWidth() /
								TSFPeakResultsWriter.SD_TO_FWHM_FACTOR;
					}
					else
					{
						double a = Math.sqrt(spot.getA());
						double sd = spot.getWidth() / TSFPeakResultsWriter.SD_TO_FWHM_FACTOR;
						params[Gaussian2DFunction.X_SD] = (float) (sd * a);
						params[Gaussian2DFunction.Y_SD] = (float) (sd / a);
					}

					if (fitMode == FitMode.TWOAXISANDTHETA && spot.hasTheta())
						// XXX - what are the units for theta? GDSC SMLM uses Radians.
						params[Gaussian2DFunction.ANGLE] = spot.getTheta();
				}

				// We can use the original position in pixels used for fitting
				int origX = (spot.hasXPosition()) ? spot.getXPosition() : 0;
				int origY = (spot.hasYPosition()) ? spot.getYPosition() : 0;

				// Q. Should we use the required field 'molecule'?

				float origValue = 0;
				double error = 0;
				float noise = 0;
				float[] paramsStdDev = null;
				if (isGDSC)
				{
					// Get the error, noise, origValue, paramsStdDev
				}

				PeakResult peakResult;
				if (spot.hasCluster())
				{
					peakResult = new IdPeakResult(frame, origX, origY, origValue, error, noise, params, paramsStdDev,
							spot.getCluster());
				}
				else
				{
					peakResult = new PeakResult(frame, origX, origY, origValue, error, noise, params, paramsStdDev);
				}
				results.add(peakResult);
			}
		}
		catch (IOException e)
		{
			System.err.println("Failed to read Spot message");
			e.printStackTrace();

			// This may just be an error because we ran out of spots to read. 
			// Only fail if there is a number of expected spots. 
			if (expectedSpots != 0)
			{
				System.err.println("Unexpected error in reading Spot messages, no results will be returned");
				return null;
			}
		}
		finally
		{
			if (fi != null)
			{
				try
				{
					fi.close();
				}
				catch (IOException e)
				{
				}
			}
		}

		return results;
	}

	private long getExpectedSpots()
	{
		if (spotList.hasNrSpots())
			return spotList.getNrSpots();
		return 0;
	}

	private MemoryPeakResults createResults()
	{
		// Limit the capacity since we may not need all the spots
		int capacity = 1000;
		if (spotList.hasNrSpots())
			capacity = (int) Math.min(100000, spotList.getNrSpots());
		MemoryPeakResults results = new MemoryPeakResults(capacity);

		// Generic reconstruction
		String name;
		if (spotList.hasName())
		{
			name = spotList.getName();
		}
		else
		{
			name = new File(filename).getName();
		}
		// Append these if not using the defaults
		if (channel != 1 || slice != 1 || position != 1 || fluorophoreType != 1)
		{
			name = String.format("%s c=%d, s=%d, p=%d, ft=%d", name, channel, slice, position, fluorophoreType);
		}
		results.setName(name);

		//		if (spotList.hasNrPixelsX() && spotList.hasNrPixelsY())
		//		{
		//			// Do not do this. The size of the camera may not map to the data bounds due
		//			// to the support for position offsets.
		//			results.setBounds(new Rectangle(0, 0, spotList.getNrPixelsX(), spotList.getNrPixelsY()));
		//		}

		if (spotList.hasPixelSize())
		{
			Calibration cal = new Calibration(spotList.getPixelSize(), 1, 0);
			results.setCalibration(cal);
		}

		if (isGDSC)
		{
			// TODO: Special processing for results we created to allow
			// perfect dataset reconstruction

			// Only trust this if we created it using the source property
			if (spotList.hasNrFrames())
				results.setBounds(new Rectangle(0, 0, spotList.getNrPixelsX(), spotList.getNrPixelsY()));
		}

		Calibration cal = results.getCalibration();

		if (spotList.getLocationUnits() != LocationUnits.PIXELS)
		{
			if (getNmPerPixel(cal) == 1f)
				System.err.println(
						"TSF location units are not pixels and no calibration is available. The dataset will be constructed in the native units: " +
								spotList.getLocationUnits());
		}
		if (spotList.getIntensityUnits() != IntensityUnits.COUNTS)
		{
			if (getGain(cal) == 1f)
				System.err.println(
						"TSF intensity units are not counts and no calibration is available. The dataset will be constructed in the native units: " +
								spotList.getIntensityUnits());
		}

		return results;
	}

	private float getNmPerPixel(Calibration cal)
	{
		if (cal == null || cal.nmPerPixel <= 0)
			return 1f;
		return (float) cal.nmPerPixel;
	}

	private float getGain(Calibration cal)
	{
		if (cal == null || cal.gain <= 0)
			return 1f;
		return (float) cal.gain;
	}

	/**
	 * Gets the channel to read.
	 *
	 * @return the channel
	 */
	public int getChannel()
	{
		return channel;
	}

	/**
	 * Sets the channel to read.
	 *
	 * @param channel
	 *            the new channel
	 */
	public void setChannel(int channel)
	{
		this.channel = channel;
	}

	/**
	 * Gets the slice to read.
	 *
	 * @return the slice
	 */
	public int getSlice()
	{
		return slice;
	}

	/**
	 * Sets the slice to read.
	 *
	 * @param slice
	 *            the new slice
	 */
	public void setSlice(int slice)
	{
		this.slice = slice;
	}

	/**
	 * Gets the position to read.
	 *
	 * @return the position
	 */
	public int getPosition()
	{
		return position;
	}

	/**
	 * Sets the position to read.
	 *
	 * @param position
	 *            the new position
	 */
	public void setPosition(int position)
	{
		this.position = position;
	}

	/**
	 * Gets the fluorophore type to read.
	 *
	 * @return the fluorophore type
	 */
	public int getFluorophoreType()
	{
		return fluorophoreType;
	}

	/**
	 * Sets the fluorophore type to read.
	 *
	 * @param fluorophoreType
	 *            the new fluorophore type
	 */
	public void setFluorophoreType(int fluorophoreType)
	{
		this.fluorophoreType = fluorophoreType;
	}
}