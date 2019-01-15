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

package uk.ac.sussex.gdsc.smlm.function;

/**
 * Class for evaluating the integral (sum) of a function.
 */
public class IntegralValueProcedure implements ValueProcedure {
  /**
   * The integral (sum) or the values from the last call to
   * {@link #getIntegral(ValueFunction, double[])}.
   */
  public double integral;

  /**
   * Gets the integral.
   *
   * @param f the function
   * @param a the function coefficients
   * @return the integral
   */
  public double getIntegral(ValueFunction f, double[] a) {
    integral = 0;
    f.initialise0(a);
    f.forEach(this);
    return integral;
  }

  @Override
  public void execute(double value) {
    integral += value;
  }
}
