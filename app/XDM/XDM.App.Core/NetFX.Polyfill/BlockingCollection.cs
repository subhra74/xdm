#if NET35

using System.Collections.Generic;
using System.Threading;

namespace NetFX.Polyfill
{
    public class BlockingCollection<T>
    {
        private object _queueLock = new();
        private Queue<T> _queue = new();
        private AutoResetEvent _objectAvailableEvent = new(false);

        public T Take()
        {
            lock (_queueLock)
            {
                if (_queue.Count > 0)
                    return _queue.Dequeue();
            }

            _objectAvailableEvent.WaitOne();

            return Take();
        }

        public void Add(T obj)
        {
            lock (_queueLock)
            {
                _queue.Enqueue(obj);
            }

            _objectAvailableEvent.Set();
        }
    }
}

#endif
