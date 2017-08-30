package gdsc.smlm.ij.plugins;

import java.awt.AWTEvent;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Label;
import java.awt.Rectangle;
import java.awt.TextField;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import gdsc.core.ij.Utils;
import gdsc.core.match.BasePoint;
import gdsc.core.match.Coordinate;
import gdsc.core.match.MatchCalculator;
import gdsc.core.match.MatchResult;
import gdsc.core.match.PointPair;
import gdsc.smlm.data.config.FitProtos.DataFilterMethod;
import gdsc.smlm.data.config.FitProtos.DataFilterType;
import gdsc.smlm.data.config.FitProtos.FitEngineSettings;
import gdsc.smlm.data.config.PSFProtos.PSF;
import gdsc.smlm.data.config.PSFProtosHelper;
import gdsc.smlm.data.config.TemplateProtos.TemplateSettings;
import gdsc.smlm.engine.FitConfiguration;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2016 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import gdsc.smlm.engine.FitEngineConfiguration;
import gdsc.smlm.filters.MaximaSpotFilter;
import gdsc.smlm.filters.Spot;
import gdsc.smlm.ij.plugins.PeakFit.FitConfigurationProvider;
import gdsc.smlm.ij.settings.SettingsManager;
import gdsc.smlm.model.camera.CameraModel;
import gdsc.smlm.model.camera.FakePerPixelCameraModel;
import gdsc.smlm.results.MemoryPeakResults;
import gnu.trove.map.hash.TIntObjectHashMap;
import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.ImageRoi;
import ij.gui.NonBlockingExtendedGenericDialog;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.Blitter;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Runs the candidate maxima identification on the image and provides a preview using an overlay
 */
public class SpotFinderPreview
		implements ExtendedPlugInFilter, DialogListener, ImageListener, ItemListener, FitConfigurationProvider
{
	private final static String TITLE = "Spot Finder Preview";

	private static DataFilterMethod defaultDataFilterMethod;
	private static double defaultSmooth;
	static
	{
		FitEngineConfiguration c = new FitEngineConfiguration();
		defaultDataFilterMethod = c.getDataFilterMethod(0);
		defaultSmooth = c.getSmooth(0);
	}

	private int flags = DOES_16 | DOES_8G | DOES_32;
	private FitEngineConfiguration config = null;
	private FitConfiguration fitConfig = null;
	private Overlay o = null;
	private ImagePlus imp = null;
	private boolean preview = false;
	private Label label = null;
	private TIntObjectHashMap<ArrayList<Coordinate>> actualCoordinates = null;
	private static double distance = 1.5;
	private static boolean showTP = true;
	private static boolean showFP = true;

	private int currentSlice = 0;
	private MaximaSpotFilter filter = null;

	// All the fields that will be updated when reloading the configuration file
	private Choice textCameraModelName;
	private Choice textPSF;
	private Choice textDataFilterType;
	private Choice textDataFilterMethod;
	private TextField textSmooth;
	private Choice textDataFilterMethod2;
	private TextField textSmooth2;
	private TextField textSearch;
	private TextField textBorder;

	private boolean refreshing = false;
	private NonBlockingExtendedGenericDialog gd;

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.filter.PlugInFilter#setup(java.lang.String, ij.ImagePlus)
	 */
	public int setup(String arg, ImagePlus imp)
	{
		SMLMUsageTracker.recordPlugin(this.getClass(), arg);

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

		return flags;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.filter.ExtendedPlugInFilter#showDialog(ij.ImagePlus, java.lang.String,
	 * ij.plugin.filter.PlugInFilterRunner)
	 */
	@SuppressWarnings("unchecked")
	public int showDialog(ImagePlus imp, String command, PlugInFilterRunner pfr)
	{
		this.o = imp.getOverlay();
		this.imp = imp;

		config = SettingsManager.readFitEngineConfiguration(0);
		fitConfig = config.getFitConfiguration();

		gd = new NonBlockingExtendedGenericDialog(TITLE);
		gd.addHelp(About.HELP_URL);
		gd.addMessage("Preview candidate maxima");

		String[] templates = ConfigurationTemplate.getTemplateNames(true);
		gd.addChoice("Template", templates, templates[0]);

		String[] models = CameraModelManager.listCameraModels(true);
		gd.addChoice("Camera_model_name", models, fitConfig.getCameraModelName());

		PeakFit.addPSFOptions(gd, this);
		gd.addChoice("Spot_filter_type", SettingsManager.getDataFilterTypeNames(),
				config.getDataFilterType().ordinal());
		gd.addChoice("Spot_filter", SettingsManager.getDataFilterMethodNames(),
				config.getDataFilterMethod(0).ordinal());
		gd.addSlider("Smoothing", 0, 2.5, config.getSmooth(0));
		gd.addChoice("Spot_filter_2", SettingsManager.getDataFilterMethodNames(),
				config.getDataFilterMethod(1, defaultDataFilterMethod).ordinal());
		gd.addSlider("Smoothing_2", 2.5, 4.5, config.getSmooth(1, defaultSmooth));
		gd.addSlider("Search_width", 0.5, 2.5, config.getSearch());
		gd.addSlider("Border", 0.5, 2.5, config.getBorder());

		// Find if this image was created with ground truth data
		if (imp.getID() == CreateData.getImageId())
		{
			MemoryPeakResults results = CreateData.getResults();
			if (results != null)
			{
				gd.addSlider("Match_distance", 0, 2.5, distance);
				gd.addCheckbox("Show TP", showTP);
				gd.addCheckbox("Show FP", showFP);
				gd.addMessage("");
				label = (Label) gd.getMessage();
				// Integer coords
				actualCoordinates = ResultsMatchCalculator.getCoordinates(results, true);
			}
		}

		if (!(IJ.isMacro() || java.awt.GraphicsEnvironment.isHeadless()))
		{
			// Listen for changes to an image
			ImagePlus.addImageListener(this);

			// Support template settings
			Vector<TextField> numerics = (Vector<TextField>) gd.getNumericFields();
			Vector<Choice> choices = (Vector<Choice>) gd.getChoices();

			int n = 0;
			int ch = 0;

			Choice textTemplate = choices.get(ch++);
			textTemplate.removeItemListener(gd);
			textTemplate.removeKeyListener(gd);
			textTemplate.addItemListener(this);

			textCameraModelName = choices.get(ch++);
			textPSF = choices.get(ch++);
			textDataFilterType = choices.get(ch++);
			textDataFilterMethod = choices.get(ch++);
			textSmooth = numerics.get(n++);
			textDataFilterMethod2 = choices.get(ch++);
			textSmooth2 = numerics.get(n++);
			textSearch = numerics.get(n++);
			textBorder = numerics.get(n++);
		}

		gd.addPreviewCheckbox(pfr);
		gd.addDialogListener(this);
		gd.setOKLabel("Save");
		gd.setCancelLabel("Close");
		gd.showDialog();

		if (!(IJ.isMacro() || java.awt.GraphicsEnvironment.isHeadless()))
			ImagePlus.removeImageListener(this);

		if (!gd.wasCanceled())
		{
			if (!SettingsManager.writeSettings(config, SettingsManager.FLAG_SILENT))
				IJ.error(TITLE, "Failed to save settings");
		}

		// Reset
		imp.setOverlay(o);

		return DONE;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.gui.DialogListener#dialogItemChanged(ij.gui.GenericDialog, java.awt.AWTEvent)
	 */
	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e)
	{
		if (refreshing)
			return false;

		gd.getNextChoice(); // Ignore template

		// Set a camera model
		fitConfig.setCameraModelName(gd.getNextChoice());
		CameraModel model = CameraModelManager.load(fitConfig.getCameraModelName());
		if (model == null)
			model = new FakePerPixelCameraModel(0, 1, 1);
		fitConfig.setCameraModel(model);

		fitConfig.setPSFType(PeakFit.getPSFTypeValues()[gd.getNextChoiceIndex()]);

		config.setDataFilterType(gd.getNextChoiceIndex());
		config.setDataFilter(gd.getNextChoiceIndex(), Math.abs(gd.getNextNumber()), false, 0);
		config.setDataFilter(gd.getNextChoiceIndex(), Math.abs(gd.getNextNumber()), false, 1);
		config.setSearch(gd.getNextNumber());
		config.setBorder(gd.getNextNumber());
		if (label != null)
		{
			distance = gd.getNextNumber();
			showTP = gd.getNextBoolean();
			showFP = gd.getNextBoolean();
		}
		preview = gd.getNextBoolean();
		boolean result = !gd.invalidNumber();
		if (!preview)
		{
			setLabel("");
			this.imp.setOverlay(o);
		}
		return result;
	}

	private void setLabel(String message)
	{
		if (label == null)
			return;
		label.setText(message);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.filter.PlugInFilter#run(ij.process.ImageProcessor)
	 */
	public void run(ImageProcessor ip)
	{
		if (refreshing)
			return;

		currentSlice = imp.getCurrentSlice();

		Rectangle bounds = ip.getRoi();

		// Set a camera model
		CameraModel cameraModel = CameraModelManager.load(fitConfig.getCameraModelName());
		if (cameraModel == null)
			cameraModel = new FakePerPixelCameraModel(0, 1, 1);
		fitConfig.setCameraModel(cameraModel);

		// Configure a jury filter
		if (config.getDataFilterType() == DataFilterType.JURY)
		{
			if (!PeakFit.configureDataFilter(config, PeakFit.FLAG_NO_SAVE))
				return;
		}

		try
		{
			filter = config.createSpotFilter(true);
		}
		catch (Exception e)
		{
			filter = null;
			this.imp.setOverlay(o);
			throw new RuntimeException(e); // Required for ImageJ to disable the preview
			//Utils.log("ERROR: " + e.getMessage());			
			//return;
		}
		Utils.log(filter.getDescription());

		// Note: 
		// Do not support origin selection when there is a width/height mismatch 
		// as this will break the live preview with multiple pop-ups. Origin 
		// could be added to the input dialog.
		//cameraModel = PeakFit.cropCameraModel(cameraModel, ip.getWidth(), ip.getHeight(), 0, 0, true);		

		// Instead just warn if the roi cannot be extracted from the selected model 
		// or there is a mismatch
		Rectangle modelBounds = cameraModel.getBounds();
		if (modelBounds != null)
		{
			if (!modelBounds.contains(bounds))
			{
			//@formatter:off
			Utils.log("WARNING: Camera model bounds [x=%d,y=%d,width=%d,height=%d] does not contain image target bounds [x=%d,y=%d,width=%d,height=%d]",
					modelBounds.x, modelBounds.y, modelBounds.width, modelBounds.height, 
					bounds.x, bounds.y, bounds.width, bounds.height 
					);
			//@formatter:on
			}
			else
			// Warn if the model bounds are mismatched than the image as this may be an incorrect
			// selection for the camera model
			if (modelBounds.x != 0 || modelBounds.y != 0 || modelBounds.width > ip.getWidth() ||
					modelBounds.height > ip.getHeight())
			{
			//@formatter:off
			Utils.log("WARNING: Camera model bounds\n[x=%d,y=%d,width=%d,height=%d]\nare mismatched from the image image target bounds\n[width=%d,height=%d].\n \nThis is probably an incorrect camera model.",
					modelBounds.x, modelBounds.y, modelBounds.width, modelBounds.height, 
					ip.getWidth(),  ip.getHeight()
					);
			//@formatter:on
			}
		}

		run(ip, filter);
	}

	private void run(ImageProcessor ip, MaximaSpotFilter filter)
	{
		if (refreshing)
			return;

		Rectangle bounds = ip.getRoi();

		// Crop to the ROI
		FloatProcessor fp = ip.crop().toFloat(0, null);

		float[] data = (float[]) fp.getPixels();

		int width = fp.getWidth();
		int height = fp.getHeight();

		// Set weights
		CameraModel cameraModel = fitConfig.getCameraModel();
		if (!(cameraModel instanceof FakePerPixelCameraModel))
		{
			float[] w = cameraModel.getWeights(bounds);
			filter.setWeights(w, width, height);
		}

		Spot[] spots = filter.rank(data, width, height);
		data = filter.getPreprocessedData();

		fp = new FloatProcessor(width, height, data);
		FloatProcessor out = new FloatProcessor(ip.getWidth(), ip.getHeight());
		out.copyBits(ip, 0, 0, Blitter.COPY);
		out.insert(fp, bounds.x, bounds.y);
		//ip.resetMinAndMax();
		double min = fp.getMin();
		double max = fp.getMax();
		out.setMinAndMax(min, max);

		Overlay o = new Overlay();
		o.add(new ImageRoi(0, 0, out));

		if (label != null)
		{
			// Get results for frame
			Coordinate[] actual = ResultsMatchCalculator.getCoordinates(actualCoordinates, imp.getCurrentSlice());

			Coordinate[] predicted = new Coordinate[spots.length];
			for (int i = 0; i < spots.length; i++)
			{
				predicted[i] = new BasePoint(spots[i].x + bounds.x, spots[i].y + bounds.y);
			}

			// Q. Should this use partial scoring with multi-matches allowed.
			// If so then this needs to be refactored out of the BenchmarkSpotFilter class.

			// TODO - compute AUC and max jaccard and plot			

			// Compute matches
			List<PointPair> matches = new ArrayList<PointPair>(Math.min(actual.length, predicted.length));
			List<Coordinate> FP = new ArrayList<Coordinate>(predicted.length);
			MatchResult result = MatchCalculator.analyseResults2D(actual, predicted,
					distance * fitConfig.getInitialPeakStdDev(), null, FP, null, matches);

			// Show scores
			setLabel(String.format("P=%s, R=%s, J=%s", Utils.rounded(result.getPrecision()),
					Utils.rounded(result.getRecall()), Utils.rounded(result.getJaccard())));

			// Create Rois for TP and FP
			if (showTP)
			{
				float[] x = new float[matches.size()];
				float[] y = new float[x.length];
				int n = 0;
				for (PointPair pair : matches)
				{
					BasePoint p = (BasePoint) pair.getPoint2();
					x[n] = p.getX() + 0.5f;
					y[n] = p.getY() + 0.5f;
					n++;
				}
				addRoi(0, o, x, y, n, Color.green);
			}
			if (showFP)
			{
				float[] x = new float[predicted.length - matches.size()];
				float[] y = new float[x.length];
				int n = 0;
				for (Coordinate c : FP)
				{
					BasePoint p = (BasePoint) c;
					x[n] = p.getX() + 0.5f;
					y[n] = p.getY() + 0.5f;
					n++;
				}
				addRoi(0, o, x, y, n, Color.red);
			}
		}
		else
		{
			float[] x = new float[spots.length];
			float[] y = new float[x.length];
			for (int i = 0; i < spots.length; i++)
			{
				x[i] = spots[i].x + bounds.x + 0.5f;
				y[i] = spots[i].y + bounds.y + 0.5f;
			}
			PointRoi roi = new PointRoi(x, y);
			// Add options to configure colour and labels
			o.add(roi);
		}

		imp.setOverlay(o);
	}

	public static void addRoi(int frame, Overlay o, float[] x, float[] y, int n, Color colour)
	{
		// Add as a circle
		addRoi(frame, o, x, y, n, colour, 3);
	}

	public static void addRoi(int frame, Overlay o, float[] x, float[] y, int n, Color colour, int pointType)
	{
		if (n == 0)
			return;
		PointRoi roi = new PointRoi(x, y, n);
		roi.setPointType(pointType);
		roi.setFillColor(colour);
		roi.setStrokeColor(colour);
		if (frame != 0)
			roi.setPosition(frame);
		o.add(roi);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.filter.ExtendedPlugInFilter#setNPasses(int)
	 */
	public void setNPasses(int nPasses)
	{
		// Nothing to do		
	}

	public void imageOpened(ImagePlus imp)
	{

	}

	public void imageClosed(ImagePlus imp)
	{
	}

	public void imageUpdated(ImagePlus imp)
	{
		if (this.imp.getID() == imp.getID() && preview)
		{
			if (imp.getCurrentSlice() != currentSlice && filter != null)
			{
				run(imp.getProcessor(), filter);
			}
		}
	}

	public void itemStateChanged(ItemEvent e)
	{
		if (e.getSource() instanceof Choice)
		{
			// Update the settings from the template
			Choice choice = (Choice) e.getSource();
			String templateName = choice.getSelectedItem();
			//System.out.println("Update to " + templateName);

			// Get the configuration template
			TemplateSettings template = ConfigurationTemplate.getTemplate(templateName);

			if (template != null)
			{
				refreshing = true;

				IJ.log("Applying template: " + templateName);

				for (String note : template.getNotesList())
					IJ.log(note);

				boolean custom = ConfigurationTemplate.isCustomTemplate(templateName);
				if (template.hasPsf())
				{
					refreshSettings(template.getPsf(), custom);
				}
				if (template.hasFitEngineSettings())
				{
					refreshSettings(template.getFitEngineSettings(), custom);
				}

				refreshing = false;
				//dialogItemChanged(gd, null);
			}
		}
	}

	private void refreshSettings(PSF psf, boolean isCustomTemplate)
	{
		if (!isCustomTemplate || psf == null)
			return;

		// Do not use set() as we support merging a partial PSF
		fitConfig.mergePSF(psf);

		textPSF.select(PSFProtosHelper.getName(fitConfig.getPSFType()));
	}

	/**
	 * Refresh settings.
	 * <p>
	 * If this is a custom template then use all the settings. If a default template then leave some existing spot
	 * settings untouched as the user may have updated them (e.g. PSF width).
	 *
	 * @param fitEngineSettings
	 *            the config
	 * @param isCustomTemplate
	 *            True if a custom template.
	 */
	private void refreshSettings(FitEngineSettings fitEngineSettings, boolean isCustomTemplate)
	{
		// Set the configuration
		// This will clear everything and merge the configuration so
		// remove the fit settings (as we do not care about those).

		this.config.setFitEngineSettings(fitEngineSettings.toBuilder().clearFitSettings().build());
		fitConfig = this.config.getFitConfiguration();

		textCameraModelName.select(fitConfig.getCameraModelName());
		textDataFilterType.select(SettingsManager.getDataFilterTypeNames()[config.getDataFilterType().ordinal()]);
		textDataFilterMethod
				.select(SettingsManager.getDataFilterMethodNames()[config.getDataFilterMethod(0).ordinal()]);
		textSmooth.setText("" + config.getSmooth(0));
		if (config.getDataFiltersCount() > 1)
		{
			textDataFilterMethod2
					.select(SettingsManager.getDataFilterMethodNames()[config.getDataFilterMethod(1).ordinal()]);
			textSmooth2.setText("" + config.getSmooth(1));
		}
		textSearch.setText("" + config.getSearch());
		textBorder.setText("" + config.getBorder());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ij.plugins.PeakFit.FitConfigurationProvider#getFitConfiguration()
	 */
	public FitConfiguration getFitConfiguration()
	{
		return fitConfig;
	}
}
