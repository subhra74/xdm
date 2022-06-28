using System;
using System.Threading;

namespace XDM.Core.Lib.Common
{
    public class CancelFlag
    {
        public static CancelFlag None = new CancelFlag();

        private bool _cancelled = false;
        private ReaderWriterLockSlim locker = new(LockRecursionPolicy.SupportsRecursion);

        public void Cancel()
        {
            try
            {
                locker.EnterWriteLock();
                this._cancelled = true;
            }
            finally
            {
                locker.ExitWriteLock();
            }
        }

        public bool IsCancellationRequested
        {
            get
            {
                try
                {
                    locker.EnterReadLock();
                    return this._cancelled;
                }
                finally
                {
                    locker.ExitReadLock();
                }
            }
        }

        public void ThrowIfCancellationRequested()
        {
            try
            {
                locker.EnterReadLock();
                if (this._cancelled)
                {
                    throw new OperationCanceledException();
                }
            }
            finally
            {
                locker.ExitReadLock();
            }
        }
    }
}
