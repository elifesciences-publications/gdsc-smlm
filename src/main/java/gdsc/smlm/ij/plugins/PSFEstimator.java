package gdsc.smlm.ij.plugins;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2013 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import gdsc.smlm.engine.FitEngine;
import gdsc.smlm.engine.FitEngineConfiguration;
import gdsc.smlm.engine.FitJob;
import gdsc.smlm.fitting.FitConfiguration;
import gdsc.smlm.fitting.FitCriteria;
import gdsc.smlm.fitting.FitFunction;
import gdsc.smlm.fitting.function.GaussianFunction;
import gdsc.smlm.ij.IJImageSource;
import gdsc.smlm.ij.results.ResultsTable;
import gdsc.smlm.ij.settings.GlobalSettings;
import gdsc.smlm.ij.settings.PSFEstimatorSettings;
import gdsc.smlm.ij.settings.ResultsSettings;
import gdsc.smlm.ij.settings.SettingsManager;
import gdsc.smlm.ij.utils.ImageConverter;
import gdsc.smlm.results.Calibration;
import gdsc.smlm.results.PeakResult;
import gdsc.smlm.results.PeakResults;
import gdsc.smlm.results.ImageSource;
import gdsc.smlm.utils.Random;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;
import ij.text.TextWindow;

import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.util.Collection;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.descriptive.StatisticalSummary;
import org.apache.commons.math3.stat.inference.TestUtils;

/**
 * Iteratively fits local maxima using a 2D Gaussian until the PSF converges.
 */
public class PSFEstimator implements PlugInFilter, PeakResults
{
	private static final String TITLE = "PSF Estimator";
	private static TextWindow resultsWindow = null;

	private int fitFunction = FitFunction.FREE.ordinal();
	private float initialPeakStdDev0 = 1;
	private float initialPeakStdDev1 = 1;
	private float initialPeakAngle = 0;

	private GlobalSettings globalSettings;
	private FitEngineConfiguration config;
	private PSFEstimatorSettings settings;

	private int flags = DOES_16 | DOES_8G | DOES_32 | NO_CHANGES;

	private ImagePlus imp;

	// Required for the significance tests
	private static final int ANGLE = 0;
	private static final int X = 1;
	private static final int Y = 2;
	private static final int XY = 3;
	DescriptiveStatistics[] sampleNew = new DescriptiveStatistics[3];
	DescriptiveStatistics[] sampleOld = new DescriptiveStatistics[3];
	boolean[] ignore = new boolean[3];

	public PSFEstimator()
	{
		globalSettings = SettingsManager.loadSettings();
		config = globalSettings.getFitEngineConfiguration();
		settings = globalSettings.getPsfEstimatorSettings();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.filter.PlugInFilter#setup(java.lang.String, ij.ImagePlus)
	 */
	public int setup(String arg, ImagePlus imp)
	{
		if (imp == null)
		{
			IJ.noImage();
			return DONE;
		}

		Roi roi = imp.getRoi();
		if (roi != null && roi.getType() != Roi.RECTANGLE)
		{
			IJ.error("Rectangular ROI required");
			return DONE;
		}

		return showDialog(imp);
	}

	/**
	 * @param imp
	 * @return
	 */
	private int showDialog(ImagePlus imp)
	{
		// Start with a free fit for the first time
		if (IJ.altKeyDown())
		{
			fitFunction = FitFunction.FREE.ordinal();
			initialPeakStdDev0 = 1;
			initialPeakStdDev1 = 1;
			initialPeakAngle = 0;
		}
		else
		{
			FitConfiguration fitConfig = config.getFitConfiguration();
			fitFunction = fitConfig.getFitFunction().ordinal();
			initialPeakStdDev0 = fitConfig.getInitialPeakStdDev0();
			initialPeakStdDev1 = fitConfig.getInitialPeakStdDev1();
			initialPeakAngle = fitConfig.getInitialAngle();
		}

		this.imp = imp;

		GenericDialog gd = new GenericDialog(TITLE);
		gd.addHelp(About.HELP_URL);
		gd.addMessage("Estimate 2D Gaussian to fit maxima.\nHold ALT when running plugin to reset");

		gd.addNumericField("Initial_StdDev0", initialPeakStdDev0, 3);
		gd.addNumericField("Initial_StdDev1", initialPeakStdDev1, 3);
		gd.addNumericField("Initial_Angle", initialPeakAngle, 3);
		gd.addNumericField("Number_of_peaks", settings.numberOfPeaks, 0);

		// pValue sets the smallest significance level probability level at which they are said to be different.
		// i.e. p <= pValue they are different

		// lower pValue means harder to be found different.
		// lower pValue means easier to be found the same.

		gd.addNumericField("p-Value", settings.pValue, 4);
		gd.addCheckbox("Update_preferences", settings.updatePreferences);
		gd.addCheckbox("Log_progress", settings.debugPSFEstimator);
		gd.addCheckbox("Iterate", settings.iterate);

		gd.addSlider("Smoothing", 0, 2.5, config.getSmooth());
		gd.addSlider("Smoothing2", 0, 5, config.getSmooth2());
		gd.addSlider("Search_width", 0.5, 2.5, config.getSearch());
		gd.addSlider("Fitting_width", 2, 4.5, config.getFitting());

		gd.addMessage("--- Gaussian fitting ---");
		Component splitLabel = gd.getMessage();

		FitConfiguration fitConfig = config.getFitConfiguration();
		String[] functionNames = SettingsManager.getNames((Object[]) FitFunction.values());
		gd.addChoice("Fit_function", functionNames, functionNames[fitFunction]);

		String[] criteriaNames = SettingsManager.getNames((Object[]) FitCriteria.values());
		gd.addChoice("Fit_criteria", criteriaNames, criteriaNames[fitConfig.getFitCriteria().ordinal()]);
		gd.addNumericField("Significant_digits", fitConfig.getSignificantDigits(), 0);
		gd.addNumericField("Coord_delta", fitConfig.getDelta(), 4);
		gd.addNumericField("Lambda", fitConfig.getLambda(), 4);
		gd.addNumericField("Max_iterations", fitConfig.getMaxIterations(), 0);
		gd.addNumericField("Fail_limit", config.getFailuresLimit(), 0);
		gd.addCheckbox("Include_neighbours", config.isIncludeNeighbours());
		gd.addSlider("Neighbour_height", 0.01, 1, config.getNeighbourHeightThreshold());
		gd.addSlider("Residuals_threshold", 0.01, 1, config.getResidualsThreshold());

		gd.addMessage("--- Peak filtering ---\nDiscard fits that shift; are too low; or expand/contract");
		gd.addSlider("Shift_factor", 0.01, 2, fitConfig.getCoordinateShiftFactor());
		gd.addNumericField("Signal_strength", fitConfig.getSignalStrength(), 2);
		gd.addSlider("Width_factor", 0.01, 5, fitConfig.getWidthFactor());

		if (gd.getLayout() != null)
		{
			GridBagLayout grid = (GridBagLayout) gd.getLayout();

			int xOffset = 0, yOffset = 0;
			int lastY = -1, rowCount = 0;
			for (Component comp : gd.getComponents())
			{
				// Check if this should be the second major column
				if (comp == splitLabel)
				{
					xOffset += 2;
					yOffset -= rowCount;
				}
				// Reposition the field
				GridBagConstraints c = grid.getConstraints(comp);
				if (lastY != c.gridy)
					rowCount++;
				lastY = c.gridy;
				c.gridx = c.gridx + xOffset;
				c.gridy = c.gridy + yOffset;
				c.insets.left = c.insets.left + 10 * xOffset;
				c.insets.top = 0;
				c.insets.bottom = 0;
				grid.setConstraints(comp, c);
			}

			if (IJ.isLinux())
				gd.setBackground(new Color(238, 238, 238));
		}

		gd.showDialog();

		if (gd.wasCanceled() || !readDialog(gd))
			return DONE;

		return flags;
	}

	private boolean readDialog(GenericDialog gd)
	{
		initialPeakStdDev0 = (float) gd.getNextNumber();
		initialPeakStdDev1 = (float) gd.getNextNumber();
		initialPeakAngle = (float) gd.getNextNumber();

		settings.numberOfPeaks = (int) gd.getNextNumber();
		settings.pValue = gd.getNextNumber();
		settings.updatePreferences = gd.getNextBoolean();
		settings.debugPSFEstimator = gd.getNextBoolean();
		settings.iterate = gd.getNextBoolean();

		config.setSmooth(gd.getNextNumber());
		config.setSmooth2(gd.getNextNumber());
		config.setSearch(gd.getNextNumber());
		config.setFitting(gd.getNextNumber());

		FitConfiguration fitConfig = config.getFitConfiguration();
		fitConfig.setFitFunction(gd.getNextChoiceIndex());
		fitConfig.setFitCriteria(gd.getNextChoiceIndex());

		fitConfig.setSignificantDigits((int) gd.getNextNumber());
		fitConfig.setDelta(gd.getNextNumber());
		fitConfig.setLambda(gd.getNextNumber());
		fitConfig.setMaxIterations((int) gd.getNextNumber());
		config.setFailuresLimit((int) gd.getNextNumber());
		config.setIncludeNeighbours(gd.getNextBoolean());
		config.setNeighbourHeightThreshold(gd.getNextNumber());
		config.setResidualsThreshold(gd.getNextNumber());

		fitConfig.setCoordinateShiftFactor((float) gd.getNextNumber());
		fitConfig.setSignalStrength((float) gd.getNextNumber());
		fitConfig.setWidthFactor((float) gd.getNextNumber());

		if (gd.invalidNumber())
			return false;

		// Check arguments
		try
		{
			Parameters.isAboveZero("Initial SD0", initialPeakStdDev0);
			Parameters.isAboveZero("Initial SD1", initialPeakStdDev1);
			Parameters.isPositive("Initial angle", initialPeakAngle);
			Parameters.isPositive("Number of peaks", settings.numberOfPeaks);
			Parameters.isAboveZero("P-value", settings.pValue);
			Parameters.isEqualOrBelow("P-value", settings.pValue, 0.5);
			Parameters.isPositive("Smoothing", config.getSmooth());
			Parameters.isPositive("Smoothing2", config.getSmooth2());
			Parameters.isAboveZero("Search_width", config.getSearch());
			Parameters.isAboveZero("Fitting_width", config.getFitting());
			Parameters.isAboveZero("Significant digits", fitConfig.getSignificantDigits());
			Parameters.isAboveZero("Delta", fitConfig.getDelta());
			Parameters.isAboveZero("Lambda", fitConfig.getLambda());
			Parameters.isAboveZero("Max iterations", fitConfig.getMaxIterations());
			Parameters.isAboveZero("Failures limit", config.getFailuresLimit());
			Parameters.isPositive("Neighbour height threshold", config.getNeighbourHeightThreshold());
			Parameters.isPositive("Residuals threshold", config.getResidualsThreshold());
			Parameters.isPositive("Coordinate Shift factor", fitConfig.getCoordinateShiftFactor());
			Parameters.isPositive("Signal strength", fitConfig.getSignalStrength());
			Parameters.isPositive("Width factor", fitConfig.getWidthFactor());
		}
		catch (IllegalArgumentException e)
		{
			IJ.error(TITLE, e.getMessage());
			return false;
		}

		if (fitConfig.getFitFunction() != FitFunction.FREE && fitConfig.getFitFunction() != FitFunction.FREE_CIRCULAR &&
				fitConfig.getFitFunction() != FitFunction.CIRCULAR)
		{
			String msg = "ERROR: A width-fitting function must be selected (i.e. not fixed-width fitting)";
			IJ.error(TITLE, msg);
			log(msg);
			return false;
		}

		SettingsManager.saveSettings(globalSettings);

		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.filter.PlugInFilter#run(ij.process.ImageProcessor)
	 */
	public void run(ImageProcessor ip)
	{
		int result;
		while (true)
		{
			result = estimatePSF();
			if (settings.iterate && result == TRY_AGAIN)
			{
				continue;
			}
			break;
		}
		if (result < INSUFFICIENT_PEAKS)
			log("Finished. Check the table for final parameters");

		if (settings.updatePreferences)
			SettingsManager.saveSettings(globalSettings);
	}

	private static final int TRY_AGAIN = 0;
	private static final int COMPLETE = 1;
	private static final int INSUFFICIENT_PEAKS = 2;
	private static final int ABORTED = 3;
	private static final int EXCEPTION = 4;
	private static final int BAD_ESTIMATE = 5;

	private int estimatePSF()
	{
		log("Estimating PSF ... Press escape to abort");

		PeakFit fitter = createFitter();

		// Use the fit configuration to generate a Gaussian function to test what is being evaluated
		GaussianFunction gf = config.getFitConfiguration().createGaussianFunction(1, 1,
				new float[] { 0, 10, initialPeakAngle, 0, 0, initialPeakStdDev0, initialPeakStdDev1 });
		createResultsWindow();
		int iteration = 0;
		ignore[ANGLE] = !gf.evaluatesAngle();
		ignore[X] = !gf.evaluatesWidth0();
		ignore[Y] = !gf.evaluatesWidth1();

		double[] params = new double[] { gf.evaluatesAngle() ? initialPeakAngle : 0,
				gf.evaluatesWidth0() ? initialPeakStdDev0 : 0, gf.evaluatesWidth1() ? initialPeakStdDev1 : 0, 0, 0 };
		double[] params_dev = new double[3];
		boolean[] identical = new boolean[4];
		double[] p = new double[] { Double.NaN, Double.NaN, Double.NaN, Double.NaN };

		addToResultTable(iteration++, 0, params, params_dev, p);

		if (!calculateStatistics(fitter, params, params_dev))
			return INSUFFICIENT_PEAKS;

		if (!addToResultTable(iteration++, size(), params, params_dev, p))
			return BAD_ESTIMATE;

		boolean tryAgain = false;

		do
		{
			if (!calculateStatistics(fitter, params, params_dev))
				return INSUFFICIENT_PEAKS;

			try
			{
				for (int i = 0; i < 3; i++)
					getP(i, p, identical);

				if (!ignore[Y])
					getPairedP(sampleNew[X], sampleNew[Y], XY, p, identical);

				if (!addToResultTable(iteration++, size(), params, params_dev, p))
					return BAD_ESTIMATE;

				if ((ignore[ANGLE] || identical[ANGLE] || identical[XY]) && (ignore[X] || identical[X]) &&
						(ignore[Y] || identical[Y]))
				{
					tryAgain = checkAngleSignificance() || checkXYSignificance(identical);

					// Update recommended values. Only use if significant
					params[X] = sampleNew[X].getMean();
					params[Y] = (!ignore[Y] && !identical[XY]) ? sampleNew[Y].getMean() : sampleNew[X].getMean();
					params[ANGLE] = (!ignore[ANGLE]) ? sampleNew[ANGLE].getMean() : 0;

					// update starting configuration
					initialPeakAngle = (float) params[ANGLE];
					initialPeakStdDev0 = (float) params[X];
					initialPeakStdDev1 = (float) params[Y];

					if (settings.updatePreferences)
					{
						config.getFitConfiguration().setInitialPeakStdDev0((float) params[X]);
						config.getFitConfiguration().setInitialPeakStdDev1((float) params[Y]);
						config.getFitConfiguration().setInitialAngle((float) params[ANGLE]);
					}

					break;
				}

				if (IJ.escapePressed())
				{
					IJ.beep();
					IJ.showStatus("Aborted");
					return ABORTED;
				}
			}
			catch (Exception e)
			{
				e.printStackTrace();
				return EXCEPTION;
			}
		} while (true);

		return (tryAgain) ? TRY_AGAIN : COMPLETE;
	}

	private boolean checkAngleSignificance()
	{
		boolean tryAgain = false;
		if (ignore[ANGLE])
			return tryAgain;

		double p = TestUtils.tTest(0, sampleNew[ANGLE]);
		if (p < settings.pValue)
		{
			log("NOTE: Angle is not significant: %g ~ 0.0 (p=%g) => Re-run with fixed zero angle",
					sampleNew[ANGLE].getMean(), p);
			ignore[ANGLE] = true;
			fitFunction = FitFunction.FREE_CIRCULAR.ordinal();
			if (settings.updatePreferences)
			{
				config.getFitConfiguration().setFitFunction(fitFunction);
			}
			tryAgain = true;
		}
		else
			debug("  NOTE: Angle is significant: %g !~ 0.0 (p=%g)", sampleNew[ANGLE].getMean(), p);
		return tryAgain;
	}

	private boolean checkXYSignificance(boolean[] identical)
	{
		boolean tryAgain = false;
		if (identical[XY])
		{
			log("NOTE: X-width and Y-width are not significantly different: %g ~ %g => Re-run with circular function",
					sampleNew[X].getMean(), sampleNew[Y].getMean());
			fitFunction = FitFunction.CIRCULAR.ordinal();
			if (settings.updatePreferences)
			{
				config.getFitConfiguration().setFitFunction(fitFunction);
			}
			tryAgain = true;
		}
		return tryAgain;
	}

	private void getP(int i, double[] p, boolean[] identical) throws IllegalArgumentException
	{
		getP(sampleNew[i], sampleOld[i], i, p, identical);
	}

	private void getP(StatisticalSummary sample1, StatisticalSummary sample2, int i, double[] p, boolean[] identical)
	{
		if (sample1.getN() < 2)
			return;

		// The number returned is the smallest significance level at which one can reject the null 
		// hypothesis that the mean of the paired differences is 0 in favor of the two-sided alternative 
		// that the mean paired difference is not equal to 0. For a one-sided test, divide the returned value by 2
		p[i] = TestUtils.tTest(sample1, sample2);
		identical[i] = (p[i] > settings.pValue);
	}

	private void getPairedP(DescriptiveStatistics sample1, DescriptiveStatistics sample2, int i, double[] p,
			boolean[] identical) throws IllegalArgumentException
	{
		if (sample1.getN() < 2)
			return;

		// The number returned is the smallest significance level at which one can reject the null 
		// hypothesis that the mean of the paired differences is 0 in favor of the two-sided alternative 
		// that the mean paired difference is not equal to 0. For a one-sided test, divide the returned value by 2
		p[i] = TestUtils.pairedTTest(sample1.getValues(), sample2.getValues());
		identical[i] = (p[i] > settings.pValue);
	}

	private boolean calculateStatistics(PeakFit fitter, double[] params, double[] params_dev)
	{
		debug("  Fitting PSF");

		swapStatistics();

		// Create the fit engine using the PeakFit plugin
		FitConfiguration fitConfig = config.getFitConfiguration();
		fitConfig.setInitialAngle((float) params[0]);
		fitConfig.setInitialPeakStdDev0((float) params[1]);
		fitConfig.setInitialPeakStdDev1((float) params[2]);

		ImageStack stack = imp.getImageStack();
		Rectangle roi = stack.getProcessor(1).getRoi();

		fitter.initialiseImage(new IJImageSource(imp), roi, true);
		fitter.addPeakResults(this);
		fitter.initialiseFitting();

		FitEngine engine = fitter.createFitEngine();

		// Use random slices
		int[] slices = new int[stack.getSize()];
		for (int i = 0; i < slices.length; i++)
			slices[i] = i + 1;
		Random rand = new Random();
		rand.shuffle(slices);

		// Use multi-threaded code for speed
		int i;
		for (i = 0; i < slices.length; i++)
		{
			int slice = slices[i];
			//debug("  Processing slice = %d\n", slice);

			ImageProcessor ip = stack.getProcessor(slice);
			ip.setRoi(roi); // stack processor does not set the bounds required by ImageConverter
			FitJob job = new FitJob(slice, ImageConverter.getData(ip), roi);
			engine.run(job);

			if (sampleSizeReached())
			{
				break;
			}
		}
		// Wait until we have enough results
		while (!sampleSizeReached() && !engine.isQueueEmpty())
		{
			try
			{
				Thread.sleep(10);
			}
			catch (InterruptedException e)
			{
				break;
			}
		}
		// End now if we have enough samples
		engine.end(sampleSizeReached());

		// This count will be an over-estimate given that the provider is ahead of the consumer
		// in this multi-threaded system
		debug("  Processed %d/%d slices (%d peaks)", i, slices.length, size());

		setParams(ANGLE, params, params_dev, sampleNew[ANGLE]);
		setParams(X, params, params_dev, sampleNew[X]);
		setParams(Y, params, params_dev, sampleNew[Y]);

		if (size() < 2)
		{
			log("ERROR: Insufficient number of fitted peaks, terminating ...");
			return false;
		}
		return true;
	}

	private void setParams(int i, double[] params, double[] params_dev, StatisticalSummary sample)
	{
		if (sample.getN() > 0)
		{
			params[i] = sample.getMean();
			params_dev[i] = sample.getStandardDeviation();
		}
	}

	private void swapStatistics()
	{
		sampleOld[ANGLE] = sampleNew[ANGLE];
		sampleOld[X] = sampleNew[X];
		sampleOld[Y] = sampleNew[Y];
	}

	private PeakFit createFitter()
	{
		ResultsSettings resultsSettings = new ResultsSettings();
		resultsSettings.setResultsTable(ResultsTable.NONE);
		resultsSettings.showDeviations = false;
		resultsSettings.logProgress = false;
		resultsSettings.setResultsImage(0);
		resultsSettings.resultsDirectory = null;
		PeakFit fitter = new PeakFit(config, resultsSettings);
		return fitter;
	}

	/**
	 * Create the result window (if it is not available)
	 */
	private void createResultsWindow()
	{
		if (resultsWindow == null || !resultsWindow.isShowing())
		{
			resultsWindow = new TextWindow(TITLE + " Results", createResultsHeader(), "", 900, 300);
		}
	}

	private String createResultsHeader()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Iteration\t");
		sb.append("N-peaks\t");
		sb.append("Angle\t");
		sb.append("+/-\t");
		sb.append("p(Angle same)\t");
		sb.append("X width\t");
		sb.append("+/-\t");
		sb.append("p(X same)\t");
		sb.append("Y width\t");
		sb.append("+/-\t");
		sb.append("p(Y same)\t");
		sb.append("p(XY same)\t");
		return sb.toString();
	}

	private boolean addToResultTable(int iteration, int n, double[] params, double[] params_dev, double[] p)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(iteration).append("\t").append(n).append("\t");
		for (int i = 0; i < 3; i++)
		{
			sb.append(params[i]).append("\t");
			sb.append(params_dev[i]).append("\t");
			sb.append(p[i]).append("\t");
		}
		sb.append(p[XY]).append("\t");
		resultsWindow.append(sb.toString());

		if (params[X] > imp.getWidth() || params[Y] > imp.getWidth())
		{
			log("ERROR: Bad width estimation (try altering the peak validation parameters), terminating ...");
			return false;
		}
		return true;
	}

	private void debug(String format, Object... args)
	{
		if (settings.debugPSFEstimator)
			log(format, args);
	}

	private void log(String format, Object... args)
	{
		IJ.log(String.format(format, args));
	}

	public void begin()
	{
		sampleNew[ANGLE] = new DescriptiveStatistics();
		sampleNew[X] = new DescriptiveStatistics();
		sampleNew[Y] = new DescriptiveStatistics();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.results.PeakResults#add(int, int, int, float, double, float, float[], float[])
	 */
	public synchronized void add(int peak, int origX, int origY, float origValue, double chiSquared, float noise,
			float[] params, float[] paramsStdDev)
	{
		if (!sampleSizeReached())
		{
			if (!ignore[ANGLE])
				sampleNew[ANGLE].addValue(params[2]);
			//if (!ignore[X])
			sampleNew[X].addValue(params[5]);
			if (!ignore[Y])
				sampleNew[Y].addValue(params[6]);
		}
	}

	private boolean sampleSizeReached()
	{
		return size() >= settings.numberOfPeaks;
	}

	public synchronized void addAll(Collection<PeakResult> results)
	{
		for (PeakResult result : results)
			add(result.peak, result.origX, result.origY, result.origValue, result.error, result.noise, result.params,
					result.paramsStdDev);
	}

	public int size()
	{
		return (int) sampleNew[X].getN();
	}

	public void end()
	{
		// Do nothing
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.utils.fitting.results.PeakResults#isActive()
	 */
	public boolean isActive()
	{
		return true;
	}

	public void setSource(String source)
	{
		// Ignored		
	}

	public ImageSource getSource()
	{
		// Ignored		
		return null;
	}

	public void setBounds(Rectangle bounds)
	{
		// Ignored		
	}

	public Rectangle getBounds()
	{
		// Ignored		
		return null;
	}

	public void setCalibration(Calibration calibration)
	{
		// Ignored
	}

	public Calibration getCalibration()
	{
		// Ignored
		return null;
	}

	public void setConfiguration(String configuration)
	{
		// Ignored		
	}

	public String getConfiguration()
	{
		// Ignored
		return null;
	}

	public void copySettings(PeakResults peakResults)
	{
		// Ignored
	}

	public void setSource(ImageSource source)
	{
		// Ignored
	}

	public String getName()
	{
		// Ignored
		return null;
	}

	public void setName(String name)
	{
		// Ignored
	}
}
