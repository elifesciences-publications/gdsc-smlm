package gdsc.smlm.results;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;

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
 * Stores peak results using an ArrayList.
 */
public class ArrayListPeakResultStore implements PeakResultStore
{
	/** The results. */
	private ArrayList<PeakResult> results;

	/**
	 * Instantiates a new array list peak results store.
	 *
	 * @param capacity
	 *            the capacity
	 */
	public ArrayListPeakResultStore(int capacity)
	{
		this.results = new ArrayList<PeakResult>(capacity);
	}

	/**
	 * Instantiates a new array list peak result store.
	 *
	 * @param store the store to copy
	 */
	public ArrayListPeakResultStore(ArrayListPeakResultStore store)
	{
		this.results = new ArrayList<PeakResult>(store.results);
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#get(int)
	 */
	public PeakResult get(int index)
	{
		return results.get(index);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#size()
	 */
	public int size()
	{
		return results.size();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#add(gdsc.smlm.results.PeakResult)
	 */
	public void add(PeakResult result)
	{
		results.add(result);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#addCollection(java.util.Collection)
	 */
	public void addCollection(Collection<PeakResult> results)
	{
		this.results.addAll(results);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#addArray(gdsc.smlm.results.PeakResult[])
	 */
	public void addArray(PeakResult[] results)
	{
		this.results.addAll(Arrays.asList(results));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#addStore(gdsc.smlm.results.PeakResultStore)
	 */
	public void addStore(PeakResultStore results)
	{
		if (results instanceof ArrayListPeakResultStore)
		{
			this.results.addAll(((ArrayListPeakResultStore) results).results);
		}
		else
		{
			addArray(results.toArray());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#clear()
	 */
	public void clear()
	{
		results.clear();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#trimToSize()
	 */
	public void trimToSize()
	{
		results.trimToSize();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#sort()
	 */
	public void sort()
	{
		Collections.sort(results);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#sort(java.util.Comparator)
	 */
	public void sort(Comparator<PeakResult> comparator)
	{
		Collections.sort(results, comparator);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#toArray()
	 */
	public PeakResult[] toArray()
	{
		return results.toArray(new PeakResult[size()]);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#copy()
	 */
	public PeakResultStore copy()
	{
		return new ArrayListPeakResultStore(this);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#removeNullResults()
	 */
	public void removeNullResults()
	{
		PeakResult[] list = toArray();
		int newSize = 0;
		for (int i = 0, size = list.length; i < size; i++)
		{
			if (list[i] != null)
				list[newSize++] = list[i];
		}
		if (newSize < list.length)
		{
			if (newSize == 0)
				clear();
			else
				this.results = new ArrayList<PeakResult>(Arrays.asList(Arrays.copyOf(list, newSize)));
		}
	}
}