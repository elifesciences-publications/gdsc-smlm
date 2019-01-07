package uk.ac.sussex.gdsc.smlm.function.gaussian;

import uk.ac.sussex.gdsc.test.api.TestAssertions;
import uk.ac.sussex.gdsc.test.api.TestHelper;
import uk.ac.sussex.gdsc.test.api.function.DoubleDoubleBiPredicate;
import uk.ac.sussex.gdsc.test.junit5.SpeedTag;
import uk.ac.sussex.gdsc.test.utils.TestComplexity;
import uk.ac.sussex.gdsc.test.utils.TestLogUtils;
import uk.ac.sussex.gdsc.test.utils.TestSettings;

import org.apache.commons.math3.util.FastMath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Contains tests for the Gaussian functions in single or double precision
 *
 * <p>The tests show that there is very little (if any) time penalty when using double precision for
 * the calculations. However the precision of the single-precision functions is 1e-4 when using
 * reasonable Gaussian parameters. This could effect the convergence of optimisers/fitters if using
 * single precision math.
 */
@SuppressWarnings({"javadoc"})
public class PrecisionTest {
  private static Logger logger;

  @BeforeAll
  public static void beforeAll() {
    logger = Logger.getLogger(PrecisionTest.class.getName());
  }

  @AfterAll
  public static void afterAll() {
    logger = null;
  }

  int Single = 1;
  int Double = 2;

  private final int MAX_ITER = 200000;

  int maxx = 10;
  // Use realistic values for a camera with a bias of 500
  static double[] params2 = new double[] {500.23, 300.12, 0, 5.12, 5.23, 1.11, 1.11};
  static float[] params1 = toFloat(params2);

  // Stripped down Gaussian functions copied from the
  // uk.ac.sussex.gdsc.smlm.fitting.function.gaussian package
  public abstract class Gaussian {
    public static final int BACKGROUND = 0;
    public static final int AMPLITUDE = 1;
    public static final int ANGLE = 2;
    public static final int X_POSITION = 3;
    public static final int Y_POSITION = 4;
    public static final int X_SD = 5;
    public static final int Y_SD = 6;

    int maxx;

    public Gaussian(int maxx) {
      this.maxx = maxx;
    }

    public void setMaxX(int maxx) {
      this.maxx = maxx;
    }
  }

  public interface DoublePrecision {
    void setMaxX(int maxx);

    void initialise(double[] a);

    double eval(final int x, final double[] dyda);

    double eval(final int x);
  }

  public interface SinglePrecision {
    void setMaxX(int maxx);

    void initialise(float[] a);

    float eval(final int x, final float[] dyda);

    float eval(final int x);
  }

  public class DoubleCircularGaussian extends Gaussian implements DoublePrecision {
    double background;
    double amplitude;
    double x0pos;
    double x1pos;

    double aa;
    double aa2;
    double ax;

    public DoubleCircularGaussian(int maxx) {
      super(maxx);
    }

    @Override
    public void initialise(double[] a) {
      background = a[BACKGROUND];
      amplitude = a[AMPLITUDE];
      x0pos = a[X_POSITION];
      x1pos = a[Y_POSITION];

      final double sx = a[X_SD];
      final double sx2 = sx * sx;
      final double sx3 = sx2 * sx;

      aa = -0.5 / sx2;
      aa2 = -2.0 * aa;

      // For the x-width gradient
      ax = 1.0 / sx3;
    }

    @Override
    public double eval(final int x, final double[] dyda) {
      dyda[0] = 1.0;

      final int x1 = x / maxx;
      final int x0 = x % maxx;

      return background + gaussian(x0, x1, dyda);
    }

    private double gaussian(final int x0, final int x1, final double[] dy_da) {
      final double h = amplitude;

      final double dx = x0 - x0pos;
      final double dy = x1 - x1pos;
      final double dx2dy2 = dx * dx + dy * dy;

      dy_da[1] = FastMath.exp(aa * (dx2dy2));
      final double y = h * dy_da[1];
      final double yaa2 = y * aa2;
      dy_da[2] = yaa2 * dx;
      dy_da[3] = yaa2 * dy;

      dy_da[4] = y * (ax * (dx2dy2));

      return y;
    }

    @Override
    public double eval(final int x) {
      final int x1 = x / maxx;
      final int x0 = x % maxx;

      final double dx = x0 - x0pos;
      final double dy = x1 - x1pos;

      return background + amplitude * FastMath.exp(aa * (dx * dx + dy * dy));
    }
  }

  public class SingleCircularGaussian extends Gaussian implements SinglePrecision {
    float background;
    float amplitude;
    float x0pos;
    float x1pos;

    float aa;
    float aa2;
    float ax;

    public SingleCircularGaussian(int maxx) {
      super(maxx);
    }

    @Override
    public void initialise(float[] a) {
      background = a[BACKGROUND];
      amplitude = a[AMPLITUDE];
      x0pos = a[X_POSITION];
      x1pos = a[Y_POSITION];

      final float sx = a[X_SD];
      final float sx2 = sx * sx;
      final float sx3 = sx2 * sx;

      aa = -0.5f / sx2;
      aa2 = -2.0f * aa;

      ax = 1.0f / sx3;
    }

    @Override
    public float eval(final int x, final float[] dyda) {
      dyda[0] = 1.0f;

      final int x1 = x / maxx;
      final int x0 = x % maxx;

      return background + gaussian(x0, x1, dyda);
    }

    private float gaussian(final int x0, final int x1, final float[] dy_da) {
      final float h = amplitude;

      final float dx = x0 - x0pos;
      final float dy = x1 - x1pos;
      final float dx2dy2 = dx * dx + dy * dy;

      dy_da[1] = (float) FastMath.exp(aa * (dx2dy2));
      final float y = h * dy_da[1];
      final float yaa2 = y * aa2;
      dy_da[2] = yaa2 * dx;
      dy_da[3] = yaa2 * dy;

      dy_da[4] = y * (ax * (dx2dy2));

      return y;
    }

    @Override
    public float eval(final int x) {
      final int x1 = x / maxx;
      final int x0 = x % maxx;

      final float dx = x0 - x0pos;
      final float dy = x1 - x1pos;

      return background + amplitude * (float) (FastMath.exp(aa * (dx * dx + dy * dy)));
    }
  }

  public class DoubleFixedGaussian extends Gaussian implements DoublePrecision {
    double width;

    double background;
    double amplitude;
    double x0pos;
    double x1pos;

    double aa;
    double aa2;

    public DoubleFixedGaussian(int maxx) {
      super(maxx);
    }

    @Override
    public void initialise(double[] a) {
      background = a[BACKGROUND];
      amplitude = a[AMPLITUDE];
      x0pos = a[X_POSITION];
      x1pos = a[Y_POSITION];
      width = a[X_SD];

      final double sx = a[X_SD];
      final double sx2 = sx * sx;

      aa = -0.5 / sx2;
      aa2 = -2.0 * aa;
    }

    @Override
    public double eval(final int x, final double[] dyda) {
      dyda[0] = 1.0;

      final int x1 = x / maxx;
      final int x0 = x % maxx;

      return background + gaussian(x0, x1, dyda);
    }

    private double gaussian(final int x0, final int x1, final double[] dy_da) {
      final double h = amplitude;

      final double dx = x0 - x0pos;
      final double dy = x1 - x1pos;

      dy_da[1] = FastMath.exp(aa * (dx * dx + dy * dy));
      final double y = h * dy_da[1];
      final double yaa2 = y * aa2;
      dy_da[2] = yaa2 * dx;
      dy_da[3] = yaa2 * dy;

      return y;
    }

    @Override
    public double eval(final int x) {
      final int x1 = x / maxx;
      final int x0 = x % maxx;

      final double dx = x0 - x0pos;
      final double dy = x1 - x1pos;

      return background + amplitude * FastMath.exp(aa * (dx * dx + dy * dy));
    }
  }

  public class SingleFixedGaussian extends Gaussian implements SinglePrecision {
    float width;

    float background;
    float amplitude;
    float x0pos;
    float x1pos;

    float aa;
    float aa2;

    public SingleFixedGaussian(int maxx) {
      super(maxx);
    }

    @Override
    public void initialise(float[] a) {
      background = a[BACKGROUND];
      amplitude = a[AMPLITUDE];
      x0pos = a[X_POSITION];
      x1pos = a[Y_POSITION];
      width = a[X_SD];

      final float sx = a[X_SD];
      final float sx2 = sx * sx;

      aa = -0.5f / sx2;
      aa2 = -2.0f * aa;
    }

    @Override
    public float eval(final int x, final float[] dyda) {
      dyda[0] = 1.0f;

      final int x1 = x / maxx;
      final int x0 = x % maxx;

      return background + gaussian(x0, x1, dyda);
    }

    private float gaussian(final int x0, final int x1, final float[] dy_da) {
      final float h = amplitude;

      final float dx = x0 - x0pos;
      final float dy = x1 - x1pos;

      dy_da[1] = (float) (FastMath.exp(aa * (dx * dx + dy * dy)));
      final float y = h * dy_da[1];
      final float yaa2 = y * aa2;
      dy_da[2] = yaa2 * dx;
      dy_da[3] = yaa2 * dy;

      return y;
    }

    @Override
    public float eval(final int x) {
      final int x1 = x / maxx;
      final int x0 = x % maxx;

      final float dx = x0 - x0pos;
      final float dy = x1 - x1pos;

      return background + amplitude * (float) (FastMath.exp(aa * (dx * dx + dy * dy)));
    }
  }

  @Test
  public void circularFunctionPrecisionIs3sf() {
    functionsComputeSameValue(maxx, new SingleCircularGaussian(maxx),
        new DoubleCircularGaussian(maxx), 1e-3);
  }

  @Test
  public void circularFunctionPrecisionIs4sf() {
    functionsComputeSameValue(maxx, new SingleCircularGaussian(maxx),
        new DoubleCircularGaussian(maxx), 1e-4);
  }

  @Test
  public void circularFunctionPrecisionIsNot5sf() {
    Assertions.assertThrows(AssertionError.class, () -> {
      functionsComputeSameValue(maxx, new SingleCircularGaussian(maxx),
          new DoubleCircularGaussian(maxx), 1e-5);
    });
  }

  @Test
  public void circularFunctionsPrecisionIsNot3sfAtLargeXY() {
    int maxx = this.maxx;
    try {
      maxx *= 2;
      while (maxx * maxx < Integer.MAX_VALUE) {
        logger.log(TestLogUtils.getRecord(Level.INFO, "maxx = %d", maxx));
        functionsComputeSameValue(maxx, new SingleCircularGaussian(maxx),
            new DoubleCircularGaussian(maxx), 1e-3);
        maxx *= 2;
      }
    } catch (final AssertionError ex) {
      logger.log(TestLogUtils.getRecord(Level.INFO, ex.getMessage()));
      // ex.printStackTrace();
      return;
    }
    Assertions.fail("Expected different value");
  }

  @SpeedTag
  @Test
  public void circularDoublePrecisionIsFasterWithGradients() {
    isFasterWithGradients(maxx, new SingleCircularGaussian(maxx), new DoubleCircularGaussian(maxx),
        false, true);
  }

  @SpeedTag
  @Test
  public void circularDoublePrecisionIsFaster() {
    isFaster(maxx, new SingleCircularGaussian(maxx), new DoubleCircularGaussian(maxx), false, true);
  }

  @SpeedTag
  @Test
  public void circularDoublePrecisionIsFasterWithGradientsNoSum() {
    isFasterWithGradients(maxx, new SingleCircularGaussian(maxx), new DoubleCircularGaussian(maxx),
        true, true);
  }

  @SpeedTag
  @Test
  public void circularDoublePrecisionIsFasterNoSum() {
    isFaster(maxx, new SingleCircularGaussian(maxx), new DoubleCircularGaussian(maxx), true, true);
  }

  @Test
  public void fixedFunctionPrecisionIs3sf() {
    functionsComputeSameValue(maxx, new SingleFixedGaussian(maxx), new DoubleFixedGaussian(maxx),
        1e-3);
  }

  @Test
  public void fixedFunctionPrecisionIs4sf() {
    functionsComputeSameValue(maxx, new SingleFixedGaussian(maxx), new DoubleFixedGaussian(maxx),
        1e-4);
  }

  @Test
  public void fixedFunctionPrecisionIsNot6sf() {
    Assertions.assertThrows(AssertionError.class, () -> {
      functionsComputeSameValue(maxx, new SingleFixedGaussian(maxx), new DoubleFixedGaussian(maxx),
          1e-6);
    });
  }

  @Test
  public void fixedFunctionsPrecisionIsNot3sfAtLargeXY() {
    int maxx = this.maxx;
    try {
      maxx *= 2;
      while (maxx * maxx < Integer.MAX_VALUE) {
        logger.log(TestLogUtils.getRecord(Level.INFO, "maxx = %d", maxx));
        functionsComputeSameValue(maxx, new SingleFixedGaussian(maxx),
            new DoubleFixedGaussian(maxx), 1e-3);
        maxx *= 2;
      }
    } catch (final AssertionError ex) {
      logger.log(TestLogUtils.getRecord(Level.INFO, ex.getMessage()));
      // ex.printStackTrace();
      return;
    }
    Assertions.fail("Expected different value");
  }

  @SpeedTag
  @Test
  public void fixedDoublePrecisionIsFasterWithGradients() {
    isFasterWithGradients(maxx, new SingleFixedGaussian(maxx), new DoubleFixedGaussian(maxx), false,
        true);
  }

  @SpeedTag
  @Test
  public void fixedDoublePrecisionIsFaster() {
    isFaster(maxx, new SingleFixedGaussian(maxx), new DoubleFixedGaussian(maxx), false, true);
  }

  @SpeedTag
  @Test
  public void fixedDoublePrecisionIsFasterWithGradientsNoSum() {
    isFasterWithGradients(maxx, new SingleFixedGaussian(maxx), new DoubleFixedGaussian(maxx), true,
        true);
  }

  @SpeedTag
  @Test
  public void fixedDoublePrecisionIsFasterNoSum() {
    isFaster(maxx, new SingleFixedGaussian(maxx), new DoubleFixedGaussian(maxx), true, true);
  }

  private static void functionsComputeSameValue(int maxx, SinglePrecision f1, DoublePrecision f2,
      final double precision) {
    f1.setMaxX(maxx);
    f2.setMaxX(maxx);
    final float[] p1 = params1.clone();
    final double[] p2 = params2.clone();
    p1[Gaussian.X_POSITION] = (float) (p2[Gaussian.X_POSITION] = (float) (0.123 + maxx / 2));
    p1[Gaussian.Y_POSITION] = (float) (p2[Gaussian.Y_POSITION] = (float) (0.789 + maxx / 2));
    f1.initialise(p1);
    f2.initialise(p2);
    final int n = p1.length;
    final float[] g1 = new float[n];
    final double[] g2 = new double[n];

    double t1 = 0;
    double t2 = 0;
    final double[] tg1 = new double[n];
    final double[] tg2 = new double[n];

    final DoubleDoubleBiPredicate predicate = TestHelper.doublesAreClose(precision, 0);

    for (int i = 0, limit = maxx * maxx; i < limit; i++) {
      final float v1 = f1.eval(i);
      t1 += v1;
      final double v2 = f2.eval(i);
      t2 += v2;
      TestAssertions.assertTest(v2, v1, predicate, "Different values");
      final float vv1 = f1.eval(i, g1);
      final double vv2 = f2.eval(i, g2);
      Assertions.assertEquals(v1, vv1, "Different f1 values");
      Assertions.assertEquals(v2, vv2, "Different f2 values");
      for (int j = 0; j < n; j++) {
        tg1[j] += g1[j];
        tg2[j] += g2[j];
      }
      TestAssertions.assertArrayTest(g2, toDouble(g1), predicate, "Different gradients");
    }
    TestAssertions.assertArrayTest(tg2, tg1, predicate, "Different total gradients");
    TestAssertions.assertTest(t2, t1, predicate, "Different totals");
  }

  private void isFasterWithGradients(int maxx, SinglePrecision f1, DoublePrecision f2,
      boolean noSum, boolean doubleFaster) {
    Assumptions.assumeTrue(TestSettings.allow(TestComplexity.MEDIUM));

    f1.setMaxX(maxx);
    f2.setMaxX(maxx);
    final float[] p1 = params1.clone();
    final double[] p2 = params2.clone();
    p1[Gaussian.X_POSITION] = (float) (p2[Gaussian.X_POSITION] = (float) (0.123 + maxx / 2));
    p1[Gaussian.Y_POSITION] = (float) (p2[Gaussian.Y_POSITION] = (float) (0.789 + maxx / 2));

    long time1;
    long time2;

    if (noSum) {
      time1 = runSingleWithGradientsNoSum(maxx, f1, p1);
      time1 = runSingleWithGradientsNoSum(maxx, f1, p1);
      time1 += runSingleWithGradientsNoSum(maxx, f1, p1);
      time2 = runDoubleWithGradientsNoSum(maxx, f2, p2);
      time2 = runDoubleWithGradientsNoSum(maxx, f2, p2);
      time2 += runDoubleWithGradientsNoSum(maxx, f2, p2);
    } else {
      time1 = runSingleWithGradients(maxx, f1, p1);
      time1 = runSingleWithGradients(maxx, f1, p1);
      time1 += runSingleWithGradients(maxx, f1, p1);
      time2 = runDoubleWithGradients(maxx, f2, p2);
      time2 = runDoubleWithGradients(maxx, f2, p2);
      time2 += runDoubleWithGradients(maxx, f2, p2);
    }

    Class<?> c1;
    Class<?> c2;
    if (doubleFaster) {
      final long time = time1;
      time1 = time2;
      time2 = time;
      c1 = f2.getClass();
      c2 = f1.getClass();
    } else {
      c1 = f1.getClass();
      c2 = f2.getClass();
    }

    logger.log(
        TestLogUtils.getTimingRecord(((noSum) ? "No sum " : "") + "Gradient " + c1.getSimpleName(),
            time1, c2.getSimpleName(), time2));
  }

  @SuppressWarnings("unused")
  private long runSingleWithGradients(int maxx, SinglePrecision f, float[] p) {
    f.initialise(p);
    final int n = params1.length;
    final float[] g = new float[n];
    final double[] tg = new double[n];

    final int limit = maxx * maxx;

    // Warm up
    for (int j = 0; j < 10; j++) {
      for (int i = 0; i < limit; i++) {
        f.eval(i, g);
      }
    }

    final long time = System.nanoTime();
    double sum = 0;
    for (int j = 0; j < MAX_ITER; j++) {
      sum = 0;
      for (int i = 0; i < limit; i++) {
        sum += f.eval(i, g);
        for (int k = 0; k < n; k++) {
          tg[k] += g[k];
        }
      }
    }
    return System.nanoTime() - time;
  }

  private long runSingleWithGradientsNoSum(int maxx, SinglePrecision f, float[] p) {
    f.initialise(p);
    final float[] g = new float[params1.length];

    final int limit = maxx * maxx;

    // Warm up
    for (int j = 0; j < 10; j++) {
      for (int i = 0; i < limit; i++) {
        f.eval(i, g);
      }
    }

    final long time = System.nanoTime();
    for (int j = 0; j < MAX_ITER; j++) {
      for (int i = 0; i < limit; i++) {
        f.eval(i, g);
      }
    }
    return System.nanoTime() - time;
  }

  @SuppressWarnings("unused")
  private long runDoubleWithGradients(int maxx, DoublePrecision f, double[] p) {
    f.initialise(p);
    final int n = params1.length;
    final double[] g = new double[n];
    final double[] tg = new double[n];

    final int limit = maxx * maxx;

    // Warm up
    for (int j = 0; j < 10; j++) {
      for (int i = 0; i < limit; i++) {
        f.eval(i, g);
      }
    }

    final long time = System.nanoTime();
    double sum = 0;
    for (int j = 0; j < MAX_ITER; j++) {
      sum = 0;
      for (int i = 0; i < limit; i++) {
        sum += f.eval(i, g);
        for (int k = 0; k < n; k++) {
          tg[k] += g[k];
        }
      }
    }
    return System.nanoTime() - time;
  }

  private long runDoubleWithGradientsNoSum(int maxx, DoublePrecision f, double[] p) {
    f.initialise(p);
    final double[] g = new double[params1.length];

    final int limit = maxx * maxx;

    // Warm up
    for (int j = 0; j < 10; j++) {
      for (int i = 0; i < limit; i++) {
        f.eval(i, g);
      }
    }

    final long time = System.nanoTime();
    for (int j = 0; j < MAX_ITER; j++) {
      for (int i = 0; i < limit; i++) {
        f.eval(i, g);
      }
    }
    return System.nanoTime() - time;
  }

  private void isFaster(int maxx, SinglePrecision f1, DoublePrecision f2, boolean noSum,
      boolean doubleFaster) {
    Assumptions.assumeTrue(TestSettings.allow(TestComplexity.MEDIUM));

    f1.setMaxX(maxx);
    f2.setMaxX(maxx);
    final float[] p1 = params1.clone();
    final double[] p2 = params2.clone();
    p1[Gaussian.X_POSITION] = (float) (p2[Gaussian.X_POSITION] = (float) (0.123 + maxx / 2));
    p1[Gaussian.Y_POSITION] = (float) (p2[Gaussian.Y_POSITION] = (float) (0.789 + maxx / 2));

    long time1;
    long time2;
    if (noSum) {
      time1 = runSingleNoSum(maxx, f1, p1);
      time1 = runSingleNoSum(maxx, f1, p1);
      time1 += runSingleNoSum(maxx, f1, p1);
      time2 = runDoubleNoSum(maxx, f2, p2);
      time2 = runDoubleNoSum(maxx, f2, p2);
      time2 += runDoubleNoSum(maxx, f2, p2);
    } else {
      time1 = runSingle(maxx, f1, p1);
      time1 = runSingle(maxx, f1, p1);
      time1 += runSingle(maxx, f1, p1);
      time2 = runDouble(maxx, f2, p2);
      time2 = runDouble(maxx, f2, p2);
      time2 += runDouble(maxx, f2, p2);
    }

    Class<?> c1;
    Class<?> c2;
    if (doubleFaster) {
      final long time = time1;
      time1 = time2;
      time2 = time;
      c1 = f2.getClass();
      c2 = f1.getClass();
    } else {
      c1 = f1.getClass();
      c2 = f2.getClass();
    }

    logger.log(TestLogUtils.getTimingRecord(((noSum) ? "No sum " : "") + c1.getSimpleName(), time1,
        c2.getSimpleName(), time2));
  }

  @SuppressWarnings("unused")
  private long runSingle(int maxx, SinglePrecision f, float[] p) {
    final int limit = maxx * maxx;

    // Warm up
    for (int j = 0; j < 10; j++) {
      f.initialise(p);
      for (int i = 0; i < limit; i++) {
        f.eval(i);
      }
    }

    final long time = System.nanoTime();
    double sum = 0;
    for (int j = 0; j < MAX_ITER; j++) {
      sum = 0;
      f.initialise(p);
      for (int i = 0; i < limit; i++) {
        sum += f.eval(i);
      }
    }
    return System.nanoTime() - time;
  }

  private long runSingleNoSum(int maxx, SinglePrecision f, float[] p) {
    final int limit = maxx * maxx;

    // Warm up
    for (int j = 0; j < 10; j++) {
      f.initialise(p);
      for (int i = 0; i < limit; i++) {
        f.eval(i);
      }
    }

    final long time = System.nanoTime();
    for (int j = 0; j < MAX_ITER; j++) {
      f.initialise(p);
      for (int i = 0; i < limit; i++) {
        f.eval(i);
      }
    }
    return System.nanoTime() - time;
  }

  @SuppressWarnings("unused")
  private long runDouble(int maxx, DoublePrecision f, double[] p) {
    final int limit = maxx * maxx;

    // Warm up
    for (int j = 0; j < 10; j++) {
      f.initialise(p);
      for (int i = 0; i < limit; i++) {
        f.eval(i);
      }
    }

    final long time = System.nanoTime();
    double sum = 0;
    for (int j = 0; j < MAX_ITER; j++) {
      sum = 0;
      f.initialise(p);
      for (int i = 0; i < limit; i++) {
        sum += f.eval(i);
      }
    }
    return System.nanoTime() - time;
  }

  private long runDoubleNoSum(int maxx, DoublePrecision f, double[] p) {
    final int limit = maxx * maxx;

    // Warm up
    for (int j = 0; j < 10; j++) {
      f.initialise(p);
      for (int i = 0; i < limit; i++) {
        f.eval(i);
      }
    }

    final long time = System.nanoTime();
    for (int j = 0; j < MAX_ITER; j++) {
      f.initialise(p);
      for (int i = 0; i < limit; i++) {
        f.eval(i);
      }
    }
    return System.nanoTime() - time;
  }

  private static float[] toFloat(double[] p) {
    final float[] f = new float[p.length];
    for (int i = 0; i < f.length; i++) {
      f[i] = (float) p[i];
    }
    return f;
  }

  private static double[] toDouble(float[] p) {
    final double[] f = new double[p.length];
    for (int i = 0; i < f.length; i++) {
      f[i] = p[i];
    }
    return f;
  }
}
