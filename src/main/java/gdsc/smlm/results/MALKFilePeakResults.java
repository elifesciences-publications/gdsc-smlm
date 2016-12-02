package gdsc.smlm.results;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.InputMismatchException;
import java.util.NoSuchElementException;
import java.util.Scanner;

import gdsc.core.ij.Utils;
import gdsc.core.utils.Maths;

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

/**
 * Saves the fit results to file using the simple MALK file format (Molecular Accuracy Localisation Keep). This consists
 * of [X,Y,T,Signal] data in a white-space separated format. Comments are allowed with the # character.
 */
public class MALKFilePeakResults extends FilePeakResults
{
	private float nmPerPixel;

	public MALKFilePeakResults(String filename)
	{
		super(filename);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.FilePeakResults#getHeaderEnd()
	 */
	protected String getHeaderEnd()
	{
		return null;
	}

	@Override
	protected String getVersion()
	{
		return "MALK";
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.FilePeakResults#getHeaderComments()
	 */
	protected String[] getHeaderComments()
	{
		String[] comments = new String[3];
		int count = 0;
		nmPerPixel = 1;
		if (calibration != null)
		{
			if (Maths.isFinite(calibration.nmPerPixel) && calibration.nmPerPixel > 0)
			{
				nmPerPixel = (float) calibration.nmPerPixel;
				comments[count++] = String.format("Pixel pitch %s (nm)", Utils.rounded(calibration.nmPerPixel));
			}
			if (Maths.isFinite(calibration.gain) && calibration.gain > 0)
			{
				comments[count++] = String.format("Gain %s (Count/photon)", Utils.rounded(calibration.gain));
			}
			if (Maths.isFinite(calibration.exposureTime) && calibration.exposureTime > 0)
			{
				comments[count++] = String.format("Exposure time %s (seconds)", Utils.rounded(calibration.exposureTime*1e-3));
			}
		}
		return Arrays.copyOf(comments, count);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.FilePeakResults#getFieldNames()
	 */
	protected String[] getFieldNames()
	{
		return new String[] { "X", "Y", peakIdColumnName, "Signal" };
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.results.PeakResults#add(int, int, int, float, double, float, float[], float[])
	 */
	public void add(int peak, int origX, int origY, float origValue, double chiSquared, float noise, float[] params,
			float[] paramsStdDev)
	{
		if (out == null)
			return;

		StringBuilder sb = new StringBuilder(100);

		addStandardData(sb, params[Gaussian2DFunction.X_POSITION], params[Gaussian2DFunction.Y_POSITION], peak,
				params[Gaussian2DFunction.SIGNAL]);

		writeResult(1, sb.toString());
	}

	private void addStandardData(StringBuilder sb, final float x, final float y, final int frame, final float signal)
	{
		sb.append(x * nmPerPixel);
		sb.append('\t');
		sb.append(y * nmPerPixel);
		sb.append('\t');
		sb.append(frame);
		sb.append('\t');
		sb.append(signal);
		sb.append('\n');
	}

	public void addAll(Collection<PeakResult> results)
	{
		if (out == null)
			return;

		int count = 0;

		StringBuilder sb = new StringBuilder(2000);
		for (PeakResult result : results)
		{
			// Add the standard data
			addStandardData(sb, result.getXPosition(), result.getYPosition(), result.peak, result.getSignal());

			// Flush the output to allow for very large input lists
			if (++count >= 20)
			{
				writeResult(count, sb.toString());
				if (!isActive())
					return;
				sb.setLength(0);
				count = 0;
			}
		}
		writeResult(count, sb.toString());
	}

	/**
	 * Output a cluster to the results file.
	 * <p>
	 * Note: This is not synchronised
	 * 
	 * @param cluster
	 */
	public void addCluster(Cluster cluster)
	{
		if (out == null)
			return;
		if (cluster.size() > 0)
		{
			float[] centroid = cluster.getCentroid();
			writeResult(0, String.format("#Cluster %f %f (+/-%f) n=%d\n", centroid[0], centroid[1],
					cluster.getStandardDeviation(), cluster.size()));
			addAll(cluster);
		}
	}

	private void addAll(Cluster cluster)
	{
		addAll(cluster.getPoints());
	}

	/**
	 * Output a trace to the results file.
	 * <p>
	 * Note: This is not synchronised
	 * 
	 * @param trace
	 */
	public void addTrace(Trace trace)
	{
		if (out == null)
			return;
		if (trace.size() > 0)
		{
			float[] centroid = trace.getCentroid();
			writeResult(0,
					String.format("#Trace %f %f (+/-%f) n=%d, b=%d, on=%f, off=%f, signal= %f\n", centroid[0],
							centroid[1], trace.getStandardDeviation(), trace.size(), trace.getNBlinks(),
							trace.getOnTime(), trace.getOffTime(), trace.getSignal()));
			addAll(trace);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.PeakResults#end()
	 */
	public void end()
	{
		if (out == null)
			return;

		// Close the file.
		try
		{
			closeOutput();

			if (!isSortAfterEnd())
				return;

			ArrayList<Result> results = new ArrayList<Result>(size);

			StringBuffer header = new StringBuffer();
			BufferedReader input = new BufferedReader(new FileReader(filename));
			try
			{
				String line;
				// Skip the header
				while ((line = input.readLine()) != null)
				{
					if (line.charAt(0) != '#')
					{
						// This is the first record
						results.add(new Result(line));
						break;
					}
					else
						header.append(line).append("\n");
				}

				while ((line = input.readLine()) != null)
				{
					results.add(new Result(line));
				}
			}
			finally
			{
				input.close();
			}

			Collections.sort(results);

			BufferedWriter output = new BufferedWriter(new FileWriter(filename));
			try
			{
				output.write(header.toString());
				for (Result result : results)
				{
					output.write(result.line);
					output.write("\n");
				}
			}
			finally
			{
				output.close();
			}
		}
		catch (IOException e)
		{
			// ignore
		}
		finally
		{
			out = null;
		}
	}

	private class Result implements Comparable<Result>
	{
		String line;
		int slice = 0;

		public Result(String line)
		{
			this.line = line;
			extractSlice();
		}

		private void extractSlice()
		{
			Scanner scanner = new Scanner(line);
			scanner.useDelimiter("\t");

			try
			{
				scanner.nextFloat(); // X
				scanner.nextFloat(); // Y
				slice = scanner.nextInt();
				scanner.close();
			}
			catch (InputMismatchException e)
			{
			}
			catch (NoSuchElementException e)
			{
			}
		}

		public int compareTo(Result o)
		{
			// Sort by slice number
			// (Note: peak height is already done in the run(...) method)
			return slice - o.slice;
		}
	}
}
