/*-
 * #%L
 * Genome Damage and Stability Centre SMLM ImageJ Plugins
 *
 * Software for single molecule localisation microscopy (SMLM)
 * %%
 * Copyright (C) 2011 - 2019 Alex Herbert
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package uk.ac.sussex.gdsc.smlm.results;

import uk.ac.sussex.gdsc.smlm.results.procedures.PeakResultProcedure;

import org.apache.commons.rng.UniformRandomProvider;

import java.io.Serializable;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Stores peak results using an array.
 */
public class ArrayPeakResultStore implements PeakResultStoreList, Serializable {
  private static final long serialVersionUID = 20190319L;

  /** The results. */
  private PeakResult[] results;

  /** The size. */
  private int size;

  /**
   * Instantiates a new array list peak results store.
   *
   * @param capacity the capacity
   */
  public ArrayPeakResultStore(int capacity) {
    this.results = new PeakResult[Math.max(capacity, 0)];
  }

  /**
   * Instantiates a new array peak result store.
   *
   * @param store the store to copy
   * @throws NullPointerException if the store is null
   */
  public ArrayPeakResultStore(ArrayPeakResultStore store) {
    this.results = store.toArray();
    this.size = store.size;
  }

  /**
   * Instantiates a new array peak result store.
   *
   * @param results the results
   * @throws NullPointerException if the results are null
   */
  public ArrayPeakResultStore(PeakResult[] results) {
    this.results = results;
    this.size = results.length;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Note: This does not check against the current size so can return stale data.
   */
  @Override
  public PeakResult get(int index) {
    return results[index];
  }

  @Override
  public int size() {
    return size;
  }

  /**
   * Ensure that the specified number of elements can be added to the array.
   *
   * <p>This is not synchronized. However any class using the safeAdd() methods in different threads
   * should be using the same synchronized method to add data thus this method will be within
   * synchronized code.
   *
   * @param length the length
   */
  private void checkCapacity(int length) {
    final int minCapacity = size + length;
    final int oldCapacity = results.length;
    if (minCapacity > oldCapacity) {
      int newCapacity = (oldCapacity * 3) / 2 + 1;
      if (newCapacity < minCapacity) {
        newCapacity = minCapacity;
      }
      final PeakResult[] newResults = new PeakResult[newCapacity];
      System.arraycopy(results, 0, newResults, 0, size);
      results = newResults;
    }
  }

  @Override
  public boolean add(PeakResult result) {
    checkCapacity(1);
    results[size++] = result;
    return true;
  }

  @Override
  public boolean addCollection(Collection<PeakResult> results) {
    return addArray(results.toArray(new PeakResult[results.size()]));
  }

  @Override
  public boolean addArray(PeakResult[] results) {
    if (results == null) {
      return false;
    }
    return addArray(results, results.length);
  }

  private boolean addArray(PeakResult[] results, int length) {
    if (results == null || length == 0) {
      return false;
    }
    checkCapacity(length);
    System.arraycopy(results, 0, this.results, size, length);
    size += length;
    return true;
  }

  @Override
  public boolean addStore(PeakResultStore results) {
    if (results instanceof ArrayPeakResultStore) {
      final ArrayPeakResultStore store = (ArrayPeakResultStore) results;
      return addArray(store.results, store.size);
    }
    return addArray(results.toArray());
  }

  @Override
  public PeakResult remove(int index) {
    rangeCheck(index);
    final PeakResult oldValue = results[index];
    fastRemove(index);
    return oldValue;
  }

  @Override
  public void remove(int fromIndex, int toIndex) {
    if (fromIndex > toIndex) {
      throw new IllegalArgumentException("fromIndex must be <= toIndex");
    }
    rangeCheckWithLowerBounds(fromIndex);
    rangeCheck(toIndex); // This is above fromIndex so ignore lower bounds check
    toIndex++; // Make exclusive
    final int numMoved = size - toIndex;
    if (numMoved > 0) {
      System.arraycopy(results, toIndex, results, fromIndex, numMoved);
    }
    // Let gc do its work
    while (fromIndex++ < toIndex) {
      results[size--] = null;
    }
  }

  @Override
  public boolean remove(PeakResult result) {
    final int index = indexOf(result);
    if (index != -1) {
      fastRemove(index);
      return true;
    }
    return false;
  }

  /**
   * Checks if the given index is in range. If not, throws an appropriate runtime exception. This
   * method does *not* check if the index is negative: It is always used immediately prior to an
   * array access, which throws an ArrayIndexOutOfBoundsException if index is negative.
   */
  private void rangeCheck(int index) {
    if (index >= size) {
      throw new IndexOutOfBoundsException(outOfBoundsMsg(index));
    }
  }

  /**
   * A version of rangeCheck with lower bounds check.
   */
  private void rangeCheckWithLowerBounds(int index) {
    if (index > size || index < 0) {
      throw new IndexOutOfBoundsException(outOfBoundsMsg(index));
    }
  }

  /**
   * Constructs an IndexOutOfBoundsException detail message.
   */
  private String outOfBoundsMsg(int index) {
    return "Index: " + index + ", Size: " + size;
  }

  /*
   * Private remove method that skips bounds checking and does not return the value removed.
   */
  private void fastRemove(int index) {
    final int numMoved = size - index - 1;
    if (numMoved > 0) {
      System.arraycopy(results, index + 1, results, index, numMoved);
    }
    results[--size] = null; // Let gc do its work
  }

  @Override
  public boolean removeCollection(Collection<PeakResult> results) {
    return removeArray(results.toArray(new PeakResult[results.size()]));
  }

  @Override
  public boolean removeArray(PeakResult[] results) {
    if (results == null || results.length == 0) {
      return false;
    }
    return batchRemove(results, false);
  }

  @Override
  public boolean removeStore(PeakResultStore results) {
    if (results instanceof ArrayPeakResultStore) {
      return removeArray(((ArrayPeakResultStore) results).results);
    }
    return removeArray(results.toArray());
  }

  private boolean batchRemove(PeakResult[] results2, boolean complement) {
    // Adapted from java.utils.ArrayList

    final ArrayPeakResultStore c = new ArrayPeakResultStore(results2);
    int index = 0;
    int newSize = 0;
    boolean modified = false;
    try {
      for (; index < size; index++) {
        if (c.contains(results[index]) == complement) {
          results[newSize++] = results[index];
        }
      }
    } finally {
      // Preserve data even if c.contains() throws.
      if (index != size) {
        System.arraycopy(results, index, results, newSize, size - index);
        newSize += size - index;
      }
      if (newSize != size) {
        // clear to let GC do its work
        for (int i = newSize; i < size; i++) {
          results[i] = null;
        }
        size = newSize;
        modified = true;
      }
    }
    return modified;
  }

  @Override
  public boolean retainCollection(Collection<PeakResult> results) {
    return retainArray(results.toArray(new PeakResult[results.size()]));
  }

  @Override
  public boolean retainArray(PeakResult[] results) {
    if (results == null || results.length == 0) {
      final boolean result = size != 0;
      clear();
      return result;
    }
    return batchRemove(results, true);
  }

  @Override
  public boolean retainStore(PeakResultStore results) {
    if (results instanceof ArrayPeakResultStore) {
      return retainArray(((ArrayPeakResultStore) results).results);
    }
    return retainArray(results.toArray());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Note: This does not remove the references to the underlying data or reallocate storage thus
   * {@link #get(int)} can return stale data.
   */
  @Override
  public void clear() {
    size = 0;
  }

  @Override
  public void trimToSize() {
    if (size < results.length) {
      results = toArray();
    }
  }

  @Override
  public void sort(Comparator<PeakResult> comparator) {
    Arrays.sort(results, 0, size, comparator);
  }

  @Override
  public PeakResult[] toArray() {
    final PeakResult[] array = new PeakResult[size];
    System.arraycopy(results, 0, array, 0, size);
    return array;
  }

  @Override
  public PeakResultStore copy() {
    return new ArrayPeakResultStore(this);
  }

  @Override
  public PeakResultStore copy(boolean deepCopy) {
    if (deepCopy) {
      final ArrayPeakResultStore copy = new ArrayPeakResultStore(size());
      for (int i = 0, max = size(); i < max; i++) {
        copy.add(results[i].copy());
      }
      return copy;
    }
    return copy();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Note: This does not remove the references to the underlying data or reallocate storage thus
   * {@link #get(int)} can return stale data.
   */
  @Override
  public boolean removeIf(Predicate<PeakResult> filter) {
    Objects.requireNonNull(filter);

    // Adapted from java.util.ArrayList (Java 1.8)

    // figure out which elements are to be removed
    // any exception thrown from the filter predicate at this stage
    // will leave the collection unmodified
    int removeCount = 0;
    final int oldSize = this.size;
    final BitSet removeSet = new BitSet(oldSize);
    for (int i = 0; i < oldSize; i++) {
      if (filter.test(results[i])) {
        removeSet.set(i);
        removeCount++;
      }
    }

    // shift surviving elements left over the spaces left by removed elements
    final boolean anyToRemove = removeCount > 0;
    if (anyToRemove) {
      final int newSize = oldSize - removeCount;
      for (int i = 0, j = 0; (i < oldSize) && (j < newSize); i++, j++) {
        i = removeSet.nextClearBit(i);
        results[j] = results[i];
      }
      for (int k = newSize; k < oldSize; k++) {
        results[k] = null; // Let gc do its work
      }
      this.size = newSize;
    }

    return anyToRemove;
  }

  @Override
  public void forEach(PeakResultProcedure procedure) {
    for (int i = 0; i < size; i++) {
      procedure.execute(results[i]);
    }
  }

  @Override
  public PeakResult[] subset(Predicate<PeakResult> filter) {
    final ArrayPeakResultStore list = new ArrayPeakResultStore(10);
    for (int i = 0; i < size; i++) {
      if (filter.test(results[i])) {
        list.add(results[i]);
      }
    }
    return list.toArray();
  }

  @Override
  public void shuffle(UniformRandomProvider randomSource) {
    // Fisher-Yates shuffle
    for (int i = size; i-- > 1;) {
      final int j = randomSource.nextInt(i + 1);
      final PeakResult tmp = results[i];
      results[i] = results[j];
      results[j] = tmp;
    }
  }

  @Override
  public int indexOf(PeakResult result) {
    if (result == null) {
      for (int i = 0; i < size; i++) {
        if (results[i] == null) {
          return i;
        }
      }
    } else {
      for (int i = 0; i < size; i++) {
        if (result.equals(results[i])) {
          return i;
        }
      }
    }
    return -1;
  }

  @Override
  public int lastIndexOf(PeakResult result) {
    if (result == null) {
      for (int i = size; i-- > 0;) {
        if (results[i] == null) {
          return i;
        }
      }
    } else {
      for (int i = size; i-- > 0;) {
        if (result.equals(results[i])) {
          return i;
        }
      }
    }
    return -1;
  }

  @Override
  public boolean contains(PeakResult result) {
    return indexOf(result) != -1;
  }
}
