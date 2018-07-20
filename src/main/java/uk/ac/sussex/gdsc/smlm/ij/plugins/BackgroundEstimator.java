/*-
 * #%L
 * Genome Damage and Stability Centre SMLM ImageJ Plugins
 *
 * Software for single molecule localisation microscopy (SMLM)
 * %%
 * Copyright (C) 2011 - 2018 Alex Herbert
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

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.math3.util.FastMath;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingExtendedGenericDialog;
import ij.gui.Plot;
import ij.gui.Plot2;
import ij.gui.PlotWindow;
import ij.plugin.WindowOrganiser;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.ImageProcessor;
import ij.text.TextWindow;
import uk.ac.sussex.gdsc.core.ij.BufferedTextWindow;
import uk.ac.sussex.gdsc.core.ij.Utils;
import uk.ac.sussex.gdsc.core.threshold.AutoThreshold;
import uk.ac.sussex.gdsc.core.utils.Maths;
import uk.ac.sussex.gdsc.core.utils.NoiseEstimator;
import uk.ac.sussex.gdsc.core.utils.Statistics;
import uk.ac.sussex.gdsc.smlm.data.config.FitProtos.NoiseEstimatorMethod;
import uk.ac.sussex.gdsc.smlm.data.config.FitProtosHelper;
import uk.ac.sussex.gdsc.smlm.engine.DataEstimator;
import uk.ac.sussex.gdsc.smlm.ij.settings.SettingsManager;
import uk.ac.sussex.gdsc.smlm.ij.utils.IJImageConverter;

/**
 * Contains methods to find the noise in the provided image data.
 */
public class BackgroundEstimator implements ExtendedPlugInFilter, DialogListener
{
	private static final String TITLE = "Background Estimator";
	private List<double[]> results;
	private final int FLAGS = DOES_8G | DOES_16 | DOES_32 | PARALLELIZE_STACKS | FINAL_PROCESSING | NO_CHANGES;
	private PlugInFilterRunner pfr;
	private ImagePlus imp;
	private NonBlockingExtendedGenericDialog gd;
	private static double percentile;
	private static NoiseEstimatorMethod noiseMethod = NoiseEstimatorMethod.QUICK_RESIDUALS_LEAST_TRIMMED_OF_SQUARES;
	private NoiseEstimator.Method myNoiseMethod = null;
	private static AutoThreshold.Method thresholdMethod = AutoThreshold.Method.DEFAULT;
	private static float fraction;
	private static int histogramSize;
	static
	{
		final DataEstimator de = new DataEstimator(new float[0], 0, 0);
		fraction = de.getFraction();
		histogramSize = de.getHistogramSize();
	}

	@Override
	public int setup(String arg, ImagePlus imp)
	{
		if (arg.equalsIgnoreCase("final"))
		{
			showResults();
			return DONE;
		}
		SMLMUsageTracker.recordPlugin(this.getClass(), arg);

		if (imp == null)
		{
			IJ.noImage();
			return DONE;
		}
		this.imp = imp;
		results = Collections.synchronizedList(new ArrayList<double[]>(imp.getStackSize()));
		return FLAGS;
	}

	@Override
	public int showDialog(ImagePlus imp, String command, PlugInFilterRunner pfr)
	{
		// If using a stack, provide a preview graph of the noise for two methods
		if (imp.getStackSize() > 1)
		{
			this.pfr = pfr;

			drawPlot();

			gd = new NonBlockingExtendedGenericDialog(TITLE);
			gd.addHelp(About.HELP_URL);

			gd.addSlider("Percential", 0, 100, percentile);

			gd.addChoice("Noise_method", SettingsManager.getNoiseEstimatorMethodNames(), noiseMethod.ordinal());

			// For background based on pixel below a threshold
			final String[] thresholdMethods = AutoThreshold.getMethods(true);
			gd.addChoice("Threshold_method", thresholdMethods, thresholdMethods[thresholdMethod.ordinal() - 1]);
			gd.addSlider("Fraction", 0, 0.999, fraction);
			gd.addNumericField("Histogram_size", histogramSize, 0);

			gd.addDialogListener(this);
			gd.addMessage("Click OK to compute table for all slices");
			gd.showDialog();

			if (gd.wasCanceled() || !dialogItemChanged(gd, null))
				return DONE;
		}

		return IJ.setupDialog(imp, FLAGS);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see ij.gui.DialogListener#dialogItemChanged(ij.gui.GenericDialog, java.awt.AWTEvent)
	 */
	@Override
	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e)
	{
		percentile = gd.getNextNumber();
		noiseMethod = SettingsManager.getNoiseEstimatorMethodValues()[gd.getNextChoiceIndex()];
		myNoiseMethod = FitProtosHelper.convertNoiseEstimatorMethod(noiseMethod);
		thresholdMethod = AutoThreshold.getMethod(gd.getNextChoiceIndex(), true);
		fraction = (float) gd.getNextNumber();
		histogramSize = (int) gd.getNextNumber();
		if (gd.isShowing())
			drawPlot();
		return true;
	}

	/**
	 * Build a plot of the noise estimate from the current frame.
	 * Limit the preview to 100 frames.
	 */
	private void drawPlot()
	{
		IJ.showStatus("Estimating background ...");

		final int start = imp.getCurrentSlice();
		final int end = FastMath.min(imp.getStackSize(), start + 100);
		final int size = end - start + 1;
		final double[] xValues = new double[size];
		final double[] noise1 = new double[size];
		final double[] noise2 = new double[size];
		final double[] background = new double[size];
		final double[] threshold = new double[size];
		final double[] percentile = new double[size];

		final ImageStack stack = imp.getImageStack();
		final Rectangle bounds = imp.getProcessor().getRoi();
		float[] buffer = null;
		for (int slice = start, i = 0; slice <= end; slice++, i++)
		{
			IJ.showProgress(i, size);
			final ImageProcessor ip = stack.getProcessor(slice);
			buffer = IJImageConverter.getData(ip.getPixels(), ip.getWidth(), ip.getHeight(), bounds, buffer);
			final DataEstimator de = new DataEstimator(buffer, bounds.width, bounds.height);
			de.setFraction(fraction);
			de.setHistogramSize(histogramSize);
			de.setThresholdMethod(thresholdMethod);
			xValues[i] = slice;
			try
			{
				noise1[i] = de.getNoise();
				noise2[i] = de.getNoise(myNoiseMethod);
				background[i] = de.getBackground();
				threshold[i] = de.getThreshold();
				percentile[i] = de.getPercentile(BackgroundEstimator.percentile);
			}
			catch (final Exception e)
			{
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
		IJ.showProgress(1);

		IJ.showStatus("Plotting background ...");

		final WindowOrganiser wo = new WindowOrganiser();
		plot(wo, xValues, noise1, noise2, null, "Noise", "Background Noise", "Global Noise", null);
		plot(wo, xValues, background, threshold, percentile, "Background", "Background", "Threshold", "Percentile");
		wo.tile();

		IJ.showStatus("");
	}

	private void plot(WindowOrganiser wo, double[] xValues, double[] data1, double[] data2, double[] data3,
			String title, String title1, String title2, String title3)
	{
		// Get limits
		final double[] a = Maths.limits(xValues);
		double[] b = Maths.limits(data1);
		b = Maths.limits(b, data2);
		if (data3 != null)
			b = Maths.limits(b, data3);

		title = imp.getTitle() + " " + title;
		final Plot2 plot = new Plot2(title, "Slice", title);
		double range = b[1] - b[0];
		if (range == 0)
			range = 1;
		plot.setLimits(a[0], a[1], b[0] - 0.05 * range, b[1] + 0.05 * range);

		plot.setColor(Color.blue);
		plot.addPoints(xValues, data1, Plot.LINE);
		plot.draw();
		Statistics stats = new Statistics(data1);
		String label = String.format("%s (Blue) = %s +/- %s", title1, Utils.rounded(stats.getMean()),
				Utils.rounded(stats.getStandardDeviation()));

		plot.setColor(Color.red);
		plot.addPoints(xValues, data2, Plot.LINE);
		stats = new Statistics(data2);
		label += String.format(", %s (Red) = %s +/- %s", title2, Utils.rounded(stats.getMean()),
				Utils.rounded(stats.getStandardDeviation()));

		if (data3 != null)
		{
			plot.setColor(Color.green);
			plot.addPoints(xValues, data3, Plot.LINE);
			stats = new Statistics(data3);
			label += String.format(", %s (Green) = %s +/- %s", title3, Utils.rounded(stats.getMean()),
					Utils.rounded(stats.getStandardDeviation()));
		}

		plot.setColor(Color.black);
		plot.addLabel(0, 0, label);

		final PlotWindow pw = Utils.display(title, plot);
		if (Utils.isNewWindow())
			wo.add(pw);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see ij.plugin.filter.PlugInFilter#run(ij.process.ImageProcessor)
	 */
	@Override
	public void run(ImageProcessor ip)
	{
		// Perform all methods and add to the results
		final double[] result = new double[8];
		int i = 0;
		result[i++] = (pfr == null) ? 1 : pfr.getSliceNumber();
		final Rectangle bounds = ip.getRoi();
		final float[] buffer = IJImageConverter.getData(ip.getPixels(), ip.getWidth(), ip.getHeight(), bounds, null);
		final DataEstimator de = new DataEstimator(buffer, bounds.width, bounds.height);
		de.setFraction(fraction);
		de.setHistogramSize(histogramSize);
		de.setThresholdMethod(thresholdMethod);
		result[i++] = de.isBackgroundRegion() ? 1 : 0;
		result[i++] = de.getNoise();
		result[i++] = de.getNoise(myNoiseMethod);
		result[i++] = de.getBackground();
		result[i++] = de.getThreshold();
		result[i++] = de.getBackgroundSize();
		result[i++] = de.getPercentile(percentile);
		results.add(result);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see ij.plugin.filter.ExtendedPlugInFilter#setNPasses(int)
	 */
	@Override
	public void setNPasses(int nPasses)
	{
		// Do nothing
	}

	private void showResults()
	{
		Collections.sort(results, new Comparator<double[]>()
		{
			@Override
			public int compare(double[] o1, double[] o2)
			{
				// Sort on slice number
				return (o1[0] < o2[0]) ? -1 : 1;
			}
		});

		final BufferedTextWindow tw = new BufferedTextWindow(
				new TextWindow(imp.getTitle() + " Background", createHeader(), "", 800, 400));
		for (final double[] result : results)
			tw.append(createResult(result));
		tw.flush();
	}

	private String createHeader()
	{
		StringBuilder sb = new StringBuilder(imp.getTitle());
		sb.append('\t').append(Utils.rounded(percentile));
		sb.append('\t').append(noiseMethod.toString());
		sb.append('\t').append(thresholdMethod.toString());
		sb.append('\t').append(Utils.rounded(fraction));
		sb.append('\t').append(histogramSize).append('\t');
		prefix = sb.toString();

		sb = new StringBuilder("Image");
		sb.append("\tPercentile");
		sb.append("\tNoiseMethod");
		sb.append("\tThresholdMethod");
		sb.append("\tFraction");
		sb.append("\tHistogramSize");
		sb.append("\tSlice");
		sb.append("\tIsBackground");
		sb.append("\tNoise");
		sb.append("\tGlobalNoise");
		sb.append("\tBackground");
		sb.append("\tThreshold");
		sb.append("\tBackgroundSize");
		sb.append("\tPercentile");
		return sb.toString();
	}

	private String prefix;

	private String createResult(double[] result)
	{
		final StringBuilder sb = new StringBuilder(prefix);
		sb.append((int) result[0]);
		for (int i = 1; i < result.length; i++)
			sb.append('\t').append(Utils.rounded(result[i]));
		return sb.toString();
	}
}