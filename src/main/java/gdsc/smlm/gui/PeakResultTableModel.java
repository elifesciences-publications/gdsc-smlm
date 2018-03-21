package gdsc.smlm.gui;

import java.util.Arrays;

import javax.swing.event.TableModelEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import gdsc.core.data.utils.ConversionException;
import gdsc.core.data.utils.Converter;
import gdsc.core.data.utils.Rounder;
import gdsc.core.data.utils.RounderFactory;
import gdsc.core.utils.TextUtils;
import gdsc.core.utils.TurboList;
import gdsc.smlm.data.config.CalibrationProtos.Calibration;
import gdsc.smlm.data.config.ConfigurationException;
import gdsc.smlm.data.config.PSFProtos.PSF;

/*----------------------------------------------------------------------------- 
 * GDSC SMLM Software
 * 
 * Copyright (C) 2018 Alex Herbert
 * Genome Damage and Stability Centre
 * University of Sussex, UK
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *---------------------------------------------------------------------------*/

import gdsc.smlm.data.config.ResultsProtos.ResultsTableSettings;
import gdsc.smlm.data.config.ResultsProtosHelper;
import gdsc.smlm.results.ArrayPeakResultStore;
import gdsc.smlm.results.Gaussian2DPeakResultCalculator;
import gdsc.smlm.results.Gaussian2DPeakResultHelper;
import gdsc.smlm.results.PeakResult;
import gdsc.smlm.results.PeakResultConversionHelper;
import gdsc.smlm.results.PeakResultData;
import gdsc.smlm.results.PeakResultStoreList;
import gdsc.smlm.results.data.PeakResultDataEndFrame;
import gdsc.smlm.results.data.PeakResultDataError;
import gdsc.smlm.results.data.PeakResultDataFloat;
import gdsc.smlm.results.data.PeakResultDataFrame;
import gdsc.smlm.results.data.PeakResultDataId;
import gdsc.smlm.results.data.PeakResultDataOrigValue;
import gdsc.smlm.results.data.PeakResultDataOrigX;
import gdsc.smlm.results.data.PeakResultDataOrigY;
import gdsc.smlm.results.data.PeakResultDataParameterConverter;
import gdsc.smlm.results.data.PeakResultDataParameterDeviationConverter;
import gdsc.smlm.results.data.PeakResultDataPrecision;
import gdsc.smlm.results.data.PeakResultDataSNR;

/**
 * Stores peak results and allows event propagation to listeners of the model.
 */
public class PeakResultTableModel extends AbstractTableModel
{
	private static final long serialVersionUID = 3600737169000976938L;

	/** Identifies the cell renderer has changed. */
	static final int RENDERER = -99;

	final PeakResultStoreList data;
	final Calibration calibration;
	final PSF psf;
	private ResultsTableSettings tableSettings;
	private boolean checkForDuplicates = false;

	// Used for the columns 
	private Rounder rounder;
	private PeakResultData<?>[] values;
	private String[] names;

	/**
	 * Instantiates a new peak result model using the store.
	 * <p>
	 * The default store implementation is a set to avoid duplicate entries in the model.
	 *
	 * @param results
	 *            the results
	 * @param calibration
	 *            the calibration
	 * @param psf
	 *            the psf
	 * @param tableSettings
	 *            the table settings
	 */
	public PeakResultTableModel(PeakResultStoreList results, Calibration calibration, PSF psf,
			ResultsTableSettings tableSettings)
	{
		if (results == null)
			results = new ArrayPeakResultStore(10);
		if (calibration == null)
			calibration = Calibration.getDefaultInstance();
		if (psf == null)
			psf = PSF.getDefaultInstance();
		this.data = results;
		this.calibration = calibration;
		this.psf = psf;
		setTableSettings(tableSettings);
	}

	/**
	 * Convert the model to an array.
	 *
	 * @return the peak result array
	 */
	public PeakResult[] toArray()
	{
		return data.toArray();
	}

	public Calibration getCalibration()
	{
		return calibration;
	}

	public PSF getPSF()
	{
		return psf;
	}

	public ResultsTableSettings getTableSettings()
	{
		return tableSettings;
	}

	/**
	 * Sets the table settings.
	 *
	 * @param tableSettings
	 *            the new table settings
	 */
	public void setTableSettings(ResultsTableSettings tableSettings)
	{
		if (tableSettings == null)
			tableSettings = ResultsProtosHelper.defaultResultsSettings.getResultsTableSettings();
		if (tableSettings.equals(this.tableSettings))
			return;
		this.tableSettings = tableSettings;

		rounder = RounderFactory.create(tableSettings.getRoundingPrecision());

		// Create the converters
		PeakResultConversionHelper helper = new PeakResultConversionHelper(calibration, psf);
		helper.setIntensityUnit(tableSettings.getIntensityUnit());
		helper.setDistanceUnit(tableSettings.getDistanceUnit());
		helper.setAngleUnit(tableSettings.getAngleUnit());
		final Converter[] converters = helper.getConverters();
		final Converter ic = converters[PeakResult.INTENSITY];

		String[] paramNames = helper.getNames();
		String[] unitNames = helper.getUnitNames();

		//Calibration tableCalibration = (helper.isCalibrationChanged()) ? helper.getCalibration() : calibration;

		// Organise the data columns.
		// This is done as per the IJTablePeakResults for consistency
		TurboList<PeakResultData<?>> valuesList = new TurboList<PeakResultData<?>>();
		TurboList<String> namesList = new TurboList<String>();

		valuesList.add(new PeakResultDataFrame());
		addName(valuesList, namesList);
		// XXX - Configure this
		valuesList.add(new PeakResultDataEndFrame());
		addName(valuesList, namesList);
		// XXX - Configure this
		valuesList.add(new PeakResultDataId());
		addName(valuesList, namesList);
		if (tableSettings.getShowFittingData())
		{
			valuesList.add(new PeakResultDataOrigX());
			addName(valuesList, namesList);
			valuesList.add(new PeakResultDataOrigY());
			addName(valuesList, namesList);
			valuesList.add(new PeakResultDataOrigValue());
			addName(valuesList, namesList);
			valuesList.add(new PeakResultDataError());
			addName(valuesList, namesList);
		}
		if (tableSettings.getShowNoiseData())
		{
			// Must be converted
			valuesList.add(new PeakResultDataFloat()
			{
				public Float getValue(PeakResult result)
				{
					return ic.convert(result.getNoise());
				}
			});
			addName("Noise", namesList, unitNames[PeakResult.INTENSITY]);
			valuesList.add(new PeakResultDataSNR());
			addName(valuesList, namesList);
		}

		// XXX - Configure this
		boolean showDeviations = false;
		for (int i = 0; i < PeakResult.STANDARD_PARAMETERS; i++)
		{
			// Must be converted
			valuesList.add(new PeakResultDataParameterConverter(converters[i], i));
			addName(paramNames[i], namesList, unitNames[i]);
			if (showDeviations)
			{
				valuesList.add(new PeakResultDataParameterDeviationConverter(converters[i], i));
				namesList.add("+/-");
			}
		}

		if (tableSettings.getShowPrecision())
		{
			PeakResultDataPrecision p = null;

			try
			{
				final Gaussian2DPeakResultCalculator calculator = Gaussian2DPeakResultHelper.create(getPSF(),
						calibration, Gaussian2DPeakResultHelper.LSE_PRECISION);
				p = new PeakResultDataPrecision()
				{
					@Override
					public Double getValue(PeakResult result)
					{
						if (result.hasPrecision())
							return result.getPrecision();
						if (calculator != null)
							return calculator.getLSEPrecision(result.getParameters(), result.getNoise());
						return 0.0;
					}
				};
			}
			catch (ConfigurationException e)
			{
			}
			catch (ConversionException e)
			{
			}
			if (p == null)
				p = new PeakResultDataPrecision();
			valuesList.add(p);
			namesList.add("Precision (nm)");
		}

		values = valuesList.toArray(new PeakResultData<?>[valuesList.size()]);
		names = namesList.toArray(new String[namesList.size()]);

		fireTableStructureChanged();
	}

	private static void addName(TurboList<PeakResultData<?>> valuesList, TurboList<String> namesList)
	{
		namesList.add(valuesList.get(valuesList.size() - 1).getValueName());
	}

	private static void addName(String name, TurboList<String> namesList, String unitName)
	{
		if (!TextUtils.isNullOrEmpty(unitName))
			name += " (" + unitName + ")";
		namesList.add(name);
	}

	/**
	 * Sets the rounding precision.
	 *
	 * @param roundingPrecision
	 *            the new rounding precision
	 */
	public void setRoundingPrecision(int roundingPrecision)
	{
		rounder = RounderFactory.create(roundingPrecision);
		fireTableChanged(new TableModelEvent(this, 0, 0, 0, RENDERER));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.swing.table.TableModel#getRowCount()
	 */
	public int getRowCount()
	{
		return data.size();
	}

	@Override
	public String getColumnName(int column)
	{
		return names[column]; // values[column].getValueName();
	}

	@Override
	public Class<?> getColumnClass(int columnIndex)
	{
		return values[columnIndex].getValueClass();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.swing.table.TableModel#getColumnCount()
	 */
	public int getColumnCount()
	{
		return values.length;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.swing.table.TableModel#getValueAt(int, int)
	 */
	public Object getValueAt(int rowIndex, int columnIndex)
	{
		PeakResult r = data.get(rowIndex);
		return values[columnIndex].getValue(r);
	}

	/**
	 * Adds the results. Duplicates can be avoided using the check for duplicates property.
	 * 
	 * @param source
	 *            the source
	 * @param peakResults
	 *            the peak results
	 * @see {@link #isCheckDuplicates()}
	 */
	public void add(Object source, PeakResult... peakResults)
	{
		if (peakResults.length == 0)
			return;
		int index0 = data.size();
		if (checkForDuplicates)
		{
			int size = 0;
			for (int i = 0; i < peakResults.length; i++)
			{
				if (!data.contains(peakResults[i]))
					peakResults[size++] = peakResults[i];
			}
			if (size == 0)
				return;
			if (size != peakResults.length)
				peakResults = Arrays.copyOf(peakResults, size);
		}
		data.addArray(peakResults);
		int index1 = data.size() - 1;

		fireTableRowsInserted(index0, index1);
	}

	/**
	 * Removes the result
	 *
	 * @param source
	 *            the source
	 * @param peakResult
	 *            the peak result
	 */
	public void remove(Object source, PeakResult peakResult)
	{
		remove(data.indexOf(peakResult));
	}

	/**
	 * Removes the result
	 *
	 * @param source
	 *            the source
	 * @param peakResult
	 *            the peak result
	 */
	public void remove(Object source, int index)
	{
		if (index < 0 || index >= data.size())
			return;
		data.remove(index);
		fireTableRowsDeleted(index, index);
	}

	/**
	 * Removes the results. This is a convenience method that calls remove for each result.
	 *
	 * @param source
	 *            the source
	 * @param peakResults
	 *            the peak results
	 */
	public void remove(Object source, PeakResult... peakResults)
	{
		if (peakResults.length == 0)
			return;
		for (int i = 0; i < peakResults.length; i++)
		{
			remove(peakResults[i]);
		}
	}

	/**
	 * Clear the results.
	 */
	public void clear()
	{
		int index1 = data.size() - 1;
		if (index1 >= 0)
		{
			data.clear();
			fireTableRowsDeleted(0, index1);
		}
	}

	/**
	 * Deletes the components at the specified range of indexes.
	 * The removal is inclusive, so specifying a range of (1,5)
	 * removes the component at index 1 and the component at index 5,
	 * as well as all components in between.
	 * <p>
	 * Throws an <code>ArrayIndexOutOfBoundsException</code>
	 * if the index was invalid.
	 *
	 * @param fromIndex
	 *            the index of the lower end of the range
	 * @param toIndex
	 *            the index of the upper end of the range
	 */
	public void removeRange(int fromIndex, int toIndex)
	{
		data.remove(fromIndex, toIndex);
		fireTableRowsDeleted(fromIndex, toIndex);
	}

	/**
	 * If true then all results will be checked against the current contents before
	 * allowing an addition.
	 *
	 * @return true, if checking duplicates
	 */
	public boolean isCheckDuplicates()
	{
		return checkForDuplicates;
	}

	/**
	 * Sets the check duplicates flag. If true then all results will be checked against the current contents before
	 * allowing an addition.
	 *
	 * @param checkForDuplicates
	 *            the new check duplicates flag
	 */
	public void setCheckDuplicates(boolean checkForDuplicates)
	{
		this.checkForDuplicates = checkForDuplicates;
	}

	/**
	 * Gets the float renderer.
	 *
	 * @return the float renderer
	 */
	TableCellRenderer getFloatRenderer()
	{
		return createTableCellRenderer(new DefaultTableCellRenderer()
		{
			private static final long serialVersionUID = 6315482677057619115L;

			@Override
			protected void setValue(Object value)
			{
				setText((value == null) ? "" : rounder.toString((Float) value));
			}
		});
	}

	/**
	 * Gets the double renderer.
	 *
	 * @return the double renderer
	 */
	TableCellRenderer getDoubleRenderer()
	{
		return createTableCellRenderer(new DefaultTableCellRenderer()
		{
			private static final long serialVersionUID = 476334804032977020L;

			@Override
			protected void setValue(Object value)
			{
				setText((value == null) ? "" : rounder.toString((Double) value));
			}
		});
	}

	/**
	 * Gets the double renderer.
	 *
	 * @return the double renderer
	 */
	TableCellRenderer getIntegerRenderer()
	{
		return createTableCellRenderer(new DefaultTableCellRenderer()
		{
			private static final long serialVersionUID = 9099181306853421196L;
		});
	}

	private TableCellRenderer createTableCellRenderer(DefaultTableCellRenderer renderer)
	{
		renderer.setHorizontalAlignment(DefaultTableCellRenderer.TRAILING);
		return renderer;
	}
}
