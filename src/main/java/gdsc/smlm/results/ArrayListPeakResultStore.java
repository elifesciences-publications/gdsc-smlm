package gdsc.smlm.results;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;

import org.apache.commons.math3.random.RandomAdaptor;
import org.apache.commons.math3.random.RandomGenerator;

import gdsc.core.utils.TurboList;
import gdsc.core.utils.TurboList.SimplePredicate;
import gdsc.smlm.results.predicates.PeakResultPredicate;
import gdsc.smlm.results.procedures.PeakResultProcedure;

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
	 * @param store
	 *            the store to copy
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
	 * @see gdsc.smlm.results.PeakResultStore#copy(boolean)
	 */
	public PeakResultStore copy(boolean deepCopy)
	{
		if (deepCopy)
		{
			ArrayListPeakResultStore copy = new ArrayListPeakResultStore(size());
			for (int i = 0, size = size(); i < size; i++)
				copy.add(results.get(i).clone());
			return copy;
		}
		return copy();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#removeIf(gdsc.smlm.results.PeakResultPredicate)
	 */
	public boolean removeIf(final PeakResultPredicate filter)
	{
		// Util we upgrade the Java version to 1.8 the ArrayList does not support 
		// predicates so use a TurboList
		TurboList<PeakResult> temp = new TurboList<PeakResult>(this.results);
		if (temp.removeIf(new SimplePredicate<PeakResult>()
		{
			public boolean test(PeakResult t)
			{
				return filter.test(t);
			}
		}))
		{
			this.results = new ArrayList<PeakResult>(temp);
			return true;
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#forEach(gdsc.smlm.results.procedures.PeakResultProcedure)
	 */
	public void forEach(PeakResultProcedure procedure)
	{
		for (int i = 0, size = size(); i < size; i++)
			procedure.execute(results.get(i));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#subset(gdsc.smlm.results.procedures.PeakResultPredicate)
	 */
	public PeakResult[] subset(PeakResultPredicate filter)
	{
		final ArrayPeakResultStore list = new ArrayPeakResultStore(10);
		for (int i = 0, size = size(); i < size; i++)
			if (filter.test(results.get(i)))
				list.add(results.get(i));
		return list.toArray();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#shuffle(org.apache.commons.math3.random.RandomGenerator)
	 */
	public void shuffle(RandomGenerator randomGenerator)
	{
		Collections.shuffle(results, RandomAdaptor.createAdaptor(randomGenerator));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#indexOf(gdsc.smlm.results.PeakResult)
	 */
	public int indexOf(PeakResult result)
	{
		return results.indexOf(result);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.results.PeakResultStore#lastIndexOf(gdsc.smlm.results.PeakResult)
	 */
	public int lastIndexOf(PeakResult result)
	{
		return results.lastIndexOf(result);
	}
}
