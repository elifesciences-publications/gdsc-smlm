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

import uk.ac.sussex.gdsc.core.ij.BufferedTextWindow;
import uk.ac.sussex.gdsc.core.ij.HistogramPlot.HistogramPlotBuilder;
import uk.ac.sussex.gdsc.core.ij.ImageJUtils;
import uk.ac.sussex.gdsc.core.ij.gui.ExtendedGenericDialog;
import uk.ac.sussex.gdsc.core.ij.gui.Plot2;
import uk.ac.sussex.gdsc.core.ij.plugin.WindowOrganiser;
import uk.ac.sussex.gdsc.core.match.AssignmentComparator;
import uk.ac.sussex.gdsc.core.match.AucCalculator;
import uk.ac.sussex.gdsc.core.match.BasePoint;
import uk.ac.sussex.gdsc.core.match.Coordinate;
import uk.ac.sussex.gdsc.core.match.FractionClassificationResult;
import uk.ac.sussex.gdsc.core.match.FractionalAssignment;
import uk.ac.sussex.gdsc.core.match.ImmutableFractionalAssignment;
import uk.ac.sussex.gdsc.core.utils.FastCorrelator;
import uk.ac.sussex.gdsc.core.utils.MathUtils;
import uk.ac.sussex.gdsc.core.utils.RampedScore;
import uk.ac.sussex.gdsc.core.utils.SettingsList;
import uk.ac.sussex.gdsc.core.utils.SimpleArrayUtils;
import uk.ac.sussex.gdsc.core.utils.Statistics;
import uk.ac.sussex.gdsc.core.utils.StoredData;
import uk.ac.sussex.gdsc.core.utils.TurboList;
import uk.ac.sussex.gdsc.smlm.data.config.ConfigurationException;
import uk.ac.sussex.gdsc.smlm.data.config.FitProtos.DataFilterMethod;
import uk.ac.sussex.gdsc.smlm.data.config.FitProtos.DataFilterType;
import uk.ac.sussex.gdsc.smlm.data.config.FitProtos.RelativeParameter;
import uk.ac.sussex.gdsc.smlm.data.config.PSFProtos.PSF;
import uk.ac.sussex.gdsc.smlm.data.config.PsfHelper;
import uk.ac.sussex.gdsc.smlm.data.config.UnitProtos.DistanceUnit;
import uk.ac.sussex.gdsc.smlm.data.config.UnitProtos.IntensityUnit;
import uk.ac.sussex.gdsc.smlm.engine.FitConfiguration;
import uk.ac.sussex.gdsc.smlm.engine.FitEngineConfiguration;
import uk.ac.sussex.gdsc.smlm.filters.MaximaSpotFilter;
import uk.ac.sussex.gdsc.smlm.filters.Spot;
import uk.ac.sussex.gdsc.smlm.function.gaussian.Gaussian2DFunction;
import uk.ac.sussex.gdsc.smlm.function.gaussian.GaussianFunctionFactory;
import uk.ac.sussex.gdsc.smlm.function.gaussian.GaussianOverlapAnalysis;
import uk.ac.sussex.gdsc.smlm.ij.plugins.ResultsMatchCalculator.PeakResultPoint;
import uk.ac.sussex.gdsc.smlm.ij.settings.SettingsManager;
import uk.ac.sussex.gdsc.smlm.ij.utils.ImageJImageConverter;
import uk.ac.sussex.gdsc.smlm.model.camera.CameraModel;
import uk.ac.sussex.gdsc.smlm.results.Gaussian2DPeakResultCalculator;
import uk.ac.sussex.gdsc.smlm.results.Gaussian2DPeakResultHelper;
import uk.ac.sussex.gdsc.smlm.results.MemoryPeakResults;
import uk.ac.sussex.gdsc.smlm.results.procedures.StandardResultProcedure;

import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.procedure.TIntObjectProcedure;
import gnu.trove.procedure.TObjectProcedure;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Overlay;
import ij.gui.Plot;
import ij.plugin.PlugIn;
import ij.text.TextWindow;

import org.apache.commons.lang3.concurrent.ConcurrentRuntimeException;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.commons.math3.util.FastMath;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Filters the benchmark spot image created by CreateData plugin to identify candidates and then
 * assess the filter.
 */
public class BenchmarkSpotFilter implements PlugIn {
  /** The title. */
  static final String TITLE = "Filter Spot Data";

  private static FitConfiguration fitConfig;
  private static FitEngineConfiguration config;
  private static double search = 1;
  private static boolean differenceFilter;
  private static double differenceSmooth = 3;
  private static int minSearch = 1;
  private static int maxSearch = 1;
  private static double border = 1;
  private static boolean useCached = true;
  private static boolean[] batchPlot;
  private static String[] batchPlotNames;
  private static final String[] SELECTION_METHOD;
  private static int selectionMethod = 2;

  static {
    config = new FitEngineConfiguration();
    fitConfig = config.getFitConfiguration();
    batchPlot = new boolean[BatchResult.getNumberOfScores()];
    batchPlotNames = new String[batchPlot.length];
    for (int i = 0; i < batchPlot.length; i++) {
      batchPlotNames[i] = BatchResult.getScoreName(i);
    }
    SELECTION_METHOD = new String[3];
    SELECTION_METHOD[0] = BatchResult.getScoreName(0);
    SELECTION_METHOD[1] = BatchResult.getScoreName(1);
    SELECTION_METHOD[2] = BatchResult.getScoreName(0) + "+" + BatchResult.getScoreName(1);
  }

  private static final String[] MATCHING_METHOD = {"Single", "Multi", "Greedy"};
  @SuppressWarnings("unused")
  private static final int METHOD_SINGLE = 0;
  private static final int METHOD_MULTI = 1;
  private static final int METHOD_GREEDY = 2;

  private static double sAnalysisBorder = 2;
  private static boolean hardBorder = true;
  private static int matchingMethod = METHOD_MULTI;

  /** The last border used in analysis. */
  static Rectangle lastAnalysisBorder;
  private static double upperDistance = 1.5;
  private static double lowerDistance = 0.5;
  private static double upperSignalFactor = 2;
  private static double lowerSignalFactor = 1;
  private static boolean filterRelativeDistances = true;
  private static boolean scoreRelativeDistances = true;
  private double matchDistance;
  private double lowerMatchDistance;
  private static double recallFraction = 100;
  private static boolean showPlot = true;
  private static boolean rankByIntensity;
  private static boolean showFailuresPlot;
  private static boolean showTP;
  private static boolean showFP;
  private static boolean showFN;
  private static boolean sDebug;

  private static boolean batchMean = true;
  private static boolean batchGaussian = true;
  private static boolean batchCircular;
  private static boolean batchMedian;

  private boolean extraOptions;
  private boolean debug;
  private boolean batchMode;
  private long time;

  // Cache batch results
  private static SettingsList batchSettings;
  private static ArrayList<BatchResult[]> cachedBatchResults = new ArrayList<>();

  private static int id = 1;

  private static BufferedTextWindow summaryTable;
  private static BufferedTextWindow batchSummaryTable;

  private ImagePlus imp;
  private MemoryPeakResults results;
  private Gaussian2DPeakResultCalculator calculator;
  private CameraModel cameraModel;
  private float[] weights;
  private float resultsBackground = Float.NaN;
  private Rectangle bounds;
  private CreateData.SimulationParameters simulationParameters;

  private static TIntObjectHashMap<PsfSpot[]> actualCoordinates;
  private static int lastId = -1;
  // private static boolean lastRelativeDistances = false;

  private WindowOrganiser windowOrganiser;

  /**
   * The prefix for the results table header. Contains all the standard header data about the input
   * results data.
   */
  public static String tablePrefix;

  /**
   * The prefix for the results table entry. Contains all the standard data about the input results
   * data.
   */
  public static String resultPrefix;

  // Used by the Benchmark Spot Fit plugin
  private static int filterResultsId;

  /** The filter result. */
  static BenchmarkFilterResult filterResult;

  /**
   * Store the benchmark filter result.
   */
  public static class BenchmarkFilterResult {
    /** The simulation id. */
    public final int simulationId;

    /** The id. */
    public final int id = ++filterResultsId;

    /** The filter results. */
    public TIntObjectHashMap<FilterResult> filterResults;

    /** The configuration for the spot filter. */
    public FitEngineConfiguration config;

    /** The spot filter. */
    public MaximaSpotFilter spotFilter;

    /** The Area-Under precision-recall Curve (AUC). */
    public double auc;

    /** The recall when all spots are ranked by spot intensity. */
    double[] recall;

    /** The precision when all spots are ranked by spot intensity. */
    double[] precision;

    /** The Jaccard when all spots are ranked by spot intensity. */
    double[] jaccard;

    /** The correlation when all spots are ranked by spot intensity. */
    double[] correlation;

    /** The max index for the Jaccard score. */
    int maxIndex;

    /** The fraction index where the recall achieves the fraction of the maximum.. */
    int fractionIndex;

    /** The cumulutive histogram of the number of negatives preceding each positive. */
    public double[][] cumul;

    /** The statistics of the number of negatives preceding each positive. */
    public StoredData stats;

    /**
     * The Area-Under precision-recall Curve (AUC) computed using the maximum precision for
     * {@code recall >= r}.
     */
    public double auc2;

    /** The slope of the regression between the actual and candidate spot intensity for matches. */
    public double slope;

    /** The intensity of the actual spots that match. */
    public double[] i1;

    /** The intensity of the filter candidates that match. */
    public double[] i2;

    /** The intensity of all the spots. */
    public double[] intensity;

    /** The time. */
    public long time;

    /**
     * Instantiates a new benchmark filter result.
     *
     * @param simulationId the simulation id
     * @param filterResults the filter results
     * @param config the config
     * @param spotFilter the spot filter
     */
    public BenchmarkFilterResult(int simulationId, TIntObjectHashMap<FilterResult> filterResults,
        FitEngineConfiguration config, MaximaSpotFilter spotFilter) {
      this.simulationId = simulationId;
      this.filterResults = filterResults;
      this.config = config;
      this.spotFilter = spotFilter;
    }
  }

  private static class BatchResult {
    public double auc;
    public double jaccard;
    public double precision;
    public double recall;
    public long time;
    public DataFilterMethod dataFilter;
    public double param;
    public int search;
    public double param2;

    public BatchResult(BenchmarkFilterResult filterResult, DataFilterMethod dataFilter,
        double param, int search, double param2) {
      if (filterResult != null) {
        this.auc = filterResult.auc;
        this.jaccard = filterResult.jaccard[filterResult.maxIndex];
        this.precision = filterResult.precision[filterResult.maxIndex];
        this.recall = filterResult.recall[filterResult.maxIndex];
        this.time = filterResult.time;
      }
      this.dataFilter = dataFilter;
      this.param = param;
      this.search = search;
      if (param2 > 0) {
        this.param2 = param2;
      }
    }

    public double getScore(int index) {
      if (index == 0) {
        return auc;
      }
      if (index == 1) {
        return jaccard;
      }
      if (index == 2) {
        return precision;
      }
      if (index == 3) {
        return recall;
      }
      if (index == 4) {
        return time / 1e6;
      }
      return 0;
    }

    public static String getScoreName(int index) {
      if (index == 0) {
        return "AUC";
      }
      if (index == 1) {
        return "Max Jaccard";
      }
      if (index == 2) {
        return "Precision (at Max Jaccard)";
      }
      if (index == 3) {
        return "Recall (at Max Jaccard)";
      }
      if (index == 4) {
        return "Time (ms)";
      }
      return "";
    }

    public static int getNumberOfScores() {
      return 5;
    }

    public String getName() {
      if (param2 > 0) {
        return String.format("Difference %s (@%s):%d", dataFilter.toString(),
            // param2 was absolute so convert to relative since the name is used in relative plots
            MathUtils.roundUsingDecimalPlacesToBigDecimal(param2 / config.getHwhmMin(), 3)
                .toPlainString(),
            search);
      }
      return String.format("Single %s:%d", dataFilter.toString(), search);
    }
  }

  /**
   * The Class ScoredSpot.
   */
  public static class ScoredSpot {
    /** The match. */
    final boolean match;

    /** The scores. */
    double[] scores;

    /** The score. */
    double score;
    // Total intensity of spots we matched
    private double intensity;

    /** The background. */
    final float background;

    /** The spot. */
    final Spot spot;

    /** The fails. */
    int fails;

    /**
     * Instantiates a new scored spot.
     *
     * @param match the match
     * @param score the score
     * @param intensity the intensity
     * @param spot the spot
     * @param background the background
     */
    public ScoredSpot(boolean match, double score, double intensity, Spot spot, float background) {
      this.match = match;
      this.spot = spot;
      this.background = background;
      this.fails = 0;
      add(score, intensity);
    }

    /**
     * Instantiates a new scored spot.
     *
     * @param match the match
     * @param spot the spot
     * @param background the background
     * @param fails the fails
     */
    public ScoredSpot(boolean match, Spot spot, float background, int fails) {
      this.match = match;
      this.spot = spot;
      this.background = background;
      this.fails = fails;
    }

    /**
     * Adds the result to the scored spot.
     *
     * @param score the score
     * @param intensity the intensity
     */
    void add(double score, double intensity) {
      if (scores == null) {
        scores = new double[] {score};
        this.score = score;
        this.intensity = intensity;
      } else {
        final int size = scores.length;
        scores = Arrays.copyOf(scores, size + 1);
        scores[size] = score;
        this.score += score;
        this.intensity += intensity;
      }
    }

    /**
     * Gets the score.
     *
     * @param index the index
     * @return the score
     */
    public double getScore(int index) {
      return (scores != null && index < scores.length) ? scores[index] : 0;
    }

    /**
     * Get the score.
     *
     * @return The score
     */
    double getScore() {
      return score;
    }

    /**
     * Get the opposite of the score.
     *
     * @return the anti-score
     */
    public double antiScore() {
      // The use of partial scoring mimicks using multiple distance
      // thresholds and taking an average of scores.
      // For a single experiment at a single distance threshold a spot can
      // match 0, 1 or more actual results. This would be:
      // 0 = TP=0 ,FP=1
      // 1 = TP=1 ,FP=0
      // 2+ = TP=2+,FP=0 (because it doesn't 'not match' anything)
      // So for now I will use FP (anti-score) in the range 0-1.

      // Only ever allow an anti-score in the range 0-1
      return FastMath.max(0, 1 - score);
    }

    /**
     * Compare the two objects.
     *
     * <p>Returns a comparison of the spot intensity, highest first.
     *
     * @param o1 the first object
     * @param o2 the second object
     * @return the comparison
     */
    public static int compare(ScoredSpot o1, ScoredSpot o2) {
      return Double.compare(o2.spot.intensity, o1.spot.intensity);
    }

    /**
     * Gets the intensity of the spot without the background.
     *
     * @return the intensity
     */
    public float getIntensity() {
      return spot.intensity - background;
    }
  }

  /**
   * Store a filter result.
   */
  public static class FilterResult {

    /** The frame. */
    final int frame;

    /** The result. */
    final FractionClassificationResult result;

    /** The spots. */
    final ScoredSpot[] spots;

    /** The actual. */
    final PsfSpot[] actual;

    /** The actual assignment. */
    final boolean[] actualAssignment;

    /**
     * Instantiates a new filter result.
     *
     * @param frame the frame
     * @param result the result
     * @param spots the spots
     * @param actual the actual
     * @param actualAssignment the actual assignment
     */
    public FilterResult(int frame, FractionClassificationResult result, ScoredSpot[] spots,
        PsfSpot[] actual, boolean[] actualAssignment) {
      this.frame = frame;
      this.result = result;
      this.spots = spots;
      this.actual = actual;
      this.actualAssignment = actualAssignment;
    }
  }

  /**
   * Used to allow multi-threading of the PSF overlap computation.
   */
  private class OverlapWorker implements Runnable {
    volatile boolean finished;
    final BlockingQueue<Integer> jobs;
    final TIntObjectHashMap<ArrayList<Coordinate>> originalCoordinates;
    final TIntObjectHashMap<PsfSpot[]> coordinates;

    public OverlapWorker(BlockingQueue<Integer> jobs,
        TIntObjectHashMap<ArrayList<Coordinate>> originalCoordinates) {
      this.jobs = jobs;
      this.originalCoordinates = originalCoordinates;
      this.coordinates = new TIntObjectHashMap<>();
    }

    @Override
    public void run() {
      try {
        while (true) {
          final Integer job = jobs.take();
          if (job == null || job.intValue() < 0) {
            break;
          }
          if (!finished) {
            // Only run if not finished to allow queue to be emptied
            run(job.intValue());
          }
        }
      } catch (final InterruptedException ex) {
        System.out.println(ex.toString());
        throw new RuntimeException(ex);
      } finally {
        finished = true;
      }
    }

    private void run(int frame) {
      if (ImageJUtils.isInterrupted()) {
        finished = true;
        return;
      }

      showProgress();

      // Extract the data
      final PsfSpot[] actual = getCoordinates(originalCoordinates, frame);
      coordinates.put(frame, actual);

      if (actual.length == 0) {
        return;
      }

      // Here we approximate the PSF as a Gaussian adjusted for square pixels.

      // Determine spots (1) that have an overlap with other spots (2).
      // In practice this will be any spot within 2SD1 + 2SD2.

      // Pre-compute the adjusted pixel widths for each PSF
      final double[] sa = new double[actual.length];
      final double[] sa2 = new double[actual.length];
      for (int i = 0; i < actual.length; i++) {
        sa[i] = PsfCalculator.squarePixelAdjustment(
            calculator.getStandardDeviation(actual[i].peakResult.getParameters()), 1);
        sa2[i] = 2 * sa[i];
      }

      final double[] allParams =
          new double[1 + Gaussian2DFunction.PARAMETERS_PER_PEAK * actual.length];
      final int flags = GaussianFunctionFactory.FIT_SIMPLE_NS_NB_FIXED;
      for (int i = 0; i < actual.length; i++) {
        // Bounding rectangle for the spot. This serves as the reference frame 0,0
        final float cx = actual[i].getX();
        final float cy = actual[i].getY();

        GaussianOverlapAnalysis overlapAnalysis = null;

        // Check for overlap
        int offset = 0;
        for (int j = 0; j < actual.length; j++) {
          if (i == j) {
            continue;
          }
          final double dx = (actual[j].getX() - cx);
          final double dy = (actual[j].getY() - cy);
          final double d2 = dx * dx + dy * dy;
          final double threshold = sa2[i] + sa2[j];
          if (d2 <= threshold * threshold) {
            // These overlap.

            // Initialise a Gaussian2D function for i
            if (overlapAnalysis == null) {
              final double[] params = new double[1 + Gaussian2DFunction.PARAMETERS_PER_PEAK];
              params[Gaussian2DFunction.SIGNAL] = actual[i].peakResult.getIntensity();
              params[Gaussian2DFunction.X_POSITION] = cx;
              params[Gaussian2DFunction.Y_POSITION] = cy;
              params[Gaussian2DFunction.X_SD] = params[Gaussian2DFunction.Y_SD] = sa[i];
              final int maxx = GaussianOverlapAnalysis.getRange(sa[i], 2);
              overlapAnalysis = new GaussianOverlapAnalysis(flags, null, params, maxx, maxx);
            }

            // Accumulate the function for j
            allParams[offset + Gaussian2DFunction.SIGNAL] = actual[j].peakResult.getIntensity();
            allParams[offset + Gaussian2DFunction.X_POSITION] = actual[j].peakResult.getXPosition();
            allParams[offset + Gaussian2DFunction.Y_POSITION] = actual[j].peakResult.getYPosition();
            allParams[offset + Gaussian2DFunction.X_SD] =
                allParams[offset + Gaussian2DFunction.Y_SD] = sa[j];
            offset += Gaussian2DFunction.PARAMETERS_PER_PEAK;
          }
        }

        if (overlapAnalysis != null) {
          final double[] overlapParams = Arrays.copyOf(allParams, 1 + offset);
          overlapAnalysis.add(overlapParams, false);
          actual[i].backgroundOffset = (float) overlapAnalysis.getWeightedbackground();

          // This is not currently used.
          // Computation of this would depend on how a filter is estimating the signal.
          // The offset should be computed with the same method to create a 'fair' signal offset.
          // actual[i].intensityOffset = ?
        }
      }
    }

    /**
     * Return an array of PSF spots for the given time point. Returns an empty array if there are no
     * coordinates.
     *
     * @param coords the coords
     * @param time the time
     * @return The array list
     */
    public PsfSpot[] getCoordinates(TIntObjectHashMap<ArrayList<Coordinate>> coords, int time) {
      final ArrayList<Coordinate> list1 = coords.get(time);
      if (list1 != null) {
        final PsfSpot[] list2 = new PsfSpot[list1.size()];
        int index = 0;
        for (final Coordinate c : list1) {
          final PeakResultPoint p = (PeakResultPoint) c;
          final PsfSpot spot = new PsfSpot(p.getTime(), p.getX(), p.getY(), p.peakResult);
          list2[index++] = spot;

          // Compute the amplitude.
          final float[] params = spot.peakResult.getParameters();
          spot.amplitude = calculator.getPixelAmplitude(params);
        }
        return list2;
      }
      return new PsfSpot[0];
    }
  }

  /**
   * Used to allow multi-threading of the fitting method.
   */
  private class Worker implements Runnable {
    volatile boolean finished;
    final BlockingQueue<Integer> jobs;
    final ImageStack stack;
    final MaximaSpotFilter spotFilter;
    final float background;
    final TIntObjectHashMap<FilterResult> results;

    float[] data;
    long time;

    public Worker(BlockingQueue<Integer> jobs, ImageStack stack, MaximaSpotFilter spotFilter,
        float background) {
      this.jobs = jobs;
      this.stack = stack;
      this.spotFilter = (MaximaSpotFilter) spotFilter.copy();
      this.results = new TIntObjectHashMap<>();
      this.background = background;

      // Do not assume that cloning will preserve the weights
      if (cameraModel.isPerPixelModel() && spotFilter.isWeighted()) {
        this.spotFilter.setWeights(weights, bounds.width, bounds.height);
      }
    }

    @Override
    public void run() {
      try {
        while (true) {
          final Integer job = jobs.take();
          if (job == null || job.intValue() < 0) {
            break;
          }
          if (!finished) {
            // Only run if not finished to allow queue to be emptied
            run(job.intValue());
          }
        }
      } catch (final InterruptedException ex) {
        System.out.println(ex.toString());
        throw new RuntimeException(ex);
      } finally {
        finished = true;
      }
    }

    private void run(int frame) {
      if (ImageJUtils.isInterrupted()) {
        finished = true;
        return;
      }

      showProgress();

      // Extract the data
      data = ImageJImageConverter.getData(stack.getPixels(frame), stack.getWidth(),
          stack.getHeight(), null, data);

      // Use this code to subtract the background before filtering. This produces different results
      // from using the raw data.
      // float background = this.background;
      // if (background != 0)
      // {
      // for (int i = stack.getWidth() * stack.getHeight(); i-- > 0;)
      // data[i] -= background;
      // }
      // background = 0;

      cameraModel.removeBiasAndGain(data);

      final long start = System.nanoTime();
      Spot[] spots = spotFilter.rank(data, stack.getWidth(), stack.getHeight());
      time += System.nanoTime() - start;

      // Debug the candidates
      // if (debug && frame == 5)
      // {
      // StringBuilder sb = new StringBuilder();
      // sb.append(spotFilter.getDescription()).append("\n");
      // for (int i = 0; i < spots.length; i++)
      // {
      // sb.append(String.format("Fit %d [%d,%d = %.1f]\n", i, spots[i].x, spots[i].y,
      // spots[i].intensity));
      // }
      // System.out.print(sb.toString());
      // }

      // Score the spots that are matches
      PsfSpot[] actual = actualCoordinates.get(frame);
      if (actual == null) {
        actual = new PsfSpot[0];
      }

      // We do not remove results at the border from analysis.
      // We can just mark them to contribute less to the score.
      final double[] actualWeight = new double[actual.length];
      final double[] spotsWeight = new double[spots.length];
      double actualLength = actual.length;
      double spotsLength = spots.length;
      if (lastAnalysisBorder.x > 0) {
        actualLength = spotsLength = 0;

        final int analysisBorder = lastAnalysisBorder.x;
        final int xlimit = lastAnalysisBorder.x + lastAnalysisBorder.width;
        final int ylimit = lastAnalysisBorder.y + lastAnalysisBorder.height;
        // Create a Tukey window weighting from the border to the edge.
        // The type of weighting could be user configurable, e.g. Hard, Tukey, Linear, etc.
        final RampedScore weighting = new RampedScore(0, analysisBorder);
        for (int i = 0; i < actual.length; i++) {
          final PsfSpot c = actual[i];
          actualWeight[i] =
              getWeight(c.getX(), c.getY(), analysisBorder, xlimit, ylimit, weighting);
          actualLength += actualWeight[i];
        }

        for (int i = 0; i < spots.length; i++) {
          final Spot s = spots[i];
          // Add half-pixel offset
          spotsWeight[i] =
              getWeight(s.x + 0.5f, s.y + 0.5f, analysisBorder, xlimit, ylimit, weighting);
          spotsLength += spotsWeight[i];
        }

        // option for hard border to match the old scoring.
        // Create smaller arrays using only those with a weighting of 1.
        if (hardBorder) {
          final PsfSpot[] actual2 = new PsfSpot[actual.length];
          int count = 0;
          for (int i = 0; i < actual.length; i++) {
            if (actualWeight[i] == 1) {
              actualWeight[count] = 1;
              actual2[count++] = actual[i];
            }
          }
          actual = Arrays.copyOf(actual2, count);

          final Spot[] spots2 = new Spot[spots.length];
          count = 0;
          for (int i = 0; i < spots.length; i++) {
            if (spotsWeight[i] == 1) {
              spotsWeight[count] = 1;
              spots2[count++] = spots[i];
            }
          }
          spots = Arrays.copyOf(spots2, count);

          // Update lengths
          actualLength = actual.length;
          spotsLength = spots.length;
        }
      } else {
        Arrays.fill(actualWeight, 1);
        Arrays.fill(spotsWeight, 1);
      }

      final ScoredSpot[] scoredSpots = new ScoredSpot[spots.length];
      FractionClassificationResult result;

      // Store the count of false positives since the last true positive
      int fails = 0;

      final int actualSize = actual.length;
      final boolean[] actualAssignment = new boolean[actualSize];
      if (actual.length > 0) {
        final SpotCoordinate[] predicted = getCoordinates(spots);

        // Use the distance to the true location to score the candidate
        final RampedScore rampedScore = new RampedScore(lowerMatchDistance, matchDistance);
        final RampedScore rampedSignalScore =
            (upperSignalFactor > 0) ? new RampedScore(lowerSignalFactor, upperSignalFactor) : null;

        // Candidates may be close to many localisations. In order to compute the signal
        // factor correctly we have computed the signal offset for each spot with overlapping PSFs.
        // This is used to raise the spot intensity when computing the signal factor.

        // Compute assignments
        final TurboList<FractionalAssignment> fractionalAssignments =
            new TurboList<>(predicted.length * 3);

        final double dmin = matchDistance * matchDistance;
        final int predictedSize = predicted.length;
        for (int j = 0; j < predictedSize; j++) {
          final float x = predicted[j].getX();
          final float y = predicted[j].getY();
          // Any spots that match
          for (int i = 0; i < actualSize; i++) {
            final double dx = (x - actual[i].getX());
            final double dy = (y - actual[i].getY());
            final double d2 = dx * dx + dy * dy;
            if (d2 <= dmin) {
              final double d = Math.sqrt(d2);
              double score = rampedScore.score(d);
              final double intensity = getIntensity(actual[i]);
              if (rampedSignalScore != null) {
                // Adjust intensity using the surrounding PSF contributions
                // final double rsf = intensity / (predicted[j].spot.intensity - background);
                final double rsf = (actual[i].backgroundOffset + intensity)
                    / (predicted[j].spot.intensity - background);
                // Normalise so perfect is zero
                final double sf = Math.abs((rsf < 1) ? 1 - 1 / rsf : rsf - 1);
                score *= rampedSignalScore.score(sf);
              }
              score = RampedScore.flatten(score, 256);

              if (score == 0) {
                continue;
              }

              double distance = 1 - score;
              if (distance == 0) {
                // In the case of a match below the distance and signal factor thresholds
                // the distance will be 0. To distinguish between candidates all below
                // the thresholds just take the closest.
                // We know d2 is below dmin so we subtract the delta.
                distance -= (dmin - d2);
              }

              // Store the match
              fractionalAssignments.add(new ImmutableFractionalAssignment(i, j, distance, score));
            }
          }
        }

        final FractionalAssignment[] assignments =
            fractionalAssignments.toArray(new FractionalAssignment[fractionalAssignments.size()]);
        // sort the assignments
        AssignmentComparator.sort(assignments);

        // Assign matches
        double tp = 0;
        double fp;
        final double[] predictedScore = new double[predictedSize];
        if (matchingMethod == METHOD_GREEDY) {
          // Spots can match as many actual results as they can, first match wins
          int actualRemaining = actualSize;

          for (final FractionalAssignment a : assignments) {
            final int i = a.getTargetId();
            if (!actualAssignment[i]) {
              final int j = a.getPredictedId();
              actualAssignment[i] = true;
              final double intensity = getIntensity(actual[i]);
              final double tpScore = a.getScore() * actualWeight[i];
              if (scoredSpots[j] == null) {
                scoredSpots[j] = new ScoredSpot(true, tpScore, intensity, spots[j], background);
              } else {
                scoredSpots[j].add(tpScore, intensity);
              }
              tp += tpScore;
              predictedScore[j] += tpScore;
              if (--actualRemaining == 0) {
                break;
              }
            }
          }
        } else if (matchingMethod == METHOD_MULTI) {
          // Spots can match as many actual results as they can. Matching is iterative
          // so only the best match is computed for each spot per round.
          int actualRemaining = actualSize;

          // Flag to indicate a match was made
          boolean processAgain = true;
          OUTER: while (processAgain) {
            processAgain = false;
            final boolean[] predictedAssignment = new boolean[predictedSize];
            for (final FractionalAssignment a : assignments) {
              final int i = a.getTargetId();
              if (!actualAssignment[i]) {
                final int j = a.getPredictedId();
                if (!predictedAssignment[j]) {
                  actualAssignment[i] = true;
                  predictedAssignment[j] = true;
                  processAgain = true;
                  final double intensity = getIntensity(actual[i]);
                  final double tpScore = a.getScore() * actualWeight[i];
                  if (scoredSpots[j] == null) {
                    scoredSpots[j] = new ScoredSpot(true, tpScore, intensity, spots[j], background);
                  } else {
                    scoredSpots[j].add(tpScore, intensity);
                  }
                  tp += tpScore;
                  predictedScore[j] += tpScore;
                  if (--actualRemaining == 0) {
                    break OUTER;
                  }
                }
              }
            }
          }
        } else {
          // matchingMethod == METHOD_SINGLE
          // Spots can match only one actual result

          final boolean[] predictedAssignment = new boolean[predictedSize];

          int predictedRemaining = predictedSize;
          int actualRemaining = actualSize;

          for (final FractionalAssignment a : assignments) {
            final int i = a.getTargetId();
            if (!actualAssignment[i]) {
              final int j = a.getPredictedId();
              if (!predictedAssignment[j]) {
                actualAssignment[i] = true;
                predictedAssignment[j] = true;
                final double tpScore = a.getScore() * actualWeight[i];
                scoredSpots[j] =
                    new ScoredSpot(true, tpScore, getIntensity(actual[i]), spots[j], background);
                tp += tpScore;
                predictedScore[j] = tpScore;
                if (--actualRemaining == 0 || --predictedRemaining == 0) {
                  break;
                }
              }
            }
          }
        }

        // Compute the FP.
        // Note: The TP score is the match score multiplied by the weight of the actual spot.
        // Although a predicted point can accumulate more than its weight for TP matches (due
        // to multiple matching and the fuzzy border), no predicted point can score less than
        // its weight. This means:
        // TP + FN = nActual
        // TP + FP >= nPredicted (due to fuzzy border)
        //
        // Note: This score scenario is the same as if using a hard border exclusion and
        // multi-matching where TP can be above 1 but FP is not.
        //
        // This does mean that a spot in the border that matches a result in the image
        // can have a high TP score and very low FP score. Hopefully this has little effect
        // in practice.

        fp = spotsLength;
        for (int j = 0; j < predictedScore.length; j++) {
          if (predictedScore[j] > spotsWeight[j]) {
            predictedScore[j] = spotsWeight[j];
          }
          fp -= predictedScore[j];
        }

        result = new FractionClassificationResult(tp, fp, 0, actualLength - tp);

        // Store the number of fails (negatives) before each positive
        for (int i = 0; i < spots.length; i++) {
          if (scoredSpots[i] == null) {
            scoredSpots[i] = new ScoredSpot(false, spots[i], background, fails++);
          } else {
            scoredSpots[i].fails = fails++;
            if (scoredSpots[i].match) {
              // if (fails > 60)
              // System.out.printf("%d @ %d : %d,%d\n", fails, frame, scoredSpots[i].spot.x,
              // scoredSpots[i].spot.y);
              fails = 0;
            }
          }
        }
      } else {
        // No results.
        // All spots are false positives
        result = new FractionClassificationResult(0, spotsLength, 0, 0);
        for (int i = 0; i < spots.length; i++) {
          scoredSpots[i] = new ScoredSpot(false, spots[i], background, fails++);
        }
      }

      if (debug) {
        System.out.printf("Frame %d : N = %.2f, TP = %.2f, FP = %.2f, R = %.2f, P = %.2f%n", frame,
            actualLength, result.getTruePositives(), result.getFalsePositives(), result.getRecall(),
            result.getPrecision());
      }

      results.put(frame, new FilterResult(frame, result, scoredSpots, actual, actualAssignment));
    }

    private double getWeight(float x, float y, int analysisBorder, int xlimit, int ylimit,
        RampedScore weighting) {
      // Distance outside the border
      double dx = 0;
      double dy = 0;
      if (x < analysisBorder) {
        dx = analysisBorder - x;
      } else if (x > xlimit) {
        dx = x - xlimit;
      }
      if (y < analysisBorder) {
        dy = analysisBorder - y;
      } else if (y > ylimit) {
        dy = y - ylimit;
      }
      return (dx == 0 && dy == 0) ? 1 : weighting.score(dx) * weighting.score(dy);
    }

    private double getIntensity(final PsfSpot psfSpot) {
      // Use the amplitude as all spot filters currently estimate the height, not the total signal
      // final double intensity = calculator.getAmplitude(psfSpot.peakResult.getParameters());
      // return intensity;
      return psfSpot.amplitude;
    }

    private SpotCoordinate[] getCoordinates(Spot[] spots) {
      final SpotCoordinate[] coords = new SpotCoordinate[spots.length];
      for (int i = 0; i < spots.length; i++) {
        coords[i] = new SpotCoordinate(spots[i]);
      }
      return coords;
    }

    private class SpotCoordinate extends BasePoint {
      Spot spot;

      public SpotCoordinate(Spot spot) {
        // Centre on the middle of the pixel
        super(spot.x + 0.5f, spot.y + 0.5f);
        this.spot = spot;
      }
    }
  }

  @Override
  public void run(String arg) {
    SmlmUsageTracker.recordPlugin(this.getClass(), arg);

    extraOptions = ImageJUtils.isExtraOptions();
    batchMode = "batch".equals(arg);

    simulationParameters = CreateData.simulationParameters;
    if (simulationParameters == null) {
      IJ.error(TITLE, "No benchmark spot parameters in memory");
      return;
    }
    imp = CreateData.getImage();
    if (imp == null) {
      IJ.error(TITLE, "No benchmark image");
      return;
    }
    results = CreateData.getResults();
    if (results == null) {
      IJ.error(TITLE, "No benchmark results in memory");
      return;
    }

    // Set-up for the simulation
    try {
      if (results.getCalibration() == null) {
        throw new ConfigurationException("Require calibrated results");
      }
      if (results.getCalibrationReader().getDistanceUnit() != DistanceUnit.PIXEL) {
        throw new ConfigurationException("Require results in pixel distance units");
      }
      if (results.getCalibrationReader().getIntensityUnit() != IntensityUnit.PHOTON) {
        throw new ConfigurationException("Require results in photon units");
      }

      // This plugin is heavily reliant on the results being represented as a
      // Gaussian2D function.
      final int flags =
          Gaussian2DPeakResultHelper.AMPLITUDE | Gaussian2DPeakResultHelper.PIXEL_AMPLITUDE;
      calculator =
          Gaussian2DPeakResultHelper.create(results.getPsf(), results.getCalibration(), flags);

      cameraModel = CreateData.getCameraModel(simulationParameters);
    } catch (final ConfigurationException ex) {
      IJ.error(TITLE, "Bad configuration: " + ex.getMessage());
      return;
    }

    if (!showDialog()) {
      return;
    }

    // Clear old results to free memory
    if (filterResult != null) {
      filterResult.filterResults.clear();
      filterResult.filterResults = null;
      filterResult = null;
    }

    // For graphs
    windowOrganiser = new WindowOrganiser();

    if (batchMode) {
      // Batch mode to test enumeration of filters
      final double sd = simulationParameters.sd / simulationParameters.pixelPitch;
      final int limit = (int) Math.floor(3 * sd);

      // This should be in integers otherwise we may repeat search box sizes
      final int[] searchParam = SimpleArrayUtils.newArray(maxSearch - minSearch + 1, minSearch, 1);

      // Continuous parameters
      final double[] pEmpty = new double[0];
      final double[] mParam = (batchMean) ? getRange(limit, 0.05) : pEmpty;
      final double[] gParam = (batchGaussian) ? getRange(limit, 0.05) : pEmpty;

      // Less continuous parameters
      final double[] cParam = (batchCircular) ? getRange(limit, 0.5) : pEmpty;

      // Discrete parameters
      final double[] medParam = (batchMedian) ? getRange(limit, 1) : pEmpty;

      setupProgress(imp.getImageStackSize() * searchParam.length
          * (mParam.length + gParam.length + cParam.length + medParam.length), "Frame");

      ArrayList<BatchResult[]> batchResults = new ArrayList<>(cachedBatchResults.size());
      double param2 = 0;
      if (differenceFilter && differenceSmooth > 0) {
        if (filterRelativeDistances) {
          // Convert to absolute for batch run
          param2 = MathUtils.roundUsingDecimalPlaces(differenceSmooth * config.getHwhmMin(), 3);
        } else {
          // Already an absolute value
          param2 = differenceSmooth;
        }
        config.setDataFilterType(DataFilterType.DIFFERENCE);
      } else {
        config.setDataFilterType(DataFilterType.SINGLE);
      }
      for (final int search : searchParam) {
        // Batch runs use absolute distance
        config.setSearch(search, true);

        // Run all, store the results for plotting.
        // Allow re-use of these if they are cached to allow quick reanalysis of results.
        if (batchMean) {
          batchResults.add(addToCache(DataFilterMethod.MEAN, mParam, search, param2));
        }
        if (batchGaussian) {
          batchResults.add(addToCache(DataFilterMethod.GAUSSIAN, gParam, search, param2));
        }
        if (batchCircular) {
          batchResults.add(addToCache(DataFilterMethod.CIRCULAR_MEAN, cParam, search, param2));
        }
        if (batchMean) {
          batchResults.add(addToCache(DataFilterMethod.MEDIAN, medParam, search, param2));
        }
      }

      IJ.showProgress(-1);
      IJ.showStatus("");
      if (ImageJUtils.isInterrupted()) {
        return;
      }

      // Analysis options
      final GenericDialog gd = new GenericDialog(TITLE);
      final boolean haveCached = cachedBatchResults.size() > batchResults.size();
      if (haveCached) {
        gd.addCheckbox("Use_cached_results", useCached);
      }

      gd.addMessage("Choose performance plots:");
      for (int i = 0; i < batchPlot.length; i++) {
        gd.addCheckbox(batchPlotNames[i], batchPlot[i]);
      }

      gd.addChoice("Selection", SELECTION_METHOD, SELECTION_METHOD[selectionMethod]);
      gd.addCheckbox("Show_plots", showPlot);
      gd.addCheckbox("Plot_rank_by_intensity", rankByIntensity);
      gd.addCheckbox("Show_failures_plots", showFailuresPlot);
      gd.addCheckbox("Show_TP", showTP);
      gd.addCheckbox("Show_FP", showFP);
      gd.addCheckbox("Show_FN", showFN);

      gd.showDialog();
      if (gd.wasCanceled()) {
        return;
      }

      if (haveCached) {
        useCached = gd.getNextBoolean();
        if (useCached) {
          batchResults = cachedBatchResults;
        }
      }
      for (int i = 0; i < batchPlot.length; i++) {
        batchPlot[i] = gd.getNextBoolean();
      }
      selectionMethod = gd.getNextChoiceIndex();
      showPlot = gd.getNextBoolean();
      rankByIntensity = gd.getNextBoolean();
      showFailuresPlot = gd.getNextBoolean();
      showTP = gd.getNextBoolean();
      showFP = gd.getNextBoolean();
      showFN = gd.getNextBoolean();

      // Plot charts
      for (int i = 0; i < batchPlot.length; i++) {
        plot(i, batchResults);
      }

      // Store in global singleton
      filterResult = analyse(batchResults);
    } else {
      // Single filter mode
      setupProgress(imp.getImageStackSize(), "Frame");

      filterResult = runAnalysis(config);
    }

    IJ.showProgress(-1);
    IJ.showStatus("");

    getTable(false).flush();

    if (filterResult == null) {
      return;
    }

    // Store a clone of the config
    filterResult.config = filterResult.config.clone();

    // Debugging the matches
    if (debug) {
      addSpotsToMemory(filterResult.filterResults);
    }

    if (showFailuresPlot) {
      showFailuresPlot(filterResult);
    }
    if (showPlot) {
      showPlot(filterResult);
    }
    if (isShowOverlay()) {
      showOverlay(imp, filterResult);
    }

    windowOrganiser.tile();
  }

  private BatchResult[] addToCache(DataFilterMethod dataFilter, double[] param, int search,
      double param2) {
    for (final BatchResult[] batchResult : cachedBatchResults) {
      if (batchResult == null || batchResult.length == 0) {
        continue;
      }
      if (batchResult[0].dataFilter == dataFilter && batchResult[0].search == search
          && batchResult[0].param2 == param2) {
        return batchResult;
      }
    }
    final BatchResult[] batchResult = runFilter(dataFilter, param, search, param2);
    if (batchResult != null) {
      cachedBatchResults.add(batchResult);
    }
    return batchResult;
  }

  private void plot(int index, ArrayList<BatchResult[]> batchResults) {
    if (!batchPlot[index]) {
      return;
    }

    final Color[] colors =
        new Color[] {Color.red, Color.gray, Color.green, Color.blue, Color.magenta};

    final String name = batchPlotNames[index];
    final String title = TITLE + " Performance " + name;
    final Plot plot = new Plot(title, "Relative width", name);
    final double scale = 1.0 / config.getHwhmMin();
    for (final BatchResult[] batchResult : batchResults) {
      if (batchResult == null || batchResult.length == 0) {
        continue;
      }
      final float[][] data = extractData(batchResult, index, scale);
      final int colorIndex = batchResult[0].dataFilter.ordinal();
      plot.setColor(colors[colorIndex]);
      colors[colorIndex] = colors[colorIndex].darker();
      plot.addPoints(data[0], data[1], null, (batchResult.length > 1) ? Plot.LINE : Plot.CIRCLE,
          batchResult[0].getName());
    }
    plot.setColor(Color.black);
    plot.addLegend(null);
    if (name.contains("Time")) {
      plot.setAxisYLog(true);
    }
    ImageJUtils.display(title, plot, windowOrganiser);
    plot.setLimitsToFit(true); // Seems to only work after drawing
  }

  private static float[][] extractData(BatchResult[] batchResult, int index, double scale) {
    final float[][] data = new float[2][batchResult.length];
    for (int i = 0; i < batchResult.length; i++) {
      data[0][i] = (float) (batchResult[i].param * scale);
      data[1][i] = (float) batchResult[i].getScore(index);
    }
    return data;
  }

  private static double[] extractData(BatchResult[] batchResult, int index, double[][] stats) {
    final double[] data = new double[batchResult.length];
    for (int i = 0; i < batchResult.length; i++) {
      data[i] = getScore(batchResult[i], index, stats);
    }
    return data;
  }

  private BenchmarkFilterResult analyse(ArrayList<BatchResult[]> batchResults) {
    // Support z-score of AUC and Max. Jaccard combined.
    // For this wee need the statistics of the population of scores.
    final double[][] stats = getStats(batchResults);

    double max = 0;
    BatchResult best = null;
    for (final BatchResult[] batchResult : batchResults) {
      if (batchResult == null || batchResult.length == 0) {
        continue;
      }
      final double[] data = extractData(batchResult, selectionMethod, stats);
      int maxi = 0;
      for (int i = 1; i < batchResult.length; i++) {
        if (data[maxi] < data[i]) {
          maxi = i;
        }
      }
      if (max < data[maxi]) {
        max = data[maxi];
        best = batchResult[maxi];
      }
    }

    if (best != null) {
      // Rerun to get the best result and show in the summary table

      // Support difference filters second parameter
      if (best.param2 > 0) {
        config.setDataFilterType(DataFilterType.DIFFERENCE);
      } else {
        config.setDataFilterType(DataFilterType.SINGLE);
      }

      // Note: All batch runs use absolute distances for search and filter smoothing parameters.
      // The border parameter is already set as relative/absolute.
      if (filterRelativeDistances) {
        final double hwhmMax = config.getHwhmMax();
        final double hwhmMin = config.getHwhmMin();

        // Convert absolute search distance to relative
        config.setSearch(MathUtils.roundUsingDecimalPlaces(best.search / hwhmMax, 3), false);

        // Convert the absolute distance to be relative to the PSF width
        config.setDataFilter(best.dataFilter,
            MathUtils.roundUsingDecimalPlaces(best.param / hwhmMin, 3), false, 0);
        if (best.param2 > 0) {
          config.setDataFilter(best.dataFilter,
              MathUtils.roundUsingDecimalPlaces(best.param2 / hwhmMin, 3), false, 1);
        }
      } else {
        config.setSearch(best.search, true);
        config.setDataFilter(best.dataFilter, best.param, true, 0);
        if (best.param2 > 0) {
          config.setDataFilter(best.dataFilter, best.param2, true, 1);
        }
      }

      final BenchmarkFilterResult result = runAnalysis(config, true);
      getTable(true).flush();
      return result;
    }

    return null;
  }

  private static double[][] getStats(ArrayList<BatchResult[]> batchResults) {
    if (selectionMethod < 2) {
      return null;
    }

    final double[][] stats = new double[2][2];
    for (int index = 0; index < stats.length; index++) {
      final Statistics s = new Statistics();
      for (final BatchResult[] batchResult : batchResults) {
        if (batchResult == null || batchResult.length == 0) {
          continue;
        }
        for (int i = 0; i < batchResult.length; i++) {
          s.add(batchResult[i].getScore(index));
        }
      }
      stats[index][0] = s.getMean();
      stats[index][1] = s.getStandardDeviation();
    }
    return stats;
  }

  private static double getScore(BatchResult batchResult, int index, double[][] stats) {
    if (stats == null) {
      return batchResult.getScore(index);
    }
    // Z-score of all metrics combined
    double z = 0;
    for (int i = 0; i < stats.length; i++) {
      z += (batchResult.getScore(i) - stats[i][0]) / stats[i][1];
    }
    return z;
  }

  private static double[] getRange(final int limit, final double interval) {
    double[] param;
    final int c = (int) (limit / interval);
    param = new double[c];
    for (int i = 1; i <= c; i++) {
      param[i - 1] = i * interval;
    }
    return param;
  }

  private BatchResult[] runFilter(DataFilterMethod dataFilter, double[] param, int search,
      double param2) {
    progressPrefix = new BatchResult(null, dataFilter, 0, search, param2).getName();
    final TurboList<BatchResult> result = new TurboList<>();

    // Note: All batch runs use absolute distances for filter smoothing parameters

    // For difference filters
    if (param2 > 0) {
      // Note: Add a dummy first param so we can set the second param
      config.setDataFilter(dataFilter, param2, true, 0);
      config.setDataFilter(dataFilter, param2, true, 1);
    }

    for (int i = 0; i < param.length; i++) {
      config.setDataFilter(dataFilter, param[i], true, 0);
      try {
        final BenchmarkFilterResult filterResult = runAnalysis(config, false);
        if (filterResult != null) {
          result.add(new BatchResult(filterResult, dataFilter, param[i], search, param2));
        }
      } catch (final IllegalArgumentException ex) {
        // This can occur during batch processing when the param2 argument is smaller than param
        break;
      }
    }
    return result.toArray(new BatchResult[result.size()]);
  }

  private boolean showDialog() {
    final ExtendedGenericDialog gd = new ExtendedGenericDialog(TITLE);
    gd.addHelp(About.HELP_URL);

    final StringBuilder sb = new StringBuilder();
    sb.append("Finds spots in the benchmark image created by CreateData plugin.\n");
    final double s = simulationParameters.sd / simulationParameters.pixelPitch;
    final double sa = getSa() / simulationParameters.pixelPitch;
    sb.append("PSF width = ").append(MathUtils.rounded(s)).append(" px (sa = ")
        .append(MathUtils.rounded(sa)).append(" px). HWHM = ")
        .append(MathUtils.rounded(s * Gaussian2DFunction.SD_TO_HWHM_FACTOR)).append(" px\n");
    sb.append("Simulation depth = ").append(MathUtils.rounded(simulationParameters.depth))
        .append(" nm");
    if (simulationParameters.fixedDepth) {
      sb.append(" (fixed)");
    }
    sb.append("\n \nConfigure the spot filter:");
    gd.addMessage(sb.toString());

    if (batchMode) {
      // Support enumeration of single/difference spot filters
      gd.addCheckbox("Mean", batchMean);
      gd.addCheckbox("Gaussian", batchGaussian);
      gd.addCheckbox("Circular", batchCircular);
      gd.addCheckbox("Median", batchMedian);
      // For difference filters we set the smoothing for the second filter
      // using only one distance
      gd.addMessage("Difference filter settings:");
      gd.addCheckbox("Difference_filter", differenceFilter);
      gd.addSlider("Difference_smoothing", 1.5, 5, differenceSmooth);
      gd.addMessage("Local maxima search settings:");
      gd.addSlider("Min_search_width", 1, 4, minSearch);
      gd.addSlider("Max_search_width", 1, 4, maxSearch);
      gd.addCheckbox("Filter_relative_distances (to HWHM)", filterRelativeDistances);
    } else {
      gd.addChoice("Spot_filter_type", SettingsManager.getDataFilterTypeNames(),
          config.getDataFilterType().ordinal());
      gd.addChoice("Spot_filter", SettingsManager.getDataFilterMethodNames(),
          config.getDataFilterMethod(0).ordinal());

      gd.addCheckbox("Filter_relative_distances (to HWHM)",
          !config.getDataFilterParameterAbsolute(0));
      gd.addSlider("Smoothing", 0, 2.5, config.getDataFilterParameterValue(0));
      gd.addSlider("Search_width", 1, 4, search);
    }
    gd.addSlider("Border", 0, 5, border);
    gd.addCheckbox("Hard_border", hardBorder);

    gd.addMessage("Scoring options:");
    gd.addCheckbox("Score_relative_distances (to HWHM)", scoreRelativeDistances);
    gd.addSlider("Analysis_border", 0, 5, sAnalysisBorder);
    gd.addChoice("Matching_method", MATCHING_METHOD, MATCHING_METHOD[matchingMethod]);
    gd.addSlider("Match_distance", 0.5, 3.5, upperDistance);
    gd.addSlider("Lower_distance", 0, 3.5, lowerDistance);
    gd.addSlider("Signal_factor", 0, 3.5, upperSignalFactor);
    gd.addSlider("Lower_factor", 0, 3.5, lowerSignalFactor);
    gd.addSlider("Recall_fraction", 50, 100, recallFraction);
    if (!batchMode) {
      gd.addCheckbox("Show_plots", showPlot);
      gd.addCheckbox("Plot_rank_by_intensity", rankByIntensity);
      gd.addCheckbox("Show_failures_plots", showFailuresPlot);
      gd.addCheckbox("Show_TP", showTP);
      gd.addCheckbox("Show_FP", showFP);
      gd.addCheckbox("Show_FN", showFN);
    }
    if (extraOptions) {
      gd.addCheckbox("Debug", sDebug);
    }

    ImageJUtils.rearrangeColumns(gd, (batchMode) ? 14 : 8);

    gd.showDialog();

    if (gd.wasCanceled()) {
      return false;
    }

    // Here we use PSF stored in the results if supported (i.e. a Gaussian).
    // The results are likely to come from the CreateData simulation.
    final PSF psf = results.getPsf();
    if (PsfHelper.isGaussian2D(psf)) {
      fitConfig.setPsf(results.getPsf());
    } else {
      fitConfig.setInitialPeakStdDev(s);
    }

    if (batchMode) {
      batchMean = gd.getNextBoolean();
      batchGaussian = gd.getNextBoolean();
      batchCircular = gd.getNextBoolean();
      batchMedian = gd.getNextBoolean();

      if (!(batchMean || batchGaussian || batchCircular || batchMedian)) {
        return false;
      }

      differenceFilter = gd.getNextBoolean();
      differenceSmooth = gd.getNextNumber();

      minSearch = (int) gd.getNextNumber();
      maxSearch = (int) gd.getNextNumber();
      filterRelativeDistances = gd.getNextBoolean();
    } else {
      config.setDataFilterType(SettingsManager.getDataFilterTypeValues()[gd.getNextChoiceIndex()]);
      filterRelativeDistances = gd.getNextBoolean();
      config.setDataFilter(SettingsManager.getDataFilterMethodValues()[gd.getNextChoiceIndex()],
          MathUtils.roundUsingDecimalPlaces(Math.abs(gd.getNextNumber()), 3),
          !filterRelativeDistances, 0);
      search = gd.getNextNumber();
    }
    border = gd.getNextNumber();
    hardBorder = gd.getNextBoolean();
    scoreRelativeDistances = gd.getNextBoolean();
    sAnalysisBorder = Math.abs(gd.getNextNumber());
    matchingMethod = gd.getNextChoiceIndex();
    upperDistance = Math.abs(gd.getNextNumber());
    lowerDistance = Math.abs(gd.getNextNumber());
    upperSignalFactor = Math.abs(gd.getNextNumber());
    lowerSignalFactor = Math.abs(gd.getNextNumber());
    recallFraction = Math.abs(gd.getNextNumber());
    if (!batchMode) {
      showPlot = gd.getNextBoolean();
      rankByIntensity = gd.getNextBoolean();
      showFailuresPlot = gd.getNextBoolean();
      showTP = gd.getNextBoolean();
      showFP = gd.getNextBoolean();
      showFN = gd.getNextBoolean();
    }
    if (extraOptions) {
      debug = sDebug = gd.getNextBoolean();
    }

    if (gd.invalidNumber()) {
      return false;
    }

    if (lowerDistance > upperDistance) {
      lowerDistance = upperDistance;
    }
    if (lowerSignalFactor > upperSignalFactor) {
      lowerSignalFactor = upperSignalFactor;
    }

    // Set border here so that the results are consistent with single-filter mode.
    config.setBorder(MathUtils.roundUsingDecimalPlaces(border, 3), !filterRelativeDistances);

    if (batchMode) {
      // Clear the cached results if the setting changed
      final SettingsList settings =
          new SettingsList(simulationParameters.id, filterRelativeDistances,
              // search, maxSearch, // Ignore search distance for smart caching
              border, scoreRelativeDistances, sAnalysisBorder, hardBorder, matchingMethod,
              upperDistance, lowerDistance, upperSignalFactor, lowerSignalFactor, recallFraction);
      if (!settings.equals(batchSettings)) {
        cachedBatchResults.clear();
      }
      batchSettings = settings;
    } else {
      // Single filter ...
      config.setSearch(MathUtils.roundUsingDecimalPlaces(search, 3), !filterRelativeDistances);

      // Allow more complicated filters to be configured
      if (!PeakFit.configureDataFilter(config, PeakFit.FLAG_NO_SAVE)) {
        return false;
      }
    }

    int analysisBorder;
    if (scoreRelativeDistances) {
      // Convert distance to PSF standard deviation units
      final double hwhmMax = config.getHwhmMax();
      matchDistance = upperDistance * hwhmMax;
      lowerMatchDistance = lowerDistance * hwhmMax;
      analysisBorder = (int) (sAnalysisBorder * hwhmMax);
    } else {
      matchDistance = upperDistance;
      lowerMatchDistance = lowerDistance;
      analysisBorder = (int) (sAnalysisBorder);
    }

    if (analysisBorder > 0) {
      lastAnalysisBorder = new Rectangle(analysisBorder, analysisBorder,
          imp.getWidth() - 2 * analysisBorder, imp.getHeight() - 2 * analysisBorder);
    } else {
      lastAnalysisBorder = new Rectangle(imp.getWidth(), imp.getHeight());
    }

    return true;
  }

  private double getSa() {
    final double sa = PsfCalculator.squarePixelAdjustment(simulationParameters.sd,
        simulationParameters.pixelPitch);
    return sa;
  }

  /** The total progress. */
  private int progress;
  private int stepProgress;
  private int totalProgress;
  private String progressPrefix;

  private void setupProgress(int total, String prefix) {
    totalProgress = total;
    stepProgress = ImageJUtils.getProgressInterval(totalProgress);
    progressPrefix = prefix;
    progress = 0;
  }

  /**
   * Show progress.
   */
  private synchronized void showProgress() {
    if (progress % stepProgress == 0) {
      // if (Utils.showStatus(String.format("%s: %d / %d", progressPrefix, progress,
      // totalProgress)))
      if (ImageJUtils.showStatus(progressPrefix)) {
        IJ.showProgress(progress, totalProgress);
      }
    }
    progress++;
  }

  private BenchmarkFilterResult runAnalysis(FitEngineConfiguration config) {
    return runAnalysis(config, false);
  }

  private BenchmarkFilterResult runAnalysis(FitEngineConfiguration config, boolean batchSummary) {
    if (ImageJUtils.isInterrupted()) {
      return null;
    }

    final MaximaSpotFilter spotFilter = config.createSpotFilter();
    // System.out.println(spotFilter.getDescription());

    // Extract all the results in memory into a list per frame. This can be cached
    if (lastId != simulationParameters.id) {
      // Always use float coordinates.
      // The Worker adds a pixel offset for the spot coordinates.
      final TIntObjectHashMap<ArrayList<Coordinate>> coordinates =
          ResultsMatchCalculator.getCoordinates(results, false);
      actualCoordinates = new TIntObjectHashMap<>();
      lastId = simulationParameters.id;
      // lastRelativeDistances = relativeDistances;

      // Store these so we can reset them
      final int total = totalProgress;
      final String prefix = progressPrefix;

      // Spot PSFs may overlap so we must determine the amount of signal overlap and amplitude
      // effect
      // for each spot...
      IJ.showStatus("Computing PSF overlap ...");

      final int nThreads = Prefs.getThreads();
      final BlockingQueue<Integer> jobs = new ArrayBlockingQueue<>(nThreads * 2);
      final List<OverlapWorker> workers = new LinkedList<>();
      final List<Thread> threads = new LinkedList<>();
      for (int i = 0; i < nThreads; i++) {
        final OverlapWorker worker = new OverlapWorker(jobs, coordinates);
        final Thread t = new Thread(worker);
        workers.add(worker);
        threads.add(t);
        t.start();
      }

      // Process the frames
      totalProgress = coordinates.size();
      stepProgress = ImageJUtils.getProgressInterval(totalProgress);
      progress = 0;
      coordinates.forEachKey(value -> {
        put(jobs, value);
        return true;
      });
      // Finish all the worker threads by passing in a null job
      for (int i = 0; i < threads.size(); i++) {
        put(jobs, -1);
      }

      // Wait for all to finish
      for (int i = 0; i < threads.size(); i++) {
        try {
          threads.get(i).join();
        } catch (final InterruptedException ex) {
          ex.printStackTrace();
        }
        actualCoordinates.putAll(workers.get(i).coordinates);
      }
      threads.clear();

      // For testing
      final SimpleRegression regression = new SimpleRegression(false);
      for (final PsfSpot[] spots : actualCoordinates.valueCollection()) {
        for (final PsfSpot spot : spots) {
          regression.addData(spot.amplitude,
              calculator.getAmplitude(spot.peakResult.getParameters()));
        }
      }
      ImageJUtils.log("PixelAmplitude vs Amplitude = %f, slope=%f, n=%d", regression.getR(),
          regression.getSlope(), regression.getN());

      IJ.showProgress(-1);
      IJ.showStatus("");

      setupProgress(total, prefix);
    }

    if (!batchMode) {
      IJ.showStatus("Computing results ...");
    }
    final ImageStack stack = imp.getImageStack();

    float background = 0;
    if (spotFilter.isAbsoluteIntensity()) {
      if (Float.isNaN(resultsBackground)) {
        // To allow the signal factor to be computed we need to lower the image by the background so
        // that the intensities correspond to the results amplitude.
        // Just assume the simulation background is uniform.
        final StandardResultProcedure s =
            new StandardResultProcedure(results, IntensityUnit.PHOTON);
        s.getB();
        resultsBackground = (float) (MathUtils.sum(s.background) / results.size());
      }
      background = this.resultsBackground;
    }
    // Create the weights if needed
    if (cameraModel.isPerPixelModel() && spotFilter.isWeighted()) {
      if (weights == null) {
        bounds = cameraModel.getBounds();
        weights = cameraModel.getNormalisedWeights(bounds);
      }
    }

    // Create a pool of workers
    final int nThreads = Prefs.getThreads();
    final BlockingQueue<Integer> jobs = new ArrayBlockingQueue<>(nThreads * 2);
    final List<Worker> workers = new TurboList<>(nThreads);
    final List<Thread> threads = new TurboList<>(nThreads);
    for (int i = 0; i < nThreads; i++) {
      final Worker worker = new Worker(jobs, stack, spotFilter, background);
      final Thread t = new Thread(worker);
      workers.add(worker);
      threads.add(t);
      t.start();
    }

    // Fit the frames
    for (int i = 1; i <= stack.getSize(); i++) {
      put(jobs, i);
    }
    // Finish all the worker threads by passing in a null job
    for (int i = 0; i < threads.size(); i++) {
      put(jobs, -1);
    }

    // Wait for all to finish
    for (int i = 0; i < threads.size(); i++) {
      try {
        threads.get(i).join();
      } catch (final InterruptedException ex) {
        ex.printStackTrace();
      }
    }
    threads.clear();

    if (ImageJUtils.isInterrupted()) {
      return null;
    }

    if (!batchMode) {
      IJ.showProgress(-1);
      IJ.showStatus("Collecting results ...");
    }

    TIntObjectHashMap<FilterResult> filterResults = null;
    time = 0;
    for (int i = 0; i < workers.size(); i++) {
      final Worker w = workers.get(i);
      time += w.time;
      if (filterResults == null) {
        filterResults = w.results;
      } else {
        filterResults.putAll(w.results);
        w.results.clear();
        w.results.compact();
      }
    }
    if (filterResults == null) {
      throw new NullPointerException();
    }

    filterResults.compact();

    IJ.showStatus("Summarising results ...");

    // Show a table of the results
    final BenchmarkFilterResult filterResult =
        summariseResults(filterResults, config, spotFilter, batchSummary);

    if (!batchMode) {
      IJ.showStatus("");
    }

    return filterResult;
  }

  /**
   * Add all the true-positives to memory as a new results set.
   *
   * @param filterResults the filter results
   */
  void addSpotsToMemory(TIntObjectHashMap<FilterResult> filterResults) {
    final MemoryPeakResults results = new MemoryPeakResults();
    results.setName(TITLE + " TP " + id++);
    filterResults.forEachEntry(new TIntObjectProcedure<FilterResult>() {
      @Override
      public boolean execute(int peak, FilterResult filterResult) {
        for (final ScoredSpot spot : filterResult.spots) {
          if (spot.match) {
            final float[] params =
                new float[] {0, spot.getIntensity(), 0, spot.spot.x, spot.spot.y, 0, 0};
            results.add(peak, spot.spot.x, spot.spot.y, spot.getIntensity(), 0d, 0f, 0f, params,
                null);
          }
        }
        return true;
      }
    });
    MemoryPeakResults.addResults(results);
  }

  /**
   * Histogram the number of negatives preceding each positive.
   *
   * @param filterResult the filter result
   * @return the cumulative histogram
   */
  private static double[][] histogramFailures(BenchmarkFilterResult filterResult) {
    final StoredData data = new StoredData();
    filterResult.filterResults.forEachEntry(new TIntObjectProcedure<FilterResult>() {
      @Override
      public boolean execute(int peak, FilterResult filterResult) {
        for (final ScoredSpot spot : filterResult.spots) {
          if (spot.match) {
            data.add(spot.fails);
          }
        }
        return true;
      }
    });

    final double[][] h = MathUtils.cumulativeHistogram(data.getValues(), true);

    filterResult.cumul = h;
    filterResult.stats = data;

    return h;
  }

  private void showFailuresPlot(BenchmarkFilterResult filterResult) {
    final double[][] h = filterResult.cumul;
    final StoredData data = filterResult.stats;

    final String xTitle = "Failures";
    new HistogramPlotBuilder(TITLE, data, xTitle).setMinBinWidth(1).show(windowOrganiser);

    final String title = TITLE + " " + xTitle + " Cumulative";
    final Plot2 plot = new Plot2(title, xTitle, "Frequency");
    final double xMin = (data.size() == 0) ? 1 : h[0][0];
    final double xMax = (data.size() == 0) ? 1 : h[0][h[0].length - 1] + 1;
    final double xPadding = 0.05 * (xMax - xMin);
    plot.setLimits(xMin - xPadding, xMax, 0, 1.05);
    plot.setColor(Color.blue);
    plot.addPoints(h[0], h[1], Plot2.BAR);
    ImageJUtils.display(title, plot, windowOrganiser);
  }

  private static void put(BlockingQueue<Integer> jobs, int slice) {
    try {
      jobs.put(slice);
    } catch (final InterruptedException ex) {
      // Restore interrupted state...
      Thread.currentThread().interrupt();
      throw new ConcurrentRuntimeException("Unexpected interruption", ex);
    }
  }

  private BenchmarkFilterResult summariseResults(TIntObjectHashMap<FilterResult> filterResults,
      FitEngineConfiguration config, MaximaSpotFilter spotFilter, boolean batchSummary) {
    final BenchmarkFilterResult filterResult =
        new BenchmarkFilterResult(simulationParameters.id, filterResults, config, spotFilter);

    // Note:
    // Although we can compute the TP/FP score as each additional spot is added
    // using the RankedScoreCalculator this is not applicable to the PeakFit method.
    // The method relies on all spot candidates being present in order to make a
    // decision to fit the candidate as a multiple. So scoring the filter candidates using
    // for example the top 10 may get a better score than if all candidates were scored
    // and the scores accumulated for the top 10, it is not how the algorithm will use the
    // candidate set. I.e. It does not use the top 10, then top 20 to refine the fit, etc.
    // (the method is not iterative) .
    // We require an assessment of how a subset of the scored candidates
    // in ranked order contributes to the overall score, i.e. are the candidates ranked
    // in the correct order, those most contributing to the match to the underlying data
    // should be higher up and those least contributing will be at the end.

    // TODO We could add some smart filtering of candidates before ranking. This would
    // allow assessment of the candidate set handed to PeakFit. E.g. Threshold the image
    // and only use candidates that are in the foreground region.

    final double[][] cumul = histogramFailures(filterResult);

    // Create the overall match score
    final double[] total = new double[3];
    final ArrayList<ScoredSpot> allSpots = new ArrayList<>();
    filterResults.forEachValue(new TObjectProcedure<FilterResult>() {
      @Override
      public boolean execute(FilterResult result) {
        total[0] += result.result.getTruePositives();
        total[1] += result.result.getFalsePositives();
        total[2] += result.result.getFalseNegatives();
        allSpots.addAll(Arrays.asList(result.spots));
        return true;
      }
    });
    double tp = total[0];
    double fp = total[1];
    final double fn = total[2];
    final FractionClassificationResult allResult = new FractionClassificationResult(tp, fp, 0, fn);
    // The number of actual results
    final double numberOfResults = (tp + fn);

    final StringBuilder sb = new StringBuilder();

    final double signal = (simulationParameters.minSignal + simulationParameters.maxSignal) * 0.5;

    // Create the benchmark settings and the fitting settings
    sb.append(imp.getStackSize()).append('\t');
    final int w = lastAnalysisBorder.width;
    final int h = lastAnalysisBorder.height;
    sb.append(w).append('\t');
    sb.append(h).append('\t');
    sb.append(MathUtils.rounded(numberOfResults)).append('\t');
    final double density = (numberOfResults / imp.getStackSize()) / (w * h)
        / (simulationParameters.pixelPitch * simulationParameters.pixelPitch / 1e6);
    sb.append(MathUtils.rounded(density)).append('\t');
    sb.append(MathUtils.rounded(signal)).append('\t');
    sb.append(MathUtils.rounded(simulationParameters.sd)).append('\t');
    sb.append(MathUtils.rounded(simulationParameters.pixelPitch)).append('\t');
    sb.append(MathUtils.rounded(simulationParameters.depth)).append('\t');
    sb.append(simulationParameters.fixedDepth).append('\t');

    // Camera specific
    CreateData.addCameraDescription(sb, simulationParameters).append('\t');

    sb.append(MathUtils.rounded(simulationParameters.background)).append('\t');
    sb.append(MathUtils.rounded(simulationParameters.noise)).append('\t');

    sb.append(MathUtils.rounded(signal / simulationParameters.noise)).append('\t');
    sb.append(MathUtils.rounded(simulationParameters.sd / simulationParameters.pixelPitch))
        .append('\t');
    sb.append(config.getDataFilterType()).append('\t');
    // sb.append(spotFilter.getName()).append('\t');
    sb.append(spotFilter.getSearch()).append('\t');
    sb.append(spotFilter.getBorder()).append('\t');
    sb.append(MathUtils.rounded(spotFilter.getSpread())).append('\t');
    sb.append(config.getDataFilterMethod(0)).append('\t');
    final double param = config.getDataFilterParameterValue(0);
    final boolean absolute = config.getDataFilterParameterAbsolute(0);
    final double hwhmMin = config.getHwhmMin();
    if (absolute) {
      sb.append(MathUtils.rounded(param)).append('\t');
      sb.append(MathUtils.roundUsingDecimalPlacesToBigDecimal(param / hwhmMin, 3)).append('\t');
    } else {
      sb.append(MathUtils.roundUsingDecimalPlacesToBigDecimal(param * hwhmMin, 3)).append('\t');
      sb.append(MathUtils.rounded(param)).append('\t');
    }
    sb.append(spotFilter.getDescription()).append('\t');
    sb.append(lastAnalysisBorder.x).append('\t');
    sb.append(MATCHING_METHOD[matchingMethod]).append('\t');
    sb.append(MathUtils.rounded(lowerMatchDistance)).append('\t');
    sb.append(MathUtils.rounded(matchDistance)).append('\t');
    sb.append(MathUtils.rounded(lowerSignalFactor)).append('\t');
    sb.append(MathUtils.rounded(upperSignalFactor));

    resultPrefix = sb.toString();

    // Add the results
    sb.append('\t');

    // Rank the scored spots by intensity
    Collections.sort(allSpots, ScoredSpot::compare);

    // Produce Recall, Precision, Jaccard for each cut of the spot candidates
    final double[] recall = new double[allSpots.size() + 1];
    final double[] precision = new double[recall.length];
    final double[] jaccard = new double[recall.length];
    final double[] correlation = new double[recall.length];
    final double[] truePositives = new double[recall.length];
    final double[] falsePositives = new double[recall.length];
    final double[] intensity = new double[recall.length];
    // Note: fn = n - tp
    tp = fp = 0;
    int index = 1;
    precision[0] = 1;
    final FastCorrelator corr = new FastCorrelator();
    double lastCorrelation = 0;
    double[] i1 = new double[recall.length];
    double[] i2 = new double[recall.length];
    int ci = 0;
    final SimpleRegression regression = new SimpleRegression(false);
    for (final ScoredSpot s : allSpots) {
      if (s.match) {
        // Score partial matches as part true-positive and part false-positive.
        // TP can be above 1 if we are allowing multiple matches.
        tp += s.getScore();
        fp += s.antiScore();
        // Just use a rounded intensity for now
        final double spotIntensity = s.getIntensity();
        final long v1 = Math.round(spotIntensity);
        final long v2 = Math.round(s.intensity);
        regression.addData(spotIntensity, s.intensity);
        i1[ci] = spotIntensity;
        i2[ci] = s.intensity;
        ci++;
        corr.add(v1, v2);
        lastCorrelation = corr.getCorrelation();
      } else {
        fp++;
      }
      recall[index] = tp / numberOfResults;
      precision[index] = tp / (tp + fp);
      jaccard[index] = tp / (fp + numberOfResults); // (tp+fp+fn) == (fp+n) since tp+fn=n;
      correlation[index] = lastCorrelation;
      truePositives[index] = tp;
      falsePositives[index] = fp;
      intensity[index] = s.getIntensity();
      index++;
    }
    i1 = Arrays.copyOf(i1, ci);
    i2 = Arrays.copyOf(i2, ci);

    final double slope = regression.getSlope();
    sb.append(MathUtils.rounded(slope)).append('\t');
    addResult(sb, allResult, correlation[correlation.length - 1]);

    // Output the match results when the recall achieves the fraction of the maximum.
    double target = recall[recall.length - 1];
    if (recallFraction < 100) {
      target *= recallFraction / 100.0;
    }
    int fractionIndex = 0;
    while (fractionIndex < recall.length && recall[fractionIndex] < target) {
      fractionIndex++;
    }
    if (fractionIndex == recall.length) {
      fractionIndex--;
    }
    sb.append('\t');
    addResult(sb,
        new FractionClassificationResult(truePositives[fractionIndex],
            falsePositives[fractionIndex], 0, numberOfResults - truePositives[fractionIndex]),
        correlation[fractionIndex]);

    // Output the match results at the maximum jaccard score
    int maxIndex = 0;
    for (int ii = 1; ii < recall.length; ii++) {
      if (jaccard[maxIndex] < jaccard[ii]) {
        maxIndex = ii;
      }
    }
    sb.append('\t');
    addResult(sb, new FractionClassificationResult(truePositives[maxIndex],
        falsePositives[maxIndex], 0, numberOfResults - truePositives[maxIndex]),
        correlation[maxIndex]);

    sb.append(MathUtils.rounded(time / 1e6));

    // Calculate AUC (Average precision == Area Under Precision-Recall curve)
    final double auc = AucCalculator.auc(precision, recall);
    // Compute the AUC using the adjusted precision curve
    // which uses the maximum precision for recall >= r
    final double[] maxp = new double[precision.length];
    double max = 0;
    for (int k = maxp.length; k-- > 0;) {
      if (max < precision[k]) {
        max = precision[k];
      }
      maxp[k] = max;
    }
    final double auc2 = AucCalculator.auc(maxp, recall);

    sb.append('\t').append(MathUtils.rounded(auc));
    sb.append('\t').append(MathUtils.rounded(auc2));

    // Output the number of fit failures that must be processed to capture fractions of the true
    // positives
    if (cumul[0].length != 0) {
      sb.append('\t').append(MathUtils.rounded(getFailures(cumul, 0.80)));
      sb.append('\t').append(MathUtils.rounded(getFailures(cumul, 0.90)));
      sb.append('\t').append(MathUtils.rounded(getFailures(cumul, 0.95)));
      sb.append('\t').append(MathUtils.rounded(getFailures(cumul, 0.99)));
      sb.append('\t').append(MathUtils.rounded(cumul[0][cumul[0].length - 1]));
    } else {
      sb.append("\t\t\t\t\t");
    }

    final BufferedTextWindow resultsTable = getTable(batchSummary);
    resultsTable.append(sb.toString());

    // Store results
    filterResult.auc = auc;
    filterResult.auc2 = auc2;
    filterResult.recall = recall;
    filterResult.precision = precision;
    filterResult.jaccard = jaccard;
    filterResult.correlation = correlation;
    filterResult.maxIndex = maxIndex;
    filterResult.fractionIndex = fractionIndex;
    filterResult.cumul = cumul;
    filterResult.slope = slope;
    filterResult.i1 = i1;
    filterResult.i2 = i2;
    filterResult.intensity = intensity;
    filterResult.time = time;
    return filterResult;
  }

  private static boolean isShowOverlay() {
    return (showTP || showFP || showFN);
  }

  @SuppressWarnings("null")
  private static void showOverlay(ImagePlus imp, BenchmarkFilterResult filterResult) {
    final Overlay o = new Overlay();
    // int tp = 0, fp = 0, fn = 0, nn = 0;
    filterResult.filterResults.forEachValue(result -> {
      final int size = result.spots.length;

      float[] tx = null;
      float[] ty = null;
      float[] fx = null;
      float[] fy = null;
      if (showTP) {
        tx = new float[size];
        ty = new float[size];
      }
      if (showFP) {
        fx = new float[size];
        fy = new float[size];
      }
      int tc = 0;
      int fc = 0;
      for (final ScoredSpot s : result.spots) {
        if (s.match) {
          if (showTP) {
            tx[tc] = s.spot.x + 0.5f;
            ty[tc++] = s.spot.y + 0.5f;
          }
        } else if (showFP) {
          fx[fc] = s.spot.x + 0.5f;
          fy[fc++] = s.spot.y + 0.5f;
        }
      }
      if (showTP) {
        SpotFinderPreview.addRoi(result.frame, o, tx, ty, tc, Color.green);
      }
      if (showFP) {
        SpotFinderPreview.addRoi(result.frame, o, fx, fy, fc, Color.red);
      }
      if (showFN) {
        // We need the FN ...
        final PsfSpot[] actual = result.actual;
        final boolean[] actualAssignment = result.actualAssignment;
        // nn += actual.length;
        final float[] nx = new float[actual.length];
        final float[] ny = new float[actual.length];
        int npoints = 0;
        for (int i = 0; i < actual.length; i++) {
          if (!actualAssignment[i]) {
            nx[npoints] = actual[i].getX();
            ny[npoints++] = actual[i].getY();
          }
        }
        // fn += n;
        SpotFinderPreview.addRoi(result.frame, o, nx, ny, npoints, Color.yellow);
      }
      return true;
    });

    // System.out.printf("TP=%d, FP=%d, FN=%d, N=%d (%d) %d\n", tp, fp, fn, tp + fn, results.size(),
    // nn);

    imp.setOverlay(o);
  }

  private void showPlot(BenchmarkFilterResult filterResult) {
    final double[] r = filterResult.recall;
    final double[] p = filterResult.precision;
    final double[] j = filterResult.jaccard;
    final double[] c = filterResult.correlation;
    final int fractionIndex = filterResult.fractionIndex;
    final int maxIndex = filterResult.maxIndex;
    final double auc = filterResult.auc;
    final double auc2 = filterResult.auc2;
    final double slope = filterResult.slope;
    final double[] i1 = filterResult.i1;
    final double[] i2 = filterResult.i2;
    final double[] intensity = filterResult.intensity;

    final double[] rank = new double[intensity.length];
    final double topIntensity = (intensity.length == 1) ? 0 : intensity[1];
    for (int i = 1; i < rank.length; i++) {
      if (rankByIntensity) {
        rank[i] = topIntensity - intensity[i];
      } else {
        rank[i] = i;
      }
    }

    String title = TITLE + " Performance";
    Plot2 plot = new Plot2(title, (rankByIntensity) ? "Relative Intensity" : "Spot Rank", "");
    final double[] limits = MathUtils.limits(rank);
    plot.setLimits(limits[0], limits[1], 0, 1.05);
    plot.setColor(Color.blue);
    plot.addPoints(rank, p, Plot.LINE);
    // plot.addPoints(rank, maxp, Plot2.DOT);
    plot.setColor(Color.red);
    plot.addPoints(rank, r, Plot.LINE);
    plot.setColor(Color.black);
    plot.addPoints(rank, j, Plot.LINE);
    // Plot correlation - update the scale to be 0-1?
    plot.setColor(Color.yellow);
    plot.addPoints(rank, c, Plot.LINE);
    plot.setColor(Color.magenta);
    plot.drawLine(rank[fractionIndex], 0, rank[fractionIndex],
        MathUtils.max(p[fractionIndex], r[fractionIndex], j[fractionIndex], c[fractionIndex]));
    plot.setColor(Color.pink);
    plot.drawLine(rank[maxIndex], 0, rank[maxIndex],
        MathUtils.max(p[maxIndex], r[maxIndex], j[maxIndex], c[maxIndex]));
    plot.setColor(Color.black);
    plot.addLabel(0, 0, "Precision=Blue, Recall=Red, Jaccard=Black, Correlation=Yellow");

    ImageJUtils.display(title, plot, windowOrganiser);

    title = TITLE + " Precision-Recall";
    plot = new Plot2(title, "Recall", "Precision");
    plot.setLimits(0, 1, 0, 1.05);
    plot.setColor(Color.red);
    plot.addPoints(r, p, Plot.LINE);
    // plot.setColor(Color.magenta);
    // plot.addPoints(r, maxp, Plot2.LINE);
    plot.drawLine(r[r.length - 1], p[r.length - 1], r[r.length - 1], 0);
    plot.setColor(Color.black);
    plot.addLabel(0, 0, "AUC = " + MathUtils.rounded(auc) + ", AUC2 = " + MathUtils.rounded(auc2));
    ImageJUtils.display(title, plot, windowOrganiser);

    title = TITLE + " Intensity";
    plot = new Plot2(title, "Candidate", "Spot");
    final double[] limits1 = MathUtils.limits(i1);
    final double[] limits2 = MathUtils.limits(i2);
    plot.setLimits(limits1[0], limits1[1], limits2[0], limits2[1]);
    plot.addLabel(0, 0, String.format("Correlation=%s; Slope=%s",
        MathUtils.rounded(c[c.length - 1]), MathUtils.rounded(slope)));
    plot.setColor(Color.red);
    plot.addPoints(i1, i2, Plot.DOT);
    if (slope > 1) {
      plot.drawLine(limits1[0], limits1[0] * slope, limits1[1], limits1[1] * slope);
    } else {
      plot.drawLine(limits2[0] / slope, limits2[0], limits2[1] / slope, limits2[1]);
    }
    ImageJUtils.display(title, plot, windowOrganiser);
  }

  private static double getFailures(double[][] cumul, double fraction) {
    int index = 0;
    final double[] sum = cumul[1];
    while (sum[index] < fraction && index < sum.length) {
      index++;
    }
    return index;
  }

  private static void addResult(StringBuilder sb, FractionClassificationResult matchResult,
      double correlation) {
    addCount(sb, matchResult.getTruePositives());
    addCount(sb, matchResult.getFalsePositives());
    sb.append(MathUtils.rounded(matchResult.getRecall())).append('\t');
    sb.append(MathUtils.rounded(matchResult.getPrecision())).append('\t');
    sb.append(MathUtils.rounded(matchResult.getJaccard())).append('\t');
    sb.append(MathUtils.rounded(correlation)).append('\t');
  }

  private static void addCount(StringBuilder sb, double value) {
    // Check if the double holds an integer count
    if ((int) value == value) {
      sb.append((int) value);
      // Otherwise add the counts using at least 2 dp
    } else if (value > 100) {
      sb.append(IJ.d2s(value));
    } else {
      sb.append(MathUtils.rounded(value));
    }
    sb.append('\t');
  }

  private static BufferedTextWindow getTable(boolean batchSummary) {
    if (batchSummary) {
      if (batchSummaryTable == null || !batchSummaryTable.isVisible()) {
        final TextWindow table = new TextWindow(TITLE + " Batch", createHeader(), "", 1000, 300);
        table.setVisible(true);
        batchSummaryTable = new BufferedTextWindow(table);
      }
      return batchSummaryTable;
    }
    if (summaryTable == null || !summaryTable.isVisible()) {
      final TextWindow table = new TextWindow(TITLE, createHeader(), "", 1000, 300);
      table.setVisible(true);
      summaryTable = new BufferedTextWindow(table);
    }
    return summaryTable;
  }

  private static String createHeader() {
    final StringBuilder sb = new StringBuilder(
        "Frames\tW\tH\tMolecules\tDensity (um^-2)\tN\ts (nm)\ta (nm)\tDepth (nm)\t"
            + "Fixed\tCamera\tB (photons)\tNoise (photons)\tSNR\ts (px)\t");
    sb.append("Type\tSearch\tBorder\tWidth\tFilter\tAbs.Param\tRel.Param\tDescription\t"
        + "A.Border\tMatching\tlower d\td\tlower sf\tsf");
    tablePrefix = sb.toString();
    sb.append("\tSlope\t");
    sb.append("TP\tFP\tRecall\tPrecision\tJaccard\tR\t");
    sb.append("@FractionRecall\t");
    sb.append("TP\tFP\tRecall\tPrecision\tJaccard\tR\t");
    sb.append("@MaxJ\t");
    sb.append("TP\tFP\tRecall\tPrecision\tJaccard\tR\t");
    sb.append("Time (ms)\t");
    sb.append("AUC\tAUC2\t");
    sb.append("Fail80\tFail90\tFail95\tFail99\tFail100");
    return sb.toString();
  }

  /**
   * Updates the given configuration using the latest settings used in benchmarking.
   *
   * @param config the configuration
   * @return true, if successful
   */
  public static boolean updateConfiguration(FitEngineConfiguration config) {
    if (filterResult == null) {
      return false;
    }

    final FitEngineConfiguration latestConfig = filterResult.config;

    config.setDataFilterType(latestConfig.getDataFilterType());
    final int nFilters = latestConfig.getNumberOfFilters();
    for (int n = 0; n < nFilters; n++) {
      final RelativeParameter p = latestConfig.getDataFilterParameter(n);
      config.setDataFilter(latestConfig.getDataFilterMethod(n), p.getValue(), p.getAbsolute(), n);
    }
    config.setSearch(latestConfig.getSearch(), latestConfig.getSearchAbsolute());
    config.setBorder(latestConfig.getBorder(), latestConfig.getBorderAbsolute());

    return true;
  }
}
