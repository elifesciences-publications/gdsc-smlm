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

package uk.ac.sussex.gdsc.smlm.ij.plugins;

import uk.ac.sussex.gdsc.core.match.BasePoint;
import uk.ac.sussex.gdsc.smlm.results.PeakResult;

/**
 * Stores details of a simulated localisation. Contains details of the amount of signal that occurs
 * due to overlap with neighbour PSFs.
 */
public class PsfSpot extends BasePoint {
  /** The time. */
  public final int time;

  /** The peak result. */
  public final PeakResult peakResult;
  /**
   * The amount of total background contributed within the region of this spot from overlapping
   * PSFs, i.e. how much higher is this spot due to other PSFs.
   */
  public float backgroundOffset;

  /** The amplitude. */
  public double amplitude;

  /**
   * Instantiates a new PSF spot.
   *
   * @param time the time
   * @param x the x
   * @param y the y
   * @param peakResult the peak result
   */
  public PsfSpot(int time, float x, float y, PeakResult peakResult) {
    super(x, y);
    this.time = time;
    this.peakResult = peakResult;
  }

  /**
   * Gets the time.
   *
   * @return the time
   */
  public int getTime() {
    return time;
  }
}
