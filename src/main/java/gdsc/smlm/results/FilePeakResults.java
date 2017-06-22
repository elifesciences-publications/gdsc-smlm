package gdsc.smlm.results;

import java.io.FileOutputStream;
import java.io.IOException;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Printer;

import gdsc.core.utils.NotImplementedException;

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
 * Saves the fit results to file
 */
public abstract class FilePeakResults extends AbstractPeakResults implements ThreadSafePeakResults
{
	// Only write to a single results file
	protected FileOutputStream fos = null;

	protected String filename;
	private boolean sortAfterEnd = false;

	protected int size = 0;

	public FilePeakResults(String filename)
	{
		this.filename = filename;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.PeakResults#begin()
	 */
	public void begin()
	{
		fos = null;
		size = 0;
		try
		{
			fos = new FileOutputStream(filename);
			openOutput();
			write(createResultsHeader());
		}
		catch (Exception e)
		{
			// TODO - Add better handling of errors
			e.printStackTrace();
			closeOutput();
		}
	}

	/**
	 * Open the required output from the open file output stream
	 */
	protected abstract void openOutput();

	/**
	 * Write the data to the output.
	 *
	 * @param data
	 *            the data
	 */
	protected abstract void write(String data);

	/**
	 * Write the result and increment the size by the count.
	 * <p>
	 * This method is synchronised to ensure that the change to the size or the output file are thread safe.
	 *
	 * @param count
	 *            the count
	 * @param result
	 *            the result
	 */
	protected synchronized void writeResult(int count, String result)
	{
		// In case another thread caused the output to close
		if (fos == null)
			return;
		size += count;
		write(result);
	}

	protected String createResultsHeader()
	{
		StringBuilder sb = new StringBuilder();

		addComment(sb, getHeaderTitle());
		sb.append(String.format("#FileVersion %s\n", getVersion()));

		Printer printer = null;

		// Add the standard details
		if (name != null)
			sb.append(String.format("#Name %s\n", singleLine(name)));
		if (source != null)
			sb.append(String.format("#Source %s\n", singleLine(source.toXML())));
		if (bounds != null)
			sb.append(String.format("#Bounds x%d y%d w%d h%d\n", bounds.x, bounds.y, bounds.width, bounds.height));
		if (calibration != null)
			printer = addMessage(sb, printer, "Calibration", calibration.getCalibrationOrBuilder());
		if (configuration != null && configuration.length() > 0)
			sb.append(String.format("#Configuration %s\n", singleLine(configuration)));
		if (psf != null)
			printer = addMessage(sb, printer, "PSF", psf);

		// Add any extra comments
		String[] comments = getHeaderComments();
		if (comments != null)
		{
			for (String comment : comments)
				addComment(sb, comment);
		}

		// Output the field names
		String[] fields = getFieldNames();
		if (fields != null)
		{
			sb.append('#');
			for (int i = 0; i < fields.length; i++)
			{
				if (i != 0)
					sb.append('\t');
				sb.append(fields[i]);
			}
			sb.append('\n');
		}

		addComment(sb, getHeaderEnd());

		return sb.toString();
	}

	private Printer addMessage(StringBuilder sb, Printer printer, String name, MessageOrBuilder msg)
	{
		try
		{
			if (printer == null)
				printer = JsonFormat.printer().omittingInsignificantWhitespace().includingDefaultValueFields();
			sb.append(String.format("#%s %s\n", name, printer.print(msg)));
			System.out.println(printer.print(msg));
		}
		catch (InvalidProtocolBufferException e)
		{
			// This shouldn't happen so throw it
			throw new NotImplementedException("Unable to serialise the " + name + " settings", e);
		}
		return printer;
	}

	private void addComment(StringBuilder sb, String comment)
	{
		if (comment != null)
			sb.append("#").append(comment).append('\n');
	}

	/**
	 * @return The first line added to the header
	 */
	protected String getHeaderTitle()
	{
		return "Localisation Results File";
	}

	/**
	 * @return The last line added to the header (e.g. a header end tag)
	 */
	protected String getHeaderEnd()
	{
		return null;
	}

	/**
	 * @return A line containing the file format version
	 */
	protected abstract String getVersion();

	/**
	 * @return Any comment lines to add to the header after the standard output of source, name, bounds, etc.
	 */
	protected String[] getHeaderComments()
	{
		return null;
	}

	/**
	 * @return The names of the fields in each record. Will be the last comment of the header
	 */
	protected abstract String[] getFieldNames();

	protected String singleLine(String text)
	{
		return text.replaceAll("\n *", "");
	}

	protected void closeOutput()
	{
		if (fos == null)
			return;

		try
		{
			fos.close();
		}
		catch (Exception e)
		{
			// Ignore exception
		}
		finally
		{
			fos = null;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.PeakResults#size()
	 */
	public int size()
	{
		return size;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.PeakResults#end()
	 */
	public void end()
	{
		if (fos == null)
			return;

		// Close the file.
		try
		{
			closeOutput();

			if (!isSortAfterEnd())
				return;

			sort();
		}
		catch (IOException e)
		{
			// ignore
		}
		finally
		{
			fos = null;
		}
	}

	/**
	 * Sort the data file records. This is called once the file has been closed for input.
	 *
	 * @throws IOException
	 *             if an IO error occurs
	 */
	protected abstract void sort() throws IOException;

	/**
	 * @param sortAfterEnd
	 *            True if the results should be sorted after the {@link #end()} method
	 */
	public void setSortAfterEnd(boolean sortAfterEnd)
	{
		this.sortAfterEnd = sortAfterEnd;
	}

	/**
	 * @return True if the results should be sorted after the {@link #end()} method
	 */
	public boolean isSortAfterEnd()
	{
		return sortAfterEnd;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.results.PeakResults#isActive()
	 */
	public boolean isActive()
	{
		return fos != null;
	}

	/**
	 * @return true if the records are stored as binary data
	 */
	public boolean isBinary()
	{
		return false;
	}
}
