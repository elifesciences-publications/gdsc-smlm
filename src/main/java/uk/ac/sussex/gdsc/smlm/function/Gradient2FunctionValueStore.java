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
 * Wrap a function and store the only the values from the procedure.
 */
public class Gradient2FunctionValueStore extends ValueFunctionStore
    implements Gradient1Function, Gradient1Procedure, Gradient2Function, Gradient2Procedure {
  private Gradient1Function f1;
  private Gradient1Procedure p1;
  private Gradient2Function f2;
  private Gradient2Procedure p2;

  /**
   * Instantiates a new gradient 2 function store.
   *
   * @param f the f
   */
  public Gradient2FunctionValueStore(ValueFunction f) {
    super(f);
  }

  /**
   * Instantiates a new gradient 2 function store.
   *
   * @param f the f
   */
  public Gradient2FunctionValueStore(Gradient1Function f) {
    super(f);
    this.f1 = f;
  }

  /**
   * Instantiates a new gradient 2 function store.
   *
   * @param f the f
   */
  public Gradient2FunctionValueStore(Gradient2Function f) {
    super(f);
    this.f1 = f;
    this.f2 = f;
  }

  /**
   * Instantiates a new gradient 2 function store.
   *
   * @param f the f
   * @param values the values
   */
  public Gradient2FunctionValueStore(ValueFunction f, double[] values) {
    super(f, values);
  }

  /**
   * Instantiates a new gradient 2 function store.
   *
   * @param f the f
   * @param values the values
   */
  public Gradient2FunctionValueStore(Gradient1Function f, double[] values) {
    super(f, values);
    this.f1 = f;
  }

  /**
   * Instantiates a new gradient 2 function store.
   *
   * @param f the f
   * @param values the values
   */
  public Gradient2FunctionValueStore(Gradient2Function f, double[] values) {
    super(f, values);
    this.f1 = f;
    this.f2 = f;
  }

  @Override
  public void initialise(double[] a) {
    f1.initialise(a);
  }

  @Override
  public void initialise1(double[] a) {
    f1.initialise(a);
  }

  @Override
  public void initialise2(double[] a) {
    f2.initialise2(a);
  }

  @Override
  public int[] gradientIndices() {
    return f1.gradientIndices();
  }

  @Override
  public int getNumberOfGradients() {
    return f1.getNumberOfGradients();
  }

  @Override
  public void forEach(Gradient1Procedure procedure) {
    index = 0;
    createValues();
    this.p1 = procedure;
    f1.forEach((Gradient1Procedure) this);
  }

  @Override
  public void forEach(Gradient2Procedure procedure) {
    index = 0;
    createValues();
    this.p2 = procedure;
    f2.forEach((Gradient2Procedure) this);
  }

  @Override
  public void execute(double value, double[] dy_da) {
    values[index++] = value;
    p1.execute(value, dy_da);
  }

  @Override
  public void execute(double value, double[] dy_da, double[] d2y_da2) {
    values[index++] = value;
    p2.execute(value, dy_da, d2y_da2);
  }
}
