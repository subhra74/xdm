using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;


namespace XDM.Core.Lib.Common.Segmented
{
    public class CountdownLatch
    {
        private ManualResetEvent Latch { get; } = new ManualResetEvent(false);
        private int counter;

        public CountdownLatch(int counter)
        {
            this.counter = counter;
        }

        public void CountDown()
        {
            Interlocked.Decrement(ref counter);
            if (counter == 0) this.Latch.Set();
        }

        public void Wait()
        {
            Latch.WaitOne();
        }

        public void Break()
        {
            Latch.Set();
        }
    }
}
