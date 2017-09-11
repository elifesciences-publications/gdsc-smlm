package gdsc.smlm.ij.plugins;

import java.awt.Rectangle;
import java.io.File;
import java.io.FileFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gdsc.core.ij.Utils;
import gdsc.core.utils.Statistics;
import gdsc.core.utils.TextUtils;
import gdsc.core.utils.TurboList;
import gdsc.smlm.data.config.CalibrationProtos.CameraModelResource;
import gdsc.smlm.data.config.CalibrationProtos.CameraModelSettings;
import gdsc.smlm.ij.IJImageSource;
import gdsc.smlm.ij.settings.SettingsManager;
import gdsc.smlm.model.camera.CameraModel;
import gdsc.smlm.model.camera.PerPixelCameraModel;
import gdsc.smlm.results.ImageSource;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.ExtendedGenericDialog;
import ij.gui.GenericDialog;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * This plugin handle the save and load of per-pixel camera models
 */
public class CameraModelManager implements PlugIn
{
	private static final String TITLE = "Camera Model Manager";
	private static final String INFO_TAG = "Per-pixel camera model data";
	private static String directory = "";
	private static String filename = "";

	private static CameraModelSettings.Builder settings = null;
	// Cache camera models for speed
	private static LinkedHashMap<String, PerPixelCameraModel> map = new LinkedHashMap<String, PerPixelCameraModel>();

	private static CameraModelSettings.Builder getSettings()
	{
		return getSettings(0);
	}

	private static CameraModelSettings.Builder getSettings(int flags)
	{
		if (settings == null)
			settings = SettingsManager.readCameraModelSettings(flags).toBuilder();
		return settings;
	}

	/**
	 * Save the camera model. The model will be named in the resources using the filename without the extension or
	 * leading path entries.
	 *
	 * @param cameraModel
	 *            the camera model
	 * @param filename
	 *            the filename
	 * @return true, if successful
	 */
	public static boolean save(PerPixelCameraModel cameraModel, String filename)
	{
		if (cameraModel == null || TextUtils.isNullOrEmpty(filename))
			return false;

		// Try to save to file
		//filename = Utils.replaceExtension(filename, ".tif");
		String name = getName(filename);

		ImageStack stack = new ImageStack(cameraModel.getWidth(), cameraModel.getHeight());
		stack.addSlice("Bias", cameraModel.getBias());
		stack.addSlice("Gain", cameraModel.getGain());
		stack.addSlice("Variance", cameraModel.getVariance());
		ImagePlus imp = new ImagePlus(name, stack);
		imp.setIgnoreGlobalCalibration(true);
		Calibration cal = imp.getCalibration();
		cal.xOrigin = cameraModel.getXOrigin();
		cal.yOrigin = cameraModel.getYOrigin();
		imp.setProperty("Info", INFO_TAG);
		// Do this to allow the filename to be something other than .tif
		boolean ok = new FileSaver(imp).saveAsTiffStack(filename);

		if (ok)
			saveResource(cameraModel, filename, name);

		return ok;
	}

	private static String getName(String filename)
	{
		File file = new File(filename);
		String name = Utils.removeExtension(file.getName());
		return name;
	}

	private static void saveResource(PerPixelCameraModel cameraModel, String filename, String name)
	{
		CameraModelResource.Builder resource = CameraModelResource.newBuilder();
		resource.setX(cameraModel.getXOrigin());
		resource.setY(cameraModel.getYOrigin());
		resource.setWidth(cameraModel.getWidth());
		resource.setHeight(cameraModel.getHeight());
		resource.setFilename(filename);

		CameraModelSettings.Builder settings = getSettings();
		settings.putCameraModelResources(name, resource.build());
		SettingsManager.writeSettings(settings.build());

		// Cache this
		map.put(name, cameraModel);
	}

	/**
	 * Load the camera model. Returns null if the named model does not exist. Writes to the ImageJ log if a problems
	 * occurred loading the model.
	 *
	 * @param name
	 *            the name
	 * @return the per pixel camera model (or null)
	 */
	public static PerPixelCameraModel load(String name)
	{
		PerPixelCameraModel model = map.get(name);
		if (model == null)
		{
			CameraModelSettings.Builder settings = getSettings();
			// Try and get the named resource
			CameraModelResource resource = settings.getCameraModelResourcesMap().get(name);
			if (resource == null)
				return null;
			model = loadFromFile(name, resource.getFilename());

			// Cache this
			map.put(name, model);
		}
		return model;
	}

	private static PerPixelCameraModel loadFromFile(String name, String filename)
	{
		// Try and load the resource
		ImagePlus imp = IJ.openImage(filename);
		IJ.showStatus(""); // Remove the status from the ij.io.ImageWriter class

		if (imp == null)
		{
			Utils.log("Failed to load camera model %s data from file: ", name, filename);
			return null;
		}
		// Check stack size
		ImageStack stack = imp.getImageStack();
		if (stack.getSize() != 3)
		{
			Utils.log("Camera model %s requires 3 image stack from file: %s", name, filename);
			return null;
		}
		// Get the origin
		imp.setIgnoreGlobalCalibration(true);
		Calibration cal = imp.getCalibration();
		Rectangle bounds = new Rectangle((int) cal.xOrigin, (int) cal.yOrigin, stack.getWidth(), stack.getHeight());
		try
		{
			float[] bias = (float[]) stack.getPixels(1);
			float[] gain = (float[]) stack.getPixels(2);
			float[] variance = (float[]) stack.getPixels(3);
			return PerPixelCameraModel.create(bounds, bias, gain, variance);
		}
		catch (Exception e)
		{
			Utils.log("Failed to load camera model %s from file: %s. %s", name, filename, e.getMessage());
		}
		return null;
	}

	/**
	 * List the camera models.
	 *
	 * @param includeNone
	 *            Set to true to include an invalid none model string
	 * @return the list
	 */
	public static String[] listCameraModels(boolean includeNone)
	{
		CameraModelSettings.Builder settings = getSettings();
		List<String> list = createList(includeNone);
		list.addAll(settings.getCameraModelResourcesMap().keySet());
		return list.toArray(new String[list.size()]);
	}

	private static List<String> createList(boolean includeNone)
	{
		List<String> list = new TurboList<String>();
		if (includeNone)
			list.add("[None]");
		return list;
	}

	/**
	 * List the camera models with the correct dimensions.
	 *
	 * @param includeNone
	 *            Set to true to include an empty string
	 * @param width
	 *            the width
	 * @param height
	 *            the height
	 * @return the list
	 */
	public static String[] listCameraModels(boolean includeNone, int width, int height)
	{
		CameraModelSettings.Builder settings = getSettings();
		List<String> list = createList(includeNone);
		for (Map.Entry<String, CameraModelResource> entry : settings.getCameraModelResourcesMap().entrySet())
		{
			CameraModelResource resource = entry.getValue();
			if (resource.getWidth() == width && resource.getHeight() == height)
				list.add(entry.getKey());
		}
		return list.toArray(new String[list.size()]);
	}

	//@formatter:off
	private static String[] OPTIONS = { 
			"Print all model details", 
			"View a camera model", 
			"Load a camera model",
			"Load from directory",
			"Delete a camera model",
			"Filter an image" };
	//@formatter:on
	private static int option = 0;
	private static int ox = 0;
	private static int oy = 0;
	private static String image = "";
	private static String selected = "";

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	public void run(String arg)
	{
		SMLMUsageTracker.recordPlugin(this.getClass(), arg);

		CameraModelSettings.Builder settings = getSettings(SettingsManager.FLAG_SILENT);
		if (settings.getCameraModelResourcesCount() == 0)
		{
			IJ.error(TITLE, "No camera models found");
			return;
		}

		GenericDialog gd = new GenericDialog(TITLE);
		gd.addChoice("Option", OPTIONS, OPTIONS[option]);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		option = gd.getNextChoiceIndex();

		switch (option)
		{
			case 5:
				filterImage();
				break;
			case 4:
				deleteCameraModel();
				break;
			case 3:
				loadFromDirectory();
				break;
			case 2:
				loadFromFile();
				break;
			case 1:
				viewCameraModel();
				break;
			default:
				printCameraModels();
		}
	}

	private void filterImage()
	{
		// Select an image
		GenericDialog gd = new GenericDialog(TITLE);
		String[] list = Utils.getImageList(Utils.GREY_SCALE);
		gd.addChoice("Image", list, image);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		image = gd.getNextChoice();
		ImagePlus imp = WindowManager.getImage(image);
		if (imp == null)
		{
			IJ.log("Failed to find image: " + image);
			return;
		}

		// Select the model
		gd = new GenericDialog(TITLE);
		String[] MODELS = listCameraModels(false);
		gd.addChoice("Model", MODELS, selected);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		String name = selected = gd.getNextChoice();

		CameraModel cameraModel = load(name);

		if (cameraModel == null)
		{
			IJ.log("Failed to find camera data for model: " + name);
			return;
		}

		// Crop the model if appropriate
		cameraModel = PeakFit.cropCameraModel(cameraModel, imp.getWidth(), imp.getHeight(), ox, oy, true);

		// Filter all the frames
		ImageSource source = new IJImageSource(imp);
		ImageStack stack = new ImageStack(imp.getWidth(), imp.getHeight());
		for (float[] data = source.next(); data != null; data = source.next())
		{
			cameraModel.removeBiasAndGain(data);
			stack.addSlice(null, data);
		}

		ImagePlus imp2 = new ImagePlus(imp.getTitle() + " Filtered", stack);
		imp2.copyScale(imp);
		imp2.show();
	}

	private void deleteCameraModel()
	{
		GenericDialog gd = new GenericDialog(TITLE);
		String[] MODELS = listCameraModels(false);
		gd.addChoice("Model", MODELS, selected);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		String name = selected = gd.getNextChoice();

		CameraModelResource resource = settings.getCameraModelResourcesMap().get(name);
		if (resource == null)
		{
			IJ.log("Failed to find camera data for model: " + name);
			return;
		}

		settings.removeCameraModelResources(name);
		SettingsManager.writeSettings(settings.build());

		Utils.log("Deleted camera model: %s\n%s", name, resource);
	}

	private void loadFromDirectory()
	{
		ExtendedGenericDialog egd = new ExtendedGenericDialog(TITLE);
		egd.addMessage("Load camera models from a directory.");
		egd.addFilenameField("Directory", directory);
		egd.showDialog();
		if (egd.wasCanceled())
			return;

		directory = egd.getNextString();

		File[] fileList = (new File(directory)).listFiles(new FileFilter()
		{
			public boolean accept(File pathname)
			{
				return pathname.isFile();
			}
		});

		for (File file : fileList)
		{
			loadFromFileAndSaveResource(file.getPath());
		}
	}

	private void loadFromFile()
	{
		ExtendedGenericDialog egd = new ExtendedGenericDialog(TITLE);
		egd.addMessage("Load a camera model from file.");
		egd.addFilenameField("Filename", filename);
		egd.showDialog();
		if (egd.wasCanceled())
			return;

		filename = egd.getNextString();

		loadFromFileAndSaveResource(filename);
	}

	private static void loadFromFileAndSaveResource(String filename)
	{
		String name = getName(filename);
		PerPixelCameraModel model = loadFromFile(name, filename);

		if (model != null)
			saveResource(model, filename, name);
	}

	private void viewCameraModel()
	{
		GenericDialog gd = new GenericDialog(TITLE);
		String[] MODELS = listCameraModels(false);
		gd.addChoice("Model", MODELS, selected);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		String name = selected = gd.getNextChoice();

		// Try and get the named resource
		CameraModelResource resource = settings.getCameraModelResourcesMap().get(name);
		if (resource == null)
		{
			IJ.log("Failed to find camera data for model: " + name);
			return;
		}
		// Try and load the resource. 
		// Do not use loadFromFile as that validates the model data. We just want 
		// to view the raw image.
		ImagePlus imp = IJ.openImage(resource.getFilename());
		IJ.showStatus(""); // Remove the status from the ij.io.ImageWriter class
		if (imp == null)
		{
			IJ.log("Failed to load camera data for model: " + name);
			return;
		}
		Utils.log("Camera model: %s\n%s", name, resource);
		ImageStack stack = imp.getImageStack();
		for (int n = 1; n <= stack.getSize(); n++)
			logStats(stack.getSliceLabel(n), stack.getProcessor(n));
		imp.show();
	}

	private void logStats(String name, ImageProcessor ip)
	{
		Statistics stats = new Statistics();
		if (ip instanceof FloatProcessor)
			stats.add((float[]) ip.getPixels());
		else
		{
			for (int i = ip.getPixelCount(); i-- > 0;)
				stats.add(ip.getf(i));
		}
		Utils.log("%s : %s += %s", name, Utils.rounded(stats.getMean()), Utils.rounded(stats.getStandardDeviation()));
	}

	private void printCameraModels()
	{
		IJ.log(settings.toString());
	}
}
