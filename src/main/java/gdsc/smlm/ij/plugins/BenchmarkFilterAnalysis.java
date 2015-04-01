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

import gdsc.smlm.fitting.FitResult;
import gdsc.smlm.fitting.FitStatus;
import gdsc.smlm.function.gaussian.Gaussian2DFunction;
import gdsc.smlm.ij.plugins.BenchmarkSpotFit.FilterCandidates;
import gdsc.smlm.ij.settings.FilterSettings;
import gdsc.smlm.ij.settings.GlobalSettings;
import gdsc.smlm.ij.settings.SettingsManager;
import gdsc.smlm.ij.utils.Utils;
import gdsc.smlm.results.Calibration;
import gdsc.smlm.results.MemoryPeakResults;
import gdsc.smlm.results.PeakResult;
import gdsc.smlm.results.filter.Filter;
import gdsc.smlm.results.filter.FilterSet;
import gdsc.smlm.results.filter.XStreamWrapper;
import gdsc.smlm.results.match.ClassificationResult;
import gdsc.smlm.results.match.MatchResult;
import gdsc.smlm.utils.UnicodeReader;
import gdsc.smlm.utils.XmlUtils;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.gui.Plot2;
import ij.gui.PlotWindow;
import ij.plugin.PlugIn;
import ij.plugin.WindowOrganiser;
import ij.text.TextWindow;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

/**
 * Run different filtering methods on a set of benchmark fitting results outputting performance statistics on the
 * success of the filter. The fitting results are generated by the BenchmarkSpotFit plugin.
 * <p>
 * Filtering is done using e.g. SNR threshold, Precision thresholds, etc. The statistics reported are shown in a table,
 * e.g. precision, Jaccard, F-score.
 */
public class BenchmarkFilterAnalysis implements PlugIn
{
	private static final String TITLE = "Filter Analysis";
	private static TextWindow resultsWindow = null;
	private static TextWindow summaryWindow = null;
	private static TextWindow sensitivityWindow = null;

	private static int failCount = 3;
	private static boolean showResultsTable = false;
	private static boolean showSummaryTable = true;
	private static int plotTopN = 0;
	private ArrayList<NamedPlot> plots;
	private static boolean calculateSensitivity = false;
	private static double delta = 0.1;
	private static int scoreIndex;

	private HashMap<String, FilterScore> bestFilter;
	private LinkedList<String> bestFilterOrder;

	private static List<FilterSet> filterList = null;
	private static int lastId = 0;
	private static List<MemoryPeakResults> resultsList = null;

	private boolean isHeadless;

	private static String[] COLUMNS = { "nP", "TP", "FP", "TN", "FN", "TPR", "TNR", "PPV", "NPV", "FPR", "FNR", "FDR",
			"ACC", "MCC", "Informedness", "Markedness", "Recall", "Precision", "F1", "Jaccard" };

	private static boolean[] showColumns;
	static
	{
		showColumns = new boolean[COLUMNS.length];
		Arrays.fill(showColumns, true);
		showColumns[0] = false; // nP
		
		scoreIndex = COLUMNS.length - 1;
	}

	private CreateData.SimulationParameters simulationParameters;

	public BenchmarkFilterAnalysis()
	{
		isHeadless = java.awt.GraphicsEnvironment.isHeadless();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	public void run(String arg)
	{
		simulationParameters = CreateData.simulationParameters;
		if (simulationParameters == null)
		{
			IJ.error(TITLE, "No benchmark spot parameters in memory");
			return;
		}
		if (BenchmarkSpotFit.fitResults == null)
		{
			IJ.error(TITLE, "No benchmark fitting results in memory");
			return;
		}
		if (BenchmarkSpotFit.lastId != simulationParameters.id)
		{
			IJ.error(TITLE, "Update the benchmark spot fitting for the latest simulation");
			return;
		}
		if (BenchmarkSpotFit.lastFilterId != BenchmarkSpotFilter.filterResultsId)
		{
			IJ.error(TITLE, "Update the benchmark spot fitting for the latest filter");
			return;
		}

		resultsList = readResults();
		if (resultsList.isEmpty())
		{
			IJ.error(TITLE, "No results could be loaded");
			return;
		}

		if (!showDialog(resultsList))
			return;

		// Load filters from file
		List<FilterSet> filterSets = readFilterSets();

		if (filterSets == null || filterSets.isEmpty())
		{
			IJ.error(TITLE, "No filters specified");
			return;
		}

		analyse(resultsList, filterSets);
	}

	@SuppressWarnings("unchecked")
	private List<FilterSet> readFilterSets()
	{
		GlobalSettings gs = SettingsManager.loadSettings();
		FilterSettings filterSettings = gs.getFilterSettings();

		String oldFilename = filterSettings.filterSetFilename;
		String filename = Utils.getFilename("Filter_File", filterSettings.filterSetFilename);
		if (filename != null)
		{
			IJ.showStatus("Reading filters ...");
			filterSettings.filterSetFilename = filename;

			// Allow the filters to be cached
			if (filename.equals(oldFilename) && filterList != null)
			{
				GenericDialog gd = new GenericDialog(TITLE);
				gd.enableYesNoCancel();
				gd.hideCancelButton();
				gd.addMessage("The same filter file was selected.\n \nRe-use the last filters?");
				gd.showDialog();
				if (gd.wasOKed())
					return filterList;
			}

			BufferedReader input = null;
			try
			{
				FileInputStream fis = new FileInputStream(filterSettings.filterSetFilename);
				input = new BufferedReader(new UnicodeReader(fis, null));
				Object o = XStreamWrapper.getInstance().fromXML(input);
				if (o != null && o instanceof List<?>)
				{
					SettingsManager.saveSettings(gs);
					filterList = (List<FilterSet>) o;
					return filterList;
				}
				IJ.log("No filter sets defined in the specified file: " + filterSettings.filterSetFilename);
			}
			catch (Exception e)
			{
				IJ.log("Unable to load the filter sets from file: " + e.getMessage());
			}
			finally
			{
				if (input != null)
				{
					try
					{
						input.close();
					}
					catch (IOException e)
					{
						// Ignore
					}
				}
				IJ.showStatus("");
			}
		}
		return null;
	}

	private List<MemoryPeakResults> readResults()
	{
		if (resultsList == null || lastId != BenchmarkSpotFit.fitResultsId)
		{
			lastId = BenchmarkSpotFit.fitResultsId;
			resultsList = new LinkedList<MemoryPeakResults>();
			MemoryPeakResults r = new MemoryPeakResults();
			Calibration cal = new Calibration(simulationParameters.a, simulationParameters.gain, 100);
			cal.bias = simulationParameters.bias;
			cal.emCCD = simulationParameters.emCCD;
			cal.readNoise = simulationParameters.readNoise;
			r.setCalibration(cal);
			// Set the configuration used for fitting
			r.setConfiguration(XmlUtils.toXML(BenchmarkSpotFit.fitConfig));

			for (Entry<Integer, FilterCandidates> entry : BenchmarkSpotFit.fitResults.entrySet())
			{
				final int peak = entry.getKey().intValue();
				final FilterCandidates result = entry.getValue();
				for (int i = 0; i < result.fitResult.length; i++)
				{
					final FitResult fitResult = result.fitResult[i];
					if (fitResult.getStatus() == FitStatus.OK)
					{
						// Assume we are not fitting doublets and the fit result will have 1 peak
						final float[] params = Utils.toFloat(fitResult.getParameters());
						final int origX = (int) params[Gaussian2DFunction.X_POSITION];
						final int origY = (int) params[Gaussian2DFunction.Y_POSITION];
						r.add(peak, origX, origY, (result.fitMatch[i]) ? 1 : 0, fitResult.getError(), result.noise,
								params, null);
					}
				}
			}

			// We need to sort by frame then candidate order but this is already done 
			// since the candidates are in the original ranked order

			if (r.size() > 0)
				resultsList.add(r);
		}
		return resultsList;
	}

	private boolean showDialog(List<MemoryPeakResults> resultsList)
	{
		GenericDialog gd = new GenericDialog(TITLE);
		gd.addHelp(About.HELP_URL);

		int total = 0;
		int tp = 0;
		for (MemoryPeakResults r : resultsList)
		{
			total += r.size();
			for (PeakResult p : r.getResults())
				if (p.origValue != 0)
					tp++;
		}
		gd.addMessage(String.format("%d results, %d True-Positives", total, tp));

		gd.addSlider("Fail_count", 0, 20, failCount);
		gd.addCheckbox("Show_table", showResultsTable);
		gd.addCheckbox("Show_summary", showSummaryTable);
		gd.addSlider("Plot_top_n", 0, 20, plotTopN);
		gd.addCheckbox("Calculate_sensitivity", calculateSensitivity);
		gd.addSlider("Delta", 0.01, 1, delta);
		gd.addChoice("Score", COLUMNS, COLUMNS[scoreIndex]);

		gd.showDialog();

		if (gd.wasCanceled() || !readDialog(gd))
			return false;

		if (showResultsTable || showSummaryTable)
		{
			gd = new GenericDialog(TITLE);
			gd.addHelp(About.HELP_URL);

			gd.addMessage("Select the results:");
			for (int i = 0; i < COLUMNS.length; i++)
				gd.addCheckbox(COLUMNS[i], showColumns[i]);
			gd.showDialog();

			if (gd.wasCanceled())
				return false;

			for (int i = 0; i < COLUMNS.length; i++)
				showColumns[i] = gd.getNextBoolean();
		}

		return true;
	}

	private boolean readDialog(GenericDialog gd)
	{
		failCount = (int) Math.abs(gd.getNextNumber());
		showResultsTable = gd.getNextBoolean();
		showSummaryTable = gd.getNextBoolean();
		plotTopN = (int) Math.abs(gd.getNextNumber());
		calculateSensitivity = gd.getNextBoolean();
		delta = gd.getNextNumber();
		scoreIndex = gd.getNextChoiceIndex();

		// Check there is one output
		if (!showResultsTable && !showSummaryTable && !calculateSensitivity && plotTopN < 1)
		{
			IJ.error(TITLE, "No output selected");
			return false;
		}

		// Check arguments
		try
		{
			Parameters.isAboveZero("Delta", delta);
			Parameters.isBelow("Delta", delta, 1);
		}
		catch (IllegalArgumentException e)
		{
			IJ.error(TITLE, e.getMessage());
			return false;
		}

		return !gd.invalidNumber();
	}

	/**
	 * Run different filtering methods on a set of labelled peak results outputting performance statistics on the
	 * success of
	 * the filter to an ImageJ table.
	 * <p>
	 * If the peak result original value is set to 1 it is considered a true peak, 0 for a false peak. Filtering is done
	 * using e.g. SNR threshold, Precision thresholds, etc. The statistics reported are shown in a table, e.g.
	 * precision, Jaccard, F-score.
	 * <p>
	 * For each filter set a plot is shown of the score verses the filter value, thus filters should be provided in
	 * ascending numerical order otherwise they are sorted.
	 * 
	 * @param resultsList
	 * @param filterSets
	 */
	private void analyse(List<MemoryPeakResults> resultsList, List<FilterSet> filterSets)
	{
		createResultsWindow();
		plots = new ArrayList<NamedPlot>(plotTopN);
		bestFilter = new HashMap<String, FilterScore>();
		bestFilterOrder = new LinkedList<String>();

		IJ.showStatus("Analysing filters ...");
		int total = countFilters(filterSets);
		int count = 0;
		for (FilterSet filterSet : filterSets)
		{
			IJ.showStatus("Analysing " + filterSet.getName() + " ...");
			count = run(filterSet, resultsList, count, total);
		}
		IJ.showProgress(1);
		IJ.showStatus("");

		if (showSummaryTable)
		{
			createSummaryWindow();
			List<FilterScore> filters = new ArrayList<FilterScore>(bestFilter.values());
			Collections.sort(filters);
			for (FilterScore fs : filters)
			{
				ClassificationResult r = fs.filter.score(resultsList, failCount);
				String text = createResult(fs.filter, r);
				if (isHeadless)
					IJ.log(text);
				else
					summaryWindow.append(text);
			}
			// Add a spacer to the summary table
			if (isHeadless)
				IJ.log("");
			else
				summaryWindow.append("");
		}

		showPlots();
		calculateSensitivity(resultsList);
	}

	private int countFilters(List<FilterSet> filterSets)
	{
		int count = 0;
		for (FilterSet filterSet : filterSets)
			count += filterSet.size();
		return count;
	}

	private void showPlots()
	{
		if (plots.isEmpty())
			return;

		// Display the top N plots
		int[] list = new int[plots.size()];
		int i = 0;
		for (NamedPlot p : plots)
		{
			Plot2 plot = new Plot2(p.name, p.xAxisName, COLUMNS[scoreIndex], p.xValues, p.yValues);
			plot.setLimits(p.xValues[0], p.xValues[p.xValues.length - 1], 0, 1);
			plot.setColor(Color.RED);
			plot.draw();
			plot.setColor(Color.BLUE);
			plot.addPoints(p.xValues, p.yValues, Plot2.CROSS);
			PlotWindow plotWindow = Utils.display(p.name, plot);
			list[i++] = plotWindow.getImagePlus().getID();
		}
		new WindowOrganiser().tileWindows(list);
	}

	private void calculateSensitivity(List<MemoryPeakResults> resultsList)
	{
		if (!calculateSensitivity)
			return;
		if (!bestFilter.isEmpty())
		{
			IJ.showStatus("Calculating sensitivity ...");
			createSensitivityWindow();

			int currentIndex = 0;
			for (String type : bestFilterOrder)
			{
				IJ.showProgress(currentIndex++, bestFilter.size());

				Filter filter = bestFilter.get(type).filter;

				ClassificationResult s = filter.score(resultsList, failCount);

				String message = type + "\t\t\t" + Utils.rounded(s.getJaccard(), 4) + "\t\t" +
						Utils.rounded(s.getPrecision(), 4) + "\t\t" + Utils.rounded(s.getRecall(), 4);

				if (isHeadless)
				{
					IJ.log(message);
				}
				else
				{
					sensitivityWindow.append(message);
				}

				// List all the parameters that can be adjusted.
				final int parameters = filter.getNumberOfParameters();
				for (int index = 0; index < parameters; index++)
				{
					// For each parameter compute as upward + downward delta and get the average gradient
					Filter higher = filter.adjustParameter(index, delta);
					Filter lower = filter.adjustParameter(index, -delta);

					ClassificationResult sHigher = higher.score(resultsList, failCount);
					ClassificationResult sLower = lower.score(resultsList, failCount);

					StringBuilder sb = new StringBuilder();
					sb.append("\t").append(filter.getParameterName(index)).append("\t");
					sb.append(Utils.rounded(filter.getParameterValue(index), 4)).append("\t");

					double dx1 = higher.getParameterValue(index) - filter.getParameterValue(index);
					double dx2 = filter.getParameterValue(index) - lower.getParameterValue(index);
					addSensitivityScore(sb, s.getJaccard(), sHigher.getJaccard(), sLower.getJaccard(), dx1, dx2);
					addSensitivityScore(sb, s.getPrecision(), sHigher.getPrecision(), sLower.getPrecision(), dx1, dx2);
					addSensitivityScore(sb, s.getRecall(), sHigher.getRecall(), sLower.getRecall(), dx1, dx2);

					if (isHeadless)
					{
						IJ.log(sb.toString());
					}
					else
					{
						sensitivityWindow.append(sb.toString());
					}
				}
			}

			String message = "-=-=-=-";
			if (isHeadless)
			{
				IJ.log(message);
			}
			else
			{
				sensitivityWindow.append(message);
			}

			IJ.showProgress(1);
			IJ.showStatus("");
		}
	}

	private void addSensitivityScore(StringBuilder sb, double s, double s1, double s2, double dx1, double dx2)
	{
		// Use absolute in case this is not a local maximum. We are mainly interested in how
		// flat the curve is at this point in relation to parameter changes.
		double abs1 = Math.abs(s - s1);
		double abs2 = Math.abs(s - s2);
		double dydx1 = (abs1) / dx1;
		double dydx2 = (abs2) / dx2;
		double relativeSensitivity = (abs1 + abs2) * 0.5;
		double sensitivity = (dydx1 + dydx2) * 0.5;

		sb.append(Utils.rounded(relativeSensitivity, 4)).append("\t");
		sb.append(Utils.rounded(sensitivity, 4)).append("\t");
	}

	private void createResultsWindow()
	{
		if (!showResultsTable)
			return;

		if (isHeadless)
		{
			IJ.log(createResultsHeader());
		}
		else
		{
			if (resultsWindow == null || !resultsWindow.isShowing())
			{
				String header = createResultsHeader();
				resultsWindow = new TextWindow(TITLE + " Results", header, "", 900, 300);
			}
		}
	}

	private void createSummaryWindow()
	{
		if (!showSummaryTable)
			return;

		if (isHeadless)
		{
			IJ.log(createResultsHeader());
		}
		else
		{
			if (summaryWindow == null || !summaryWindow.isShowing())
			{
				String header = createResultsHeader();
				summaryWindow = new TextWindow(TITLE + " Summary", header, "", 900, 300);
			}
		}
	}

	private String createResultsHeader()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Name\tFail");

		for (int i = 0; i < COLUMNS.length; i++)
			if (showColumns[i])
				sb.append("\t").append(COLUMNS[i]);

		// Always show the results compared to the original simulation
		sb.append("\toRecall");
		sb.append("\toPrecision");
		sb.append("\toF1");
		sb.append("\toJaccard");
		return sb.toString();
	}

	private void createSensitivityWindow()
	{
		if (isHeadless)
		{
			IJ.log(createSensitivityHeader());
		}
		else
		{
			if (sensitivityWindow == null || !sensitivityWindow.isShowing())
			{
				String header = createSensitivityHeader();
				sensitivityWindow = new TextWindow(TITLE + " Sensitivity", header, "", 900, 300);
			}
		}
	}

	private String createSensitivityHeader()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Filter\t");
		sb.append("Param\t");
		sb.append("Value\t");
		sb.append("J Sensitivity (delta)\t");
		sb.append("J Sensitivity (unit)\t");
		sb.append("P Sensitivity (delta)\t");
		sb.append("P Sensitivity (unit)\t");
		sb.append("R Sensitivity (delta)\t");
		sb.append("R Sensitivity (unit)\t");
		return sb.toString();
	}

	private int run(FilterSet filterSet, List<MemoryPeakResults> resultsList, int count, final int total)
	{
		double[] xValues = (isHeadless) ? null : new double[filterSet.size()];
		double[] yValues = (isHeadless) ? null : new double[filterSet.size()];
		int i = 0;

		filterSet.sort();

		// Track if all the filters are the same type. If so then we can calculate the sensitivity of each parameter.
		String type = null;
		boolean allSameType = true;
		Filter maxFilter = null;
		double maxScore = -1;

		for (Filter filter : filterSet.getFilters())
		{
			if (count++ % 16 == 0)
				IJ.showProgress(count, total);

			ClassificationResult s = run(filter, resultsList);

			if (type == null)
				type = filter.getType();
			else if (!type.equals(filter.getType()))
				allSameType = false;

			final double score = getScore(s);
			if (filter == null || maxScore < score)
			{
				maxScore = score;
				maxFilter = filter;
			}

			if (!isHeadless)
			{
				xValues[i] = filter.getNumericalValue();
				yValues[i++] = score;
			}
		}

		if (allSameType)
		{
			if (bestFilter.containsKey(type))
			{
				FilterScore filterScore = bestFilter.get(type);
				if (filterScore.score < maxScore)
					filterScore.update(maxFilter, maxScore);
			}
			else
			{
				bestFilter.put(type, new FilterScore(maxFilter, maxScore));
				bestFilterOrder.add(type);
			}
		}

		// Add spacer at end of each result set
		if (isHeadless)
		{
			if (showResultsTable)
				IJ.log("");
		}
		else
		{
			if (showResultsTable)
				resultsWindow.append("");

			if (plotTopN > 0)
			{
				// Check the xValues are unique. Since the filters have been sorted by their
				// numeric value we only need to compare adjacent entries.
				boolean unique = true;
				for (int ii = 0; ii < xValues.length - 1; ii++)
				{
					if (xValues[ii] == xValues[ii + 1])
					{
						unique = false;
						break;
					}
				}
				String xAxisName = filterSet.getValueName();
				// Check the values all refer to the same property
				for (Filter filter : filterSet.getFilters())
				{
					if (!xAxisName.equals(filter.getNumericalValueName()))
					{
						unique = false;
						break;
					}
				}
				if (!unique)
				{
					// If not unique then renumber them and use an arbitrary label
					xAxisName = "Filter";
					for (int ii = 0; ii < xValues.length; ii++)
						xValues[ii] = ii + 1;
				}

				String title = filterSet.getName();

				// Check if a previous filter set had the same name, update if necessary
				NamedPlot p = getNamedPlot(title);
				if (p == null)
					plots.add(new NamedPlot(title, xAxisName, xValues, yValues));
				else
					p.updateValues(xAxisName, xValues, yValues);

				if (plots.size() > plotTopN)
				{
					Collections.sort(plots);
					p = plots.remove(plots.size() - 1);
				}
			}
		}

		return count;
	}

	public double getScore(ClassificationResult s)
	{
		// This order must match the COLUMNS order 
		switch (scoreIndex)
		{
			case 0:
				return s.getTP() + s.getFP();
			case 1:
				return s.getTP();
			case 2:
				return s.getFP();
			case 3:
				return s.getTN();
			case 4:
				return s.getFN();
			case 5:
				return s.getTPR();
			case 6:
				return s.getTNR();
			case 7:
				return s.getPPV();
			case 8:
				return s.getNPV();
			case 9:
				return s.getFPR();
			case 10:
				return s.getFNR();
			case 11:
				return s.getFDR();
			case 12:
				return s.getAccuracy();
			case 13:
				return s.getMCC();
			case 14:
				return s.getInformedness();
			case 15:
				return s.getMarkedness();
			case 16:
				return s.getRecall();
			case 17:
				return s.getPrecision();
			case 18:
				return s.getFScore(1);
			case 19:
				return s.getJaccard();
		}
		return 0;
	}

	private NamedPlot getNamedPlot(String title)
	{
		for (NamedPlot p : plots)
			if (p.name.equals(title))
				return p;
		return null;
	}

	private double getMaximum(double[] values)
	{
		double max = values[0];
		for (int i = 1; i < values.length; i++)
		{
			if (values[i] > max)
			{
				max = values[i];
			}
		}
		return max;
	}

	private ClassificationResult run(Filter filter, List<MemoryPeakResults> resultsList)
	{
		ClassificationResult r = filter.score(resultsList, failCount);

		if (showResultsTable)
		{
			String text = createResult(filter, r);

			if (isHeadless)
			{
				IJ.log(text);
			}
			else
			{
				resultsWindow.append(text);
			}
		}
		return r;
	}

	public String createResult(Filter filter, ClassificationResult r)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(filter.getName()).append("\t").append(failCount);

		int i = 0;
		add(sb, r.getTP() + r.getFP(), i++);
		add(sb, r.getTP(), i++);
		add(sb, r.getFP(), i++);
		add(sb, r.getTN(), i++);
		add(sb, r.getFN(), i++);

		add(sb, r.getTPR(), i++);
		add(sb, r.getTNR(), i++);
		add(sb, r.getPPV(), i++);
		add(sb, r.getNPV(), i++);
		add(sb, r.getFPR(), i++);
		add(sb, r.getFNR(), i++);
		add(sb, r.getFDR(), i++);
		add(sb, r.getAccuracy(), i++);
		add(sb, r.getMCC(), i++);
		add(sb, r.getInformedness(), i++);
		add(sb, r.getMarkedness(), i++);

		add(sb, r.getRecall(), i++);
		add(sb, r.getPrecision(), i++);
		add(sb, r.getFScore(1), i++);
		add(sb, r.getJaccard(), i++);

		// Score relative to the original simulated number of spots
		// Score the fitting results:
		// TP are all fit results that can be matched to a spot
		// FP are all fit results that cannot be matched to a spot
		// FN = The number of missed spots
		final int tp = r.getTP();
		final int fp = r.getFP();
		MatchResult m = new MatchResult(tp, fp, simulationParameters.molecules - tp, 0);
		add(sb, m.getRecall());
		add(sb, m.getPrecision());
		add(sb, m.getFScore(1));
		add(sb, m.getJaccard());
		return sb.toString();
	}

	private static void add(StringBuilder sb, String value)
	{
		sb.append("\t").append(value);
	}

	private static void add(StringBuilder sb, int value, int i)
	{
		if (showColumns[i])
			sb.append("\t").append(value);
	}

	private static void add(StringBuilder sb, double value, int i)
	{
		if (showColumns[i])
			add(sb, Utils.rounded(value));
	}

	private static void add(StringBuilder sb, double value)
	{
		add(sb, Utils.rounded(value));
	}

	public class FilterScore implements Comparable<FilterScore>
	{
		Filter filter;
		double score;

		public FilterScore(Filter filter, double score)
		{
			update(filter, score);
		}

		public void update(Filter filter, double score)
		{
			this.filter = filter;
			this.score = score;
		}

		@Override
		public int compareTo(FilterScore that)
		{
			if (this.score > that.score)
				return -1;
			if (this.score < that.score)
				return 1;
			return 0;
		}
	}

	public class NamedPlot implements Comparable<NamedPlot>
	{
		String name, xAxisName;
		double[] xValues, yValues;
		double score;

		public NamedPlot(String name, String xAxisName, double[] xValues, double[] yValues)
		{
			this.name = name;
			updateValues(xAxisName, xValues, yValues);
		}

		public void updateValues(String xAxisName, double[] xValues, double[] yValues)
		{
			this.xAxisName = xAxisName;
			this.xValues = xValues;
			this.yValues = yValues;
			this.score = getMaximum(yValues);
		}

		public int compareTo(NamedPlot o)
		{
			if (score > o.score)
				return -1;
			if (score < o.score)
				return 1;
			return 0;
		}
	}
}
