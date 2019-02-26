package gdsc.smlm.ij.plugins;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2014 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import java.awt.Color;
import java.awt.Rectangle;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.apache.commons.math3.exception.OutOfRangeException;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import uk.ac.sussex.gdsc.core.ij.ImageJUtils; import uk.ac.sussex.gdsc.core.utils.SimpleArrayUtils; import uk.ac.sussex.gdsc.core.utils.TextUtils; import uk.ac.sussex.gdsc.core.utils.MathUtils;
import uk.ac.sussex.gdsc.core.match.Assignment;
import uk.ac.sussex.gdsc.core.match.AssignmentComparator;
import uk.ac.sussex.gdsc.core.match.BasePoint;
import uk.ac.sussex.gdsc.core.match.Coordinate;
import uk.ac.sussex.gdsc.core.match.FractionClassificationResult;
import uk.ac.sussex.gdsc.core.match.ImmutableFractionalAssignment;
import uk.ac.sussex.gdsc.core.match.PointPair;
import uk.ac.sussex.gdsc.core.utils.Correlator;
import uk.ac.sussex.gdsc.core.utils.FastCorrelator;
import uk.ac.sussex.gdsc.core.utils.MathUtils;
import uk.ac.sussex.gdsc.core.utils.NoiseEstimator.Method;
import uk.ac.sussex.gdsc.core.utils.RampedScore;
import uk.ac.sussex.gdsc.core.utils.SortUtils;
import uk.ac.sussex.gdsc.core.utils.StoredDataStatistics;
import gdsc.smlm.engine.FitEngineConfiguration;
import gdsc.smlm.engine.FitParameters;
import gdsc.smlm.engine.FitWorker;
import gdsc.smlm.engine.ParameterisedFitJob;
import gdsc.smlm.filters.MaximaSpotFilter;
import gdsc.smlm.filters.Spot;
import gdsc.smlm.fitting.FitConfiguration;
import gdsc.smlm.fitting.FitFunction;
import gdsc.smlm.fitting.FitResult;
import gdsc.smlm.fitting.FitSolver;
import gdsc.smlm.fitting.FitStatus;
import gdsc.smlm.function.gaussian.Gaussian2DFunction;
import gdsc.smlm.ij.plugins.BenchmarkSpotFilter.FilterResult;
import gdsc.smlm.ij.plugins.BenchmarkSpotFilter.ScoredSpot;
import gdsc.smlm.ij.plugins.ResultsMatchCalculator.PeakResultPoint;
import gdsc.smlm.ij.settings.FilterSettings;
import gdsc.smlm.ij.settings.GlobalSettings;
import gdsc.smlm.ij.settings.SettingsManager;
import gdsc.smlm.ij.utils.ImageConverter;
import gdsc.smlm.results.Calibration;
import gdsc.smlm.results.MemoryPeakResults;
import gdsc.smlm.results.NullPeakResults;
import gdsc.smlm.results.PeakResult;
import gdsc.smlm.results.filter.Filter;
import gdsc.smlm.results.filter.FilterSet;
import gdsc.smlm.results.filter.MultiFilter2;
import gdsc.smlm.results.filter.XStreamWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Plot;
import uk.ac.sussex.gdsc.core.ij.gui.Plot2;
import ij.gui.PlotWindow;
import ij.plugin.PlugIn;
import uk.ac.sussex.gdsc.core.ij.plugin.WindowOrganiser;
import ij.text.TextWindow;

/**
 * Fits all the candidate spots identified by the benchmark spot filter plugin.
 */
public class BenchmarkSpotFit implements PlugIn
{
	private static final String TITLE = "Fit Spot Data";

	// Used to try and guess the range for filtering the results
	private enum LowerLimit
	{
		ZERO(false), ONE_PERCENT(false), MAX_NEGATIVE_CUMUL_DELTA(true);

		final boolean requiresDelta;

		LowerLimit(boolean requiresDelta)
		{
			this.requiresDelta = requiresDelta;
		}

		public boolean requiresDeltaHistogram()
		{
			return requiresDelta;
		}
	}

	private enum UpperLimit
	{
		ZERO(false), MAX_POSITIVE_CUMUL_DELTA(true), NINETY_NINE_PERCENT(false), NINETY_NINE_NINE_PERCENT(false);

		final boolean requiresDelta;

		UpperLimit(boolean requiresDelta)
		{
			this.requiresDelta = requiresDelta;
		}

		public boolean requiresDeltaHistogram()
		{
			return requiresDelta;
		}
	}

	private class FilterCriteria
	{
		final String name;
		final LowerLimit lower;
		final UpperLimit upper;
		final int minBinWidth;
		final boolean restrictRange;
		final boolean requireLabel;

		public FilterCriteria(String name, LowerLimit lower, UpperLimit upper)
		{
			this(name, lower, upper, 0, true, true);
		}

		public FilterCriteria(String name, LowerLimit lower, UpperLimit upper, int minBinWidth, boolean restrictRange,
				boolean requireLabel)
		{
			this.name = name;
			this.lower = lower;
			this.upper = upper;
			this.minBinWidth = minBinWidth;
			this.restrictRange = restrictRange;
			this.requireLabel = requireLabel;
		}
	}

	private static FilterCriteria[] filterCriteria = null;
	private static final int FILTER_SIGNAL = 0;
	private static final int FILTER_SNR = 1;
	private static final int FILTER_MIN_WIDTH = 2;
	private static final int FILTER_MAX_WIDTH = 3;
	private static final int FILTER_SHIFT = 4;
	private static final int FILTER_ESHIFT = 5;
	private static final int FILTER_PRECISION = 6;
	private static final int FILTER_ITERATIONS = 7;
	private static final int FILTER_EVALUATIONS = 8;

	private FilterCriteria[] createFilterCriteria()
	{
		if (filterCriteria == null)
		{
			filterCriteria = new FilterCriteria[9];
			int i = 0;
			//@formatter:off
			filterCriteria[i++] = new FilterCriteria("Signal",     LowerLimit.ONE_PERCENT, UpperLimit.MAX_POSITIVE_CUMUL_DELTA);
			filterCriteria[i++] = new FilterCriteria("SNR",        LowerLimit.ONE_PERCENT, UpperLimit.MAX_POSITIVE_CUMUL_DELTA);
			filterCriteria[i++] = new FilterCriteria("MinWidth",   LowerLimit.ONE_PERCENT, UpperLimit.ZERO);
			filterCriteria[i++] = new FilterCriteria("MaxWidth",   LowerLimit.ZERO,        UpperLimit.NINETY_NINE_PERCENT);
			filterCriteria[i++] = new FilterCriteria("Shift",      LowerLimit.MAX_NEGATIVE_CUMUL_DELTA, UpperLimit.NINETY_NINE_PERCENT);
			filterCriteria[i++] = new FilterCriteria("EShift",     LowerLimit.MAX_NEGATIVE_CUMUL_DELTA, UpperLimit.NINETY_NINE_PERCENT);
			filterCriteria[i++] = new FilterCriteria("Precision",  LowerLimit.MAX_NEGATIVE_CUMUL_DELTA, UpperLimit.NINETY_NINE_PERCENT);
			// These are not filters but are used for stats analysis
			filterCriteria[i++] = new FilterCriteria("Iterations", LowerLimit.ONE_PERCENT, UpperLimit.NINETY_NINE_NINE_PERCENT, 1, false, false);
			filterCriteria[i++] = new FilterCriteria("Evaluations",LowerLimit.ONE_PERCENT, UpperLimit.NINETY_NINE_NINE_PERCENT, 1, false, false);
			//@formatter:on
		}
		return filterCriteria;
	}

	static FitConfiguration fitConfig;
	private static FitEngineConfiguration config;
	private static Calibration cal;
	static
	{
		cal = new Calibration();
		fitConfig = new FitConfiguration();
		config = new FitEngineConfiguration(fitConfig);
		// Set some default fit settings here ...
		// Ensure all candidates are fitted
		config.setFailuresLimit(-1);
		fitConfig.setFitValidation(true);
		fitConfig.setMinPhotons(1); // Do not allow negative photons 
		fitConfig.setCoordinateShiftFactor(0);
		fitConfig.setPrecisionThreshold(0);
		fitConfig.setMinWidthFactor(0);
		fitConfig.setWidthFactor(0);

		fitConfig.setBackgroundFitting(true);
		fitConfig.setMinIterations(0);
		fitConfig.setNoise(0);
		config.setNoiseMethod(Method.QUICK_RESIDUALS_LEAST_MEAN_OF_SQUARES);
	}

	private static double fractionPositives = 100;
	private static double fractionNegativesAfterAllPositives = 50;
	private static int negativesAfterAllPositives = 10;
	private static double distance = 1.5;
	private static double lowerDistance = 1.5;
	// Allow other plugins to access these
	static double signalFactor = 2;
	static double lowerSignalFactor = 1;

	private static boolean showFilterScoreHistograms = false;
	private static boolean saveFilterRange = true;
	private static boolean showCorrelation = false;
	private static boolean rankByIntensity = false;

	private boolean extraOptions = false;

	private static TextWindow summaryTable = null;

	private ImagePlus imp;
	private MemoryPeakResults results;
	private CreateData.SimulationParameters simulationParameters;
	private MaximaSpotFilter spotFilter;

	private static HashMap<Integer, ArrayList<Coordinate>> actualCoordinates = null;
	private static HashMap<Integer, FilterCandidates> filterCandidates;
	private static double fP, fN;
	private static int nP, nN;

	static int lastId = -1, lastFilterId = -1;
	private static double lastFractionPositives = -1;
	private static double lastFractionNegativesAfterAllPositives = -1;
	private static int lastNegativesAfterAllPositives = -1;

	// Allow other plugins to access the results
	static int fitResultsId = 0;
	static HashMap<Integer, FilterCandidates> fitResults;
	static double distanceInPixels;
	static double lowerDistanceInPixels;
	static double candidateTN, candidateFN;

	public static String tablePrefix, resultPrefix;

	/**
	 * Store details of spot candidates that match actual spots
	 */
	public abstract class SpotMatch
	{
		/**
		 * The index for the spot candidate
		 */
		final int i;
		/**
		 * The distance to the spot
		 */
		final double d;
		/**
		 * The depth of the actual spot
		 */
		final double z;
		/**
		 * The score
		 */
		double score;

		public SpotMatch(int i, double d, double z)
		{
			this.i = i;
			this.d = d;
			this.z = z;
		}

		/**
		 * @return True if the spot candidate was successfully fitted
		 */
		public abstract boolean isFitResult();

		/**
		 * Return a score for the difference between the fitted and actual signal. Zero is no difference. Negative is
		 * the fitted is below the actual. Positive means the fitted is above the actual.
		 * 
		 * @return The factor difference between the successfully fitted signal and the actual signal.
		 */
		public abstract double getSignalFactor();

		/**
		 * Return a score for the difference between the fitted and actual signal. Zero is no difference.
		 * Positive means the fitted is difference from the actual.
		 * 
		 * @return The factor difference between the successfully fitted signal and the actual signal.
		 */
		public double getAbsoluteSignalFactor()
		{
			return Math.abs(getSignalFactor());
		}
	}

	/**
	 * Store details of a fitted spot candidate that matches an actual spot
	 */
	public class FitMatch extends SpotMatch
	{
		final double predictedSignal, actualSignal;
		final double sf;

		public FitMatch(int i, double d, double z, double predictedSignal, double actualSignal)
		{
			super(i, d, z);
			this.predictedSignal = predictedSignal;
			this.actualSignal = actualSignal;
			this.sf = BenchmarkSpotFit.getSignalFactor(predictedSignal, actualSignal);
		}

		@Override
		public boolean isFitResult()
		{
			return true;
		}

		@Override
		public double getSignalFactor()
		{
			return sf;
		}
	}

	static double getSignalFactor(double predictedSignal, double actualSignal)
	{
		final double rsf = predictedSignal / actualSignal;
		// The relative signal factor is 1 for a perfect fit, less than 1 for below and above 1 for above.
		// Reset the signal factor from 0
		double sf = (rsf < 1) ? 1 - 1 / rsf : rsf - 1;
		return sf;
	}

	/**
	 * Store details of a spot candidate that matches an actual spot
	 */
	public class CandidateMatch extends SpotMatch
	{
		public CandidateMatch(int i, double d, double z)
		{
			super(i, d, z);
		}

		@Override
		public boolean isFitResult()
		{
			return false;
		}

		@Override
		public double getSignalFactor()
		{
			return 0;
		}
	}

	public class FilterCandidates implements Cloneable
	{
		// Integer counts of positives (matches) and negatives
		final int p, n;
		// Double sums of the fractions match score and antiscore 
		final double np, nn;
		final ScoredSpot[] spots;
		double tp, fp, tn, fn;
		FitResult[] fitResult;
		FitResult[] fitResultWithNeighbours;
		float noise;

		/** Store if the candidates can be fitted and match a position. Size is the number of scored spots */
		boolean[] fitMatch;
		/** Store the z-position of the actual spots for later analysis. Size is the number of actual spots */
		double[] zPosition;

		/**
		 * Store details about the actual spots that were matched by spot candidates or fitted spot candidates
		 */
		SpotMatch[] match;

		public FilterCandidates(int p, int n, double np, double nn, ScoredSpot[] spots)
		{
			this.p = p;
			this.n = n;
			this.np = np;
			this.nn = nn;
			this.spots = spots;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#clone()
		 */
		public FilterCandidates clone()
		{
			try
			{
				return (FilterCandidates) super.clone();
			}
			catch (CloneNotSupportedException e)
			{
				return null;
			}
		}
	}

	private class Ranking implements Comparable<Ranking>
	{
		final double value;
		final int index;

		Ranking(double value, int index)
		{
			this.value = value;
			this.index = index;
		}

		public int compareTo(Ranking that)
		{
			return Double.compare(this.value, that.value);
		}
	}

	/**
	 * Used to allow multi-threading of the fitting method
	 */
	private class Worker implements Runnable
	{
		volatile boolean finished = false;
		final BlockingQueue<Integer> jobs;
		final ImageStack stack;
		final FitWorker fitWorker;
		final HashMap<Integer, ArrayList<Coordinate>> actualCoordinates;
		final HashMap<Integer, FilterCandidates> filterCandidates;
		final HashMap<Integer, FilterCandidates> results;
		final Rectangle bounds;

		float[] data = null;
		List<PointPair> matches = new ArrayList<PointPair>();

		public Worker(BlockingQueue<Integer> jobs, ImageStack stack,
				HashMap<Integer, ArrayList<Coordinate>> actualCoordinates,
				HashMap<Integer, FilterCandidates> filterCandidates)
		{
			this.jobs = jobs;
			this.stack = stack;
			this.fitWorker = new FitWorker(config.clone(), new NullPeakResults(), null);

			final int fitting = config.getRelativeFitting();
			fitWorker.setSearchParameters(spotFilter, fitting);
			fitWorker.setUpdateInitialParameters(true);

			this.actualCoordinates = actualCoordinates;
			this.filterCandidates = filterCandidates;
			this.results = new HashMap<Integer, FilterCandidates>();
			bounds = new Rectangle(0, 0, stack.getWidth(), stack.getHeight());
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
				while (!finished)
				{
					Integer job = jobs.take();
					if (job == null || job.intValue() < 0 || finished)
						break;
					run(job.intValue());
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

		private void run(int frame)
		{
			if (ImageJUtils.isInterrupted())
			{
				finished = true;
				return;
			}

			showProgress();

			// Extract the data
			data = ImageConverter.getData(stack.getPixels(frame), stack.getWidth(), stack.getHeight(), null, data);

			FilterCandidates candidates = filterCandidates.get(frame);
			FitResult[] fitResult = new FitResult[candidates.spots.length];
			FitResult[] fitResultWithNeighbours = new FitResult[candidates.spots.length];

			// Fit the candidates and store the results
			FitParameters parameters = new FitParameters();
			Spot[] spots = new Spot[candidates.spots.length];
			for (int i = 0; i < spots.length; i++)
			{
				spots[i] = candidates.spots[i].spot;
				//System.out.printf("Fit %d [%d,%d = %.1f]\n", i+1, spots[i].x, spots[i].y, spots[i].intensity);
			}
			parameters.spots = spots;

			ParameterisedFitJob job = new ParameterisedFitJob(parameters, frame, data, bounds);
			fitWorker.run(job); // Results will be stored in the fit job 

			@SuppressWarnings("unused")
			int fittedSpots = 0;
			for (int i = 0; i < spots.length; i++)
			{
				fitResult[i] = job.getFitResult(i);
				fitResultWithNeighbours[i] = job.getFitResultWithNeighbours(i);
				if (fitResult[i].getStatus() == FitStatus.OK)
					fittedSpots++;
			}

			// Compute the matches of the fitted spots to the simulated positions
			Coordinate[] actual = ResultsMatchCalculator.getCoordinates(actualCoordinates, frame);
			final double[] zPosition = new double[actual.length];
			final boolean[] fitMatch = new boolean[spots.length];
			SpotMatch[] match = new SpotMatch[spots.length];
			int matchCount = 0;
			RampedScore rampedScore = new RampedScore(lowerDistanceInPixels, distanceInPixels);
			RampedScore signalScore = (signalFactor > 0) ? new RampedScore(lowerSignalFactor, signalFactor) : null;
			if (actual.length > 0)
			{
				// Build a list of the coordinates z-depth using the PeakResultPoint
				for (int i = 0; i < actual.length; i++)
				{
					PeakResultPoint p = (PeakResultPoint) actual[i];
					zPosition[i] = p.peakResult.error;
				}

				BasePoint[] predicted = new BasePoint[spots.length];
				boolean[] isFit = new boolean[predicted.length];
				matches.clear();

				int count = 0;
				for (int i = 0; i < spots.length; i++)
				{
					if (fitResult[i].getStatus() == FitStatus.OK)
					{
						// XXX - Note that we can configure a residuals threshold. So how do we deal with doublets?

						// The XY positions should be offset by +0.5 already
						final double[] params = job.getFitResult(i).getParameters();
						isFit[count] = true;
						predicted[count++] = new BasePoint((float) params[Gaussian2DFunction.X_POSITION],
								(float) params[Gaussian2DFunction.Y_POSITION], i);
					}
					else
					{
						// Use the candidate position instead
						predicted[count++] = new BasePoint(spots[i].x + 0.5f, spots[i].y + 0.5f, -1 - i);
					}
				}
				// If we made any fits then score them
				if (count > 0)
				{
					// Match fit results/candidates with their closest actual spot
					predicted = Arrays.copyOf(predicted, count);

					// TODO - Is using the closest match the best way to do this for high density data?
					// Perhaps we should pair up the closest matches using the signal factor as well.

					final double matchDistance = distanceInPixels * distanceInPixels;
					ArrayList<Assignment> assignments = new ArrayList<Assignment>();

					// Match all the fit results to spots. We want to match all fit results to actual spots.
					// All remaining candidate spots can then be matched to any remaining actual spots.
					for (int ii = 0; ii < actual.length; ii++)
					{
						final float x = actual[ii].getX();
						final float y = actual[ii].getY();
						for (int jj = 0; jj < predicted.length; jj++)
						{
							final double d2 = predicted[jj].distanceSquared(x, y);
							if (d2 <= matchDistance)
							{
								// Get the score
								double score = d2;

								if (isFit[jj])
								{
									// Use the signal and ramped distance scoring
									if (signalScore != null)
									{
										score = rampedScore.score(Math.sqrt(d2));
										final PeakResultPoint p3 = (PeakResultPoint) actual[ii];
										final BasePoint p2 = (BasePoint) predicted[jj];
										int i = (int) p2.getZ();
										double sf = getSignalFactor(
												job.getFitResult(i).getParameters()[Gaussian2DFunction.SIGNAL],
												p3.peakResult.getSignal());
										score *= signalScore.score(Math.abs(sf));

										if (score == 0)
											// This doesn't match
											continue;

										// Invert for the ranking (i.e. low is best)
										score = 1 - score;
									}
								}
								else
								{
									// This is not a fit. Ensure that remaining candidate spots are assigned
									// after any fit results.
									score += matchDistance + 1;
								}
								assignments.add(new ImmutableFractionalAssignment(ii, jj, score));
							}
						}
					}

					AssignmentComparator.sort(assignments);

					final boolean[] actualAssignment = new boolean[actual.length];
					final boolean[] predictedAssignment = new boolean[predicted.length];

					for (Assignment assignment : assignments)
					{
						if (!actualAssignment[assignment.getTargetId()])
						{
							if (!predictedAssignment[assignment.getPredictedId()])
							{
								actualAssignment[assignment.getTargetId()] = true;
								predictedAssignment[assignment.getPredictedId()] = true;

								final PeakResultPoint p3 = (PeakResultPoint) actual[assignment.getTargetId()];
								final BasePoint p2 = (BasePoint) predicted[assignment.getPredictedId()];
								int i = (int) p2.getZ();

								final double d = p2.distanceXy(p3);

								if (i >= 0)
								{
									// This is a fitted candidate

									final double a = p3.peakResult.getSignal();
									final double p = job.getFitResult(i).getParameters()[Gaussian2DFunction.SIGNAL];

									fitMatch[i] = true;
									match[matchCount++] = new FitMatch(i, d, p3.peakResult.error, p, a);
								}
								else
								{
									// This is a candidate that could not be fitted

									// Get the index
									i = -1 - i;
									match[matchCount++] = new CandidateMatch(i, d, p3.peakResult.error);
								}
							}
						}
					}
				}
			}
			match = Arrays.copyOf(match, matchCount);

			// Mark the results 
			double tp = 0;
			double fp = 0;
			double tn = 0;
			double fn = 0;

			for (int i = 0; i < match.length; i++)
			{
				final double s = rampedScore.scoreAndFlatten(match[i].d, 256);
				match[i].score = s;

				if (match[i].isFitResult())
				{
					// This is a fitted result so is a positive

					// Check the fitted signal is approximately correct
					if (signalFactor != 0)
					{
						if (match[i].getAbsoluteSignalFactor() > signalFactor)
						{
							// Treat as an unfitted result
							fn += s;
							tn += 1 - s;
							continue;
						}
					}
					tp += s;
					fp += 1 - s;
				}
				else
				{
					// This is an unfitted result that matches so is a negative
					fn += s;
					tn += 1 - s;
				}
			}

			// Make the totals sum to the correct numbers.
			// *** Do not do this. We only want to score the matches to allow reporting of a perfect filter.

			// TP + FP = fitted spots
			//fp = fittedSpots - tp;
			// TN + FN = unfitted candidate spots
			//tn = (spots.length - fittedSpots) - fn;

			// Store the results using a copy of the original (to preserve the candidates for repeat analysis)
			candidates = candidates.clone();
			candidates.tp = tp;
			candidates.fp = fp;
			candidates.tn = tn;
			candidates.fn = fn;
			candidates.fitResult = fitResult;
			candidates.fitResultWithNeighbours = fitResultWithNeighbours;
			candidates.fitMatch = fitMatch;
			candidates.match = match;
			candidates.zPosition = zPosition;
			// Noise should be the same for all results
			if (!job.getResults().isEmpty())
				candidates.noise = job.getResults().get(0).noise;
			results.put(frame, candidates);
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

		extraOptions = ImageJUtils.isExtraOptions();

		simulationParameters = CreateData.simulationParameters;
		if (simulationParameters == null)
		{
			IJ.error(TITLE, "No benchmark spot parameters in memory");
			return;
		}
		imp = CreateData.getImage();
		if (imp == null)
		{
			IJ.error(TITLE, "No benchmark image");
			return;
		}
		results = MemoryPeakResults.getResults(CreateData.CREATE_DATA_IMAGE_TITLE + " (Create Data)");
		if (results == null)
		{
			IJ.error(TITLE, "No benchmark results in memory");
			return;
		}
		if (BenchmarkSpotFilter.filterResult == null)
		{
			IJ.error(TITLE, "No benchmark spot candidates in memory");
			return;
		}
		if (BenchmarkSpotFilter.filterResult.simulationId != simulationParameters.id)
		{
			IJ.error(TITLE, "Update the benchmark spot candidates for the latest simulation");
			return;
		}
		// This is required to initialise the FitWorker
		spotFilter = BenchmarkSpotFilter.filterResult.spotFilter;

		if (!showDialog())
			return;

		run();
	}

	private boolean showDialog()
	{
		GenericDialog gd = new GenericDialog(TITLE);
		gd.addHelp(About.HELP_URL);

		gd.addMessage(String.format(
				"Fit candidate spots in the benchmark image created by " + CreateData.TITLE +
						" plugin\nand identified by the " + BenchmarkSpotFilter.TITLE +
						" plugin.\nPSF width = %s nm (Square pixel adjustment = %s nm)\n \nConfigure the fitting:",
				MathUtils.rounded(simulationParameters.s), MathUtils.rounded(getSa())));

		gd.addSlider("Fraction_positives", 50, 100, fractionPositives);
		gd.addSlider("Fraction_negatives_after_positives", 0, 100, fractionNegativesAfterAllPositives);
		gd.addSlider("Min_negatives_after_positives", 0, 10, negativesAfterAllPositives);
		gd.addSlider("Match_distance", 0.5, 3.5, distance);
		gd.addSlider("Lower_distance", 0, 3.5, lowerDistance);
		gd.addSlider("Match_signal", 0, 3.5, signalFactor);
		gd.addSlider("Lower_signal", 0, 3.5, lowerSignalFactor);

		// Collect options for fitting
		final double sa = getSa();
		gd.addNumericField("Initial_StdDev", sa / simulationParameters.a, 3);
		gd.addSlider("Fitting_width", 2, 4.5, config.getFitting());
		String[] solverNames = SettingsManager.getNames((Object[]) FitSolver.values());
		gd.addChoice("Fit_solver", solverNames, solverNames[fitConfig.getFitSolver().ordinal()]);
		String[] functionNames = SettingsManager.getNames((Object[]) FitFunction.values());
		gd.addChoice("Fit_function", functionNames, functionNames[fitConfig.getFitFunction().ordinal()]);
		gd.addCheckbox("Include_neighbours", config.isIncludeNeighbours());
		gd.addSlider("Neighbour_height", 0.01, 1, config.getNeighbourHeightThreshold());
		//gd.addSlider("Residuals_threshold", 0.01, 1, config.getResidualsThreshold());
		gd.addSlider("Duplicate_distance", 0, 1.5, fitConfig.getDuplicateDistance());
		gd.addCheckbox("Show_score_histograms", showFilterScoreHistograms);
		gd.addCheckbox("Show_correlation", showCorrelation);
		gd.addCheckbox("Plot_rank_by_intensity", rankByIntensity);
		gd.addCheckbox("Save_filter_range", saveFilterRange);

		if (extraOptions)
		{
		}

		gd.showDialog();

		if (gd.wasCanceled())
			return false;

		fractionPositives = Math.abs(gd.getNextNumber());
		fractionNegativesAfterAllPositives = Math.abs(gd.getNextNumber());
		negativesAfterAllPositives = (int) Math.abs(gd.getNextNumber());
		distance = Math.abs(gd.getNextNumber());
		lowerDistance = Math.abs(gd.getNextNumber());
		signalFactor = Math.abs(gd.getNextNumber());
		lowerSignalFactor = Math.abs(gd.getNextNumber());

		fitConfig.setInitialPeakStdDev(gd.getNextNumber());
		config.setFitting(gd.getNextNumber());
		fitConfig.setFitSolver(gd.getNextChoiceIndex());
		fitConfig.setFitFunction(gd.getNextChoiceIndex());
		config.setIncludeNeighbours(gd.getNextBoolean());
		config.setNeighbourHeightThreshold(gd.getNextNumber());
		//config.setResidualsThreshold(gd.getNextNumber());
		fitConfig.setDuplicateDistance(gd.getNextNumber());
		showFilterScoreHistograms = gd.getNextBoolean();
		showCorrelation = gd.getNextBoolean();
		rankByIntensity = gd.getNextBoolean();
		saveFilterRange = gd.getNextBoolean();

		// Avoid stupidness, i.e. things that move outside the fit window and are bad widths
		fitConfig.setMinPhotons(15); // Realistically we cannot fit lower than this
		fitConfig.setCoordinateShiftFactor(config.getFitting() / fitConfig.getInitialPeakStdDev0());
		fitConfig.setFitRegion(2 * config.getRelativeFitting() + 1);
		fitConfig.setCoordinateOffset(0.5);
		fitConfig.setMinWidthFactor(1.0 / 5);
		fitConfig.setWidthFactor(5);

		if (extraOptions)
		{
		}

		if (gd.invalidNumber())
			return false;

		if (lowerDistance > distance)
			lowerDistance = distance;
		if (lowerSignalFactor > signalFactor)
			lowerSignalFactor = signalFactor;

		// Distances relative to sa (not s) as this is the same as the BenchmarkSpotFilter plugin 
		distanceInPixels = distance * sa / simulationParameters.a;
		lowerDistanceInPixels = lowerDistance * sa / simulationParameters.a;

		GlobalSettings settings = new GlobalSettings();
		settings.setFitEngineConfiguration(config);
		settings.setCalibration(cal);
		// Copy simulation defaults if a new simulation
		if (lastId != simulationParameters.id)
		{
			cal.nmPerPixel = simulationParameters.a;
			cal.gain = simulationParameters.gain;
			cal.amplification = simulationParameters.amplification;
			cal.exposureTime = 100;
			cal.readNoise = simulationParameters.readNoise;
			cal.bias = simulationParameters.bias;
			cal.emCCD = simulationParameters.emCCD;
		}
		if (!PeakFit.configureFitSolver(settings, null, extraOptions))
			return false;

		// This is needed to configure the fit solver
		fitConfig.setNmPerPixel(cal.nmPerPixel);
		fitConfig.setGain(cal.gain);
		fitConfig.setBias(cal.bias);
		fitConfig.setEmCCD(cal.emCCD);

		return true;
	}

	/** The total progress. */
	int progress, stepProgress, totalProgress;

	/**
	 * Show progress.
	 */
	private synchronized void showProgress()
	{
		if (++progress % stepProgress == 0)
		{
			if (ImageJUtils.showStatus("Frame: " + progress + " / " + totalProgress))
				IJ.showProgress(progress, totalProgress);
		}
	}

	private void run()
	{
		// Extract all the results in memory into a list per frame. This can be cached
		boolean refresh = false;
		if (lastId != simulationParameters.id)
		{
			// Do not get integer coordinates
			// The Coordinate objects will be PeakResultPoint objects that store the original PeakResult
			// from the MemoryPeakResults
			actualCoordinates = ResultsMatchCalculator.getCoordinates(results.getResults(), false);
			lastId = simulationParameters.id;
			refresh = true;
		}

		// Extract all the candidates into a list per frame. This can be cached if the settings have not changed
		if (refresh || lastFilterId != BenchmarkSpotFilter.filterResult.id ||
				lastFractionPositives != fractionPositives ||
				lastFractionNegativesAfterAllPositives != fractionNegativesAfterAllPositives ||
				lastNegativesAfterAllPositives != negativesAfterAllPositives)
		{
			filterCandidates = subsetFilterResults(BenchmarkSpotFilter.filterResult.filterResults);

			lastFilterId = BenchmarkSpotFilter.filterResult.id;
			lastFractionPositives = fractionPositives;
			lastFractionNegativesAfterAllPositives = fractionNegativesAfterAllPositives;
			lastNegativesAfterAllPositives = negativesAfterAllPositives;
		}

		final ImageStack stack = imp.getImageStack();

		// Clear old results to free memory
		if (fitResults != null)
			fitResults.clear();
		fitResults = null;

		// Create a pool of workers
		final int nThreads = Prefs.getThreads();
		BlockingQueue<Integer> jobs = new ArrayBlockingQueue<Integer>(nThreads * 2);
		List<Worker> workers = new LinkedList<Worker>();
		List<Thread> threads = new LinkedList<Thread>();
		for (int i = 0; i < nThreads; i++)
		{
			Worker worker = new Worker(jobs, stack, actualCoordinates, filterCandidates);
			Thread t = new Thread(worker);
			workers.add(worker);
			threads.add(t);
			t.start();
		}

		// Fit the frames
		long runTime = System.nanoTime();
		totalProgress = stack.getSize();
		stepProgress = ImageJUtils.getProgressInterval(totalProgress);
		progress = 0;
		for (int i = 1; i <= totalProgress; i++)
		{
			put(jobs, i);
		}
		// Finish all the worker threads by passing in a null job
		for (int i = 0; i < threads.size(); i++)
		{
			put(jobs, -1);
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
		runTime = System.nanoTime() - runTime;

		if (ImageJUtils.isInterrupted())
		{
			IJ.showStatus("Aborted");
			return;
		}

		IJ.showStatus("Collecting results ...");

		fitResultsId++;
		fitResults = new HashMap<Integer, FilterCandidates>();
		for (Worker w : workers)
		{
			fitResults.putAll(w.results);
		}

		summariseResults(fitResults, runTime);

		IJ.showStatus("");
	}

	/**
	 * Extract all the filter candidates in order until the desired number of positives have been reached and the number
	 * of negatives matches the configured parameters.
	 * 
	 * @param filterResults
	 * @return The filter candidates
	 */
	private HashMap<Integer, FilterCandidates> subsetFilterResults(HashMap<Integer, FilterResult> filterResults)
	{
		// Convert fractions from percent 
		final double f1 = Math.min(1, fractionPositives / 100.0);
		final double f2 = fractionNegativesAfterAllPositives / 100.0;

		HashMap<Integer, FilterCandidates> subset = new HashMap<Integer, FilterCandidates>();
		fP = fN = 0;
		nP = nN = 0;
		for (Entry<Integer, FilterResult> result : filterResults.entrySet())
		{
			FilterResult r = result.getValue();

			// Determine the number of positives to find. This score may be fractional.
			fP += r.result.getTruePositives();
			fN += r.result.getFalsePositives();

			// Q. Is r.result.getTruePositives() not the same as the total of r.spots[i].match
			// A. Not if we used fractional scoring.

			for (ScoredSpot spot : r.spots)
			{
				if (spot.match)
					nP++;
				else
					nN++;
			}

			// Make the target use the fractional score
			final double targetP = r.result.getTruePositives() * f1;

			// Count the number of positive & negatives
			int p = 0, n = 0;
			double np = 0, nn = 0;

			boolean reachedTarget = false;
			int nAfter = 0;

			int count = 0;
			for (ScoredSpot spot : r.spots)
			{
				count++;
				nn += spot.antiScore();
				if (spot.match)
				{
					np += spot.getScore();
					p++;
					if (!reachedTarget)
					{
						reachedTarget = np >= targetP;
					}
				}
				else
				{
					n++;
					if (reachedTarget)
					{
						nAfter++;
					}
				}

				if (reachedTarget)
				{
					// Check if we have reached both the limits
					if (nAfter >= negativesAfterAllPositives && (double) n / (n + p) >= f2)
						break;
				}
			}

			// Debug
			//System.out.printf("Frame %d : %.1f / (%.1f + %.1f). p=%d, n=%d, after=%d, f=%.1f\n", result.getKey().intValue(),
			//		r.result.getTruePositives(), r.result.getTruePositives(), r.result.getFalsePositives(), p, n,
			//		nAfter, (double) n / (n + p));

			subset.put(result.getKey(), new FilterCandidates(p, n, np, nn, Arrays.copyOf(r.spots, count)));
		}
		return subset;
	}

	private void put(BlockingQueue<Integer> jobs, int i)
	{
		try
		{
			jobs.put(i);
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException("Unexpected interruption", e);
		}
	}

	private void summariseResults(HashMap<Integer, FilterCandidates> filterCandidates, long runTime)
	{
		createTable();

		// Summarise the fitting results. N fits, N failures. 
		// Optimal match statistics if filtering is perfect (since fitting is not perfect).
		StoredDataStatistics distanceStats = new StoredDataStatistics();
		StoredDataStatistics depthStats = new StoredDataStatistics();

		// Get stats for all fitted results and those that match 
		// Signal, SNR, Width, xShift, yShift, Precision
		createFilterCriteria();
		StoredDataStatistics[][] stats = new StoredDataStatistics[3][filterCriteria.length];
		for (int i = 0; i < stats.length; i++)
			for (int j = 0; j < stats[i].length; j++)
				stats[i][j] = new StoredDataStatistics();

		final double nmPerPixel = simulationParameters.a;
		final boolean mle = fitConfig.getFitSolver() == FitSolver.MLE;
		double tp = 0, fp = 0;
		int failcTP = 0, failcFP = 0;
		int cTP = 0, cFP = 0;
		int[] status = null, status2 = null;
		status = new int[FitStatus.values().length];
		status2 = new int[status.length];
		for (FilterCandidates result : filterCandidates.values())
		{
			// Count the number of fit results that matched (tp) and did not match (fp)
			tp += result.tp;
			fp += result.fp;

			for (int i = 0; i < result.fitResult.length; i++)
			{
				if (result.spots[i].match)
					cTP++;
				else
					cFP++;
				final FitResult fitResult = result.fitResult[i];
				if (status2 != null)
				{
					final FitResult fitResultWithNeighbours = result.fitResultWithNeighbours[i];
					if (fitResultWithNeighbours != null && fitResultWithNeighbours.getStatus() != FitStatus.OK)
						status2[fitResultWithNeighbours.getStatus().ordinal()]++;
				}
				if (fitResult.getStatus() != FitStatus.OK)
				{
					// Debug why the MLE does not fit as many candidates as the LSE
					if (status != null)
					{
						status[result.fitResult[i].getStatus().ordinal()]++;
					}

					if (result.spots[i].match)
						failcTP++;
					else
						failcFP++;

					// Add the evaluations for spots that were not OK
					stats[0][FILTER_ITERATIONS].add(fitResult.getIterations());
					stats[0][FILTER_EVALUATIONS].add(fitResult.getEvaluations());

					// Q. Should we add to the TP/FP stats
					//final int index = (result.spots[i].match) ? 1 : 2;
					//stats[index][FILTER_ITERATIONS].add(fitResult.getIterations());
					//stats[index][FILTER_EVALUATIONS].add(fitResult.getEvaluations());
				}
				else
				{
					// This was fit - Get statistics
					final double[] p = fitResult.getParameters();
					final double[] initialParams = fitResult.getInitialParameters();
					final double s0 = (p[Gaussian2DFunction.X_SD] + p[Gaussian2DFunction.Y_SD]) * 0.5;
					final double s = s0 * nmPerPixel;
					final double N = p[Gaussian2DFunction.SIGNAL] / simulationParameters.gain;
					final double b2 = Math.max(0,
							(p[Gaussian2DFunction.BACKGROUND] - simulationParameters.bias) / simulationParameters.gain);
					double precision;
					if (mle)
						precision = PeakResult.getMLPrecisionX(nmPerPixel, s, N, b2, simulationParameters.emCCD);
					else
						precision = PeakResult.getPrecisionX(nmPerPixel, s, N, b2, simulationParameters.emCCD);

					final double signal = p[Gaussian2DFunction.SIGNAL] / simulationParameters.gain;
					final double snr = p[Gaussian2DFunction.SIGNAL] / result.noise;
					final double width = s0 / fitConfig.getInitialPeakStdDev0();
					final double xShift = p[Gaussian2DFunction.X_POSITION] -
							initialParams[Gaussian2DFunction.X_POSITION];
					final double yShift = p[Gaussian2DFunction.Y_POSITION] -
							initialParams[Gaussian2DFunction.Y_POSITION];
					// Since these two are combined for filtering and the max is what matters.
					double shift = ((Math.abs(xShift) > Math.abs(yShift)) ? xShift : yShift) /
							fitConfig.getInitialPeakStdDev0();
					// Comment this out to allow plotting the absolute shift which should be centred around 0
					shift = Math.abs(shift);
					final double eshift = Math.sqrt(xShift * xShift + yShift * yShift);

					stats[0][FILTER_SIGNAL].add(signal);
					stats[0][FILTER_SNR].add(snr);
					if (width < 1)
						stats[0][FILTER_MIN_WIDTH].add(width);
					else
						stats[0][FILTER_MAX_WIDTH].add(width);
					stats[0][FILTER_SHIFT].add(shift);
					stats[0][FILTER_ESHIFT].add(eshift);
					stats[0][FILTER_PRECISION].add(precision);
					stats[0][FILTER_ITERATIONS].add(fitResult.getIterations());
					stats[0][FILTER_EVALUATIONS].add(fitResult.getEvaluations());

					// Add to the TP or FP stats 
					final int index = (result.fitMatch[i]) ? 1 : 2;
					stats[index][FILTER_SIGNAL].add(signal);
					stats[index][FILTER_SNR].add(snr);
					if (width < 1)
						stats[index][FILTER_MIN_WIDTH].add(width);
					else
						stats[index][FILTER_MAX_WIDTH].add(width);
					stats[index][FILTER_SHIFT].add(shift);
					stats[index][FILTER_ESHIFT].add(eshift);
					stats[index][FILTER_PRECISION].add(precision);
					stats[index][FILTER_ITERATIONS].add(fitResult.getIterations());
					stats[index][FILTER_EVALUATIONS].add(fitResult.getEvaluations());
				}
			}
			for (int i = 0; i < result.match.length; i++)
			{
				if (!result.match[i].isFitResult())
					// For now just ignore the candidates that matched
					continue;

				FitMatch fitMatch = (FitMatch) result.match[i];
				distanceStats.add(fitMatch.d * nmPerPixel);
				depthStats.add(fitMatch.z * nmPerPixel);
			}
		}

		// Store data for computing correlation
		double[] i1 = new double[depthStats.getN()];
		double[] i2 = new double[i1.length];
		double[] is = new double[i1.length];
		int ci = 0;
		for (FilterCandidates result : filterCandidates.values())
		{
			for (int i = 0; i < result.match.length; i++)
			{
				if (!result.match[i].isFitResult())
					// For now just ignore the candidates that matched
					continue;

				FitMatch fitMatch = (FitMatch) result.match[i];
				ScoredSpot spot = result.spots[i];
				i1[ci] = fitMatch.predictedSignal;
				i2[ci] = fitMatch.actualSignal;
				is[ci] = spot.spot.intensity;
				ci++;
			}
		}

		// Debug the reasons the fit failed
		if (status != null)
		{
			String name = PeakFit.getSolverName(fitConfig);
			if (fitConfig.getFitSolver() == FitSolver.MLE && fitConfig.isModelCamera())
				name += " Camera";
			System.out.println("Failure counts: " + name);
			int total = 0;
			for (int i = 0; i < status.length; i++)
			{
				if (status[i] != 0)
				{
					System.out.printf("%s = %d\n", FitStatus.values()[i].toString(), status[i]);
					total += status[i]; 
				}
			}
			int total2 = 0;
			for (int i = 0; i < status2.length; i++)
			{
				if (status2[i] != 0)
				{
					System.out.printf("Neighbours %s = %d\n", FitStatus.values()[i].toString(), status2[i]);
					total2 += status2[i]; 
				}
			}
			System.out.printf("Total Failures: %d. Neighbour failures = %d\n", total, total2);
		}

		StringBuilder sb = new StringBuilder();

		// Add information about the simulation
		final double signal = simulationParameters.signalPerFrame; //(simulationParameters.minSignal + simulationParameters.maxSignal) * 0.5;
		final int n = results.size();
		sb.append(imp.getStackSize()).append("\t");
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		sb.append(w).append("\t");
		sb.append(h).append("\t");
		sb.append(n).append("\t");
		double density = ((double) n / imp.getStackSize()) / (w * h) /
				(simulationParameters.a * simulationParameters.a / 1e6);
		sb.append(MathUtils.rounded(density)).append("\t");
		sb.append(MathUtils.rounded(signal)).append("\t");
		sb.append(MathUtils.rounded(simulationParameters.s)).append("\t");
		sb.append(MathUtils.rounded(simulationParameters.a)).append("\t");
		sb.append(MathUtils.rounded(simulationParameters.depth)).append("\t");
		sb.append(simulationParameters.fixedDepth).append("\t");
		sb.append(MathUtils.rounded(simulationParameters.gain)).append("\t");
		sb.append(MathUtils.rounded(simulationParameters.readNoise)).append("\t");
		sb.append(MathUtils.rounded(simulationParameters.b)).append("\t");
		sb.append(MathUtils.rounded(simulationParameters.b2)).append("\t");

		// Compute the noise
		double noise = simulationParameters.b2;
		if (simulationParameters.emCCD)
		{
			// The b2 parameter was computed without application of the EM-CCD noise factor of 2.
			//final double b2 = backgroundVariance + readVariance
			//                = simulationParameters.b + readVariance
			// This should be applied only to the background variance.
			final double readVariance = noise - simulationParameters.b;
			noise = simulationParameters.b * 2 + readVariance;
		}

		if (simulationParameters.fullSimulation)
		{
			// The total signal is spread over frames
		}

		sb.append(MathUtils.rounded(signal / Math.sqrt(noise))).append("\t");
		sb.append(MathUtils.rounded(simulationParameters.s / simulationParameters.a)).append("\t");

		sb.append(spotFilter.getDescription());

		// nP and nN is the fractional score of the spot candidates 
		addCount(sb, nP + nN);
		addCount(sb, nP);
		addCount(sb, nN);
		addCount(sb, fP);
		addCount(sb, fN);
		String name = PeakFit.getSolverName(fitConfig);
		if (fitConfig.getFitSolver() == FitSolver.MLE && fitConfig.isModelCamera())
			name += " Camera";
		add(sb, name);
		add(sb, config.getFitting());

		resultPrefix = sb.toString();

		// Q. Should I add other fit configuration here?

		// The fraction of positive and negative candidates that were included
		add(sb, (100.0 * cTP) / nP);
		add(sb, (100.0 * cFP) / nN);

		// Score the fitting results compared to the original simulation.

		// Score the candidate selection:
		add(sb, cTP + cFP);
		add(sb, cTP);
		add(sb, cFP);
		// TP are all candidates that can be matched to a spot
		// FP are all candidates that cannot be matched to a spot
		// FN = The number of missed spots
		FractionClassificationResult m = new FractionClassificationResult(cTP, cFP, 0,
				simulationParameters.molecules - cTP);
		add(sb, m.getRecall());
		add(sb, m.getPrecision());
		add(sb, m.getF1Score());
		add(sb, m.getJaccard());

		// Score the fitting results:
		add(sb, failcTP);
		add(sb, failcFP);

		// TP are all fit results that can be matched to a spot
		// FP are all fit results that cannot be matched to a spot
		// FN = The number of missed spots
		add(sb, tp);
		add(sb, fp);
		m = new FractionClassificationResult(tp, fp, 0, simulationParameters.molecules - tp);
		add(sb, m.getRecall());
		add(sb, m.getPrecision());
		add(sb, m.getF1Score());
		add(sb, m.getJaccard());

		// Do it again but pretend we can perfectly filter all the false positives
		//add(sb, tp);
		m = new FractionClassificationResult(tp, 0, 0, simulationParameters.molecules - tp);
		// Recall is unchanged
		// Precision will be 100%
		add(sb, m.getF1Score());
		add(sb, m.getJaccard());

		// The mean may be subject to extreme outliers so use the median
		double median = distanceStats.getMedian();
		add(sb, median);

		WindowOrganiser wo = new WindowOrganiser();

		String label = String.format("Recall = %s. n = %d. Median = %s nm. SD = %s nm", MathUtils.rounded(m.getRecall()),
				distanceStats.getN(), MathUtils.rounded(median), MathUtils.rounded(distanceStats.getStandardDeviation()));
		int id = ImageJUtils.showHistogram(TITLE, distanceStats, "Match Distance (nm)", 0, 0, 0, label);
		if (ImageJUtils.isNewWindow())
			wo.add(id);

		median = depthStats.getMedian();
		add(sb, median);

		// Sort by spot intensity and produce correlation
		int[] indices = SimpleArrayUtils.newArray(i1.length, 0, 1);
		if (showCorrelation)
			SortUtils.sort(indices, is, rankByIntensity);
		double[] r = (showCorrelation) ? new double[i1.length] : null;
		double[] sr = (showCorrelation) ? new double[i1.length] : null;
		double[] rank = (showCorrelation) ? new double[i1.length] : null;
		ci = 0;
		FastCorrelator fastCorrelator = new FastCorrelator();
		ArrayList<Ranking> pc1 = new ArrayList<Ranking>();
		ArrayList<Ranking> pc2 = new ArrayList<Ranking>();
		for (int ci2 : indices)
		{
			fastCorrelator.add((long) Math.round(i1[ci2]), (long) Math.round(i2[ci2]));
			pc1.add(new Ranking(i1[ci2], ci));
			pc2.add(new Ranking(i2[ci2], ci));
			if (showCorrelation)
			{
				r[ci] = fastCorrelator.getCorrelation();
				sr[ci] = Correlator.correlation(rank(pc1), rank(pc2));
				if (rankByIntensity)
					rank[ci] = is[0] - is[ci];
				else
					rank[ci] = ci;
			}
			ci++;
		}

		final double pearsonCorr = fastCorrelator.getCorrelation();
		final double rankedCorr = Correlator.correlation(rank(pc1), rank(pc2));

		// Get the regression
		SimpleRegression regression = new SimpleRegression(false);
		for (int i = 0; i < pc1.size(); i++)
			regression.addData(pc1.get(i).value, pc2.get(i).value);
		//final double intercept = regression.getIntercept();
		final double slope = regression.getSlope();

		if (showCorrelation)
		{
			String title = TITLE + " Intensity";
			Plot plot = new Plot(title, "Candidate", "Spot");
			double[] limits1 = MathUtils.limits(i1);
			double[] limits2 = MathUtils.limits(i2);
			plot.setLimits(limits1[0], limits1[1], limits2[0], limits2[1]);
			label = String.format("Correlation=%s; Ranked=%s; Slope=%s", MathUtils.rounded(pearsonCorr),
					MathUtils.rounded(rankedCorr), MathUtils.rounded(slope));
			plot.addLabel(0, 0, label);
			plot.setColor(Color.red);
			plot.addPoints(i1, i2, Plot.DOT);
			if (slope > 1)
				plot.drawLine(limits1[0], limits1[0] * slope, limits1[1], limits1[1] * slope);
			else
				plot.drawLine(limits2[0] / slope, limits2[0], limits2[1] / slope, limits2[1]);
			PlotWindow pw = ImageJUtils.display(title, plot);
			if (ImageJUtils.isNewWindow())
				wo.add(pw);

			title = TITLE + " Correlation";
			plot = new Plot(title, "Spot Rank", "Correlation");
			double[] xlimits = MathUtils.limits(rank);
			double[] ylimits = MathUtils.limits(r);
			ylimits = MathUtils.limits(ylimits, sr);
			plot.setLimits(xlimits[0], xlimits[1], ylimits[0], ylimits[1]);
			plot.setColor(Color.red);
			plot.addPoints(rank, r, Plot.LINE);
			plot.setColor(Color.blue);
			plot.addPoints(rank, sr, Plot.LINE);
			plot.setColor(Color.black);
			plot.addLabel(0, 0, label);
			pw = ImageJUtils.display(title, plot);
			if (ImageJUtils.isNewWindow())
				wo.add(pw);
		}

		add(sb, pearsonCorr);
		add(sb, rankedCorr);
		add(sb, slope);

		label = String.format("n = %d. Median = %s nm", depthStats.getN(), MathUtils.rounded(median));
		id = ImageJUtils.showHistogram(TITLE, depthStats, "Match Depth (nm)", 0, 1, 0, label);
		if (ImageJUtils.isNewWindow())
			wo.add(id);

		// Plot histograms of the stats on the same window
		double[] lower = new double[filterCriteria.length];
		double[] upper = new double[lower.length];
		for (int i = 0; i < stats[0].length; i++)
		{
			double[] limits = showDoubleHistogram(stats, i, wo);
			lower[i] = limits[0];
			upper[i] = limits[1];
		}

		// Reconfigure some of the range limits
		upper[FILTER_SIGNAL] *= 2; // Make this a bit bigger
		upper[FILTER_SNR] *= 2; // Make this a bit bigger
		lower[FILTER_PRECISION] *= 0.5; // Make this a bit smaller
		double factor = 0.25;
		if (lower[FILTER_MIN_WIDTH] != 0)
			upper[FILTER_MIN_WIDTH] = 1 - Math.max(0, factor * (1 - lower[FILTER_MIN_WIDTH])); // (assuming lower is less than 1)
		if (upper[FILTER_MIN_WIDTH] != 0)
			lower[FILTER_MAX_WIDTH] = 1 + Math.max(0, factor * (upper[FILTER_MAX_WIDTH] - 1)); // (assuming upper is more than 1)

		// Create a range increment
		double[] increment = new double[lower.length];
		for (int i = 0; i < increment.length; i++)
			increment[i] = (upper[i] - lower[i]) / 10;

		// Disable some filters
		increment[FILTER_SIGNAL] = Double.POSITIVE_INFINITY;
		//increment[FILTER_SHIFT] = Double.POSITIVE_INFINITY;
		increment[FILTER_ESHIFT] = Double.POSITIVE_INFINITY;

		for (int i = 0; i < stats[0].length; i++)
		{
			lower[i] = MathUtils.round(lower[i]);
			upper[i] = MathUtils.round(upper[i]);
			increment[i] = MathUtils.round(increment[i]);
			sb.append("\t").append(lower[i]).append('-').append(upper[i]);
		}

		wo.tile();

		sb.append("\t").append(ImageJUtils.timeToString(runTime / 1000000.0));
		
		summaryTable.append(sb.toString());

		if (saveFilterRange)
		{
			GlobalSettings gs = SettingsManager.loadSettings();
			FilterSettings filterSettings = gs.getFilterSettings();

			String filename = ImageJUtils.getFilename("Filter_range_file", filterSettings.filterSetFilename);
			if (filename == null)
				return;
			filename = ImageJUtils.replaceExtension(filename, ".xml");
			filterSettings.filterSetFilename = filename;
			// Create a filter set using the ranges
			ArrayList<Filter> filters = new ArrayList<Filter>(3);
			filters.add(new MultiFilter2(lower[0], (float) lower[1], lower[2], lower[3], lower[4], lower[5], lower[6]));
			filters.add(new MultiFilter2(upper[0], (float) upper[1], upper[2], upper[3], upper[4], upper[5], upper[6]));
			filters.add(new MultiFilter2(increment[0], (float) increment[1], increment[2], increment[3], increment[4],
					increment[5], increment[6]));
			ArrayList<FilterSet> filterList = new ArrayList<FilterSet>(1);
			filterList.add(new FilterSet("Multi2", filters));
			FileOutputStream fos = null;
			try
			{
				fos = new FileOutputStream(filename);
				// Use the instance (not .toXML() method) to allow the exception to be caught
				XStreamWrapper.getInstance().toXML(filterList, fos);
				SettingsManager.saveSettings(gs);
			}
			catch (Exception e)
			{
				IJ.log("Unable to save the filter set to file: " + e.getMessage());
			}
			finally
			{
				if (fos != null)
				{
					try
					{
						fos.close();
					}
					catch (IOException e)
					{
						// Ignore
					}
				}
			}
		}
	}

	private int[] rank(ArrayList<Ranking> pc)
	{
		Collections.sort(pc);
		int[] ranking = new int[pc.size()];
		int rank = 1;
		for (Ranking r : pc)
		{
			ranking[r.index] = rank++;
		}
		return ranking;
	}

	private double[] showDoubleHistogram(StoredDataStatistics[][] stats, int i, WindowOrganiser wo)
	{
		String xLabel = filterCriteria[i].name;
		LowerLimit lower = filterCriteria[i].lower;
		UpperLimit upper = filterCriteria[i].upper;
		// [0] is all
		// [1] is matches
		// [2] is no match
		StoredDataStatistics s1 = stats[0][i];
		StoredDataStatistics s2 = stats[1][i];
		StoredDataStatistics s3 = stats[2][i];

		if (s1.getN() == 0)
			return new double[2];

		DescriptiveStatistics d = s1.getStatistics();
		double median = 0;
		Plot2 plot = null;
		String title = null;

		if (showFilterScoreHistograms)
		{
			median = d.getPercentile(50);
			String label = String.format("n = %d. Median = %s nm", s1.getN(), MathUtils.rounded(median));
			int id = ImageJUtils.showHistogram(TITLE, s1, xLabel, filterCriteria[i].minBinWidth,
					(filterCriteria[i].restrictRange) ? 1 : 0, 0, label);
			if (id == 0)
			{
				IJ.log("Failed to show the histogram: " + xLabel);
				return new double[2];
			}

			if (ImageJUtils.isNewWindow())
				wo.add(id);

			title = WindowManager.getImage(id).getTitle();

			// Reverse engineer the histogram settings
			plot = ImageJUtils.plot;
			double[] xValues = ImageJUtils.xValues;
			int bins = xValues.length;
			double yMin = xValues[0];
			double binSize = xValues[1] - xValues[0];
			double yMax = xValues[0] + (bins - 1) * binSize;

			if (s2.getN() > 0)
			{
				double[] values = s2.getValues();
				double[][] hist = ImageJUtils.calcHistogram(values, yMin, yMax, bins);

				if (hist[0].length > 0)
				{
					plot.setColor(Color.red);
					plot.addPoints(hist[0], hist[1], Plot2.BAR);
					ImageJUtils.display(title, plot);
				}
			}

			if (s3.getN() > 0)
			{
				double[] values = s3.getValues();
				double[][] hist = ImageJUtils.calcHistogram(values, yMin, yMax, bins);

				if (hist[0].length > 0)
				{
					plot.setColor(Color.blue);
					plot.addPoints(hist[0], hist[1], Plot2.BAR);
					ImageJUtils.display(title, plot);
				}
			}
		}

		// Do cumulative histogram
		double[][] h1 = MathUtils.cumulativeHistogram(s1.getValues(), true);
		double[][] h2 = MathUtils.cumulativeHistogram(s2.getValues(), true);
		double[][] h3 = MathUtils.cumulativeHistogram(s3.getValues(), true);

		if (showFilterScoreHistograms)
		{
			title = TITLE + " Cumul " + xLabel;
			plot = new Plot2(title, xLabel, "Frequency");
			// Find limits
			double[] xlimit = MathUtils.limits(h1[0]);
			xlimit = MathUtils.limits(xlimit, h2[0]);
			xlimit = MathUtils.limits(xlimit, h3[0]);
			// Restrict using the inter-quartile range 
			if (filterCriteria[i].restrictRange)
			{
				double q1 = d.getPercentile(25);
				double q2 = d.getPercentile(75);
				double iqr = (q2 - q1) * 2.5;
				xlimit[0] = MathUtils.max(xlimit[0], median - iqr);
				xlimit[1] = MathUtils.min(xlimit[1], median + iqr);
			}
			plot.setLimits(xlimit[0], xlimit[1], 0, 1.05);
			plot.addPoints(h1[0], h1[1], Plot.LINE);
			plot.setColor(Color.red);
			plot.addPoints(h2[0], h2[1], Plot.LINE);
			plot.setColor(Color.blue);
			plot.addPoints(h3[0], h3[1], Plot.LINE);
		}

		// Determine the maximum difference between the TP and FP
		double maxx1 = 0;
		double maxx2 = 0;
		double max1 = 0;
		double max2 = 0;

		// We cannot compute the delta histogram, or use percentiles
		if (s2.getN() == 0)
		{
			upper = UpperLimit.ZERO;
			lower = LowerLimit.ZERO;
		}

		final boolean requireLabel = (showFilterScoreHistograms && filterCriteria[i].requireLabel);
		if (requireLabel || upper.requiresDeltaHistogram() || lower.requiresDeltaHistogram())
		{
			if (s2.getN() != 0 && s3.getN() != 0)
			{
				LinearInterpolator li = new LinearInterpolator();
				PolynomialSplineFunction f1 = li.interpolate(h2[0], h2[1]);
				PolynomialSplineFunction f2 = li.interpolate(h3[0], h3[1]);
				for (double x : h1[0])
				{
					if (x < h2[0][0] || x < h3[0][0])
						continue;
					try
					{
						double v1 = f1.value(x);
						double v2 = f2.value(x);
						double diff = v2 - v1;
						if (diff > 0)
						{
							if (max1 < diff)
							{
								max1 = diff;
								maxx1 = x;
							}
						}
						else
						{
							if (max2 > diff)
							{
								max2 = diff;
								maxx2 = x;
							}
						}
					}
					catch (OutOfRangeException e)
					{
						// Because we reached the end
						break;
					}
				}
			}
			else
			{
				// Switch to percentiles if we have no delta histogram
				if (upper.requiresDeltaHistogram())
					upper = UpperLimit.NINETY_NINE_PERCENT;
				if (lower.requiresDeltaHistogram())
					lower = LowerLimit.ONE_PERCENT;
			}

			//			System.out.printf("Bounds %s : %s, pos %s, neg %s, %s\n", xLabel, MathUtils.rounded(getPercentile(h2, 0.01)),
			//					MathUtils.rounded(maxx1), MathUtils.rounded(maxx2), MathUtils.rounded(getPercentile(h1, 0.99)));
		}

		if (showFilterScoreHistograms)
		{
			// We use bins=1 on charts where we do not need a label
			if (requireLabel)
			{
				String label = String.format("Max+ %s @ %s, Max- %s @ %s", MathUtils.rounded(max1), MathUtils.rounded(maxx1),
						MathUtils.rounded(max2), MathUtils.rounded(maxx2));
				plot.setColor(Color.black);
				plot.addLabel(0, 0, label);
			}
			PlotWindow pw = ImageJUtils.display(title, plot);
			if (ImageJUtils.isNewWindow())
				wo.add(pw.getImagePlus().getID());
		}

		// Now compute the bounds using the desired limit
		double l, u;
		switch (lower)
		{
			case ONE_PERCENT:
				l = getPercentile(h2, 0.01);
				break;
			case MAX_NEGATIVE_CUMUL_DELTA:
				l = maxx2;
				break;
			case ZERO:
				l = 0;
				break;
			default:
				throw new RuntimeException("Missing lower limit method");
		}
		switch (upper)
		{
			case MAX_POSITIVE_CUMUL_DELTA:
				u = maxx1;
				break;
			case NINETY_NINE_PERCENT:
				u = getPercentile(h2, 0.99);
				break;
			case NINETY_NINE_NINE_PERCENT:
				u = getPercentile(h2, 0.999);
				break;
			case ZERO:
				u = 0;
				break;
			default:
				throw new RuntimeException("Missing upper limit method");
		}
		return new double[] { l, u };
	}

	/**
	 * @param h
	 *            The cumulative histogram
	 * @param p
	 *            The fraction
	 * @return The value for the given fraction
	 */
	private double getPercentile(double[][] h, double p)
	{
		double[] x = h[0];
		double[] y = h[1];
		for (int i = 0; i < x.length; i++)
		{
			if (y[i] > p)
			{
				if (i == 0)
					return x[i];
				// Interpolation
				double delta = (p - y[i - 1]) / (y[i] - y[i - 1]);
				return x[i - 1] + delta * (x[i] - x[i - 1]);
			}
		}
		return x[x.length - 1];
	}

	private static void add(StringBuilder sb, String value)
	{
		sb.append("\t").append(value);
	}

	private static void add(StringBuilder sb, int value)
	{
		sb.append("\t").append(value);
	}

	private static void add(StringBuilder sb, double value)
	{
		add(sb, MathUtils.rounded(value));
	}

	private static void addCount(StringBuilder sb, double value)
	{
		// Check if the double holds an integer count
		if ((int) value == value)
		{
			sb.append("\t").append((int) value);
		}
		else
		{
			// Otherwise add the counts using at least 2 dp
			if (value > 100)
				sb.append("\t").append(IJ.d2s(value));
			else
				add(sb, MathUtils.rounded(value));
		}
	}

	private void createTable()
	{
		if (summaryTable == null || !summaryTable.isVisible())
		{
			summaryTable = new TextWindow(TITLE, createHeader(false), "", 1000, 300);
			summaryTable.setVisible(true);
		}
	}

	private String createHeader(boolean extraRecall)
	{
		StringBuilder sb = new StringBuilder(
				"Frames\tW\tH\tMolecules\tDensity (um^-2)\tN\ts (nm)\ta (nm)\tDepth (nm)\tFixed\tGain\tReadNoise (ADUs)\tB (photons)\tb2 (photons)\tSNR\ts (px)\t");
		sb.append("Filter\t");
		sb.append("Spots\t");
		sb.append("nP\t");
		sb.append("nN\t");
		sb.append("fP\t");
		sb.append("fN\t");
		sb.append("Solver\t");
		sb.append("Fitting");

		tablePrefix = sb.toString();

		sb.append("\t");
		sb.append("% nP\t");
		sb.append("% nN\t");
		sb.append("Total\t");
		sb.append("cTP\t");
		sb.append("cFP\t");

		sb.append("cRecall\t");
		sb.append("cPrecision\t");
		sb.append("cF1\t");
		sb.append("cJaccard\t");

		sb.append("Fail cTP\t");
		sb.append("Fail cFP\t");
		sb.append("TP\t");
		sb.append("FP\t");
		sb.append("Recall\t");
		sb.append("Precision\t");
		sb.append("F1\t");
		sb.append("Jaccard\t");

		sb.append("pF1\t");
		sb.append("pJaccard\t");

		sb.append("Med.Distance (nm)\t");
		sb.append("Med.Depth (nm)\t");
		sb.append("Correlation\t");
		sb.append("Ranked\t");
		sb.append("Slope\t");

		createFilterCriteria();
		for (FilterCriteria f : filterCriteria)
			sb.append(f.name).append('\t');

		sb.append("Run time");
		return sb.toString();
	}

	private double getSa()
	{
		final double sa = PSFCalculator.squarePixelAdjustment(simulationParameters.s, simulationParameters.a);
		return sa;
	}

	/**
	 * Updates the given configuration using the latest settings used in benchmarking.
	 *
	 * @param pConfig
	 *            the configuration
	 * @return true, if successful
	 */
	public static boolean updateConfiguration(FitEngineConfiguration pConfig)
	{
		final FitConfiguration pFitConfig = pConfig.getFitConfiguration();

		pFitConfig.setInitialPeakStdDev(fitConfig.getInitialPeakStdDev0());
		pConfig.setFitting(config.getFitting());
		pFitConfig.setFitSolver(fitConfig.getFitSolver());
		pFitConfig.setFitFunction(fitConfig.getFitFunction());
		pConfig.setIncludeNeighbours(config.isIncludeNeighbours());
		pConfig.setNeighbourHeightThreshold(config.getNeighbourHeightThreshold());
		pFitConfig.setDuplicateDistance(fitConfig.getDuplicateDistance());

		pFitConfig.setMaxIterations(fitConfig.getMaxIterations());
		pFitConfig.setMaxFunctionEvaluations(fitConfig.getMaxFunctionEvaluations());

		// MLE settings
		pFitConfig.setModelCamera(fitConfig.isModelCamera());
		pFitConfig.setBias(0);
		pFitConfig.setReadNoise(0);
		pFitConfig.setAmplification(0);
		pFitConfig.setEmCCD(fitConfig.isEmCCD());
		pFitConfig.setSearchMethod(fitConfig.getSearchMethod());
		pFitConfig.setRelativeThreshold(fitConfig.getRelativeThreshold());
		pFitConfig.setAbsoluteThreshold(fitConfig.getAbsoluteThreshold());
		pFitConfig.setGradientLineMinimisation(fitConfig.isGradientLineMinimisation());

		// LSE settings
		pFitConfig.setFitCriteria(fitConfig.getFitCriteria());
		pFitConfig.setSignificantDigits(fitConfig.getSignificantDigits());
		pFitConfig.setDelta(fitConfig.getDelta());
		pFitConfig.setLambda(fitConfig.getLambda());

		return true;
	}
}
