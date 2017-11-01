package gdsc.smlm.ij.plugins;

import java.awt.AWTEvent;
import java.awt.Label;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import gdsc.core.data.procedures.FloatStackTrivalueProcedure;
import gdsc.core.ij.IJTrackProgress;
import gdsc.core.ij.Utils;
import gdsc.core.logging.Ticker;
import gdsc.core.logging.TrackProgress;
import gdsc.core.math.interpolation.CustomTricubicFunction;
import gdsc.core.utils.Maths;
import gdsc.core.utils.SimpleLock;
import gdsc.core.utils.TextUtils;
import gdsc.core.utils.TurboList;
import gdsc.smlm.data.config.PSFProtos.CubicSplineResource;
import gdsc.smlm.data.config.PSFProtos.CubicSplineSettings;
import gdsc.smlm.data.config.PSFProtos.ImagePSF;
import gdsc.smlm.data.config.PSFProtos.ImagePSFOrBuilder;
import gdsc.smlm.function.StandardValueProcedure;
import gdsc.smlm.function.cspline.CubicSplineCalculator;
import gdsc.smlm.function.cspline.CubicSplineData;
import gdsc.smlm.function.cspline.CubicSplineFunction;
import gdsc.smlm.function.cspline.SingleCubicSplineFunction;
import gdsc.smlm.ij.settings.SettingsManager;
import gdsc.smlm.ij.utils.ImageConverter;
import gdsc.smlm.results.PeakResult;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.DialogListener;
import ij.gui.ExtendedGenericDialog;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingExtendedGenericDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;

/**
 * This plugin handle the save and load of per-pixel spline models
 */
public class CubicSplineManager implements PlugIn
{
	/**
	 * Contains the information used to represent a point spread function using a cubic spline
	 */
	public static class CubicSplinePSF
	{
		ImagePSF imagePSF;
		CubicSplineData splineData;

		/**
		 * Instantiates a new cubic spline PSF.
		 *
		 * @param imagePSF
		 *            the image PSF
		 * @param splineData
		 *            the spline data
		 * @throws IllegalArgumentException
		 *             If the centre is not within the function range
		 */
		public CubicSplinePSF(ImagePSF imagePSF, CubicSplineData splineData) throws IllegalArgumentException
		{
			this.imagePSF = imagePSF;
			this.splineData = splineData;
			if (
			//@formatter:off
					imagePSF.getXCentre() < 0 ||
					imagePSF.getYCentre() < 0||
					imagePSF.getZCentre() < 0 ||
					imagePSF.getXCentre() > splineData.getMaxX() ||
					imagePSF.getYCentre() > splineData.getMaxY() ||
					imagePSF.getZCentre() > splineData.getMaxZ() 
					//@formatter:on
			)
				throw new IllegalArgumentException("The centre is not within the function");
		}

		public CubicSplineFunction createCubicSplineFunction(int maxy, int maxx, int scale)
		{
			CubicSplineFunction f = new SingleCubicSplineFunction(splineData, maxx, maxy, imagePSF.getXCentre(),
					imagePSF.getYCentre(), imagePSF.getZCentre(), scale);
			return f;
		}

		public int getScale(double nmPerPixel)
		{
			return CubicSplineManager.getScale(imagePSF.getPixelSize(), nmPerPixel);
		}
	}

	private static final String TITLE = "Cubic Spline Manager";
	private static String directory = "";
	private static String filename = "";
	private static String cacheName = "";
	private static CubicSplinePSF cache = null;

	private static CubicSplineSettings.Builder settings = null;

	private static CubicSplineSettings.Builder getSettings()
	{
		return getSettings(0);
	}

	private static CubicSplineSettings.Builder getSettings(int flags)
	{
		if (settings == null)
			settings = SettingsManager.readCubicSplineSettings(flags).toBuilder();
		return settings;
	}

	/**
	 * Creates the cubic spline.
	 *
	 * @param imagePSF
	 *            the image PSF details
	 * @param image
	 *            the image
	 * @return the cubic spline PSF
	 */
	public static CubicSplinePSF createCubicSpline(ImagePSFOrBuilder imagePSF, ImageStack image,
			final boolean singlePrecision)
	{
		final int maxx = image.getWidth();
		final int maxy = image.getHeight();
		final int maxz = image.getSize();
		final float[][] psf = new float[maxz][];
		for (int z = 0; z < maxz; z++)
			psf[z] = ImageConverter.getData(image.getPixels(z + 1), maxx, maxy, null, null);

		// We reduce by a factor of 3
		final int maxi = (maxx - 1) / 3;
		final int maxj = (maxy - 1) / 3;
		final int maxk = (maxz - 1) / 3;

		int size = maxi * maxj;
		final CustomTricubicFunction[][] splines = new CustomTricubicFunction[maxk][size];
		final Ticker ticker = Ticker.create(new IJTrackProgress(), (long) maxi * maxj * maxk, true);
		ticker.start();
		ExecutorService threadPool = Executors.newFixedThreadPool(Prefs.getThreads());
		TurboList<Future<?>> futures = new TurboList<Future<?>>(maxk);
		// Create all the spline nodes by processing continuous blocks of 4x4x4 from the image stack.
		// Note that the function is enlarge x3 so a 4x4x4 block samples the voxel at [0,1/3,2/3,1]
		// in each dimension. There should be a final pixel on the end of the data for the final 
		// spline node along each dimension, i.e. dimension length = n*3 + 1 with n the number of nodes.
		for (int k = 0; k < maxk; k++)
		{
			final int kk = k;
			futures.add(threadPool.submit(new Runnable()
			{
				public void run()
				{
					CubicSplineCalculator calc = new CubicSplineCalculator();
					double[] value = new double[64];
					final int zz = 3 * kk;
					for (int j = 0, index = 0; j < maxj; j++)
					{
						// 4x4 block origin in the XY data
						int index0 = 3 * j * maxx;
						for (int i = 0; i < maxi; i++, index++)
						{
							ticker.tick();
							int c = 0;
							for (int z = 0; z < 4; z++)
							{
								final float[] data = psf[zz + z];
								for (int y = 0; y < 4; y++)
									for (int x = 0, ii = index0 + y * maxx; x < 4; x++)
										value[c++] = data[ii++];
							}
							splines[kk][index] = CustomTricubicFunction.create(calc.compute(value));
							if (singlePrecision)
								splines[kk][index] = splines[kk][index].toSinglePrecision();
							index0 += 3;
						}
					}
				}
			}));
		}
		ticker.stop();

		Utils.waitForCompletion(futures);

		threadPool.shutdownNow();

		// Normalise
		double maxSum = 0;
		for (int k = 0; k < maxk; k++)
		{
			double sum = 0;
			for (int i = 0; i < size; i++)
				sum += splines[k][i].value000();
			if (maxSum < sum)
				maxSum = sum;
		}
		if (maxSum == 0)
			throw new IllegalStateException("The cubic spline has no maximum signal");
		final double scale = 1.0 / maxSum;
		for (int k = 0; k < maxk; k++)
		{
			for (int i = 0; i < size; i++)
				splines[k][i].scale(scale);
		}

		// Create on an integer scale
		CubicSplineData f = new CubicSplineData(maxi, maxj, splines);

		// Create a new info with the PSF details
		ImagePSF.Builder b = ImagePSF.newBuilder();
		b.setImageCount(imagePSF.getImageCount());
		// Reducing the image has the effect of enlarging the pixel size
		b.setPixelSize(imagePSF.getPixelSize() * 3.0);
		b.setPixelDepth(imagePSF.getPixelDepth() * 3.0);
		// The centre has to be moved as we reduced the image size by 3.
		// In the ImagePSF the XY centre puts 0.5 at the centre of the pixel.
		// The spline puts 0,0 at the centre of each pixel for convenience.
		double cx = maxi / 2.0;
		if (imagePSF.getXCentre() != 0)
			cx = (imagePSF.getXCentre() - 0.5) / 3;
		double cy = maxj / 2.0;
		if (imagePSF.getYCentre() != 0)
			cy = (imagePSF.getYCentre() - 0.5) / 3;
		double cz = maxk / 2.0;
		if (imagePSF.getZCentre() != 0)
			cz = imagePSF.getZCentre() / 3;
		else if (imagePSF.getCentreImage() != 0)
			cz = (imagePSF.getCentreImage() - 1) / 3.0;

		b.setXCentre(cx);
		b.setYCentre(cy);
		b.setZCentre(cz);

		return new CubicSplinePSF(b.build(), f);
	}

	/**
	 * Save the spline model. The model will be named in the resources using the filename without the extension or
	 * leading path entries.
	 *
	 * @param psfModel
	 *            the spline model
	 * @param filename
	 *            the filename
	 * @return true, if successful
	 */
	public static boolean save(CubicSplinePSF psfModel, String filename)
	{
		if (psfModel == null || TextUtils.isNullOrEmpty(filename))
			return false;

		// Try to save to file
		FileOutputStream os = null;
		try
		{
			TrackProgress progress = new IJTrackProgress();
			os = new FileOutputStream(filename);

			psfModel.imagePSF.writeDelimitedTo(os);
			psfModel.splineData.write(os, progress);

			saveResource(psfModel, filename, getName(filename));

			return true;
		}
		catch (Exception e)
		{
			Utils.log("Failed to save spline model to file: %s. %s", filename, e.getMessage());
		}
		finally
		{
			if (os != null)
				try
				{
					os.close();
				}
				catch (IOException e)
				{
					// Ignore
				}
		}

		return false;
	}

	private static String getName(String filename)
	{
		File file = new File(filename);
		String name = Utils.removeExtension(file.getName());
		return name;
	}

	private static void saveResource(CubicSplinePSF psfModel, String filename, String name)
	{
		CubicSplineResource.Builder resource = CubicSplineResource.newBuilder();
		resource.setFilename(filename);
		resource.setSplineScale(psfModel.imagePSF.getPixelSize());

		CubicSplineSettings.Builder settings = getSettings();
		settings.putCubicSplineResources(name, resource.build());
		SettingsManager.writeSettings(settings.build());

		cacheName = name;
		cache = psfModel;
	}

	/**
	 * Load the spline model. Returns null if the named model does not exist. Writes to the ImageJ log if a problems
	 * occurred loading the model.
	 *
	 * @param name
	 *            the name
	 * @return the per pixel spline model (or null)
	 */
	public static CubicSplinePSF load(String name)
	{
		if (cache != null && cacheName.equals(name))
			return cache;

		CubicSplineSettings.Builder settings = getSettings();
		// Try and get the named resource
		CubicSplineResource resource = settings.getCubicSplineResourcesMap().get(name);
		if (resource == null)
			return null;
		CubicSplinePSF f = loadFromFile(name, resource.getFilename());
		if (f != null)
		{
			cache = f;
			cacheName = name;
		}
		return f;
	}

	private static CubicSplinePSF loadFromFile(String name, String filename)
	{
		// Try to load from file
		InputStream is = null;
		try
		{
			IJ.showStatus("Loading cubic spline: " + name);
			TrackProgress progress = new IJTrackProgress();
			is = new BufferedInputStream(new FileInputStream(filename));

			ImagePSF imagePSF = ImagePSF.parseDelimitedFrom(is);
			CubicSplineData function = CubicSplineData.read(is, progress);

			return new CubicSplinePSF(imagePSF, function);
		}
		catch (Exception e)
		{
			Utils.log("Failed to load spline model %s from file: %s. %s", name, filename, e.getMessage());
		}
		finally
		{
			IJ.showStatus("");
			if (is != null)
				try
				{
					is.close();
				}
				catch (IOException e)
				{
					// Ignore
				}
		}
		return null;
	}

	/**
	 * List the spline models.
	 *
	 * @param includeNone
	 *            Set to true to include an invalid none model string
	 * @return the list
	 */
	public static String[] listCubicSplines(boolean includeNone)
	{
		CubicSplineSettings.Builder settings = getSettings();
		List<String> list = createList(includeNone);
		list.addAll(settings.getCubicSplineResourcesMap().keySet());
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
	 * List the spline models with the a spline scale (pixel size) that is an integer scale of the given nm/pixel scale.
	 *
	 * @param includeNone
	 *            Set to true to include an empty string
	 * @param nmPerPixel
	 *            the nm per pixel
	 * @return the list
	 */
	public static String[] listCubicSplines(boolean includeNone, double nmPerPixel)
	{
		CubicSplineSettings.Builder settings = getSettings();
		List<String> list = createList(includeNone);
		for (Map.Entry<String, CubicSplineResource> entry : settings.getCubicSplineResourcesMap().entrySet())
		{
			CubicSplineResource resource = entry.getValue();
			int scale = getScale(resource.getSplineScale(), nmPerPixel);
			if (scale == 0)
				continue;
			list.add(entry.getKey());
		}
		return list.toArray(new String[list.size()]);
	}

	private static int getScale(double splineSize, double nmPerPixel)
	{
		if (!(splineSize > 0))
			return 0;
		double factor = nmPerPixel / splineSize;
		int i = (int) Math.round(factor);
		if (Math.abs(factor - i) < 1e-6)
			return i;
		return 0;
	}

	//@formatter:off
	private static String[] OPTIONS = { 
			"Print all model details", 
			"View a spline model", 
			"Load a spline model",
			"Load from directory",
			"Delete a spline model",
			"Render the spline function" };
	//@formatter:on
	private static int option = 0;
	private static String selected = "";
	private static int magnification = 3;
	private static int scale = 2;
	private static double xshift = 0;
	private static double yshift = 0;
	private static double zshift = 0;

	/*
	 * (non-Javadoc)
	 * 
	 * @see ij.plugin.PlugIn#run(java.lang.String)
	 */
	public void run(String arg)
	{
		SMLMUsageTracker.recordPlugin(this.getClass(), arg);

		CubicSplineSettings.Builder settings = getSettings(SettingsManager.FLAG_SILENT);
		if (settings.getCubicSplineResourcesCount() == 0)
		{
			IJ.error(TITLE, "No spline models found");
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
				renderCubicSpline();
				break;
			case 4:
				deleteCubicSpline();
				break;
			case 3:
				loadFromDirectory();
				break;
			case 2:
				loadFromFile();
				break;
			case 1:
				viewCubicSpline();
				break;
			default:
				printCubicSplines();
		}
	}

	private void renderCubicSpline()
	{
		String[] MODELS = listCubicSplines(false);
		GenericDialog gd = new GenericDialog(TITLE);
		gd.addChoice("Model", MODELS, selected);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		String name = selected = gd.getNextChoice();

		CubicSplinePSF psfModel = load(name);

		if (psfModel == null)
		{
			IJ.log("Failed to find spline data for model: " + name);
			return;
		}

		// Interactive render
		new CSoplineRenderer(psfModel).run();
	}

	private class CSoplineRenderer implements DialogListener
	{
		CubicSplinePSF psfModel;
		ImagePSF imagePSF;
		CubicSplineData function;
		double padx, pady;
		Label label;

		public CSoplineRenderer(CubicSplinePSF psfModel)
		{
			this.psfModel = psfModel;
			imagePSF = psfModel.imagePSF;
			function = psfModel.splineData;

			// Find the limits of the model. This works if the centre is within the image
			padx = Math.max(imagePSF.getXCentre(), function.getMaxX() - imagePSF.getXCentre());
			pady = Math.max(imagePSF.getYCentre(), function.getMaxY() - imagePSF.getYCentre());
		}

		public void run()
		{
			final NonBlockingExtendedGenericDialog gd = new NonBlockingExtendedGenericDialog(TITLE);
			gd.addSlider("Scale", 1, 5, scale);

			// Find the limits of the PSF
			int width = (int) Math.ceil(function.getMaxX() * imagePSF.getPixelSize());
			int height = (int) Math.ceil(function.getMaxY() * imagePSF.getPixelSize());
			int minZ = (int) Math.floor(-imagePSF.getZCentre() * imagePSF.getPixelDepth());
			int maxZ = (int) Math.ceil((function.getMaxZ() - imagePSF.getZCentre()) * imagePSF.getPixelDepth());

			gd.addSlider("x_shift (nm)", -width, width, xshift);
			final TextField tfxshift = gd.getLastTextField();
			gd.addSlider("y_shift (nm)", -height, height, yshift);
			final TextField tfyshift = gd.getLastTextField();
			gd.addSlider("z_shift (nm)", minZ, maxZ, zshift);
			final TextField tfzshift = gd.getLastTextField();
			gd.addDialogListener(this);
			if (Utils.isShowGenericDialog())
			{
				gd.addAndGetButton("Reset", new ActionListener()
				{
					public void actionPerformed(ActionEvent e)
					{
						xshift = yshift = zshift = 0;
						update();
						// The events triggered by setting these should be ignored now
						tfxshift.setText("0");
						tfyshift.setText("0");
						tfzshift.setText("0");
					}
				});
				gd.addMessage("");
				label = gd.getLastLabel();
				draw();
			}
			gd.showDialog();
		}

		public boolean dialogItemChanged(GenericDialog gd, AWTEvent e)
		{
			scale = (int) gd.getNextNumber();
			xshift = gd.getNextNumber();
			yshift = gd.getNextNumber();
			zshift = gd.getNextNumber();

			update();
			return true;
		}

		private int _scale = -1;
		private double _xshift = 0;
		private double _yshift = 0;
		private double _zshift = 0;
		CubicSplineFunction f = null;
		double[] a = new double[] { 0, 1, 0, 0, 0 }; // Intensity 1

		private void draw()
		{
			_scale = scale;
			_xshift = xshift;
			_yshift = yshift;
			_zshift = zshift;

			boolean updateCalibration = false;
			if (f == null || f.getScale() != _scale)
			{
				updateCalibration = true;
				int padX = (int) Math.ceil(padx / _scale);
				int padY = (int) Math.ceil(pady / _scale);

				// Create a function
				int rangeX = 1 + 2 * padX;
				int rangeY = 1 + 2 * padY;
				f = psfModel.createCubicSplineFunction(rangeX, rangeY, _scale);
				a[PeakResult.X] = padX;
				a[PeakResult.Y] = padY;
			}

			// Render
			StandardValueProcedure p = new StandardValueProcedure();

			// Put the spot in the centre of the image
			double[] a = this.a.clone();

			// Adjust the centre
			double nmPerPixel = imagePSF.getPixelSize() * _scale;
			double nmPerSlice = imagePSF.getPixelDepth() * _scale;

			a[PeakResult.X] += _xshift / nmPerPixel;
			a[PeakResult.Y] += _yshift / nmPerPixel;
			a[PeakResult.Z] += _zshift / nmPerSlice;

			double[] values = p.getValues(f, a);

			ImagePlus imp = Utils.display(selected + " (slice)", values, f.getMaxX(), f.getMaxY(), Utils.NO_TO_FRONT);
			if (Utils.isNewWindow())
			{
				updateCalibration = true;
				imp.getWindow().toFront();
			}

			if (updateCalibration)
			{
				Calibration c = imp.getLocalCalibration();
				c.setUnit("nm");
				c.pixelWidth = c.pixelHeight = nmPerPixel;
				c.pixelDepth = nmPerSlice;
			}

			if (label != null)
				label.setText("Intensity = " + Utils.rounded(Maths.sum(values)));
		}

		SimpleLock lock = new SimpleLock();

		private void update()
		{
			if (lock.acquire())
			{
				// Run in a new thread to allow the GUI to continue updating
				new Thread(new Runnable()
				{
					public void run()
					{
						try
						{
							// Continue while the parameter is changing
							//@formatter:off
							while (
									_scale != scale ||
									_xshift != xshift ||
									_yshift != yshift ||
									_zshift != zshift
									)
							//@formatter:on
							{
								draw();
							}
						}
						finally
						{
							// Ensure the running flag is reset
							lock.release();
						}
					}
				}).start();
			}
		}
	}

	private void deleteCubicSpline()
	{
		GenericDialog gd = new GenericDialog(TITLE);
		String[] MODELS = listCubicSplines(false);
		gd.addChoice("Model", MODELS, selected);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		String name = selected = gd.getNextChoice();

		CubicSplineResource resource = settings.getCubicSplineResourcesMap().get(name);
		if (resource == null)
		{
			IJ.log("Failed to find spline data for model: " + name);
			return;
		}

		settings.removeCubicSplineResources(name);
		SettingsManager.writeSettings(settings.build());

		Utils.log("Deleted spline model: %s\n%s", name, resource);
	}

	private void loadFromDirectory()
	{
		ExtendedGenericDialog egd = new ExtendedGenericDialog(TITLE);
		egd.addMessage("Load spline models from a directory.");
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
		egd.addMessage("Load a spline model from file.");
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
		CubicSplinePSF model = loadFromFile(name, filename);

		if (model != null)
			saveResource(model, filename, name);
	}

	private void viewCubicSpline()
	{
		GenericDialog gd = new GenericDialog(TITLE);
		String[] MODELS = listCubicSplines(false);
		gd.addChoice("Model", MODELS, selected);
		gd.addSlider("Magnification", 1, 5, magnification);
		gd.showDialog();
		if (gd.wasCanceled())
			return;
		String name = selected = gd.getNextChoice();
		magnification = (int) gd.getNextNumber();

		CubicSplinePSF psfModel = load(name);

		if (psfModel == null)
		{
			IJ.log("Failed to find spline data for model: " + name);
			return;
		}

		IJ.showStatus("Drawing cubic spline");
		FloatStackTrivalueProcedure p = new FloatStackTrivalueProcedure();
		psfModel.splineData.sample(magnification, p, new IJTrackProgress());

		ImageStack stack = new ImageStack(p.x.length, p.y.length);
		for (float[] pixels : p.value)
			stack.addSlice(null, pixels);

		ImagePlus imp = Utils.display(name + " (upsampled)", stack);
		Calibration c = imp.getLocalCalibration();
		c.setUnit("nm");
		c.pixelWidth = c.pixelHeight = psfModel.imagePSF.getPixelSize() * magnification;
		c.pixelDepth = psfModel.imagePSF.getPixelDepth() * magnification;

		int centre = 1 + (int) Math.round(psfModel.imagePSF.getZCentre() * magnification);
		imp.setSlice(centre);
		imp.resetDisplayRange();
		imp.updateAndDraw();

		IJ.showStatus("");
	}

	private void printCubicSplines()
	{
		IJ.log(settings.toString());
	}
}