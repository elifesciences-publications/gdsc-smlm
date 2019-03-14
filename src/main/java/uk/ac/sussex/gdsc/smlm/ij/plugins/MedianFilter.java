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

import uk.ac.sussex.gdsc.core.ij.ImageJTrackProgress;
import uk.ac.sussex.gdsc.core.ij.ImageJUtils;
import uk.ac.sussex.gdsc.core.logging.Ticker;
import uk.ac.sussex.gdsc.core.utils.FloatLinkedMedianWindow;
import uk.ac.sussex.gdsc.core.utils.FloatMedianWindow;
import uk.ac.sussex.gdsc.core.utils.TextUtils;
import uk.ac.sussex.gdsc.core.utils.concurrent.ConcurrencyUtils;
import uk.ac.sussex.gdsc.smlm.ij.utils.ImageJImageConverter;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;

import org.apache.commons.math3.util.FastMath;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Filters each pixel using a sliding median through the time stack. Medians are computed at set
 * intervals and the values interpolated.
 */
public class MedianFilter implements PlugInFilter {
  private static final String TITLE = "Median Filter";
  private static final int FLAGS = DOES_8G | DOES_16 | DOES_32;

  private static int radius = 50;
  private static int interval = 12;
  private static int blockSize = 32;
  private static boolean subtract;
  private static float bias = 500;

  private ImagePlus imp;

  /**
   * Extract the data for a specified slice, calculate the mean and then normalise by the mean.
   *
   * <p>Use a runnable for the image generation to allow multi-threaded operation. Input parameters
   * that are manipulated should have synchronized methods.
   */
  private static class ImageNormaliser implements Runnable {
    final ImageStack inputStack;
    final float[][] imageStack;
    final float[] mean;
    final int slice;
    final Ticker ticker;

    public ImageNormaliser(ImageStack inputStack, float[][] imageStack, float[] mean, int slice,
        Ticker ticker) {
      this.inputStack = inputStack;
      this.imageStack = imageStack;
      this.mean = mean;
      this.slice = slice;
      this.ticker = ticker;
    }

    @Override
    public void run() {
      final float[] data =
          imageStack[slice - 1] = ImageJImageConverter.getData(inputStack.getProcessor(slice));
      double sum = 0;
      for (final float f : data) {
        sum += f;
      }
      final float av = mean[slice - 1] = (float) (sum / data.length);
      for (int i = 0; i < data.length; i++) {
        data[i] /= av;
      }
      ticker.tick();
    }
  }

  /**
   * Compute the rolling median window on a set of pixels in the image stack, interpolating at
   * intervals if necessary. Convert back into the final image pixel value by multiplying by the
   * mean for the slice.
   *
   * <p>Use a runnable for the image generation to allow multi-threaded operation. Input parameters
   * that are manipulated should have synchronized methods.
   */
  private static class ImageGenerator implements Runnable {
    final float[][] imageStack;
    final float[] mean;
    final int start;
    final int end;
    final Ticker ticker;

    public ImageGenerator(float[][] imageStack, float[] mean, int start, int end, Ticker ticker) {
      this.imageStack = imageStack;
      this.mean = mean;
      this.start = start;
      this.end = end;
      this.ticker = ticker;
    }

    @Override
    public void run() {
      if (IJ.escapePressed()) {
        return;
      }

      // For each pixel extract the time line of pixel data
      final int nSlices = imageStack.length;
      final int nPixels = end - start;

      if (nPixels == 1) {
        if (interval == 1) {
          // The rolling window operates effectively in linear time so use this with an interval of
          // 1. There is no need for interpolation and the data can be written directly to the
          // output.
          final int window = 2 * radius + 1;
          final float[] data = new float[window];
          for (int slice = 0; slice < window; slice++) {
            data[slice] = imageStack[slice][start];
          }

          // Initialise the window with the first n frames.
          final FloatLinkedMedianWindow mw = new FloatLinkedMedianWindow(data);

          // Get the early medians.
          int slice = 0;
          for (; slice < radius; slice++) {
            imageStack[slice][start] = mw.getMedianOldest(slice + 1 + radius) * mean[slice];
          }

          // Then increment through the data getting the median when required.
          for (int j = mw.getSize(); j < nSlices; j++, slice++) {
            imageStack[slice][start] = mw.getMedian() * mean[slice];
            mw.add(imageStack[j][start]);
          }

          // Then get the later medians as required.
          for (int i = 2 * radius + 1; slice < nSlices; i--, slice++) {
            imageStack[slice][start] = mw.getMedianYoungest(i) * mean[slice];
          }
        } else {
          final float[] data = new float[nSlices];
          for (int slice = 0; slice < nSlices; slice++) {
            data[slice] = imageStack[slice][start];
          }

          // Create median window filter
          final FloatMedianWindow mw = FloatMedianWindow.wrap(data.clone(), radius);

          // Produce the medians
          for (int slice = 0; slice < nSlices; slice += interval) {
            data[slice] = mw.getMedian();
            mw.increment(interval);
          }
          // Final position if necessary
          if (mw.getPosition() != nSlices + interval - 1) {
            mw.setPosition(nSlices - 1);
            data[nSlices - 1] = mw.getMedian();
          }

          // Interpolate
          for (int slice = 0; slice < nSlices; slice += interval) {
            final int endSlice = FastMath.min(slice + interval, nSlices - 1);
            final float increment = (data[endSlice] - data[slice]) / (endSlice - slice);
            for (int s = slice + 1, i = 1; s < endSlice; s++, i++) {
              data[s] = data[slice] + increment * i;
            }
          }

          // Put back in the image re-scaling using the image mean
          for (int slice = 0; slice < nSlices; slice++) {
            imageStack[slice][start] = data[slice] * mean[slice];
          }
        }
      } else if (interval == 1) {
        // The rolling window operates effectively in linear time so use this with an interval of 1.
        // There is no need for interpolation and the data can be written directly to the output.
        final int window = 2 * radius + 1;
        final float[][] data = new float[nPixels][window];
        for (int slice = 0; slice < window; slice++) {
          final float[] sliceData = imageStack[slice];
          for (int pixel = 0, i = start; pixel < nPixels; pixel++, i++) {
            data[pixel][slice] = sliceData[i];
          }
        }

        // Initialise the window with the first n frames.
        final FloatLinkedMedianWindow[] mw = new FloatLinkedMedianWindow[nPixels];
        for (int pixel = 0; pixel < nPixels; pixel++) {
          mw[pixel] = new FloatLinkedMedianWindow(data[pixel]);
        }

        // Get the early medians.
        int slice = 0;
        for (; slice < radius; slice++) {
          for (int pixel = 0, i = start; pixel < nPixels; pixel++, i++) {
            imageStack[slice][i] = mw[pixel].getMedianOldest(slice + 1 + radius) * mean[slice];
          }
        }

        // Then increment through the data getting the median when required.
        for (int j = mw[0].getSize(); j < nSlices; j++, slice++) {
          for (int pixel = 0, i = start; pixel < nPixels; pixel++, i++) {
            imageStack[slice][i] = mw[pixel].getMedian() * mean[slice];
            mw[pixel].add(imageStack[j][i]);
          }
        }

        // Then get the later medians as required.
        for (int i = 2 * radius + 1; slice < nSlices; i--, slice++) {
          for (int pixel = 0, ii = start; pixel < nPixels; pixel++, ii++) {
            imageStack[slice][ii] = mw[pixel].getMedianYoungest(i) * mean[slice];
          }
        }
      } else {
        final float[][] data = new float[nPixels][nSlices];
        for (int slice = 0; slice < nSlices; slice++) {
          final float[] sliceData = imageStack[slice];
          for (int pixel = 0, i = start; pixel < nPixels; pixel++, i++) {
            data[pixel][slice] = sliceData[i];
          }
        }

        // Create median window filter
        final FloatMedianWindow[] mw = new FloatMedianWindow[nPixels];
        for (int pixel = 0; pixel < nPixels; pixel++) {
          mw[pixel] = FloatMedianWindow.wrap(data[pixel].clone(), radius);
        }

        // Produce the medians
        for (int slice = 0; slice < nSlices; slice += interval) {
          for (int pixel = 0; pixel < nPixels; pixel++) {
            data[pixel][slice] = mw[pixel].getMedian();
            mw[pixel].increment(interval);
          }
        }
        // Final position if necessary
        if (mw[0].getPosition() != nSlices + interval - 1) {
          for (int pixel = 0; pixel < nPixels; pixel++) {
            mw[pixel].setPosition(nSlices - 1);
            data[pixel][nSlices - 1] = mw[pixel].getMedian();
          }
        }

        // Interpolate
        final float[] increment = new float[nPixels];
        for (int slice = 0; slice < nSlices; slice += interval) {
          final int endSlice = FastMath.min(slice + interval, nSlices - 1);
          for (int pixel = 0; pixel < nPixels; pixel++) {
            increment[pixel] = (data[pixel][endSlice] - data[pixel][slice]) / (endSlice - slice);
          }
          for (int s = slice + 1, i = 1; s < endSlice; s++, i++) {
            for (int pixel = 0; pixel < nPixels; pixel++) {
              data[pixel][s] = data[pixel][slice] + increment[pixel] * i;
            }
          }
        }

        // Put back in the image re-scaling using the image mean
        for (int slice = 0; slice < nSlices; slice++) {
          final float[] sliceData = imageStack[slice];
          for (int pixel = 0, i = start; pixel < nPixels; pixel++, i++) {
            sliceData[i] = data[pixel][slice] * mean[slice];
          }
        }
      }

      ticker.tick(nPixels);
    }
  }

  /**
   * Extract the data for a specified slice, subtract the background median filter and add the bias.
   *
   * <p>Use a runnable for the image generation to allow multi-threaded operation. Input parameters
   * that are manipulated should have synchronized methods.
   */
  private static class ImageFilter implements Runnable {
    final ImageStack inputStack;
    final float[][] imageStack;
    final int slice;
    final Ticker ticker;

    public ImageFilter(ImageStack inputStack, float[][] imageStack, int slice, Ticker ticker) {
      this.inputStack = inputStack;
      this.imageStack = imageStack;
      this.slice = slice;
      this.ticker = ticker;
    }

    @Override
    public void run() {
      final float[] data = ImageJImageConverter.getData(inputStack.getProcessor(slice));
      final float[] filter = imageStack[slice - 1];
      final float b = bias;
      for (int i = 0; i < data.length; i++) {
        filter[i] = data[i] - filter[i] + b;
      }
      ticker.tick();
    }
  }

  @Override
  public int setup(String arg, ImagePlus imp) {
    SmlmUsageTracker.recordPlugin(this.getClass(), arg);

    if (imp == null) {
      IJ.noImage();
      return DONE;
    }
    this.imp = imp;
    return showDialog(this);
  }

  @Override
  public void run(ImageProcessor ip) {
    final long start = System.nanoTime();

    final ImageJTrackProgress trackProgress = new ImageJTrackProgress();
    final ImageStack stack = imp.getImageStack();

    final int width = stack.getWidth();
    final int height = stack.getHeight();
    final float[][] imageStack = new float[stack.getSize()][];
    final float[] mean = new float[imageStack.length];

    // Get the mean for each frame and normalise the data using the mean
    final ExecutorService threadPool = Executors.newFixedThreadPool(Prefs.getThreads());
    List<Future<?>> futures = new LinkedList<>();

    Ticker ticker = Ticker.createStarted(trackProgress, stack.getSize(), true);
    IJ.showStatus("Calculating means...");
    for (int n = 1; n <= stack.getSize(); n++) {
      futures.add(threadPool.submit(new ImageNormaliser(stack, imageStack, mean, n, ticker)));
    }

    // Finish processing data
    ConcurrencyUtils.waitForCompletionUnchecked(futures);

    futures = new LinkedList<>();

    final int size = width * height;
    ticker = Ticker.createStarted(trackProgress, size, true);
    IJ.showStatus("Calculating medians...");
    for (int i = 0; i < size; i += blockSize) {
      futures.add(threadPool.submit(
          new ImageGenerator(imageStack, mean, i, FastMath.min(i + blockSize, size), ticker)));
    }

    // Finish processing data
    ConcurrencyUtils.waitForCompletionUnchecked(futures);

    if (ImageJUtils.isInterrupted()) {
      return;
    }

    if (subtract) {
      IJ.showStatus("Subtracting medians...");
      ticker = Ticker.createStarted(trackProgress, stack.getSize(), true);
      for (int n = 1; n <= stack.getSize(); n++) {
        futures.add(threadPool.submit(new ImageFilter(stack, imageStack, n, ticker)));
      }

      // Finish processing data
      ConcurrencyUtils.waitForCompletionUnchecked(futures);
    }

    // Update the image
    final ImageStack outputStack =
        new ImageStack(stack.getWidth(), stack.getHeight(), stack.getSize());
    for (int n = 1; n <= stack.getSize(); n++) {
      outputStack.setPixels(imageStack[n - 1], n);
    }

    imp.setStack(outputStack);
    imp.updateAndDraw();

    IJ.showTime(imp, TimeUnit.NANOSECONDS.toMillis(start), "Completed");
    final long nanoseconds = System.nanoTime() - start;
    ImageJUtils.log(TITLE + " : Radius %d, Interval %d, Block size %d = %s, %s / frame", radius,
        interval, blockSize, TextUtils.millisToString(nanoseconds),
        TextUtils.nanosToString(Math.round(nanoseconds / (double) imp.getStackSize())));
  }

  private static int showDialog(MedianFilter plugin) {
    final GenericDialog gd = new GenericDialog(TITLE);
    gd.addHelp(About.HELP_URL);

    gd.addMessage("Compute the median using a rolling window at set intervals.\n"
        + "Blocks of pixels are processed on separate threads.");

    gd.addSlider("Radius", 10, 100, radius);
    gd.addSlider("Interval", 10, 30, interval);
    gd.addSlider("Block_size", 1, 32, blockSize);
    gd.addCheckbox("Subtract", subtract);
    gd.addSlider("Bias", 0, 1000, bias);

    gd.showDialog();

    if (gd.wasCanceled()) {
      return DONE;
    }

    radius = (int) Math.abs(gd.getNextNumber());
    interval = (int) Math.abs(gd.getNextNumber());
    blockSize = (int) Math.abs(gd.getNextNumber());
    if (blockSize < 1) {
      blockSize = 1;
    }
    subtract = gd.getNextBoolean();
    bias = (float) Math.abs(gd.getNextNumber());

    if (gd.invalidNumber() || interval < 1 || radius < 1) {
      return DONE;
    }

    // Check the window size is smaller than the stack size
    if (plugin.imp.getStackSize() < 2 * radius + 1) {
      IJ.error(TITLE, "The window size is larger than the stack size.\n"
          + "This is equal to a z-stack median projection.");
      return DONE;
    }

    return FLAGS;
  }
}
