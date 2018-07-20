/*-
 * #%L
 * Genome Damage and Stability Centre SMLM ImageJ Plugins
 *
 * Software for single molecule localisation microscopy (SMLM)
 * %%
 * Copyright (C) 2011 - 2018 Alex Herbert
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
package uk.ac.sussex.gdsc.smlm.data.config;

import org.apache.commons.math3.random.RandomGenerator;
import org.junit.Assert;
import org.junit.Test;

import uk.ac.sussex.gdsc.smlm.data.config.CalibrationProtos.CameraType;
import uk.ac.sussex.gdsc.smlm.data.config.UnitProtos.AngleUnit;
import uk.ac.sussex.gdsc.smlm.data.config.UnitProtos.DistanceUnit;
import uk.ac.sussex.gdsc.smlm.data.config.UnitProtos.IntensityUnit;
import uk.ac.sussex.gdsc.test.TestSettings;

@SuppressWarnings({ "javadoc" })
public class CalibrationWriterTest
{
	@Test
	public void canWrite()
	{
		final RandomGenerator r = TestSettings.getRandomGenerator();
		for (int i = 0; i < 100; i++)
			canWrite(r);
	}

	private static void canWrite(RandomGenerator r)
	{
		final double qe = r.nextDouble();
		final double bias = 1 + r.nextDouble();
		final double exposureTime = 1 + r.nextDouble();
		final double gain = 1 + r.nextDouble();
		final double nmPerPixel = 1 + r.nextDouble();
		final double readNoise = 1 + r.nextDouble();
		final AngleUnit angleUnit = AngleUnit.values()[r.nextInt(AngleUnit.values().length - 1)];
		final CameraType cameraType = CameraType.values()[r.nextInt(CameraType.values().length - 1)];
		final DistanceUnit distanceUnit = DistanceUnit.values()[r.nextInt(DistanceUnit.values().length - 1)];
		final IntensityUnit intensityUnit = IntensityUnit.values()[r.nextInt(IntensityUnit.values().length - 1)];

		final CalibrationWriter writer = new CalibrationWriter();

		Assert.assertEquals(writer.getQuantumEfficiency(), 0, 0);
		Assert.assertEquals(writer.getBias(), 0, 0);
		Assert.assertEquals(writer.getExposureTime(), 0, 0);
		Assert.assertEquals(writer.getCountPerPhoton(), 0, 0);
		Assert.assertEquals(writer.getNmPerPixel(), 0, 0);
		Assert.assertEquals(writer.getReadNoise(), 0, 0);
		Assert.assertFalse(writer.hasQuantumEfficiency());
		Assert.assertFalse(writer.hasBias());
		Assert.assertFalse(writer.hasExposureTime());
		Assert.assertFalse(writer.hasCountPerPhoton());
		Assert.assertFalse(writer.hasNmPerPixel());
		Assert.assertFalse(writer.hasReadNoise());
		Assert.assertEquals(writer.getAngleUnit(), AngleUnit.ANGLE_UNIT_NA);
		Assert.assertEquals(writer.getCameraType(), CameraType.CAMERA_TYPE_NA);
		Assert.assertEquals(writer.getDistanceUnit(), DistanceUnit.DISTANCE_UNIT_NA);
		Assert.assertEquals(writer.getIntensityUnit(), IntensityUnit.INTENSITY_UNIT_NA);

		writer.setQuantumEfficiency(qe);
		writer.setBias(bias);
		writer.setExposureTime(exposureTime);
		writer.setCountPerPhoton(gain);
		writer.setNmPerPixel(nmPerPixel);
		writer.setReadNoise(readNoise);
		writer.setAngleUnit(angleUnit);
		writer.setCameraType(cameraType);
		writer.setDistanceUnit(distanceUnit);
		writer.setIntensityUnit(intensityUnit);

		Assert.assertEquals(writer.getQuantumEfficiency(), qe, 0);
		Assert.assertEquals(writer.getBias(), bias, 0);
		Assert.assertEquals(writer.getExposureTime(), exposureTime, 0);
		Assert.assertEquals(writer.getCountPerPhoton(), gain, 0);
		Assert.assertEquals(writer.getNmPerPixel(), nmPerPixel, 0);
		Assert.assertEquals(writer.getReadNoise(), readNoise, 0);
		Assert.assertTrue(writer.hasQuantumEfficiency());
		Assert.assertTrue(writer.hasBias());
		Assert.assertTrue(writer.hasExposureTime());
		Assert.assertTrue(writer.hasCountPerPhoton());
		Assert.assertTrue(writer.hasNmPerPixel());
		Assert.assertTrue(writer.hasReadNoise());
		Assert.assertEquals(writer.getAngleUnit(), angleUnit);
		Assert.assertEquals(writer.getCameraType(), cameraType);
		Assert.assertEquals(writer.getDistanceUnit(), distanceUnit);
		Assert.assertEquals(writer.getIntensityUnit(), intensityUnit);

		final CalibrationReader reader = new CalibrationReader(writer.getCalibration());

		Assert.assertEquals(reader.getQuantumEfficiency(), qe, 0);
		Assert.assertEquals(reader.getBias(), bias, 0);
		Assert.assertEquals(reader.getExposureTime(), exposureTime, 0);
		Assert.assertEquals(reader.getCountPerPhoton(), gain, 0);
		Assert.assertEquals(reader.getNmPerPixel(), nmPerPixel, 0);
		Assert.assertEquals(reader.getReadNoise(), readNoise, 0);
		Assert.assertTrue(reader.hasQuantumEfficiency());
		Assert.assertTrue(reader.hasBias());
		Assert.assertTrue(reader.hasExposureTime());
		Assert.assertTrue(reader.hasCountPerPhoton());
		Assert.assertTrue(reader.hasNmPerPixel());
		Assert.assertTrue(reader.hasReadNoise());
		Assert.assertEquals(reader.getAngleUnit(), angleUnit);
		Assert.assertEquals(reader.getCameraType(), cameraType);
		Assert.assertEquals(reader.getDistanceUnit(), distanceUnit);
		Assert.assertEquals(reader.getIntensityUnit(), intensityUnit);
	}
}