package gdsc.smlm.ij.ij3d;

import java.util.Arrays;
import java.util.List;

import org.scijava.java3d.Appearance;
import org.scijava.java3d.Geometry;
import org.scijava.java3d.GeometryArray;
import org.scijava.java3d.GeometryUpdater;
import org.scijava.java3d.PointArray;
import org.scijava.java3d.PointAttributes;
import org.scijava.vecmath.Color3f;
import org.scijava.vecmath.Point3f;

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

import customnode.CustomPointMesh;

/**
 * Create an object to represent a set of points
 */
public class ItemPointMesh extends CustomPointMesh implements UpdateableItemMesh
{
	/**
	 * Instantiates a new item point mesh.
	 *
	 * @param mesh
	 *            the mesh
	 */
	public ItemPointMesh(final List<Point3f> mesh)
	{
		super(mesh);
	}

	/**
	 * Instantiates a new item point mesh.
	 *
	 * @param mesh
	 *            the mesh
	 * @param color
	 *            the color
	 * @param transparency
	 *            the transparency
	 */
	public ItemPointMesh(final List<Point3f> mesh, final Color3f color, final float transparency)
	{
		super(mesh, color, transparency);
	}

	@Override
	protected Appearance createAppearance()
	{
		Appearance appearance = super.createAppearance();
		final PointAttributes pointAttributes = appearance.getPointAttributes();
		// This allows points to support transparency
		pointAttributes.setPointAntialiasingEnable(true);
		return appearance;
	}

	@Override
	protected GeometryArray createGeometry()
	{
		if (mesh == null || mesh.size() == 0)
			return null;
		final List<Point3f> tri = mesh;
		final int size = tri.size();

		final Point3f[] coords = new Point3f[size];
		tri.toArray(coords);

		final Color3f[] colors = new Color3f[size];
		Arrays.fill(colors, color);

		GeometryArray ta = new PointArray(2 * size, GeometryArray.COORDINATES | GeometryArray.COLOR_3);

		ta.setValidVertexCount(size);

		ta.setCoordinates(0, coords);
		ta.setColors(0, colors);

		ta.setCapability(GeometryArray.ALLOW_COLOR_WRITE);
		ta.setCapability(GeometryArray.ALLOW_COORDINATE_WRITE);
		ta.setCapability(GeometryArray.ALLOW_COUNT_WRITE);
		ta.setCapability(GeometryArray.ALLOW_COUNT_READ);
		ta.setCapability(Geometry.ALLOW_INTERSECT);

		return ta;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ij.ij3d.UpdatedableItemMesh#reorder(int[])
	 */
	public void reorder(int[] indices) throws IllegalArgumentException
	{
		checkIndices(indices, mesh.size());
		reorderFast(indices);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see gdsc.smlm.ij.ij3d.UpdatedableItemMesh#reorderFast(int[])
	 */
	public void reorderFast(int[] indices) throws IllegalArgumentException
	{
		changed = true;

		int oldSize = mesh.size();
		int size = (indices == null) ? 0 : Math.min(oldSize, indices.length);

		if (size == 0)
		{
			mesh.clear();
			this.setGeometry(null);
			return;
		}

		// From here on we assume the current geometry will not be null
		// as this only happens when the original size is zero. Size has 
		// been checked at this point to be the smaller of new and old. 
		GeometryArray ga = (GeometryArray) getGeometry();

		// Reorder all things in the geometry: coordinates and colour
		Point3f[] oldCoords = mesh.toArray(new Point3f[oldSize]);
		float[] oldColors = new float[oldSize * 3];
		ga.getColors(0, oldColors);
		final Point3f[] coords = new Point3f[size];
		final Color3f[] colors = new Color3f[size];
		for (int i = 0; i < size; i++)
		{
			int j = indices[i];
			coords[i] = oldCoords[j];
			j *= 3;
			colors[i] = new Color3f(oldColors[j], oldColors[j + 1], oldColors[j + 2]);
		}
		mesh = Arrays.asList(coords);

		ga.updateData(new GeometryUpdater()
		{
			public void updateData(Geometry geometry)
			{
				GeometryArray ga = (GeometryArray) geometry;
				// We re-use the geometry and just truncate the vertex count
				ga.setCoordinates(0, coords);
				ga.setColors(0, colors);
				ga.setValidVertexCount(coords.length);
			}
		});

		//this.setGeometry(ga);
	}

	/**
	 * Check the indices contain a valid natural order of the specifed size
	 *
	 * @param indices
	 *            the indices
	 * @param size
	 *            the size
	 */
	public static void checkIndices(int[] indices, int size)
	{
		if (indices == null || indices.length != size)
			throw new IllegalArgumentException("Indices length do not match the size of the mesh");

		// Check all indices are present.
		// Do a sort and then check it is a natural order
		int[] check = indices.clone();
		Arrays.sort(check);
		for (int i = 0; i < check.length; i++)
			if (check[i] != i)
				throw new IllegalArgumentException("Indices do not contain a valid natural order");
	}
}
