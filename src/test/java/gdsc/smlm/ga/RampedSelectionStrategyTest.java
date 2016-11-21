package gdsc.smlm.ga;

import org.junit.Assert;
import org.junit.Test;

import gdsc.core.test.TimingResult;
import gdsc.core.test.TimingService;
import gdsc.core.test.TimingTask;

public class RampedSelectionStrategyTest
{
	@Test
	public void canSearchUsingActualKey()
	{
		long[] sum = RampedSelectionStrategy.createSum(10);

		for (int i = 0; i < sum.length - 1; i++)
		{
			long key = sum[i];
			int j = RampedSelectionStrategy.search(sum, key);
			Assert.assertEquals(i + 1, j);
		}
	}

	@Test
	public void canBinarySearchUsingActualKey()
	{
		long[] sum = RampedSelectionStrategy.createSum(10);

		for (int i = 0; i < sum.length - 1; i++)
		{
			long key = sum[i];
			int j = RampedSelectionStrategy.binarySearch(sum, key);
			Assert.assertEquals(i + 1, j);
		}
	}

	@Test
	public void canSearchUsingNotActualKey()
	{
		long[] sum = RampedSelectionStrategy.createSum(10);

		for (int i = 0; i < sum.length; i++)
		{
			long key = sum[i] - 1;
			int j = RampedSelectionStrategy.search(sum, key);
			Assert.assertEquals(i, j);
		}
	}

	@Test
	public void canBinarySearchUsingNotActualKey()
	{
		long[] sum = RampedSelectionStrategy.createSum(10);

		for (int i = 0; i < sum.length; i++)
		{
			long key = sum[i] - 1;
			int j = RampedSelectionStrategy.binarySearch(sum, key);
			Assert.assertEquals(i, j);
		}
	}

	@Test
	public void binarySearchEqualsSearch()
	{
		long[] sum = RampedSelectionStrategy.createSum(100);
		for (int key = (int) sum[sum.length - 1]; key-- > 0;)
		{
			int i = RampedSelectionStrategy.search(sum, key);
			int j = RampedSelectionStrategy.binarySearch(sum, key);
			Assert.assertEquals(i, j);
		}
	}

	@Test
	public void speedTest50()
	{
		speedTest(50, false, 10);
	}

	@Test
	public void speedTest200()
	{
		speedTest(200, true, 5);
	}

	@Test
	public void speedTest1000()
	{
		speedTest(1000, true, 2);
	}

	// Too slow for common use
	//@Test
	public void speedTest5000()
	{
		speedTest(5000, true, 1);
	}

	private void speedTest(final int size, boolean faster, int runs)
	{
		final long[] sum = RampedSelectionStrategy.createSum(size);

		TimingService ts = new TimingService(runs);

		ts.execute(new TimingTask()
		{
			public Object getData(int i)
			{
				return sum;
			}

			public Object run(Object data)
			{
				for (int key = (int) sum[sum.length - 1]; key-- > 0;)
					RampedSelectionStrategy.search(sum, key);
				return null;
			}

			public void check(int i, Object result)
			{
			}

			public int getSize()
			{
				return 1;
			}

			public String getName()
			{
				return "search" + size;
			}
		});

		ts.execute(new TimingTask()
		{
			public Object getData(int i)
			{
				return sum[i];
			}

			public Object run(Object data)
			{
				for (int key = (int) sum[sum.length - 1]; key-- > 0;)
					RampedSelectionStrategy.binarySearch(sum, key);
				return null;
			}

			public void check(int i, Object result)
			{
			}

			public int getSize()
			{
				return 1;
			}

			public String getName()
			{
				return "binarySearch" + size;
			}
		});

		int n = ts.repeat();
		ts.repeat(n);

		ts.report();

		TimingResult slow = ts.get((faster) ? ts.getSize() - 2 : ts.getSize() - 1);
		TimingResult fast = ts.get((faster) ? ts.getSize() - 1 : ts.getSize() - 2);
		Assert.assertTrue(slow.getMin() > fast.getMin());
	}
}