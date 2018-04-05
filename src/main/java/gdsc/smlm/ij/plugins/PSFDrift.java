package gdsc.smlm.ij.plugins;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Label;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.analysis.interpolation.LoessInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.apache.commons.math3.random.RandomDataGenerator;
import org.apache.commons.math3.random.Well19937c;

import gdsc.core.ij.Utils;
import gdsc.core.utils.Maths;
import gdsc.core.utils.SimpleArrayUtils;
import gdsc.core.utils.Statistics;
import gdsc.core.utils.TurboList;
import gdsc.smlm.data.config.FitProtos.FitEngineSettings;
import gdsc.smlm.data.config.FitProtosHelper;
import gdsc.smlm.data.config.PSFProtos.ImagePSF;
import gdsc.smlm.data.config.PSFProtos.Offset;
import gdsc.smlm.data.config.PSFProtosHelper;
import gdsc.smlm.engine.FitConfiguration;

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

import gdsc.smlm.engine.FitEngineConfiguration;
import gdsc.smlm.fitting.FitStatus;
import gdsc.smlm.fitting.FunctionSolver;
import gdsc.smlm.fitting.Gaussian2DFitter;
import gdsc.smlm.function.gaussian.Gaussian2DFunction;
import gdsc.smlm.ij.IJImageSource;
import gdsc.smlm.ij.settings.ImagePSFHelper;
import gdsc.smlm.ij.settings.SettingsManager;
import gdsc.smlm.model.ImagePSFModel;
import gnu.trove.list.array.TDoubleArrayList;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.ExtendedGenericDialog;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.NonBlockingExtendedGenericDialog;
import ij.gui.Plot;
import ij.gui.Plot2;
import ij.gui.PlotWindow;
import ij.plugin.PlugIn;
import ij.plugin.WindowOrganiser;

/**
 * Produces an drift curve for a PSF image using fitting.
 * <p>
 * The input images must be a z-stack of a PSF. These can be produced using the PSFCreator plugin.
 */
public class PSFDrift implements PlugIn
{
	private final static String TITLE = "PSF Drift";

	private static String title = "";
	private static boolean useOffset = false;
	private static double scale = 10;
	private static double zDepth = 1000;
	private static int gridSize = 10;
	private static double recallLimit = 0.25;
	private static int regionSize = 5;
	private static boolean backgroundFitting = false;
	private static boolean offsetFitting = true;
	private static double startOffset = 0.5;
	private static boolean comFitting = true;
	private static boolean useSampling = false;
	private static double photons = 1000;
	private static double photonLimit = 0.25;
	private static int positionsToAverage = 5;
	private static double smoothing = 0.1;

	private static boolean updateCentre = true;
	private static boolean updateHWHM = true;

	private ImagePlus imp;
	private ImagePSF psfSettings;
	private static FitConfiguration fitConfig;

	static
	{
		// Initialise for fitting
		fitConfig = new FitConfiguration();
	}

	private int centrePixel;
	private int total;
	private double[][] results;

	private int[] idList = new int[3];
	private int idCount = 0;

	private class Job
	{
		final int z;
		final double cx, cy;
		final int index;

		public Job(int z, double cx, double cy, int index)
		{
			this.z = z;
			this.cx = cx;
			this.cy = cy;
			this.index = index;
		}

		public Job()
		{
			this(0, 0, 0, -1);
		}

		@Override
		public String toString()
		{
			return String.format("z=%d, cx=%.2f, cy=%.2f", z, cx, cy);
		}
	}

	/**
	 * Used to allow multi-threading of the fitting method
	 */
	private class Worker implements Runnable
	{
		volatile boolean finished = false;
		final ImagePSFModel psf;
		final BlockingQueue<Job> jobs;
		final FitConfiguration fitConfig2;
		final double sx, sy, a;
		final double[][] xy;
		final int w;
		final int w2;
		final RandomDataGenerator random;

		private double[] lb, ub = null;
		private double[] lc, uc = null;

		public Worker(BlockingQueue<Job> jobs, ImagePSFModel psf, int width, FitConfiguration fitConfig)
		{
			this.jobs = jobs;
			this.psf = psf.copy();
			this.fitConfig2 = fitConfig.clone();
			sx = fitConfig.getInitialXSD();
			sy = fitConfig.getInitialYSD();
			a = psfSettings.getPixelSize() * scale;
			xy = PSFDrift.getStartPoints(PSFDrift.this);
			w = width;
			w2 = w * w;
			if (useSampling)
				random = new RandomDataGenerator(new Well19937c());
			else
				random = null;

			createBounds();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Runnable#run()
		 */
		public void run()
		{
			try
			{
				while (true)
				{
					Job job = jobs.take();
					if (job == null || job.index < 0)
						break;
					if (!finished)
						// Only run if not finished to allow queue to be emptied
						run(job);
				}
			}
			catch (InterruptedException e)
			{
				System.out.println(e.toString());
				throw new RuntimeException(e);
			}
			finally
			{
				finished = true;
			}
		}

		private void run(Job job)
		{
			if (IJ.escapePressed())
			{
				finished = true;
				return;
			}

			double cx = centrePixel + job.cx;
			double cy = centrePixel + job.cy;

			// Draw the PSF
			double[] data = new double[w2];
			if (useSampling)
			{
				int p = (int) random.nextPoisson(photons);
				psf.sample3D(data, w, w, p, cx, cy, job.z);
			}
			else
				psf.create3D(data, w, w, photons, cx, cy, job.z, false);

			//Utils.display("Data", data, w, w);

			// Fit the PSF. Do this from different start positions.

			// Get the background and signal estimate
			final double b = (backgroundFitting) ? Gaussian2DFitter.getBackground(data, w, w, 1) : 0;
			final double signal = BenchmarkFit.getSignal(data, b);

			if (comFitting)
			{
				// Get centre-of-mass estimate, then subtract the centre that will be added later 
				BenchmarkFit.getCentreOfMass(data, w, w, xy[xy.length - 1]);
				xy[xy.length - 1][0] -= cx;
				xy[xy.length - 1][1] -= cy;
			}

			double[] initialParams = new double[1 + Gaussian2DFunction.PARAMETERS_PER_PEAK];
			initialParams[Gaussian2DFunction.BACKGROUND] = b;
			initialParams[Gaussian2DFunction.SIGNAL] = signal;
			initialParams[Gaussian2DFunction.X_SD] = sx;
			initialParams[Gaussian2DFunction.Y_SD] = sy;

			int resultPosition = job.index;
			for (double[] centre : xy)
			{
				// Do fitting
				final double[] params = initialParams.clone();
				params[Gaussian2DFunction.X_POSITION] = cx + centre[0];
				params[Gaussian2DFunction.Y_POSITION] = cy + centre[1];
				fitConfig2.initialise(1, w, w, params);
				FunctionSolver solver = fitConfig2.getFunctionSolver();
				if (solver.isBounded())
					setBounds(solver);
				else if (solver.isConstrained())
					setConstraints(solver);
				final FitStatus status = solver.fit(data, null, params, null);
				// Account for 0.5 pixel offset during fitting
				params[Gaussian2DFunction.X_POSITION] += 0.5;
				params[Gaussian2DFunction.Y_POSITION] += 0.5;
				if (isValid(status, params, w))
				{
					// XXX Decide what results are needed for analysis
					// Store all the results for later analysis
					//results[resultPosition] = params;
					// Store only the drift
					results[resultPosition] = new double[] { a * (params[Gaussian2DFunction.X_POSITION] - cx),
							a * (params[Gaussian2DFunction.Y_POSITION] - cy), job.z };
					//System.out.printf("Fit " + job + ". %f,%f\n", results[resultPosition][0],
					//		results[resultPosition][1]);
				}
				else
				{
					//System.out.println("Failed to fit " + job + ". " + status);
				}
				resultPosition += total;
			}
		}

		private void setBounds(FunctionSolver solver)
		{
			solver.setBounds(lb, ub);
		}

		private void createBounds()
		{
			if (ub == null)
			{
				ub = new double[1 + Gaussian2DFunction.PARAMETERS_PER_PEAK];
				lb = new double[ub.length];

				// Background could be zero so always have an upper limit
				ub[Gaussian2DFunction.BACKGROUND] = 1;
				lb[Gaussian2DFunction.SIGNAL] = photons * photonLimit;
				ub[Gaussian2DFunction.SIGNAL] = photons * 2;
				ub[Gaussian2DFunction.X_POSITION] = w;
				ub[Gaussian2DFunction.Y_POSITION] = w;
				lb[Gaussian2DFunction.ANGLE] = -Math.PI;
				ub[Gaussian2DFunction.ANGLE] = Math.PI;
				lb[Gaussian2DFunction.Z_POSITION] = Double.NEGATIVE_INFINITY;
				ub[Gaussian2DFunction.Z_POSITION] = Double.POSITIVE_INFINITY;
				double wf = 1.5;
				lb[Gaussian2DFunction.X_SD] = sx / wf;
				ub[Gaussian2DFunction.X_SD] = sx * 5;
				lb[Gaussian2DFunction.Y_SD] = sy / wf;
				ub[Gaussian2DFunction.Y_SD] = sy * 5;
			}
		}

		private void setConstraints(FunctionSolver solver)
		{
			if (uc == null)
			{
				lc = new double[1 + Gaussian2DFunction.PARAMETERS_PER_PEAK];
				uc = new double[uc.length];
				Arrays.fill(lc, Float.NEGATIVE_INFINITY);
				Arrays.fill(uc, Float.POSITIVE_INFINITY);
				lc[Gaussian2DFunction.BACKGROUND] = 0;
				lc[Gaussian2DFunction.SIGNAL] = 0;
			}
			solver.setConstraints(lc, uc);
		}

		private boolean isValid(FitStatus status, double[] params, int size)
		{
			if (status != FitStatus.OK)
			{
				//System.out.println("Failed to fit: " + status);
				return false;
			}

			// Reject fits that are outside the bounds of the data
			if (params[Gaussian2DFunction.X_POSITION] < 0 || params[Gaussian2DFunction.Y_POSITION] < 0 ||
					params[Gaussian2DFunction.X_POSITION] > size || params[Gaussian2DFunction.Y_POSITION] > size)
			{
				//System.out.printf("Failed to fit position: x=%f,y=%f\n", params[Gaussian2DFunction.X_POSITION],
				//		params[Gaussian2DFunction.Y_POSITION]);
				return false;
			}

			// Reject fits that do not correctly estimate the signal
			if (params[Gaussian2DFunction.SIGNAL] < lb[Gaussian2DFunction.SIGNAL] ||
					params[Gaussian2DFunction.SIGNAL] > ub[Gaussian2DFunction.SIGNAL])
			{
				//System.out.printf("Failed to fit signal: %f\n", params[Gaussian2DFunction.SIGNAL]);
				return false;
			}

			// Reject fits that have a background too far from zero
			// TODO - configure this better
			//if (params[Gaussian2DFunction.BACKGROUND] < -10 || params[Gaussian2DFunction.BACKGROUND] > 10)
			//{
			//	return false;
			//}

			// Q. Should we do width bounds checking?
			if (fitConfig2.isXSDFitting())
			{
				if (params[Gaussian2DFunction.X_SD] < lb[Gaussian2DFunction.X_SD] ||
						params[Gaussian2DFunction.X_SD] > ub[Gaussian2DFunction.X_SD])
				{
					//System.out.printf("Failed to fit x-width: %f\n", params[Gaussian2DFunction.X_SD]);
					return false;
				}
			}
			if (fitConfig2.isYSDFitting())
			{
				if (params[Gaussian2DFunction.Y_SD] < lb[Gaussian2DFunction.Y_SD] ||
						params[Gaussian2DFunction.Y_SD] > ub[Gaussian2DFunction.Y_SD])
				{
					//System.out.printf("Failed to fit y-width: %f\n", params[Gaussian2DFunction.Y_SD]);
					return false;
				}
			}

			return true;
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	public void run(String arg)
	{
		SMLMUsageTracker.recordPlugin(this.getClass(), arg);

		if ("hwhm".equals(arg))
		{
			showHWHM();
			return;
		}

		// Build a list of suitable images
		List<String> titles = createImageList(true);

		if (titles.isEmpty())
		{
			IJ.error(TITLE, "No suitable PSF images");
			return;
		}

		ExtendedGenericDialog gd = new ExtendedGenericDialog(TITLE);
		gd.addMessage("Select the input PSF image");
		gd.addChoice("PSF", titles.toArray(new String[titles.size()]), title);
		gd.addCheckbox("Use_offset", useOffset);
		gd.addNumericField("Scale", scale, 2);
		gd.addNumericField("z_depth", zDepth, 2, 6, "nm");
		gd.addNumericField("Grid_size", gridSize, 0);
		gd.addSlider("Recall_limit", 0.01, 1, recallLimit);

		gd.addSlider("Region_size", 2, 20, regionSize);
		gd.addCheckbox("Background_fitting", backgroundFitting);
		PeakFit.addPSFOptions(gd, fitConfig);
		gd.addChoice("Fit_solver", SettingsManager.getFitSolverNames(), fitConfig.getFitSolver().ordinal());
		// We need these to set bounds for any bounded fitters
		gd.addSlider("Min_width_factor", 0, 0.99, fitConfig.getMinWidthFactor());
		gd.addSlider("Width_factor", 1, 4.5, fitConfig.getMaxWidthFactor());
		gd.addCheckbox("Offset_fit", offsetFitting);
		gd.addNumericField("Start_offset", startOffset, 3);
		gd.addCheckbox("Include_CoM_fit", comFitting);
		gd.addCheckbox("Use_sampling", useSampling);
		gd.addNumericField("Photons", photons, 0);
		gd.addSlider("Photon_limit", 0, 1, photonLimit);
		gd.addSlider("Smoothing", 0, 0.5, smoothing);

		gd.showDialog();
		if (gd.wasCanceled())
			return;

		title = gd.getNextChoice();
		useOffset = gd.getNextBoolean();
		scale = gd.getNextNumber();
		zDepth = gd.getNextNumber();
		gridSize = (int) gd.getNextNumber();
		recallLimit = gd.getNextNumber();
		regionSize = (int) Math.abs(gd.getNextNumber());
		backgroundFitting = gd.getNextBoolean();
		fitConfig.setPSFType(PeakFit.getPSFTypeValues()[gd.getNextChoiceIndex()]);
		fitConfig.setFitSolver(gd.getNextChoiceIndex());
		fitConfig.setMinWidthFactor(gd.getNextNumber());
		fitConfig.setWidthFactor(gd.getNextNumber());
		offsetFitting = gd.getNextBoolean();
		startOffset = Math.abs(gd.getNextNumber());
		comFitting = gd.getNextBoolean();
		useSampling = gd.getNextBoolean();
		photons = Math.abs(gd.getNextNumber());
		photonLimit = Math.abs(gd.getNextNumber());
		smoothing = Math.abs(gd.getNextNumber());

		gd.collectOptions();

		if (!comFitting && !offsetFitting)
		{
			IJ.error(TITLE, "No initial fitting positions");
			return;
		}

		if (regionSize < 1)
			regionSize = 1;

		if (gd.invalidNumber())
			return;

		imp = WindowManager.getImage(title);
		if (imp == null)
		{
			IJ.error(TITLE, "No PSF image for image: " + title);
			return;
		}
		psfSettings = getPSFSettings(imp);
		if (psfSettings == null)
		{
			IJ.error(TITLE, "No PSF settings for image: " + title);
			return;
		}

		// Configure the fit solver. We must wrap the settings with a 
		// FitEngineConfiguration to pass to the PeakFit method
		FitEngineSettings fitEngineSettings = FitProtosHelper.defaultFitEngineSettings;
		FitEngineConfiguration config = new FitEngineConfiguration(fitEngineSettings,
				SettingsManager.readCalibration(0), PSFProtosHelper.defaultOneAxisGaussian2DPSF);
		config.getFitConfiguration().setFitSettings(fitConfig.getFitSettings());
		if (!PeakFit.configurePSFModel(config))
			return;
		if (!PeakFit.configureFitSolver(config, IJImageSource.getBounds(imp), null, PeakFit.FLAG_NO_SAVE))
			return;
		fitConfig = config.getFitConfiguration();

		computeDrift();
	}

	private void computeDrift()
	{
		// Create a grid of XY offset positions between 0-1 for PSF insert
		final double[] grid = new double[gridSize];
		for (int i = 0; i < grid.length; i++)
			grid[i] = (double) i / gridSize;

		// Configure fitting region
		final int w = 2 * regionSize + 1;
		centrePixel = w / 2;

		// Check region size using the image PSF
		double newPsfWidth = (double) imp.getWidth() / scale;
		if (Math.ceil(newPsfWidth) > w)
			Utils.log(TITLE + ": Fitted region size (%d) is smaller than the scaled PSF (%.1f)", w, newPsfWidth);

		// Create robust PSF fitting settings
		final double a = psfSettings.getPixelSize() * scale;
		final double sa = PSFCalculator.squarePixelAdjustment(
				psfSettings.getPixelSize() * (psfSettings.getFwhm() / Gaussian2DFunction.SD_TO_FWHM_FACTOR), a);
		fitConfig.setInitialPeakStdDev(sa / a);
		fitConfig.setBackgroundFitting(backgroundFitting);
		fitConfig.setNotSignalFitting(false);
		fitConfig.setComputeDeviations(false);
		fitConfig.setDisableSimpleFilter(true);

		// Create the PSF over the desired z-depth
		int depth = (int) Math.round(zDepth / psfSettings.getPixelDepth());
		int startSlice = psfSettings.getCentreImage() - depth;
		int endSlice = psfSettings.getCentreImage() + depth;
		int nSlices = imp.getStackSize();
		startSlice = (startSlice < 1) ? 1 : (startSlice > nSlices) ? nSlices : startSlice;
		endSlice = (endSlice < 1) ? 1 : (endSlice > nSlices) ? nSlices : endSlice;

		ImagePSFModel psf = createImagePSF(startSlice, endSlice, scale);

		int minz = startSlice - psfSettings.getCentreImage();
		int maxz = endSlice - psfSettings.getCentreImage();

		final int nZ = maxz - minz + 1;
		final int gridSize2 = grid.length * grid.length;
		total = nZ * gridSize2;

		// Store all the fitting results
		int nStartPoints = getNumberOfStartPoints();
		results = new double[total * nStartPoints][];

		// TODO - Add ability to iterate this, adjusting the current offset in the PSF
		// each iteration

		// Create a pool of workers
		int nThreads = Prefs.getThreads();
		BlockingQueue<Job> jobs = new ArrayBlockingQueue<Job>(nThreads * 2);
		List<Worker> workers = new LinkedList<Worker>();
		List<Thread> threads = new LinkedList<Thread>();
		for (int i = 0; i < nThreads; i++)
		{
			Worker worker = new Worker(jobs, psf, w, fitConfig);
			Thread t = new Thread(worker);
			workers.add(worker);
			threads.add(t);
			t.start();
		}

		// Fit 
		Utils.showStatus("Fitting ...");
		final int step = Utils.getProgressInterval(total);
		outer: for (int z = minz, i = 0; z <= maxz; z++)
		{
			for (int x = 0; x < grid.length; x++)
				for (int y = 0; y < grid.length; y++, i++)
				{
					if (IJ.escapePressed())
					{
						break outer;
					}
					put(jobs, new Job(z, grid[x], grid[y], i));
					if (i % step == 0)
					{
						IJ.showProgress(i, total);
					}
				}
		}

		// If escaped pressed then do not need to stop the workers, just return
		if (Utils.isInterrupted())
		{
			IJ.showProgress(1);
			return;
		}

		// Finish all the worker threads by passing in a null job
		for (int i = 0; i < threads.size(); i++)
		{
			put(jobs, new Job());
		}

		// Wait for all to finish
		for (int i = 0; i < threads.size(); i++)
		{
			try
			{
				threads.get(i).join();
			}
			catch (InterruptedException e)
			{
				e.printStackTrace();
			}
		}
		threads.clear();

		IJ.showProgress(1);
		IJ.showStatus("");

		// Plot the average and SE for the drift curve
		// Plot the recall
		double[] zPosition = new double[nZ];
		double[] avX = new double[nZ];
		double[] seX = new double[nZ];
		double[] avY = new double[nZ];
		double[] seY = new double[nZ];
		double[] recall = new double[nZ];
		for (int z = minz, i = 0; z <= maxz; z++, i++)
		{
			Statistics statsX = new Statistics();
			Statistics statsY = new Statistics();
			for (int s = 0; s < nStartPoints; s++)
			{
				int resultPosition = i * gridSize2 + s * total;
				final int endResultPosition = resultPosition + gridSize2;
				while (resultPosition < endResultPosition)
				{
					if (results[resultPosition] != null)
					{
						statsX.add(results[resultPosition][0]);
						statsY.add(results[resultPosition][1]);
					}
					resultPosition++;
				}
			}
			zPosition[i] = z * psfSettings.getPixelDepth();
			avX[i] = statsX.getMean();
			seX[i] = statsX.getStandardError();
			avY[i] = statsY.getMean();
			seY[i] = statsY.getStandardError();
			recall[i] = (double) statsX.getN() / (nStartPoints * gridSize2);
		}

		// Find the range from the z-centre above the recall limit 
		int centre = 0;
		for (int slice = startSlice, i = 0; slice <= endSlice; slice++, i++)
		{
			if (slice == psfSettings.getCentreImage())
			{
				centre = i;
				break;
			}
		}
		if (recall[centre] < recallLimit)
			return;
		int start = centre, end = centre;
		for (int i = centre; i-- > 0;)
		{
			if (recall[i] < recallLimit)
				break;
			start = i;
		}
		for (int i = centre; ++i < recall.length;)
		{
			if (recall[i] < recallLimit)
				break;
			end = i;
		}

		int iterations = 1;
		LoessInterpolator loess = null;
		if (smoothing > 0)
			loess = new LoessInterpolator(smoothing, iterations);

		double[][] smoothx = displayPlot("Drift X", "X (nm)", zPosition, avX, seX, loess, start, end);
		double[][] smoothy = displayPlot("Drift Y", "Y (nm)", zPosition, avY, seY, loess, start, end);
		displayPlot("Recall", "Recall", zPosition, recall, null, null, start, end);

		WindowOrganiser wo = new WindowOrganiser();
		wo.tileWindows(idList);

		// Ask the user if they would like to store them in the image
		GenericDialog gd = new GenericDialog(TITLE);
		gd.enableYesNoCancel();
		gd.hideCancelButton();
		startSlice = psfSettings.getCentreImage() - (centre - start);
		endSlice = psfSettings.getCentreImage() + (end - centre);
		gd.addMessage(String.format("Save the drift to the PSF?\n \nSlices %d (%s nm) - %d (%s nm) above recall limit",
				startSlice, Utils.rounded(zPosition[start]), endSlice, Utils.rounded(zPosition[end])));
		gd.addMessage("Optionally average the end points to set drift outside the limits.\n(Select zero to ignore)");
		gd.addSlider("Number_of_points", 0, 10, positionsToAverage);
		gd.showDialog();
		if (gd.wasOKed())
		{
			positionsToAverage = Math.abs((int) gd.getNextNumber());
			Map<Integer, Offset> oldOffset = psfSettings.getOffsetsMap();
			boolean useOldOffset = useOffset && !oldOffset.isEmpty();
			TurboList<double[]> offset = new TurboList<double[]>();
			final double pitch = psfSettings.getPixelSize();
			int j = 0;
			for (int i = start, slice = startSlice; i <= end; slice++, i++)
			{
				j = findCentre(zPosition[i], smoothx, j);
				if (j == -1)
				{
					Utils.log("Failed to find the offset for depth %.2f", zPosition[i]);
					continue;
				}
				// The offset should store the difference to the centre in pixels so divide by the pixel pitch
				double cx = smoothx[1][j] / pitch;
				double cy = smoothy[1][j] / pitch;
				if (useOldOffset)
				{
					Offset o = oldOffset.get(slice);
					if (o != null)
					{
						cx += o.getCx();
						cy += o.getCy();
					}
				}
				offset.add(new double[] { slice, cx, cy });
			}
			addMissingOffsets(startSlice, endSlice, nSlices, offset);
			Offset.Builder offsetBuilder = Offset.newBuilder();
			ImagePSF.Builder imagePSFBuilder = psfSettings.toBuilder();
			for (double[] o : offset)
			{
				int slice = (int) o[0];
				offsetBuilder.setCx(o[1]);
				offsetBuilder.setCy(o[2]);
				imagePSFBuilder.putOffsets(slice, offsetBuilder.build());
			}
			imagePSFBuilder.putNotes(TITLE,
					String.format("Solver=%s, Region=%d", PeakFit.getSolverName(fitConfig), regionSize));
			imp.setProperty("Info", ImagePSFHelper.toString(imagePSFBuilder));
		}
	}

	private int findCentre(double d, double[][] smoothx, int i)
	{
		while (i < smoothx[0].length)
		{
			if (smoothx[0][i] == d)
				return i;
			i++;
		}
		return -1;
	}

	private void addMissingOffsets(int startSlice, int endSlice, int nSlices, TurboList<double[]> offset)
	{
		// Add an offset for the remaining slices 
		if (positionsToAverage > 0)
		{
			double cx = 0, cy = 0;
			int n = 0;
			for (int i = 0; n < positionsToAverage && i < offset.size(); i++)
			{
				cx += offset.get(i)[1];
				cy += offset.get(i)[2];
				n++;
			}
			cx /= n;
			cy /= n;
			double cx2 = 0, cy2 = 0;
			double n2 = 0;
			for (int i = offset.size(); n2 < positionsToAverage && i-- > 0;)
			{
				cx2 += offset.get(i)[1];
				cy2 += offset.get(i)[2];
				n2++;
			}
			cx2 /= n2;
			cy2 /= n2;

			for (int slice = 1; slice < startSlice; slice++)
				offset.add(new double[] { slice, cx, cy });
			for (int slice = endSlice + 1; slice <= nSlices; slice++)
				offset.add(new double[] { slice, cx2, cy2 });
			Collections.sort(offset, new Comparator<double[]>()
			{
				public int compare(double[] arg0, double[] arg1)
				{
					if (arg0[0] < arg1[0])
						return -1;
					if (arg0[0] > arg1[0])
						return 1;
					return 0;
				}
			});
		}
	}

	private double[][] displayPlot(String title, String yLabel, double[] x, double[] y, double[] se,
			LoessInterpolator loess, int start, int end)
	{
		// Extract non NaN numbers
		double[] newX = new double[x.length];
		double[] newY = new double[x.length];
		int c = 0;
		for (int i = 0; i < x.length; i++)
			if (!Double.isNaN(y[i]))
			{
				newX[c] = x[i];
				newY[c] = y[i];
				c++;
			}
		newX = Arrays.copyOf(newX, c);
		newY = Arrays.copyOf(newY, c);

		title = TITLE + " " + title;
		Plot2 plot = new Plot2(title, "z (nm)", yLabel);
		double[] limitsx = Maths.limits(x);
		double[] limitsy = new double[2];
		if (se != null)
		{
			if (c > 0)
			{
				limitsy = new double[] { newY[0] - se[0], newY[0] + se[0] };
				for (int i = 1; i < newY.length; i++)
				{
					limitsy[0] = Maths.min(limitsy[0], newY[i] - se[i]);
					limitsy[1] = Maths.max(limitsy[1], newY[i] + se[i]);
				}
			}
		}
		else
		{
			if (c > 0)
				limitsy = Maths.limits(newY);
		}
		double rangex = Math.max(0.05 * (limitsx[1] - limitsx[0]), 0.1);
		double rangey = Math.max(0.05 * (limitsy[1] - limitsy[0]), 0.1);
		plot.setLimits(limitsx[0] - rangex, limitsx[1] + rangex, limitsy[0] - rangey, limitsy[1] + rangey);

		if (loess == null)
		{
			addPoints(plot, Plot.LINE, newX, newY, x[start], x[end]);
		}
		else
		{
			addPoints(plot, Plot.DOT, newX, newY, x[start], x[end]);
			newY = loess.smooth(newX, newY);
			addPoints(plot, Plot.LINE, newX, newY, x[start], x[end]);
		}
		if (se != null)
		{
			plot.setColor(Color.magenta);
			for (int i = 0; i < x.length; i++)
			{
				if (!Double.isNaN(y[i]))
					plot.drawLine(x[i], y[i] - se[i], x[i], y[i] + se[i]);
			}

			// Draw the start and end lines for the valid range
			plot.setColor(Color.green);
			plot.drawLine(x[start], limitsy[0], x[start], limitsy[1]);
			plot.drawLine(x[end], limitsy[0], x[end], limitsy[1]);
		}
		else
		{
			// draw a line for the recall limit
			plot.setColor(Color.magenta);
			plot.drawLine(limitsx[0] - rangex, recallLimit, limitsx[1] + rangex, recallLimit);
		}
		PlotWindow pw = Utils.display(title, plot);
		if (Utils.isNewWindow())
			idList[idCount++] = pw.getImagePlus().getID();

		return new double[][] { newX, newY };
	}

	private void addPoints(Plot plot, int shape, double[] x, double[] y, double lower, double upper)
	{
		if (x.length == 0)
			return;
		// Split the line into three:
		// 1. All points up to and including lower
		// 2. All points between lower and upper inclusive
		// 3. All point from upper upwards

		// Plot the main curve first 
		addPoints(plot, shape, x, y, lower, upper, Color.blue);
		// Then plot the others
		addPoints(plot, shape, x, y, x[0], lower, Color.red);
		addPoints(plot, shape, x, y, upper, x[x.length - 1], Color.red);
	}

	private void addPoints(Plot plot, int shape, double[] x, double[] y, double lower, double upper, Color color)
	{
		double[] x2 = new double[x.length];
		double[] y2 = new double[y.length];
		int c = 0;
		for (int i = 0; i < x.length; i++)
		{
			if (x[i] >= lower && x[i] <= upper)
			{
				x2[c] = x[i];
				y2[c] = y[i];
				c++;
			}
		}
		if (c == 0)
			return;
		x2 = Arrays.copyOf(x2, c);
		y2 = Arrays.copyOf(y2, c);
		plot.setColor(color);
		plot.addPoints(x2, y2, shape);
	}

	private ImagePSFModel createImagePSF(int lower, int upper, double scale)
	{
		int zCentre = psfSettings.getCentreImage();

		final double unitsPerPixel = 1.0 / scale;
		final double unitsPerSlice = 1; // So we can move from -depth to depth

		// Extract data uses index not slice number as arguments so subtract 1
		double noiseFraction = 1e-3;
		float[][] image = CreateData.extractImageStack(imp, lower - 1, upper - 1);
		ImagePSFModel model = new ImagePSFModel(image, zCentre - lower, unitsPerPixel, unitsPerSlice,
				psfSettings.getFwhm(), noiseFraction);

		// Add the calibrated centres
		Map<Integer, Offset> oldOffset = psfSettings.getOffsetsMap();
		if (useOffset && !oldOffset.isEmpty())
		{
			int sliceOffset = lower;
			for (Entry<Integer, Offset> entry : oldOffset.entrySet())
			{
				model.setRelativeCentre(entry.getKey() - sliceOffset, entry.getValue().getCx(),
						entry.getValue().getCy());
			}
		}
		else
		{
			// Use the CoM if present
			double cx = psfSettings.getXCentre();
			double cy = psfSettings.getYCentre();
			if (cx != 0 || cy != 0)
			{
				for (int slice = 0; slice < image.length; slice++)
					model.setCentre(slice, cx, cy);
			}
		}

		return model;
	}

	private void put(BlockingQueue<Job> jobs, Job job)
	{
		try
		{
			jobs.put(job);
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException("Unexpected interruption", e);
		}
	}

	/**
	 * @return The starting points for the fitting
	 */
	private double[][] getStartPoints()
	{
		double[][] xy = new double[getNumberOfStartPoints()][];
		int ii = 0;

		if (offsetFitting)
		{
			if (startOffset == 0)
			{
				xy[ii++] = new double[] { 0, 0 };
			}
			else
			{
				// Fit using region surrounding the point. Use -1,-1 : -1:1 : 1,-1 : 1,1 directions at 
				// startOffset pixels total distance 
				final double distance = Math.sqrt(startOffset * startOffset * 0.5);

				for (int x = -1; x <= 1; x += 2)
					for (int y = -1; y <= 1; y += 2)
					{
						xy[ii++] = new double[] { x * distance, y * distance };
					}
			}
		}
		// Add space for centre-of-mass at the end of the array
		if (comFitting)
			xy[ii++] = new double[2];
		return xy;
	}

	private int getNumberOfStartPoints()
	{
		int n = (offsetFitting) ? 1 : 0;
		if (startOffset > 0)
			n *= 4;
		return (comFitting) ? n + 1 : n;
	}

	public static List<String> createImageList(boolean requireFwhm)
	{
		List<String> titles = new LinkedList<String>();
		int[] ids = WindowManager.getIDList();
		if (ids != null)
		{
			for (int id : ids)
			{
				ImagePlus imp = WindowManager.getImage(id);
				if (imp != null)
				{
					// Image must be greyscale
					if (imp.getType() == ImagePlus.GRAY8 || imp.getType() == ImagePlus.GRAY16 ||
							imp.getType() == ImagePlus.GRAY32)
					{
						// Image must be square and a stack of a single channel
						if (imp.getWidth() == imp.getHeight() && imp.getNChannels() == 1)
						{
							// Check if these are PSF images created by the SMLM plugins
							ImagePSF psfSettings = getPSFSettings(imp);
							if (psfSettings != null)
							{
								if (psfSettings.getCentreImage() <= 0)
								{
									Utils.log(TITLE + ": Unknown PSF z-centre setting for image: " + imp.getTitle());
									continue;
								}
								if (psfSettings.getPixelSize() <= 0)
								{
									Utils.log(TITLE + ": Unknown PSF nm/pixel setting for image: " + imp.getTitle());
									continue;
								}
								if (psfSettings.getPixelDepth() <= 0)
								{
									Utils.log(TITLE + ": Unknown PSF nm/slice setting for image: " + imp.getTitle());
									continue;
								}
								if (requireFwhm && psfSettings.getFwhm() <= 0)
								{
									Utils.log(TITLE + ": Unknown PSF FWHM setting for image: " + imp.getTitle());
									continue;
								}

								titles.add(imp.getTitle());
							}
						}
					}
				}
			}
		}
		return titles;
	}

	static ImagePSF getPSFSettings(ImagePlus imp)
	{
		Object info = imp.getProperty("Info");
		if (info != null)
		{
			return ImagePSFHelper.fromString(info.toString());
		}
		return null;
	}

	public static double[][] getStartPoints(PSFDrift psfDrift)
	{
		return psfDrift.getStartPoints();
	}

	private void showHWHM()
	{
		// Build a list of suitable images
		List<String> titles = createImageList(false);

		if (titles.isEmpty())
		{
			IJ.error(TITLE, "No suitable PSF images");
			return;
		}

		GenericDialog gd = new GenericDialog(TITLE);
		gd.addMessage("Approximate the volume of the PSF as a Gaussian and\ncompute the equivalent Gaussian width.");
		gd.addChoice("PSF", titles.toArray(new String[titles.size()]), title);
		gd.addCheckbox("Use_offset", useOffset);
		gd.addSlider("Smoothing", 0, 0.5, smoothing);

		gd.showDialog();
		if (gd.wasCanceled())
			return;

		title = gd.getNextChoice();
		useOffset = gd.getNextBoolean();
		smoothing = gd.getNextNumber();

		imp = WindowManager.getImage(title);
		if (imp == null)
		{
			IJ.error(TITLE, "No PSF image for image: " + title);
			return;
		}
		psfSettings = getPSFSettings(imp);
		if (psfSettings == null)
		{
			IJ.error(TITLE, "No PSF settings for image: " + title);
			return;
		}

		int size = imp.getStackSize();
		ImagePSFModel psf = createImagePSF(1, size, 1);

		double[] w0 = psf.getAllHWHM0();
		double[] w1 = psf.getAllHWHM1();

		// Get current centre
		int centre = psfSettings.getCentreImage();

		// Extract valid values (some can be NaN)
		double[] slice0, slice1;
		double[] sw0 = new double[w0.length], sw1 = new double[w1.length];
		{
			TDoubleArrayList s0 = new TDoubleArrayList(w0.length);
			TDoubleArrayList s1 = new TDoubleArrayList(w0.length);
			int c0 = 0, c1 = 0;
			for (int i = 0; i < w0.length; i++)
			{
				if (Maths.isFinite(w0[i]))
				{
					s0.add(i + 1);
					sw0[c0++] = w0[i];
				}
				if (Maths.isFinite(w1[i]))
				{
					s1.add(i + 1);
					sw1[c1++] = w1[i];
				}
			}
			if (c0 == 0 && c1 == 0)
			{
				IJ.error(TITLE, "No computed HWHM for image: " + title);
				return;
			}
			slice0 = s0.toArray();
			sw0 = Arrays.copyOf(sw0, c0);
			slice1 = s1.toArray();
			sw1 = Arrays.copyOf(sw1, c1);
		}

		// Smooth 
		if (smoothing > 0)
		{
			LoessInterpolator loess = new LoessInterpolator(smoothing, 1);
			sw0 = loess.smooth(slice0, sw0);
			sw1 = loess.smooth(slice1, sw1);
		}

		//int newCentre = 0;
		//double minW = Double.POSITIVE_INFINITY;
		TDoubleArrayList minWX = new TDoubleArrayList();
		TDoubleArrayList minWY = new TDoubleArrayList();
		for (int i = 0; i < w0.length; i++)
		{
			double w = 0;
			if (Maths.isFinite(w0[i]))
			{
				if (Maths.isFinite(w1[i]))
				{
					w = w0[i] * w1[i];
				}
				else
				{
					w = w0[i] * w0[i];
				}
			}
			else if (Maths.isFinite(w1[i]))
			{
				w = w1[i] * w1[i];
			}

			if (w != 0)
			{
				minWX.add(i + 1);
				minWY.add(Math.sqrt(w));
			}
		}

		// Smooth the combined line
		double[] cx = minWX.toArray();
		double[] cy = minWY.toArray();
		if (smoothing > 0)
		{
			LoessInterpolator loess = new LoessInterpolator(smoothing, 1);
			cy = loess.smooth(cx, cy);
		}
		final int newCentre = SimpleArrayUtils.findMinIndex(cy);

		// Convert to FWHM
		double fwhm = psfSettings.getFwhm();

		// Widths are in pixels
		String title = TITLE + " HWHM";
		Plot plot = new Plot(title, "Slice", "HWHM (px)");
		double[] limits = Maths.limits(sw0);
		limits = Maths.limits(limits, sw1);
		double maxY = limits[1] * 1.05;
		plot.setLimits(1, size, 0, maxY);
		plot.setColor(Color.red);
		plot.addPoints(slice0, sw0, Plot.LINE);
		plot.setColor(Color.blue);
		plot.addPoints(slice1, sw1, Plot.LINE);
		plot.setColor(Color.magenta);
		plot.addPoints(cx, cy, Plot.LINE);
		plot.setColor(Color.black);
		plot.addLabel(0, 0, "X=red; Y=blue, Combined=Magenta");
		PlotWindow pw = Utils.display(title, plot);

		// Show a non-blocking dialog to allow the centre to be updated ...
		// Add a label and dynamically update when the centre is moved.
		NonBlockingExtendedGenericDialog gd2 = new NonBlockingExtendedGenericDialog(TITLE);
		double scale = psfSettings.getPixelSize();
		//@formatter:off
		gd2.addMessage(String.format(
				"Update the PSF information?\n \n" +
				"Current z-centre = %d, FHWM = %s px (%s nm)\n",
				centre, Utils.rounded(fwhm), Utils.rounded(fwhm * scale)));
		//@formatter:on
		gd2.addSlider("z-centre", cx[0], cx[cx.length - 1], newCentre);
		final TextField tf = gd2.getLastTextField();
		gd2.addMessage("");
		gd2.addAndGetButton("Reset", new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				tf.setText(Integer.toString(newCentre));
			}
		});
		Label label = gd2.getLastLabel();
		gd2.addCheckbox("Update_centre", updateCentre);
		gd2.addCheckbox("Update_HWHM", updateHWHM);
		gd2.enableYesNoCancel();
		gd2.hideCancelButton();
		UpdateDialogListener dl = new UpdateDialogListener(cx, cy, maxY, newCentre, scale, pw, label);
		gd2.addDialogListener(dl);
		gd2.showDialog();
		if (gd2.wasOKed())
		{
			if (updateCentre || updateHWHM)
			{
				ImagePSF.Builder b = psfSettings.toBuilder();
				if (updateCentre)
					b.setCentreImage(dl.centre);
				if (updateHWHM)
					b.setFwhm(dl.getFWHM());
				imp.setProperty("Info", ImagePSFHelper.toString(b));
			}
		}
	}

	private class UpdateDialogListener implements DialogListener
	{
		int offset;
		double[] cy;
		double maxY;
		int centre;
		double scale;
		PlotWindow pw;
		Label label;
		boolean drawing;

		UpdateDialogListener(double[] cx, double[] cy, double maxY, int centre, double scale, PlotWindow pw,
				Label label)
		{
			offset = (int) cx[0];
			this.cy = cy;

			// Interpolate missing values
			int upper = cx.length - 1;
			if (cx[upper] - cx[0] != upper)
			{
				LinearInterpolator in = new LinearInterpolator();
				PolynomialSplineFunction f = in.interpolate(cx, cy);
				cx = SimpleArrayUtils.newArray(upper + 1, cx[0], 1.0);
				cy = new double[cx.length];
				for (int i = 0; i < cx.length; i++)
					cy[i] = f.value(cx[i]);
			}

			this.maxY = maxY;
			this.centre = centre;
			this.scale = scale;
			this.pw = pw;
			this.label = label;
			drawing = Utils.isShowGenericDialog();
			if (drawing)
				update();
		}

		private void update()
		{
			double fwhm = getFWHM();
			label.setText(String.format("FWHM = %s px (%s nm)", Utils.rounded(fwhm), Utils.rounded(fwhm * scale)));

			Plot plot = pw.getPlot();

			double x = plot.scaleXtoPxl(centre);
			double min = plot.scaleYtoPxl(0);
			double max = plot.scaleYtoPxl(maxY);

			pw.getImagePlus().setRoi(new Line(x, min, x, max));

			imp.setSlice(centre);
			imp.resetDisplayRange();
			imp.updateAndDraw();
		}

		public double getFWHM()
		{
			return 2 * cy[centre - offset];
		}

		public boolean dialogItemChanged(GenericDialog gd, AWTEvent e)
		{
			centre = (int) gd.getNextNumber();
			updateCentre = gd.getNextBoolean();
			updateHWHM = gd.getNextBoolean();
			update();
			return true;
		}
	}
}
