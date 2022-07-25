#if NET35

namespace System
{
    public struct ValueTuple<T1, T2>
    {
        public T1 Item1;
        public T2 Item2;

        public ValueTuple(T1 t1, T2 t2)
        {
            this.Item1 = t1;
            this.Item2 = t2;
        }
    }

    public struct ValueTuple<T1, T2, T3>
    {
        public T1 Item1;
        public T2 Item2;
        public T3 Item3;

        public ValueTuple(T1 t1, T2 t2, T3 t3)
        {
            this.Item1 = t1;
            this.Item2 = t2;
            this.Item3 = t3;
        }
    }

    public struct ValueTuple<T1, T2, T3, T4>
    {
        public T1 Item1;
        public T2 Item2;
        public T3 Item3;
        public T4 Item4;

        public ValueTuple(T1 t1, T2 t2, T3 t3, T4 t4)
        {
            this.Item1 = t1;
            this.Item2 = t2;
            this.Item3 = t3;
            this.Item4 = t4;
        }
    }

    public struct ValueTuple<T1, T2, T3, T4, T5>
    {
        public T1 Item1;
        public T2 Item2;
        public T3 Item3;
        public T4 Item4;
        public T5 Item5;

        public ValueTuple(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5)
        {
            this.Item1 = t1;
            this.Item2 = t2;
            this.Item3 = t3;
            this.Item4 = t4;
            this.Item5 = t5;
        }
    }
}

#endif