package gdsc.smlm.fitting;

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

/**
 * Specifies the fitting configuration for Gaussian 2D fitting.
 */
public interface Gaussian2DFitConfiguration
{
	/**
	 * Creates the appropriate stopping criteria and Gaussian function for the configuration.
	 *
	 * @param npeaks
	 *            The number of peaks to fit
	 * @param maxx
	 *            The height of the XY data
	 * @param maxy
	 *            the maxy
	 * @param params
	 *            The Gaussian parameters
	 */
	public void initialise(int npeaks, int maxx, int maxy, double[] params);

	/**
	 * True if the fit residuals should be computed
	 *
	 * @return true if the fit residuals should be computed
	 */
	public boolean isComputeResiduals();

	/**
	 * Gets the function solver.
	 *
	 * @return the function solver
	 */
	public FunctionSolver getFunctionSolver();

	/**
	 * True if the fit parameter deviations should be computed
	 *
	 * @return true if the fit parameter deviations should be computed
	 */
	public boolean isComputeDeviations();

	/**
	 * Checks if is background fitting.
	 *
	 * @return true, if is background fitting
	 */
	public boolean isBackgroundFitting();

	/**
	 * Checks if is XSD fitting.
	 *
	 * @return true, if is XSD fitting
	 */
	public boolean isXSDFitting();

	/**
	 * Checks if is YSD fitting.
	 *
	 * @return true, if is YSD fitting
	 */
	public boolean isYSDFitting();

	/**
	 * Checks if is angle fitting.
	 *
	 * @return true, if is angle fitting
	 */
	public boolean isAngleFitting();

	/**
	 * Checks if is z fitting.
	 *
	 * @return true, if is z fitting
	 */
	public boolean isZFitting();

	/**
	 * Gets the initial guess for the XSD parameter.
	 *
	 * @return the initial XSD
	 */
	public double getInitialXSD();

	/**
	 * Gets the initial guess for the YSD parameter.
	 *
	 * @return the initial YSD
	 */
	public double getInitialYSD();

	/**
	 * Gets the initial guess for the angle parameter.
	 *
	 * @return the initial angle
	 */
	public double getInitialAngle();

	/**
	 * Gets the minimum width factor. This is used to limit the bounds of width fitting.
	 *
	 * @return the minimum width factor
	 */
	public double getMinWidthFactor();

	/**
	 * Gets the maximum width factor. This is used to limit the bounds of width fitting.
	 *
	 * @return the maximum width factor
	 */
	public double getMaxWidthFactor();

	/**
	 * Checks if fit validation should be performed using validateFit.
	 *
	 * @return true, if fit validation should be performed
	 */
	public boolean isFitValidation();

	/**
	 * Check peaks to see if the fit was sensible. This is called after fitting so that the parameters can be checked.
	 *
	 * @param nPeaks
	 *            The number of peaks
	 * @param initialParams
	 *            The initial peak parameters
	 * @param params
	 *            The fitted peak parameters
	 * @return the fit status
	 */
	public FitStatus validateFit(int nPeaks, double[] initialParams, double[] params);

	/**
	 * Gets the validation data. This can be set within the validateFit function.
	 *
	 * @return Data associated with the validation result
	 */
	public Object getValidationData();
}