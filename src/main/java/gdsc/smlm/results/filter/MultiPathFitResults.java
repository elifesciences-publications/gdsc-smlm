package gdsc.smlm.results.filter;

// TODO: Auto-generated Javadoc
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

/**
 * Specifies a the result of fitting a frame using different fitting methods.
 * <p>
 * The multi-path results can be evaluated by the MultiPathFilter to determine which result from the different paths
 * should be accepted.
 * <p>
 * This class is used for benchmarking the fitting path options in the PeakFit algorithm.
 */
public class MultiPathFitResults implements IMultiPathFitResults, Cloneable
{
	/** The frame containing the results. */
	final public int frame;

	/** The multi-path results. */
	final public MultiPathFitResult[] multiPathFitResults;

	/**
	 * The total number of candidates. This may be greater than the size of the {@link #multiPathFitResults} array if
	 * this is a subset of the results, i.e. has been prefiltered.
	 */
	final public int totalCandidates;

	/**
	 * Instantiates a new multi path fit results.
	 *
	 * @param frame
	 *            the frame
	 * @param multiPathFitResults
	 *            the multi path fit results
	 */
	public MultiPathFitResults(int frame, MultiPathFitResult[] multiPathFitResults)
	{
		this(frame, multiPathFitResults, (multiPathFitResults == null) ? 0 : multiPathFitResults.length);
	}

	/**
	 * Instantiates a new multi path fit results.
	 *
	 * @param frame
	 *            the frame
	 * @param multiPathFitResults
	 *            the multi path fit results
	 * @param totalCandidates
	 *            the total candidates
	 */
	public MultiPathFitResults(int frame, MultiPathFitResult[] multiPathFitResults, int totalCandidates)
	{
		this.frame = frame;
		this.multiPathFitResults = multiPathFitResults;
		this.totalCandidates = totalCandidates;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.IMultiPathFitResults#getFrame()
	 */
	public int getFrame()
	{
		return frame;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.IMultiPathFitResults#getNumberOfResults()
	 */
	public int getNumberOfResults()
	{
		return multiPathFitResults.length;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.IMultiPathFitResults#getResult(int)
	 */
	public MultiPathFitResult getResult(int index)
	{
		return multiPathFitResults[index];
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.filter.IMultiPathFitResults#getTotalCandidates()
	 */
	public int getTotalCandidates()
	{
		return totalCandidates;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#clone()
	 */
	@Override
	public MultiPathFitResults clone()
	{
		MultiPathFitResult[] list = new MultiPathFitResult[multiPathFitResults.length];
		for (int i = 0; i < list.length; i++)
			list[i] = multiPathFitResults[i].clone();
		return new MultiPathFitResults(frame, list, totalCandidates);
	}
}
