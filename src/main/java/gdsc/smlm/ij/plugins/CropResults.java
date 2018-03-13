package gdsc.smlm.ij.plugins;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2017 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

import org.apache.commons.math3.util.FastMath;

import gdsc.core.data.utils.ConversionException;
import gdsc.core.data.utils.IdentityTypeConverter;
import gdsc.core.data.utils.TypeConverter;
import gdsc.core.ij.roi.RoiTest;
import gdsc.core.ij.roi.RoiTestFactory;
import gdsc.core.utils.Maths;
import gdsc.core.utils.TextUtils;
import gdsc.core.utils.TurboList;
import gdsc.smlm.data.config.CalibrationHelper;
import gdsc.smlm.data.config.GUIProtos.CropResultsSettings;
import gdsc.smlm.data.config.UnitHelper;
import gdsc.smlm.data.config.UnitProtos.DistanceUnit;
import gdsc.smlm.ij.plugins.ResultsManager.InputSource;
import gdsc.smlm.ij.settings.SettingsManager;
import gdsc.smlm.results.MemoryPeakResults;
import gdsc.smlm.results.PeakResult;
import gdsc.smlm.results.PeakResultParameterValue;
import gdsc.smlm.results.predicates.MinMaxPeakResultPredicate;
import gdsc.smlm.results.predicates.PassPeakResultPredicate;
import gdsc.smlm.results.predicates.PeakResultPredicate;
import gdsc.smlm.results.procedures.MinMaxResultProcedure;
import gdsc.smlm.results.procedures.XYRResultProcedure;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ExtendedGenericDialog;
import ij.gui.ExtendedGenericDialog.OptionListener;
import ij.plugin.PlugIn;

/**
 * Filters PeakFit results that are stored in memory using various fit criteria.
 */
public class CropResults implements PlugIn
{
	private static final String TITLE = "Crop Results";
	static final String[] NAME_OPTIONS = { "Name", "Suffix", "Sequence" };
	static final int NAME_OPTION_NAME = 0;
	static final int NAME_OPTION_SUFFIX = 1;
	static final int NAME_OPTION_SEQUENCE = 2;

	private TurboList<String> titles;
	private CropResultsSettings.Builder settings;
	private boolean myUseRoi;
	private MemoryPeakResults results;
	private String outputName;
	private MinMaxResultProcedure minMax;
	private TypeConverter<DistanceUnit> c;
	private boolean myLimitZ = false;

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	public void run(String arg)
	{
		SMLMUsageTracker.recordPlugin(this.getClass(), arg);

		if (MemoryPeakResults.isMemoryEmpty())
		{
			IJ.error(TITLE, "There are no fitting results in memory");
			return;
		}

		// Build a list of all images with a region ROI
		titles = new TurboList<String>(WindowManager.getWindowCount());
		if (WindowManager.getWindowCount() > 0)
		{
			for (int imageID : WindowManager.getIDList())
			{
				ImagePlus imp = WindowManager.getImage(imageID);
				if (imp != null && imp.getRoi() != null && imp.getRoi().isArea())
					titles.add(imp.getTitle());
			}
		}
		boolean roiMode = "roi".equals(arg);
		if (roiMode && titles.isEmpty())
		{
			IJ.error(TITLE, "No images with an ROI");
			return;
		}

		settings = SettingsManager.readCropResultsSettings(0).toBuilder();

		// Show a dialog allowing the results set to be filtered
		ExtendedGenericDialog gd = new ExtendedGenericDialog(TITLE);
		gd.addMessage("Select a dataset to crop");
		ResultsManager.addInput(gd, settings.getInputOption(), InputSource.MEMORY);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		settings.setInputOption(ResultsManager.getInputSource(gd));
		results = ResultsManager.loadInputResults(settings.getInputOption(), false, null, null);
		if (results == null || results.size() == 0)
		{
			IJ.error(TITLE, "No results could be loaded");
			IJ.showStatus("");
			return;
		}

		// Allow z-filtering
		if (results.is3D())
			minMax = new MinMaxResultProcedure(results, new PeakResultParameterValue(PeakResult.Z));

		if (roiMode)
		{
			runRoiCrop();
		}
		else
		{
			runCrop();
		}

		SettingsManager.writeSettings(settings);
	}

	private void runCrop()
	{
		if (!showCropDialog())
			return;
		cropResults();
	}

	private boolean showCropDialog()
	{
		ExtendedGenericDialog gd = new ExtendedGenericDialog(TITLE);
		gd.addHelp(About.HELP_URL);

		Rectangle bounds = results.getBounds(true);
		results.is3D();

		gd.addMessage(String.format("x=%d,y=%d,w=%d,h=%d", bounds.x, bounds.y, bounds.width, bounds.height));
		gd.addNumericField("Border", settings.getBorder(), 2);
		gd.addCheckbox("Select_region", settings.getSelectRegion());
		gd.addNumericField("X", settings.getX(), 2);
		gd.addNumericField("Y", settings.getY(), 2);
		gd.addNumericField("Width", settings.getWidth(), 2);
		gd.addNumericField("Height", settings.getHeight(), 2);
		if (!titles.isEmpty())
		{
			gd.addCheckbox("Use_ROI", settings.getUseRoi());
			String[] items = titles.toArray(new String[titles.size()]);
			gd.addChoice("Image", items, settings.getRoiImage());
		}
		addStandardFields(gd);
		gd.addCheckbox("Reset_origin", settings.getResetOrigin());

		gd.showDialog();

		if (gd.wasCanceled())
			return false;

		settings.setBorder(Math.max(0, gd.getNextNumber()));
		settings.setSelectRegion(gd.getNextBoolean());
		settings.setX(gd.getNextNumber());
		settings.setY(gd.getNextNumber());
		settings.setWidth(Math.max(0, gd.getNextNumber()));
		settings.setHeight(Math.max(0, gd.getNextNumber()));
		if (!titles.isEmpty())
		{
			myUseRoi = gd.getNextBoolean();
			settings.setUseRoi(myUseRoi);
			settings.setRoiImage(gd.getNextChoice());
		}
		readStandardFields(gd);
		settings.setResetOrigin(gd.getNextBoolean());

		gd.collectOptions();

		return validateOutputName();
	}

	private void addStandardFields(final ExtendedGenericDialog gd)
	{
		if (minMax != null)
		{
			// 3D crop options 
			double min = minMax.getMinimum();
			double max = minMax.getMaximum();

			double maxz = FastMath.min(settings.getMaxZ(), max);
			double minz = FastMath.max(settings.getMinZ(), min);

			// Display in nm
			c = new IdentityTypeConverter<DistanceUnit>(null);
			String unit = "";

			DistanceUnit nativeUnit = results.getDistanceUnit();
			if (nativeUnit != null)
			{
				unit = UnitHelper.getShortName(nativeUnit);
				try
				{
					c = CalibrationHelper.getDistanceConverter(results.getCalibration(), DistanceUnit.NM);
					unit = UnitHelper.getShortName(DistanceUnit.NM);
				}
				catch (ConversionException e)
				{

				}
			}
			min = c.convert(min);
			max = c.convert(max);

			String msg = String.format("%.2f <= z <= %.2f (%s)", min, max, unit);

			min = Math.floor(min);
			max = Math.ceil(max);

			gd.addMessage(msg);
			gd.addCheckbox("Limit Z-depth", settings.getLimitZ());
			gd.addSlider("minZ", min, max, c.convert(minz));
			gd.addSlider("maxZ", min, max, c.convert(maxz));
		}

		gd.addChoice("Name_option", NAME_OPTIONS, settings.getNameOption(), new OptionListener<Integer>()
		{
			public boolean collectOptions(Integer value)
			{
				settings.setNameOption(value);
				return collectOptions(false);
			}

			public boolean collectOptions()
			{
				return collectOptions(true);
			}

			private boolean collectOptions(boolean silent)
			{
				ExtendedGenericDialog egd = new ExtendedGenericDialog(TITLE);
				if (settings.getNameOption() == NAME_OPTION_NAME)
				{
					String name = (TextUtils.isNullOrEmpty(settings.getOutputName())) ? (results.getName() + " Cropped")
							: settings.getOutputName();
					egd.addStringField("Output_name", name, Maths.clip(60, 120, name.length()));
				}
				else if (settings.getNameOption() == NAME_OPTION_SUFFIX)
				{
					String name = (TextUtils.isNullOrEmpty(settings.getNameSuffix())) ? " Cropped"
							: settings.getNameSuffix();
					egd.addStringField("Name_suffix", name, Maths.clip(20, 60, name.length()));
				}
				else if (settings.getNameOption() == NAME_OPTION_SEQUENCE)
				{
					String name = settings.getNameSuffix();
					egd.addStringField("Name_suffix", name, Maths.clip(20, 60, name.length()));
					int c = settings.getNameCounter();
					if (c < 1)
						c = 1;
					egd.addNumericField("Name_counter", c, 0);
				}
				else
				{
					throw new IllegalStateException("Unknown name option: " + settings.getNameOption());
				}
				egd.setSilent(silent);
				egd.showDialog(true, gd);
				if (egd.wasCanceled())
					return false;
				if (settings.getNameOption() == NAME_OPTION_NAME)
				{
					settings.setOutputName(egd.getNextString());
				}
				else if (settings.getNameOption() == NAME_OPTION_SUFFIX)
				{
					settings.setNameSuffix(egd.getNextString());
				}
				else if (settings.getNameOption() == NAME_OPTION_SEQUENCE)
				{
					settings.setNameSuffix(egd.getNextString());
					settings.setNameCounter(Math.max(1, (int) egd.getNextNumber()));
				}

				return true;
			}
		});
	}

	private void readStandardFields(ExtendedGenericDialog gd)
	{
		if (minMax != null)
		{
			// 3D crop options 
			myLimitZ = gd.getNextBoolean();
			settings.setLimitZ(myLimitZ);
			settings.setMinZ(c.convertBack(gd.getNextNumber()));
			settings.setMaxZ(c.convertBack(gd.getNextNumber()));
		}

		settings.setNameOption(gd.getNextChoiceIndex());
	}

	private boolean validateOutputName()
	{
		if (settings.getNameOption() == NAME_OPTION_NAME)
		{
			outputName = settings.getOutputName();
			if (TextUtils.isNullOrEmpty(outputName))
			{
				IJ.error(TITLE, "No output name");
				return false;
			}
		}
		else if (settings.getNameOption() == NAME_OPTION_SUFFIX)
		{
			String suffix = settings.getNameSuffix();
			if (TextUtils.isNullOrEmpty(suffix))
			{
				IJ.error(TITLE, "No output suffix");
				return false;
			}
			//if (suffix.charAt(0) != ' ')
			//	suffix = " " + suffix;
			outputName = results.getName() + suffix;
		}
		else if (settings.getNameOption() == NAME_OPTION_SEQUENCE)
		{
			outputName = results.getName();
			String suffix = settings.getNameSuffix();
			if (!TextUtils.isNullOrEmpty(suffix))
			{
				//if (suffix.charAt(0) != ' ')
				//	outputName += " " + suffix;
				//else
				outputName += suffix;
			}
			int c = settings.getNameCounter();
			outputName += c;
			settings.setNameCounter(c + 1); // Increment for next time
		}
		return true;
	}

	/**
	 * Apply the filters to the data
	 */
	private void cropResults()
	{
		final MemoryPeakResults newResults = createNewResults();

		// These bounds are integer. But this is because the results are meant to come from an image.
		Rectangle integerBounds = results.getBounds(true);

		// The crop bounds can be floating point...

		// Border
		double border = settings.getBorder();
		double xx = integerBounds.x + border;
		double yy = integerBounds.y + border;
		double w = Math.max(0, integerBounds.width - 2 * border);
		double h = Math.max(0, integerBounds.height - 2 * border);
		Rectangle2D pixelBounds = new Rectangle2D.Double(xx, yy, w, h);

		// Bounding box
		if (settings.getSelectRegion())
		{
			Rectangle2D boxBounds = new Rectangle2D.Double(settings.getX(), settings.getY(), settings.getWidth(),
					settings.getHeight());
			pixelBounds = pixelBounds.createIntersection(boxBounds);
		}

		// If an ROI was chosen from an image, scale the roi to the bounds of this dataset
		// and create another intersection
		if (myUseRoi)
		{
			ImagePlus imp = WindowManager.getImage(settings.getRoiImage());
			if (imp != null && imp.getRoi() != null)
			{
				Rectangle roi = imp.getRoi().getBounds();
				int roiImageWidth = imp.getWidth();
				int roiImageHeight = imp.getHeight();

				double xscale = (double) roiImageWidth / integerBounds.width;
				double yscale = (double) roiImageHeight / integerBounds.height;

				Rectangle2D roiBounds = new Rectangle2D.Double(roi.x / xscale, roi.y / yscale, roi.width / xscale,
						roi.height / yscale);
				pixelBounds = pixelBounds.createIntersection(roiBounds);
			}
		}

		final Rectangle2D bounds = pixelBounds;

		final PeakResultPredicate testZ = getZFilter();

		if (bounds.getWidth() > 0 && bounds.getHeight() > 0)
		{
			results.forEach(DistanceUnit.PIXEL, new XYRResultProcedure()
			{
				public void executeXYR(float x, float y, PeakResult result)
				{
					if (bounds.contains(x, y) && testZ.test(result))
						newResults.add(result);
				}
			});
		}

		newResults.setBounds(new Rectangle((int) Math.floor(bounds.getX()), (int) Math.floor(bounds.getY()),
				(int) Math.ceil(bounds.getWidth()), (int) Math.ceil(bounds.getHeight())));

		if (settings.getResetOrigin())
		{
			Rectangle b = newResults.getBounds();
			newResults.translate(-b.x, -b.y);
		}

		IJ.showStatus(newResults.size() + " Cropped localisations");
	}

	/**
	 * Gets the z filter if cropping the z coordinate range.
	 *
	 * @return the z filter
	 */
	private PeakResultPredicate getZFilter()
	{
		if (myLimitZ)
		{
			return new MinMaxPeakResultPredicate((float) settings.getMinZ(), (float) settings.getMaxZ(),
					new PeakResultParameterValue(PeakResult.Z));
		}
		return new PassPeakResultPredicate();
	}

	private MemoryPeakResults createNewResults()
	{
		final MemoryPeakResults newResults = new MemoryPeakResults();
		newResults.copySettings(results);
		newResults.setName(outputName);
		MemoryPeakResults.addResults(newResults);
		return newResults;
	}

	private void runRoiCrop()
	{
		if (!showRoiCropDialog())
			return;
		roiCropResults();
	}

	private boolean showRoiCropDialog()
	{
		ExtendedGenericDialog gd = new ExtendedGenericDialog(TITLE);
		gd.addHelp(About.HELP_URL);

		String[] items = titles.toArray(new String[titles.size()]);
		gd.addMessage("Use ROI from ...");
		gd.addChoice("Image", items, settings.getRoiImage());
		addStandardFields(gd);

		gd.showDialog();

		if (gd.wasCanceled())
			return false;

		settings.setRoiImage(gd.getNextChoice());
		readStandardFields(gd);

		gd.collectOptions();

		return validateOutputName();
	}

	private void roiCropResults()
	{
		final MemoryPeakResults newResults = createNewResults();

		// These bounds are integer. But this is because the results are meant to come from an image.
		Rectangle integerBounds = results.getBounds(true);

		ImagePlus imp = WindowManager.getImage(settings.getRoiImage());
		if (imp == null)
		{
			IJ.error(TITLE, "No ROI image: " + settings.getRoiImage());
			return;
		}
		final RoiTest roiTest = RoiTestFactory.create(imp.getRoi());
		if (roiTest == null)
		{
			IJ.error(TITLE, "Not an area ROI");
			return;
		}

		// Scale the results to the size of the image with the ROI
		int roiImageWidth = imp.getWidth();
		int roiImageHeight = imp.getHeight();
		final double xscale = (double) roiImageWidth / integerBounds.width;
		final double yscale = (double) roiImageHeight / integerBounds.height;

		final PeakResultPredicate testZ = getZFilter();

		results.forEach(DistanceUnit.PIXEL, new XYRResultProcedure()
		{
			public void executeXYR(float x, float y, PeakResult result)
			{
				if (roiTest.contains(x * xscale, y * yscale) && testZ.test(result))
					newResults.add(result);
			}
		});

		newResults.setBounds(null);
		newResults.getBounds(true);

		IJ.showStatus(newResults.size() + " Cropped localisations");
	}
}
