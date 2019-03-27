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

package uk.ac.sussex.gdsc.smlm.function.cspline;

import uk.ac.sussex.gdsc.core.data.procedures.TrivalueProcedure;
import uk.ac.sussex.gdsc.core.logging.Ticker;
import uk.ac.sussex.gdsc.core.logging.TrackProgress;
import uk.ac.sussex.gdsc.core.math.interpolation.CubicSplinePosition;
import uk.ac.sussex.gdsc.core.math.interpolation.CustomTricubicFunction;
import uk.ac.sussex.gdsc.core.math.interpolation.CustomTricubicInterpolatingFunction;
import uk.ac.sussex.gdsc.core.math.interpolation.FloatCustomTricubicFunction;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stores a cubic spline data.
 */
public class CubicSplineData {
  /** The maxx. */
  final int maxx;
  /** The maxy. */
  final int maxy;
  /** The splines. */
  final CustomTricubicFunction[][] splines;

  /**
   * Instantiates a new cubic spline data.
   *
   * @param maxx the maxx
   * @param maxy the maxy
   * @param splines the splines
   */
  public CubicSplineData(int maxx, int maxy, CustomTricubicFunction[][] splines) {
    if (maxx < 1 || maxy < 1 || splines.length < 1) {
      throw new IllegalArgumentException("No splines");
    }
    final int size = maxx * maxy;
    for (int z = 0; z < splines.length; z++) {
      if (splines[z].length != size) {
        throw new IllegalArgumentException("Incorrect XY splines size");
      }
    }
    this.maxx = maxx;
    this.maxy = maxy;
    this.splines = splines;
  }

  /**
   * Instantiates a new cubic spline data.
   *
   * @param maxx the maxx
   * @param maxy the maxy
   * @param splines the splines
   * @param dummy the dummy flag
   */
  private CubicSplineData(int maxx, int maxy, CustomTricubicFunction[][] splines, boolean dummy) {
    this.maxx = maxx;
    this.maxy = maxy;
    this.splines = splines;
  }

  /**
   * Instantiates a new cubic spline data by copying the nodes from the function.
   *
   * <p>Warning: Any information about the scale of each axis is ignored.
   *
   * @param function the function
   */
  public CubicSplineData(CustomTricubicInterpolatingFunction function) {
    maxx = function.getMaxXSplinePosition() + 1;
    maxy = function.getMaxYSplinePosition() + 1;
    final int maxz = function.getMaxZSplinePosition() + 1;

    final int size = maxx * maxy;
    splines = new CustomTricubicFunction[maxz][size];

    for (int z = 0; z < splines.length; z++) {
      for (int y = 0, i = 0; y < maxy; y++) {
        for (int x = 0; x < maxx; x++, i++) {
          splines[z][i] = function.getSplineNode(x, y, z);
        }
      }
    }
  }

  /**
   * Checks if is single precision.
   *
   * @return true, if is single precision
   */
  public boolean isSinglePrecision() {
    return splines[0][0] instanceof FloatCustomTricubicFunction;
  }

  private static interface SplineWriter {
    void write(DataOutput out, CustomTricubicFunction function) throws IOException;
  }

  private static class FloatSplineWriter implements SplineWriter {
    @Override
    public void write(DataOutput out, CustomTricubicFunction function) throws IOException {
      for (int i = 0; i < 64; i++) {
        out.writeFloat(function.getf(i));
      }
    }
  }

  private static class DoubleSplineWriter implements SplineWriter {
    @Override
    public void write(DataOutput out, CustomTricubicFunction function) throws IOException {
      for (int i = 0; i < 64; i++) {
        out.writeDouble(function.get(i));
      }
    }
  }

  private static interface SplineReader {
    CustomTricubicFunction read(DataInput in) throws IOException;
  }

  private static class FloatSplineReader implements SplineReader {
    float[] splines = new float[64];

    @Override
    public CustomTricubicFunction read(DataInput in) throws IOException {
      for (int i = 0; i < 64; i++) {
        splines[i] = in.readFloat();
      }
      return CustomTricubicFunction.create(splines.clone());
    }
  }

  private static class DoubleSplineReader implements SplineReader {
    double[] splines = new double[64];

    @Override
    public CustomTricubicFunction read(DataInput in) throws IOException {
      for (int i = 0; i < 64; i++) {
        splines[i] = in.readDouble();
      }
      return CustomTricubicFunction.create(splines.clone());
    }
  }

  /**
   * Write a tricubic splines to the output stream. The output will be buffered for performance.
   *
   * @param outputStream the output stream
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public void write(OutputStream outputStream) throws IOException {
    write(outputStream, null);
  }

  /**
   * Write a tricubic splines to the output stream. The output will be buffered for performance.
   *
   * @param outputStream the output stream
   * @param progress the progress
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public void write(OutputStream outputStream, TrackProgress progress) throws IOException {
    // Write dimensions
    final int maxz = splines.length;
    final Ticker ticker = Ticker.create(progress, (long) maxx * maxy * maxz, false);
    ticker.start();
    final BufferedOutputStream buffer = new BufferedOutputStream(outputStream);
    final DataOutput out = new DataOutputStream(buffer);
    out.writeInt(maxx);
    out.writeInt(maxy);
    out.writeInt(maxz);
    // Write precision
    final boolean singlePrecision = isSinglePrecision();
    out.writeBoolean(singlePrecision);
    final SplineWriter writer =
        (singlePrecision) ? new FloatSplineWriter() : new DoubleSplineWriter();
    final int size = maxx * maxy;
    for (int z = 0; z < maxz; z++) {
      for (int i = 0; i < size; i++) {
        ticker.tick();
        writer.write(out, splines[z][i]);
      }
    }
    ticker.stop();
    buffer.flush();
  }

  /**
   * Read a tricubic splines from the input stream.
   *
   * <p>Note: For best performance a buffered input stream should be used.
   *
   * @param inputStream the input stream
   * @return the custom tricubic interpolating function
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public static CubicSplineData read(InputStream inputStream) throws IOException {
    return read(inputStream, null);
  }

  /**
   * Read a tricubic splines from the input stream.
   *
   * <p>Note: For best performance a buffered input stream should be used.
   *
   * @param inputStream the input stream
   * @param progress the progress
   * @return the custom tricubic interpolating function
   * @throws IOException Signals that an I/O exception has occurred.
   */
  public static CubicSplineData read(InputStream inputStream, TrackProgress progress)
      throws IOException {
    // Read dimensions
    final DataInput in = new DataInputStream(inputStream);
    final int maxx = in.readInt();
    final int maxy = in.readInt();
    final int maxz = in.readInt();
    final Ticker ticker = Ticker.create(progress, (long) maxx * maxy * maxz, false);
    ticker.start();
    // Read precision
    final boolean singlePrecision = in.readBoolean();
    final SplineReader reader =
        (singlePrecision) ? new FloatSplineReader() : new DoubleSplineReader();
    final int size = maxx * maxy;
    final CustomTricubicFunction[][] splines = new CustomTricubicFunction[maxz][maxx * maxy];
    for (int z = 0; z < maxz; z++) {
      for (int i = 0; i < size; i++) {
        ticker.tick();
        splines[z][i] = reader.read(in);
      }
    }
    ticker.stop();
    // Skip validation
    return new CubicSplineData(maxx, maxy, splines, false);
  }

  /**
   * Gets the max X.
   *
   * @return the max X
   */
  public int getMaxX() {
    return maxx;
  }

  /**
   * Gets the max Y.
   *
   * @return the max Y
   */
  public int getMaxY() {
    return maxy;
  }

  /**
   * Gets the max Z.
   *
   * @return the max Z
   */
  public int getMaxZ() {
    return splines.length;
  }

  /**
   * Sample the function.
   *
   * <p>n samples will be taken per node in each dimension. A final sample is taken at the end of
   * the sample range thus the final range for each axis will be the current axis range.
   *
   * <p>The procedure setValue(int,int,int,double) method will be executed in ZYX order.
   *
   * @param n the number of samples per spline node
   * @param procedure the procedure
   * @param progress the progress
   * @throws IllegalArgumentException If the number of sample is not positive
   */
  public void sample(int n, TrivalueProcedure procedure, TrackProgress progress) {
    sample(n, n, n, procedure, progress);
  }

  /**
   * Sample the function.
   *
   * <p>n samples will be taken per node in each dimension. A final sample is taken at the end of
   * the sample range thus the final range for each axis will be the current axis range.
   *
   * <p>The procedure setValue(int,int,int,double) method will be executed in ZYX order.
   *
   * @param nx the number of samples per spline node in the x dimension
   * @param ny the number of samples per spline node in the y dimension
   * @param nz the number of samples per spline node in the z dimension
   * @param procedure the procedure
   * @param progress the progress
   * @throws IllegalArgumentException If the number of sample is not positive
   */
  public void sample(int nx, int ny, int nz, TrivalueProcedure procedure, TrackProgress progress) {
    if (nx < 1 || ny < 1 || nz < 1) {
      throw new IllegalArgumentException("Samples must be positive");
    }

    // We can interpolate all nodes n-times plus a final point at the last node
    final int maxx = (getMaxX() - 1) * nx;
    final int maxy = (getMaxY() - 1) * ny;
    final int maxz = (getMaxZ() - 1) * nz;
    if (!procedure.setDimensions(maxx + 1, maxy + 1, maxz + 1)) {
      return;
    }

    final Ticker ticker =
        Ticker.create(progress, (long) (maxx + 1) * (maxy + 1) * (maxz + 1), false);
    ticker.start();

    // Pre-compute interpolation tables
    final CubicSplinePosition[] sx = createCubicSplinePosition(nx);
    final CubicSplinePosition[] sy = createCubicSplinePosition(ny);
    final CubicSplinePosition[] sz = createCubicSplinePosition(nz);
    final int nx1 = nx + 1;
    final int ny1 = ny + 1;
    final int nz1 = nz + 1;

    final double[][] tables = new double[nx1 * ny1 * nz1][];
    for (int z = 0, i = 0; z < nz1; z++) {
      final CubicSplinePosition szz = sz[z];
      for (int y = 0; y < ny1; y++) {
        final CubicSplinePosition syy = sy[y];
        for (int x = 0; x < nx1; x++, i++) {
          tables[i] = CustomTricubicFunction.computePowerTable(sx[x], syy, szz);
        }
      }
    }

    // Write axis values
    // Cache the table and the spline position to use for each interpolation point
    final int[] xt = new int[maxx + 1];
    final int[] xp = new int[maxx + 1];
    for (int x = 0; x <= maxx; x++) {
      int xposition = x / nx;
      int xtable = x % nx;
      if (x == maxx) {
        // Final interpolation point
        xposition--;
        xtable = nx;
      }
      xt[x] = xtable;
      xp[x] = xposition;
      procedure.setX(x, xposition + (double) xtable / nx);
    }
    final int[] yt = new int[maxy + 1];
    final int[] yp = new int[maxy + 1];
    for (int y = 0; y <= maxy; y++) {
      int yposition = y / ny;
      int ytable = y % ny;
      if (y == maxy) {
        // Final interpolation point
        yposition--;
        ytable = ny;
      }
      yt[y] = ytable;
      yp[y] = yposition;
      procedure.setY(y, yposition + (double) ytable / ny);
    }
    final int[] zt = new int[maxz + 1];
    final int[] zp = new int[maxz + 1];
    for (int z = 0; z <= maxz; z++) {
      int zposition = z / nz;
      int ztable = z % nz;
      if (z == maxz) {
        // Final interpolation point
        zposition--;
        ztable = nz;
      }
      zt[z] = ztable;
      zp[z] = zposition;
      procedure.setZ(z, zposition + (double) ztable / nz);
    }

    // Write interpolated values
    for (int z = 0; z <= maxz; z++) {
      final CustomTricubicFunction[] xySplines = splines[zp[z]];
      for (int y = 0; y <= maxy; y++) {
        final int index = yp[y] * getMaxX();
        final int j = nx1 * (yt[y] + ny1 * zt[z]);
        for (int x = 0; x <= maxx; x++) {
          ticker.tick();
          procedure.setValue(x, y, z, xySplines[index + xp[x]].value(tables[j + xt[x]]));
        }
      }
    }

    ticker.stop();
  }

  private static CubicSplinePosition[] createCubicSplinePosition(int n) {
    // Use an extra one to have the final x=1 interpolation point.
    final int n1 = n + 1;
    final double step = 1.0 / n;
    final CubicSplinePosition[] s = new CubicSplinePosition[n1];
    for (int x = 0; x < n; x++) {
      s[x] = new CubicSplinePosition(x * step);
    }
    // Final interpolation point must be exactly 1
    s[n] = new CubicSplinePosition(1);
    return s;
  }
}
